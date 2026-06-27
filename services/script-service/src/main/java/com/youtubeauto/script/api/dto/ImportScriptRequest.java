package com.youtubeauto.script.api.dto;

import com.youtubeauto.script.anthropic.GeneratedScript;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Import a hand-authored (or externally generated) script as a COMPLETED job,
 * WITHOUT calling the paid Anthropic generator. The {@code script} must match
 * the {@link GeneratedScript} / emit_script shape. {@code audience} and
 * {@code targetSeconds} are metadata only (sensible defaults applied when null).
 *
 * This is the AI-free counterpart to {@code POST /api/v1/scripts}: it lets the
 * orchestrator feed a ready script into the pipeline when the Anthropic
 * kill-switch is on.
 */
public record ImportScriptRequest(
        @NotBlank String topic,
        String audience,
        Integer targetSeconds,
        @NotNull GeneratedScript script
) {}
