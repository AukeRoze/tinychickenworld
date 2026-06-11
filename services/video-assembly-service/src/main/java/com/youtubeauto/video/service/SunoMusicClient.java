package com.youtubeauto.video.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Generates original kids-cartoon music via a SELF-HOSTED Suno-API wrapper
 * (github.com/Suno-API/Suno-API). The wrapper drives the user's own Suno
 * account, so there is no per-clip API charge — it runs on the Suno
 * subscription.
 *
 * There is no official public Suno API; this wrapper exposes a stable REST
 * surface in front of Suno's internal endpoints:
 *
 *   POST /suno/submit/music   {gpt_description_prompt|prompt, tags, title,
 *                              mv, make_instrumental}  -> { code, data: "<taskId>" }
 *   GET  /suno/fetch/{taskId}                          -> { code, data: [clip,...] }
 *
 * Each clip carries a `status` ("submitted"/"queued"/"streaming"/"complete"
 * /"error") and, once done, an `audio_url`. We poll fetch until a clip is
 * complete, then download the MP3.
 *
 * Disabled (returns null) unless app.suno.enabled=true — the assembly
 * pipeline then uses the bible's royalty-free tracks. So this is opt-in:
 * stand up the suno-api container and flip SUNO_ENABLED=true.
 */
@Slf4j
@Component
public class SunoMusicClient {

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;
    private final String model;

    public SunoMusicClient(
            @Value("${app.suno.enabled:false}") boolean enabled,
            @Value("${app.suno.base-url:http://suno-api:8000}") String baseUrl,
            @Value("${app.suno.token:}") String token,
            @Value("${app.suno.model:chirp-v3-5}") String model) {
        this.enabled = enabled;
        this.model = (model == null || model.isBlank()) ? "chirp-v3-5" : model;
        WebClient.Builder b = WebClient.builder()
                .baseUrl(baseUrl == null ? "" : baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(64 * 1024 * 1024));
        if (token != null && !token.isBlank()) {
            b.defaultHeader("Authorization", "Bearer " + token);
        }
        this.client = b.build();
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Generates an INSTRUMENTAL track from a descriptive prompt + style.
     * Uses Suno "inspiration" mode (gpt_description_prompt) so Suno composes
     * freely from the description. Blocks until complete (typically 1-3 min).
     * Returns null if disabled or generation fails — caller falls back.
     */
    public Path generate(String prompt, String style, Path outPath) {
        String desc = (prompt == null ? "" : prompt)
                + (style == null || style.isBlank() ? "" : " | style: " + style);
        ObjectNode req = mapper.createObjectNode();
        req.put("gpt_description_prompt", desc.trim());
        req.put("make_instrumental", true);
        req.put("mv", model);
        return submitAndDownload(req, outPath);
    }

    /**
     * Generates a full song WITH vocals from explicit lyrics + a style tag.
     * Uses Suno "custom" mode (prompt = lyrics, tags = style). Used by Song
     * Mode for character episodes built around a sing-along chorus.
     */
    public Path generateSong(String lyrics, String style, Path outPath) {
        ObjectNode req = mapper.createObjectNode();
        req.put("prompt", lyrics == null ? "" : lyrics);
        req.put("tags", style == null ? "" : style);
        req.put("title", "");
        req.put("make_instrumental", false);
        req.put("mv", model);
        return submitAndDownload(req, outPath);
    }

    /** Submits a /suno/submit/music task, polls /suno/fetch/{id} until a clip
     *  is complete, then downloads the MP3 to outPath. */
    private Path submitAndDownload(ObjectNode req, Path outPath) {
        if (!enabled) return null;
        try {
            JsonNode created = client.post().uri("/suno/submit/music")
                    .bodyValue(req)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(2))
                    .block();
            String taskId = extractTaskId(created);
            if (taskId == null) {
                log.warn("Suno submit returned no task id: {}", created);
                return null;
            }

            // Poll for completion (max ~5 min @ 5s intervals).
            for (int i = 0; i < 60; i++) {
                TimeUnit.SECONDS.sleep(5);
                JsonNode fetched = client.get().uri("/suno/fetch/{id}", taskId)
                        .retrieve().bodyToMono(JsonNode.class).block();
                JsonNode clips = clipsOf(fetched);
                if (clips == null || !clips.isArray()) continue;

                boolean anyError = false;
                for (JsonNode clip : clips) {
                    String status = clip.path("status").asText("");
                    String audio = firstNonBlank(
                            clip.path("audio_url").asText(null),
                            clip.path("audioUrl").asText(null));
                    if ("complete".equalsIgnoreCase(status) && audio != null && !audio.isBlank()) {
                        byte[] mp3 = WebClient.create().get().uri(audio)
                                .retrieve().bodyToMono(byte[].class).block(Duration.ofMinutes(3));
                        if (mp3 == null || mp3.length == 0) return null;
                        Files.write(outPath, mp3);
                        log.info("Suno track saved: {} ({} bytes)", outPath, mp3.length);
                        return outPath;
                    }
                    if ("error".equalsIgnoreCase(status)) anyError = true;
                }
                if (anyError) {
                    log.warn("Suno generation errored for task {}", taskId);
                    return null;
                }
            }
            log.warn("Suno generation timed out (task {})", taskId);
            return null;
        } catch (Exception e) {
            log.warn("Suno generation failed: {}", e.getMessage());
            return null;
        }
    }

    /** Submit response is { code, data: "<taskId>" } — but tolerate a few
     *  shapes (raw string, {id}, {data:{task_id}}). */
    private String extractTaskId(JsonNode resp) {
        if (resp == null) return null;
        if (resp.isTextual()) return blankToNull(resp.asText());
        JsonNode data = resp.path("data");
        if (data.isTextual()) return blankToNull(data.asText());
        for (String k : new String[]{"task_id", "taskId", "id"}) {
            if (data.path(k).isTextual()) return blankToNull(data.path(k).asText());
            if (resp.path(k).isTextual()) return blankToNull(resp.path(k).asText());
        }
        return null;
    }

    /** Fetch response is { code, data: [clip,...] }; tolerate data being the
     *  array directly or nested under data.response. */
    private JsonNode clipsOf(JsonNode resp) {
        if (resp == null) return null;
        if (resp.isArray()) return resp;
        JsonNode data = resp.path("data");
        if (data.isArray()) return data;
        if (data.path("response").isArray()) return data.path("response");
        if (resp.path("response").isArray()) return resp.path("response");
        return null;
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
    }
}
