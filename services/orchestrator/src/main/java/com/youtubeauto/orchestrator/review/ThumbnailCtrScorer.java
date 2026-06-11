package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;

/**
 * Role 8 — Thumbnail CTR Director, as a vision scorer. Shows the chosen
 * thumbnail to Claude and scores it on the levers that drive click-through for
 * a kids channel: a big readable emotional face, strong contrast/pop, mobile
 * legibility (it works tiny), and a clear single focal subject. Target: 10%+ CTR.
 *
 * Fail-safe: any error returns a NEUTRAL score (60) so the QA Board never breaks
 * just because the thumbnail couldn't be judged.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ThumbnailCtrScorer {

    public record Result(int score, List<String> notes) {}

    private static final int NEUTRAL = 60;
    private static final String TOOL = "emit_thumbnail_score";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["ctr","expression","contrast","mobileReadability","focalClarity","issues"],
              "properties":{
                "ctr":{"type":"integer","minimum":0,"maximum":100,
                    "description":"Overall click-through potential for a kids audience. 80+ = scroll-stopping."},
                "expression":{"type":"integer","minimum":0,"maximum":100,
                    "description":"Big, readable, EXTREME emotion on the character's face."},
                "contrast":{"type":"integer","minimum":0,"maximum":100,
                    "description":"Colour pop / subject-background separation."},
                "mobileReadability":{"type":"integer","minimum":0,"maximum":100,
                    "description":"Still reads instantly at tiny mobile size — one clear idea, not busy."},
                "focalClarity":{"type":"integer","minimum":0,"maximum":100,
                    "description":"One obvious focal subject, large in frame, not cluttered."},
                "issues":{"type":"array","maxItems":6,"items":{"type":"string","maxLength":160}}
              }
            }
            """;
    private static final String SYSTEM = """
            You are a YouTube Thumbnail CTR Director for a kids cartoon channel
            (ages 3-6). You are shown ONE thumbnail. Score it for click-through.
            Top kids thumbnails have: ONE big character face filling 50-80% of the
            frame, an EXTREME readable emotion (huge eyes, open-mouth gasp, joy),
            strong colour contrast that pops on a white feed, a single clear focal
            subject, and instant legibility at tiny mobile size. Penalise: small
            or distant characters, busy/cluttered scenes, muddy contrast, flat
            neutral expressions, unreadable-when-tiny compositions, stray text.
            Be strict — most thumbnails are mediocre. Reserve 80+ for genuinely
            scroll-stopping frames. Always emit via emit_thumbnail_score.
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public Result evaluate(String thumbnailPath) {
        if (thumbnailPath == null || thumbnailPath.isBlank()) {
            return new Result(NEUTRAL, List.of("No thumbnail to score (neutral)"));
        }
        try {
            Path p = Paths.get(thumbnailPath);
            if (!Files.exists(p)) {
                return new Result(NEUTRAL, List.of("Thumbnail file missing (neutral)"));
            }
            byte[] bytes = Files.readAllBytes(p);

            ArrayNode content = mapper.createArrayNode();
            ObjectNode img = content.addObject();
            img.put("type", "image");
            ObjectNode src = img.putObject("source");
            src.put("type", "base64");
            src.put("media_type", "image/png");
            src.put("data", Base64.getEncoder().encodeToString(bytes));
            content.addObject().put("type", "text")
                    .put("text", "Score this thumbnail for kids-channel CTR. emit_thumbnail_score.");

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 1000);
            body.put("system", SYSTEM);
            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.set("content", content);
            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL);
            tool.put("description", "Emit thumbnail CTR scores.");
            tool.set("input_schema", mapper.readTree(SCHEMA));
            ObjectNode toolChoice = body.putObject("tool_choice");
            toolChoice.put("type", "tool");
            toolChoice.put("name", TOOL);

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (resp == null) return new Result(NEUTRAL, List.of("No response (neutral)"));
            for (JsonNode block : resp.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    JsonNode in = block.path("input");
                    int ctr = in.path("ctr").asInt(NEUTRAL);
                    List<String> notes = List.of(
                            "CTR " + ctr + "/100",
                            "Expression " + in.path("expression").asInt(0) + "/100",
                            "Contrast " + in.path("contrast").asInt(0) + "/100",
                            "Mobile " + in.path("mobileReadability").asInt(0) + "/100",
                            "Focal " + in.path("focalClarity").asInt(0) + "/100"
                    );
                    log.info("Thumbnail CTR score = {}", ctr);
                    return new Result(Math.max(0, Math.min(100, ctr)), notes);
                }
            }
            return new Result(NEUTRAL, List.of("No tool_use (neutral)"));
        } catch (Exception e) {
            log.warn("Thumbnail CTR scoring failed (neutral): {}", e.getMessage());
            return new Result(NEUTRAL, List.of("Scoring error (neutral): " + e.getMessage()));
        }
    }
}
