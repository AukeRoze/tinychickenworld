package com.youtubeauto.videogen.bible;

import java.util.List;

/**
 * Mirror of bible/channel.yml -> videoGen section. Loaded once at startup.
 */
public record VideoGenConfig(
        String defaultMode,        // "ken_burns" | "veo"
        Veo veo
) {
    public record Veo(
            String defaultModel,            // Vertex model id, e.g. veo-3.1-fast-generate-001
            String heroModel,               // Vertex model id for hero/intro/outro
            String heroQuality,             // "high" etc — purely informational; Vertex resolution is separate
            String fallbackModel,
            int maxClipSeconds,
            boolean audio,
            double costCapEurPerVideo,
            List<Routing> routing
    ) {}

    public record Routing(
            String sceneType,               // intro|hero|outro|standard
            String model,                   // model id; nullable -> uses defaultModel
            Integer maxSeconds              // optional override
    ) {}

    public static VideoGenConfig defaults() {
        return new VideoGenConfig(
                "ken_burns",
                new Veo(
                        "veo-3.1-fast-generate-preview",
                        "veo-3.1-generate-preview",
                        "high",
                        "veo-3.0-fast-generate-001",
                        6,
                        false,
                        5.00,
                        List.of(
                                new Routing("intro",    "veo-3.1-generate-preview",      6),
                                new Routing("outro",    "veo-3.1-generate-preview",      6),
                                new Routing("hero",     "veo-3.1-generate-preview",      8),
                                new Routing("standard", "veo-3.1-fast-generate-preview", 6)
                        )
                )
        );
    }
}
