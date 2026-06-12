package com.youtubeauto.orchestrator.api;

import com.youtubeauto.orchestrator.domain.VideoAnalytics;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAnalyticsRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.service.AnalyticsPoller;
import com.youtubeauto.orchestrator.service.InsightsAggregator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Channel analytics for the static UI: KPI totals, top-performer insights and a
 * per-video metrics table. (The "poll now" trigger still lives on the classic
 * dashboard for now; it moves here when that controller is retired.)
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final InsightsAggregator insights;
    private final VideoAnalyticsRepository analyticsRepo;
    private final VideoJobRepository jobRepo;
    private final AnalyticsPoller poller;

    /** Force a fresh analytics poll now (moved here from the old dashboard). */
    @PostMapping("/poll")
    public ResponseEntity<Map<String, String>> pollNow() {
        try {
            poller.poll();
            return ResponseEntity.ok(Map.of("result", "POLLED"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping
    public Map<String, Object> analytics() {
        InsightsAggregator.Snapshot snap = insights.snapshot();

        Map<UUID, VideoAnalytics> latest = analyticsRepo.findLatestPerVideo().stream()
                .collect(Collectors.toMap(VideoAnalytics::getVideoJobId, a -> a, (a, b) -> a));

        List<VideoJob> uploaded = jobRepo.findAll().stream()
                .filter(j -> j.getYoutubeVideoId() != null)
                .toList();

        List<Map<String, Object>> videos = uploaded.stream().map(j -> {
            VideoAnalytics a = latest.get(j.getId());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", j.getId().toString());
            m.put("topic", j.getTopic() == null ? "" : j.getTopic());
            m.put("views", a == null ? null : a.getViews());
            m.put("likes", a == null ? null : a.getLikes());
            m.put("comments", a == null ? null : a.getComments());
            m.put("series", j.getSeriesId() == null ? "" : j.getSeriesId());
            m.put("updated", (a == null || a.getFetchedAt() == null) ? null : a.getFetchedAt().toString());
            return m;
        }).toList();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("uploaded", uploaded.size());
        out.put("totalViews", snap.totalViews());
        out.put("snapshots", analyticsRepo.count());
        out.put("hint", insights.performanceHint());
        out.put("topMoods", toInsights(snap.topMoods()));
        out.put("topLessons", toInsights(snap.topLessons()));
        out.put("topSeries", toInsights(snap.topSeries()));
        out.put("topMotionModes", toInsights(snap.topMotionModes()));
        // Story I: per-story-phase retention aggregation (additive; empty
        // list + 0 until ≥1 video has a retention mapping — fail-safe).
        var phaseRetention = insights.retentionByPhase();
        out.put("retentionByPhase", SeriesStatsController.toPhaseMaps(phaseRetention.phases()));
        out.put("retentionVideos", phaseRetention.videos());
        out.put("videos", videos);
        return out;
    }

    private List<Map<String, Object>> toInsights(List<InsightsAggregator.Insight> items) {
        return items.stream()
                .map(i -> Map.<String, Object>of(
                        "value", i.value() == null ? "" : i.value(),
                        "avgViews", Math.round(i.avgViews()),
                        "n", i.n()))
                .toList();
    }
}
