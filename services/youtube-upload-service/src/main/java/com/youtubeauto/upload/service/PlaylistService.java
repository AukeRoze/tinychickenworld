package com.youtubeauto.upload.service;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Playlist;
import com.google.api.services.youtube.model.PlaylistItem;
import com.google.api.services.youtube.model.PlaylistItemSnippet;
import com.google.api.services.youtube.model.PlaylistSnippet;
import com.google.api.services.youtube.model.PlaylistStatus;
import com.google.api.services.youtube.model.ResourceId;
import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Adds an uploaded video to a channel playlist by TITLE — the orchestrator's
 * series feature: every episode of a bible series lands in one public playlist
 * named after the series.
 *
 * Lookup is by exact title match (case-insensitive, trimmed) over the
 * channel's own playlists; a missing playlist is created on the fly
 * (privacyStatus=public). The whole operation is idempotent: when the video
 * is already in the playlist we return added=false instead of duplicating it.
 *
 * Scope note: playlists.* / playlistItems.* are covered by the already-granted
 * youtube.force-ssl scope — no re-consent needed.
 *
 * NOTE (Shorts): the orchestrator deliberately never calls this for vertical
 * (Shorts) jobs — vertical videos pollute a landscape series playlist.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PlaylistService {

    /** Outcome: added=false means the video was already in the playlist. */
    public record Result(String playlistId, boolean added, boolean playlistCreated) {}

    /** playlistItems pages scanned for the idempotency check (50 items each).
     *  Beyond ~100 episodes a re-run could append a duplicate — acceptable,
     *  and cheap on quota. */
    private static final int MAX_ITEM_PAGES = 2;

    private final YouTubeClientFactory clientFactory;

    public Result addToPlaylist(String videoId, String playlistTitle, String playlistDescription) {
        if (videoId == null || videoId.isBlank()) {
            throw new IllegalArgumentException("videoId is required");
        }
        if (playlistTitle == null || playlistTitle.isBlank()) {
            throw new IllegalArgumentException("playlistTitle is required");
        }
        String wanted = playlistTitle.trim();
        try {
            YouTube yt = clientFactory.get();

            String playlistId = findOwnPlaylistByTitle(yt, wanted);
            boolean created = false;
            if (playlistId == null) {
                playlistId = createPlaylist(yt, wanted, playlistDescription);
                created = true;
                log.info("created playlist '{}' -> {}", wanted, playlistId);
            }

            // Idempotency: a just-created playlist can't contain the video yet.
            if (!created && containsVideo(yt, playlistId, videoId)) {
                log.info("video {} already in playlist {} ('{}') — nothing to do",
                        videoId, playlistId, wanted);
                return new Result(playlistId, false, false);
            }

            insertItem(yt, playlistId, videoId);
            log.info("video {} added to playlist {} ('{}')", videoId, playlistId, wanted);
            return new Result(playlistId, true, created);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("playlist add failed: " + e.getMessage(), e);
        }
    }

    /** Exact title match (case-insensitive, trimmed) over the channel's own
     *  playlists. One page of 50 — more playlists than that and the series
     *  playlist should be looked up by id instead. */
    private String findOwnPlaylistByTitle(YouTube yt, String wanted) throws java.io.IOException {
        var resp = yt.playlists().list(List.of("snippet"))
                .setMine(true)
                .setMaxResults(50L)
                .execute();
        if (resp.getItems() == null) return null;
        for (Playlist p : resp.getItems()) {
            String title = p.getSnippet() != null ? p.getSnippet().getTitle() : null;
            if (title != null && title.trim().equalsIgnoreCase(wanted)) return p.getId();
        }
        return null;
    }

    private String createPlaylist(YouTube yt, String title, String description)
            throws java.io.IOException {
        PlaylistSnippet snippet = new PlaylistSnippet();
        snippet.setTitle(title);
        if (description != null && !description.isBlank()) snippet.setDescription(description);

        PlaylistStatus status = new PlaylistStatus();
        status.setPrivacyStatus("public");

        Playlist playlist = new Playlist();
        playlist.setSnippet(snippet);
        playlist.setStatus(status);

        Playlist created = yt.playlists().insert(List.of("snippet", "status"), playlist).execute();
        return created.getId();
    }

    /** Scans up to {@link #MAX_ITEM_PAGES} pages of playlist items for the
     *  videoId (the API has no reliable single-call "is video X in playlist Y"
     *  for this client style — paging is the documented fallback). */
    private boolean containsVideo(YouTube yt, String playlistId, String videoId)
            throws java.io.IOException {
        String pageToken = null;
        for (int page = 0; page < MAX_ITEM_PAGES; page++) {
            YouTube.PlaylistItems.List req = yt.playlistItems().list(List.of("snippet"))
                    .setPlaylistId(playlistId)
                    .setMaxResults(50L);
            if (pageToken != null) req.setPageToken(pageToken);
            var resp = req.execute();
            if (resp.getItems() != null) {
                for (PlaylistItem item : resp.getItems()) {
                    String vid = item.getSnippet() != null && item.getSnippet().getResourceId() != null
                            ? item.getSnippet().getResourceId().getVideoId() : null;
                    if (videoId.equals(vid)) return true;
                }
            }
            pageToken = resp.getNextPageToken();
            if (pageToken == null) break;
        }
        return false;
    }

    /** Position omitted on purpose → YouTube appends at the end (episode order). */
    private void insertItem(YouTube yt, String playlistId, String videoId)
            throws java.io.IOException {
        ResourceId resource = new ResourceId();
        resource.setKind("youtube#video");
        resource.setVideoId(videoId);

        PlaylistItemSnippet snippet = new PlaylistItemSnippet();
        snippet.setPlaylistId(playlistId);
        snippet.setResourceId(resource);

        PlaylistItem item = new PlaylistItem();
        item.setSnippet(snippet);
        yt.playlistItems().insert(List.of("snippet"), item).execute();
    }
}
