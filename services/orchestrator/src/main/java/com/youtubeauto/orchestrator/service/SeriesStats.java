package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoAnalytics;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAnalyticsRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Story H — per-series aggregated state that feeds the NEXT episodes.
 * A pure QUERY layer over the existing tables (video_jobs + video_analytics):
 * no new columns, no new write paths — everything is derived on read, so the
 * stats are live the moment the AnalyticsPoller lands fresh data.
 *
 * Complements (does not duplicate) what already exists:
 * <ul>
 *   <li>{@link SeriesContinuity} — narrative memory ("previously on…")</li>
 *   <li>{@link PerformanceLoop} — channel-wide arc/thumbnail decisions</li>
 *   <li>{@link InsightsAggregator} — channel-wide rankings + performance hint</li>
 * </ul>
 * This class answers the SERIES-scoped question: how is series X doing, which
 * arc works best inside it, where do its viewers drop off.
 *
 * Fail-safe: an unknown series or empty database yields an empty Stats record,
 * never an exception.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeriesStats {

    /** Same convention as PerformanceLoop: an arc/layout needs ≥2 scored
     *  episodes before it can be called best/worst within a series. */
    private static final int MIN_SAMPLES = 2;

    private final VideoJobRepository jobs;
    private final VideoAnalyticsRepository analytics;

    /** Aggregated state of one series. Nullable fields are null until enough
     *  data exists (cold-start safe). avgRetentionPercent is 0-100. */
    public record Stats(
            String seriesId,
            int episodes,
            int completedEpisodes,
            Integer lastEpisodeNumber,
            long totalViews,
            Double avgQaScore,
            String bestArc,
            String worstArc,
            Double avgRetentionPercent,
            int retentionSamples,
            String bestThumbnailLayout,
            List<PhaseRetention.PhaseStat> retentionByPhase
    ) {
        static Stats empty(String seriesId) {
            return new Stats(seriesId, 0, 0, null, 0L, null, null, null, null, 0, null, List.of());
        }
    }

    /** Live aggregation for one series; never throws. */
    public Stats forSeries(String seriesId) {
        if (seriesId == null || seriesId.isBlank()) return Stats.empty(seriesId);
        try {
            List<VideoJob> episodes = jobs.findAll().stream()
                    .filter(j -> seriesId.equalsIgnoreCase(j.getSeriesId()))
                    .toList();
            if (episodes.isEmpty()) return Stats.empty(seriesId);

            Map<UUID, VideoAnalytics> latest = analytics.findLatestPerVideo().stream()
                    .collect(Collectors.toMap(VideoAnalytics::getVideoJobId, a -> a,
                            (a, b) -> a.getFetchedAt().isAfter(b.getFetchedAt()) ? a : b));

            List<VideoJob> completed = episodes.stream()
                    .filter(j -> j.getStatus() == JobStatus.COMPLETED)
                    .toList();

            Integer lastEp = episodes.stream()
                    .map(VideoJob::getEpisodeNumber)
                    .filter(java.util.Objects::nonNull)
                    .max(Integer::compare).orElse(null);

            long totalViews = 0;
            double retentionSum = 0;
            int retentionN = 0;
            List<Double> qaScores = new ArrayList<>();
            Map<String, List<Double>> scoreByArc = new HashMap<>();
            Map<String, List<Double>> scoreByLayout = new HashMap<>();
            List<String> retentionPayloads = new ArrayList<>();

            for (VideoJob j : episodes) {
                if (j.getQaBoardScore() != null) qaScores.add(j.getQaBoardScore().doubleValue());
                if (j.getRetentionScenesJson() != null && !j.getRetentionScenesJson().isBlank()) {
                    retentionPayloads.add(j.getRetentionScenesJson());
                }
                VideoAnalytics a = latest.get(j.getId());
                if (a == null) continue;
                if (a.getViews() != null) totalViews += a.getViews();
                if (a.getAverageViewPercentage() != null && a.getAverageViewPercentage() > 0) {
                    retentionSum += a.getAverageViewPercentage();
                    retentionN++;
                }
                Double score = episodeScore(a);
                if (score == null || j.getStatus() != JobStatus.COMPLETED) continue;
                bucket(scoreByArc, j.getStoryArc(), score);
                bucket(scoreByLayout, j.getThumbnailLayout(), score);
            }

            return new Stats(
                    seriesId,
                    episodes.size(),
                    completed.size(),
                    lastEp,
                    totalViews,
                    qaScores.isEmpty() ? null : round1(avg(qaScores)),
                    extremeKey(scoreByArc, true),
                    // worst only meaningful when there is something to compare against
                    scoreByArc.size() >= 2 ? extremeKey(scoreByArc, false) : null,
                    retentionN == 0 ? null : round1(retentionSum / retentionN),
                    retentionN,
                    extremeKey(scoreByLayout, true),
                    PhaseRetention.aggregate(retentionPayloads).phases()
            );
        } catch (Exception e) {
            log.warn("series stats for '{}' failed: {}", seriesId, e.getMessage());
            return Stats.empty(seriesId);
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    /** Episode score: averageViewPercentage (0-100) when the Analytics API
     *  filled it, else the same log-scaled views fallback PerformanceLoop
     *  uses; null when there's no usable signal. */
    private static Double episodeScore(VideoAnalytics a) {
        if (a.getAverageViewPercentage() != null && a.getAverageViewPercentage() > 0) {
            return a.getAverageViewPercentage().doubleValue();
        }
        if (a.getViews() != null && a.getViews() > 0) {
            return Math.min(100, 10 * Math.log10(a.getViews() + 1) * 2);
        }
        return null;
    }

    private static void bucket(Map<String, List<Double>> m, String key, double score) {
        if (key == null || key.isBlank()) return;
        m.computeIfAbsent(key, k -> new ArrayList<>()).add(score);
    }

    /** Best (or worst) key by average score among keys with ≥MIN_SAMPLES. */
    private static String extremeKey(Map<String, List<Double>> m, boolean best) {
        String found = null;
        double foundAvg = best ? -1 : Double.MAX_VALUE;
        for (var e : m.entrySet()) {
            if (e.getValue().size() < MIN_SAMPLES) continue;
            double a = avg(e.getValue());
            if (best ? a > foundAvg : a < foundAvg) { foundAvg = a; found = e.getKey(); }
        }
        return found;
    }

    private static double avg(List<Double> xs) {
        return xs.stream().mapToDouble(Double::doubleValue).average().orElse(0);
    }

    private static double round1(double v) { return Math.round(v * 10) / 10.0; }
}
