package com.youtubeauto.video.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record AssemblyRequest(
        @NotNull UUID jobId,
        @NotNull UUID scriptId,
        @NotEmpty @Valid List<SceneInput> scenes,
        String backgroundMusicPath,
        String introPath,
        String outroPath,
        /** Output canvas. 0 / null → defaults from app config (1920x1080). */
        Integer width,
        Integer height,
        boolean burnSubtitles,
        /** Optional video title shown as an animated card on the first
         *  2 seconds of the master. Falls back to "An adventure" if blank. */
        String title
) {
    public AssemblyRequest(UUID jobId, UUID scriptId, List<SceneInput> scenes,
                            String bgm, String intro, String outro,
                            Integer w, Integer h, boolean burn) {
        this(jobId, scriptId, scenes, bgm, intro, outro, w, h, burn, null);
    }
    public record SceneInput(
            @Min(1) int seq,
            @NotBlank String imagePath,
            @NotBlank String audioPath,
            @Min(2) @Max(120) int durationSeconds,
            String narration,
            /** Optional pre-rendered video clip from video-generation-service.
             *  When present, the scene bypasses the Ken Burns filter graph and
             *  the clip is rescaled/padded to the canvas. Voice audio still
             *  comes from {@code audioPath} (Veo clips are rendered silent). */
            String clipPath,
            /** Episode-structure phase id (hook, setup, development, climax,
             *  resolution, closer). Drives the transition style into this
             *  scene. Optional — null falls back to a default crossfade. */
            String phase,
            /** Optional per-line voice timing (from the voice-service) — when
             *  present the SRT gets one millisecond-accurate cue per LINE
             *  instead of one whole-scene cue on whole seconds. */
            List<LineTiming> lineTimings
    ) {
        public record LineTiming(String speaker, String text, long startMs, long durMs) {}
    }
}
