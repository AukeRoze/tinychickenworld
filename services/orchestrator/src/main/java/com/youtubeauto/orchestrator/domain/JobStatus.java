package com.youtubeauto.orchestrator.domain;

/**
 * Job lifecycle. _REVIEW_PENDING states pause the pipeline until a human
 * calls POST /api/v1/videos/{id}/approve (or /reject). Which gates are
 * actually enabled comes from the bible's `review:` block.
 */
public enum JobStatus {
    PENDING,
    SCRIPT_GENERATING,
    SCRIPT_REVIEW_PENDING,
    ASSETS_GENERATING,
    IMAGES_REVIEW_PENDING,     // per-scene image review + regenerate
    ASSETS_REVIEW_PENDING,
    VEO_GENERATING,
    VEO_REVIEW_PENDING,
    ASSEMBLING,
    THUMBNAIL_REVIEW_PENDING,   // thumbnail ready — human picks/approves before publish settings
    UPLOAD_REVIEW_PENDING,
    UPLOADING,
    DISTRIBUTION_PENDING,       // on YouTube — human pushes other platforms, then finalises
    COMPLETED,
    FAILED;

    public boolean isAwaitingReview() {
        return this == SCRIPT_REVIEW_PENDING
                || this == ASSETS_REVIEW_PENDING
                || this == IMAGES_REVIEW_PENDING
                || this == VEO_REVIEW_PENDING
                || this == THUMBNAIL_REVIEW_PENDING
                || this == UPLOAD_REVIEW_PENDING
                || this == DISTRIBUTION_PENDING;
    }

    public boolean isTerminal() {
        return this == COMPLETED || this == FAILED;
    }
}
