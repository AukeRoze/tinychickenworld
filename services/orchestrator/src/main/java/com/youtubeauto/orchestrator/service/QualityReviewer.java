package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.client.AssemblyServiceClient;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.domain.VideoAudit;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAuditRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.UUID;

/**
 * AI quality reviewer — runs after the master MP4 is assembled, BEFORE the
 * upload review gate. Asks Claude (vision) to score and find issues, so the
 * human reviewer sees the AI critique alongside the video.
 *
 * Pipeline:
 *   1. POST assembly-service /api/v1/audit/keyframes → 8 PNGs in workdir
 *   2. base64 each frame, send as multimodal content blocks to Claude
 *   3. Force tool-use schema → structured findings
 *   4. Persist VideoAudit
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityReviewer {

    private static final String TOOL = "emit_audit";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["score","character_drift","audio_balance","framing","branding","findings"],
              "properties":{
                "score":{"type":"integer","minimum":0,"maximum":100},
                "character_drift":{"type":"integer","minimum":0,"maximum":100,
                    "description":"100 = cast looks identical across all frames"},
                "audio_balance":{"type":"integer","minimum":0,"maximum":100,
                    "description":"100 = voice clearly above music (judge from waveform cues / mouth movement)"},
                "framing":{"type":"integer","minimum":0,"maximum":100,
                    "description":"100 = subject centered + safe zones respected"},
                "branding":{"type":"integer","minimum":0,"maximum":100,
                    "description":"100 = palette + style consistent with banner"},
                "findings":{"type":"array","maxItems":12,"items":{
                    "type":"object","additionalProperties":false,
                    "required":["severity","area","message"],
                    "properties":{
                        "severity":{"enum":["critical","major","minor"]},
                        "area":{"type":"string","maxLength":40},
                        "message":{"type":"string","maxLength":280}
                    }
                }}
              }
            }
            """;

    private static final String SYSTEM = """
            You are the QA reviewer for "Tiny Chicken World", a kids YouTube
            cartoon (ages 3-6). The cast (banner-true): Pip (cream-white chick +
            straw farmer hat + red bandana), Mo (blue-grey chick + red knitted
            scarf), Bo (tan chick + round eyeglasses + green scarf). Visual style
            is warm pastoral countryside, golden-hour palette.

            You are shown 6-12 keyframes from a finished video. Be a strict
            critic — kids notice EVERYTHING. Score harshly:
              90+ = ship it
              75-89 = ship with caveats
              <75 = needs rework

            Look hard for:
              - Character drift: scarves the wrong colour, hats missing, eye
                glasses changed, extra limbs, anatomy mistakes.
              - Composition: subject cut off, awkward crops, vertical safe zone.
              - Style break: photo-real frames, hyper-detailed textures that
                break the soft pastoral look.
              - Branding: palette drift, missing brand chrome.

            Emit findings as critical/major/minor. Don't pad — 0 findings is
            valid if everything is good. Always emit via emit_audit.
            """;

    private final WebClient anthropicWebClient;
    private final AssemblyServiceClient assemblyClient;
    private final VideoJobRepository jobRepo;
    private final VideoAuditRepository auditRepo;
    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Run an audit on the given finished job. Best-effort — never throws. */
    public VideoAudit auditJob(UUID jobId) {
        try {
            VideoJob job = jobRepo.findById(jobId).orElseThrow();
            if (job.getVideoPath() == null || job.getVideoPath().isBlank()) {
                log.warn("Audit skipped — job {} has no videoPath", jobId);
                return null;
            }
            JsonNode kf = assemblyClient.auditKeyframes(jobId, job.getVideoPath(), 8);
            if (kf == null) return null;

            // DETERMINISTIC GATE — edge scan for black pillarbox/letterbox bars.
            // Runs before (and independently of) the vision review: a pixel scan
            // never misses a 14px bar, a vision model sometimes does. On a hit
            // the audit score is hard-capped below the ship threshold and a
            // critical finding is injected, so Auto-Fix / the human gate always
            // see it. (Root-cause fix lives in assembly's Concatenator blur-fill
            // + baked-bar strip; this gate catches any regression.)
            java.util.List<Path> framePaths = new java.util.ArrayList<>();
            for (JsonNode f : kf.path("frames")) {
                framePaths.add(Paths.get(f.path("path").asText()));
            }
            EdgeBarsCheck.Result edge = EdgeBarsCheck.scan(framePaths);
            if (edge.failed()) {
                log.error("Job {} EDGE GATE FAIL — black bars on {}/{} keyframes (max {}px): {}",
                        jobId, edge.framesWithBars(), edge.framesChecked(),
                        edge.maxBarPx(), edge.detail());
            }

            // DETERMINISTIC GATE — render checks (duration / dead-air / black
            // frames) via one assembly-service decode pass. The master includes
            // the branded intro+outro (~10-25s on top of the BODY target), so
            // too-short is judged strictly (master < 90% of target = the body
            // undershot badly) while too-long gets brand headroom.
            JsonNode rc = assemblyClient.renderChecks(jobId, job.getVideoPath());
            double masterDur = rc == null ? -1 : rc.path("durationSeconds").asDouble(-1);
            int target = job.getTargetSeconds();
            boolean durTooShort = masterDur > 0 && target > 0 && masterDur < target * 0.90;
            boolean durTooLong  = masterDur > 0 && target > 0 && masterDur > target * 1.20 + 30;
            int silenceCount  = rc == null ? 0 : rc.path("silences").size();
            int blackoutCount = rc == null ? 0 : rc.path("blackouts").size();
            if (durTooShort || durTooLong) {
                log.error("Job {} DURATION GATE FAIL — master {}s vs target {}s",
                        jobId, masterDur, target);
            }
            if (blackoutCount > 0 || silenceCount > 0) {
                log.warn("Job {} render checks: {} blackout(s), {} silence span(s)",
                        jobId, blackoutCount, silenceCount);
            }

            ArrayNode contentBlocks = mapper.createArrayNode();
            int n = 0;
            for (JsonNode f : kf.path("frames")) {
                Path p = Paths.get(f.path("path").asText());
                if (!Files.exists(p)) continue;
                byte[] bytes = Files.readAllBytes(p);
                ObjectNode img = contentBlocks.addObject();
                img.put("type", "image");
                ObjectNode src = img.putObject("source");
                src.put("type", "base64");
                src.put("media_type", "image/png");
                src.put("data", Base64.getEncoder().encodeToString(bytes));
                n++;
            }
            if (n == 0) {
                log.warn("Audit aborted — no frames retrievable for job {}", jobId);
                return null;
            }
            contentBlocks.addObject()
                    .put("type", "text")
                    .put("text", "Title: " + safe(job.getMetadataTitle())
                            + "\nLesson: " + safe(job.getLesson())
                            + "\nDuration: " + kf.path("durationSeconds").asDouble() + "s"
                            + "\n\nReview these " + n + " keyframes and emit_audit.");

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 2000);
            body.put("system", SYSTEM);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.set("content", contentBlocks);

            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL);
            tool.put("description", "Emit audit findings.");
            tool.set("input_schema", mapper.readTree(SCHEMA));
            ObjectNode toolChoice = body.putObject("tool_choice");
            toolChoice.put("type", "tool");
            toolChoice.put("name", TOOL);

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (resp == null) return null;
            for (JsonNode block : resp.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    JsonNode input = block.path("input");
                    // Always INSERT a new row so the audit history is kept
                    // (e.g. 78 → 84 → 88 across Auto-Fix passes), not overwritten.
                    VideoAudit audit = VideoAudit.builder().videoJobId(jobId).build();
                    int score = input.path("score").asInt(0);
                    int framing = input.path("framing").asInt(0);
                    JsonNode findingsNode = input.path("findings");
                    if (edge.failed()) {
                        // Hard gate: bars = never "ship it", whatever the LLM said.
                        score = Math.min(score, 50);
                        framing = Math.min(framing, 40);
                        injectFinding(findingsNode, "critical", "edge-bars", String.format(
                                "Deterministic edge scan: black pillarbox/letterbox bars on %d/%d keyframes (max %dpx). %s",
                                edge.framesWithBars(), edge.framesChecked(),
                                edge.maxBarPx(), truncate(edge.detail(), 160)));
                    }
                    if (durTooShort || durTooLong) {
                        // Hard gate: a 138s master on a 180s format never ships.
                        score = Math.min(score, 50);
                        injectFinding(findingsNode, "critical", "duration", String.format(
                                "Master is %.1fs but the format target is %ds (%+.0f%%). %s",
                                masterDur, target, (masterDur - target) / target * 100,
                                durTooShort ? "Body undershot the beat-sheet badly."
                                            : "Master overran the format."));
                    }
                    if (blackoutCount > 0) {
                        score = Math.min(score, 50);
                        injectFinding(findingsNode, "critical", "black-frames", String.format(
                                "blackdetect: %d black segment(s) of ≥0.5s in the master (first at %ss).",
                                blackoutCount, rc.path("blackouts").path(0).path("start").asText("?")));
                    }
                    if (silenceCount > 0) {
                        score = Math.min(score, 65);
                        injectFinding(findingsNode, "major", "dead-air", String.format(
                                "silencedetect: %d span(s) of ≥1.5s near-silence (first at %ss) — kids drop off in dead air.",
                                silenceCount, rc.path("silences").path(0).path("start").asText("?")));
                    }
                    audit.setScore(score);
                    audit.setCharacterDrift(input.path("character_drift").asInt(0));
                    audit.setAudioBalance(input.path("audio_balance").asInt(0));
                    audit.setFraming(framing);
                    audit.setBranding(input.path("branding").asInt(0));
                    audit.setFindings(findingsNode.toString());
                    audit.setFramesInspected(n);
                    audit.setModel(props.anthropic().model());
                    VideoAudit saved = auditRepo.save(audit);
                    log.info("Audit for job {} → score={} findings={}",
                            jobId, saved.getScore(), input.path("findings").size());
                    return saved;
                }
            }
            return null;
        } catch (Exception e) {
            log.warn("Quality audit FAILED for job {}: {}", jobId, e.getMessage(), e);
            return null;
        }
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Prepends a deterministic finding so it always outranks LLM nitpicks. */
    private static void injectFinding(JsonNode findingsNode,
                                      String severity, String area, String message) {
        if (findingsNode instanceof ArrayNode arr) {
            ObjectNode fnd = arr.insertObject(0);
            fnd.put("severity", severity);
            fnd.put("area", area);
            fnd.put("message", message);
        }
    }
}
