package com.youtubeauto.script.api.dto;

import jakarta.validation.constraints.*;

public record GenerateScriptRequest(
        @NotBlank @Size(max = 300) String topic,
        @NotBlank @Pattern(regexp = "kids_3_6|kids_7_10|tween_11_13") String audience,
        @Min(30) @Max(900) int targetSeconds,
        @Min(3) @Max(20) Integer numScenes,
        String styleHint,
        /** Free-form creative brief — overrides default story-shape decisions. */
        @Size(max = 4000) String brief,
        /** What the viewer should walk away knowing. */
        @Size(max = 500) String lesson,
        /** Emotional tone, 2-4 words. */
        @Size(max = 200) String mood,
        /** Narrative angle / who-drives-the-story. */
        @Size(max = 500) String angle,
        /** Concrete hook seed for the first 0-8 seconds. If blank, Claude
         *  invents one from the brief. Used to enforce the HOOK phase of
         *  the episode structure. */
        @Size(max = 500) String hook,
        /** Optional performance feedback from analytics: "your videos with
         *  warm cozy mood get 30%% more watch time" — injected into Opus's
         *  system prompt so it favours proven patterns. */
        @Size(max = 2000) String performanceHint,
        /** Optional story-arc id (from bible storyArcs) chosen by the
         *  orchestrator's performance-weighted ArcSelector. Blank/unknown =
         *  legacy random pick. */
        @Size(max = 60) String preferredArc
) {}
