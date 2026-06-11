package com.youtubeauto.videogen.api.dto;

import java.util.List;
import java.util.UUID;

public record GenerateClipsResponse(
        UUID jobId,
        List<ClipResult> clips,
        double totalCostEur,
        boolean costCapReached
) {}
