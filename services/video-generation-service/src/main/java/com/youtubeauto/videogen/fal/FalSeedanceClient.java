package com.youtubeauto.videogen.fal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

/**
 * Seedance 2.0 via the fal.ai queue API — the second motion provider next to
 * Veo. Same contract as the Veo path: start image (+ optional end frame) +
 * motion prompt in, an MP4 on local disk out, so everything downstream
 * (assembly, QC, gates) is provider-agnostic.
 *
 * Protocol (https://fal.ai/models/bytedance/seedance-2.0/image-to-video/api):
 *   1. POST https://queue.fal.run/{model}  with the input JSON
 *      → {request_id, status_url, response_url}
 *   2. Poll status_url until COMPLETED (IN_QUEUE → IN_PROGRESS → COMPLETED)
 *   3. GET response_url → {video:{url}, seed}
 *   4. Download the MP4.
 *
 * Images are passed as base64 DATA URIs (officially supported, max 30 MB) —
 * no GCS or fal-storage round-trip needed. generate_audio=false: voice/music
 * come from ElevenLabs + the music library at assembly, same as with Veo
 * (and audio costs nothing either way, so this is purely hygiene).
 */
@Slf4j
@Component
public class FalSeedanceClient {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(20)).build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String queueBase;
    private final int maxWaitSeconds;
    private final long pollMs;

    public FalSeedanceClient(
            @Value("${fal.key:${FAL_KEY:}}") String apiKey,
            @Value("${fal.queue-base:https://queue.fal.run}") String queueBase,
            @Value("${fal.max-wait-seconds:420}") int maxWaitSeconds,
            @Value("${fal.poll-ms:4000}") long pollMs) {
        this.apiKey = apiKey;
        this.queueBase = queueBase;
        this.maxWaitSeconds = maxWaitSeconds;
        this.pollMs = pollMs;
    }

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * Generates a clip and downloads it to {@code outFile}.
     *
     * @param model      fal endpoint id, e.g. "bytedance/seedance-2.0/image-to-video"
     * @param resolution "480p" | "720p" | "1080p"
     * @param durationSec clamped to Seedance's 4-15s window
     * @param aspect     "16:9" | "9:16"
     * @throws IOException on any API failure — the caller's existing
     *         IOException handling turns this into a Ken Burns FALLBACK.
     */
    public void generateAndDownload(String model, String prompt,
                                    Path startImage, Path endImage,
                                    String resolution, int durationSec, String aspect,
                                    Path outFile) throws IOException, InterruptedException {
        if (!configured()) {
            throw new IOException("FAL_KEY not configured — cannot use Seedance provider");
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("prompt", prompt);
        body.put("image_url", dataUri(startImage));
        if (endImage != null && Files.isReadable(endImage)) {
            body.put("end_image_url", dataUri(endImage));
        }
        body.put("resolution", resolution);
        body.put("duration", String.valueOf(Math.max(4, Math.min(15, durationSec))));
        body.put("aspect_ratio", aspect);
        body.put("generate_audio", false);

        JsonNode submit = postJson(queueBase + "/" + model, body.toString());
        String statusUrl = submit.path("status_url").asText("");
        String responseUrl = submit.path("response_url").asText("");
        String requestId = submit.path("request_id").asText("?");
        if (statusUrl.isBlank() || responseUrl.isBlank()) {
            throw new IOException("fal submit gave no status/response url: " + submit);
        }
        log.info("fal Seedance submitted request {} (model={})", requestId, model);

        long deadline = System.currentTimeMillis() + maxWaitSeconds * 1000L;
        while (true) {
            if (System.currentTimeMillis() > deadline) {
                throw new IOException("fal Seedance timeout after " + maxWaitSeconds + "s (request " + requestId + ")");
            }
            Thread.sleep(pollMs);
            JsonNode st = getJson(statusUrl);
            String status = st.path("status").asText("");
            if ("COMPLETED".equals(status)) break;
            if (!"IN_QUEUE".equals(status) && !"IN_PROGRESS".equals(status)) {
                throw new IOException("fal Seedance request " + requestId + " failed: " + st);
            }
        }

        JsonNode result = getJson(responseUrl);
        String videoUrl = result.path("video").path("url").asText("");
        if (videoUrl.isBlank()) {
            throw new IOException("fal Seedance result has no video url: " + result);
        }
        download(videoUrl, outFile);
        log.info("fal Seedance request {} downloaded -> {}", requestId, outFile);
    }

    // ── http helpers ────────────────────────────────────────────────────

    private JsonNode postJson(String url, String json) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(120))
                .header("Authorization", "Key " + apiKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("fal POST " + url + " -> HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body()));
        }
        return mapper.readTree(resp.body());
    }

    private JsonNode getJson(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Key " + apiKey)
                .GET().build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("fal GET " + url + " -> HTTP " + resp.statusCode()
                    + ": " + truncate(resp.body()));
        }
        return mapper.readTree(resp.body());
    }

    private void download(String url, Path out) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMinutes(5)).GET().build();
        HttpResponse<Path> resp = http.send(req, HttpResponse.BodyHandlers.ofFile(out));
        if (resp.statusCode() / 100 != 2) {
            throw new IOException("fal video download -> HTTP " + resp.statusCode());
        }
    }

    private static String dataUri(Path png) throws IOException {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(Files.readAllBytes(png));
    }

    private static String truncate(String s) {
        if (s == null) return "";
        return s.length() <= 300 ? s : s.substring(0, 300) + "…";
    }
}
