package com.youtubeauto.script.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.anthropic.AnthropicClient;
import com.youtubeauto.script.anthropic.AnthropicClient.ChatMessage;
import com.youtubeauto.script.api.dto.GenerateScriptRequest;
import com.youtubeauto.script.api.dto.TreatmentResponse;
import com.youtubeauto.script.api.dto.TreatmentResponse.Beat;
import com.youtubeauto.script.api.dto.TreatmentResponse.CharacterArc;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * STORY TREATMENT (front-end stage #1). Before the full scene-by-scene script is
 * written, this produces a short, HIGH-LEVEL treatment the human reviews and
 * edits: the logline, theme, per-character arc, the 5-7 key beats with their
 * emotional turn, the hook, the twist and the lesson payoff. The user approves
 * (and tweaks) the STORY where it's still cheap to change — one paragraph, not
 * 20 scenes — and the approved treatment then becomes the canonical brief the
 * scene generator must follow.
 *
 * Single forced-tool LLM call (reuses the script model), mirroring
 * {@link ScriptCritic}. Fails safe: a model error surfaces as an empty
 * treatment the caller can show as "couldn't generate, try again".
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TreatmentService {

    private final AnthropicClient anthropic;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TOOL_NAME = "emit_treatment";
    private static final String TOOL_DESC =
            "Emit a structured story treatment for the episode. Always use this tool.";

    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["logline","theme","characterArcs","beats","hook","twist","lessonPayoff"],
              "properties":{
                "logline":{"type":"string","description":"The whole episode in ONE vivid sentence."},
                "theme":{"type":"string","description":"The heart of it / the lesson, in kid-words."},
                "characterArcs":{"type":"array","maxItems":3,"items":{
                  "type":"object","additionalProperties":false,"required":["character","arc"],
                  "properties":{
                    "character":{"type":"string","description":"Pip, Mo or Bo."},
                    "arc":{"type":"string","description":"This character's role + how they change/feel across the episode."}}}},
                "beats":{"type":"array","minItems":5,"maxItems":7,"items":{
                  "type":"object","additionalProperties":false,"required":["name","what","emotion"],
                  "properties":{
                    "name":{"type":"string","description":"Beat label: Hook / Setup / Turn / Climax / Resolution ..."},
                    "what":{"type":"string","description":"What HAPPENS in this beat, concretely."},
                    "emotion":{"type":"string","description":"The dominant feeling of the beat."}}}},
                "hook":{"type":"string","description":"The first 0-8s: a strong emotion + a question/mystery that makes a 3-6yo stay."},
                "twist":{"type":"string","description":"The surprise / re-hook in the middle that keeps it fresh."},
                "lessonPayoff":{"type":"string","description":"The satisfying close: how the lesson lands and is restated in kid-words."}
              }
            }
            """;

    private static final String SYSTEM = """
            You are a senior children's-story editor for "Tiny Chicken World", a
            YouTube channel for young children (three chickens: Pip — the tiny
            curious one; Mo — the calm steady explainer; Bo — the comic relief).
            Design ONE episode as a HIGH-LEVEL TREATMENT, not a full script: a
            crisp logline, the theme/lesson in kid-words, a one-line arc per
            character that actually appears, and 5-7 STORY BEATS that build a
            real beginning-middle-end with rising curiosity, a fresh mid-episode
            twist/re-hook, a clear climax and a satisfying, gently-paced close.
            Keep it warm, safe and age-appropriate for 3-6 year olds: one idea
            per beat, nothing scary or chaotic, comedy from silly sounds /
            mishearings / a character's funny little mistake. Make the HOOK a
            strong feeling plus a question or mystery. Honour any brief, lesson,
            mood, angle or hook the user already gave — sharpen them, don't ignore
            them. Be specific and concrete; avoid vague "they learn about X".
            Always call the emit_treatment tool.
            """;

    public TreatmentResponse generate(GenerateScriptRequest req) {
        try {
            String json = anthropic.callTool(
                    SYSTEM,
                    List.of(new ChatMessage("user", renderBrief(req)
                            + "\n\nCall the emit_treatment tool.")),
                    TOOL_NAME, TOOL_DESC, mapper.readTree(SCHEMA)
            ).contentJson();
            JsonNode n = mapper.readTree(json);
            List<CharacterArc> arcs = new ArrayList<>();
            for (JsonNode a : n.path("characterArcs")) {
                arcs.add(new CharacterArc(a.path("character").asText(""), a.path("arc").asText("")));
            }
            List<Beat> beats = new ArrayList<>();
            for (JsonNode b : n.path("beats")) {
                beats.add(new Beat(b.path("name").asText(""),
                        b.path("what").asText(""), b.path("emotion").asText("")));
            }
            TreatmentResponse t = new TreatmentResponse(
                    n.path("logline").asText(""),
                    n.path("theme").asText(""),
                    arcs, beats,
                    n.path("hook").asText(""),
                    n.path("twist").asText(""),
                    n.path("lessonPayoff").asText(""),
                    null);
            log.info("Treatment generated for topic='{}' ({} beats)", req.topic(), beats.size());
            return t;
        } catch (Exception e) {
            log.warn("Treatment generation failed: {}", e.getMessage());
            return new TreatmentResponse("", "", List.of(), List.of(), "", "", "",
                    "Kon de treatment niet genereren: " + e.getMessage());
        }
    }

    private String renderBrief(GenerateScriptRequest req) {
        StringBuilder b = new StringBuilder();
        b.append("Topic: ").append(nz(req.topic())).append('\n');
        if (req.audience() != null && !req.audience().isBlank())
            b.append("Audience: ").append(req.audience()).append('\n');
        b.append("Target length: ~").append(req.targetSeconds()).append("s");
        if (req.numScenes() != null) b.append(", about ").append(req.numScenes()).append(" scenes");
        b.append('\n');
        if (req.brief() != null && !req.brief().isBlank())
            b.append("Creative brief (sharpen, don't ignore): ").append(req.brief()).append('\n');
        if (req.lesson() != null && !req.lesson().isBlank())
            b.append("Lesson: ").append(req.lesson()).append('\n');
        if (req.mood() != null && !req.mood().isBlank())
            b.append("Mood: ").append(req.mood()).append('\n');
        if (req.angle() != null && !req.angle().isBlank())
            b.append("Narrative angle: ").append(req.angle()).append('\n');
        if (req.hook() != null && !req.hook().isBlank())
            b.append("Hook seed: ").append(req.hook()).append('\n');
        return b.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
