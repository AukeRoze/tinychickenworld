package com.youtubeauto.thumbnail.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Preview of the assembled thumbnail prompt(s) for a job — what {@code
 * ThumbnailGenerator} would feed its models, WITHOUT generating any image.
 * Returned by {@code POST /api/v1/thumbnails/preview-prompt} so the dashboard
 * can show the thumbnail prompt copy-pasteably, exactly like the scene prompts.
 */
public record ThumbnailPromptPreview(
        UUID jobId,
        /** True = group/cast thumbnail (≥2 chicks); false = single-character close-up. */
        boolean castMode,
        List<Variant> variants
) {
    /**
     * One thumbnail variant's prompt material.
     *
     * @param variant          1-based variant index
     * @param layout           the {@code LayoutTemplate} name (e.g. HOOK_RAINBOW_TOP)
     * @param mood             the variant mood directive
     * @param framing          the hero-shot framing (single vs group)
     * @param overlayHeadline  the rendered overlay text ("" for the no-text control)
     * @param anchorPrompt     the description sent to image-service for the live
     *                         Gemini reference-conditioned render (PRIMARY path)
     * @param openAiPrompt     the full self-contained OpenAI fallback prompt
     */
    public record Variant(
            int variant,
            String layout,
            String mood,
            String framing,
            String overlayHeadline,
            String anchorPrompt,
            String openAiPrompt
    ) {}
}
