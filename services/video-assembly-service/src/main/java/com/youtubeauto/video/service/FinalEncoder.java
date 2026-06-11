package com.youtubeauto.video.service;

import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Step 6: re-encode pass tuned for YouTube upload, with two-pass loudnorm. */
@Slf4j
@Component
@RequiredArgsConstructor
public class FinalEncoder {

    private static final String TARGET = "I=-14:LRA=11:TP=-1.5";

    private final FfmpegRunner runner;
    private final VideoProperties props;

    /** End-of-episode wind-down lengths. Audio runs slightly longer than the
     *  picture so the music breathes out a beat after the screen has gone dark
     *  — the classic bedtime-content close (feedback: "outro stopt te abrupt"). */
    private static final double VIDEO_FADE_SECONDS = 2.0;
    private static final double AUDIO_FADE_SECONDS = 2.5;

    public Path encode(Path input, Path output, Path workdir) {
        double dur = probeDuration(input, workdir);
        java.util.List<String> args = new java.util.ArrayList<>(List.of(
                "-y",
                "-i", input.toString(),
                "-c:v", "libx264",
                "-preset", "medium",
                "-crf", String.valueOf(props.output().videoCrf()),
                "-pix_fmt", "yuv420p",
                "-profile:v", "high", "-level", "4.1",
                "-movflags", "+faststart"));
        // Picture fade-to-black on the tail — the audio already faded but the
        // image used to hold full brightness into the very last frame, which
        // read as an abrupt stop. Skipped when the duration can't be probed.
        if (dur > 10) {
            args.add("-vf");
            args.add(String.format(java.util.Locale.ROOT,
                    "fade=t=out:st=%.2f:d=%.2f",
                    dur - VIDEO_FADE_SECONDS, VIDEO_FADE_SECONDS));
        }
        // Final loudness normalisation — applied here (the last audio pass,
        // after music + sting are mixed in) so the delivered loudness is
        // actually -14 LUFS, not just the pre-music voice bed. Two-pass
        // (measure → apply) is ~5% more accurate than single-pass; falls
        // back to single-pass automatically if the measurement fails.
        args.add("-af");
        args.add(loudnormFilter(input, workdir) + endFade(dur));
        args.add("-c:a"); args.add("aac");
        args.add("-b:a"); args.add(props.output().audioBitrateKbps() + "k");
        args.add("-ar"); args.add("48000");
        args.add(output.toString());
        runner.runFfmpeg(args, workdir);
        return output;
    }

    /** Master-level audio wind-down on the FINAL mix. "" when unprobeable. */
    private String endFade(double dur) {
        if (dur <= 10) return "";
        return String.format(java.util.Locale.ROOT,
                ",afade=t=out:st=%.2f:d=%.1f",
                dur - AUDIO_FADE_SECONDS, AUDIO_FADE_SECONDS);
    }

    /** Container duration in seconds, or -1 when it can't be probed (the
     *  encode then simply runs without the tail fades). */
    private double probeDuration(Path input, Path workdir) {
        try {
            String out = runner.runFfprobe(List.of(
                    "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", input.toString()), workdir);
            for (String line : out.split("\\R")) {
                try {
                    return Double.parseDouble(line.trim());
                } catch (NumberFormatException ignore) { /* next line */ }
            }
        } catch (Exception e) {
            // no fade rather than a broken encode
        }
        return -1;
    }

    /** Builds the loudnorm filter. Runs a measurement pass first and feeds the
     *  measured values back for linear normalisation; on any failure returns the
     *  plain single-pass filter so the encode never breaks. */
    private String loudnormFilter(Path input, Path workdir) {
        String single = "loudnorm=" + TARGET;
        try {
            String report = runner.runFfmpegCaptured(List.of(
                    "-hide_banner", "-i", input.toString(),
                    "-af", "loudnorm=" + TARGET + ":print_format=json",
                    "-f", "null", "-"), workdir);
            String iI   = json(report, "input_i");
            String iTp  = json(report, "input_tp");
            String iLra = json(report, "input_lra");
            String iTh  = json(report, "input_thresh");
            String off  = json(report, "target_offset");
            if (iI == null || iTp == null || iLra == null || iTh == null || off == null) {
                log.warn("Two-pass loudnorm: incomplete measurement, using single-pass");
                return single;
            }
            log.info("Two-pass loudnorm: measured I={} TP={} LRA={} thresh={} offset={}",
                    iI, iTp, iLra, iTh, off);
            return "loudnorm=" + TARGET
                    + ":measured_I=" + iI + ":measured_TP=" + iTp
                    + ":measured_LRA=" + iLra + ":measured_thresh=" + iTh
                    + ":offset=" + off + ":linear=true:print_format=summary";
        } catch (Exception e) {
            log.warn("Two-pass loudnorm measurement failed ({}), using single-pass", e.getMessage());
            return single;
        }
    }

    /** Extracts a numeric value for {@code "key" : "value"} from the loudnorm
     *  JSON report (values are quoted strings in ffmpeg's output). Returns null
     *  for missing or non-finite (-inf) values so the caller falls back. */
    private String json(String report, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key)
                + "\"\\s*:\\s*\"(-?[0-9.]+|-?inf)\"").matcher(report);
        if (!m.find()) return null;
        String v = m.group(1);
        return v.endsWith("inf") ? null : v;
    }
}
