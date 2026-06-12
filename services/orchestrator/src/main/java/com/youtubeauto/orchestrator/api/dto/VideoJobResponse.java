package com.youtubeauto.orchestrator.api.dto;

import com.youtubeauto.orchestrator.domain.JobStatus;

import java.util.UUID;

public record VideoJobResponse(
        UUID id, String topic, JobStatus status, String step, String error,
        String videoPath, String youtubeVideoId, String youtubeUrl,
        // Cross-platform push status (V23): set after a successful push via the
        // distribution proxy. TikTok's API returns an id only, no URL.
        String facebookVideoId, String facebookUrl,
        String tiktokPublishId, String instagramMediaId,
        // Instagram permalink (V25, additive): best-effort lookup after the
        // Reels publish; null when the lookup failed or the job predates V25.
        String instagramUrl
) {}
