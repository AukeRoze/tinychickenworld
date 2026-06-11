package com.youtubeauto.upload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;

/**
 * Facebook Page video uploader via Meta Graph API.
 *
 * Two paths:
 *   1. {@link #uploadFromUrl(String, String, String, String)} — caller hosts the file,
 *      we pass file_url. Fastest, no upload bandwidth from this box.
 *   2. {@link #uploadFromFile(Path, String, String, String)} — local file upload via
 *      multipart "source" field. Simpler — no S3/ngrok needed.
 *
 * Opt-in via FACEBOOK_PAGE_ACCESS_TOKEN + FACEBOOK_PAGE_ID env. Returns the
 * Facebook post/video id or null when disabled / on failure (graceful).
 *
 * Note: the Page Access Token must have pages_manage_posts +
 * pages_read_engagement scopes; generate one at developers.facebook.com.
 */
@Slf4j
@Service
public class FacebookPageService {

    private static final String API = "https://graph.facebook.com/v18.0";

    private final WebClient client;
    private final String pageId;
    private final String token;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public FacebookPageService(@Value("${app.facebook.page-access-token:}") String token,
                               @Value("${app.facebook.page-id:}") String pageId) {
        this.token = token == null ? "" : token;
        this.pageId = pageId == null ? "" : pageId;
        this.enabled = !this.token.isBlank() && !this.pageId.isBlank();
        this.client = WebClient.builder()
                .baseUrl(API)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(512 * 1024 * 1024)) // big multipart
                .build();
    }

    public boolean isEnabled() { return enabled; }

    /** URL-based path — Facebook fetches the video from a public URL. */
    public String uploadFromUrl(String fileUrl, String title, String description, String scheduledPublishUnix) {
        if (!enabled) return null;
        try {
            WebClient.RequestBodySpec spec = client.post()
                    .uri(uri -> uri.path("/{pageId}/videos").build(pageId))
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED);
            String form = "access_token=" + token
                    + "&file_url=" + java.net.URLEncoder.encode(fileUrl, java.nio.charset.StandardCharsets.UTF_8)
                    + (title       == null ? "" : "&title="       + enc(title))
                    + (description == null ? "" : "&description=" + enc(description))
                    + (scheduledPublishUnix == null ? "" :
                       "&published=false&scheduled_publish_time=" + scheduledPublishUnix);
            JsonNode resp = spec.bodyValue(form)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(10))
                    .block();
            return resp == null ? null : resp.path("id").asText(null);
        } catch (Exception e) {
            log.warn("Facebook URL-based upload failed: {}", e.getMessage());
            return null;
        }
    }

    /** Multipart upload — file lives on this box. Up to ~1GB. */
    public String uploadFromFile(Path videoPath, String title, String description, String scheduledPublishUnix) {
        if (!enabled) return null;
        if (videoPath == null || !Files.exists(videoPath)) {
            log.warn("Facebook upload aborted — file missing: {}", videoPath);
            return null;
        }
        try {
            MultipartBodyBuilder mb = new MultipartBodyBuilder();
            mb.part("access_token", token);
            mb.part("source", new FileSystemResource(videoPath.toFile()))
                    .contentType(MediaType.APPLICATION_OCTET_STREAM);
            if (title != null)       mb.part("title", title);
            if (description != null) mb.part("description", description);
            if (scheduledPublishUnix != null) {
                mb.part("published", "false");
                mb.part("scheduled_publish_time", scheduledPublishUnix);
            }

            JsonNode resp = client.post()
                    .uri(uri -> uri.path("/{pageId}/videos").build(pageId))
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(BodyInserters.fromMultipartData(mb.build()))
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofMinutes(30))
                    .block();
            if (resp == null) return null;
            String id = resp.path("id").asText(null);
            log.info("Facebook video posted → id={}", id);
            return id;
        } catch (Exception e) {
            log.warn("Facebook multipart upload failed: {}", e.getMessage());
            return null;
        }
    }

    /** Posts the video as a Facebook Reel (vertical format only). */
    public String postReel(Path videoPath, String description) {
        if (!enabled) return null;
        if (videoPath == null || !Files.exists(videoPath)) return null;
        try {
            // Step 1 — initialize the upload session.
            JsonNode init = client.post()
                    .uri(uri -> uri.path("/{pageId}/video_reels")
                            .queryParam("upload_phase", "start")
                            .queryParam("access_token", token)
                            .build(pageId))
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (init == null) return null;
            String videoId  = init.path("video_id").asText();
            String uploadUrl = init.path("upload_url").asText();
            if (videoId.isEmpty() || uploadUrl.isEmpty()) return null;

            // Step 2 — upload bytes to the resumable URL.
            byte[] bytes = Files.readAllBytes(videoPath);
            WebClient.create().post()
                    .uri(uploadUrl)
                    .header("Authorization", "OAuth " + token)
                    .header("offset", "0")
                    .header("file_size", String.valueOf(bytes.length))
                    .bodyValue(bytes)
                    .retrieve().bodyToMono(String.class)
                    .timeout(Duration.ofMinutes(30))
                    .block();

            // Step 3 — finish + publish.
            String desc = description == null ? "" : enc(description);
            JsonNode pub = client.post()
                    .uri(uri -> uri.path("/{pageId}/video_reels")
                            .queryParam("access_token", token)
                            .queryParam("video_id", videoId)
                            .queryParam("upload_phase", "finish")
                            .queryParam("video_state", "PUBLISHED")
                            .queryParam("description", desc)
                            .build(pageId))
                    .retrieve().bodyToMono(JsonNode.class).block();
            return pub == null ? null : videoId;
        } catch (Exception e) {
            log.warn("Facebook Reel post failed: {}", e.getMessage());
            return null;
        }
    }

    /** Fetches Page insights for a video — views, reactions, comments, shares.
     *  Returns null if disabled or on error. */
    public JsonNode getVideoInsights(String videoId) {
        if (!enabled) return null;
        try {
            return client.get()
                    .uri(uri -> uri.path("/{videoId}/video_insights")
                            .queryParam("metric", "total_video_views,total_video_impressions,"
                                    + "total_video_avg_time_watched,total_video_reactions_by_type_total")
                            .queryParam("access_token", token)
                            .build(videoId))
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(30))
                    .block();
        } catch (Exception e) {
            log.warn("Facebook insights fetch failed: {}", e.getMessage());
            return null;
        }
    }

    private static String enc(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8);
    }
}
