package com.youtubeauto.video.service;

import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.youtubeauto.video.service.FfmpegIntegrationSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL-render regression tests for {@link Concatenator} — actual ffmpeg
 * processes, lavfi-generated inputs, no mocks. What this class pins:
 *
 * <ul>
 *   <li><b>Happy path:</b> N clips xfade-concat into one output whose duration
 *       is the sum of the inputs minus the phase-mapped crossfade overlaps,
 *       with both a video and an audio stream present.</li>
 *   <li><b>Proactive chunk-guard (board 2026-06-12):</b> when
 *       {@code clips > app.assembly.concat-chunk-size} the monolithic
 *       all-inputs filtergraph is never attempted — the chunked path runs
 *       directly (observable via its {@code xfade_chunks/} pass directory) and
 *       produces the same duration as the monolithic graph does for the same
 *       clips.</li>
 *   <li><b>Broken INPUT behaviour (honest, not wishful):</b> the resilience
 *       ladder (full → chunked → bare → stream-copy) rescues failing
 *       FILTERGRAPHS/encodes, not unreadable inputs. Durations are probed
 *       BEFORE any graph is built, so an unreadable clip fails fast with
 *       {@code IllegalStateException("ffprobe failed …")} from
 *       {@code probeDuration} — the ladder never engages and no output is
 *       written. This test pins that contract.</li>
 * </ul>
 *
 * <p>Title card and end card are forced to "none" here ON PURPOSE: their
 * drawtext filters hardcode the container font path
 * {@code /usr/share/fonts/truetype/dejavu/…}, which only exists inside the
 * service's Docker image. With them enabled, a dev-machine run would silently
 * fail level 1 and pin the fallback instead of the primary graph. The color
 * grade (font-free) stays ON in the happy path so the full cosmetic video
 * chain is exercised. Logo/whoosh/bell stay dormant unless this machine has
 * the {@code /bible} assets mounted — like production without them.
 *
 * <p>Run: {@code mvn -pl services/video-assembly-service test} from the repo
 * root. Requires ffmpeg+ffprobe on PATH (production default config); without
 * them the whole class is skipped, see {@link FfmpegIntegrationSupport}.
 * Docker alternative: run the build inside any ffmpeg-equipped JDK21/Maven
 * image — the service's own image ships ffmpeg.
 */
@EnabledIf(value = "com.youtubeauto.video.service.FfmpegIntegrationSupport#ffmpegAvailable",
           disabledReason = FfmpegIntegrationSupport.SKIP_REASON)
class ConcatenatorRenderTest {

    @TempDir
    Path tmp;

    /** Real Concatenator, constructed like production (constructor injection)
     *  with its @Value fields set the way Spring would — minus the two
     *  container-font-dependent overlays (see class javadoc). */
    private Concatenator concatenator(FfmpegRunner runner, VideoProperties props,
                                      int chunkSize, String colorGrade) {
        TransitionConfig tc = new TransitionConfig();
        // Point the bible at a non-existent file → built-in transition mapping,
        // deterministic regardless of what /bible/channel.yml on this machine says.
        ReflectionTestUtils.setField(tc, "biblePath",
                tmp.resolve("no-bible.yml").toString());
        Concatenator c = new Concatenator(runner, props, tc);
        ReflectionTestUtils.setField(c, "colorGrade", colorGrade);
        ReflectionTestUtils.setField(c, "titleCard", "none");
        ReflectionTestUtils.setField(c, "endCard", "none");
        ReflectionTestUtils.setField(c, "concatChunkSize", chunkSize);
        return c;
    }

    @Test
    void concatThreeClips_durationIsSumMinusCrossfades_withVideoAndAudio() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("happy"));
        List<Path> clips = List.of(
                sceneClip(work.resolve("clip_0.mkv"), 2.0),
                sceneClip(work.resolve("clip_1.mkv"), 2.0),
                sceneClip(work.resolve("clip_2.mkv"), 2.0));

        VideoProperties props = testProps(work);
        Concatenator c = concatenator(runner(props), props, 99, "warm-pixar");

        // Phases drive the built-in transition mapping: the INCOMING scene's
        // phase picks the xfade. development → 0.15s, climax → 0.30s.
        List<String> phases = Arrays.asList("hook", "development", "climax");
        Path out = c.concat(clips, phases, work.resolve("concat.txt"),
                work.resolve("joined.mkv"), work, null);

        assertTrue(Files.exists(out), "concat output must exist");
        assertTrue(hasStream(out, "v"), "output must have a video stream");
        assertTrue(hasStream(out, "a"), "output must have an audio stream");

        double expected = duration(clips.get(0)) + duration(clips.get(1))
                + duration(clips.get(2)) - 0.15 - 0.30;
        assertEquals(expected, duration(out), 0.5,
                "duration must be the input sum minus the two crossfade overlaps "
                + "(small slack for the J/L-cut audio pad + container rounding)");
    }

    @Test
    void chunkGuard_chunkedAndMonolithicAgreeOnDuration() throws Exception {
        // Same 4 source clips for both runs.
        Path src = Files.createDirectories(tmp.resolve("src"));
        List<Path> clips = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            clips.add(sceneClip(src.resolve("clip_" + i + ".mkv"), 2.0));
        }

        // Run 1 — chunk-size 2 < 4 clips → the proactive guard must route to
        // the CHUNKED path without ever attempting the monolithic graph.
        Path workChunked = Files.createDirectories(tmp.resolve("chunked"));
        VideoProperties propsC = testProps(workChunked);
        Concatenator chunked = concatenator(runner(propsC), propsC, 2, "none");
        Path outChunked = chunked.concat(clips, null, workChunked.resolve("concat.txt"),
                workChunked.resolve("joined.mkv"), workChunked, null);

        // Run 2 — chunk-size 99 ≥ 4 clips → monolithic single graph.
        Path workMono = Files.createDirectories(tmp.resolve("mono"));
        VideoProperties propsM = testProps(workMono);
        Concatenator mono = concatenator(runner(propsM), propsM, 99, "none");
        Path outMono = mono.concat(clips, null, workMono.resolve("concat.txt"),
                workMono.resolve("joined.mkv"), workMono, null);

        assertTrue(Files.exists(outChunked) && Files.exists(outMono));

        // The chunked path is observable: it writes its pass files into
        // workdir/xfade_chunks. The monolithic run must NOT have created it —
        // if it did, level 1 failed on this machine and we'd be comparing the
        // fallback against itself.
        assertTrue(Files.isDirectory(workChunked.resolve("xfade_chunks")),
                "chunk-size 2 with 4 clips must take the chunked path (proactive guard)");
        assertFalse(Files.exists(workMono.resolve("xfade_chunks")),
                "chunk-size 99 must stay monolithic (no chunked fallback engaged)");

        double dChunked = duration(outChunked);
        double dMono = duration(outMono);
        assertEquals(dMono, dChunked, 0.5,
                "chunked concat must match the monolithic graph's duration");

        // Both within tolerance of the analytic expectation:
        // 4×2s with three default 0.20s crossfades (null phases) ≈ 7.4s.
        double expected = clips.stream().mapToDouble(FfmpegIntegrationSupport::duration).sum()
                - 3 * 0.20;
        assertEquals(expected, dChunked, 0.5);
        assertEquals(expected, dMono, 0.5);

        assertTrue(hasStream(outChunked, "v") && hasStream(outChunked, "a"));
        assertTrue(hasStream(outMono, "v") && hasStream(outMono, "a"));
    }

    @Test
    void unreadableClip_failsFastInProbe_ladderNeverEngages() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("broken"));
        Path broken = Files.createFile(work.resolve("clip_1.mkv"));   // 0 bytes
        List<Path> clips = List.of(
                sceneClip(work.resolve("clip_0.mkv"), 2.0),
                broken,
                sceneClip(work.resolve("clip_2.mkv"), 2.0));

        VideoProperties props = testProps(work);
        Concatenator c = concatenator(runner(props), props, 2, "none");

        Path out = work.resolve("joined.mkv");
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> c.concat(clips, null, work.resolve("concat.txt"), out, work, null),
                "an unreadable INPUT must fail the upfront duration probe — "
                + "the fallback ladder only rescues graph/encode failures");
        assertTrue(ex.getMessage().contains("ffprobe failed"),
                "failure must come from probeDuration, got: " + ex.getMessage());

        assertFalse(Files.exists(out), "no output may be written for a broken input");
        assertFalse(Files.exists(work.resolve("xfade_chunks")),
                "the chunked ladder level must never have started");
    }
}
