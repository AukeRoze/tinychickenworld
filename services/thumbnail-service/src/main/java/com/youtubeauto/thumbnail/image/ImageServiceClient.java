package com.youtubeauto.thumbnail.image;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Calls image-service {@code POST /api/v1/images/thumbnail} to render thumbnail
 * bases from the cast's REFERENCE ANCHORS (the same Gemini pipeline the film
 * uses). This is what closes the thumbnail-vs-film gap: the thumbnail chicks are
 * the exact same characters as the video, just framed as a CTR close-up.
 *
 * Best-effort: any failure (image-service down, no Gemini key, empty response)
 * returns an empty list so the caller falls back to the legacy OpenAI base.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ImageServiceClient {

    private final WebClient imageServiceWebClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** A thumbnail "scene" — one CTR variant. */
    public record ThumbScene(int seq, String visualDesc, List<String> characters,
                             String cameraFraming) {}

    /**
     * @return absolute base-image paths ordered by variant seq, or an empty
     *         list if generation was unavailable (→ caller uses OpenAI fallback).
     */
    public List<String> generateThumbnailBases(UUID jobId, List<ThumbScene> scenes, String format) {
        if (scenes == null || scenes.isEmpty()) return List.of();
        try {
            ObjectNode body = mapper.createObjectNode();
            body.put("jobId", jobId.toString());
            body.put("format", format);
            ArrayNode arr = body.putArray("scenes");
            for (ThumbScene s : scenes) {
                ObjectNode n = arr.addObject();
                n.put("seq", s.seq());
                n.put("visualDesc", s.visualDesc());
                if (s.cameraFraming() != null) n.put("cameraFraming", s.cameraFraming());
                ArrayNode chars = n.putArray("characters");
                if (s.characters() != null) s.characters().forEach(chars::add);
            }

            JsonNode resp = imageServiceWebClient.post()
                    .uri("/api/v1/images/thumbnail")
                    .bodyValue(body)
                    .retrieve()
                    .bodyToMono(JsonNode.class)
                    .block(Duration.ofMinutes(3));

            if (resp == null) return List.of();
            // Order by seq so variant N lines up with mood N.
            TreeMap<Integer, String> bySeq = new TreeMap<>();
            for (JsonNode s : resp.path("scenes")) {
                String path = s.path("imagePath").asText("");
                if (!path.isBlank()) bySeq.put(s.path("seq").asInt(), path);
            }
            List<String> paths = new ArrayList<>(bySeq.values());
            log.info("job={} image-service returned {} anchor thumbnail base(s)", jobId, paths.size());
            return paths;
        } catch (Exception e) {
            log.warn("job={} anchor thumbnail-base generation failed ({}) — falling back to OpenAI",
                    jobId, e.toString());
            return List.of();
        }
    }
}
