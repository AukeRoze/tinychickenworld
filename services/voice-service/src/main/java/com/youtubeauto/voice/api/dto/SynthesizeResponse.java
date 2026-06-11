package com.youtubeauto.voice.api.dto;

import java.util.List;
import java.util.UUID;

public record SynthesizeResponse(UUID jobId, List<SceneAudio> scenes) {

    /** One spoken line's position inside the scene's audio file. Powers
     *  per-line subtitle cues (assembly-audit #3): before this, the exact
     *  per-line timing was known here and then thrown away, leaving the SRT
     *  with one whole-scene cue on whole seconds. Empty in silent/sounds
     *  modes (no per-line files exist there). */
    public record LineTiming(String speaker, String text, long startMs, long durMs) {}

    public record SceneAudio(int seq, String audioPath, long bytes,
                             List<LineTiming> lineTimings) {
        /** Back-compat for branches without per-line timing. */
        public SceneAudio(int seq, String audioPath, long bytes) {
            this(seq, audioPath, bytes, List.of());
        }
    }
}
