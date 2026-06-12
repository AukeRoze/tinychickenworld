package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest.SceneInput;
import com.youtubeauto.video.config.VideoProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.youtubeauto.video.service.FfmpegIntegrationSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * REAL-render regression tests for {@link SceneClipBuilder#build} — actual
 * ffmpeg Ken Burns renders from a lavfi still + sine voice track. What this
 * class pins (the {@code effectiveDur} rules, observed through the rendered
 * clip's duration since the method is private):
 *
 * <ul>
 *   <li><b>Voice stretches the scene:</b> a scene scripted at 2s whose voice
 *       line runs 3s renders at {@code ceil(voice + 0.4s tail)} = 4s — a
 *       spoken line is never cut off mid-sentence.</li>
 *   <li><b>Runaway lines are capped:</b> the stretch is bounded at
 *       {@code scripted + 3s}; a 9s voice line on a 2s scene renders at 5s,
 *       not 10s — one bad TTS render can't blow up the timeline.</li>
 *   <li>Either way the clip carries both streams in the uniform intermediate
 *       spec (h264 yuv420p + 48kHz PCM in MKV) the Concatenator expects.</li>
 * </ul>
 *
 * Run: {@code mvn -pl services/video-assembly-service test} from the repo
 * root. Requires ffmpeg+ffprobe on PATH; otherwise the class is skipped
 * (see {@link FfmpegIntegrationSupport} — Docker with ffmpeg also works).
 * The ambient-FX overlays stay dormant unless this machine has /bible/fx
 * assets mounted, exactly like production without them.
 */
@EnabledIf(value = "com.youtubeauto.video.service.FfmpegIntegrationSupport#ffmpegAvailable",
           disabledReason = FfmpegIntegrationSupport.SKIP_REASON)
class SceneClipBuilderRenderTest {

    @TempDir
    Path tmp;

    private SceneInput scene(Path image, Path voice, int scriptedSeconds) {
        return new SceneInput(1, image.toString(), voice.toString(), scriptedSeconds,
                "test narration", null, null, null, null, null, null);
    }

    @Test
    void voiceLongerThanScripted_stretchesToVoicePlusTail() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("stretch"));
        Path image = stillPng(work.resolve("scene.png"));
        Path voice = sineWav(work.resolve("voice.wav"), 3.0, 440);

        VideoProperties props = testProps(work);
        SceneClipBuilder builder = new SceneClipBuilder(runner(props), props);

        Path out = builder.build(scene(image, voice, 2), MotionPreset.ZOOM_IN,
                W, H, work, work.resolve("scene_01.mkv"));

        assertTrue(Files.exists(out));
        assertTrue(hasStream(out, "v") && hasStream(out, "a"));
        // effectiveDur: max(scripted=2, ceil(3.0 + 0.4)) = 4s.
        assertEquals(4.0, duration(out), 0.3,
                "scene must stretch to the voice length + 0.4s tail, ceiled");
    }

    @Test
    void runawayVoice_isCappedAtScriptedPlusThree() throws Exception {
        Path work = Files.createDirectories(tmp.resolve("cap"));
        Path image = stillPng(work.resolve("scene.png"));
        Path voice = sineWav(work.resolve("voice.wav"), 9.0, 440);

        VideoProperties props = testProps(work);
        SceneClipBuilder builder = new SceneClipBuilder(runner(props), props);

        Path out = builder.build(scene(image, voice, 2), MotionPreset.ZOOM_IN,
                W, H, work, work.resolve("scene_01.mkv"));

        assertTrue(Files.exists(out));
        // effectiveDur: min(ceil(9.0 + 0.4) = 10, scripted + 3 = 5) = 5s.
        assertEquals(5.0, duration(out), 0.3,
                "voice stretch must be capped at scripted + 3s");
    }
}
