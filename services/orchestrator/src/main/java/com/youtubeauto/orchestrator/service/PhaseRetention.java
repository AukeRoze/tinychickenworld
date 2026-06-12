package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Aggregates the per-scene retention mappings that {@link RetentionMapper}
 * persists on each job ({@code retentionScenesJson}:
 * {@code [{seq,phase,startSec,endSec,avgWatchRatio,drop}]}) into per-STORY-PHASE
 * statistics across all published videos: "viewers drop most during the
 * development phase" instead of "scene 14 of video X dipped".
 *
 * <p><b>Precision honesty:</b> the input is already an approximation twice
 * over — (1) YouTube's retention curve arrives in coarse elapsed-ratio
 * buckets, so short scenes may contain only one or two curve points;
 * (2) RetentionMapper estimates the branded-intro offset heuristically
 * (70/30 split of the unscripted remainder). On top of that, the per-phase
 * "drop" here sums only IN-scene drops; a cliff exactly on a scene boundary
 * is attributed to neither side. Treat the output as a directional editorial
 * signal, not a measurement.</p>
 *
 * <p>Pure functions, no Spring, no I/O — unit-testable in isolation. All
 * parsing is fail-safe: malformed or empty JSON contributes nothing.</p>
 */
public final class PhaseRetention {

    /** Canonical episode-structure order; unknown phases sort after these. */
    static final List<String> PHASE_ORDER =
            List.of("hook", "setup", "development", "climax", "resolution", "closer");

    /** Minimum videos with retention data before the signal speaks up. */
    public static final int MIN_VIDEOS = 3;

    private PhaseRetention() {}

    /** Cross-video average for one story phase. Ratios are fractions (0..1). */
    public record PhaseStat(String phase, double avgDrop, double avgWatchRatio, int videos) {}

    /** Aggregation result: ordered phase stats + how many videos contributed. */
    public record Aggregate(List<PhaseStat> phases, int videos) {
        public static Aggregate empty() { return new Aggregate(List.of(), 0); }
    }

    /**
     * Aggregates many {@code retentionScenesJson} payloads (one per video).
     * Per video, scenes are grouped by phase (drop = sum of in-scene drops,
     * watch = mean of scene avgWatchRatio); per phase the per-video values are
     * then averaged. Videos whose JSON is null/blank/malformed are skipped.
     */
    public static Aggregate aggregate(Collection<String> retentionScenesJsons) {
        if (retentionScenesJsons == null || retentionScenesJsons.isEmpty()) return Aggregate.empty();
        ObjectMapper m = new ObjectMapper();
        // phase -> list of per-VIDEO values
        Map<String, List<Double>> dropsByPhase = new HashMap<>();
        Map<String, List<Double>> watchByPhase = new HashMap<>();
        int videos = 0;

        for (String json : retentionScenesJsons) {
            Map<String, double[]> perVideo = perVideoPhases(m, json); // phase -> {dropSum, watchSum, n}
            if (perVideo.isEmpty()) continue;
            videos++;
            for (var e : perVideo.entrySet()) {
                double[] v = e.getValue();
                dropsByPhase.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(v[0]);
                watchByPhase.computeIfAbsent(e.getKey(), k -> new ArrayList<>()).add(v[1] / v[2]);
            }
        }
        if (videos == 0) return Aggregate.empty();

        List<PhaseStat> out = new ArrayList<>();
        for (String phase : orderedPhases(dropsByPhase.keySet())) {
            List<Double> drops = dropsByPhase.get(phase);
            List<Double> watch = watchByPhase.get(phase);
            out.add(new PhaseStat(phase,
                    round3(avg(drops)), round3(avg(watch)), drops.size()));
        }
        return new Aggregate(List.copyOf(out), videos);
    }

    /**
     * The retention section for the performance hint, or {@code null} when
     * fewer than {@code minVideos} videos carry retention data or no phase
     * shows a positive drop — silence beats noise toward the script writer.
     */
    public static String hintSection(Aggregate agg, int minVideos) {
        if (agg == null || agg.videos() < minVideos || agg.phases().isEmpty()) return null;
        PhaseStat worst = agg.phases().stream()
                .max(Comparator.comparingDouble(PhaseStat::avgDrop))
                .orElse(null);
        if (worst == null || worst.avgDrop() <= 0) return null;
        return String.format(Locale.ROOT,
                "Retention across %d published videos: viewers drop most during the %s phase "
                        + "(avg -%.1f%% watch within that phase) — tighten that phase: trim slow "
                        + "beats and get to the next moment of curiosity faster.",
                agg.videos(), worst.phase(), worst.avgDrop() * 100);
    }

    // ── internals ────────────────────────────────────────────────────────

    /** phase -> [dropSum, watchSum, sceneCount] for ONE video; empty on any miss. */
    private static Map<String, double[]> perVideoPhases(ObjectMapper m, String json) {
        Map<String, double[]> out = new LinkedHashMap<>();
        if (json == null || json.isBlank()) return out;
        try {
            JsonNode arr = m.readTree(json);
            if (!arr.isArray()) return out;
            for (JsonNode s : arr) {
                String phase = s.path("phase").asText("").trim().toLowerCase(Locale.ROOT);
                if (phase.isBlank() || !s.path("avgWatchRatio").isNumber()) continue;
                double[] acc = out.computeIfAbsent(phase, k -> new double[3]);
                acc[0] += s.path("drop").asDouble(0);
                acc[1] += s.path("avgWatchRatio").asDouble(0);
                acc[2] += 1;
            }
        } catch (Exception e) {
            return Map.of();
        }
        return out;
    }

    private static List<String> orderedPhases(Collection<String> present) {
        List<String> out = new ArrayList<>();
        for (String p : PHASE_ORDER) if (present.contains(p)) out.add(p);
        present.stream().filter(p -> !PHASE_ORDER.contains(p)).sorted().forEach(out::add);
        return out;
    }

    private static double avg(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double round3(double v) { return Math.round(v * 1000) / 1000.0; }
}
