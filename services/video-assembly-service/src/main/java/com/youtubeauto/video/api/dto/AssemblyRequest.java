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
        String title,
        /** Bumper-overgang intro → scène 1: ffmpeg xfade-naam of "cut".
         *  Null/leeg → de default intro-dissolve. */
        String introTransitionType,
        /** Duur van {@link #introTransitionType} in seconden (0,05–1,5). */
        Double introTransitionSeconds,
        /** Bumper-overgang laatste scène → outro: xfade-naam of "cut".
         *  Null/leeg → de default outro-dissolve. */
        String outroTransitionType,
        /** Duur van {@link #outroTransitionType} in seconden (0,05–1,5). */
        Double outroTransitionSeconds
) {
    public AssemblyRequest(UUID jobId, UUID scriptId, List<SceneInput> scenes,
                            String bgm, String intro, String outro,
                            Integer w, Integer h, boolean burn) {
        this(jobId, scriptId, scenes, bgm, intro, outro, w, h, burn, null,
                null, null, null, null);
    }
    public record SceneInput(
            @Min(1) int seq,
            @NotBlank String imagePath,
            /** DEPRECATED — was the ElevenLabs voice WAV. Voices now come from the
             *  Omni clip's own native audio, so this is optional and ignored; kept
             *  only for backward-compatible request shapes. */
            String audioPath,
            @Min(2) @Max(120) int durationSeconds,
            String narration,
            /** Pre-rendered Google Flow / Omni clip. The scene bypasses the Ken
             *  Burns graph, the clip is rescaled/padded to the canvas, and its OWN
             *  native audio (Omni voice + ambient) is kept — no separate voice
             *  track is mixed in any more. */
            String clipPath,
            /** Episode-structure phase id (hook, setup, development, climax,
             *  resolution, closer). Drives the transition style into this
             *  scene. Optional — null falls back to a default crossfade. */
            String phase,
            /** Scene location id from the script bible (e.g. "garden", "pond",
             *  "coop"). Optional. Drives the per-location ambient FX overlay
             *  ({@code bible/fx/location/{locationId}.mov|webm}) — the visual
             *  twin of the per-location ambient sound bed the voice-service
             *  already mixes from {@code bible/sfx/ambient/{locationId}.mp3}. */
            String locationId,
            /** Time-of-day mood id from the bible (goldenHour, midday, dusk,
             *  night). Optional. Drives {@code bible/fx/time/{timeOfDay}}
             *  overlays (night → fireflies / drifting stars). */
            String timeOfDay,
            /** Weather mood id from the bible (clear, lightRain, breezy, snow).
             *  Optional. Drives {@code bible/fx/weather/{weather}} overlays
             *  (lightRain → drops) — weather wins over time and location. */
            String weather,
            /** Optional per-line voice timing (from the voice-service) — when
             *  present the SRT gets one millisecond-accurate cue per LINE
             *  instead of one whole-scene cue on whole seconds. */
            List<LineTiming> lineTimings,
            /** Optional in-point (seconds) within the clip. The montage seeks here
             *  with ffmpeg {@code -ss} before reading {@code durationSeconds}
             *  seconds, so the scene shows the chosen [in, in+duration] window.
             *  Null/0 → start at 0 (the whole clip, current behaviour). */
            Double trimStartSeconds,
            /** Optional user-chosen transition INTO this scene (the boundary before
             *  it): an ffmpeg xfade name (e.g. "wipeleft") or "cut" for a hard cut.
             *  Null → the phase-default transition. */
            String transitionType,
            /** Length of {@link #transitionType} in seconds (0.05–1.5). Null → a
             *  per-type default. */
            Double transitionSeconds
    ) {
        public record LineTiming(String speaker, String text, long startMs, long durMs) {}
    }
}
