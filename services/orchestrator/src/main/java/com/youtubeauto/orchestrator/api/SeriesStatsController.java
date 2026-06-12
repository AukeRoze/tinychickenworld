package com.youtubeauto.orchestrator.api;

import com.youtubeauto.orchestrator.service.PhaseRetention;
import com.youtubeauto.orchestrator.service.SeriesStats;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only per-series aggregated state (Story H). Lives next to (not inside)
 * BriefOptionsController, which owns the bible-backed /api/v1/series CRUD —
 * this endpoint only ADDS GET /api/v1/series/{id}/stats and never writes.
 *
 * Always returns 200 with a (possibly empty/zeroed) body: an unknown series
 * simply has zero episodes yet, which is valid state for the planning UI.
 */
@RestController
@RequiredArgsConstructor
public class SeriesStatsController {

    private final SeriesStats seriesStats;

    @GetMapping("/api/v1/series/{id}/stats")
    public Map<String, Object> stats(@PathVariable String id) {
        SeriesStats.Stats s = seriesStats.forSeries(id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("seriesId", s.seriesId());
        out.put("episodes", s.episodes());
        out.put("completedEpisodes", s.completedEpisodes());
        out.put("lastEpisodeNumber", s.lastEpisodeNumber());
        out.put("totalViews", s.totalViews());
        out.put("avgQaScore", s.avgQaScore());
        out.put("bestArc", s.bestArc());
        out.put("worstArc", s.worstArc());
        out.put("avgRetentionPercent", s.avgRetentionPercent());
        out.put("retentionSamples", s.retentionSamples());
        out.put("bestThumbnailLayout", s.bestThumbnailLayout());
        out.put("retentionByPhase", toPhaseMaps(s.retentionByPhase()));
        return out;
    }

    static List<Map<String, Object>> toPhaseMaps(List<PhaseRetention.PhaseStat> phases) {
        return phases == null ? List.of() : phases.stream()
                .map(p -> Map.<String, Object>of(
                        "phase", p.phase(),
                        "avgDrop", p.avgDrop(),
                        "avgWatchRatio", p.avgWatchRatio(),
                        "videos", p.videos()))
                .toList();
    }
}
