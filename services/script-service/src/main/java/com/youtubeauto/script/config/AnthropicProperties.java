package com.youtubeauto.script.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.anthropic")
public record AnthropicProperties(
        String baseUrl,
        String apiKey,
        String anthropicVersion,
        String model,
        Integer maxTokens,
        Double temperature,
        Integer timeoutSeconds
) {}
