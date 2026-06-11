package com.youtubeauto.script.domain;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

@Entity
@Table(name = "script_scenes")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ScriptScene {
    @Id
    private UUID id;

    @Column(name = "script_id", nullable = false)
    private UUID scriptId;

    @Column(nullable = false)
    private int seq;

    /** Concatenated text of all spoken lines — kept for subtitles + word count. */
    @Column(columnDefinition = "text")
    private String narration;

    @Column(name = "visual_desc", nullable = false, columnDefinition = "text")
    private String visualDesc;

    @Column(name = "duration_seconds", nullable = false)
    private int durationSeconds;

    /** JSON array of {@code {speaker, text}} objects. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "lines", columnDefinition = "jsonb")
    private String linesJson;

    /** JSON array of character ids present in the scene. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "characters", columnDefinition = "jsonb")
    private String charactersJson;

    @Column(name = "location_id")
    private String locationId;

    /** Episode-structure phase id (hook, setup, development, climax,
     *  resolution, closer). Nullable for older rows. */
    @Column(name = "phase")
    private String phase;

    /** Time-of-day mood id from the bible (goldenHour, midday, dusk, night). */
    @Column(name = "time_of_day")
    private String timeOfDay;

    /** Optional weather mood id from the bible (clear, lightRain, breezy, snow). */
    @Column(name = "weather")
    private String weather;

    /** Shot-DNA: the shot's purpose in one phrase. */
    @Column(name = "goal", columnDefinition = "text")
    private String goal;

    /** Shot-DNA: primary emotion + intensity (e.g. "wonder (5/5)"). */
    @Column(name = "emotion")
    private String emotion;

    /** Shot-DNA: pace — slow | natural | quick. */
    @Column(name = "motion_speed")
    private String motionSpeed;

    /** Shot-DNA: character's end-of-shot pose, for last-frame generation. */
    @Column(name = "end_pose", columnDefinition = "text")
    private String endPose;

    /** Motion brief for hero (hook/climax) scenes — start→end movement for Veo. */
    @Column(name = "motion_desc", columnDefinition = "text")
    private String motionDesc;

    @PrePersist
    void onCreate() { if (id == null) id = UUID.randomUUID(); }
}
