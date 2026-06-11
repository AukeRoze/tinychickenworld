package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Returns a fresh on-brand episode idea: topic, hook, lesson, mood — for
 * when the user is out of inspiration. Driven by Claude with the bible as
 * context (characters, world, story arcs, recent topics to avoid).
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class RandomTopicController {

    private static final String TOOL_NAME = "emit_idea";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["topic","brief","hook","lesson","mood","angle"],
              "properties":{
                "topic":{"type":"string","maxLength":120},
                "brief":{"type":"string","maxLength":1500,
                    "description":"The full creative brief: 5-8 sentences of concrete story beats that walk the channel's enforced episode structure IN ORDER — HOOK (0-8s, a strong emotion + question/mystery), SETUP (who/where), DEVELOPMENT (the attempt or exploration), CLIMAX (the big moment the hook promised), RESOLUTION (what they learned/closer). For each beat say which chick does what, the key emotional turn, and the location. Specific and vivid, never a logline. Do NOT describe the characters' looks — the bible owns appearance."},
                "hook":{"type":"string","maxLength":500},
                "lesson":{"type":"string","maxLength":300},
                "mood":{"type":"string","maxLength":120},
                "angle":{"type":"string","maxLength":300,
                    "description":"Narrative angle: who DRIVES the story and the role each chick plays this episode (e.g. 'Pip drives the discovery, Bo brings the comedy, Mo lands the gentle lesson')."}
              }
            }
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final com.youtubeauto.orchestrator.service.InsightsAggregator insights;
    private final ObjectMapper mapper = new ObjectMapper();
    private final YAMLMapper yaml = new YAMLMapper();

    @GetMapping(value = "/api/v1/random-idea", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> randomIdea() {
        try {
            String biblePath = props.bible().path();
            JsonNode bible = Files.exists(Paths.get(biblePath))
                    ? yaml.readTree(Paths.get(biblePath).toFile())
                    : mapper.createObjectNode();

            String world = bible.path("world").path("overview").asText("");
            StringBuilder locs = new StringBuilder();
            for (JsonNode l : bible.path("locations")) {
                if (locs.length() > 0) locs.append(", ");
                locs.append(l.path("id").asText());
            }

            // Pick a story arc at random to seed variety.
            ArrayNode arcs = (ArrayNode) bible.path("storyArcs");
            String arcId = "";
            String arcLabel = "";
            if (arcs != null && arcs.size() > 0) {
                JsonNode a = arcs.get(ThreadLocalRandom.current().nextInt(arcs.size()));
                arcId    = a.path("id").asText();
                arcLabel = a.path("label").asText();
            }

            // Variety seed — random phrase to push Claude into a different
            // creative direction every call.
            String[] seeds = {
                "something tiny that turns out to matter",
                "a sound nobody else notices",
                "a thing that looks ordinary but holds wonder",
                "a small mistake that becomes a discovery",
                "a moment of quiet between friends",
                "a question that changes everything",
                "noticing how something has changed",
                "imagining what something might be",
                "comparing two things that seem different",
                "trying something for the first time",
                "explaining something to a friend",
                "finding something while looking for something else"
            };
            String seed = seeds[ThreadLocalRandom.current().nextInt(seeds.length)];

            String system = """
                    You generate ONE fresh on-brand video idea for "Tiny Chicken World" —
                    a kids cartoon channel about three baby chickens (Pip, Mo, Bo) ages 3-6.

                    Pip: curious, distracted, asks questions, makes small mistakes.
                    Mo: calm, thoughtful, observes patterns, asks gentle questions back.
                    Bo: silly, dramatic, mishears for laughs, physical comedy.

                    Output VARIED, surprising, fresh ideas. Avoid clichés. Lean into
                    sensory detail and small specific moments.

                    Write a BRIEF that walks the channel's enforced episode structure
                    IN ORDER, 5-8 sentences:
                      1. HOOK (0-8s) — a strong feeling + a question/mystery that makes
                         a 3-6 year old need to know what happens.
                      2. SETUP — who is here, where, the small everyday situation.
                      3. DEVELOPMENT — the attempt, the exploration, the rising try.
                      4. CLIMAX — the big moment the hook promised actually lands.
                      5. RESOLUTION/CLOSER — what they learned, a warm button.
                    For each beat name which chick does what and the key emotional
                    turn and the location. The brief is the HIGHEST-priority input to
                    the script writer, so be specific and vivid (never a one-line
                    logline). Do NOT describe the chicks' looks — the bible owns
                    appearance. Also fill `angle`: who drives the story and each
                    chick's role this episode. Keep topic, hook, lesson, mood, angle
                    and brief fully consistent with each other.

                    Always emit via the emit_idea tool.
                    """;

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 800);
            body.put("system", system);
            body.put("temperature", 1.0);   // high temp for variety

            ArrayNode messages = body.putArray("messages");
            // Channel-data feedback — bias toward proven patterns.
            StringBuilder feedback = new StringBuilder();
            java.util.List<String> topMoods   = insights.topMoods(3);
            java.util.List<String> topLessons = insights.topLessons(3);
            if (!topMoods.isEmpty() || !topLessons.isEmpty()) {
                feedback.append("\nYOUR CHANNEL'S DATA SHOWS WHAT WORKS:\n");
                if (!topMoods.isEmpty()) {
                    feedback.append("- Top-performing moods: ")
                            .append(String.join("; ", topMoods)).append("\n");
                }
                if (!topLessons.isEmpty()) {
                    feedback.append("- Top-performing lessons: ")
                            .append(String.join("; ", topLessons)).append("\n");
                }
                feedback.append("Bias toward (but don't copy) these patterns. ");
                feedback.append("Most ideas should sit in this proven territory; ");
                feedback.append("occasionally break out for variety.\n");
            }

            messages.addObject()
                    .put("role", "user")
                    .put("content",
                        "Variety seed: " + seed + "\n"
                        + "Story arc to use: " + arcLabel + "\n"
                        + "World: " + world + "\n"
                        + "Locations available: " + locs
                        + feedback
                        + "\nGenerate ONE FRESH IDEA via emit_idea. The topic should be "
                        + "a single concrete moment (NOT a category — not 'colours' but "
                        + "'the moment Pip notices her shadow has a friend'). The brief "
                        + "must lay out the concrete scene-by-scene beats of that moment "
                        + "following the story arc above.");

            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL_NAME);
            tool.put("description", "Emit a fresh on-brand episode idea.");
            tool.set("input_schema", mapper.readTree(SCHEMA));

            ObjectNode choice = body.putObject("tool_choice");
            choice.put("type", "tool");
            choice.put("name", TOOL_NAME);

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return ResponseEntity.internalServerError().build();

            for (JsonNode block : resp.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    return ResponseEntity.ok(block.path("input"));
                }
            }
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("random idea failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Upgrades the user's OWN rough topic/brief into a strong, norm-conforming
     * brief (HOOK→SETUP→DEVELOPMENT→CLIMAX→RESOLUTION beats) without losing their
     * intent, and fills any missing field (hook/lesson/mood/angle). One LLM call,
     * no pipeline cost — same pattern as random-idea. This is the "get it right
     * at step 1" helper so the script lands strong first time.
     */
    @PostMapping(value = "/api/v1/improve-brief", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<JsonNode> improveBrief(@RequestBody(required = false) java.util.Map<String, String> in) {
        try {
            java.util.Map<String, String> u = in == null ? java.util.Map.of() : in;
            String topic  = u.getOrDefault("topic", "").trim();
            String brief  = u.getOrDefault("brief", "").trim();
            String hook   = u.getOrDefault("hook", "").trim();
            String lesson = u.getOrDefault("lesson", "").trim();
            String mood   = u.getOrDefault("mood", "").trim();
            String angle  = u.getOrDefault("angle", "").trim();
            if (topic.isEmpty() && brief.isEmpty()) {
                return ResponseEntity.badRequest().build();   // nothing to work from
            }

            String biblePath = props.bible().path();
            JsonNode bible = Files.exists(Paths.get(biblePath))
                    ? yaml.readTree(Paths.get(biblePath).toFile())
                    : mapper.createObjectNode();
            String world = bible.path("world").path("overview").asText("");
            StringBuilder locs = new StringBuilder();
            for (JsonNode l : bible.path("locations")) {
                if (locs.length() > 0) locs.append(", ");
                locs.append(l.path("id").asText());
            }

            String system = """
                    You are the brief editor for "Tiny Chicken World" — a kids cartoon
                    (ages 3-6) about three baby chickens: Pip (curious, asks questions,
                    makes small mistakes), Mo (calm, thoughtful, gentle), Bo (silly,
                    dramatic, physical comedy).

                    You take the user's ROUGH idea and upgrade it into a STRONG,
                    production-ready brief WITHOUT changing their core intent. Keep
                    their topic and any beats they gave; sharpen, structure and enrich.

                    The brief must walk the enforced episode structure IN ORDER, 5-8
                    sentences: HOOK (0-8s strong feeling + question/mystery), SETUP,
                    DEVELOPMENT, CLIMAX (the moment the hook promised), RESOLUTION/closer.
                    For each beat name which chick does what, the emotional turn and the
                    location. Specific and vivid, never a logline. Do NOT describe the
                    chicks' looks — the bible owns appearance.

                    Fill EVERY field (topic, brief, hook, lesson, mood, angle), keeping
                    them consistent. If the user left a field blank, write a fitting one;
                    preserve the user's wording where it is already good.

                    Always emit via the emit_brief tool.
                    """;

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 900);
            body.put("system", system);
            body.put("temperature", 0.7);

            ArrayNode messages = body.putArray("messages");
            StringBuilder um = new StringBuilder();
            um.append("Upgrade this into a strong norm brief.\n\n");
            um.append("Topic: ").append(topic.isEmpty() ? "(none — derive one)" : topic).append("\n");
            um.append("Current brief: ").append(brief.isEmpty() ? "(none — write one)" : brief).append("\n");
            um.append("Hook: ").append(hook.isEmpty() ? "(none)" : hook).append("\n");
            um.append("Lesson: ").append(lesson.isEmpty() ? "(none)" : lesson).append("\n");
            um.append("Mood: ").append(mood.isEmpty() ? "(none)" : mood).append("\n");
            um.append("Angle: ").append(angle.isEmpty() ? "(none)" : angle).append("\n\n");
            um.append("World: ").append(world).append("\n");
            um.append("Locations available: ").append(locs);
            messages.addObject().put("role", "user").put("content", um.toString());

            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", "emit_brief");
            tool.put("description", "Emit the upgraded, norm-conforming brief.");
            tool.set("input_schema", mapper.readTree(SCHEMA));

            ObjectNode choice = body.putObject("tool_choice");
            choice.put("type", "tool");
            choice.put("name", "emit_brief");

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block();
            if (resp == null) return ResponseEntity.internalServerError().build();
            for (JsonNode block : resp.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    return ResponseEntity.ok(block.path("input"));
                }
            }
            return ResponseEntity.internalServerError().build();
        } catch (Exception e) {
            log.error("improve brief failed", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
