package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Translates English script content to Dutch via Claude. Used by the
 * review-preview pages so the reviewer can read both sides at once.
 *
 * Single Claude call per script (all scenes batched), result cached in
 * memory keyed by script-id so a page reload doesn't re-translate.
 */
@Slf4j
@Service
public class TranslationService {

    private static final String SYSTEM_PROMPT = """
            You are a translator. Translate the given English texts to natural,
            child-friendly Dutch. Match the warm, curious tone of a kids' show.
            Always emit via the translate tool — never freeform text.
            """;

    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["scenes"],
              "properties":{
                "title":{"type":"string"},
                "hook":{"type":"string"},
                "scenes":{
                  "type":"array",
                  "items":{
                    "type":"object","additionalProperties":false,
                    "required":["seq","visualDesc","narration"],
                    "properties":{
                      "seq":{"type":"integer"},
                      "visualDesc":{"type":"string"},
                      "narration":{"type":"string"}
                    }
                  }
                }
              }
            }
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, JsonNode> cache = new ConcurrentHashMap<>();

    public TranslationService(WebClient anthropicWebClient, OrchestratorProperties props) {
        this.anthropicWebClient = anthropicWebClient;
        this.props = props;
    }

    /**
     * Translate a script body (as returned by script-service: title, hook, scenes).
     * Returns a JsonNode mirroring the input structure with all strings translated.
     * On error returns null — caller should fall back to showing English only.
     */
    public JsonNode translateScript(String cacheKey, JsonNode scriptBody) {
        if (scriptBody == null || scriptBody.isMissingNode() || scriptBody.isNull()) return null;
        if (cacheKey != null && cache.containsKey(cacheKey)) return cache.get(cacheKey);

        try {
            ObjectNode payload = mapper.createObjectNode();
            payload.put("title", scriptBody.path("title").asText(""));
            payload.put("hook",  scriptBody.path("hook").asText(""));
            ArrayNode scenes = payload.putArray("scenes");
            for (JsonNode s : scriptBody.path("scenes")) {
                ObjectNode sc = scenes.addObject();
                sc.put("seq", s.path("seq").asInt());
                sc.put("visualDesc", s.path("visualDesc").asText(""));
                sc.put("narration", s.path("narration").asText(""));
            }

            ObjectNode tool = mapper.createObjectNode();
            tool.put("name", "translate");
            tool.put("description", "Emit the Dutch translation of the input.");
            tool.set("input_schema", mapper.readTree(SCHEMA));

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 4096);
            body.put("temperature", 0.3);
            body.put("system", SYSTEM_PROMPT);
            ArrayNode tools = body.putArray("tools");
            tools.add(tool);
            ObjectNode toolChoice = body.putObject("tool_choice");
            toolChoice.put("type", "tool");
            toolChoice.put("name", "translate");

            ArrayNode messages = body.putArray("messages");
            ObjectNode userMsg = messages.addObject();
            userMsg.put("role", "user");
            userMsg.put("content",
                    "Translate every text field in this script to Dutch. Keep the "
                    + "scene order and seq numbers. Reply only via the translate tool.\n\n"
                    + payload.toPrettyString());

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofSeconds(60));

            if (resp == null) {
                log.warn("Translation: null response from Claude");
                return null;
            }
            for (JsonNode block : resp.path("content")) {
                if ("tool_use".equals(block.path("type").asText())
                        && "translate".equals(block.path("name").asText())) {
                    JsonNode input = block.path("input");
                    if (cacheKey != null) cache.put(cacheKey, input);
                    return input;
                }
            }
            log.warn("Translation: no tool_use block found in response");
            return null;
        } catch (Exception e) {
            log.warn("Translation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Build a seq -> {visualDesc, narration} map from a translation body. */
    public Map<Integer, Map<String, String>> scenesBySeq(JsonNode translation) {
        Map<Integer, Map<String, String>> out = new HashMap<>();
        if (translation == null) return out;
        for (JsonNode s : translation.path("scenes")) {
            Map<String, String> m = new HashMap<>();
            m.put("visualDesc", s.path("visualDesc").asText(""));
            m.put("narration",  s.path("narration").asText(""));
            out.put(s.path("seq").asInt(), m);
        }
        return out;
    }
}
