package com.youtubeauto.upload.api.dto;

import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record UploadRequest(
        @NotNull UUID jobId,
        @NotBlank String videoPath,
        String thumbnailPath,
        @NotBlank String title,
        @NotBlank String description,
        List<String> tags,
        String categoryId,
        String privacyStatus,
        /** If set and in the future, video uploads as private and is auto-published
         *  by YouTube at this moment (status.publishAt). RFC3339 / ISO-8601. */
        java.time.OffsetDateTime publishAt,
        /** Optional .srt path (shared /workdir). Uploaded as a YouTube caption
         *  track so viewers can toggle subtitles; nothing is burned into the video. */
        String captionsPath
) {}
