package com.youtubeauto.video.api.dto;

import java.util.UUID;

public record AssemblyResult(
        UUID jobId,
        String outputPath,
        long fileSizeBytes,
        double durationSeconds,
        String videoCodec,
        String audioCodec,
        int width,
        int height,
        /** Path to the generated .srt (intro-shifted) for upload as a YouTube
         *  caption track. Always produced, even when subtitles aren't burned in. */
        String captionsPath,
        /** Path to the auto-derived vertical Short (out/short.mp4), or null when
         *  no Short was built (vertical master, short video, or builder error). */
        String shortPath
) {}
