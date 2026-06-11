package com.youtubeauto.orchestrator.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One recorded vision-QC failure on a scene image. We persist these (from both
 * the pre-render auto-QC and the Auto-Fix loop) so recurring patterns surface:
 * if "Mo green scarf" or "rendered text" keeps coming back, that's a signal to
 * harden the prompt / anchor permanently instead of re-fixing it every video.
 */
@Entity
@Table(name = "qc_finding")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class QcFinding {
    @Id
    private UUID id;

    @Column(name = "video_job_id", nullable = false)
    private UUID videoJobId;

    @Column(name = "seq")
    private Integer seq;

    /** Bucketed category for aggregation (accessory-swap, missing-accessory,
     *  rendered-text, color-drift, framing, duplicate, hands, other). */
    @Column(name = "category", nullable = false)
    private String category;

    /** Character the issue mentions, if any (pip/mo/bo); null otherwise. */
    @Column(name = "character_hint")
    private String characterHint;

    /** The raw issue text from the vision QC. */
    @Column(name = "issue", columnDefinition = "text")
    private String issue;

    /** "auto-qc" (pre-render) or "auto-fix" (the re-roll loop). */
    @Column(name = "source")
    private String source;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        if (createdAt == null) createdAt = OffsetDateTime.now();
    }
}
