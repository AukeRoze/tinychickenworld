package com.youtubeauto.orchestrator.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One snapshot of YouTube performance for an uploaded video. The poller
 * inserts a fresh row every 6h so we can track changes over time
 * (views going up, retention curve drift).
 */
@Entity
@Table(name = "video_analytics")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VideoAnalytics {

    @Id
    private UUID id;

    @Column(name = "video_job_id", nullable = false)
    private UUID videoJobId;

    @Column(name = "youtube_video_id", nullable = false)
    private String youtubeVideoId;

    @Column(name = "fetched_at", nullable = false)
    private OffsetDateTime fetchedAt;

    // YouTube Data API (always available)
    private Long views;
    private Integer likes;
    private Integer comments;
    private Integer favorites;

    // YouTube Analytics API (needs additional scope — null when not granted)
    @Column(name = "watch_time_minutes")        private Long watchTimeMinutes;
    @Column(name = "average_view_duration_sec") private Integer averageViewDurationSec;
    @Column(name = "average_view_percentage")   private Float averageViewPercentage;
    private Long impressions;
    @Column(name = "click_through_rate")        private Float clickThroughRate;
    @Column(name = "subscriber_gain")           private Integer subscriberGain;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
        if (fetchedAt == null) fetchedAt = OffsetDateTime.now();
    }
}
