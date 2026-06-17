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

    public JsonNode generate(UUID jobId, String topic, String title, String hook,
                             List<String> baseImagePaths, String preferredLayout,
                             String customHint) {
        return generate(jobId, topic, title, hook, baseImagePaths, preferredLayout,
                customHint, null);
    }

    /** Full form incl. an optional reviewer direction (dashboard "regenerate
     *  with prompt", e.g. "exactly three chicks") and the ground-truth cast
     *  ({@code castPresent}: bible ids die echt substantieel in de scènes
     *  zitten — ≥2 ids laat de thumbnail-service een groeps-thumbnail kiezen,
     *  ook als titel/topic niemand bij naam noemt). */
    public JsonNode generate(UUID jobId, String topic, String title, String hook,
                             List<String> baseImagePaths, String preferredLayout,
                             String customHint, List<String> castPresent) {
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
        if (castPresent != null && !castPresent.isEmpty())
            body.put("castPresent", castPresent);
        // 3 variants × image gen — minutes, not seconds. Paid profile.
        return Resilience.paid(
                client.post().uri("/api/v1/thumbnails/generate")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofMinutes(8), "thumbnail-service generate");
    }

    /** Preview-only: the assembled thumbnail prompt(s) per variant (no image
     *  generation). Cheap + idempotent — drives the dashboard "copy thumbnail
     *  prompt" view, mirroring {@code ImageServiceClient.previewPrompts}. */
    public JsonNode previewPrompt(UUID jobId, String topic, String title, String hook,
                                  List<String> baseImagePaths, String preferredLayout,
                                  String customHint, List<String> castPresent) {
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
        if (castPresent != null && !castPresent.isEmpty())
            body.put("castPresent", castPresent);
        return Resilience.idempotent(
                client.post().uri("/api/v1/thumbnails/preview-prompt")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofSeconds(30), "thumbnail-service preview-prompt");
    }
}
