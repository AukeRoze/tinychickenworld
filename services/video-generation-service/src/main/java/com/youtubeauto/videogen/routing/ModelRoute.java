package com.youtubeauto.videogen.routing;

/**
 * Resolved Vertex AI Veo model + render parameters for one scene.
 */
public record ModelRoute(
        String modelId,        // e.g. "veo-3.1-fast-generate-001"
        String resolution,     // "720p" | "1080p"
        int    durationSec
) {}
