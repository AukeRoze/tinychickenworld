package com.youtubeauto.upload.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.upload.service.CommunityPostService;
import com.youtubeauto.upload.service.EndScreenService;
import com.youtubeauto.upload.service.FacebookPageService;
import com.youtubeauto.upload.service.InstagramReelsService;
import com.youtubeauto.upload.service.TikTokUploadService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * Endpoints for the extra distribution channels: TikTok, Instagram Reels,
 * YouTube Community tab (manual), End Screen card recipes.
 *
 * Each is opt-in via env vars; calling without the config returns 503.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class MultiPlatformController {

    private final TikTokUploadService tiktok;
    private final InstagramReelsService instagram;
    private final FacebookPageService facebook;
    private final EndScreenService endScreen;
    private final CommunityPostService community;
    private final com.youtubeauto.upload.service.OAuthHealthProbe oauthProbe;
    private final com.youtubeauto.upload.service.PlaylistService playlist;
    private final com.youtubeauto.upload.service.ChannelBannerService channelBanner;

    /** YouTube OAuth-token health (probed twice a day). healthy=false means the
     *  next upload WILL fail — delete StoredCredential and re-consent. */
    @org.springframework.web.bind.annotation.GetMapping("/upload/oauth-health")
    public Map<String, Object> oauthHealth() {
        Map<String, Object> out = new java.util.HashMap<>();
        out.put("healthy", oauthProbe.isHealthy());
        out.put("lastError", oauthProbe.lastError());
        out.put("checkedAt", oauthProbe.checkedAt() == null ? null : oauthProbe.checkedAt().toString());
        return out;
    }

    /** videoPath comes over HTTP — only files inside the shared workdir may be
     *  uploaded to external platforms (otherwise this endpoint could exfiltrate
     *  any file the container can read, e.g. /secrets). */
    private static Path safeWorkdirPath(String videoPath) {
        Path p = Path.of(videoPath).normalize();
        if (!p.startsWith(Path.of("/workdir"))) {
            throw new IllegalArgumentException("videoPath must live under /workdir (was: " + p + ")");
        }
        return p;
    }

    /** Instagram needs a PUBLIC url; restrict to https + a sane host pattern so
     *  this can't be pointed at internal services (SSRF). */
    private static String safePublicUrl(String url) {
        if (url == null || !url.matches("https://[A-Za-z0-9.-]+\\.[A-Za-z]{2,}(/\\S*)?")
                || url.contains("localhost") || url.matches(".*//(\\d{1,3}\\.){3}\\d{1,3}.*")) {
            throw new IllegalArgumentException("publicVideoUrl must be a public https URL (was: " + url + ")");
        }
        return url;
    }

    @PostMapping("/distribute/tiktok")
    public ResponseEntity<Map<String, Object>> tiktok(
            @RequestParam String videoPath,
            @RequestParam String caption) {
        if (!tiktok.isEnabled()) return ResponseEntity.status(503)
                .body(Map.of("error", "TikTok not configured (set TIKTOK_ACCESS_TOKEN)"));
        String id = tiktok.uploadShort(safeWorkdirPath(videoPath), caption);
        Map<String, Object> resp = new HashMap<>();
        resp.put("platform", "tiktok");
        resp.put("publishId", id == null ? "" : id);
        resp.put("success", id != null);
        return ResponseEntity.ok(resp);
    }

    @PostMapping("/distribute/instagram")
    public ResponseEntity<Map<String, Object>> instagram(
            @RequestParam String publicVideoUrl,
            @RequestParam String caption) {
        if (!instagram.isEnabled()) return ResponseEntity.status(503)
                .body(Map.of("error", "Instagram not configured"));
        String id = instagram.publishReel(safePublicUrl(publicVideoUrl), caption);
        Map<String, Object> resp = new HashMap<>();
        resp.put("platform", "instagram");
        resp.put("mediaId", id == null ? "" : id);
        resp.put("success", id != null);
        // Best-effort permalink (additive): GET /{mediaId}?fields=permalink.
        // A failed lookup leaves it "" — the publish itself stays successful.
        String permalink = id == null ? null : instagram.fetchPermalink(id);
        resp.put("permalink", permalink == null ? "" : permalink);
        return ResponseEntity.ok(resp);
    }

    /**
     * Upload to a Facebook Page. Multipart from local file — no public URL needed.
     * Optional scheduledPublishUnix (seconds since epoch, 10min-29days in future)
     * for Facebook's native scheduled-publish.
     */
    @PostMapping("/distribute/facebook")
    public ResponseEntity<Map<String, Object>> facebook(
            @RequestParam String videoPath,
            @RequestParam String title,
            @RequestParam(required = false) String description,
            @RequestParam(required = false) String scheduledPublishUnix) {
        if (!facebook.isEnabled()) return ResponseEntity.status(503)
                .body(Map.of("error", "Facebook not configured (set FACEBOOK_PAGE_ACCESS_TOKEN + FACEBOOK_PAGE_ID)"));
        String id = facebook.uploadFromFile(safeWorkdirPath(videoPath), title, description, scheduledPublishUnix);
        Map<String, Object> resp = new HashMap<>();
        resp.put("platform", "facebook");
        resp.put("postId", id == null ? "" : id);
        resp.put("success", id != null);
        if (id != null) resp.put("url", "https://www.facebook.com/" + id);
        return ResponseEntity.ok(resp);
    }

    /** Post a vertical video as a Facebook Reel. */
    @PostMapping("/distribute/facebook/reel")
    public ResponseEntity<Map<String, Object>> facebookReel(
            @RequestParam String videoPath,
            @RequestParam(required = false) String description) {
        if (!facebook.isEnabled()) return ResponseEntity.status(503)
                .body(Map.of("error", "Facebook not configured"));
        String id = facebook.postReel(safeWorkdirPath(videoPath), description);
        Map<String, Object> resp = new HashMap<>();
        resp.put("platform", "facebook-reel");
        resp.put("videoId", id == null ? "" : id);
        resp.put("success", id != null);
        return ResponseEntity.ok(resp);
    }

    /** Page insights for a posted video (views, reactions, avg watch time, …). */
    @GetMapping("/distribute/facebook/insights/{videoId}")
    public ResponseEntity<JsonNode> facebookInsights(@PathVariable String videoId) {
        if (!facebook.isEnabled()) return ResponseEntity.status(503).build();
        JsonNode body = facebook.getVideoInsights(videoId);
        return body == null ? ResponseEntity.status(502).build() : ResponseEntity.ok(body);
    }

    /**
     * Add an already-uploaded YouTube video to a channel playlist by title
     * (series feature). Body: {"videoId": "...", "playlistTitle": "...",
     * "playlistDescription": "..."} — description only used when the playlist
     * is created. Idempotent: a video that is already in the playlist returns
     * added=false. Errors come back as a JSON body with an "error" message,
     * never a bare 500 stacktrace (the orchestrator treats this best-effort).
     */
    @PostMapping("/distribute/playlist")
    public ResponseEntity<Map<String, Object>> addToPlaylist(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("platform", "youtube-playlist");
        try {
            com.youtubeauto.upload.service.PlaylistService.Result r = playlist.addToPlaylist(
                    body.get("videoId"),
                    body.get("playlistTitle"),
                    body.get("playlistDescription"));
            resp.put("playlistId", r.playlistId());
            resp.put("added", r.added());
            resp.put("playlistCreated", r.playlistCreated());
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage() == null ? "playlist add failed" : e.getMessage());
            return ResponseEntity.status(502).body(resp);
        }
    }

    /**
     * Replace the channel's YouTube banner (channel art). Body:
     * {"bannerPath": "/workdir/.../youtube_banner.jpg"} — must live under the
     * shared workdir (same exfiltration guard as the video endpoints). Two
     * API calls: channelBanners.insert (resumable upload → temporary url),
     * then channels.update(brandingSettings) on the own channel to make it
     * live. Errors come back as a JSON body with an "error" message, like the
     * playlist endpoint (the orchestrator surfaces it in the Brand UI).
     */
    @PostMapping("/distribute/channel-banner")
    public ResponseEntity<Map<String, Object>> channelBanner(@RequestBody Map<String, String> body) {
        Map<String, Object> resp = new HashMap<>();
        resp.put("platform", "youtube-channel-banner");
        try {
            String bannerPath = body == null ? null : body.get("bannerPath");
            if (bannerPath == null || bannerPath.isBlank()) {
                throw new IllegalArgumentException("bannerPath is required");
            }
            com.youtubeauto.upload.service.ChannelBannerService.Result r =
                    channelBanner.uploadBanner(safeWorkdirPath(bannerPath));
            resp.put("bannerUrl", r.bannerUrl());
            resp.put("channelId", r.channelId());
            resp.put("success", true);
            return ResponseEntity.ok(resp);
        } catch (IllegalArgumentException e) {
            resp.put("success", false);
            resp.put("error", e.getMessage());
            return ResponseEntity.badRequest().body(resp);
        } catch (Exception e) {
            resp.put("success", false);
            resp.put("error", e.getMessage() == null ? "channel banner upload failed" : e.getMessage());
            return ResponseEntity.status(502).body(resp);
        }
    }

    @GetMapping("/distribute/end-screen-recipe")
    public Map<String, Object> endScreenRecipe() {
        return endScreen.recipe();
    }

    @GetMapping("/distribute/community-posts")
    public Map<String, Object> communityPosts(
            @RequestParam(required = false) String latestTopic,
            @RequestParam(required = false) String nextTopic) {
        return community.generatePostIdeas(latestTopic, nextTopic);
    }
}
