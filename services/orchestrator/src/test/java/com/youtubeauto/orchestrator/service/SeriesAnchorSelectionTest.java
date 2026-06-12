package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the Series-ConsistencyState selection logic
 * ({@link SeriesAnchors}) — no Spring, no Mockito, no disk I/O (file existence
 * is an injected predicate, path resolution an injected function).
 *
 * Two halves under test:
 *   1. {@link SeriesAnchors#selectPromotions}: after a human-approved upload,
 *      which episode character anchors qualify for promotion to the durable
 *      refs/series/{seriesId}/ folder;
 *   2. {@link SeriesAnchors#selectSeeds}: on the next episode's first image
 *      batch, which series files seed a scene's episodeAnchors payload —
 *      only in-scene characters, silently skipping characters that have no
 *      series file (didn't appear in the previous episode: the duckling case).
 */
class SeriesAnchorSelectionTest {

    /** The exact V27 column shape collectEpisodeAnchors persists. */
    private static final String ANCHORS_JSON = """
            {"characters":{
               "pip":"/workdir/job1/images/scene_01.png",
               "mo":"/workdir/job1/images/scene_05.png",
               "bo":"/workdir/job1/images/scene_04.png"},
             "props":[{"name":"watering can","keyword":"watering",
                       "imagePath":"/workdir/anchor/prop.png"}]}
            """;

    // ── promotion (episode canon → series folder) ────────────────────────

    @Test
    void promotion_takesCharacterAnchorsWhoseFilesExist_propsNeverTravel() {
        // Everything on disk → all three characters promote, props are ignored
        // (a prop decorated for THIS story should not haunt the next episode).
        Map<String, String> all = SeriesAnchors.selectPromotions(ANCHORS_JSON, p -> true);
        assertEquals(Map.of(
                "pip", "/workdir/job1/images/scene_01.png",
                "mo", "/workdir/job1/images/scene_05.png",
                "bo", "/workdir/job1/images/scene_04.png"), all);

        // Workdir partially cleaned → only the surviving stills promote.
        Map<String, String> some = SeriesAnchors.selectPromotions(
                ANCHORS_JSON, "/workdir/job1/images/scene_05.png"::equals);
        assertEquals(Map.of("mo", "/workdir/job1/images/scene_05.png"), some);

        // Nothing left on disk → empty, caller skips promotion entirely.
        assertTrue(SeriesAnchors.selectPromotions(ANCHORS_JSON, p -> false).isEmpty());
    }

    @Test
    void promotion_degradesSilently_onMissingOrHostileInput() {
        // No canon column (the state-machine test's jobs) / blank / malformed.
        assertTrue(SeriesAnchors.selectPromotions(null, p -> true).isEmpty());
        assertTrue(SeriesAnchors.selectPromotions("  ", p -> true).isEmpty());
        assertTrue(SeriesAnchors.selectPromotions("{not json", p -> true).isEmpty());
        assertTrue(SeriesAnchors.selectPromotions("{\"characters\":{}}", p -> true).isEmpty());

        // Hostile/unusable ids never become a series file name; blank paths
        // and a THROWING existence check disqualify instead of propagating.
        String hostile = """
                {"characters":{
                   "../evil":"/x/escape.png",
                   "a/b":"/x/slash.png",
                   "  PIP  ":"/x/pip.png",
                   "mo":""}}
                """;
        Map<String, String> out = SeriesAnchors.selectPromotions(hostile, p -> true);
        assertEquals(Map.of("pip", "/x/pip.png"), out, // sanitised: trimmed + lowercased
                "only the filename-safe id survives");
        assertTrue(SeriesAnchors.selectPromotions(ANCHORS_JSON,
                p -> { throw new RuntimeException("disk error"); }).isEmpty());

        // Null predicate = accept all (mirrors selectEpisodeAnchors' contract).
        assertEquals(3, SeriesAnchors.selectPromotions(ANCHORS_JSON, null).size());
    }

    // ── seeding (series folder → next episode's first batch) ─────────────

    @Test
    void seeding_onlyInSceneCharactersWithAnExistingSeriesFile() {
        // pip + mo have series anchors; the duckling joined the cast THIS
        // episode (no file from the previous one) → silently skipped, its
        // bible refs stay the baseline.
        java.util.function.Predicate<String> onDisk =
                p -> p.equals("/bible/refs/series/farm-s1/pip.png")
                  || p.equals("/bible/refs/series/farm-s1/mo.png");
        Map<String, String> seeds = SeriesAnchors.selectSeeds(
                List.of("Pip", "mo", "duckling"),                 // any case in
                id -> "/bible/refs/series/farm-s1/" + id + ".png",
                onDisk);
        assertEquals(Map.of(
                "pip", "/bible/refs/series/farm-s1/pip.png",
                "mo", "/bible/refs/series/farm-s1/mo.png"), seeds);

        // A solo scene seeds only its own character — same in-scene filter the
        // episode-anchor attachment uses.
        Map<String, String> solo = SeriesAnchors.selectSeeds(
                List.of("mo"),
                id -> "/bible/refs/series/farm-s1/" + id + ".png",
                p -> true);
        assertEquals(Map.of("mo", "/bible/refs/series/farm-s1/mo.png"), solo);
    }

    @Test
    void seeding_degradesSilently_onMissingOrHostileInput() {
        // Empty folder / nothing exists → no seeds, bible refs only.
        assertTrue(SeriesAnchors.selectSeeds(
                List.of("pip", "mo"), id -> "/series/" + id + ".png", p -> false).isEmpty());
        // Null cast or resolver → empty (null-safe like the rest of Story B).
        assertTrue(SeriesAnchors.selectSeeds(
                null, id -> "/series/" + id + ".png", p -> true).isEmpty());
        assertTrue(SeriesAnchors.selectSeeds(List.of("pip"), null, p -> true).isEmpty());
        // Hostile ids are dropped BEFORE they reach the path resolver; blank
        // ids and duplicates collapse; a throwing resolver skips the character.
        Map<String, String> out = SeriesAnchors.selectSeeds(
                List.of("../evil", "a/b", " ", "pip", "PIP"),
                id -> {
                    if (!"pip".equals(id)) throw new AssertionError("unsanitised id leaked: " + id);
                    return "/series/pip.png";
                },
                p -> true);
        assertEquals(Map.of("pip", "/series/pip.png"), out);
        assertTrue(SeriesAnchors.selectSeeds(
                List.of("pip"), id -> { throw new RuntimeException("resolver boom"); },
                p -> true).isEmpty());
        // A throwing existence check disqualifies instead of propagating.
        assertTrue(SeriesAnchors.selectSeeds(
                List.of("pip"), id -> "/series/pip.png",
                p -> { throw new RuntimeException("disk error"); }).isEmpty());
    }
}
