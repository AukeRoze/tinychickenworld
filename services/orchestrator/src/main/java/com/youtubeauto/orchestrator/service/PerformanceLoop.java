package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoAnalytics;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAnalyticsRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * The self-learning half of the pipeline: turns accumulated YouTube analytics
 * into PRODUCTION DECISIONS instead of dashboard trivia.
 *
 * <ul>
 *   <li>{@link #pickArc()} — performance-weighted story-arc selection.
 *       Epsilon-greedy: most picks favour arcs whose videos retain better
 *       (averageViewPercentage, falling back to views), but a fixed share
 *       stays uniformly random so new/unlucky arcs keep getting chances and
 *       the channel never converges on a monoculture.</li>
 *   <li>{@link #bestThumbnailLayout()} — the historically best-performing
 *       thumbnail layout, once enough samples exist.</li>
 * </ul>
 *
 * Cold-start safe: with no analytics at all, pickArc() degrades to uniform
 * random (exactly the legacy behaviour) and bestThumbnailLayout() returns null.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PerformanceLoop {

    /** Share of picks that explore uniformly at random. */
    private static final double EPSILON = 0.30;
    /** Minimum scored videos before an arc/layout influences decisions. */
    private static final int MIN_SAMPLES = 2;

    private final VideoJobRepository jobs;
    private final VideoAnalyticsRepository analytics;
    private final OrchestratorProperties props;

    /** Weighted story-arc pick; null when the bible defines no arcs (caller
     *  then omits preferredArc and script-service keeps its random pick). */
    public String pickArc() {
        List<String> arcIds = bibleArcIds();
        if (arcIds.isEmpty()) return null;
        if (ThreadLocalRandom.current().nextDouble() < EPSILON) {
            String pick = arcIds.get(ThreadLocalRandom.current().nextInt(arcIds.size()));
            log.info("ArcSelector: exploration pick '{}'", pick);
            return pick;
        }
        Map<String, List<Double>> scoresByArc = collectScores(VideoJob::getStoryArc);
        Map<String, Double> weight = new HashMap<>();
        double sum = 0;
        for (String arc : arcIds) {
            List<Double> s = scoresByArc.getOrDefault(arc, List.of());
            double w = s.size() >= MIN_SAMPLES
                    ? clamp(avg(s) / Math.max(1.0, overallAvg(scoresByArc)), 0.25, 3.0)
                    : 1.0;                       // unscored arcs stay neutral
            weight.put(arc, w);
            sum += w;
        }
        double r = ThreadLocalRandom.current().nextDouble() * sum;
        for (String arc : arcIds) {
            r -= weight.get(arc);
            if (r <= 0) {
                log.info("ArcSelector: weighted pick '{}' (weights={})", arc, weight);
                return arc;
            }
        }
        return arcIds.get(arcIds.size() - 1);
    }

    /** Best thumbnail layout by average score, or null below MIN_SAMPLES —
     *  the thumbnail-service then keeps its default face-driven rotation. */
    public String bestThumbnailLayout() {
        Map<String, List<Double>> byLayout = collectScores(VideoJob::getThumbnailLayout);
        String best = null;
        double bestAvg = -1;
        for (var e : byLayout.entrySet()) {
            if (e.getValue().size() < MIN_SAMPLES) continue;
            double a = avg(e.getValue());
            if (a > bestAvg) { bestAvg = a; best = e.getKey(); }
        }
        if (best != null) log.info("ThumbnailLoop: best layout so far '{}' (avg score {})", best, bestAvg);
        return best;
    }

    // ── internals ────────────────────────────────────────────────────────

    /** Score per completed job: averageViewPercentage (0-100) when the
     *  Analytics API filled it; otherwise a views-based fallback so the loop
     *  still learns (weakly) from Data-API-only snapshots. */
    private Map<String, List<Double>> collectScores(
            java.util.function.Function<VideoJob, String> keyFn) {
        Map<String, List<Double>> out = new HashMap<>();
        for (VideoJob j : jobs.findAll()) {
            if (j.getStatus() != JobStatus.COMPLETED) continue;
            String key = keyFn.apply(j);
            if (key == null || key.isBlank()) continue;
            VideoAnalytics a = analytics.findMostRecent(j.getId()).orElse(null);
            if (a == null) continue;
            double score;
            if (a.getAverageViewPercentage() != null && a.getAverageViewPercentage() > 0) {
                score = a.getAverageViewPercentage();
            } else if (a.getViews() != null && a.getViews() > 0) {
                // log-scaled views → comparable 0-100-ish range, weak signal
                score = Math.min(100, 10 * Math.log10(a.getViews() + 1) * 2);
            } else {
                continue;
            }
            out.computeIfAbsent(key, k -> new ArrayList<>()).add(score);
        }
        return out;
    }

    private List<String> bibleArcIds() {
        try {
            JsonNode bible = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                    .readTree(java.nio.file.Paths.get(props.bible().path()).toFile());
            List<String> ids = new ArrayList<>();
            for (JsonNode a : bible.path("storyArcs")) {
                String id = a.path("id").asText("");
                if (!id.isBlank()) ids.add(id);
            }
            return ids;
        } catch (Exception e) {
            log.warn("ArcSelector: bible read failed ({}) — falling back to random", e.getMessage());
            return List.of();
        }
    }

    private static double avg(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double overallAvg(Map<String, List<Double>> m) {
        return m.values().stream().flatMap(List::stream)
                .mapToDouble(Double::doubleValue).average().orElse(1.0);
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
