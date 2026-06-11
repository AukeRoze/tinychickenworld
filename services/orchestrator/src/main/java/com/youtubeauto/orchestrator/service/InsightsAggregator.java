package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.domain.VideoAnalytics;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAnalyticsRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Distills raw VideoAnalytics into actionable patterns for the feedback
 * loop: which moods, lessons, hooks, series, motion modes are
 * out-performing — and which are dragging the channel down.
 *
 * Returns simple rankings the rest of the system can use to bias
 * generation:
 *   - RandomTopicController biases toward top moods
 *   - ThumbnailGenerator biases toward winning variants
 *   - PromptBuilder weights story arcs
 */
@Service
@RequiredArgsConstructor
public class InsightsAggregator {

    private final VideoAnalyticsRepository analyticsRepo;
    private final VideoJobRepository jobRepo;

    public record Insight(String category, String value, double avgViews, int n) {}
    public record Snapshot(
            List<Insight> topMoods,
            List<Insight> topLessons,
            List<Insight> topSeries,
            List<Insight> topMotionModes,
            long totalVideos,
            long totalViews
    ) {}

    public Snapshot snapshot() {
        // Most recent stat per video, joined to its job's bucketing attributes.
        Map<UUID, VideoAnalytics> latest = analyticsRepo.findLatestPerVideo().stream()
                .collect(Collectors.toMap(VideoAnalytics::getVideoJobId, a -> a,
                        (a, b) -> a.getFetchedAt().isAfter(b.getFetchedAt()) ? a : b));

        Map<String, List<Long>> byMood        = new HashMap<>();
        Map<String, List<Long>> byLesson      = new HashMap<>();
        Map<String, List<Long>> bySeries      = new HashMap<>();
        Map<String, List<Long>> byMotionMode  = new HashMap<>();
        long totalVideos = 0, totalViews = 0;

        for (VideoJob job : jobRepo.findAll()) {
            VideoAnalytics a = latest.get(job.getId());
            if (a == null) continue;
            long views = a.getViews() == null ? 0 : a.getViews();
            totalVideos++;
            totalViews += views;
            put(byMood,       job.getMood(),         views);
            put(byLesson,     job.getLesson(),       views);
            put(bySeries,     job.getSeriesId(),     views);
            put(byMotionMode, job.getMotionMode(),   views);
        }

        return new Snapshot(
                rank("mood",        byMood,        5),
                rank("lesson",      byLesson,      5),
                rank("series",      bySeries,      5),
                rank("motionMode",  byMotionMode,  5),
                totalVideos,
                totalViews
        );
    }

    private static void put(Map<String, List<Long>> m, String key, long value) {
        if (key == null || key.isBlank()) return;
        m.computeIfAbsent(key, k -> new ArrayList<>()).add(value);
    }

    private static List<Insight> rank(String category, Map<String, List<Long>> m, int top) {
        return m.entrySet().stream()
                .filter(e -> e.getValue().size() >= 1)
                .map(e -> {
                    double avg = e.getValue().stream().mapToLong(Long::longValue).average().orElse(0);
                    return new Insight(category, e.getKey(), avg, e.getValue().size());
                })
                .sorted(Comparator.comparingDouble(Insight::avgViews).reversed())
                .limit(top)
                .toList();
    }

    /** Returns the top N mood strings for use in topic-generator bias. */
    public List<String> topMoods(int n) {
        return snapshot().topMoods().stream().limit(n).map(Insight::value).toList();
    }

    /** Returns the top N lesson strings — used by random idea generator. */
    public List<String> topLessons(int n) {
        return snapshot().topLessons().stream().limit(n).map(Insight::value).toList();
    }

    /** The self-learning performance hint fed into every new script — the proven
     *  patterns (top moods/lessons by view count) the writer should bias toward.
     *  One source of truth, shown to the user AND sent to script-service.
     *  Returns null when there isn't enough analytics data yet. */
    public String performanceHint() {
        try {
            List<String> top = topMoods(3);
            List<String> lessons = topLessons(3);
            if (top.isEmpty() && lessons.isEmpty()) return null;
            StringBuilder sb = new StringBuilder();
            if (!top.isEmpty())
                sb.append("Top moods by view count: ").append(String.join("; ", top)).append(". ");
            if (!lessons.isEmpty())
                sb.append("Top lessons by view count: ").append(String.join("; ", lessons)).append(". ");
            sb.append("Use these proven patterns as a base; don't copy literally.");
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
