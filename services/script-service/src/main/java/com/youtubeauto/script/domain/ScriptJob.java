package com.youtubeauto.script.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "script_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ScriptJob {
    @Id
    private UUID id;

    @Column(nullable = false)
    private String topic;

    @Column(nullable = false)
    private String audience;

    @Column(name = "target_seconds", nullable = false)
    private int targetSeconds;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private JobStatus status;

    private String error;

    /** Count of regenerations triggered by the duplicate detector for this job. */
    @Column(name = "duplicate_rejections", nullable = false)
    private int duplicateRejections;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (status == null) status = JobStatus.PENDING;
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
