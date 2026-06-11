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

import java.util.List;
import java.util.Map;

/**
 * Translates the script + title + description into one of the configured
 * target languages via Claude. Output is a localised JSON that other
 * services can consume to produce per-language audio + subtitles.
 *
 * Supported languages (extend by adding to LANGUAGE_NAMES):
 *    nl  Dutch
 *    en  English
 *    de  German
 *    es  Spanish
 *    fr  French
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalizationService {

    private static final Map<String, String> LANGUAGE_NAMES = Map.of(
            "nl", "Dutch (Netherlands)",
            "en", "English (US)",
            "de", "German",
            "es", "Spanish (Spain)",
            "fr", "French (France)",
            "pt", "Portuguese (Brazil)"
    );

    private static final String TOOL_NAME = "emit_translation";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["title","hook","scenes"],
              "properties":{
                "title":{"type":"string","maxLength":120},
                "hook":{"type":"string","maxLength":500},
                "description":{"type":"string","maxLength":4500},
                "scenes":{
                  "type":"array",
                  "items":{
                    "type":"object","required":["seq","narration","lines"],
                    "properties":{
                      "seq":{"type":"integer"},
                      "narration":{"type":"string"},
                      "lines":{
                        "type":"array",
                        "items":{"type":"object","required":["speaker","text"],
                                 "properties":{"speaker":{"type":"string"},"text":{"type":"string"}}}
                      }
                    }
                  }
                }
              }
            }
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public JsonNode translate(JsonNode scriptEN, String targetLang) {
        String langName = LANGUAGE_NAMES.get(targetLang.toLowerCase());
        if (langName == null) {
            throw new IllegalArgumentException("Unsupported language: " + targetLang);
        }

        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.anthropic().model());
        body.put("max_tokens", 8192);
        body.put("system",
                "You translate kids' cartoon scripts. Keep them age-appropriate (3-6 years old), "
                + "warm, friendly, and natural in the target language. Preserve character names "
                + "(Pip, Mo, Bo) unchanged. Translate sound effects / onomatopoeia to the target "
                + "language's conventional forms (eg English 'whoosh' may stay; German might use "
                + "'wusch'). Keep visual descriptions in ENGLISH (those go to the image generator). "
                + "Translate: title, hook, narration, dialogue lines. Always emit via the tool.");

        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "user")
                .put("content",
                        "Target language: " + langName + "\n"
                        + "Source script (English):\n"
                        + scriptEN.toPrettyString()
                        + "\n\nTranslate to " + langName + " using the emit_translation tool.");

        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Emit the localised script.");
        try { tool.set("input_schema", mapper.readTree(SCHEMA)); }
        catch (Exception e) { throw new IllegalStateException(e); }

        ObjectNode choice = body.putObject("tool_choice");
        choice.put("type", "tool");
        choice.put("name", TOOL_NAME);

        JsonNode resp = anthropicWebClient.post()
                .uri("/messages")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();
        if (resp == null) throw new IllegalStateException("Empty translation response");
        for (JsonNode block : resp.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                return block.path("input");
            }
        }
        throw new IllegalStateException("Translation tool_use missing");
    }

    public List<String> supportedLanguages() {
        return List.copyOf(LANGUAGE_NAMES.keySet());
    }
    public String displayName(String code) {
        return LANGUAGE_NAMES.getOrDefault(code, code);
    }
}
