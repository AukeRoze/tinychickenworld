package com.youtubeauto.video.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app")
public record VideoProperties(
        Ffmpeg ffmpeg,
        Storage storage,
        Output output
) {
    public record Ffmpeg(String binary, String probeBinary,
                         int maxConcurrentJobs, int sceneParallelism,
                         int perInvocationTimeoutMinutes) {}
    public record Storage(String workRoot) {}
    public record Output(int width, int height, int fps,
                         int videoCrf, int audioBitrateKbps) {}
}
