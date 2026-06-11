package com.youtubeauto.orchestrator.api.dto;

import java.util.List;

/**
 * Per-scene row for the static job-detail page (GET /api/v1/videos/{id}/scenes).
 * Drives both the image grid (image streamed by /review/images/{id}/file/{seq}.png)
 * and the readable Script view (dialogue lines + visual description).
 */
public record SceneSummary(
        int seq,
        int durationSeconds,
        String phase,
        String narration,
        boolean hasClip,
        boolean locked,
        String visualDesc,
        List<Line> lines,
        /** True when this (hero) scene also has a directed end-still on disk.
         *  Stream it from /review/images/{id}/file/{endStillSeq}.png. */
        boolean hasEndStill,
        /** Seq to fetch the end-still image (= 900 + seq); -1 when none. */
        int endStillSeq,
        /** Last-modified millis of the scene still — used as a ?v= cache token so
         *  a regenerated image refreshes in the UI (and only then). 0 when absent. */
        long imageVersion,
        /** True = the scripted SILENT visual beat (no lines, no narration). The
         *  UI highlights it gold. Computed BEFORE the narration display-fallback
         *  (which fills narration with visualDesc and used to hide this flag —
         *  the reason the golden frame never showed). */
        boolean silentBeat
) {
    /** One spoken line in a scene. */
    public record Line(String speaker, String text) {}
}
