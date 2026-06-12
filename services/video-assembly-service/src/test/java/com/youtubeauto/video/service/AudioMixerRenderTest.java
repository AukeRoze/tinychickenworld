package com.youtubeauto.video.service;

import com.youtubeauto.video.config.VideoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.youtubeauto.video.service.FfmpegIntegrationSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL-render regression tests for {@link AudioMixer#mixBackgroundMusic} —
 * actual ffmpeg, lavfi-generated video + music, no mocks. What this class
 * pins:
 *
 * <ul>
 *   <li><b>Score-plan + silence-dip mix:</b> the full 9-arg signature
 *       (video, bgm, output, workdir, swellCenter, swellSpread, dipStart,
 *       dipEnd, scorePlan) renders: a sane 2-segment plan plus a dip window
 *       produces an output of the SAME duration as the input video
 *       ({@code amix duration=first} + {@code -shortest}), with video stream
 *       copied and an audio stream present. The dip depth comes from the
 *       {@code app.assembly.climax-dip-db} @Value field (production default
 *       -12), set here via ReflectionTestUtils because the component is
 *       constructed outside Spring.</li>
 *   <li><b>Nonsense plan never sinks the render:</b> the envelopes are
 *       cosmetic by contract. A plan that {@code planActive} rejects (here:
 *       a non-finite gain — same guard that catches out-of-order or
 *       degenerate segments) must yield a successful FLAT mix. And even if
 *       the guard ever regressed and the bad expression reached ffmpeg, the
 *       catch-and-retry-flat in {@code mixBackgroundMusic} is required to
 *       ship the mix anyway — either way this test demands a valid output.</li>
 * </ul>
 *
 * Run: {@code mvn -pl services/video-assembly-service test} from the repo
 * root. Requires ffmpeg+ffprobe on PATH; otherwise the class is skipped
 * (see {@link FfmpegIntegrationSupport} — Docker with ffmpeg also works).
 */
@EnabledIf(value = "com.youtubeauto.video.service.FfmpegIntegrationSupport#ffmpegAvailable",
           disabledReason = FfmpegIntegrationSupport.SKIP_REASON)
class AudioMixerRenderTest {

    @TempDir
    Path tmp;

    /** Real AudioMixer; climaxDipDb mirrors the production default (-12 dB).
     *  Without this the field would be 0.0 and dipActive() would always be
     *  false — the dip window under test would silently be a no-op. */
    private AudioMixer mixer(VideoProperties props) {
        AudioMixer m = new AudioMixer(runner(props));
        ReflectionTestUtils.setField(m, "climaxDipDb", -12.0);
        return m;
    }

    @Test
    void scorePlanWithDip_keepsDuration_andProducesAudio() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("planmix"));
        Path video = sceneClip(work.resolve("video.mkv"), 10.0);
        Path music = sineWav(work.resolve("music.wav"), 12.0, 330);

        // 2-segment beat-sheet arc (quiet first half, leaning in after) plus a
        // held-silence dip window in the middle — the exact shape
        // AssemblyService.scorePlan/musicDipWindow hand to the mixer.
        List<AudioMixer.ScoreSegment> plan = List.of(
                new AudioMixer.ScoreSegment(0.0, 5.0, -2.0),
                new AudioMixer.ScoreSegment(5.0, 10.0, 1.5));

        VideoProperties props = testProps(work);
        Path out = mixer(props).mixBackgroundMusic(
                video, music.toString(), work.resolve("withmusic.mkv"), work,
                0, 0,          // no swell — plan + dip are the envelopes under test
                4.0, 6.0,      // dip window
                plan);

        assertTrue(Files.exists(out), "music mix output must exist");
        assertTrue(hasStream(out, "v"), "video stream must be copied through");
        assertTrue(hasStream(out, "a"), "mixed audio stream must be present");
        assertEquals(duration(video), duration(out), 0.2,
                "mix must not change the video duration (duration=first + -shortest)");
    }

    @Test
    void nonsensePlan_isRejectedByGuards_andMixShipsFlat() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("flatretry"));
        Path video = sceneClip(work.resolve("video.mkv"), 10.0);
        Path music = sineWav(work.resolve("music.wav"), 12.0, 330);

        // Non-finite gain — planActive() must drop the WHOLE plan (one broken
        // segment poisons the arc) so the bed stays flat. NaN can't slip
        // through to the ffmpeg volume expression.
        List<AudioMixer.ScoreSegment> nonsense = List.of(
                new AudioMixer.ScoreSegment(0.0, 5.0, Double.NaN),
                new AudioMixer.ScoreSegment(5.0, 10.0, 2.0));

        VideoProperties props = testProps(work);
        Path out = assertDoesNotThrow(() -> mixer(props).mixBackgroundMusic(
                        video, music.toString(), work.resolve("withmusic.mkv"), work,
                        0, 0, 0, 0, nonsense),
                "a nonsense score plan must never fail the mix — flat bed instead");

        assertTrue(Files.exists(out));
        assertTrue(hasStream(out, "a"));
        assertEquals(duration(video), duration(out), 0.2);
    }
}
