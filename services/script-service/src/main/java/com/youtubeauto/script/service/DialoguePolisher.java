package com.youtubeauto.script.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.anthropic.AnthropicClient;
import com.youtubeauto.script.anthropic.AnthropicClient.ChatMessage;
import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.anthropic.GeneratedScript.Line;
import com.youtubeauto.script.anthropic.GeneratedScript.Scene;
import com.youtubeauto.script.config.PolishProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * OPTIONAL dialogue punch-up pass (option 2). Runs ONE forced-tool call on a
 * STRONGER model than the cheap default script model, and rewrites ONLY the
 * spoken {@code line.text} — sharpening humour, timing and the callback/payoff —
 * while leaving structure, visuals, durations and cast completely untouched.
 *
 * <p>Safety is the whole game here: the merge can NEVER change the shape of the
 * script. For each scene we only accept the polished lines when the seq matches,
 * the line COUNT matches, and the speaker of each line is IDENTICAL to the
 * original (same speaker, same order). Any mismatch → we keep that scene's
 * original lines. Any error at all → we return the original script unchanged.
 * That makes this pass purely additive: at worst it's a no-op.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DialoguePolisher {

    private final AnthropicClient anthropic;
    private final PolishProperties props;
    private final com.youtubeauto.script.bible.BibleLoader bibleLoader;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TOOL_NAME = "emit_polish";
    private static final String TOOL_DESC =
            "Emit the punched-up dialogue, scene by scene. Always use this tool.";

    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["scenes"],
              "properties":{
                "scenes":{"type":"array","items":{
                  "type":"object","additionalProperties":false,
                  "required":["seq","lines"],
                  "properties":{
                    "seq":{"type":"integer"},
                    "lines":{"type":"array","items":{
                      "type":"object","additionalProperties":false,
                      "required":["speaker","text"],
                      "properties":{
                        "speaker":{"type":"string"},
                        "text":{"type":"string","maxLength":80}
                      }
                    }}
                  }
                }}
              }
            }
            """;

    private static final String SYSTEM = """
            You are a top-tier comedy dialogue doctor for "Tiny Chicken World", a
            YouTube show for children aged 3-6 (three chickens: Pip, Mo, Bo). You
            are handed a script whose STRUCTURE is already locked and approved. Your
            ONLY job is to make the SPOKEN LINES funnier, punchier and more
            memorable for a small child — nothing else.

            HARD RULES (breaking any one makes your output unusable):
            - Return EVERY scene by its seq. Keep the SAME number of lines in each
              scene, in the SAME order, with the SAME speaker on each line. You may
              ONLY change the words of `text`. Never add, drop or reorder lines.
              Never change who speaks a line.
            - Keep each line SHORT: max 8 words, ideally 3-5. A 4-year-old runs out
              of breath fast.
            - Keep every word understandable to a 4-year-old. No adult wit, no
              sarcasm, no idioms they won't know.
            - Keep the MEANING and the story beat of each line. Don't change what
              happens — change how delightfully it's said.

            WHERE TO SPEND YOUR EFFORT (make it actually funny):
            - Strengthen the CALLBACK: if a gag is planted early (Pip's wrong made-up
              name, Bo's silly rhyme, Mo's calm comparison), make sure it PAYS OFF
              near the climax/closer. Sharpen both the setup and the payoff line.
            - Punch up the 3-4 funniest moments — silly sounds ("Splat!", "Boing!"),
              wordplay and mishearings (Bo), a funny wrong guess (Pip).
            - Keep Mo's voice calm and warm with ONE landing observation; keep Pip
              the eager host; keep Bo the goofball. Stay in character.
            - Don't explain or signpost the joke. Play it straight; let it land.

            Always emit via the emit_polish tool. Return all scenes.
            """;

    /**
     * Returns a polished copy of the script, or the original unchanged if the
     * pass is disabled, errors, or produces nothing safely mergeable.
     */
    public GeneratedScript polish(GeneratedScript script) {
        if (!props.isEnabled() || script == null || script.scenes() == null
                || script.scenes().isEmpty()) {
            return script;
        }
        try {
            // Editable humor-specialist persona (bible personas.humorSpecialist)
            // sits ABOVE the built-in comedy-doctor rules. Blank → rules only.
            String persona = bibleLoader.getBible().humorSpecialistPersona();
            String system = (persona != null && !persona.isBlank())
                    ? "=== WHO YOU ARE (humor specialist) ===\n" + persona.trim() + "\n\n" + SYSTEM
                    : SYSTEM;
            String json = anthropic.callTool(
                    system,
                    List.of(new ChatMessage("user", renderForPolish(script)
                            + "\n\nCall the emit_polish tool with every scene.")),
                    TOOL_NAME, TOOL_DESC, mapper.readTree(SCHEMA),
                    props.modelOrNull(), props.temperatureOrNull()
            ).contentJson();

            Map<Integer, List<Line>> polished = parsePolished(json);
            if (polished.isEmpty()) {
                log.warn("Dialogue polish returned no usable scenes; keeping original.");
                return script;
            }

            int swapped = 0;
            List<Scene> out = new ArrayList<>(script.scenes().size());
            for (Scene orig : script.scenes()) {
                List<Line> newLines = polished.get(orig.seq());
                if (safeToSwap(orig.lines(), newLines)) {
                    out.add(withLines(orig, newLines));
                    swapped++;
                } else {
                    out.add(orig);
                }
            }
            log.info("Dialogue polish applied to {}/{} scenes (model={})",
                    swapped, script.scenes().size(),
                    props.modelOrNull() == null ? "default" : props.modelOrNull());
            return new GeneratedScript(script.title(), script.hook(), script.cta(), out);
        } catch (Exception e) {
            log.warn("Dialogue polish failed (keeping original script): {}", e.getMessage());
            return script;
        }
    }

    /** A swap is only safe when the polished lines exist, match the original
     *  count, and every speaker is identical in order. Otherwise we keep
     *  the original scene verbatim — the pass can never alter structure. */
    private boolean safeToSwap(List<Line> orig, List<Line> polished) {
        if (orig == null || polished == null) return false;
        if (orig.isEmpty() || polished.size() != orig.size()) return false;
        for (int i = 0; i < orig.size(); i++) {
            String os = orig.get(i).speaker();
            String ps = polished.get(i).speaker();
            if (os == null || ps == null || !os.equalsIgnoreCase(ps)) return false;
            String txt = polished.get(i).text();
            if (txt == null || txt.isBlank()) return false;
        }
        return true;
    }

    private Map<Integer, List<Line>> parsePolished(String json) throws Exception {
        Map<Integer, List<Line>> map = new HashMap<>();
        JsonNode root = mapper.readTree(json);
        for (JsonNode sc : root.path("scenes")) {
            int seq = sc.path("seq").asInt(-1);
            if (seq < 0) continue;
            List<Line> lines = new ArrayList<>();
            for (JsonNode l : sc.path("lines")) {
                lines.add(new Line(l.path("speaker").asText(null), l.path("text").asText(null)));
            }
            map.put(seq, lines);
        }
        return map;
    }

    /** Rebuilds a Scene with new lines, preserving every other field. */
    private Scene withLines(Scene s, List<Line> lines) {
        return new Scene(s.seq(), lines, s.visualDesc(), s.characters(), s.locationId(),
                s.phase(), s.timeOfDay(), s.weather(), s.goal(), s.emotion(),
                s.motionSpeed(), s.endPose(), s.motionDesc(), s.durationSeconds());
    }

    /** Compact rendering: only what the doctor needs — seq, phase, speakers,
     *  current lines. VisualDesc is summarised so the model knows the beat but
     *  is not tempted to touch visuals. */
    private String renderForPolish(GeneratedScript s) {
        StringBuilder b = new StringBuilder();
        b.append("Title: ").append(s.title()).append("\n\nScenes (keep seq, line count and speakers EXACTLY):\n");
        for (Scene sc : s.scenes()) {
            b.append("seq ").append(sc.seq()).append(" [").append(nz(sc.phase())).append("]\n");
            if (sc.lines() != null) {
                for (Line l : sc.lines()) {
                    b.append("  ").append(l.speaker()).append(": \"").append(l.text()).append("\"\n");
                }
            }
        }
        return b.toString();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
