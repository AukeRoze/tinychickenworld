package com.youtubeauto.script.api.dto;

import com.youtubeauto.script.domain.JobStatus;

import java.util.UUID;

public record ScriptJobResponse(UUID jobId, JobStatus status) {}
