package com.youtubeauto.orchestrator.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_jobs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VideoJob {
    @Id
    private UUID id;

    // ---- request inputs (persisted so the pipeline is resumable after a review gate) ----
    @Column(nullable = false) private String topic;
    @Column(name = "brief",  columnDefinition = "text") private String brief;
    @Column(name = "lesson", columnDefinition = "text") private String lesson;
    @Column(name = "mood")   private String mood;
    @Column(name = "angle",  columnDefinition = "text") private String angle;
    @Column(name = "hook",   columnDefinition = "text") private String hook;
    @Column(nullable = false) private String audience;
    @Column(name = "target_seconds", nullable = false) private int targetSeconds;
    @Column(name = "format") private String format;
    @Column(name = "motion_mode") private String motionMode;
    /** Job-level Veo model override (veo3_1_lite / veo3_1_fast / veo3_1);
     *  blank = bible routing per sceneType decides. */
    @Column(name = "veo_model") private String veoModel;
    @Column(name = "burn_subtitles") private Boolean burnSubtitles;
    @Column(name = "privacy_status") private String privacyStatus;
    @Column(name = "background_music_path") private String backgroundMusicPath;
    /** Optional per-episode recurring visual motif (injected into image prompts).
     *  Eggs are no longer a channel-wide default; set this to opt a motif in. */
    @Column(name = "recurring_motif", columnDefinition = "text") private String recurringMotif;

    /** When set, runAssetsStage copies scene-images from this job rather
     *  than calling image-service. */
    @Column(name = "reuse_images_from_job") private java.util.UUID reuseImagesFromJob;

    /** Comma-separated list of scene seq numbers the reviewer has locked. */
    @Column(name = "locked_scene_seqs", columnDefinition = "text") private String lockedSceneSeqs;

    // ---- lifecycle ----
    @Enumerated(EnumType.STRING)
    @Column(nullable = false) private JobStatus status;

    private String step;
    private String error;

    // ---- intermediate state across stages ----
    @Column(name = "script_job_id") private UUID scriptJobId;
    @Column(name = "script_id") private UUID scriptId;

    /** Pre-render quality scores copied from the script stage so Polish can
     *  surface weak arc/timing before upload (0-100, null until script done). */
    @Column(name = "structure_score") private Integer structureScore;
    @Column(name = "critic_score")    private Integer criticScore;

    /** QA Board (role 12) consolidated verdict: the 0-100 total and the full
     *  8-axis breakdown as JSON. Set after the master is assembled + audited;
     *  null until then. The publish gate reads qaBoardScore. */
    @Column(name = "qa_board_score")             private Integer qaBoardScore;
    @Column(name = "qa_board_json", columnDefinition = "text") private String qaBoardJson;

    /** AI-Critic Auto-Fix loop state. target = the AI-Critic score to reach
     *  (e.g. 90); null = auto-fix not active. iterations/rerolls left are the
     *  hard caps that stop the loop. */
    @Column(name = "autofix_target")          private Integer autofixTarget;
    @Column(name = "autofix_iterations_left") private Integer autofixIterationsLeft;
    @Column(name = "autofix_rerolls_left")    private Integer autofixRerollsLeft;

    /** JSON: list of {seq, durationSeconds, narration, visualDesc, imagePath, audioPath, clipPath?}.
     *  Re-loaded at each stage so a review gate can pause + resume without
     *  re-running upstream work. */
    @Column(name = "assembly_scenes")
    private String assemblyScenesJson;

    // ---- outputs ----
    @Column(name = "video_path") private String videoPath;
    @Column(name = "thumbnail_path") private String thumbnailPath;
    /** Auto-derived vertical Short (out/short.mp4) — the assembly stage builds
     *  one from the hook + most energetic moment of every landscape master. */
    @Column(name = "short_path", columnDefinition = "text") private String shortPath;
    /** Production metrics JSON: {veoCostEur, veoOk, veoTotal, masterSeconds,
     *  scriptedSeconds, stretchFactor} — filled per stage, shown on the job page. */
    @Column(name = "metrics_json", columnDefinition = "text") private String metricsJson;
    /** Path to the .srt caption file uploaded to YouTube as a toggleable
     *  caption track (image stays clean — captions are not burned in). */
    @Column(name = "captions_path", columnDefinition = "text") private String captionsPath;
    @Column(name = "metadata_title") private String metadataTitle;
    @Column(name = "metadata_description") private String metadataDescription;
    @Column(name = "metadata_tags") private String metadataTags;   // comma-separated
    @Column(name = "youtube_video_id") private String youtubeVideoId;
    @Column(name = "youtube_url") private String youtubeUrl;

    // ---- Self-learning loop (V10) ----
    /** Story-arc id this episode's script followed — analytics scores arcs. */
    @Column(name = "story_arc") private String storyArc;
    /** Winning thumbnail layout (NO_TEXT / BADGE_BOTTOM_LEFT / …). */
    @Column(name = "thumbnail_layout") private String thumbnailLayout;
    /** Per-scene retention mapping JSON, filled by the AnalyticsPoller:
     *  [{seq,phase,startSec,endSec,avgWatchRatio,drop}] */
    @Column(name = "retention_scenes_json", columnDefinition = "text")
    private String retentionScenesJson;

    // ---- Cross-platform distribution IDs ----
    @Column(name = "facebook_video_id") private String facebookVideoId;
    @Column(name = "facebook_url") private String facebookUrl;

    // ---- planning ----
    /** When this episode should go live on YouTube. If set, upload uses
     *  YouTube's scheduled-publish feature (private until publishAt). */
    @Column(name = "planned_publish_at") private OffsetDateTime plannedPublishAt;
    /** Optional series identifier — groups multiple episodes together. */
    @Column(name = "series_id") private String seriesId;
    /** Episode number within the series. */
    @Column(name = "episode_number") private Integer episodeNumber;

    // ---- Song Mode (motionMode=song) ----
    @Column(name = "song_title", columnDefinition = "text") private String songTitle;
    @Column(name = "song_style", columnDefinition = "text") private String songStyle;
    @Column(name = "song_lyrics", columnDefinition = "text") private String songLyrics;
    @Column(name = "song_path", columnDefinition = "text") private String songPath;
    @Column(name = "karaoke_path", columnDefinition = "text") private String karaokePath;

    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

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
