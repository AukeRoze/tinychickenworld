package com.youtubeauto.voice.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Generates a silent MP3 of an estimated duration via ffmpeg. Used when
 * voice synthesis is disabled (e.g. to test the pipeline without burning
 * ElevenLabs quota). Duration is estimated from word count so timing
 * matches what real narration would have been.
 */
@Slf4j
@Component
public class SilentAudio {

    private static final double WORDS_PER_SECOND = 2.5;     // ~150 wpm kids cadence
    private static final double PAUSE_PER_LINE_SEC = 0.4;
    private static final double MIN_SECONDS = 4.0;

    /** Estimate scene duration in seconds from the per-line texts. */
    public double estimateSeconds(List<String> lineTexts) {
        if (lineTexts == null || lineTexts.isEmpty()) return MIN_SECONDS;
        int totalWords = 0;
        for (String t : lineTexts) {
            if (t == null || t.isBlank()) continue;
            totalWords += t.trim().split("\\s+").length;
        }
        double sec = (totalWords / WORDS_PER_SECOND)
                + (lineTexts.size() * PAUSE_PER_LINE_SEC);
        return Math.max(MIN_SECONDS, sec);
    }

    /** Write a silent MP3 of the given duration to {@code out}. */
    public void writeSilent(double seconds, Path out) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-y", "-f", "lavfi",
                    "-i", "anullsrc=channel_layout=mono:sample_rate=44100",
                    "-t", String.format(java.util.Locale.ROOT, "%.2f", seconds),
                    "-c:a", "libmp3lame", "-b:a", "64k",
                    out.toString()
            );
            pb.redirectErrorStream(true);
            Process p = pb.start();
            // Drain stdout so the buffer doesn't block ffmpeg
            p.getInputStream().transferTo(java.io.OutputStream.nullOutputStream());
            int code = p.waitFor();
            if (code != 0) {
                throw new IOException("ffmpeg exited " + code + " producing silent audio");
            }
            log.debug("silent {}s -> {}", seconds, out);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalStateException("Could not write silent MP3 " + out, e);
        }
    }
}
