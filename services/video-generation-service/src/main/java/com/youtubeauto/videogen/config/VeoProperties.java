package com.youtubeauto.videogen.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.Map;

@ConfigurationProperties(prefix = "veo")
public record VeoProperties(
        Polling polling,
        Parallelism parallelism,
        Map<String, Rate> rates,
        Quota quota
) {
    // GEEN extra (3-arg) constructor: Spring's @ConfigurationProperties bindt
    // records via de canonieke constructor, en een TWEEDE constructor maakt
    // dat ambigu → "No default constructor found" bij opstart. De enige
    // positionele caller (CostCalculatorTest) geeft nu het 4e arg expliciet mee.

    public record Polling(long initialDelayMs, long maxDelayMs, long maxWaitSeconds) {}
    public record Parallelism(int maxParallel) {}
    public record Rate(double eurPerSecond) {}

    /**
     * Vertex Veo RESOURCE_EXHAUSTED (gRPC code 8) backoff policy. On a quota /
     * rate-limit error the scene is retried on the SAME model with exponential
     * backoff + jitter (holding its parallelism slot to throttle throughput)
     * before degrading to the Ken-Burns fallback. Tuned via env so a quota
     * burst doesn't immediately dump 84% of scenes to €0 fallbacks.
     */
    public record Quota(int maxRetries, long baseBackoffMs) {
        public static Quota defaults() { return new Quota(3, 5000L); }
    }
}
