package com.youtubeauto.script.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "scripts")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Script {
    @Id
    private UUID id;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String hook;

    @Column(nullable = false)
    private String cta;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_json", nullable = false, columnDefinition = "jsonb")
    private String rawJson;

    @Column(name = "word_count", nullable = false)
    private int wordCount;

    @Column(name = "est_seconds", nullable = false)
    private int estSeconds;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_tokens")
    private Integer promptTokens;

    @Column(name = "completion_tokens")
    private Integer completionTokens;

    /** SHA-256 hex of the normalised narration text. Stored as fixed-width
     *  CHAR(64) in Postgres (bpchar). Hibernate would default String to
     *  Types#VARCHAR and reject the schema; @JdbcTypeCode forces Types#CHAR. */
    @Column(name = "content_hash", length = 64)
    @org.hibernate.annotations.JdbcTypeCode(java.sql.Types.CHAR)
    private String contentHash;

    /** 64-bit SimHash signature for near-duplicate scans. */
    @Column(name = "simhash")
    private Long simhash;

    /** Serialised VariationProfile for audit / pattern-detection forensics. */
    @Column(name = "variation_profile")
    private String variationProfile;

    /** Story-arc id the script was written around (bible storyArcs) —
     *  feeds the performance-weighted arc selection loop. */
    @Column(name = "story_arc")
    private String storyArc;

    /** How many regeneration attempts it took to clear dedupe. */
    @Column(name = "regen_attempts", nullable = false)
    private int regenAttempts;

    /** Deterministic beat-sheet/structure score 0-100 (100 = no violations).
     *  Surfaced downstream so weak timing/arc is visible before render. */
    @Column(name = "structure_score")
    private Integer structureScore;

    /** Qualitative story-critic overall score 0-100 (null = critic disabled
     *  or unavailable). Complements the structural score. */
    @Column(name = "critic_score")
    private Integer criticScore;

    /** Per-axis story-critic scores 0-10 (null = critic disabled). Surfaced so
     *  the orchestrator QA Board can map Humor / Emotional Impact / child-fit
     *  directly instead of re-judging the script. */
    @Column(name = "critic_comedy")     private Integer criticComedy;
    @Column(name = "critic_emotional")  private Integer criticEmotional;
    @Column(name = "critic_psychology") private Integer criticPsychology;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @OneToMany(mappedBy = "scriptId", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    @OrderBy("seq ASC")
    @lombok.Builder.Default
    private List<ScriptScene> scenes = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        createdAt = OffsetDateTime.now();
    }
}
