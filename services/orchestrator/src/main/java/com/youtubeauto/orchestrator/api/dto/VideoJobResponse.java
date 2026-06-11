package com.youtubeauto.orchestrator.api.dto;

import com.youtubeauto.orchestrator.domain.JobStatus;

import java.util.UUID;

public record VideoJobResponse(
        UUID id, String topic, JobStatus status, String step, String error,
        String videoPath, String youtubeVideoId, String youtubeUrl
) {}
