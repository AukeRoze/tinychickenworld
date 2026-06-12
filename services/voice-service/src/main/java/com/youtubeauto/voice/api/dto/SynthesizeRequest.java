package com.youtubeauto.voice.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record SynthesizeRequest(
        @NotNull UUID jobId,
        @NotEmpty @Valid List<SceneAudio> scenes
) {
    /**
     * A scene's spoken content. Each line is sent to ElevenLabs with the
     * voice id mapped from {@code speaker}; the per-line MP3s are then
     * concatenated into a single scene MP3.
     */
    public record SceneAudio(
            @Min(1) int seq,
            /** May be EMPTY: a silent visual beat — the scene acts without
             *  dialogue and gets a silent voice track (ambient + music carry it). */
            @NotNull @Valid List<Line> lines,
            /** Location id from the script (eg "coop", "pond"). Used by
             *  the ambient mixer to pick the right background sound. */
            String locationId,
            /** Scripted scene duration — used to size the silent track for
             *  dialogue-less beats so the edit holds the intended pause. */
            Integer durationSeconds,
            /** Scene weather from the script (eg "lightRain", "snow"). When a
             *  matching {@code ambient/{weather}.mp3} bed exists it overrides
             *  the location bed, so the rain you SEE in the visual weather
             *  overlay also sounds like rain. Optional/additive: null or
             *  "clear" keeps the location-bed behaviour. */
            String weather
    ) {
        public SceneAudio(int seq, List<Line> lines) { this(seq, lines, null, null, null); }
    }

    /** speaker = character id; text = the dialogue OR emotion tag depending
     *  on mode; emotion = optional explicit emotion that overrides text
     *  inference in sounds mode. */
    public record Line(@NotBlank String speaker, @NotBlank String text,
                       String emotion) {
        public Line(String speaker, String text) { this(speaker, text, null); }
    }
}
