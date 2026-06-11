package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Component
public class ImageServiceClient {

    private final WebClient client;

    public ImageServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().image()).build();
    }

    public JsonNode generate(UUID jobId, List<Map<String, Object>> scenes, String format) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("scenes", scenes);
        body.put("format", format);
        // Image gen for a full scene list can legitimately run minutes; the
        // timeout exists to kill a HUNG call, not a slow one. Paid profile:
        // never re-fires unless the request provably never arrived.
        return Resilience.paid(
                client.post().uri("/api/v1/images/generate")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofMinutes(15), "image-service generate");
    }
}
