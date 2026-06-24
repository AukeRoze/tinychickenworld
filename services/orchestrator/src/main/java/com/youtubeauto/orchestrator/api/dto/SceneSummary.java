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
        boolean silentBeat,
        /** True wanneer deze scène een door de clip-QC AFGEKEURDE Veo-clip
         *  bewaard heeft (clip.rejected.mp4). De UI toont dan een review-knop +
         *  override-knop. Stream via /dashboard/{id}/scene/{seq}/rejected-clip.mp4. */
        boolean hasRejectedClip,
        /** De reden waarom de clip-QC de clip afkeurde (gecapt). Leeg/null als er
         *  geen afgekeurde clip is. */
        String qcRejectReason,
        /** De volledige, gecompileerde Veo-prompt voor deze scène (camera,
         *  camera-move, setting, performance + identity/headcount/scale-locks),
         *  zoals VeoPromptCompiler 'm bouwt. Voor de "kopieer alle prompts"-knop
         *  op de jobpagina. Leeg/null als compilatie niet lukt. */
        String veoPrompt,
        /** De gecomponeerde IMAGE/still-prompt voor deze scène (zoals de actieve
         *  image-provider 'm aan z'n model voert), opgehaald via de image-service
         *  preview-endpoint. Voor de "kopieer image-prompts"-knop op de jobpagina.
         *  Leeg/null als de preview niet lukt. */
        String imagePrompt,
        /** Het scène-'goal' (korte doel-/titelzin uit het script). De UI maakt er
         *  een slug van voor het scène-label en de aanbevolen clip-bestandsnaam
         *  (scene-<seq>-<goal-slug>). Leeg als het script geen goal zette. */
        String goal,
        /** In-punt van de scène binnen de clip (seconden); null = vanaf 0. Voedt
         *  de linker handgreep van het inkort-schuifje. */
        Double trimStartSeconds,
        /** Uit-punt van de scène binnen de clip (seconden) = in-punt + (ruwe,
         *  on-gevloerde) lengte; null = tot het clip-einde. Voedt de rechter
         *  handgreep van het inkort-schuifje. */
        Double trimEndSeconds,
        /** Gekozen overgang NAAR deze scène (ffmpeg xfade-naam of "cut"); leeg =
         *  phase-default. Voedt het label van het +-icoon vóór deze scène. */
        String transitionType,
        /** Duur van {@link #transitionType} in seconden; null = default. */
        Double transitionSeconds
) {
    /** One spoken line in a scene. */
    public record Line(String speaker, String text) {}
}
