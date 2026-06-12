package com.youtubeauto.orchestrator.api.dto;

import java.util.UUID;

/**
 * Compact job row for the static dashboard's jobs list (GET /api/v1/videos).
 * Also powers the Calendar (plannedPublishAt), Backlog (no planned date) and a
 * Quality glance (qaScore) views, so it carries a few planning/quality fields.
 */
public record JobSummary(
        UUID id,
        String topic,
        String status,
        String createdAt,
        String plannedPublishAt,
        String seriesId,
        Integer episodeNumber,
        Integer qaScore,
        String format          // landscape | vertical — drives the list's format filter
) {}
