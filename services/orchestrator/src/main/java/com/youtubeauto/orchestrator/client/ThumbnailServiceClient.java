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
public class ThumbnailServiceClient {

    private final WebClient client;

    public ThumbnailServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().thumbnail()).build();
    }

    public JsonNode generate(UUID jobId, String topic, String title, String hook,
                             List<String> baseImagePaths) {
        return generate(jobId, topic, title, hook, baseImagePaths, null);
    }

    public JsonNode generate(UUID jobId, String topic, String title, String hook,
                             List<String> baseImagePaths, String preferredLayout) {
        return generate(jobId, topic, title, hook, baseImagePaths, preferredLayout, null);
    }

    /** Full form incl. an optional reviewer direction (dashboard "regenerate
     *  with prompt", e.g. "exactly three chicks"). */
    public JsonNode generate(UUID jobId, String topic, String title, String hook,
                             List<String> baseImagePaths, String preferredLayout,
                             String customHint) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("topic", topic);
        body.put("title", title);
        body.put("hook", hook);
        body.put("baseImagePaths", baseImagePaths == null ? List.of() : baseImagePaths);
        if (preferredLayout != null && !preferredLayout.isBlank())
            body.put("preferredLayout", preferredLayout);
        if (customHint != null && !customHint.isBlank())
            body.put("customHint", customHint.trim());
        // 3 variants × image gen — minutes, not seconds. Paid profile.
        return Resilience.paid(
                client.post().uri("/api/v1/thumbnails/generate")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofMinutes(8), "thumbnail-service generate");
    }
}
