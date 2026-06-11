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
public class UploadServiceClient {

    private final WebClient client;

    public UploadServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().upload()).build();
    }

    /** Proxies the upload-service's OAuth-token health (the UI can't reach
     *  port 8085 directly). Null on any error — shown as "unknown" in the UI. */
    public JsonNode oauthHealth() {
        try {
            return client.get().uri("/api/v1/upload/oauth-health")
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(5)).block();
        } catch (Exception e) {
            return null;
        }
    }

    public JsonNode upload(UUID jobId, String videoPath, String thumbnailPath,
                           String title, String description, List<String> tags,
                           String privacyStatus, java.time.OffsetDateTime publishAt,
                           String captionsPath) {
        Map<String, Object> body = new HashMap<>();
        body.put("jobId", jobId);
        body.put("videoPath", videoPath);
        body.put("thumbnailPath", thumbnailPath);
        body.put("title", title);
        body.put("description", description);
        body.put("tags", tags);
        body.put("privacyStatus", privacyStatus);
        if (publishAt != null) body.put("publishAt", publishAt.toString());
        if (captionsPath != null && !captionsPath.isBlank()) body.put("captionsPath", captionsPath);

        return client.post().uri("/api/v1/upload")
                .bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class)
                .timeout(Duration.ofMinutes(30))
                .block();
    }

    /** Cross-post the finished master to Facebook Page. Returns null when
     *  Facebook isn't configured (503) or upload failed. */
    public JsonNode distributeFacebook(String videoPath, String title, String description,
                                       String scheduledPublishUnix) {
        try {
            org.springframework.web.util.UriComponentsBuilder u =
                    org.springframework.web.util.UriComponentsBuilder.fromPath("/api/v1/distribute/facebook")
                            .queryParam("videoPath", videoPath)
                            .queryParam("title", title);
            if (description != null)         u.queryParam("description", description);
            if (scheduledPublishUnix != null) u.queryParam("scheduledPublishUnix", scheduledPublishUnix);
            return client.post().uri(u.build().toUriString())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Cross-post the finished master to TikTok. Returns null when TikTok
     *  isn't configured (503) or upload failed. */
    public JsonNode distributeTikTok(String videoPath, String caption) {
        try {
            var u = org.springframework.web.util.UriComponentsBuilder
                    .fromPath("/api/v1/distribute/tiktok")
                    .queryParam("videoPath", videoPath)
                    .queryParam("caption", caption == null ? "" : caption);
            return client.post().uri(u.build().toUriString())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Cross-post the finished master to Instagram Reels. Needs a public URL
     *  (e.g. the YouTube URL). Returns null when not configured or failed. */
    public JsonNode distributeInstagram(String publicVideoUrl, String caption) {
        try {
            var u = org.springframework.web.util.UriComponentsBuilder
                    .fromPath("/api/v1/distribute/instagram")
                    .queryParam("publicVideoUrl", publicVideoUrl == null ? "" : publicVideoUrl)
                    .queryParam("caption", caption == null ? "" : caption);
            return client.post().uri(u.build().toUriString())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Copy-paste community-post ideas for the channel (YouTube API can't post
     *  these). Returns null on error. */
    public JsonNode communityPosts(String latestTopic, String nextTopic) {
        try {
            var u = org.springframework.web.util.UriComponentsBuilder
                    .fromPath("/api/v1/distribute/community-posts");
            if (latestTopic != null) u.queryParam("latestTopic", latestTopic);
            if (nextTopic != null)   u.queryParam("nextTopic", nextTopic);
            return client.get().uri(u.build().toUriString())
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** End-screen card recipe (what to put on the last 20s). Returns null on error. */
    public JsonNode endScreenRecipe() {
        try {
            return client.get().uri("/api/v1/distribute/end-screen-recipe")
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetch Facebook video insights — views, reactions, avg watch time. */
    public JsonNode facebookInsights(String fbVideoId) {
        try {
            return client.get().uri("/api/v1/distribute/facebook/insights/{id}", fbVideoId)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Audience-retention curve from the YouTube Analytics API.
     *  Returns null on error / scope not granted (loop degrades gracefully). */
    public JsonNode retention(String youtubeVideoId) {
        try {
            return client.get().uri("/api/v1/analytics/retention/{vid}", youtubeVideoId)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Engagement metrics (views, AVD, averageViewPercentage) from the
     *  YouTube Analytics API. Returns null on error. */
    public JsonNode engagement(String youtubeVideoId) {
        try {
            return client.get().uri("/api/v1/analytics/engagement/{vid}", youtubeVideoId)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(45))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }

    /** Fetches latest YouTube stats for a video. Returns null on error. */
    public JsonNode stats(String youtubeVideoId) {
        try {
            return client.get().uri("/api/v1/upload/stats/{vid}", youtubeVideoId)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            return null;
        }
    }
}
