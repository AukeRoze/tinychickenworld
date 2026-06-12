package com.youtubeauto.thumbnail.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

public record GenerateThumbnailRequest(
        @NotNull UUID jobId,
        @NotBlank String topic,
        @NotBlank String title,
        String hook,
        /** Optional real cast scene stills to use as the thumbnail base so the
         *  thumbnail characters EXACTLY match the film (the scenes are Gemini
         *  reference-conditioned). Empty/absent → fall back to OpenAI generation. */
        List<String> baseImagePaths,
        /** Optional historically best-performing layout (from the orchestrator's
         *  analytics loop). When set and valid, variant 3 uses this layout
         *  instead of the default rotation slot. Null = pure face-driven rotation. */
        String preferredLayout,
        /** Optional free-text direction from the human reviewer (e.g. "exactly
         *  three chicks, no extra chickens in the background"). Injected into
         *  every variant's generation prompt as a MANDATORY instruction; used by
         *  the dashboard's regenerate-with-prompt button. Null/blank = ignored. */
        String customHint,
        /** Optional GROUND TRUTH from the orchestrator: bible character ids that
         *  substantially appear in this episode's scenes (host + sidekicks with
         *  a real role). ≥2 ids → cast/group thumbnail, ook als de titel/topic
         *  niemand bij naam noemt; the ids also drive WHICH characters are
         *  rendered (so an absent cast member never sneaks into the thumbnail).
         *  Null/empty → fall back to the name/group-cue text heuristics. */
        List<String> castPresent
) {}
