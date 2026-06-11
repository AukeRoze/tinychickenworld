package com.youtubeauto.upload.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

/**
 * Uploads videos to TikTok via the Content Posting API. Requires a
 * separate OAuth flow (TikTok for Developers).
 *
 * Falls back to no-op when TIKTOK_ACCESS_TOKEN isn't set — the rest of
 * the pipeline keeps working without TikTok.
 *
 * Setup:
 *   1. Register an app at https://developers.tiktok.com/
 *   2. Get an access token (long-lived OR refresh-token flow)
 *   3. Drop TIKTOK_ACCESS_TOKEN in .env
 */
@Slf4j
@Service
public class TikTokUploadService {

    private static final String API = "https://open.tiktokapis.com/v2";

    private final WebClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final boolean enabled;

    public TikTokUploadService(@Value("${app.tiktok.access-token:}") String token) {
        this.enabled = token != null && !token.isBlank();
        this.client = WebClient.builder()
                .baseUrl(API)
                .defaultHeader("Authorization", "Bearer " + (token == null ? "" : token))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(200 * 1024 * 1024))
                .build();
    }

    public boolean isEnabled() { return enabled; }

    /**
     * Uploads a vertical 9:16 short to TikTok. Returns the TikTok publish_id
     * or null on failure. Pattern: init upload → resumable file PUT →
     * commit with caption.
     */
    public String uploadShort(Path videoPath, String caption) {
        if (!enabled) {
            log.debug("TikTok upload skipped — no access token configured");
            return null;
        }
        try {
            long size = Files.size(videoPath);
            // Step 1: init
            ObjectNode initBody = mapper.createObjectNode();
            ObjectNode source = initBody.putObject("source_info");
            source.put("source", "FILE_UPLOAD");
            source.put("video_size", size);
            source.put("chunk_size", size);
            source.put("total_chunk_count", 1);
            ObjectNode postInfo = initBody.putObject("post_info");
            postInfo.put("title", caption);
            postInfo.put("privacy_level", "SELF_ONLY");   // start safe; user can switch
            postInfo.put("disable_duet", false);
            postInfo.put("disable_comment", false);
            postInfo.put("disable_stitch", false);
            JsonNode init = client.post().uri("/post/publish/inbox/video/init/")
                    .header("Content-Type", "application/json")
                    .bodyValue(initBody)
                    .retrieve().bodyToMono(JsonNode.class)
                    .timeout(Duration.ofSeconds(60))
                    .block();
            if (init == null) return null;
            String uploadUrl = init.path("data").path("upload_url").asText();
            String publishId = init.path("data").path("publish_id").asText();

            // Step 2: PUT the file bytes
            byte[] bytes = Files.readAllBytes(videoPath);
            WebClient.create().put().uri(uploadUrl)
                    .header("Content-Type", "video/mp4")
                    .header("Content-Range", "bytes 0-" + (size - 1) + "/" + size)
                    .bodyValue(bytes)
                    .retrieve().bodyToMono(Void.class)
                    .timeout(Duration.ofMinutes(10))
                    .block();

            log.info("TikTok upload submitted: publish_id={}", publishId);
            return publishId;
        } catch (Exception e) {
            log.warn("TikTok upload failed: {}", e.getMessage());
            return null;
        }
    }
}
