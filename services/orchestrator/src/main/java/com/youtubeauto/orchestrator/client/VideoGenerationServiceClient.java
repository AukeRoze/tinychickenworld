package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Client for the video-generation-service. Returns per-scene results; the
 * orchestrator persists them and the assembly stage picks up clip.mp4 paths
 * where status=OK.
 *
 * Two entry points with the SAME signature and the SAME JsonNode result shape
 * ({jobId, clips:[...], totalCostEur, costCapReached}):
 *
 *  - {@link #generate}      — legacy synchronous call: one HTTP request that
 *    stays open for the whole Veo render (blocked up to 15 minutes).
 *  - {@link #generateAsync} — submit + poll: POST /generate-async returns a
 *    jobId immediately, then GET /jobs/{id} every ~10s until DONE/FAILED.
 *    Drop-in alternative: callers get the identical JsonNode back.
 *
 * Why async: no quarter-hour-open HTTP connection that any proxy/idle-timeout
 * or connection reset can kill mid-render. Honest limitation: the poll state
 * lives in this method's stack, so an orchestrator restart loses the poll
 * (the generation itself keeps running server-side and its result stays
 * retrievable for 2h, but nothing in the orchestrator resumes the poll).
 * That is still no worse than the synchronous call, where a restart drops
 * the connection AND the response.
 */
@Component
public class VideoGenerationServiceClient {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationServiceClient.class);

    private final WebClient client;
    private final long pollIntervalMs;
    private final long pollTimeoutMs;

    public VideoGenerationServiceClient(
            WebClient.Builder builder, OrchestratorProperties props,
            @Value("${app.videogen.poll-interval-ms:10000}") long pollIntervalMs,
            @Value("${app.videogen.poll-timeout-ms:1200000}") long pollTimeoutMs) {
        this.client = builder.clone().baseUrl(props.services().videoGen()).build();
        this.pollIntervalMs = pollIntervalMs;
        this.pollTimeoutMs = pollTimeoutMs;
    }

    public JsonNode generate(UUID jobId, String format, List<Map<String, Object>> scenes) {
        Map<String, Object> body = Map.of(
                "jobId", jobId,
                "format", format,
                "scenes", scenes
        );
        return client.post().uri("/api/v1/clips/generate")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofMinutes(15));
    }

    /**
     * Async submit + poll. Same args, same result shape as {@link #generate}.
     * Polls every {@code app.videogen.poll-interval-ms} (default 10s) with an
     * overall budget of {@code app.videogen.poll-timeout-ms} (default 20 min),
     * logging progress once a minute. Transient poll errors (network blip,
     * 5xx) are tolerated and retried on the next tick; a 404 is fatal — it
     * means the job expired or the video-generation-service restarted and the
     * in-memory job store is gone.
     */
    public JsonNode generateAsync(UUID jobId, String format, List<Map<String, Object>> scenes) {
        Map<String, Object> body = Map.of(
                "jobId", jobId,
                "format", format,
                "scenes", scenes
        );
        JsonNode submit = client.post().uri("/api/v1/clips/generate-async")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));
        String genJobId = submit == null ? "" : submit.path("jobId").asText("");
        if (genJobId.isBlank()) {
            throw new IllegalStateException(
                    "video-generation-service async submit returned no jobId (job " + jobId + ")");
        }
        log.info("Job {} video-gen submitted async (genJob={}), polling every {}ms, timeout {}min",
                jobId, genJobId, pollIntervalMs, pollTimeoutMs / 60_000);

        long started = System.currentTimeMillis();
        long deadline = started + pollTimeoutMs;
        long lastProgressLog = started;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while polling video-gen job " + genJobId, ie);
            }
            JsonNode status;
            try {
                status = client.get().uri("/api/v1/clips/jobs/{id}", genJobId)
                        .retrieve().bodyToMono(JsonNode.class)
                        .block(Duration.ofSeconds(30));
            } catch (WebClientResponseException.NotFound nf) {
                // Job store is in-memory: 404 = expired or service restarted.
                throw new IllegalStateException("video-gen job " + genJobId
                        + " is gone (404) — video-generation-service restarted or job expired", nf);
            } catch (WebClientRequestException | WebClientResponseException
                     | IllegalStateException transientErr) {
                // Network blip / 5xx / block-timeout: keep polling, the render
                // continues server-side regardless of our poll succeeding.
                log.warn("Job {} video-gen poll error for {} ({}), retrying next tick",
                        jobId, genJobId, transientErr.getMessage());
                continue;
            }
            String st = status == null ? "" : status.path("status").asText("");
            if ("DONE".equals(st)) {
                log.info("Job {} video-gen job {} DONE after {}s",
                        jobId, genJobId, (System.currentTimeMillis() - started) / 1000);
                return status.path("result");
            }
            if ("FAILED".equals(st)) {
                throw new IllegalStateException("video-gen job " + genJobId + " FAILED: "
                        + (status == null ? "" : status.path("error").asText("")));
            }
            long now = System.currentTimeMillis();
            if (now - lastProgressLog >= 60_000) {
                lastProgressLog = now;
                log.info("Job {} video-gen job {} still {} after {}min",
                        jobId, genJobId, st.isBlank() ? "RUNNING" : st, (now - started) / 60_000);
            }
        }
        throw new IllegalStateException("video-gen job " + genJobId + " did not finish within "
                + pollTimeoutMs / 60_000 + " minutes (job " + jobId + ")");
    }
}
