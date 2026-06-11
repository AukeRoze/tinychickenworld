package com.youtubeauto.image.api.dto;

import java.util.List;
import java.util.UUID;

public record GenerateImageResponse(UUID jobId, List<SceneImage> scenes) {
    public record SceneImage(int seq, String imagePath, long bytes) {}
}
