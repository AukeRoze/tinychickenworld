package com.youtubeauto.image.provider;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;

public interface ImageProvider {
    String name();
    /**
     * Render the scene.
     * @param scene  visual scene data
     * @param format "landscape" or "vertical"
     * @param seed   per-video shared seed. Provider passes it to the underlying
     *               model so every scene in the same video lands in the same
     *               Flux "style neighbourhood" — keeps brush stroke density,
     *               palette and mood consistent across scenes.
     */
    byte[] generatePng(SceneVisual scene, String format, long seed);

    /**
     * Render a THUMBNAIL base: same character identity, but a CTR close-up
     * composition (big expressive face) instead of a full scene. Providers that
     * support reference anchors (gemini) override this so the thumbnail chicks
     * are the EXACT same characters as the film. The default falls back to the
     * normal scene render so non-anchor providers still produce something usable.
     */
    default byte[] generateThumbnailPng(SceneVisual scene, String format, long seed) {
        return generatePng(scene, format, seed);
    }

    /**
     * ConsistencyState variant: {@code episodeAnchors} are paths to stills
     * ALREADY RENDERED for this same episode (the first still + the previous
     * one). Anchor-capable providers (gemini) pass them as extra reference
     * images so every scene stays pixel-consistent with how THIS episode has
     * drawn the cast — the structural fix for cross-scene micro-drift.
     * Default ignores them, so non-anchor providers are unaffected.
     */
    default byte[] generatePng(SceneVisual scene, String format, long seed,
                               java.util.List<java.nio.file.Path> episodeAnchors) {
        return generatePng(scene, format, seed);
    }

    /** Thumbnail twin of the ConsistencyState variant. Default ignores anchors. */
    default byte[] generateThumbnailPng(SceneVisual scene, String format, long seed,
                                        java.util.List<java.nio.file.Path> episodeAnchors) {
        return generateThumbnailPng(scene, format, seed);
    }
}
