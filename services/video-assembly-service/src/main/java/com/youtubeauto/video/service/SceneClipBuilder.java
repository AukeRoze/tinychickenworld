package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest.SceneInput;
import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Step 2: build one self-contained scene clip via the Ken Burns filter graph.
 * The camera motion is parameterised by {@link MotionPreset} so consecutive
 * videos don't share the "every scene slow-zooms-in" AI-farm signature.
 *
 * Canvas size (width × height) is provided per call so the same builder
 * supports both 1920×1080 (landscape) and 1080×1920 (vertical Shorts).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SceneClipBuilder {

    private final FfmpegRunner runner;
    private final VideoProperties props;

    /** Optional ambient-effects overlay (drifting fireflies / butterflies /
     *  petals / bokeh) composited over every Ken Burns scene to add life to an
     *  otherwise-still image. Expects a LOOPING clip with a transparent
     *  background. Dormant unless one of these exists — the default render is
     *  untouched. Drop a loop here to enable. (Per-location/time-of-day
     *  selection is a backlog item; this is the single global layer.) */
    private static final String[] AMBIENT_FX_PATHS = {
            "/bible/fx/ambient.mov",
            "/bible/fx/ambient.webm"
    };
    private static final double AMBIENT_FX_OPACITY = 0.8;

    private String ambientFxPath() {
        for (String p : AMBIENT_FX_PATHS) {
            if (java.nio.file.Files.exists(java.nio.file.Paths.get(p))) return p;
        }
        return null;
    }

    /** Effective scene length: never SHORTER than the voice-over (+a small tail)
     *  so a spoken line is never cut off mid-sentence. Bounded to scripted+3s so a
     *  runaway line can't blow up the timeline; falls back to the scripted
     *  duration if the voice can't be probed. */
    private int effectiveDur(SceneInput scene, Path workdir) {
        int scripted = scene.durationSeconds();
        String audio = scene.audioPath();
        if (audio == null || audio.isBlank()) return scripted;
        try {
            String out = runner.runFfprobe(List.of(
                    "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", audio), workdir);
            for (String line : out.split("\\R")) {
                try {
                    double voice = Double.parseDouble(line.trim());
                    if (voice > 0.1) {
                        int need = (int) Math.ceil(voice + 0.4);     // small tail after the line
                        return Math.min(Math.max(scripted, need), scripted + 3);  // bounded +3s
                    }
                } catch (NumberFormatException ignore) { /* try next line */ }
            }
        } catch (Exception e) {
            log.debug("scene seq={} voice probe failed ({}) — using scripted dur", scene.seq(), e.toString());
        }
        return scripted;
    }

    public Path build(SceneInput scene, MotionPreset motion,
                      int w, int h,
                      Path workdir, Path output) {
        int fps = props.output().fps();
        int dur = effectiveDur(scene, workdir);
        int frames = dur * fps;

        String motionChain = motion.filterChain(frames, w, h, fps);
        String fx = ambientFxPath();

        String filter;
        List<String> args = new java.util.ArrayList<>(List.of("-y",
                "-loop", "1", "-t", String.valueOf(dur), "-i", scene.imagePath(),
                "-i", scene.audioPath()));

        // Blurred-fill base: the SHARP image is scaled to FIT (decrease — nothing
        // cropped, full subject always visible) and centered over a blurred,
        // enlarged copy that fills the whole canvas (so there are never black
        // bars, whatever the source aspect). When the image already matches the
        // canvas aspect the blurred layer is fully hidden — zero downside. Ken
        // Burns motion is applied to the finished composite.
        String blurredBase =
                "[0:v]split=2[fgsrc][bgsrc];"
                + String.format("[bgsrc]scale=%d:%d:force_original_aspect_ratio=increase,"
                        + "crop=%d:%d,boxblur=20:1,eq=brightness=-0.05[bg];", w, h, w, h)
                + String.format("[fgsrc]scale=%d:%d:force_original_aspect_ratio=decrease[fg];", w, h)
                + "[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1[based];";

        if (fx != null) {
            // [0]=image  [1]=audio  [2]=looping fx overlay (transparent bg)
            filter = blurredBase + String.format(
                    "[based]%s[base];" +
                    "[2:v]scale=%d:%d,format=rgba,colorchannelmixer=aa=%.2f[fx];" +
                    "[base][fx]overlay=eof_action=repeat:format=auto[v];" +
                    "[1:a]apad,atrim=duration=%d[a]",
                    motionChain, w, h, AMBIENT_FX_OPACITY, dur
            );
            args.add("-stream_loop"); args.add("-1"); args.add("-i"); args.add(fx);
        } else {
            filter = blurredBase + String.format(
                    "[based]%s[v];" +
                    "[1:a]apad,atrim=duration=%d[a]",
                    motionChain, dur
            );
        }

        log.debug("scene seq={} motion={} canvas={}x{} fx={}", scene.seq(), motion, w, h, fx != null);

        args.add("-filter_complex"); args.add(filter);
        args.add("-map"); args.add("[v]");
        args.add("-map"); args.add("[a]");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        // crf 16 (was 20): this is the FIRST re-encode of the Veo pixels and
        // every later pass compounds on it — the weakest link must not be the
        // first one (audit 2026-06-11, encode-cascade).
        args.add("-crf"); args.add("16");
        args.add("-r"); args.add(String.valueOf(fps));
        // Pin 4:2:0 so the concat doesn't inherit 4:4:4 (overlay=format=auto
        // can yield yuv444p, which ~doubles decode/encode memory and OOM-kills
        // the multi-input xfade graph). Final delivery is 4:2:0 anyway.
        args.add("-pix_fmt"); args.add("yuv420p");
        // PCM intermediate (audit #1) — voice stays lossless until FinalEncoder.
        args.add("-c:a"); args.add("pcm_s16le");
        args.add("-ar"); args.add("48000");
        args.add("-shortest");
        args.add(output.toString());

        runner.runFfmpeg(args, workdir);
        return output;
    }

    /**
     * Build a scene clip from a pre-rendered video (e.g. Veo image-to-video).
     * Bypasses the Ken Burns filter graph entirely — the source clip already
     * has motion. We re-encode to the project canvas + standard codecs and
     * replace the audio track with the voice narration WAV/MP3.
     */
    public Path buildFromClip(SceneInput scene, int w, int h,
                              Path workdir, Path output) {
        int fps = props.output().fps();
        int dur = effectiveDur(scene, workdir);

        // tpad holds the LAST frame after the clip ends so a Veo clip shorter than
        // the scene's scripted duration (e.g. the 8s Veo cap on a longer beat)
        // fills out to full length instead of cutting early. The voice track
        // (atrim=dur) + -shortest cap the output at `dur`, so the hold only ever
        // appears when the clip is genuinely shorter than the scene.
        String filter = String.format(
                "[0:v]scale=%d:%d:force_original_aspect_ratio=increase," +
                "crop=%d:%d,setsar=1,fps=%d,tpad=stop_mode=clone:stop_duration=30[v];" +
                "[1:a]apad,atrim=duration=%d[a]",
                w, h, w, h, fps, dur
        );

        log.debug("scene seq={} (from clip) canvas={}x{}", scene.seq(), w, h);

        List<String> args = List.of(
                "-y",
                "-t", String.valueOf(dur), "-i", scene.clipPath(),
                "-i", scene.audioPath(),
                "-filter_complex", filter,
                "-map", "[v]", "-map", "[a]",
                // crf 16 (was 20): first re-encode of the Veo clip — see above.
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "16", "-r", String.valueOf(fps),
                "-pix_fmt", "yuv420p",
                "-c:a", "pcm_s16le", "-ar", "48000",   // lossless intermediate (audit #1)
                "-shortest",
                output.toString()
        );
        runner.runFfmpeg(args, workdir);
        return output;
    }
}
