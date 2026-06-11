package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.client.UploadServiceClient;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoAnalytics;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAnalyticsRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;

/**
 * Polls YouTube every 6 hours for uploaded videos. Inserts a fresh
 * snapshot row in {@code video_analytics} so we can build the history
 * + drive the self-learning loop.
 *
 * Catches errors per-video so one failure doesn't block the batch.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AnalyticsPoller {

    private final VideoJobRepository jobs;
    private final VideoAnalyticsRepository analytics;
    private final UploadServiceClient uploadClient;

    /** Run on startup (10s delay) + every 6 hours. */
    @Scheduled(initialDelay = 10_000L, fixedDelay = 6L * 60L * 60L * 1000L)
    public void poll() {
        log.info("Analytics poll starting");
        int ok = 0, skip = 0, fail = 0;
        for (VideoJob job : jobs.findAll()) {
            if (job.getStatus() != JobStatus.COMPLETED) { skip++; continue; }
            if (job.getYoutubeVideoId() == null || job.getYoutubeVideoId().isBlank()) { skip++; continue; }
            try {
                JsonNode stats = uploadClient.stats(job.getYoutubeVideoId());
                if (stats == null || stats.has("error")) { fail++; continue; }
                VideoAnalytics.VideoAnalyticsBuilder b = VideoAnalytics.builder()
                        .videoJobId(job.getId())
                        .youtubeVideoId(job.getYoutubeVideoId())
                        .fetchedAt(OffsetDateTime.now())
                        .views(stats.path("views").asLong(0))
                        .likes(stats.path("likes").asInt(0))
                        .comments(stats.path("comments").asInt(0))
                        .favorites(stats.path("favorites").asInt(0));

                // Analytics API enrichment (best-effort; null until the OAuth
                // token carries the yt-analytics.readonly scope).
                JsonNode eng = uploadClient.engagement(job.getYoutubeVideoId());
                java.util.Map<String, Double> em = firstRowByHeader(eng);
                if (!em.isEmpty()) {
                    if (em.containsKey("estimatedMinutesWatched"))
                        b.watchTimeMinutes(em.get("estimatedMinutesWatched").longValue());
                    if (em.containsKey("averageViewDuration"))
                        b.averageViewDurationSec(em.get("averageViewDuration").intValue());
                    if (em.containsKey("averageViewPercentage"))
                        b.averageViewPercentage(em.get("averageViewPercentage").floatValue());
                    if (em.containsKey("subscribersGained"))
                        b.subscriberGain(em.get("subscribersGained").intValue());
                }
                analytics.save(b.build());

                // Retention-per-scene: map the audience-retention curve onto the
                // episode's scene timeline so drop-offs point at SCENES, not
                // just timestamps. Stored on the job; refreshed every poll.
                try {
                    JsonNode ret = uploadClient.retention(job.getYoutubeVideoId());
                    String mapped = RetentionMapper.mapToScenes(
                            ret, job.getAssemblyScenesJson(),
                            stats.path("duration").asText(""));
                    if (mapped != null) {
                        job.setRetentionScenesJson(mapped);
                        jobs.save(job);
                    }
                } catch (Exception re) {
                    log.debug("retention mapping skipped for job {}: {}", job.getId(), re.getMessage());
                }
                ok++;
            } catch (Exception e) {
                log.warn("poll failed for job {}: {}", job.getId(), e.getMessage());
                fail++;
            }
        }
        log.info("Analytics poll done: ok={} skip={} fail={}", ok, skip, fail);
    }

    /** Parses a YouTube Analytics reports response (columnHeaders + rows) into
     *  a header-name → value map for the FIRST row. Empty on any miss. */
    private static java.util.Map<String, Double> firstRowByHeader(JsonNode resp) {
        java.util.Map<String, Double> out = new java.util.HashMap<>();
        if (resp == null || !resp.has("rows") || resp.path("rows").isEmpty()) return out;
        JsonNode headers = resp.path("columnHeaders");
        JsonNode row = resp.path("rows").path(0);
        for (int i = 0; i < headers.size() && i < row.size(); i++) {
            String name = headers.path(i).path("name").asText("");
            if (!name.isBlank() && row.path(i).isNumber()) {
                out.put(name, row.path(i).asDouble());
            }
        }
        return out;
    }
}
