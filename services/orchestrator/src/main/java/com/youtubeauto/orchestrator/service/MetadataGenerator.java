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

import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class MetadataGenerator {

    private static final String TOOL_NAME = "emit_metadata";
    private static final String TOOL_DESC =
            "Emit YouTube metadata as a structured object. Always use this tool.";

    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["title","description","tags"],
              "properties":{
                "title":{"type":"string","maxLength":100},
                "description":{"type":"string","maxLength":4500},
                "tags":{"type":"array","items":{"type":"string"},"maxItems":15}
              }
            }
            """;

    private static final String SYSTEM_BASE = """
            You write YouTube metadata for "Tiny Chicken World" — a child-safe
            animated kids channel for ages 3-6 featuring three baby chickens
            (Pip, Mo, Bo) exploring a cozy world. Target: maximum YouTube
            algorithm performance for kids content.

            === TITLE (short + curiosity-first — this is ~80% of CTR) ===
            - Keep it SHORT: aim for 2-5 words, well under 60 chars. Shorter =
              punchier and easier to read on a phone.
            - Name EXACTLY ONE hero chick — the episode's main character (usually
              Pip). NEVER list two or three names ("Pip, Mo & Bo"): a single hero
              makes the thumbnail a big single FACE, which is what drives clicks.
              A group of names forces a small-faces group shot.
            - Make it a CURIOSITY HOOK — a question or anticipation, never a flat
              summary or a lesson statement.
              GOOD: "Pip's First Rain!"  ·  "What Did Bo Find?"  ·
                    "Mo Heard a Sound…"  ·  "Where Did the Egg Go?"
              WEAK: "Pip, Mo & Bo learn about rain | Tiny Chicken World"
            - End with ! or ? or … for warmth and anticipation.
            - At most ONE leading emoji (🐤 🐣 ☀️ 🌅 🌈 🌸 ⭐ 🌙 — pick by topic),
              optional — skip it if it makes the title feel cluttered.
            - Do NOT append "| Tiny Chicken World" or any brand/suffix, and do not
              cram search keywords in — those live in the description and tags.
              The channel name already shows under every video.
            - NO clickbait, NO all-caps, NO numbers like "12 things...", no
              adult phrasing.

            === DESCRIPTION — kids-channel SEO template ===
            Structure (4 blocks separated by blank lines):

            BLOCK 1 — Hook paragraph (1-2 sentences)
              Engaging summary of the episode. What viewers will experience.
              Mention emotion words: "wonder", "discover", "giggle", "warm",
              "magical".

            BLOCK 2 — Story summary (2-3 sentences)
              Brief story without spoilers. Mention each character by name.
              End with a gentle hook ("Will they figure it out?").

            BLOCK 3 — What kids learn
              Format: "🌟 In this episode kids learn about: [lesson]"
              Add 2-3 "Perfect for:" lines:
                "Perfect for: [bedtime / morning / quiet time / car rides]"
                "Ages: 3-6"
                "Length: ~[duration] minutes of sweet adventure"

            BLOCK 4 — CTAs and hashtags
              "✨ Subscribe so you never miss a Tiny Chicken World adventure!"
              "🔔 Tap the bell for new episodes"
              ""
              "#TinyChickenWorld #KidsCartoon #ToddlerLearning #ToddlerShows
               #CuteAnimation #KidsAnimation #PreschoolLearning"
              NEVER use #BedtimeStories or any bedtime hashtag — the channel's
              episodes are daytime discovery adventures, and a bedtime tag
              teaches the algorithm the wrong audience slot.

            === TAGS (12-15, all lowercase) ===
            Mix of these categories:
            - Brand: "tiny chicken world", "pip mo bo", "chicken cartoon"
            - Genre: "kids cartoon", "preschool show", "toddler learning",
              "animated kids show", "toddler shows", "cute animation"
              (never "bedtime story" / "bedtime stories")
            - Topic-specific: from the topic and lesson
            - Discoverability: "videos for kids", "kids tv", "cartoon for toddlers"

            Always emit via the emit_metadata tool.
            """;

    private static final String VERTICAL_HINT = """

            FORMAT-SPECIFIC RULES (this is a YouTube Shorts video):
            - The title MUST include the hashtag #Shorts somewhere (preferably at the end).
            - Keep the title short (under 60 chars) — Shorts UI truncates.
            - Description: include #Shorts on its own line, plus 2-3 other hashtags.
            - Tags: include 'shorts' and 'youtube shorts' along with topic tags.
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final KeywordSuggester keywordSuggester;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Metadata(String title, String description, List<String> tags) {}

    /**
     * Backwards-compatible entry point (the orchestrator calls this one).
     * Fetches YouTube-autocomplete keywords for the topic itself, best-effort:
     * {@link KeywordSuggester} never throws and returns an empty list when the
     * feature is off or the lookup fails, so existing callers — including
     * PipelineOrchestrator, which must not change — get the SEO boost for free
     * with zero behavioural risk.
     */
    public Metadata generate(String topic, String scriptTitle, String hook, boolean isShort) {
        List<String> seoKeywords =
                keywordSuggester == null ? List.of() : keywordSuggester.suggestFor(topic);
        return generate(topic, scriptTitle, hook, isShort, seoKeywords);
    }

    /**
     * Overload with explicit SEO keywords (real YouTube autocomplete phrases).
     * Pass {@code List.of()} to skip the SEO section entirely; the
     * emit_metadata tool schema is unchanged either way. Exists for tests and
     * callers that want to control or pre-fetch the keyword list themselves.
     */
    public Metadata generate(String topic, String scriptTitle, String hook, boolean isShort,
                             List<String> seoKeywords) {
        String system = SYSTEM_BASE + (isShort ? VERTICAL_HINT : "");

        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.anthropic().model());
        body.put("max_tokens", 1024);
        body.put("system", system);
        body.put("temperature", 0.7);

        ArrayNode messages = body.putArray("messages");
        messages.addObject()
                .put("role", "user")
                .put("content",
                        "Topic: " + topic
                        + "\nScript title: " + scriptTitle
                        + "\nHook: " + hook
                        + (isShort ? "\nThis is a YouTube Short (vertical, <=60s)." : "")
                        + seoSection(seoKeywords)
                        + "\nCall the emit_metadata tool.");

        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", TOOL_DESC);
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
        if (resp == null) throw new IllegalStateException("Empty metadata response");

        JsonNode toolUse = null;
        for (JsonNode block : resp.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                toolUse = block;
                break;
            }
        }
        if (toolUse == null) {
            throw new IllegalStateException("Metadata response missing tool_use: " + resp);
        }

        JsonNode input = toolUse.path("input");
        List<String> tags = new ArrayList<>();
        input.path("tags").forEach(t -> tags.add(t.asText()));
        return new Metadata(
                enforceTitleLength(input.path("title").asText(), isShort),
                input.path("description").asText(),
                tags
        );
    }

    // ---- Localised metadata -------------------------------------------------
    private static final String LOC_SYSTEM = """
            You localise YouTube metadata for "Tiny Chicken World", a child-safe
            animated kids channel (ages 3-6, chickens Pip, Mo, Bo). You are given
            the English title, description and tags. Produce a NATURAL, native
            version in the target language for parents searching in that language.
            RULES:
            - Keep the brand name "Tiny Chicken World" and the character names
              (Pip, Mo, Bo) UNCHANGED.
            - Keep the title <=60 characters and keep its leading emoji.
            - Preserve the description's block structure, emojis and any
              #Hashtags (translate hashtag words where natural, keep
              #TinyChickenWorld unchanged; keep #Shorts unchanged if present).
            - Tags: translate to natural search terms in the target language,
              lowercase, keep brand tags; 12-15 tags.
            - Do NOT add or remove meaning; just localise. Always emit via the
              emit_metadata tool.
            """;

    /**
     * Localises an English {@link Metadata} into {@code langName}. Reuses the
     * emit_metadata schema. The title is re-capped to the mobile-safe length.
     */
    public Metadata localize(Metadata en, String langName, boolean isShort) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.anthropic().model());
        body.put("max_tokens", 1500);
        body.put("system", LOC_SYSTEM + (isShort ? VERTICAL_HINT : ""));
        body.put("temperature", 0.6);
        body.putArray("messages").addObject().put("role", "user").put("content",
                "Target language: " + langName
                + "\n\nEnglish title:\n" + en.title()
                + "\n\nEnglish description:\n" + en.description()
                + "\n\nEnglish tags:\n" + String.join(", ", en.tags())
                + "\n\nLocalise all three and call the emit_metadata tool.");
        ObjectNode tool = body.putArray("tools").addObject();
        tool.put("name", TOOL_NAME);
        tool.put("description", TOOL_DESC);
        try { tool.set("input_schema", mapper.readTree(SCHEMA)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        ObjectNode choice = body.putObject("tool_choice");
        choice.put("type", "tool");
        choice.put("name", TOOL_NAME);

        JsonNode resp = anthropicWebClient.post().uri("/messages")
                .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
        if (resp == null) throw new IllegalStateException("Empty localized-metadata response");
        JsonNode toolUse = null;
        for (JsonNode block : resp.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) { toolUse = block; break; }
        }
        if (toolUse == null) throw new IllegalStateException("Localized metadata missing tool_use: " + resp);
        JsonNode input = toolUse.path("input");
        List<String> tags = new ArrayList<>();
        input.path("tags").forEach(t -> tags.add(t.asText()));
        return new Metadata(
                enforceTitleLength(input.path("title").asText(), isShort),
                input.path("description").asText(),
                tags);
    }

    // ---- Chapter titles -----------------------------------------------------
    private static final String CHAP_TOOL = "emit_chapters";
    private static final String CHAP_SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["chapters"],
              "properties":{
                "chapters":{
                  "type":"array",
                  "items":{
                    "type":"object","additionalProperties":false,
                    "required":["phase","title"],
                    "properties":{
                      "phase":{"type":"string"},
                      "title":{"type":"string","maxLength":40}
                    }
                  }
                }
              }
            }
            """;
    private static final String CHAP_SYSTEM = """
            You write YouTube chapter titles for "Tiny Chicken World" (kids 3-6,
            chickens Pip/Mo/Bo). For each episode phase you are given, write ONE
            short, warm, kid-friendly chapter title (max ~30 chars) that is
            SPECIFIC to THIS episode's topic and what happens in that phase —
            never generic boilerplate like "The big moment" or "The adventure".
            Use simple, cozy, inviting words. No emojis, no episode numbers, no
            clickbait. Keep the title's playful curiosity. Always emit via the
            emit_chapters tool, returning a title for every phase given.
            """;

    /**
     * Generates topic-specific, kid-friendly chapter titles per episode phase.
     * Fails safe: any error or missing phase returns an empty/partial map and the
     * caller falls back to its static labels. The >=10s chapter-merge in the
     * orchestrator is unaffected — this only swaps the LABEL text.
     *
     * @param phaseToSample ordered phaseId -> a short snippet of that phase's content
     * @return phaseId (lowercase) -> chapter title
     */
    public java.util.Map<String, String> chapterTitles(
            String topic, String scriptTitle, java.util.LinkedHashMap<String, String> phaseToSample) {
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        if (phaseToSample == null || phaseToSample.isEmpty()) return out;
        try {
            StringBuilder u = new StringBuilder();
            u.append("Topic: ").append(topic).append("\nScript title: ").append(scriptTitle)
             .append("\n\nPhases (write one title each):\n");
            phaseToSample.forEach((ph, sample) ->
                    u.append("- ").append(ph).append(": ").append(sample).append('\n'));
            u.append("\nCall the emit_chapters tool.");

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 512);
            body.put("system", CHAP_SYSTEM);
            body.put("temperature", 0.7);
            body.putArray("messages").addObject().put("role", "user").put("content", u.toString());
            ObjectNode tool = body.putArray("tools").addObject();
            tool.put("name", CHAP_TOOL);
            tool.put("description", "Emit per-phase kid-friendly chapter titles.");
            tool.set("input_schema", mapper.readTree(CHAP_SCHEMA));
            ObjectNode choice = body.putObject("tool_choice");
            choice.put("type", "tool");
            choice.put("name", CHAP_TOOL);

            JsonNode resp = anthropicWebClient.post().uri("/messages")
                    .bodyValue(body).retrieve().bodyToMono(JsonNode.class).block();
            if (resp == null) return out;
            for (JsonNode block : resp.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    for (JsonNode c : block.path("input").path("chapters")) {
                        String ph = c.path("phase").asText("").trim().toLowerCase();
                        String ti = c.path("title").asText("").trim();
                        if (!ph.isBlank() && !ti.isBlank()) out.put(ph, ti);
                    }
                    break;
                }
            }
        } catch (Exception e) {
            log.warn("Chapter-title generation failed (using static labels): {}", e.getMessage());
        }
        return out;
    }

    /** Renders the SEO-keyword block appended to the user message, or "" when
     *  there are no keywords (the prompt is then byte-for-byte what it was
     *  before this feature — important for prompt-stability). */
    static String seoSection(List<String> seoKeywords) {
        if (seoKeywords == null || seoKeywords.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "\n\nPopular YouTube search phrases for this topic (real autocomplete"
                + " data — weave the strongest naturally into the title and include"
                + " the best 3-5 as tags; NEVER keyword-stuff, kid-readability wins):");
        for (String kw : seoKeywords) {
            if (kw == null || kw.isBlank()) continue;
            sb.append("\n- ").append(kw.trim());
        }
        sb.append('\n');
        return sb.toString();
    }

    /** Hard mobile-safe title cap. The prompt asks for <=60 chars but the schema
     *  allows 100 and models drift over; long titles get truncated on phones.
     *  Trims to {@link #MAX_TITLE} on a word boundary (no ellipsis — YouTube
     *  titles read badly with one) while preserving a trailing #Shorts tag. */
    static String enforceTitleLength(String title, boolean isShort) {
        if (title == null) return "";
        String t = title.trim();
        if (t.length() <= MAX_TITLE) return t;

        // Preserve the #Shorts hashtag (required on Shorts) by setting it aside,
        // truncating the body, then re-appending it within budget.
        String tag = "";
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(?i)\\s*#shorts\\s*$").matcher(t);
        if (m.find()) { tag = " #Shorts"; t = t.substring(0, m.start()).trim(); }

        int budget = MAX_TITLE - tag.length();
        if (t.length() > budget) {
            t = t.substring(0, budget).trim();
            int lastSpace = t.lastIndexOf(' ');
            if (lastSpace >= budget / 2) t = t.substring(0, lastSpace).trim();
            // Drop a dangling separator left at the cut ("Pip & Bo |" → "Pip & Bo").
            t = t.replaceAll("[\\s|\\-–—:,]+$", "").trim();
        }
        return (t + tag).trim();
    }

    private static final int MAX_TITLE = 60;
}
