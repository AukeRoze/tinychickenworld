package com.youtubeauto.upload.api;

import com.youtubeauto.upload.service.AnalyticsApiService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Exposes the (previously unwired) YouTube Analytics API to the orchestrator:
 * audience-retention curves and engagement metrics per video. This is the data
 * side of the self-learning loop — retention-per-scene, arc weighting and
 * thumbnail scoring all read from here.
 *
 * Uses {@code channel==MINE}, i.e. the authorized channel — no channel-id
 * config needed. First call may require a re-OAuth if the stored token
 * predates the yt-analytics.readonly scope.
 */
@RestController
@RequestMapping("/api/v1/analytics")
@RequiredArgsConstructor
public class AnalyticsController {

    private final AnalyticsApiService analytics;

    /** Audience retention curve: rows of (elapsedVideoTimeRatio,
     *  audienceWatchRatio, relativeRetentionPerformance). */
    @GetMapping("/retention/{videoId}")
    public ResponseEntity<Map<String, Object>> retention(@PathVariable String videoId) {
        return ResponseEntity.ok(analytics.retentionCurve("MINE", videoId));
    }

    /** Engagement metrics: views, watch time, AVD, average view percentage. */
    @GetMapping("/engagement/{videoId}")
    public ResponseEntity<Map<String, Object>> engagement(@PathVariable String videoId) {
        return ResponseEntity.ok(analytics.engagement("MINE", videoId));
    }
}
