package com.youtubeauto.videogen.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record GenerateClipsRequest(
        @NotNull UUID jobId,
        String format,                     // "landscape" | "vertical" ; null -> landscape
        @NotEmpty @Valid List<SceneRequest> scenes
) {}
