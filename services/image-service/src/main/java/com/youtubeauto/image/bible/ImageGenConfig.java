package com.youtubeauto.image.bible;

/**
 * Image-generation backend configuration parsed from {@code imageGen} in the
 * channel bible. Keeps provider-specific settings out of Spring properties so
 * the bible remains the single source of truth.
 */
public record ImageGenConfig(
        String provider,        // openai | replicate
        Replicate replicate
) {
    public record Replicate(
            String model,           // owner/name:version
            String castLoraUrl,
            double castLoraScale,
            int width, int height,
            int numInferenceSteps,
            double guidanceScale
    ) {}
}
