package com.youtubeauto.videogen.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * Local ffmpeg frame extraction for generated clips. Two consumers:
 *
 * 1. Clip-QC (orchestrator) — after every successful generation we drop three
 *    sample frames (first / middle / last) next to {@code clip.mp4} on the
 *    shared workdir. The orchestrator's vision QC judges those frames for
 *    headcount + identity drift WITHOUT needing ffmpeg in its own container.
 *    Prompts only raise the odds of a good clip; these frames are how the
 *    pipeline finally SEES what Veo actually produced.
 *
 * 2. Frame-chaining — {@code extractLastFrame} hands the true final frame of
 *    clip N to the next scene as its start image, so consecutive shots cut
 *    seamlessly (pixel-level continuity instead of a textual "continue from"
 *    hint).
 *
 * Everything here is best-effort: extraction failure logs a warning and never
 * fails the scene (the clip itself is already validated by ffprobe).
 */
@Slf4j
@Component
public class FrameExtractor {

    public static final String QC_FIRST = "qc_first.png";
    public static final String QC_MID   = "qc_mid.png";
    public static final String QC_LAST  = "qc_last.png";
    public static final String LAST_FRAME = "last_frame.png";

    /** Extracts first / middle / last QC frames next to the clip. Best-effort. */
    public void extractQcFrames(Path clip, Path sceneDir) {
        try {
            double dur = probeDuration(clip);
            if (dur <= 0) dur = 4.0;
            // First frame slightly in (0.1s) to skip any encoder fade-in frame.
            grabAt(clip, Math.min(0.1, dur / 10), sceneDir.resolve(QC_FIRST));
            grabAt(clip, dur / 2.0, sceneDir.resolve(QC_MID));
            grabLast(clip, sceneDir.resolve(QC_LAST));
        } catch (Exception e) {
            log.warn("QC frame extraction failed for {} ({}) — clip ships unchecked",
                    clip, e.getMessage());
        }
    }

    /**
     * Extracts the true last frame of a clip (for chaining into the next
     * scene's start image). @return the written PNG, or null on failure.
     */
    public Path extractLastFrame(Path clip, Path out) {
        try {
            grabLast(clip, out);
            return Files.exists(out) ? out : null;
        } catch (Exception e) {
            log.warn("Last-frame extraction failed for {}: {}", clip, e.getMessage());
            return null;
        }
    }

    // ---- internals ---------------------------------------------------------

    private double probeDuration(Path clip) {
        try {
            Process p = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", clip.toString())
                    .redirectErrorStream(true).start();
            String outStr = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor(20, TimeUnit.SECONDS);
            return Double.parseDouble(outStr);
        } catch (Exception e) {
            return -1;
        }
    }

    private void grabAt(Path clip, double seconds, Path out) throws Exception {
        run("ffmpeg", "-y", "-v", "error",
                "-ss", String.format(java.util.Locale.ROOT, "%.3f", seconds),
                "-i", clip.toString(), "-frames:v", "1", "-q:v", "2", out.toString());
    }

    /** -sseof seeks from the END of the file — robust way to get the final frame. */
    private void grabLast(Path clip, Path out) throws Exception {
        run("ffmpeg", "-y", "-v", "error",
                "-sseof", "-0.20", "-i", clip.toString(),
                "-update", "1", "-frames:v", "1", "-q:v", "2", out.toString());
        if (!Files.exists(out)) {
            // Fallback: decode the whole clip, overwriting the same PNG each
            // frame — the file that remains IS the last frame. Slower, but
            // clips are max ~8s so this stays cheap.
            run("ffmpeg", "-y", "-v", "error", "-i", clip.toString(),
                    "-update", "1", "-q:v", "2", out.toString());
        }
    }

    private void run(String... cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        if (!p.waitFor(60, TimeUnit.SECONDS)) {
            p.destroyForcibly();
            throw new IllegalStateException("ffmpeg timeout: " + String.join(" ", cmd));
        }
    }
}
