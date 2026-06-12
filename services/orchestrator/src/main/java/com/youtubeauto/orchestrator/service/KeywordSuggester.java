package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Free keyword source: YouTube autocomplete (the public "suggest" endpoint).
 * <p>
 * Uses the classic {@code client=firefox&ds=yt} variant because it returns
 * clean JSON: {@code ["query",["suggestion 1","suggestion 2",...]]} — no JSONP
 * wrapper to strip. For a topic like "Pip finds a rainbow" it queries 2-3
 * variants (the topic itself, topic + " for kids", and the core nouns +
 * " for toddlers"), then dedups, drops anything that trips the kid-safety
 * blocklist and caps the result at {@link #MAX_SUGGESTIONS}.
 * <p>
 * STRICTLY BEST-EFFORT: any error (network, timeout, parse, weird payload)
 * yields an empty list — this component must never block or fail a pipeline.
 * The {@code app.seo.enabled} flag (env {@code SEO_KEYWORDS_ENABLED}) turns it
 * off entirely; off = empty list, no HTTP at all.
 * <p>
 * NOTE (run-time check): the suggest endpoint is an external Google host
 * ({@code suggestqueries.google.com}); verify outbound DNS/HTTPS works from
 * the orchestrator container. If it doesn't, behaviour degrades gracefully to
 * "no SEO keywords", identical to before this feature existed.
 */
@Slf4j
@Component
public class KeywordSuggester {

    /** Hard cap on suggestions handed to the metadata prompt. */
    static final int MAX_SUGGESTIONS = 10;

    /** Per-request budget. Two-three sequential lookups stay well under any
     *  pipeline timeout even in the worst case. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(5);

    /** Kid-safety blocklist: a suggestion containing any of these words (as a
     *  whole word, case-insensitive) is dropped. Deliberately small — the
     *  channel prompt does the heavy lifting; this just keeps obviously
     *  unsuitable autocomplete tails out of the prompt. */
    private static final Set<String> BLOCKLIST = Set.of(
            "scary", "horror", "creepy", "haunted", "jumpscare",
            "prank", "gun", "guns", "knife", "weapon", "shoot", "shooting",
            "kill", "killer", "dead", "death", "die", "dies", "blood",
            "violent", "violence", "zombie", "murder", "exorcist", "demon");

    /** Filler words skipped when extracting "core nouns" from a topic.
     *  Includes the channel's character names — "pip rainbow for toddlers" is
     *  not something parents search for, "rainbow for toddlers" is. */
    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "the", "and", "or", "but", "of", "in", "on", "at", "to",
            "with", "for", "his", "her", "its", "their", "is", "are", "was",
            "were", "be", "has", "have", "had", "what", "when", "where", "who",
            "why", "how", "finds", "find", "found", "gets", "get", "got",
            "goes", "go", "went", "learns", "learn", "learned", "makes",
            "make", "made", "meets", "meet", "met", "sees", "see", "saw",
            "hears", "hear", "heard", "first", "little", "big", "new",
            "pip", "mo", "bo", "pips", "mos", "bos");

    private final WebClient client;
    private final boolean enabled;
    private final String suggestUrl;
    private final ObjectMapper mapper = new ObjectMapper();

    public KeywordSuggester(
            WebClient.Builder webClientBuilder,
            @Value("${app.seo.enabled:true}") boolean enabled,
            @Value("${app.seo.suggest-url:https://suggestqueries.google.com/complete/search}")
            String suggestUrl) {
        // Clone the shared builder (same pattern as the service clients) so we
        // inherit the connect timeout without mutating anyone else's baseUrl.
        this.client = webClientBuilder.clone().build();
        this.enabled = enabled;
        this.suggestUrl = suggestUrl;
    }

    /**
     * Returns up to {@link #MAX_SUGGESTIONS} kid-safe, deduplicated YouTube
     * autocomplete phrases for the given topic — or an empty list when the
     * feature is disabled, the topic is blank, or anything at all goes wrong.
     */
    public List<String> suggestFor(String topic) {
        if (!enabled || topic == null || topic.isBlank()) return List.of();
        try {
            List<String> raw = new ArrayList<>();
            for (String query : buildQueries(topic)) {
                raw.addAll(fetchSuggestions(query));
                if (raw.size() >= MAX_SUGGESTIONS * 3) break; // plenty to filter from
            }
            List<String> out = postProcess(raw);
            if (!out.isEmpty()) {
                log.info("YouTube autocomplete: {} keyword(s) for topic \"{}\": {}",
                        out.size(), topic, out);
            }
            return out;
        } catch (Exception e) {
            // Belt-and-braces: fetchSuggestions already swallows per-request
            // errors, but nothing in this component may ever propagate.
            log.warn("Keyword suggestion failed for topic \"{}\" (continuing without): {}",
                    topic, e.toString());
            return List.of();
        }
    }

    // ---- internals (package-private for unit tests, no HTTP needed) ---------

    /** 2-3 query variants: the topic itself, topic + " for kids", and the
     *  topic's core nouns + " for toddlers" (when distinct core nouns exist). */
    List<String> buildQueries(String topic) {
        String t = topic.trim();
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        queries.add(t);
        queries.add(t + " for kids");
        String nouns = extractCoreNouns(t);
        if (!nouns.isBlank() && !nouns.equalsIgnoreCase(t)) {
            queries.add(nouns + " for toddlers");
        }
        return List.copyOf(queries);
    }

    /** Strips stopwords/character names; keeps at most the first two
     *  remaining content words ("Pip finds a rainbow" → "rainbow"). */
    String extractCoreNouns(String topic) {
        List<String> kept = new ArrayList<>();
        for (String word : topic.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
            if (word.isBlank() || STOPWORDS.contains(word)) continue;
            kept.add(word);
            if (kept.size() == 2) break;
        }
        return String.join(" ", kept);
    }

    /** One best-effort HTTP lookup; any failure → empty list. */
    private List<String> fetchSuggestions(String query) {
        try {
            URI uri = URI.create(suggestUrl
                    + "?client=firefox&ds=yt&q="
                    + URLEncoder.encode(query, StandardCharsets.UTF_8));
            String body = client.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(REQUEST_TIMEOUT)
                    .block();
            return parse(body);
        } catch (Exception e) {
            log.debug("Autocomplete lookup failed for \"{}\": {}", query, e.toString());
            return List.of();
        }
    }

    /**
     * Parses the firefox-client suggest payload:
     * {@code ["query",["suggestion 1","suggestion 2",...]]}.
     * Also tolerates the youtube-client nesting where each suggestion is itself
     * an array {@code ["text", 0, ...]}. Anything unparseable → empty list.
     */
    List<String> parse(String body) {
        if (body == null || body.isBlank()) return List.of();
        try {
            JsonNode root = mapper.readTree(body);
            if (!root.isArray() || root.size() < 2 || !root.get(1).isArray()) {
                return List.of();
            }
            List<String> out = new ArrayList<>();
            for (JsonNode item : root.get(1)) {
                if (item.isTextual()) {
                    out.add(item.asText());
                } else if (item.isArray() && item.size() > 0 && item.get(0).isTextual()) {
                    out.add(item.get(0).asText());
                }
            }
            return out;
        } catch (Exception e) {
            log.debug("Unparseable suggest payload: {}", e.toString());
            return List.of();
        }
    }

    /** Normalises (trim + lowercase), dedups (insertion order preserved),
     *  drops blocklisted phrases, caps at {@link #MAX_SUGGESTIONS}. */
    List<String> postProcess(List<String> raw) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String s : raw) {
            if (s == null) continue;
            String norm = s.trim().toLowerCase(Locale.ROOT);
            if (norm.isBlank() || !isKidSafe(norm)) continue;
            out.add(norm);
            if (out.size() >= MAX_SUGGESTIONS) break;
        }
        return List.copyOf(out);
    }

    /** True when no blocklisted word appears as a whole word in the phrase. */
    boolean isKidSafe(String phrase) {
        for (String word : phrase.toLowerCase(Locale.ROOT).split("[^a-z0-9']+")) {
            if (BLOCKLIST.contains(word)) return false;
        }
        return true;
    }
}
