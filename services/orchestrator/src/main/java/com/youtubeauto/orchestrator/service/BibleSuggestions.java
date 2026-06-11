package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The learning-studio loop #2: recurring QC failures → CONCRETE bible-fix
 * proposals. {@link QcInsights} already knows WHAT keeps going wrong
 * ("accessory-swap / mo, 6×"); this service asks Claude to translate that
 * pattern + the character's CURRENT bible fields into ONE minimal field edit
 * that would prevent the failure class permanently. The proposal is shown on
 * the QC-patterns page and only applied after a human clicks Approve (via
 * {@link BibleEditor}, which backs up + validates every write) — PR-style:
 * the system drafts, the human merges.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BibleSuggestions {

    /** Fields the suggestion engine may propose (must stay a subset of what
     *  BibleEditor can write). */
    private static final Set<String> EDITABLE =
            Set.of("accessory", "tic", "antiAccessory", "description", "personality");

    private static final String TOOL = "emit_bible_fix";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["field","proposedValue","rationale"],
              "properties":{
                "field":{"type":"string","enum":["accessory","tic","antiAccessory","description","personality"]},
                "proposedValue":{"type":"string","maxLength":1200},
                "rationale":{"type":"string","maxLength":250}
              }
            }
            """;

    private static final String SYSTEM = """
            You are the character-consistency engineer for an AI-rendered kids
            cartoon. The bible (channel.yml) drives every image and video
            prompt. You are given ONE recurring QC failure pattern and the
            character's CURRENT bible fields.

            Propose ONE minimal edit to ONE field that would prevent this
            failure class permanently. Rules:
            - Return the COMPLETE new value for the field (it replaces the old
              value verbatim), keeping the existing tone and format.
            - Prefer the most targeted field: accessory swaps/disappearance →
              antiAccessory (a comma-separated forbidden list) or accessory;
              motion/behaviour drift → tic; colour/shape drift → description.
            - Make constraints EXPLICIT and unambiguous for an image/video
              model ("NEVER", "ALWAYS", exact colours) — vague wording is what
              caused the failures.
            - Do not invent new traits; harden what exists.
            Always emit via emit_bible_fix.
            """;

    private final QcInsights qcInsights;
    private final OrchestratorProperties props;
    private final WebClient anthropicWebClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Minimum recurrences before a pattern earns a proposal. */
    @Value("${app.qc.suggest-threshold:4}")
    private int threshold;

    /** Max proposals per refresh (each costs one LLM call). */
    @Value("${app.qc.suggest-max:4}")
    private int maxSuggestions;

    public record Suggestion(String characterId, String field, String currentValue,
                             String proposedValue, String rationale,
                             String patternCategory, long patternCount,
                             String patternExample, boolean applicable) {}

    private volatile List<Suggestion> cache = List.of();
    private volatile long cacheAtMs;
    private static final long TTL_MS = 30 * 60_000L;

    public synchronized List<Suggestion> suggestions(boolean refresh) {
        if (!refresh && System.currentTimeMillis() - cacheAtMs < TTL_MS) return cache;
        List<Suggestion> out = new ArrayList<>();
        try {
            for (QcInsights.Pattern p : qcInsights.patterns()) {
                if (out.size() >= maxSuggestions) break;
                if (p.count() < threshold) continue;
                if (p.character() == null || p.character().isBlank() || "-".equals(p.character())) continue;
                Map<String, String> fields = characterFields(p.character());
                if (fields.isEmpty()) continue;
                Suggestion s = propose(p, fields);
                if (s != null) out.add(s);
            }
        } catch (Exception e) {
            log.warn("Bible suggestions failed (returning what we have): {}", e.getMessage());
        }
        cache = out;
        cacheAtMs = System.currentTimeMillis();
        return out;
    }

    /** Invalidate after an apply so the page reflects the new bible state. */
    public synchronized void invalidate() { cacheAtMs = 0; }

    private Suggestion propose(QcInsights.Pattern p, Map<String, String> fields) {
        try {
            StringBuilder u = new StringBuilder();
            u.append("RECURRING QC FAILURE\n")
             .append("category: ").append(p.category()).append('\n')
             .append("character: ").append(p.character()).append('\n')
             .append("occurrences: ").append(p.count()).append('\n')
             .append("most recent example: ").append(p.lastExample() == null ? "" : p.lastExample())
             .append("\n\nCURRENT BIBLE FIELDS for '").append(p.character()).append("':\n");
            fields.forEach((k, v) -> u.append(k).append(": ").append(v).append('\n'));
            u.append("\nPropose the single best fix. emit_bible_fix.");

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 800);
            body.put("system", SYSTEM);
            body.putArray("messages").addObject()
                    .put("role", "user").put("content", u.toString());
            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL);
            tool.put("description", "Emit the proposed bible field fix.");
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
                String field = in.path("field").asText("");
                String value = in.path("proposedValue").asText("");
                if (!EDITABLE.contains(field) || value.isBlank()) return null;
                return new Suggestion(
                        p.character(), field,
                        fields.getOrDefault(field, ""),
                        value,
                        in.path("rationale").asText(""),
                        p.category(), p.count(),
                        p.lastExample() == null ? "" : p.lastExample(),
                        true);
            }
            return null;
        } catch (Exception e) {
            log.warn("Suggestion for {}/{} failed: {}", p.character(), p.category(), e.getMessage());
            return null;
        }
    }

    /** The character's current editable fields from the live bible. */
    private Map<String, String> characterFields(String id) {
        Map<String, String> out = new LinkedHashMap<>();
        try {
            JsonNode root = new YAMLMapper().readTree(
                    Paths.get(props.bible().path()).toFile());
            for (JsonNode ch : root.path("characters")) {
                if (!id.equalsIgnoreCase(ch.path("id").asText(""))) continue;
                out.put("description", ch.path("description").asText("").trim());
                out.put("personality", ch.path("personality").asText("").trim());
                JsonNode dna = ch.path("dna");
                out.put("accessory", dna.path("accessory").asText("").trim());
                out.put("tic", dna.path("tic").asText("").trim());
                out.put("antiAccessory", dna.path("antiAccessory").asText("").trim());
                break;
            }
        } catch (Exception e) {
            log.warn("Could not read bible fields for '{}': {}", id, e.getMessage());
        }
        out.values().removeIf(String::isBlank);
        return out;
    }
}
