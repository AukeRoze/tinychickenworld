package com.youtubeauto.image.api.dto;

import java.util.List;
import java.util.UUID;

/**
 * Per-scene COMPOSED image prompt — the exact text the active image provider
 * would feed its model, built by the same {@code PromptComposer} the generate
 * path uses, but WITHOUT generating anything. Powers the dashboard's
 * "copy image prompts" button so Auke can paste/tweak the still prompts the
 * same way he copies the Veo (video) prompts.
 */
public record PreviewPromptsResponse(UUID jobId, List<ScenePrompt> scenes) {
    public record ScenePrompt(int seq, String prompt) {}
}
