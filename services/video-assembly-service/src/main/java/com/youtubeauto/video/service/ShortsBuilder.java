package com.youtubeauto.video.service;

import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Extracts a vertical YouTube Shorts (9:16, max 60s) clip from the
 * assembled landscape master. The "highlight" is the HOOK (first 8-15s)
 * plus a snippet from the CLIMAX. Result: instant Shorts content from
 * every longform video, doubling output and algorithm reach.
 *
 * Strategy:
 *   1. Take the first {@code hookSeconds} seconds (the HOOK).
 *   2. Take {@code climaxSeconds} from the climax window
 *      ({@code climaxStartSec} into the master).
 *   3. Concat + crop to 9:16 with centred-on-action crop, padding
 *      the top/bottom with blurred background of the source frame.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShortsBuilder {

    private final FfmpegRunner runner;

    /** Back-compat: hook starts at the very top of the master. */
    public Path build(Path masterMp4, int totalSeconds, Path outShort, Path workdir) {
        return build(masterMp4, totalSeconds, 0, null, null, outShort, workdir);
    }

    /** Back-compat: no caption track, no hook text. */
    public Path build(Path masterMp4, int totalSeconds, int hookStartSec, Path outShort, Path workdir) {
        return build(masterMp4, totalSeconds, hookStartSec, null, null, outShort, workdir);
    }

    /** Back-compat: captions but no hook text. */
    public Path build(Path masterMp4, int totalSeconds, int hookStartSec, Path masterSrt,
                      Path outShort, Path workdir) {
        return build(masterMp4, totalSeconds, hookStartSec, masterSrt, null, outShort, workdir);
    }

    /**
     * @param hookStartSec where the HOOK extraction starts — pass the intro
     *        duration so the Short opens on the actual cold-open instead of
     *        the branded intro bumper (a Short that spends its first 6 of 22
     *        seconds on a logo loses the scroll-by viewer instantly).
     * @param masterSrt the master's SRT — the cues that fall inside the hook
     *        and climax windows are remapped to the Short's timeline and
     *        BURNED IN, big and high. Shorts are watched muted-first: without
     *        captions the dialogue simply doesn't exist for most viewers.
     *        Null/missing = no captions (old behaviour).
     * @param title episode title — shown as a BIG text hook in the top third
     *        during the first ~3 seconds (the scroll-stop moment decides a
     *        Short's fate; a text hook buys the extra half-second). Null = none.
     */
    public Path build(Path masterMp4, int totalSeconds, int hookStartSec, Path masterSrt,
                      String title, Path outShort, Path workdir) {
        // Reasonable defaults — HOOK 10s + 12s of the most energetic moment.
        int hookSeconds   = 10;
        int climaxSeconds = 12;
        int hookStart     = Math.max(0, Math.min(hookStartSec, Math.max(0, totalSeconds - hookSeconds)));
        // Smarter than a fixed 55%% crop: scan the master's loudness and centre
        // the second clip on the most ENERGETIC moment (gasp / laugh / reveal).
        int climaxStart   = energeticStart(masterMp4, totalSeconds, climaxSeconds,
                hookStart + hookSeconds, workdir);

        // Step 1: extract hook + climax with stream-copy speed
        Path hook    = workdir.resolve("short_hook.mp4");
        Path climax  = workdir.resolve("short_climax.mp4");
        runner.runFfmpeg(List.of(
                "-y", "-ss", String.valueOf(hookStart), "-i", masterMp4.toString(),
                "-t", String.valueOf(hookSeconds),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "17",
                "-c:a", "aac", "-b:a", "192k",
                hook.toString()
        ), workdir);
        runner.runFfmpeg(List.of(
                "-y", "-ss", String.valueOf(climaxStart), "-i", masterMp4.toString(),
                "-t", String.valueOf(climaxSeconds),
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "17",
                "-c:a", "aac", "-b:a", "192k",
                climax.toString()
        ), workdir);

        // Step 2: write concat list
        Path concatList = workdir.resolve("short_concat.txt");
        try {
            java.nio.file.Files.writeString(concatList,
                "file '" + hook.toAbsolutePath() + "'\n" +
                "file '" + climax.toAbsolutePath() + "'\n");
        } catch (java.io.IOException e) {
            throw new IllegalStateException("concat write failed", e);
        }

        // Step 2b: remap the master's captions onto the Short's timeline
        // (hook window → starts at 0; climax window → starts at hookSeconds).
        Path shortSrt = remapSrtForShort(masterSrt, hookStart, hookSeconds,
                climaxStart, climaxSeconds, workdir);

        // Step 3: concat + reframe to 9:16 (1080x1920) with blurred
        // padding of the source. Centres action vertically.
        // Filter: scale to 1920 height, then crop centre to 1080 wide,
        // background = blurred wider version padded behind. When captions are
        // available they are burned in BIG (muted-first viewing).
        String filter =
                "[0:v]split=2[bg][fg];"
                + "[bg]scale=1080:1920:force_original_aspect_ratio=increase,"
                + "crop=1080:1920,boxblur=20:1[bgblur];"
                + "[fg]scale=1080:-2:force_original_aspect_ratio=decrease[fgscaled];"
                + "[bgblur][fgscaled]overlay=(W-w)/2:(H-h)/2[vshort]";
        String videoOut = "[vshort]";
        if (shortSrt != null) {
            String style = String.join(",",
                    "Fontname=DejaVu Sans Bold", "Fontsize=17",
                    "PrimaryColour=&H00FFFFFF&", "OutlineColour=&H00101010&",
                    "BackColour=&H80000000&", "Outline=2", "Shadow=1",
                    "BorderStyle=1", "MarginV=58", "Alignment=2", "Bold=1");
            String srtPath = shortSrt.toAbsolutePath().toString()
                    .replace("\\", "/").replace(":", "\\:");
            filter += ";[vshort]subtitles='" + srtPath + "':force_style='" + style + "'[vsub]";
            videoOut = "[vsub]";
        }
        // Text hook in the top third for the first ~3s — the scroll-stop beat.
        // Sits high so it never collides with the burned captions low in frame.
        if (title != null && !title.isBlank()) {
            String safe = title.replace("'", "").replace(":", "").replace("\"", "");
            if (safe.length() > 36) safe = safe.substring(0, 33) + "...";
            String in = videoOut.substring(1, videoOut.length() - 1);
            filter += ";[" + in + "]drawtext="
                    + "fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
                    + ":text='" + safe + "'"
                    + ":fontsize=58:fontcolor=white"
                    + ":box=1:boxcolor=0xB83A1F@0.90:boxborderw=20"
                    + ":x=(w-text_w)/2:y=(h*0.10)"
                    + ":enable=between(t\\,0.2\\,3.2)"
                    + ":alpha=if(lt(t\\,0.2)\\,0\\,if(lt(t\\,0.6)\\,(t-0.2)/0.4\\,"
                    + "if(gt(t\\,2.8)\\,max(0\\,(3.2-t)/0.4)\\,1)))"
                    + "[vhook]";
            videoOut = "[vhook]";
        }
        runner.runFfmpeg(List.of(
                "-y", "-f", "concat", "-safe", "0", "-i", concatList.toString(),
                "-filter_complex", filter,
                "-map", videoOut, "-map", "0:a",
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "17",
                "-c:a", "aac", "-b:a", "192k",
                "-movflags", "+faststart",
                outShort.toString()
        ), workdir);

        log.info("Short built: {} from master {} ({}s)", outShort, masterMp4, totalSeconds);
        return outShort;
    }

    /**
     * Remaps the master's SRT cues onto the Short's timeline: cues that
     * intersect the hook window land at {@code t - hookStart}, cues in the
     * climax window at {@code hookSeconds + (t - climaxStart)}. Cue edges are
     * clamped to their window; slivers under 300ms are dropped. Returns null
     * when there is no SRT or no cue survives (the Short then ships clean).
     */
    private Path remapSrtForShort(Path masterSrt, int hookStart, int hookSeconds,
                                  int climaxStart, int climaxSeconds, Path workdir) {
        if (masterSrt == null || !java.nio.file.Files.isReadable(masterSrt)) return null;
        try {
            record Cue(long s, long e, String text) {}
            List<Cue> cues = new java.util.ArrayList<>();
            String[] blocks = java.nio.file.Files.readString(masterSrt).split("\\R\\R+");
            java.util.regex.Pattern times = java.util.regex.Pattern.compile(
                    "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})\\s*-->\\s*"
                    + "(\\d{2}):(\\d{2}):(\\d{2}),(\\d{3})");
            for (String block : blocks) {
                java.util.regex.Matcher m = times.matcher(block);
                if (!m.find()) continue;
                long s = ms(m, 1), e = ms(m, 5);
                String text = block.substring(m.end()).strip();
                if (!text.isBlank()) cues.add(new Cue(s, e, text));
            }
            // windows in ms + their offset to the Short's timeline
            long[][] windows = {
                    {hookStart * 1000L, (hookStart + hookSeconds) * 1000L, -hookStart * 1000L},
                    {climaxStart * 1000L, (climaxStart + climaxSeconds) * 1000L,
                            hookSeconds * 1000L - climaxStart * 1000L}
            };
            record Out(long s, long e, String text) {}
            List<Out> out = new java.util.ArrayList<>();
            for (long[] w : windows) {
                for (Cue c : cues) {
                    long s = Math.max(c.s(), w[0]), e = Math.min(c.e(), w[1]);
                    if (e - s < 300) continue;                  // sliver — unreadable
                    out.add(new Out(s + w[2], e + w[2], c.text()));
                }
            }
            if (out.isEmpty()) return null;
            out.sort((a, b) -> Long.compare(a.s(), b.s()));
            StringBuilder sb = new StringBuilder();
            int idx = 1;
            for (Out o : out) {
                sb.append(idx++).append('\n')
                  .append(srtStamp(o.s())).append(" --> ").append(srtStamp(o.e())).append('\n')
                  .append(o.text()).append("\n\n");
            }
            Path srt = workdir.resolve("short_subs.srt");
            java.nio.file.Files.writeString(srt, sb.toString());
            log.info("Short captions: {} cue(s) remapped from the master SRT", idx - 1);
            return srt;
        } catch (Exception e) {
            log.warn("Short caption remap failed ({}) — Short ships without captions",
                    e.getMessage());
            return null;
        }
    }

    private static long ms(java.util.regex.Matcher m, int g) {
        return Long.parseLong(m.group(g)) * 3_600_000L
                + Long.parseLong(m.group(g + 1)) * 60_000L
                + Long.parseLong(m.group(g + 2)) * 1000L
                + Long.parseLong(m.group(g + 3));
    }

    private static String srtStamp(long t) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d:%02d,%03d",
                t / 3_600_000, (t % 3_600_000) / 60_000, (t % 60_000) / 1000, t % 1000);
    }

    /**
     * Finds the start (seconds) of the most ENERGETIC {@code clipLen}-second
     * window by scanning short-term loudness (ebur128). Kept after the hook
     * ({@code minStart}). Falls back to ~55%% of the master on any failure so a
     * loudness-scan hiccup never breaks the Short.
     */
    private int energeticStart(Path master, int totalSeconds, int clipLen, int minStart, Path workdir) {
        int fallback = Math.max(minStart, Math.min((int) (totalSeconds * 0.55),
                Math.max(minStart, totalSeconds - clipLen)));
        try {
            String out = runner.runFfmpegCaptured(List.of(
                    "-hide_banner", "-i", master.toString(),
                    "-af", "ebur128=metadata=1", "-f", "null", "-"), workdir);
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("t:\\s*([0-9.]+).*?S:\\s*(-?[0-9.]+)").matcher(out);
            double bestT = -1, bestS = -1e9;
            double latestStart = totalSeconds - clipLen / 2.0;
            while (m.find()) {
                double t = Double.parseDouble(m.group(1));
                double s = Double.parseDouble(m.group(2));
                if (s < -70) continue;          // silence / start-up ramp
                if (t < minStart) continue;     // keep it after the hook
                if (t > latestStart) continue;  // leave room for the full clip
                if (s > bestS) { bestS = s; bestT = t; }
            }
            if (bestT < 0) return fallback;
            int start = (int) Math.round(bestT - clipLen / 2.0);
            start = Math.max(minStart, Math.min(start, Math.max(minStart, totalSeconds - clipLen)));
            log.info("Short: loudest moment at {}s (S={} LUFS) -> climax start {}s", bestT, bestS, start);
            return start;
        } catch (Exception e) {
            log.warn("Short: loudness scan failed ({}), using {}s", e.getMessage(), fallback);
            return fallback;
        }
    }
}
