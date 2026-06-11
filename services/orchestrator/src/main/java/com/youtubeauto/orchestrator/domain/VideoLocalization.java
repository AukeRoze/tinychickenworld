package com.youtubeauto.orchestrator.domain;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "video_localization")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class VideoLocalization {

    @Id
    private UUID id;

    @Column(name = "video_job_id", nullable = false)
    private UUID videoJobId;

    @Column(name = "language_code", nullable = false)
    private String languageCode;

    @Column(name = "translated_script", columnDefinition = "text")
    private String translatedScript;

    /** Localised YouTube metadata per language (title/description/tags). */
    @Column(name = "localized_title",       columnDefinition = "text") private String localizedTitle;
    @Column(name = "localized_description", columnDefinition = "text") private String localizedDescription;
    @Column(name = "localized_tags",        columnDefinition = "text") private String localizedTags; // comma-separated

    @Column(name = "audio_dir")     private String audioDir;
    @Column(name = "subtitle_path") private String subtitlePath;

    @Column(name = "youtube_video_id") private String youtubeVideoId;
    @Column(name = "youtube_url")      private String youtubeUrl;

    @Column(nullable = false)
    private String status = "PENDING";

    @Column(columnDefinition = "text")
    private String error;

    @Column(name = "created_at", nullable = false) private OffsetDateTime createdAt;
    @Column(name = "updated_at", nullable = false) private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        if (id == null) id = UUID.randomUUID();
        var now = OffsetDateTime.now();
        if (createdAt == null) createdAt = now;
        updatedAt = now;
    }
    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
