package com.youtubeauto.orchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record OrchestratorProperties(
        Services services, Poll poll, Anthropic anthropic, Defaults defaults, Brand brand,
        Bible bible
) {
    public record Services(String script, String assembly, String voice, String image,
                           String thumbnail, String upload, String videoGen) {}
    public record Poll(int intervalMs, int maxAttempts) {}
    public record Anthropic(String baseUrl, String apiKey, String anthropicVersion,
                            String model, Integer maxTokens, Double temperature) {}
    public record Defaults(String audience, int targetSeconds, boolean burnSubtitles,
                           String motionMode) {}
    public record Brand(String introPath, String outroPath) {}
    public record Bible(String path) {}
}
