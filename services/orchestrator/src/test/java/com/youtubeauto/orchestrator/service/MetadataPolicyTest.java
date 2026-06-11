package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the deterministic metadata gate. Both channel audits (ep 2 + ep 3)
 * flagged the same two issues the LLM kept reproducing — #BedtimeStories and
 * missing series branding. These tests guarantee the policy keeps fixing them
 * even if the prompt regresses.
 */
class MetadataPolicyTest {

    private final MetadataPolicy policy = new MetadataPolicy();

    private static MetadataGenerator.Metadata meta(String desc, List<String> tags) {
        return new MetadataGenerator.Metadata("🐣 Pip Found a Wobbly Egg!", desc, tags);
    }

    @Test
    void bannedHashtagIsStripped() {
        var in = meta("A lovely episode.\n\n#TinyChickenWorld #KidsCartoon "
                + "#BedtimeStories #ToddlerLearning", List.of("tiny chicken world"));
        var out = policy.apply(in, 3);
        assertFalse(out.metadata().description().toLowerCase().contains("#bedtimestories"),
                "banned hashtag must be removed");
        assertTrue(out.fixes().stream().anyMatch(f -> f.contains("bedtimestories")));
    }

    @Test
    void episodeLineIsPrependedWhenMissing() {
        var in = meta("A lovely episode.\n\n#TinyChickenWorld #KidsCartoon #ToddlerLearning",
                List.of("tiny chicken world"));
        var out = policy.apply(in, 3);
        assertTrue(out.metadata().description().startsWith("🐤 Episode 3 of Tiny Chicken World"),
                "series/episode line must open the description");
    }

    @Test
    void existingEpisodeLineIsNotDuplicated() {
        var in = meta("Episode 3 of Tiny Chicken World — the egg wobbles!\n\n"
                + "#TinyChickenWorld #KidsCartoon #ToddlerLearning", List.of("tiny chicken world"));
        var out = policy.apply(in, 3);
        long count = out.metadata().description().toLowerCase()
                .split("episode 3 of tiny chicken world", -1).length - 1;
        assertEquals(1, count, "episode line must appear exactly once");
    }

    @Test
    void requiredHashtagsAreAppended() {
        var in = meta("Just a description without any hashtags.", List.of("tiny chicken world"));
        var out = policy.apply(in, 1);
        String d = out.metadata().description();
        assertTrue(d.contains("#TinyChickenWorld"));
        assertTrue(d.contains("#KidsCartoon"));
        assertTrue(d.contains("#ToddlerLearning"));
    }

    @Test
    void bannedTagRemovedAndBrandTagGuaranteed() {
        var in = meta("Desc #TinyChickenWorld #KidsCartoon #ToddlerLearning",
                List.of("bedtime story", "kids cartoon"));
        var out = policy.apply(in, 2);
        assertFalse(out.metadata().tags().contains("bedtime story"));
        assertEquals("tiny chicken world", out.metadata().tags().get(0),
                "brand tag must be guaranteed");
    }

    @Test
    void seriesNameGuaranteedWithoutEpisodeNumber() {
        var in = meta("Desc without brand. #TinyChickenWorld #KidsCartoon #ToddlerLearning",
                List.of("tiny chicken world"));
        var out = policy.apply(in, null);
        assertTrue(out.metadata().description().toLowerCase().contains("tiny chicken world"));
    }

    @Test
    void cleanMetadataPassesUntouched() {
        String desc = "🐤 Episode 4 of Tiny Chicken World\n\nA lovely episode.\n\n"
                + "#TinyChickenWorld #KidsCartoon #ToddlerLearning";
        var in = meta(desc, List.of("tiny chicken world", "kids cartoon"));
        var out = policy.apply(in, 4);
        assertEquals(desc, out.metadata().description(), "clean description must not change");
        assertTrue(out.fixes().isEmpty(), "no fixes expected, got: " + out.fixes());
    }
}
