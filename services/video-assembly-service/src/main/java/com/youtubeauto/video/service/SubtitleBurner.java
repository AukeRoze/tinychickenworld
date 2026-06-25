package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest.SceneInput;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.List;

/**
 * Step 5: write SRT from scene narrations + durations and burn it as
 * a hardcoded subtitle track. Pro-grade styling:
 *   - Bold large font (kids-friendly)
 *   - White text with warm-orange outline + soft black shadow
 *   - Lower-third position (above the end-card area)
 *   - Auto-split long lines so nothing overflows
 *   - Character emoji prefix when speaker is detected
 *   - Empty narration skipped (no blank captions for SFX-only scenes)
 */
@Component
@RequiredArgsConstructor
public class SubtitleBurner {

    private final FfmpegRunner runner;
    private static final int MAX_LINE_CHARS = 38;

    public void writeSrt(List<SceneInput> scenes, Path srtPath) {
        writeSrt(scenes, srtPath, 0);
    }

    /**
     * @param offsetSeconds shift every cue by this many seconds — set to the
     *        intro duration so the captions line up with the FINAL video (the
     *        intro is prepended before the scenes). Fixes both the burned-in
     *        path and the uploaded soft-caption track.
     *
     * <p>When a scene carries {@code lineTimings} (voice-service knows exactly
     * where each spoken line sits in the scene audio) the SRT gets one
     * millisecond-accurate cue per LINE. Scenes without timing fall back to the
     * old single whole-scene cue — so silent/sounds modes keep working.
     */
    public void writeSrt(List<SceneInput> scenes, Path srtPath, int offsetSeconds) {
        writeSrt(scenes, srtPath, offsetSeconds, null, null);
    }

    /**
     * Real-timeline overload — the ONLY one that stays in sync over a whole
     * episode. {@code startsSec[i]} / {@code dursSec[i]} are scene i's true
     * start + length in the JOINED video (from {@link Concatenator#sceneTimeline},
     * which subtracts the crossfade overlap between every cut), and
     * {@code introOffsetSec} is the fractional intro length prepended in front.
     * Cues are anchored to those real positions instead of summed nominal
     * {@code durationSeconds}, which drifted later and later because it ignored
     * the xfade overlaps and rounded each scene to a whole second
     * (gebruikersfeedback 2026-06-25).
     *
     * <p>The arrays are indexed by seq-sorted scene order — the same order
     * {@link Concatenator} builds the clips in. When they're null/empty or don't
     * match the scene count, this falls back to the old nominal-seconds timing.
     */
    public void writeSrt(List<SceneInput> scenes, Path srtPath, double introOffsetSec,
                         double[] startsSec, double[] dursSec) {
        boolean haveRealTimeline = startsSec != null && dursSec != null
                && startsSec.length == scenes.size() && dursSec.length == scenes.size();
        if (!haveRealTimeline) {
            writeSrtNominal(scenes, srtPath, (int) Math.round(introOffsetSec));
            return;
        }
        // Anchor every scene to its real position; iterate in the SAME seq-sorted
        // order the timeline arrays use.
        List<SceneInput> ordered = scenes.stream()
                .sorted(java.util.Comparator.comparingInt(SceneInput::seq)).toList();
        long introMs = Math.round(Math.max(0, introOffsetSec) * 1000.0);
        StringBuilder sb = new StringBuilder();
        int srtIdx = 1;
        for (int i = 0; i < ordered.size(); i++) {
            SceneInput s = ordered.get(i);
            long sceneStart = introMs + Math.round(startsSec[i] * 1000.0);
            long sceneEnd   = introMs + Math.round((startsSec[i] + dursSec[i]) * 1000.0);
            srtIdx = appendCues(sb, srtIdx, s, sceneStart, sceneEnd);
        }
        write(srtPath, sb.toString());
    }

    /** Original nominal-seconds timing — kept as the fallback path. */
    private void writeSrtNominal(List<SceneInput> scenes, Path srtPath, int offsetSeconds) {
        StringBuilder sb = new StringBuilder();
        long tMs = Math.max(0, offsetSeconds) * 1000L;
        int srtIdx = 1;
        for (SceneInput s : scenes) {
            long sceneStart = tMs;
            long sceneEnd   = tMs + s.durationSeconds() * 1000L;
            srtIdx = appendCues(sb, srtIdx, s, sceneStart, sceneEnd);
            tMs = sceneEnd;
        }
        write(srtPath, sb.toString());
    }

    /** Appends the cue(s) for one scene confined to [{@code sceneStart},
     *  {@code sceneEnd}] (ms). Line-level timing when present, else one
     *  whole-scene cue. Returns the next SRT index. */
    private int appendCues(StringBuilder sb, int srtIdx, SceneInput s,
                           long sceneStart, long sceneEnd) {
        if (s.lineTimings() != null && !s.lineTimings().isEmpty()) {
            for (SceneInput.LineTiming lt : s.lineTimings()) {
                String text = formatLine(lt.text());
                if (text == null || text.isBlank() || lt.durMs() <= 0) continue;
                long start = Math.min(sceneEnd, sceneStart + Math.max(0, lt.startMs()));
                // Hold each cue a touch past the audio (min 900ms) so short
                // exclamations stay readable, but never past the scene.
                long end = Math.min(sceneEnd, start + Math.max(900, lt.durMs()));
                if (end <= start) continue;
                sb.append(srtIdx++).append('\n')
                  .append(fmtMs(start)).append(" --> ").append(fmtMs(end)).append('\n')
                  .append(text).append("\n\n");
            }
        } else {
            String text = formatLine(s.narration());
            // Skip empty captions — better than showing a blank box.
            if (text != null && !text.isBlank()) {
                sb.append(srtIdx++).append('\n')
                  .append(fmtMs(sceneStart)).append(" --> ").append(fmtMs(sceneEnd)).append('\n')
                  .append(text).append("\n\n");
            }
        }
        return srtIdx;
    }

    private void write(Path srtPath, String content) {
        try {
            Files.writeString(srtPath, content,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write SRT", e);
        }
    }

    /** Returns formatted caption text: emoji prefix if speaker detected,
     *  line-wrapped, null if blank. */
    private String formatLine(String narration) {
        if (narration == null) return null;
        String n = narration.trim();
        if (n.isEmpty()) return null;
        // NOTE: no emoji prefix — DejaVu Sans has no colour emoji glyphs, so
        // they rendered as "tofu" boxes. Clean text reads better for kids.
        // Wrap long lines so nothing overflows the lower-third on phones.
        return wrap(n, MAX_LINE_CHARS);
    }

    /** Simple word-wrap to max chars per line, preserving word boundaries. */
    private String wrap(String text, int maxChars) {
        StringBuilder out = new StringBuilder();
        StringBuilder line = new StringBuilder();
        for (String word : text.split("\\s+")) {
            if (line.length() + 1 + word.length() > maxChars && line.length() > 0) {
                out.append(line).append('\n');
                line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(word);
        }
        out.append(line);
        return out.toString();
    }

    public Path burn(Path video, Path srt, Path output, Path workdir) {
        // ASS-style overrides via force_style. Color format is &HAABBGGRR& (BGR,
        // alpha 00=opaque..FF=transparent). Deliberately SMALL and minimal so the
        // captions never dominate the image: small white text with a thin dark
        // outline + soft shadow (BorderStyle=1, NO heavy box), low in the frame.
        String style = String.join(",",
                "Fontname=DejaVu Sans Bold",
                "Fontsize=22",                  // small + unobtrusive (was 44/34)
                "PrimaryColour=&H00FFFFFF&",    // opaque white text
                "OutlineColour=&H00101010&",    // thin near-black outline for legibility
                "BackColour=&H80000000&",       // soft drop shadow
                "Outline=1.5",                  // thin outline, not a box
                "Shadow=1",
                "BorderStyle=1",                // outline + shadow (no scrim box)
                "MarginV=60",                   // low in the lower band
                "Alignment=2",                  // bottom-center
                "Bold=1",
                "Spacing=0.2"
        );
        String filter = "subtitles='" + srt.toAbsolutePath().toString().replace("\\", "/").replace(":", "\\:")
                + "':force_style='" + style + "'";

        runner.runFfmpeg(List.of(
                "-y",
                "-i", video.toString(),
                "-vf", filter,
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "16",  // near-lossless intermediate
                "-c:a", "copy",
                output.toString()
        ), workdir);
        return output;
    }

    private static String fmtMs(long totalMs) {
        long h = totalMs / 3_600_000;
        long m = (totalMs % 3_600_000) / 60_000;
        long s = (totalMs % 60_000) / 1000;
        long ms = totalMs % 1000;
        return String.format("%02d:%02d:%02d,%03d", h, m, s, ms);
    }
}
