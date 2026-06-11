package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The WebClient is built once at construction with its baseUrl. Sharing
 * the WebClient.Builder bean across multiple per-request {@code baseUrl(...)}
 * mutations races with parallel calls (voice + image run concurrently in
 * runAssetsStage) and lets one client steal the other's baseUrl.
 */
@Component
public class VoiceServiceClient {

    private final WebClient client;

    public VoiceServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().voice()).build();
    }

    public JsonNode synthesize(UUID jobId, List<Map<String, Object>> scenes) {
        // ElevenLabs over ~27 scenes can take minutes; timeout kills hangs only.
        // Paid profile: retry only when the request provably never arrived.
        return Resilience.paid(
                client.post().uri("/api/v1/voice/synthesize")
                        .bodyValue(Map.of("jobId", jobId, "scenes", scenes))
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofMinutes(15), "voice-service synthesize");
    }
}
