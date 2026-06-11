package com.youtubeauto.thumbnail.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ThumbnailProperties(OpenAi openai, Storage storage, Thumbnail thumbnail, Image image) {
    public record OpenAi(String baseUrl, String apiKey, String model, String size, int timeoutSeconds) {}
    public record Storage(String workRoot) {}
    public record Thumbnail(int width, int height, String fontPath) {}
    /** image-service base URL — used to render anchor-based thumbnail bases so
     *  the thumbnail chicks are the EXACT same characters as the film. */
    public record Image(String baseUrl) {}
}
