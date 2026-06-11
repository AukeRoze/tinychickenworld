package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Vision QC for GENERATED VEO CLIPS — the missing output-side gate. The stills
 * are QC'd by {@link SceneImageQc} before Veo runs, but until now NOBODY looked
 * at what Veo actually produced: a clip where a chick morphs, vanishes
 * mid-shot, or where an extra chicken wanders in went straight to assembly.
 *
 * The video-generation-service drops three sample frames (first / middle /
 * last — {@code qc_first.png}, {@code qc_mid.png}, {@code qc_last.png}) next to
 * every {@code clip.mp4} on the shared workdir. This checker shows all three to
 * Claude vision IN ORDER and asks the questions a prompt can't guarantee:
 *
 *   1. HEADCOUNT — every frame contains exactly the listed cast, no extras.
 *   2. PRESENCE  — nobody disappears between first and last frame.
 *   3. IDENTITY  — accessories/colours stay on the right character across
 *                  frames (the classic image-to-video accessory swap).
 *
 * Fail-safe like SceneImageQc: any error (missing frames, API hiccup) returns
 * PASS so QC never blocks the pipeline; the human review gate is the backstop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClipQc {

    /** QC frame filenames written by video-generation-service FrameExtractor. */
    public static final String[] QC_FRAMES = {"qc_first.png", "qc_mid.png", "qc_last.png"};

    private static final String TOOL = "emit_clip_qc";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["ok","issues"],
              "properties":{
                "ok":{"type":"boolean","description":"true if the clip is good enough to publish"},
                "issues":{"type":"array","maxItems":6,"items":{"type":"string","maxLength":160}}
              }
            }
            """;

    private static final String SYSTEM = """
            You are a strict QC checker for an AI-animated kids cartoon (ages 3-6).
            You may first be shown OFFICIAL REFERENCE images — the approved,
            canonical look of each character. Judge the clip frames AGAINST
            those references (colours, accessories, proportions, face), not
            against your own idea of the characters. Then you see 2-3 frames
            sampled IN ORDER (first, middle, last) from ONE generated video
            clip, plus the cast the clip must contain.

            Set ok=false ONLY for these clear failures:
              - HEADCOUNT: any frame shows MORE characters than the listed cast
                (an extra chicken/animal appeared) or FEWER (a listed character
                is entirely absent from a frame it should be in).
              - DISAPPEARANCE: a character present in the first frame is gone in
                a later frame (vanished / faded / left the shot) — the cast must
                stay in frame for the whole clip.
              - DUPLICATION: the same character appears twice in one frame.
              - IDENTITY DRIFT between frames: a character's signature accessory
                (straw hat / bandana / scarf / round eyeglasses) disappears,
                changes, or MOVES TO ANOTHER character between frames; or a
                character's body colour clearly changes between frames.
              - DEFORMATION: a character is clearly melted, warped, has extra
                limbs/wings, or is otherwise broken in any frame.
              - TEXT: rendered text, captions or watermarks anywhere.

            Judge ACROSS the frames (they are the same shot over time), not each
            frame in isolation. Slight pose/size differences from motion or
            camera move are NORMAL — do not flag them. A character partially out
            of frame due to an intentional camera move is fine if clearly the
            same shot. When unsure, lean towards ok=true.
            List concrete issues. Always emit via emit_clip_qc.
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final CharacterRefStills refStills;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Result(boolean ok, List<String> issues) {
        public static Result pass() { return new Result(true, List.of()); }
    }

    /**
     * @param clip          path to the scene's clip.mp4 (QC frames are read from
     *                      its parent directory)
     * @param expectedChars DNA lines like "Pip: cream-white chick wearing a straw
     *                      farmer hat and a red neck bandana"
     */
    /** Back-compat: QC without reference images. */
    public Result check(Path clip, List<String> expectedChars) {
        return check(clip, expectedChars, List.of());
    }

    /** @param charIds cast ids — their approved reference stills (bible/refs)
     *  are shown to the checker FIRST, so drift is judged against the actual
     *  canonical pixels (the same refs Veo receives as asset references). */
    public Result check(Path clip, List<String> expectedChars, List<String> charIds) {
        try {
            if (clip == null || clip.getParent() == null) return Result.pass();
            List<Path> frames = new ArrayList<>();
            for (String f : QC_FRAMES) {
                Path p = clip.getParent().resolve(f);
                if (Files.exists(p)) frames.add(p);
            }
            // Need at least first+last to judge presence over time.
            if (frames.size() < 2) {
                log.info("Clip QC skipped for {} — only {} QC frame(s) found", clip, frames.size());
                return Result.pass();
            }

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 500);
            body.put("system", SYSTEM);

            ArrayNode messages = body.putArray("messages");
            ObjectNode um = messages.addObject();
            um.put("role", "user");
            ArrayNode content = um.putArray("content");
            // Ground truth first: the approved canonical look per character.
            for (CharacterRefStills.RefStill ref : refStills.resolve(charIds)) {
                try {
                    content.addObject().put("type", "text").put("text",
                            "OFFICIAL REFERENCE — the approved canonical look of '"
                            + ref.characterId() + "':");
                    ObjectNode rimg = content.addObject();
                    rimg.put("type", "image");
                    ObjectNode rsrc = rimg.putObject("source");
                    rsrc.put("type", "base64");
                    rsrc.put("media_type", "image/png");
                    rsrc.put("data", Base64.getEncoder()
                            .encodeToString(Files.readAllBytes(ref.path())));
                } catch (Exception refErr) {
                    log.debug("Clip QC ref {} unreadable ({}) — skipping",
                            ref.path(), refErr.getMessage());
                }
            }
            for (int i = 0; i < frames.size(); i++) {
                content.addObject().put("type", "text")
                        .put("text", "Frame " + (i + 1) + " of " + frames.size()
                                + " (in chronological order):");
                ObjectNode img = content.addObject();
                img.put("type", "image");
                ObjectNode src = img.putObject("source");
                src.put("type", "base64");
                src.put("media_type", "image/png");
                src.put("data", Base64.getEncoder()
                        .encodeToString(Files.readAllBytes(frames.get(i))));
            }
            content.addObject().put("type", "text").put("text",
                    "Cast this clip must contain in EVERY frame — exactly "
                    + expectedChars.size() + " character(s), no extras:\n"
                    + String.join("\n", expectedChars)
                    + "\n\nCheck headcount per frame, disappearance across frames,"
                    + " duplication, accessory/colour drift between frames,"
                    + " deformation and text. emit_clip_qc.");

            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL);
            tool.put("description", "Emit the clip QC verdict.");
            tool.set("input_schema", mapper.readTree(SCHEMA));
            ObjectNode tc = body.putObject("tool_choice");
            tc.put("type", "tool");
            tc.put("name", TOOL);

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (resp == null) return Result.pass();
            for (JsonNode b : resp.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) {
                    JsonNode in = b.path("input");
                    List<String> issues = new ArrayList<>();
                    in.path("issues").forEach(i -> issues.add(i.asText()));
                    return new Result(in.path("ok").asBoolean(true), issues);
                }
            }
            return Result.pass();
        } catch (Exception e) {
            log.warn("Clip QC failed (treating as pass): {}", e.getMessage());
            return Result.pass();
        }
    }
}
