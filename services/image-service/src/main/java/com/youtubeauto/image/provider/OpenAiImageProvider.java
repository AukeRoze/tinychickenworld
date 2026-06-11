package com.youtubeauto.image.provider;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.openai.OpenAiImageClient;
import com.youtubeauto.image.service.PromptComposer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * Default image provider — OpenAI gpt-image-1 with format-appropriate size.
 *
 * Sizes supported by gpt-image-1 (older DALL-E 3 sizes like 1792x1024
 * are no longer accepted — API returns 400):
 *   - 1024x1024 — square
 *   - 1536x1024 — landscape
 *   - 1024x1536 — portrait / vertical
 *   - auto     — let OpenAI decide
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiImageProvider implements ImageProvider {

    private static final String LANDSCAPE_SIZE = "1536x1024";
    private static final String VERTICAL_SIZE  = "1024x1536";

    private final OpenAiImageClient client;
    private final PromptComposer prompts;

    @Override
    public String name() { return "openai"; }

    @Override
    public byte[] generatePng(SceneVisual scene, String format, long seed) {
        // OpenAI gpt-image-1 doesn't accept a seed param — ignored here.
        // Style consistency relies entirely on the visualStyle description.
        String prompt = prompts.composeDescribe(scene);
        String size = "vertical".equalsIgnoreCase(format) ? VERTICAL_SIZE : LANDSCAPE_SIZE;
        log.debug("openai scene {} format={} prompt: {}", scene.seq(), format, prompt);
        return client.generatePng(prompt, size);
    }
}
