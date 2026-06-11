package com.youtubeauto.videogen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "veo")
public record VeoProperties(
        Polling polling,
        Parallelism parallelism,
        Map<String, Rate> rates
) {
    public record Polling(long initialDelayMs, long maxDelayMs, long maxWaitSeconds) {}
    public record Parallelism(int maxParallel) {}
    public record Rate(double eurPerSecond) {}
}
