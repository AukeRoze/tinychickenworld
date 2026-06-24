package com.youtubeauto.script.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * Canonical SOURCE-FIX patch: the orchestrator detected an
 * accessory-vs-action contradiction in a scene's authored action text, cleaned
 * it once, and writes the cleaned text back here so the persisted script_scenes
 * row (the single source of truth) stops re-emitting the same contradiction on
 * every reuse / re-roll / episode rebuild.
 *
 * <p>Only the fields that actually changed are sent; a null {@code visualDesc}
 * or {@code motionDesc} leaves that column untouched. Unknown {@code seq} values
 * are skipped silently (a stale orchestrator can never corrupt the store).
 */
public record PatchScenesRequest(
        @NotEmpty @Valid List<ScenePatch> scenes
) {
    public record ScenePatch(
            int seq,
            String visualDesc,
            String motionDesc
    ) {}
}
