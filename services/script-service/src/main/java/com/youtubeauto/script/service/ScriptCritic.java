package com.youtubeauto.script.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.anthropic.AnthropicClient;
import com.youtubeauto.script.anthropic.AnthropicClient.ChatMessage;
import com.youtubeauto.script.anthropic.GeneratedScript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Cheap qualitative "script critic". The {@link StructureValidator} enforces the
 * STRUCTURE (phase counts, durations, location variety) deterministically; this
 * complements it by scoring the things structure can't see — story arc, re-hook
 * cadence, a satisfying ending and age-appropriate language for 3-6 year olds.
 *
 * Runs a single forced-tool LLM call (reuses the script model, which is Haiku —
 * pennies) and returns a {@link Critique}. The orchestrator decides whether the
 * overall score warrants one targeted rewrite before the expensive render.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ScriptCritic {

    private final AnthropicClient anthropic;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final String TOOL_NAME = "emit_critique";
    private static final String TOOL_DESC =
            "Emit a structured critique of the children's script. Always use this tool.";

    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["overall","arc","rehook","ending","ageLanguage","comedy","emotionalImpact","childPsychology","continuity","issues","directives"],
              "properties":{
                "overall":{"type":"integer","minimum":0,"maximum":100,
                  "description":"Overall story quality for a 3-6yo audience."},
                "arc":{"type":"integer","minimum":0,"maximum":10,
                  "description":"Clear beginning/middle/end with rising stakes."},
                "rehook":{"type":"integer","minimum":0,"maximum":10,
                  "description":"A fresh hook (joke, sound, surprise) every ~10-15s."},
                "ending":{"type":"integer","minimum":0,"maximum":10,
                  "description":"Satisfying, intentional close — something resolved or revealed."},
                "ageLanguage":{"type":"integer","minimum":0,"maximum":10,
                  "description":"Every word understandable to a 4-year-old; no adult exposition."},
                "comedy":{"type":"integer","minimum":0,"maximum":10,
                  "description":"Will a 3-6yo actually laugh or giggle? Silly sounds, wordplay, a character's funny mistake, a gag that pays off. NOT just 'pleasant'."},
                "emotionalImpact":{"type":"integer","minimum":0,"maximum":10,
                  "description":"The Executive-Producer axis: is there a real emotional payoff and a 'wow' a child would remember and want to re-watch? Tenderness, awe, a satisfying release. NOT just cute."},
                "childPsychology":{"type":"integer","minimum":0,"maximum":10,
                  "description":"For ages 3-6: clear and easy to follow, gentle pacing (one idea per beat, not rushed or chaotic), and emotionally SAFE — no scary, intense, threatening or distressing content."},
                "continuity":{"type":"integer","minimum":0,"maximum":10,
                  "description":"VISUAL continuity across scenes: (a) the key prop / recurring motif (e.g. the egg) is tracked — never silently dropped, left behind, or teleported to a new spot; (b) the location is stable within a continuous beat and only changes when the characters actually move (no flip-flopping garden<->path while they stay put); (c) every listed character has a part in the beat (no cast member padded in with nothing to do). Score low when a prop vanishes/relocates without explanation or the setting bounces shot to shot."},
                "issues":{"type":"array","items":{"type":"string"},"maxItems":6,
                  "description":"Concrete, specific weaknesses."},
                "directives":{"type":"array","items":{"type":"string"},"maxItems":6,
                  "description":"Short, actionable rewrite instructions (imperative)."}
              }
            }
            """;

    private static final String SYSTEM = """
            You are a tough but fair story editor for "Tiny Chicken World", a
            YouTube channel for children aged 3-6 (three chickens: Pip, Mo, Bo).
            Score the script on FIVE axes, each 0-10:
              - arc:        a real beginning/middle/end with rising curiosity or
                            stakes, NOT just "things happen then it ends".
              - rehook:     something fresh (a joke, a sound, a surprise, a visual
                            change) lands roughly every 10-15 seconds.
              - ending:     a satisfying, intentional close — a small thing
                            resolved or revealed, the lesson restated in kid-words.
              - ageLanguage:every line is understandable to a 4-year-old, short,
                            concrete, never adult exposition disguised as a child.
              - comedy:     will this actually make a 3-6yo LAUGH or giggle? Look
                            for silly sounds, wordplay/mishearings, a character's
                            funny mistake, anticipation (the viewer sees it coming),
                            and a gag that gets SET UP early and PAYS OFF later
                            (a callback). "Pleasant and warm" is NOT funny — score
                            that 4-5. Reserve 8+ for scripts with real laugh beats.
              - emotionalImpact: the Executive-Producer test — is there a genuine
                            emotional payoff and a "wow" a child would REMEMBER and
                            want to re-watch? Awe, tenderness, a satisfying release,
                            a moment that lands. "Cute but forgettable" scores 4-5.
              - childPsychology: for ages 3-6 — is it clear and easy to follow,
                            gently paced (one idea per beat, never rushed or
                            chaotic), and emotionally SAFE (nothing scary, intense,
                            threatening or distressing)? A jarring or confusing
                            script scores low here even if it's clever.
              - continuity: VISUAL continuity across the scenes (read each scene's
                            location, time-of-day and visualDesc, in order). Check
                            four things: (a) PROP/MOTIF — the key object (e.g. the
                            egg) is tracked: once introduced it is shown or accounted
                            for in the scenes where it should be present, moves WITH
                            the characters when they change location, and never
                            silently disappears or jumps to a different place between
                            scenes; AND if it changes state (the egg cracks, then
                            hatches) the change is one-way — no later scene reverts to
                            or re-asserts a superseded state (e.g. demands 'uncracked'
                            after it has cracked); (b) LOCATION — the setting is stable
                            within a continuous beat and only changes when the
                            characters actually move, with the move shown or
                            established (penalise a beat that flip-flops e.g.
                            garden<->pebble-path shot to shot while the group stays
                            put, and a hard cut from indoors to a far landmark with no
                            travel beat); (c) CAST — every character listed in a scene
                            has something to do in it (penalise a character padded in
                            with no line and no action); (d) TIME — the time-of-day is
                            consistent and only shifts gradually (penalise a scene
                            whose visualDesc says dusk/night while its time reads as
                            midday). Score 8+ only when prop, place, cast and time stay
                            coherent scene to scene.
            Then set `overall` 0-100 as your holistic judgement (NOT a strict sum).
            IMPORTANT: comedy AND emotionalImpact are PRIMARY drivers, not
            tie-breakers. A script that will not make a small child laugh or gasp,
            or that has no emotional payoff, cannot score above 72 overall, no
            matter how clean its arc or language. A script that is confusing,
            rushed or emotionally UNSAFE (childPsychology <= 4) cannot score above
            60 overall. A script with broken visual continuity (continuity <= 4 —
            a key prop dropped or teleported, the location flip-flopping mid-beat,
            or a listed character with nothing to do) cannot score above 65 overall,
            because it renders as jarring, morphing video. Reward genuine, age-right
            humour and feeling.
            Be concise. List only real, specific issues, and give short imperative
            rewrite directives a writer could apply immediately. A strong script
            scores 80+. Always emit via the emit_critique tool.
            """;

    public record Critique(int overall, int arc, int rehook, int ending, int ageLanguage,
                           int comedy, int emotionalImpact, int childPsychology, int continuity,
                           List<String> issues, List<String> directives) {

        /** One feedback block the PromptBuilder can hand back to the generator. */
        public String asFeedback() {
            StringBuilder b = new StringBuilder();
            b.append("Story-critic score ").append(overall).append("/100 ")
             .append("(arc ").append(arc).append("/10, re-hook ").append(rehook)
             .append("/10, ending ").append(ending).append("/10, language ")
             .append(ageLanguage).append("/10, comedy ").append(comedy)
             .append("/10, emotion ").append(emotionalImpact)
             .append("/10, child-fit ").append(childPsychology)
             .append("/10, continuity ").append(continuity).append("/10).");
            if (!issues.isEmpty())     b.append(" Issues: ").append(String.join("; ", issues)).append('.');
            if (!directives.isEmpty()) b.append(" Fix: ").append(String.join("; ", directives)).append('.');
            return b.toString();
        }
    }

    /** Scores a script. Fails safe: any error returns a passing score so the
     *  critic can never hard-block the pipeline. */
    public Critique review(GeneratedScript script) {
        try {
            String json = anthropic.callTool(
                    SYSTEM,
                    List.of(new ChatMessage("user", renderScript(script)
                            + "\n\nCall the emit_critique tool.")),
                    TOOL_NAME, TOOL_DESC, mapper.readTree(SCHEMA)
            ).contentJson();
            JsonNode n = mapper.readTree(json);
            Critique c = new Critique(
                    n.path("overall").asInt(100),
                    n.path("arc").asInt(10),
                    n.path("rehook").asInt(10),
                    n.path("ending").asInt(10),
                    n.path("ageLanguage").asInt(10),
                    n.path("comedy").asInt(10),
                    n.path("emotionalImpact").asInt(10),
                    n.path("childPsychology").asInt(10),
                    n.path("continuity").asInt(10),
                    toList(n.path("issues")),
                    toList(n.path("directives")));
            log.info("Script critic: overall={} arc={} rehook={} ending={} lang={} comedy={} emotion={} child={} continuity={}",
                    c.overall(), c.arc(), c.rehook(), c.ending(), c.ageLanguage(),
                    c.comedy(), c.emotionalImpact(), c.childPsychology(), c.continuity());
            return c;
        } catch (Exception e) {
            log.warn("Script critic failed (passing through): {}", e.getMessage());
            return new Critique(100, 10, 10, 10, 10, 10, 10, 10, 10, List.of(), List.of());
        }
    }

    private String renderScript(GeneratedScript s) {
        StringBuilder b = new StringBuilder();
        b.append("Title: ").append(s.title()).append('\n');
        b.append("Hook: ").append(s.hook()).append('\n');
        b.append("CTA: ").append(s.cta()).append("\n\nScenes:\n");
        for (GeneratedScript.Scene sc : s.scenes()) {
            // location + cast are included so the continuity axis can be judged
            // (prop/location/cast coherence is invisible from dialogue alone).
            b.append(sc.seq()).append(". [").append(nz(sc.phase())).append(", ")
             .append(sc.durationSeconds()).append("s, @").append(nz(sc.locationId()))
             .append(", cast: ").append(castCsv(sc)).append("] ");
            if (sc.lines() != null) {
                for (GeneratedScript.Line l : sc.lines()) {
                    b.append(l.speaker()).append(": \"").append(l.text()).append("\" ");
                }
            }
            b.append("(visual: ").append(nz(sc.visualDesc())).append(")\n");
        }
        return b.toString();
    }

    private static String castCsv(GeneratedScript.Scene sc) {
        if (sc.characters() == null || sc.characters().isEmpty()) return "-";
        return String.join(",", sc.characters());
    }

    private List<String> toList(JsonNode arr) {
        List<String> out = new ArrayList<>();
        if (arr != null && arr.isArray()) arr.forEach(x -> out.add(x.asText()));
        return out;
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
