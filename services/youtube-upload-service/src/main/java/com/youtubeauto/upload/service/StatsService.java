package com.youtubeauto.upload.service;

import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.Video;
import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Reads basic video stats from the YouTube Data API. Doesn't need any
 * extra scopes beyond what we already grant for upload.
 *
 * For deeper retention curves, impressions and CTR you'd add the YouTube
 * Analytics API (yt-analytics-monetary.readonly scope) — left as a hook
 * for a future expansion. The basic stats here are enough to drive most
 * of the self-learning loop (views, likes, comments).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StatsService {

    private final YouTubeClientFactory factory;

    public Map<String, Object> stats(String videoId) {
        try {
            YouTube yt = factory.get();
            YouTube.Videos.List req = yt.videos().list(List.of("statistics", "snippet", "contentDetails"));
            req.setId(List.of(videoId));
            var resp = req.execute();
            if (resp.getItems() == null || resp.getItems().isEmpty()) {
                return Map.of("error", "video not found");
            }
            Video v = resp.getItems().get(0);
            var s = v.getStatistics();
            return Map.of(
                    "youtubeVideoId", videoId,
                    "title",          v.getSnippet() != null ? v.getSnippet().getTitle() : "",
                    "publishedAt",    v.getSnippet() != null ? String.valueOf(v.getSnippet().getPublishedAt()) : "",
                    "duration",       v.getContentDetails() != null ? v.getContentDetails().getDuration() : "",
                    "views",          s != null && s.getViewCount() != null ? s.getViewCount().longValue() : 0L,
                    "likes",          s != null && s.getLikeCount() != null ? s.getLikeCount().intValue() : 0,
                    "comments",       s != null && s.getCommentCount() != null ? s.getCommentCount().intValue() : 0,
                    "favorites",      s != null && s.getFavoriteCount() != null ? s.getFavoriteCount().intValue() : 0
            );
        } catch (Exception e) {
            log.warn("stats fetch failed for {}: {}", videoId, e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }
}
