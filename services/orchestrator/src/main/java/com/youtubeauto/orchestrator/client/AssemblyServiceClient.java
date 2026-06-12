package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AssemblyServiceClient {

    private static final Logger log = LoggerFactory.getLogger(AssemblyServiceClient.class);

    private final WebClient client;
    private final long pollIntervalMs;
    private final long pollTimeoutMs;

    public AssemblyServiceClient(
            WebClient.Builder builder, OrchestratorProperties props,
            @Value("${app.assembly-client.poll-interval-ms:10000}") long pollIntervalMs,
            @Value("${app.assembly-client.poll-timeout-ms:3600000}") long pollTimeoutMs) {
        // Gewoon de gedeelde builder (Netty responseTimeout 20 min uit
        // WebClientConfig). Op 2026-06-12 (job e2ec9448) knapte een render van
        // >20 min hier op een Netty ReadTimeoutException terwijl ffmpeg gewoon
        // doorliep; de tijdelijke pleister was een eigen connector met een
        // 50-min responseTimeout. De structurele fix is assembleAsync(): submit
        // + poll (zoals videogen) — elke afzonderlijke HTTP-call is dan kort,
        // dus de gedeelde 20-min guard is weer ruim voldoende en de eigen
        // connector is verwijderd. Het legacy-synchrone assemble() hieronder
        // valt daarmee terug onder die 20-min grens; gebruik assembleAsync
        // voor echte renders.
        this.client = builder.clone().baseUrl(props.services().assembly()).build();
        this.pollIntervalMs = pollIntervalMs;
        this.pollTimeoutMs = pollTimeoutMs;
    }

    public JsonNode assemble(UUID jobId, UUID scriptId, List<Map<String, Object>> scenes,
                             String backgroundMusicPath, String introPath, String outroPath,
                             int width, int height, boolean burnSubtitles, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("scriptId", scriptId);
        body.put("scenes", scenes);
        body.put("backgroundMusicPath", backgroundMusicPath == null ? "" : backgroundMusicPath);
        body.put("introPath", introPath == null ? "" : introPath);
        body.put("outroPath", outroPath == null ? "" : outroPath);
        body.put("width", width);
        body.put("height", height);
        body.put("burnSubtitles", burnSubtitles);
        if (title != null && !title.isBlank()) body.put("title", title);

        return client.post().uri("/api/v1/assemble")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(45))
                .block();
    }

    /**
     * Async submit + poll. Same args, same JsonNode result shape as
     * {@link #assemble} (the AssemblyResult: outputPath/captionsPath/shortPath/…).
     * POST /assemble-async returns a jobId immediately, then
     * GET /assemble/jobs/{id} every {@code app.assembly-client.poll-interval-ms}
     * (default 10s) with an overall budget of
     * {@code app.assembly-client.poll-timeout-ms} (default 60 min), logging
     * progress once a minute. Transient poll errors (network blip, 5xx) are
     * tolerated and retried on the next tick; a 404 is fatal — it means the
     * job expired or the assembly-service restarted and the in-memory job
     * store is gone. Mirrors VideoGenerationServiceClient.generateAsync.
     */
    public JsonNode assembleAsync(UUID jobId, UUID scriptId, List<Map<String, Object>> scenes,
                                  String backgroundMusicPath, String introPath, String outroPath,
                                  int width, int height, boolean burnSubtitles, String title) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("scriptId", scriptId);
        body.put("scenes", scenes);
        body.put("backgroundMusicPath", backgroundMusicPath == null ? "" : backgroundMusicPath);
        body.put("introPath", introPath == null ? "" : introPath);
        body.put("outroPath", outroPath == null ? "" : outroPath);
        body.put("width", width);
        body.put("height", height);
        body.put("burnSubtitles", burnSubtitles);
        if (title != null && !title.isBlank()) body.put("title", title);

        JsonNode submit = client.post().uri("/api/v1/assemble-async")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .block(Duration.ofSeconds(30));
        String asmJobId = submit == null ? "" : submit.path("jobId").asText("");
        if (asmJobId.isBlank()) {
            throw new IllegalStateException(
                    "assembly-service async submit returned no jobId (job " + jobId + ")");
        }
        log.info("Job {} assembly submitted async (asmJob={}), polling every {}ms, timeout {}min",
                jobId, asmJobId, pollIntervalMs, pollTimeoutMs / 60_000);

        long started = System.currentTimeMillis();
        long deadline = started + pollTimeoutMs;
        long lastProgressLog = started;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(pollIntervalMs);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "Interrupted while polling assembly job " + asmJobId, ie);
            }
            JsonNode status;
            try {
                status = client.get().uri("/api/v1/assemble/jobs/{id}", asmJobId)
                        .retrieve().bodyToMono(JsonNode.class)
                        .block(Duration.ofSeconds(30));
            } catch (WebClientResponseException.NotFound nf) {
                // Job store is in-memory: 404 = expired or service restarted.
                throw new IllegalStateException("assembly job " + asmJobId
                        + " is gone (404) — assembly-service restarted or job expired", nf);
            } catch (WebClientRequestException | WebClientResponseException
                     | IllegalStateException transientErr) {
                // Network blip / 5xx / block-timeout: keep polling, the render
                // continues server-side regardless of our poll succeeding.
                log.warn("Job {} assembly poll error for {} ({}), retrying next tick",
                        jobId, asmJobId, transientErr.getMessage());
                continue;
            }
            String st = status == null ? "" : status.path("status").asText("");
            if ("DONE".equals(st)) {
                log.info("Job {} assembly job {} DONE after {}s",
                        jobId, asmJobId, (System.currentTimeMillis() - started) / 1000);
                return status.path("result");
            }
            if ("FAILED".equals(st)) {
                throw new IllegalStateException("assembly job " + asmJobId + " FAILED: "
                        + (status == null ? "" : status.path("error").asText("")));
            }
            long now = System.currentTimeMillis();
            if (now - lastProgressLog >= 60_000) {
                lastProgressLog = now;
                log.info("Job {} assembly job {} still {} after {}min",
                        jobId, asmJobId, st.isBlank() ? "RUNNING" : st, (now - started) / 60_000);
            }
        }
        throw new IllegalStateException("assembly job " + asmJobId + " did not finish within "
                + pollTimeoutMs / 60_000 + " minutes (job " + jobId + ")");
    }

    /**
     * Song Mode — calls assembly-service's Suno wrapper to turn lyrics
     * into a sing-along audio track + karaoke instrumental.
     * Returns {songPath, karaokePath, enabled}. When enabled=false the
     * Suno key isn't configured and caller should fall back to royalty-free.
     */
    /** Extracts evenly-spaced keyframes from a finished master MP4 so the
     *  orchestrator's QualityReviewer can audit them via Claude vision. */
    public JsonNode auditKeyframes(UUID jobId, String videoPath, int count) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("videoPath", videoPath);
        body.put("count", count);
        return client.post().uri("/api/v1/audit/keyframes")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(5))
                .block();
    }

    /** Deterministic render checks (duration / silence / black frames) on the
     *  finished master — one decode pass in assembly-service. Best-effort:
     *  returns null on any failure so the audit continues without it. */
    public JsonNode renderChecks(UUID jobId, String videoPath) {
        try {
            Map<String, Object> body = new HashMap<>();
            body.put("jobId", jobId);
            body.put("videoPath", videoPath);
            return client.post().uri("/api/v1/audit/render-checks")
                    .bodyValue(body)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(10))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Normal-episode music — asks assembly-service for an ORIGINAL Suno
     * instrumental tailored to the episode mood. Returns {musicPath, enabled};
     * enabled=false means Suno isn't configured and the caller should fall back
     * to the royalty-free library.
     */
    /** Composite the branded title overlay over a one-time Veo chickens clip and
     *  write the intro the assembly stage prepends to every video. {@code voiceLines}
     *  are the ordered ElevenLabs MP3s (Pip, Mo, Bo) of the chickens introducing
     *  themselves; when non-empty they replace Veo's own audio. */
    public JsonNode buildIntro(String clipPath, List<String> voiceLines) {
        Map<String, Object> body = new HashMap<>();
        body.put("clipPath", clipPath);
        body.put("voiceLines", voiceLines == null ? List.of() : voiceLines);
        return client.post().uri("/api/v1/intro/build")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(12))
                .block();
    }

    /** Composite the branded SUBSCRIBE call-to-action over a one-time Veo
     *  chickens-waving clip and write the outro the assembly stage appends.
     *  {@code voiceLines} are the ordered farewell MP3s (Pip, Mo, Bo). */
    public JsonNode buildOutro(String clipPath, List<String> voiceLines) {
        Map<String, Object> body = new HashMap<>();
        body.put("clipPath", clipPath);
        body.put("voiceLines", voiceLines == null ? List.of() : voiceLines);
        return client.post().uri("/api/v1/outro/build")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(12))
                .block();
    }

    public JsonNode generateInstrumental(UUID jobId, String mood) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("mood", mood == null ? "" : mood);
        return client.post().uri("/api/v1/songs/instrumental")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(8))
                .block();
    }

    public JsonNode generateSong(UUID jobId, String lyrics, String style, String chorus) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("lyrics", lyrics);
        body.put("style", style);
        body.put("chorus", chorus == null ? "" : chorus);
        return client.post().uri("/api/v1/songs/generate")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(8))
                .block();
    }
}
