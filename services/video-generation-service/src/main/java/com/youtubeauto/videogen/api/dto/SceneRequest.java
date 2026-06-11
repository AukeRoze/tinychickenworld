package com.youtubeauto.videogen.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SceneRequest(
        @NotNull @Min(1) Integer seq,
        String sceneType,                  // intro|hero|outro|standard ; null -> standard
        @NotBlank String startImagePath,
        String endImagePath,               // optional end-pose still (directed start→end motion)
        @NotBlank String visualDesc,
        @NotNull @Min(1) Integer durationSeconds,
        String negativePrompt,
        /** Optional per-scene model override (e.g. "veo3_1" for premium 1080p on a
         *  single re-roll). Blank/null → normal bible routing by scene type. */
        String modelOverride,
        /** Frame-chaining group id (orchestrator-assigned). Scenes sharing a
         *  group are rendered SEQUENTIALLY and each next scene starts on the
         *  extracted LAST FRAME of the previous clip, so consecutive shots cut
         *  with pixel-level continuity. Null → independent (parallel) scene. */
        Integer chainGroup,
        /** Cast ids in frame (e.g. ["pip","mo"]) — used to attach the approved
         *  character reference stills to the generation call so identity is
         *  anchored in pixels, not just prompt text. Null/empty = no refs. */
        java.util.List<String> characters
) {
    /** Copy with a different start image (used for frame-chaining). */
    public SceneRequest withStartImage(String newStart) {
        return new SceneRequest(seq, sceneType, newStart, endImagePath, visualDesc,
                durationSeconds, negativePrompt, modelOverride, chainGroup, characters);
    }
}
