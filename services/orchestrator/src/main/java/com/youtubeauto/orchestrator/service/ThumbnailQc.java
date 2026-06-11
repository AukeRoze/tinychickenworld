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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The SQUINT-TEST scorer (assembly-audit #11): ranks the generated thumbnail
 * variants the way a scrolling viewer experiences them — tiny, for one second,
 * on a phone. The best variant becomes the preselected default so the human
 * gate starts from the strongest CTR candidate instead of from variant 0; the
 * reviewer can still override in the gate. Fail-safe: any error returns null
 * and the existing default stays.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailQc {

    private static final String TOOL = "emit_thumbnail_rank";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["bestVariant","scores"],
              "properties":{
                "bestVariant":{"type":"integer"},
                "scores":{
                  "type":"array",
                  "items":{
                    "type":"object","additionalProperties":false,
                    "required":["variant","score"],
                    "properties":{
                      "variant":{"type":"integer"},
                      "score":{"type":"integer","minimum":0,"maximum":100},
                      "note":{"type":"string","maxLength":120}
                    }
                  }
                }
              }
            }
            """;

    private static final String SYSTEM = """
            You judge YouTube thumbnails for a kids channel (viewers 3-6, the
            CLICK decision is often made by a parent scrolling on a phone).
            You see several VARIANTS of the same episode's thumbnail. Judge each
            as if it were 120 pixels wide and on screen for ONE second:
              - FACE: is a character's face big, sharp and emotionally readable?
                Small faces or characters seen from afar score low.
              - ONE RIDDLE: is there exactly one clear visual question (an egg,
                a puddle, a mystery object)? Clutter or multiple focal points
                score low.
              - POP: contrast and colour separation from a busy feed; washed-out
                or uniformly warm images score low.
              - CLEAN: nothing important cut off at the edges; readable at a
                squint; no accidental text artefacts.
            Score each variant 0-100 and pick bestVariant (the variant NUMBER
            given in its label, not its position). Be decisive — ties are not
            allowed. Always emit via emit_thumbnail_rank.
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Result(int bestVariant, Map<Integer, Integer> scores) {}

    /** @param variants variant number → PNG path (≥2 entries required). */
    public Result rank(Map<Integer, Path> variants, String title) {
        try {
            if (variants == null || variants.size() < 2) return null;

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 600);
            body.put("system", SYSTEM);

            ArrayNode messages = body.putArray("messages");
            ObjectNode um = messages.addObject();
            um.put("role", "user");
            ArrayNode content = um.putArray("content");
            content.addObject().put("type", "text").put("text",
                    "Episode title: " + (title == null ? "(unknown)" : title)
                    + "\nRank these thumbnail variants. emit_thumbnail_rank.");
            for (Map.Entry<Integer, Path> e : variants.entrySet()) {
                content.addObject().put("type", "text")
                        .put("text", "VARIANT " + e.getKey() + ":");
                ObjectNode img = content.addObject();
                img.put("type", "image");
                ObjectNode src = img.putObject("source");
                src.put("type", "base64");
                src.put("media_type", "image/png");
                src.put("data", Base64.getEncoder()
                        .encodeToString(Files.readAllBytes(e.getValue())));
            }

            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL);
            tool.put("description", "Emit the thumbnail squint-test ranking.");
            tool.set("input_schema", mapper.readTree(SCHEMA));
            ObjectNode tc = body.putObject("tool_choice");
            tc.put("type", "tool");
            tc.put("name", TOOL);

            JsonNode resp = anthropicWebClient.post().uri("/messages")
                    .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
            if (resp == null) return null;
            for (JsonNode b : resp.path("content")) {
                if (!"tool_use".equals(b.path("type").asText())) continue;
                JsonNode in = b.path("input");
                int best = in.path("bestVariant").asInt(-1);
                Map<Integer, Integer> scores = new LinkedHashMap<>();
                for (JsonNode s : in.path("scores")) {
                    scores.put(s.path("variant").asInt(), s.path("score").asInt());
                }
                if (!variants.containsKey(best)) return null;   // hallucinated number
                return new Result(best, scores);
            }
            return null;
        } catch (Exception e) {
            log.warn("Thumbnail QC failed (keeping existing default): {}", e.getMessage());
            return null;
        }
    }
}
