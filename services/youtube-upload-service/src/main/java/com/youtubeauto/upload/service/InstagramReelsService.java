package com.youtubeauto.upload.service;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Uploads vertical Shorts to Instagram Reels via the Meta Graph API.
 *
 * Requires:
 *   - A Facebook page connected to an Instagram Business/Creator account
 *   - A long-lived Page Access Token with instagram_basic + instagram_content_publish
 *   - The Instagram Business Account ID
 *
 * Pattern:
 *   1. POST /{ig-user-id}/media (media_type=REELS, video_url=<public URL>)
 *      — Instagram pulls the video from a public URL (not direct upload)
 *   2. Wait for status_code = FINISHED
 *   3. POST /{ig-user-id}/media_publish to actually post it
 *
 * Falls back to no-op when not configured.
 */
@Slf4j
@Service
public class InstagramReelsService {

    private static final String API = "https://graph.facebook.com/v18.0";

    private final WebClient client;
    private final String igUserId;
    private final String accessToken;
    private final boolean enabled;

    public InstagramReelsService(
            @Value("${app.instagram.access-token:}") String token,
            @Value("${app.instagram.user-id:}") String userId) {
        this.accessToken = token;
        this.igUserId    = userId;
        this.enabled = token != null && !token.isBlank()
                && userId != null && !userId.isBlank();
        this.client = WebClient.builder()
                .baseUrl(API)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    public boolean isEnabled() { return enabled; }

    /**
     * IMPORTANT: Instagram doesn't accept direct uploads — it requires a
     * publicly-reachable URL where the video lives. Caller must host the
     * file (eg short-lived S3 signed URL or via local ngrok during dev)
     * and pass that URL here.
     */
    public String publishReel(String publicVideoUrl, String caption) {
        if (!enabled) {
            log.debug("Instagram upload skipped — not configured");
            return null;
        }
        try {
            JsonNode create = client.post()
                    .uri(uri -> uri.path("/" + igUserId + "/media")
                            .queryParam("media_type", "REELS")
                            .queryParam("video_url", publicVideoUrl)
                            .queryParam("caption", caption)
                            .queryParam("access_token", accessToken)
                            .build())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(2))
                    .block();
            if (create == null) return null;
            String creationId = create.path("id").asText();

            // Poll until upload-side finishes processing
            for (int i = 0; i < 30; i++) {
                TimeUnit.SECONDS.sleep(3);
                JsonNode status = client.get()
                        .uri(uri -> uri.path("/" + creationId)
                                .queryParam("fields", "status_code")
                                .queryParam("access_token", accessToken)
                                .build())
                        .retrieve().bodyToMono(JsonNode.class).block();
                if (status != null && "FINISHED".equals(status.path("status_code").asText())) {
                    break;
                }
            }

            JsonNode published = client.post()
                    .uri(uri -> uri.path("/" + igUserId + "/media_publish")
                            .queryParam("creation_id", creationId)
                            .queryParam("access_token", accessToken)
                            .build())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(2))
                    .block();
            if (published == null) return null;
            String id = published.path("id").asText();
            log.info("Instagram Reel published: {}", id);
            return id;
        } catch (Exception e) {
            log.warn("Instagram publish failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Best-effort permalink lookup for a published media id:
     * {@code GET /{mediaId}?fields=permalink} on the same Graph API client,
     * version and access token as the publish flow above. Returns the public
     * {@code https://www.instagram.com/reel/...} URL, or null when the lookup
     * fails or the field is absent — the publish itself stays successful
     * either way; callers must treat null as "no link available", not as an
     * error.
     */
    public String fetchPermalink(String mediaId) {
        if (!enabled || mediaId == null || mediaId.isBlank()) return null;
        try {
            JsonNode media = client.get()
                    .uri(uri -> uri.path("/" + mediaId)
                            .queryParam("fields", "permalink")
                            .queryParam("access_token", accessToken)
                            .build())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
            String permalink = media == null ? "" : media.path("permalink").asText("");
            if (permalink.isBlank()) {
                log.info("Instagram permalink lookup for {} returned no permalink", mediaId);
                return null;
            }
            return permalink;
        } catch (Exception e) {
            log.warn("Instagram permalink lookup failed for {} (publish itself succeeded): {}",
                    mediaId, e.getMessage());
            return null;
        }
    }
}
