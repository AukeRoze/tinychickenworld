package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * Series-ConsistencyState — pure selection logic for SERIES ANCHORS: carrying
 * the visual canon ACROSS episodes of one series (per-serie asset caching,
 * backlog P3; builds on Story B's per-episode anchors).
 *
 * <p>Two halves, both side-effect free (file existence comes in as a predicate,
 * paths as a function), so they unit-test without Spring/Mockito/disk:
 * <ul>
 *   <li>{@link #selectPromotions}: when a job's upload survives the human
 *       gates, which of its episode character anchors
 *       ({@code VideoJob.episodeAnchorsJson}, V27) qualify for promotion to
 *       the durable {@code {bible-dir}/refs/series/{seriesId}/} folder;</li>
 *   <li>{@link #selectSeeds}: on the NEXT episode's first image batch (no
 *       episode canon yet), which series files seed the scene's
 *       {@code episodeAnchors} payload.</li>
 * </ul>
 *
 * <p>Priority as built in {@code PipelineOrchestrator.attachEpisodeAnchors}:
 * own episode canon (post-QC election) &gt; series canon (promoted from the
 * previous approved episode) &gt; bible refs only. Characters that did not
 * appear in any previous episode simply have no series file and are skipped
 * silently — the bible refs remain their baseline (the duckling case).
 *
 * <p>Ids are sanitised to a safe filename alphabet before they may become (or
 * resolve) a file name — an id like {@code "../evil"} can never escape the
 * series folder. Sanitisation lowercases, matching how both the episode-anchor
 * election and the scene payload lowercase character ids.
 */
public final class SeriesAnchors {

    private SeriesAnchors() {}

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Lowercase filename-safe id: letters/digits, then ./_/- allowed. The
     *  explicit {@code ".."} check below is belt-and-braces ("a..b" matches
     *  the pattern but stays suspicious in a path context). */
    private static final Pattern SAFE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** Lowercased, trimmed, filename-safe id — or null when unusable. */
    static String sanitizeId(String raw) {
        if (raw == null) return null;
        String id = raw.trim().toLowerCase();
        if (id.isEmpty() || id.contains("..")) return null;
        return SAFE_ID.matcher(id).matches() ? id : null;
    }

    /**
     * Which episode anchors to PROMOTE to the series folder after a successful,
     * human-approved upload. Input is the raw {@code episodeAnchorsJson} column
     * (shape {@code {"characters":{id:path},"props":[...]}}); only the
     * character anchors travel — props are episode-scoped by design (a basket
     * decorated for THIS story should not haunt the next one).
     *
     * <p>Null/blank/malformed JSON, an unsafe character id, a blank path or a
     * source file the predicate rejects (e.g. the workdir was cleaned) each
     * silently drop that entry — with nothing usable the result is empty and
     * the caller skips promotion entirely.
     *
     * @param episodeAnchorsJson the job's persisted episode canon (may be null)
     * @param fileExists         source-still existence check (null accepts all;
     *                           a throwing predicate disqualifies the entry)
     * @return sanitised characterId → source still path, deterministic order
     */
    public static Map<String, String> selectPromotions(String episodeAnchorsJson,
                                                       Predicate<String> fileExists) {
        Map<String, String> out = new TreeMap<>();
        if (episodeAnchorsJson == null || episodeAnchorsJson.isBlank()) return out;
        JsonNode chars;
        try {
            chars = MAPPER.readTree(episodeAnchorsJson).path("characters");
        } catch (Exception e) {
            return out;
        }
        var fields = chars.fields();
        while (fields.hasNext()) {
            var e = fields.next();
            String id = sanitizeId(e.getKey());
            if (id == null) continue;
            String src = e.getValue().asText("");
            if (src.isBlank() || !exists(fileExists, src)) continue;
            out.put(id, src);
        }
        return out;
    }

    /**
     * Which series anchors SEED a scene of the next episode (used only while
     * the job has no episode canon of its own — once
     * {@code collectEpisodeAnchors} fills the column, the episode's own canon
     * takes over for every re-roll).
     *
     * <p>Same filter mechanics as the episode-anchor attachment: only
     * characters that are actually IN the scene, and only files the predicate
     * accepts. A character without a series file (didn't appear in the
     * previous episode) is skipped silently.
     *
     * @param sceneCharacters the scene's cast ids (any case; null-safe)
     * @param anchorPathForId sanitised id → candidate series-anchor path
     *                        (typically {@code dir/{id}.png}); null/blank or a
     *                        thrown exception skips the character
     * @param fileExists      existence check (null accepts all; throwing
     *                        disqualifies)
     * @return sanitised characterId → series anchor path, deterministic order
     */
    public static Map<String, String> selectSeeds(Collection<String> sceneCharacters,
                                                  Function<String, String> anchorPathForId,
                                                  Predicate<String> fileExists) {
        Map<String, String> out = new TreeMap<>();
        if (sceneCharacters == null || anchorPathForId == null) return out;
        for (String raw : sceneCharacters) {
            String id = sanitizeId(raw);
            if (id == null || out.containsKey(id)) continue;
            String path;
            try {
                path = anchorPathForId.apply(id);
            } catch (Exception e) {
                continue;
            }
            if (path == null || path.isBlank() || !exists(fileExists, path)) continue;
            out.put(id, path);
        }
        return out;
    }

    private static boolean exists(Predicate<String> fileExists, String path) {
        try {
            return fileExists == null || fileExists.test(path);
        } catch (Exception e) {
            return false; // a failing existence check just disqualifies the file
        }
    }
}
