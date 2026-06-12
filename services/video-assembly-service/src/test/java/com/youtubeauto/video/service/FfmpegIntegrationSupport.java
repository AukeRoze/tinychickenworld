package com.youtubeauto.video.service;

import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Shared plumbing for the REAL-ffmpeg integration tests in this module
 * ({@link ConcatenatorRenderTest}, {@link AudioMixerRenderTest},
 * {@link SceneClipBuilderRenderTest}).
 *
 * <p><b>No binary fixtures in git.</b> Every input (scene clips, still image,
 * voice/music tracks) is generated on the fly with ffmpeg's own lavfi sources
 * ({@code testsrc} + {@code sine}) into a JUnit {@code @TempDir} — tiny
 * (320×180 @ 15fps, 2–10s) so a full run stays in the seconds range.
 *
 * <p><b>Skip condition.</b> The tests only run when BOTH {@code ffmpeg} and
 * {@code ffprobe} respond to {@code -version} on the PATH (see
 * {@link #ffmpegAvailable()}, referenced from each test class's
 * {@code @EnabledIf}). Without them, {@code mvn test} stays green — the
 * classes report as skipped with a clear reason. Note that the production
 * code itself requires ffprobe on the PATH too: besides the configurable
 * {@code app.ffmpeg.binary}/{@code probeBinary} (default {@code ffmpeg} /
 * {@code ffprobe}), {@code Concatenator.probeDuration} spawns a literal
 * {@code ffprobe} ProcessBuilder.
 *
 * <p><b>How to run:</b> from the repo root,
 * {@code mvn -pl services/video-assembly-service test} (or plain
 * {@code mvn test} — the classes end in *Test, so Surefire's default includes
 * pick them up; no extra pom config needed). On a machine without ffmpeg,
 * install it (e.g. winget/choco/apt) or run the suite inside the service's
 * Docker image, which ships ffmpeg/ffprobe:
 * {@code docker run --rm -v "$PWD":/src -w /src maven:3-eclipse-temurin-21
 *  mvn -pl services/video-assembly-service test}
 * (any ffmpeg-equipped Maven/JDK21 container works).
 *
 * <p>All process invocations use plain ProcessBuilder argument lists — no
 * shell, no quoting — so they behave identically on Windows and Linux.
 */
public final class FfmpegIntegrationSupport {

    private FfmpegIntegrationSupport() {}

    /** Tiny canvas so encodes stay fast; mirrors production's landscape shape. */
    public static final int W = 320, H = 180, FPS = 15;

    public static final String SKIP_REASON =
            "ffmpeg/ffprobe not found on PATH — render integration tests skipped "
            + "(install ffmpeg or run inside the service's Docker image)";

    // ── Skip condition ──────────────────────────────────────────────────────

    private static volatile Boolean available;

    /** Referenced by {@code @EnabledIf} on each render test class. Cached so
     *  the probe runs once per JVM, not once per test. */
    public static boolean ffmpegAvailable() {
        Boolean a = available;
        if (a == null) {
            a = canRun("ffmpeg") && canRun("ffprobe");
            available = a;
        }
        return a;
    }

    private static boolean canRun(String binary) {
        try {
            Process p = new ProcessBuilder(binary, "-version")
                    .redirectErrorStream(true)
                    .start();
            p.getInputStream().readAllBytes();   // drain so it can't block
            if (!p.waitFor(10, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ── Production components, wired like production wires them ────────────

    /** Same record shape the app builds from application.yml, scaled down for
     *  test speed. binary/probeBinary = bare names on PATH, exactly like the
     *  production defaults ({@code ${FFMPEG_BIN:ffmpeg}}). */
    public static VideoProperties testProps(Path workRoot) {
        return new VideoProperties(
                new VideoProperties.Ffmpeg("ffmpeg", "ffprobe", 1, 2, 5),
                new VideoProperties.Storage(workRoot.toString()),
                new VideoProperties.Output(W, H, FPS, 19, 96));
    }

    /** Real FfmpegRunner. Its {@code maxConcurrent} @Value field would be 0
     *  outside Spring (gate() clamps to 1) — set it explicitly like the
     *  production default would. */
    public static FfmpegRunner runner(VideoProperties props) {
        FfmpegRunner r = new FfmpegRunner(props);
        ReflectionTestUtils.setField(r, "maxConcurrent", 2);
        return r;
    }

    // ── lavfi fixture generators (no binary fixtures in git) ───────────────

    /**
     * One uniform scene clip exactly like the intermediates the pipeline
     * feeds the Concatenator: h264 yuv420p video + 48kHz pcm_s16le audio in
     * MKV (mp4 can't carry PCM — see WorkspaceManager's mkv-intermediates
     * note). testsrc video + sine audio, {@code seconds} long.
     */
    public static Path sceneClip(Path out, double seconds) {
        ffmpeg(out.getParent(),
                "-y",
                "-f", "lavfi", "-i",
                "testsrc=duration=" + fmt(seconds) + ":size=" + W + "x" + H + ":rate=" + FPS,
                "-f", "lavfi", "-i",
                "sine=frequency=440:duration=" + fmt(seconds),
                "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "pcm_s16le", "-ar", "48000",
                "-shortest",
                out.toString());
        return out;
    }

    /** Single-frame test PNG, stand-in for a scene's generated image. */
    public static Path stillPng(Path out) {
        ffmpeg(out.getParent(),
                "-y",
                "-f", "lavfi", "-i", "testsrc=duration=0.2:size=" + W + "x" + H + ":rate=5",
                "-frames:v", "1",
                out.toString());
        return out;
    }

    /** Sine WAV of the given length — stand-in for a voice line or for the
     *  background-music bed (PCM WAV avoids depending on an mp3 encoder being
     *  compiled into the local ffmpeg; the mixer accepts any readable audio). */
    public static Path sineWav(Path out, double seconds, int frequencyHz) {
        ffmpeg(out.getParent(),
                "-y",
                "-f", "lavfi", "-i",
                "sine=frequency=" + frequencyHz + ":duration=" + fmt(seconds),
                "-c:a", "pcm_s16le", "-ar", "48000",
                out.toString());
        return out;
    }

    // ── Probes (independent of the code under test) ─────────────────────────

    /** Container duration in seconds via ffprobe. */
    public static double duration(Path file) {
        String out = capture(file.getParent(),
                "ffprobe", "-v", "error",
                "-show_entries", "format=duration",
                "-of", "default=noprint_wrappers=1:nokey=1",
                file.toString());
        return Double.parseDouble(out.trim());
    }

    /** @param streamType "v" or "a" */
    public static boolean hasStream(Path file, String streamType) {
        String out = capture(file.getParent(),
                "ffprobe", "-v", "error",
                "-select_streams", streamType,
                "-show_entries", "stream=codec_type",
                "-of", "csv=p=0",
                file.toString());
        return !out.isBlank();
    }

    // ── Plumbing ────────────────────────────────────────────────────────────

    public static String fmt(double v) {
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static void ffmpeg(Path workdir, String... args) {
        List<String> cmd = new ArrayList<>();
        cmd.add("ffmpeg");
        cmd.addAll(List.of(args));
        String out = run(cmd, workdir);
        if (out == null) {
            throw new IllegalStateException("fixture ffmpeg call failed: " + String.join(" ", cmd));
        }
    }

    private static String capture(Path workdir, String... cmd) {
        String out = run(List.of(cmd), workdir);
        if (out == null) {
            throw new IllegalStateException("probe failed: " + String.join(" ", cmd));
        }
        return out;
    }

    /** Runs a command, merged output captured; null on non-zero exit/timeout. */
    private static String run(List<String> cmd, Path workdir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true);
            if (workdir != null) pb.directory(workdir.toFile());
            Process p = pb.start();
            String output = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(120, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                return null;
            }
            if (p.exitValue() != 0) {
                // Surface the tail in the assertion failure that follows.
                System.err.println("[FfmpegIntegrationSupport] command failed ("
                        + p.exitValue() + "): " + String.join(" ", cmd)
                        + "\n" + tail(output));
                return null;
            }
            return output;
        } catch (Exception e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    private static String tail(String s) {
        String[] lines = s.split("\\R");
        int from = Math.max(0, lines.length - 25);
        return String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length));
    }
}
