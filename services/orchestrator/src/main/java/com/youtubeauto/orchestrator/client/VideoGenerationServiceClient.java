package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous call to the video-generation-service. Returns per-scene
 * results; the orchestrator persists them and the assembly stage picks
 * up clip.mp4 paths where status=OK.
 */
@Component
public class VideoGenerationServiceClient {

    private final WebClient client;

    public VideoGenerationServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().videoGen()).build();
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
}
