package com.youtubeauto.image.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record ImageProperties(OpenAi openai, Storage storage) {
    public record OpenAi(String baseUrl, String apiKey, String model, String size, int timeoutSeconds) {}
    public record Storage(String workRoot) {}
}
