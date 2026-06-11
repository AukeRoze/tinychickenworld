package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.client.UploadServiceClient;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Surfaces the already-built multi-platform distribution backend
 * ({@code MultiPlatformController} in youtube-upload-service) to the dashboard.
 * One finished master → TikTok, Instagram Reels, Facebook, plus copy-paste
 * community-post ideas and an end-screen recipe.
 *
 * Each platform is opt-in via env vars on the upload-service; when a token is
 * missing the client returns null and we report "not configured or failed".
 */
@RestController
@RequestMapping("/api/v1/videos/{id}/distribute")
@RequiredArgsConstructor
public class DistributionController {

    private final VideoJobRepository repo;
    private final UploadServiceClient uploadClient;

    @PostMapping("/{platform}")
    public ResponseEntity<Map<String, Object>> distribute(@PathVariable UUID id,
                                                          @PathVariable String platform) {
        VideoJob job = repo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.getVideoPath() == null || job.getVideoPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "No master video yet"));
        }
        String caption = job.getMetadataTitle() != null ? job.getMetadataTitle() : job.getTopic();
        String description = job.getMetadataDescription() != null ? job.getMetadataDescription() : "";

        JsonNode res;
        switch (platform.toLowerCase()) {
            case "tiktok" -> res = uploadClient.distributeTikTok(job.getVideoPath(), caption);
            case "instagram" -> {
                if (job.getYoutubeUrl() == null || job.getYoutubeUrl().isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Instagram needs a public URL — upload to YouTube first"));
                }
                res = uploadClient.distributeInstagram(job.getYoutubeUrl(), caption);
            }
            case "facebook" -> {
                res = uploadClient.distributeFacebook(job.getVideoPath(), caption, description, null);
                // Persist the Facebook IDs so the dashboard can show + link them.
                if (res != null && res.path("success").asBoolean(false)) {
                    job.setFacebookVideoId(res.path("postId").asText(null));
                    job.setFacebookUrl(res.path("url").asText(null));
                    repo.save(job);
                }
            }
            default -> { return ResponseEntity.badRequest().body(Map.of("error", "Unknown platform: " + platform)); }
        }
        if (res == null) {
            return ResponseEntity.status(502).body(Map.of(
                    "error", platform + " not configured or upload failed",
                    "platform", platform));
        }
        return ResponseEntity.ok(Map.of("platform", platform, "result", res));
    }

    @GetMapping("/community-posts")
    public ResponseEntity<JsonNode> communityPosts(@PathVariable UUID id) {
        VideoJob job = repo.findById(id).orElse(null);
        String topic = job == null ? null : job.getTopic();
        JsonNode res = uploadClient.communityPosts(topic, null);
        return res == null ? ResponseEntity.status(502).build() : ResponseEntity.ok(res);
    }

    @GetMapping("/end-screen-recipe")
    public ResponseEntity<JsonNode> endScreenRecipe(@PathVariable UUID id) {
        JsonNode res = uploadClient.endScreenRecipe();
        return res == null ? ResponseEntity.status(502).build() : ResponseEntity.ok(res);
    }
}
