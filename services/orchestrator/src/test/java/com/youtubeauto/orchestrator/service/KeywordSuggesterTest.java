package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pure unit tests (no Spring context, no HTTP): the parser, blocklist,
 * dedup/cap and query-building logic are all package-private precisely so
 * they can be exercised without touching the suggest endpoint. The network
 * path is best-effort by design and is NOT tested here.
 */
class KeywordSuggesterTest {

    /** suggest-url points at nothing — these tests must never do I/O. */
    private final KeywordSuggester suggester =
            new KeywordSuggester(WebClient.builder(), true, "http://127.0.0.1:1/complete/search");

    // ---- parse(): firefox-client JSON format --------------------------------

    @Test
    void parsesFirefoxClientFormat() {
        String body = "[\"rainbow for kids\",[\"rainbow for kids song\","
                + "\"rainbow for kids learning\",\"rainbow colors for kids\"]]";
        assertEquals(
                List.of("rainbow for kids song", "rainbow for kids learning",
                        "rainbow colors for kids"),
                suggester.parse(body));
    }

    @Test
    void parsesYoutubeClientNestedVariant() {
        // youtube-client nests each suggestion as ["text", 0]
        String body = "[\"rainbow\",[[\"rainbow song\",0],[\"rainbow colors\",0]]]";
        assertEquals(List.of("rainbow song", "rainbow colors"), suggester.parse(body));
    }

    @Test
    void parseToleratesGarbageAndEmptyInput() {
        assertTrue(suggester.parse(null).isEmpty());
        assertTrue(suggester.parse("").isEmpty());
        assertTrue(suggester.parse("not json at all").isEmpty());
        assertTrue(suggester.parse("{\"unexpected\":\"object\"}").isEmpty());
        assertTrue(suggester.parse("[\"query only, no suggestions array\"]").isEmpty());
        assertTrue(suggester.parse("[\"q\",\"second element not an array\"]").isEmpty());
    }

    // ---- postProcess(): blocklist, dedup, cap -------------------------------

    @Test
    void blocklistedSuggestionsAreDropped() {
        List<String> out = suggester.postProcess(List.of(
                "rainbow for kids",
                "scary rainbow video",          // blocked: scary
                "rainbow horror story",         // blocked: horror
                "rainbow prank on toddler",     // blocked: prank
                "nerf gun rainbow",             // blocked: gun
                "rainbow song"));
        assertEquals(List.of("rainbow for kids", "rainbow song"), out);
    }

    @Test
    void blocklistMatchesWholeWordsOnly() {
        // "gunther"/"diet" must NOT trip the "gun"/"die" entries.
        assertTrue(suggester.isKidSafe("gunther the chicken"));
        assertTrue(suggester.isKidSafe("rainbow diet for kids"));
        assertFalse(suggester.isKidSafe("toy GUN review"));
    }

    @Test
    void dedupIsCaseInsensitiveAndOrderPreserving() {
        List<String> out = suggester.postProcess(List.of(
                "Rainbow For Kids", "rainbow for kids", "  rainbow for kids ",
                "rainbow song"));
        assertEquals(List.of("rainbow for kids", "rainbow song"), out);
    }

    @Test
    void resultIsCappedAtMax() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 30; i++) many.add("rainbow suggestion " + i);
        assertEquals(KeywordSuggester.MAX_SUGGESTIONS, suggester.postProcess(many).size());
    }

    @Test
    void blankAndNullEntriesAreSkipped() {
        List<String> raw = new ArrayList<>();
        raw.add(null);
        raw.add("   ");
        raw.add("rainbow song");
        assertEquals(List.of("rainbow song"), suggester.postProcess(raw));
    }

    // ---- buildQueries() / extractCoreNouns() --------------------------------

    @Test
    void buildsTopicKidsAndCoreNounVariants() {
        List<String> q = suggester.buildQueries("Pip finds a rainbow");
        assertEquals(List.of(
                "Pip finds a rainbow",
                "Pip finds a rainbow for kids",
                "rainbow for toddlers"), q);
    }

    @Test
    void coreNounsStripCharacterNamesAndFiller() {
        assertEquals("rainbow", suggester.extractCoreNouns("Pip finds a rainbow"));
        assertEquals("puddle splash", suggester.extractCoreNouns("Mo and the big puddle splash"));
    }

    @Test
    void coreNounVariantSkippedWhenNothingLeft() {
        // Topic of only stopwords/names → no third variant.
        List<String> q = suggester.buildQueries("Pip and Mo");
        assertEquals(List.of("Pip and Mo", "Pip and Mo for kids"), q);
    }

    // ---- feature flag --------------------------------------------------------

    @Test
    void disabledFlagShortCircuitsToEmptyList() {
        KeywordSuggester off =
                new KeywordSuggester(WebClient.builder(), false, "http://127.0.0.1:1/x");
        assertTrue(off.suggestFor("Pip finds a rainbow").isEmpty());
    }

    @Test
    void blankTopicYieldsEmptyList() {
        assertTrue(suggester.suggestFor("  ").isEmpty());
        assertTrue(suggester.suggestFor(null).isEmpty());
    }

    // ---- MetadataGenerator prompt section ------------------------------------

    @Test
    void seoSectionEmptyWhenNoKeywords() {
        assertEquals("", MetadataGenerator.seoSection(null));
        assertEquals("", MetadataGenerator.seoSection(List.of()));
    }

    @Test
    void seoSectionListsKeywordsWithInstruction() {
        String s = MetadataGenerator.seoSection(List.of("rainbow for kids", "rainbow song"));
        assertTrue(s.contains("Popular YouTube search phrases for this topic"));
        assertTrue(s.contains("NEVER keyword-stuff"));
        assertTrue(s.contains("\n- rainbow for kids"));
        assertTrue(s.contains("\n- rainbow song"));
    }
}
