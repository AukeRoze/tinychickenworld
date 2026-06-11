package com.youtubeauto.thumbnail.api.dto;

import java.util.UUID;

public record GenerateThumbnailResponse(
        UUID jobId,
        String thumbnailPath,
        String layout,
        long bytes
) {}
