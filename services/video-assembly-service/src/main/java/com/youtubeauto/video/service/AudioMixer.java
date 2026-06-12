package com.youtubeauto.video.service;

import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Step 4: pro-grade music ducking. Background music auto-dips by ~8dB when
 * scene audio (voice / SFX / ambient) is active, restoring smoothly when
 * the foreground goes quiet. Broadcast-standard sidechain compression with
 * conservative ratio so the duck is musical, not pumpy.
 */
@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class AudioMixer {

    private final FfmpegRunner runner;

    /**
     * Depth (dB, negative) of the deliberate music dip on the held-silence
     * beat — applied ON TOP of the music bed's base level and the sidechain
     * duck, so the hero-silence reads as truly quiet. 0 (or any value ≥ 0)
     * disables the dip entirely. Default -12 dB ≈ music sinks to ~25%.
     */
    @org.springframework.beans.factory.annotation.Value("${app.assembly.climax-dip-db:-12}")
    private double climaxDipDb;

    /** Edge fade of the dip window (seconds): the music eases down into the
     *  silence and eases back out — no hard volume step, so it can't click. */
    private static final double DIP_FADE_SEC = 0.8;

    /** Ramp length (seconds) between two score-plan phase levels: the music
     *  glides linearly from one level to the next, centred on the phase
     *  boundary — never a hard volume step between story beats. */
    private static final double PLAN_RAMP_SEC = 1.5;

    /** Safety cap on score-plan segments — beyond this the volume expression
     *  stops being "a handful of ramps" and the plan is dropped (flat base)
     *  rather than risking an unwieldy ffmpeg expression. A normal beat-sheet
     *  (hook..closer) yields ≤ 6. */
    private static final int PLAN_MAX_SEGMENTS = 12;

    /**
     * One phase of the score plan (Pixar audit E2): between {@code startSec}
     * and {@code endSec} (final-video time) the music bed targets
     * {@code gainDb} relative to its base level (0 = bed as-is, negative =
     * pulled back for dialogue air, positive = leaning in). Segments are
     * expected sorted and non-overlapping; the mixer ramps linearly between
     * consecutive levels over {@link #PLAN_RAMP_SEC}.
     */
    public record ScoreSegment(double startSec, double endSec, double gainDb) {}

    /**
     * Adds a 1-1.5s branded audio sting at the very start of the video, mixed
     * UNDER the existing track so it doesn't fight the title card or first
     * scene's voice/SFX. Soft volume (-3dB by default).
     * Skipped silently if the sting file doesn't exist.
     */
    public Path mixIntroSting(Path video, String stingPath, double volumeDb,
                                Path output, Path workdir) {
        if (stingPath == null || stingPath.isBlank()
                || !java.nio.file.Files.exists(java.nio.file.Paths.get(stingPath))) {
            return video;
        }
        String filter = String.format(java.util.Locale.ROOT,
                "[1:a]volume=%.1fdB[stingV];"
                + "[0:a][stingV]amix=inputs=2:duration=first:dropout_transition=0[mixed]",
                volumeDb);
        runner.runFfmpeg(java.util.List.of(
                "-y",
                "-i", video.toString(),
                "-i", stingPath,
                "-filter_complex", filter,
                "-map", "0:v", "-map", "[mixed]",
                "-c:v", "copy",
                // PCM intermediate (audit #1): keep the mix lossless until the
                // FinalEncoder's single AAC pass. Output container is mkv.
                "-c:a", "pcm_s16le",
                "-shortest",
                output.toString()
        ), workdir);
        return output;
    }

    public Path mixBackgroundMusic(Path video, String bgmPath, Path output, Path workdir) {
        return mixBackgroundMusic(video, bgmPath, output, workdir, 0.0, 0.0);
    }

    /**
     * @param swellCenterSec  centre (seconds into the FINAL video) of the climax
     *        beat; the music gently swells there so the score follows the story
     *        arc. 0 → no swell (flat mix, original behaviour).
     * @param swellSpreadSec  half-width of the swell (Gaussian sigma). Broad so a
     *        few seconds of timing drift is inaudible.
     */
    public Path mixBackgroundMusic(Path video, String bgmPath, Path output, Path workdir,
                                   double swellCenterSec, double swellSpreadSec) {
        return mixBackgroundMusic(video, bgmPath, output, workdir,
                swellCenterSec, swellSpreadSec, 0, 0);
    }

    /**
     * @param dipStartSec/dipEndSec optional near-silence WINDOW (seconds in the
     *        final video) for the scripted SILENT visual beat / climax peak
     *        (board #18, backlog P2): the music sinks by
     *        {@code app.assembly.climax-dip-db} (default -12 dB → ~25%) across
     *        the whole window, easing in/out over {@link #DIP_FADE_SEC} — a
     *        held breath deserves a held score. {0,0} (or any non-positive /
     *        inverted window) = no dip. The dip is cosmetic: if the envelope
     *        ever makes the mix fail, the mix is retried FLAT rather than
     *        failing the render.
     */
    public Path mixBackgroundMusic(Path video, String bgmPath, Path output, Path workdir,
                                   double swellCenterSec, double swellSpreadSec,
                                   double dipStartSec, double dipEndSec) {
        return mixBackgroundMusic(video, bgmPath, output, workdir,
                swellCenterSec, swellSpreadSec, dipStartSec, dipEndSec, List.of());
    }

    /**
     * @param scorePlan optional beat-sheet volume arc (Pixar audit E2): a short
     *        list of phase segments (start, end, gain dB vs the music bed) that
     *        forms ONE coherent base curve over the whole track — quiet under
     *        setup/development, leaning in at the climax, settling under the
     *        resolution, soft under the closer. The plan is the BASE arc; the
     *        Gaussian climax swell and the held-silence dip stack on top of it
     *        (separate chained {@code volume} filters → factors multiply), so
     *        all three remain independently tunable. Null/empty = no plan
     *        (current behaviour). Like the swell and dip, the plan is cosmetic:
     *        any envelope failure retries the mix FLAT instead of failing the
     *        render.
     */
    public Path mixBackgroundMusic(Path video, String bgmPath, Path output, Path workdir,
                                   double swellCenterSec, double swellSpreadSec,
                                   double dipStartSec, double dipEndSec,
                                   List<ScoreSegment> scorePlan) {
        boolean withEnvelope = (swellCenterSec > 0 && swellSpreadSec > 0)
                || dipActive(dipStartSec, dipEndSec)
                || planActive(scorePlan);
        try {
            return runMusicMix(video, bgmPath, output, workdir,
                    swellCenterSec, swellSpreadSec, dipStartSec, dipEndSec, scorePlan);
        } catch (RuntimeException e) {
            // The plan/swell/dip envelopes are cosmetic — they must never sink
            // the render. Retry once with a flat bed; if THAT fails too, the
            // music mix itself is broken and the exception propagates as before.
            if (!withEnvelope) throw e;
            log.warn("Music mix with score-plan/swell/dip envelope failed ({}). Retrying "
                    + "with a flat music bed — no plan, no swell, no silence dip — so the "
                    + "render ships.",
                    e.getMessage());
            return runMusicMix(video, bgmPath, output, workdir, 0, 0, 0, 0, List.of());
        }
    }

    /** Dip only fires with a sane, non-degenerate window and a negative depth. */
    private boolean dipActive(double dipStartSec, double dipEndSec) {
        return climaxDipDb < 0
                && dipStartSec >= 0
                && dipEndSec > dipStartSec + 0.2;
    }

    /** Plan only fires when every segment is sane (ordered, non-degenerate,
     *  non-negative start) and the list stays expression-friendly. One broken
     *  segment drops the WHOLE plan — a partial arc would sound arbitrary. */
    private boolean planActive(List<ScoreSegment> plan) {
        if (plan == null || plan.isEmpty() || plan.size() > PLAN_MAX_SEGMENTS) return false;
        double prevEnd = 0;
        boolean anyGain = false;
        for (ScoreSegment s : plan) {
            if (s == null || s.startSec() < 0 || s.endSec() <= s.startSec() + 0.2
                    || s.startSec() + 1e-6 < prevEnd
                    || !Double.isFinite(s.gainDb())) {
                return false;
            }
            prevEnd = s.endSec();
            anyGain |= Math.abs(s.gainDb()) > 0.05;
        }
        return anyGain;   // an all-0dB plan is a no-op → keep the flat bed
    }

    /**
     * Builds the score-plan {@code volume} stage: one piecewise-linear
     * expression that starts at the first phase's level and adds a clamped
     * linear ramp term per level CHANGE — {@code g1 + Δ2*ramp(b2) + Δ3*ramp(b3)
     * + …}, each ramp {@link #PLAN_RAMP_SEC} long and centred on the phase
     * boundary. Same clamp idiom ({@code max(0,min(1,…))}) and quoting as the
     * proven dip trapezoid. Levels before the first segment (the branded
     * intro) hold the first level; after the last segment the closer level
     * holds — the FinalEncoder's afade still takes the whole mix to zero.
     * Gains are clamped to ±12 dB so a config typo can't blow up the bed.
     */
    private String scorePlanExpr(List<ScoreSegment> plan) {
        StringBuilder expr = new StringBuilder();
        double prevGain = linearGain(plan.get(0).gainDb());
        expr.append(String.format(java.util.Locale.ROOT, "%.4f", prevGain));
        for (int i = 1; i < plan.size(); i++) {
            double gain = linearGain(plan.get(i).gainDb());
            double delta = gain - prevGain;
            prevGain = gain;
            if (Math.abs(delta) < 0.001) continue;   // same level → no ramp term
            double rampStart = Math.max(0, plan.get(i).startSec() - PLAN_RAMP_SEC / 2.0);
            expr.append(String.format(java.util.Locale.ROOT,
                    "%+.4f*max(0,min(1,(t-%.2f)/%.2f))",
                    delta, rampStart, PLAN_RAMP_SEC));
        }
        return ",volume='" + expr + "':eval=frame";
    }

    /** dB → linear factor, clamped to ±12 dB (0.25x … 4x) for config safety. */
    private static double linearGain(double db) {
        return Math.pow(10.0, Math.max(-12.0, Math.min(12.0, db)) / 20.0);
    }

    private Path runMusicMix(Path video, String bgmPath, Path output, Path workdir,
                             double swellCenterSec, double swellSpreadSec,
                             double dipStartSec, double dipEndSec,
                             List<ScoreSegment> scorePlan) {
        // Optional beat-sheet score plan FIRST (the base arc); the climax swell
        // and the silence dip are separate chained volume filters on top, so
        // the three envelopes multiply — plan shapes the whole track, swell
        // and dip accent their single beats, exactly as before.
        String swell = "";
        if (planActive(scorePlan)) {
            swell = scorePlanExpr(scorePlan);
        }
        // Optional climax swell on the music branch: a smooth Gaussian volume bump
        // (≈+4 dB peak) centred on the climax — no hard on/off, so it can't click
        // and small timing error just shifts a gentle hump. eval=frame = per-frame.
        if (swellCenterSec > 0 && swellSpreadSec > 0) {
            swell += String.format(java.util.Locale.ROOT,
                    ",volume='1+0.6*exp(-pow((t-%.2f)/%.2f,2))':eval=frame",
                    swellCenterSec, swellSpreadSec);
        }
        if (dipActive(dipStartSec, dipEndSec)) {
            // Trapezoid envelope: flat full dip inside the window, linear
            // DIP_FADE_SEC ramps at both edges (min of the two ramps, clamped
            // to [0,1] — a window shorter than 2 fades just dips less deep).
            // Depth from config dB: -12 dB → 1 - 10^(-12/20) ≈ 0.749.
            double depth = 1.0 - Math.pow(10.0, climaxDipDb / 20.0);
            depth = Math.min(0.98, depth);   // never a hard mute (avoids a dead bed)
            swell += String.format(java.util.Locale.ROOT,
                    ",volume='1-%.3f*max(0,min(1,min((t-%.2f)/%.2f,(%.2f-t)/%.2f)))':eval=frame",
                    depth, dipStartSec, DIP_FADE_SEC, dipEndSec, DIP_FADE_SEC);
        }
        // Filter chain:
        //   1. Loop the music indefinitely so a 30s loop covers 75s video.
        //   2. High-pass at 80Hz to remove rumble that competes with voice.
        //   3. Initial volume reduction (-12 dB) — already below voice.
        //   3b. Optional score-plan arc + climax swell + silence dip (above).
        //   5. Sidechain compressor keyed on the video's audio.
        //   6. Mix dry video audio + ducked music.
        String filter =
                "[1:a]aloop=loop=-1:size=2e9,"
                + "highpass=f=80,"
                + "volume=-12dB"
                + swell
                + "[bgmReady];"
                + // Split video audio into the sidechain trigger + the pass-through
                "[0:a]asplit=2[vfg][vsidechain];"
                + // Compressor: ratio 8:1, attack 20ms (snappy so the first word
                //  isn't buried), release 450ms (smooth recovery so music swells
                //  back up in the gaps), threshold 0.03 (-30 dB) — ducks deeper
                //  under voice/SFX for clearly-readable dialogue, then breathes
                //  back up when the foreground goes quiet.
                "[bgmReady][vsidechain]sidechaincompress="
                + "threshold=0.03:ratio=8:attack=20:release=450:makeup=3"
                + "[bgmDucked];"
                + // Mix the foreground voice + ducked music. Music sits a touch
                //  fuller (0.9) in the quiet beats; the sidechain keeps it out of
                //  the way when anyone speaks.
                "[vfg][bgmDucked]amix=inputs=2:duration=first:weights=1.0 0.9"
                + "[mixed]";

        runner.runFfmpeg(List.of(
                "-y",
                "-i", video.toString(),
                "-i", bgmPath,
                "-filter_complex", filter,
                "-map", "0:v", "-map", "[mixed]",
                "-c:v", "copy",
                // PCM intermediate (audit #1): keep the mix lossless until the
                // FinalEncoder's single AAC pass. Output container is mkv.
                "-c:a", "pcm_s16le",
                "-shortest",
                output.toString()
        ), workdir);
        return output;
    }
}
