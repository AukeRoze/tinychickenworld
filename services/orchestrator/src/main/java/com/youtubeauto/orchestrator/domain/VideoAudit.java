package com.youtubeauto.orchestrator.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_audit")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VideoAudit {
    @Id
    private UUID id;

    @Column(name = "video_job_id", nullable = false)
    private UUID videoJobId;

    @Column(nullable = false) private int score;

    @Column(name = "character_drift") private Integer characterDrift;
    @Column(name = "audio_balance")   private Integer audioBalance;
    @Column(name = "framing")         private Integer framing;
    @Column(name = "branding")        private Integer branding;

    /** JSON list of {severity, area, message}. */
    @Column(name = "findings", columnDefinition = "text")
    private String findings;

    @Column(name = "frames_inspected") private Integer framesInspected;

    @Column(name = "model")
    private String model;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
