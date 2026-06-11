package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class AssemblyServiceClient {

    private final WebClient client;

    public AssemblyServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().assembly()).build();
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
