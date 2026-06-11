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

/**
 * Generates on-brand kids-song lyrics via Claude. Structure is verse/chorus
 * with sing-along repetition tuned for ages 3-6 (chorus repeats 3-4 times,
 * simple rhymes, lots of repetition).
 *
 * Used by Song Mode pipeline — lyrics feed into SunoSongService which
 * synthesises the actual audio.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LyricsGenerator {

    private static final String TOOL_NAME = "emit_song";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["title","style","lyrics","chorus","verses"],
              "properties":{
                "title":{"type":"string","maxLength":80},
                "style":{"type":"string","maxLength":200,
                         "description":"Music style description for Suno"},
                "lyrics":{"type":"string","description":"Full lyrics with [Verse]/[Chorus] markers"},
                "chorus":{"type":"string","description":"Chorus only — for repetition cue"},
                "verses":{"type":"array","items":{"type":"string"}}
              }
            }
            """;

    private static final String SYSTEM = """
            You write original ORIGINAL songs for the kids YouTube channel
            "Tiny Chicken World" — ages 3-6, three baby chickens (Pip, Mo, Bo).

            STRUCTURE:
              [Verse 1] (4 lines, setup the moment)
              [Chorus]  (4 lines, simple + sticky + repeated)
              [Verse 2] (4 lines, escalate)
              [Chorus]  (same)
              [Bridge]  (2 lines, the realisation)
              [Chorus]  (final, slightly extended)

            RULES (these make a kids song work):
            - Chorus must be SIMPLE — words a 3-year-old can sing.
            - REPETITION wins: same line 2-3 times in a row is good.
            - Onomatopoeia welcome: "peep peep", "cluck cluck", "tap tap tap".
            - Each chick gets a moment to sing their personality:
                Pip: curious "what's that?" energy
                Mo: calm wise observation
                Bo: silly punchline / sound effect
            - Reference the world: cozy barn, golden hour, decorated eggs,
              windmill, hills — sense of place.
            - Lesson is woven in, never preached.
            - Last line should LAND with warmth.

            STYLE STRING for Suno (be specific — Suno reads this literally):
              "cheerful kids song, ukulele and soft piano, warm and cozy,
               gentle children's chorus voices, 90 BPM, sing-along feel"

            Always emit via the emit_song tool.
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    public record SongLyrics(String title, String style, String lyrics, String chorus) {}

    public SongLyrics generate(String topic, String lesson, String mood) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.anthropic().model());
        body.put("max_tokens", 2000);
        body.put("system", SYSTEM);
        body.put("temperature", 0.9);

        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user").put("content",
                "Topic: " + topic + "\n"
                + "Lesson: " + (lesson == null ? "" : lesson) + "\n"
                + "Mood: "   + (mood   == null ? "" : mood)   + "\n\n"
                + "Write the song for this episode via emit_song.");

        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", "Emit the song.");
        try { tool.set("input_schema", mapper.readTree(SCHEMA)); }
        catch (Exception e) { throw new IllegalStateException(e); }

        ObjectNode choice = body.putObject("tool_choice");
        choice.put("type", "tool");
        choice.put("name", TOOL_NAME);

        JsonNode resp = anthropicWebClient.post()
                .uri("/messages")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block();
        if (resp == null) throw new IllegalStateException("Empty lyrics response");
        for (JsonNode block : resp.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                JsonNode input = block.path("input");
                return new SongLyrics(
                        input.path("title").asText(),
                        input.path("style").asText(),
                        input.path("lyrics").asText(),
                        input.path("chorus").asText()
                );
            }
        }
        throw new IllegalStateException("Lyrics tool_use missing");
    }
}
