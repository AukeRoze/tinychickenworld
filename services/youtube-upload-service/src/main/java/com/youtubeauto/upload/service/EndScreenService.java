package com.youtubeauto.upload.service;

import com.youtubeauto.upload.youtube.YouTubeClientFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Adds an End Screen element pattern to a YouTube video — clickable
 * elements in the last 5-20 seconds: Subscribe button, latest video,
 * "best for viewer" suggestion.
 *
 * Note: as of 2025 the YouTube Data API does NOT expose End Screen
 * editing programmatically (it's a Studio-only feature). What we CAN
 * do is bake the visual prompt INTO the video (which we already do via
 * the end-card overlay in Concatenator) AND set defaults via the
 * channel's "default upload settings". This service codifies that
 * pattern + future-proofs the integration point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EndScreenService {

    private final YouTubeClientFactory factory;

    /** Returns a structured "this is what your end screen should be"
     *  recipe. Used by the dashboard to remind the user to manually set
     *  this once in YT Studio (it sticks for all future uploads). */
    public Map<String, Object> recipe() {
        return Map.of(
                "elements", java.util.List.of(
                        Map.of(
                                "type", "subscribe",
                                "position", "left",
                                "startSecondsBeforeEnd", 20
                        ),
                        Map.of(
                                "type", "video",
                                "subType", "best_for_viewer",
                                "position", "right",
                                "startSecondsBeforeEnd", 18
                        ),
                        Map.of(
                                "type", "playlist",
                                "subType", "latest",
                                "position", "center",
                                "startSecondsBeforeEnd", 15
                        )
                ),
                "instructions", java.util.List.of(
                        "Open YouTube Studio → Channel → Customization → Branding.",
                        "Upload your logo as watermark (suggest 'whole video' duration).",
                        "Settings → Upload defaults → set this end-screen as default.",
                        "Now every new upload gets these end-screen cards automatically."
                )
        );
    }
}
