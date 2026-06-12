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
            case "tiktok" -> {
                res = uploadClient.distributeTikTok(job.getVideoPath(), caption);
                // Persist the TikTok publish-id so the job page can show an
                // honest status chip (best-effort; the push already succeeded).
                if (res != null && res.path("success").asBoolean(false)) {
                    try {
                        job.setTiktokPublishId(blankToNull(res.path("publishId").asText(null)));
                        repo.save(job);
                    } catch (Exception ignore) { /* status chip only — never fail the push */ }
                }
            }
            case "instagram" -> {
                if (job.getYoutubeUrl() == null || job.getYoutubeUrl().isBlank()) {
                    return ResponseEntity.badRequest().body(Map.of(
                            "error", "Instagram needs a public URL — upload to YouTube first"));
                }
                res = uploadClient.distributeInstagram(job.getYoutubeUrl(), caption);
                // Persist the Instagram media-id + the best-effort permalink the
                // upload-service now looks up after publish (V25). A missing
                // permalink ("" in the response) stays null — the push itself
                // already succeeded, the chip just won't be a link.
                if (res != null && res.path("success").asBoolean(false)) {
                    try {
                        job.setInstagramMediaId(blankToNull(res.path("mediaId").asText(null)));
                        job.setInstagramUrl(blankToNull(res.path("permalink").asText(null)));
                        repo.save(job);
                    } catch (Exception ignore) { /* status chip only — never fail the push */ }
                }
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

    /** The upload-service sends "" for a missing id — store null, not "". */
    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s;
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
