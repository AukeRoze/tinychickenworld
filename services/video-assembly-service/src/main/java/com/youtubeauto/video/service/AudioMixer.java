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
public class AudioMixer {

    private final FfmpegRunner runner;

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
     * @param dipCenterSec/dipSpreadSec optional near-silence window for the
     *        scripted SILENT visual beat (board #18): the music sinks to ~25%
     *        with the same smooth Gaussian shape as the swell — a held breath
     *        deserves a held score. 0 = no dip.
     */
    public Path mixBackgroundMusic(Path video, String bgmPath, Path output, Path workdir,
                                   double swellCenterSec, double swellSpreadSec,
                                   double dipCenterSec, double dipSpreadSec) {
        // Optional climax swell on the music branch: a smooth Gaussian volume bump
        // (≈+4 dB peak) centred on the climax — no hard on/off, so it can't click
        // and small timing error just shifts a gentle hump. eval=frame = per-frame.
        String swell = "";
        if (swellCenterSec > 0 && swellSpreadSec > 0) {
            swell = String.format(java.util.Locale.ROOT,
                    ",volume='1+0.6*exp(-pow((t-%.2f)/%.2f,2))':eval=frame",
                    swellCenterSec, swellSpreadSec);
        }
        if (dipCenterSec > 0 && dipSpreadSec > 0) {
            swell += String.format(java.util.Locale.ROOT,
                    ",volume='1-0.75*exp(-pow((t-%.2f)/%.2f,2))':eval=frame",
                    dipCenterSec, dipSpreadSec);
        }
        // Filter chain:
        //   1. Loop the music indefinitely so a 30s loop covers 75s video.
        //   2. High-pass at 80Hz to remove rumble that competes with voice.
        //   3. Initial volume reduction (-12 dB) — already below voice.
        //   3b. Optional climax swell (above).
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
