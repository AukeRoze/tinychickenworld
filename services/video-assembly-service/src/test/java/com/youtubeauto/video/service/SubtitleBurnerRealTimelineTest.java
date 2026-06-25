package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest.SceneInput;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Caption sync regression: cues must follow the REAL joined-video timeline
 * (probed durations minus the crossfade overlap at each cut), not the summed
 * nominal scene seconds that drifted later and later across an episode
 * (gebruikersfeedback 2026-06-25).
 */
class SubtitleBurnerRealTimelineTest {

    private static SceneInput scene(int seq, String narration) {
        return new SceneInput(seq, "img.png", null, 10, narration,
                null, null, null, null, null, null, null, null, null);
    }

    @Test
    void realTimeline_subtracts_crossfade_overlap(@TempDir Path tmp) throws Exception {
        SubtitleBurner sb = new SubtitleBurner(mock(FfmpegRunner.class));
        List<SceneInput> scenes = List.of(scene(1, "Scene one"), scene(2, "Scene two"),
                scene(3, "Scene three"));
        // Each 10s clip, cut with a 0.3s xfade → real starts drift EARLIER than
        // the nominal 0/10/20.
        double[] starts = {0.0, 9.7, 19.4};
        double[] durs = {10.0, 10.0, 10.0};
        Path srt = tmp.resolve("real.srt");

        sb.writeSrt(scenes, srt, /*introOffsetSec=*/ 0.0, starts, durs);
        String out = Files.readString(srt);

        // Scene 2 starts at 9.700, scene 3 at 19.400 — overlap honoured.
        assertThat(out).contains("00:00:09,700 -->");
        assertThat(out).contains("00:00:19,400 -->");
        // And NOT the old nominal positions.
        assertThat(out).doesNotContain("00:00:10,000 -->");
        assertThat(out).doesNotContain("00:00:20,000 -->");
    }

    @Test
    void fractionalIntroOffset_shiftsEveryCue(@TempDir Path tmp) throws Exception {
        SubtitleBurner sb = new SubtitleBurner(mock(FfmpegRunner.class));
        List<SceneInput> scenes = List.of(scene(1, "Hello"));
        Path srt = tmp.resolve("intro.srt");

        // 5.4s intro must shift the cue by 5.4s, not a rounded 5s.
        sb.writeSrt(scenes, srt, /*introOffsetSec=*/ 5.4, new double[]{0.0}, new double[]{10.0});
        String out = Files.readString(srt);

        assertThat(out).contains("00:00:05,400 -->");
    }

    @Test
    void fallsBackToNominalWhenTimelineMissing(@TempDir Path tmp) throws Exception {
        SubtitleBurner sb = new SubtitleBurner(mock(FfmpegRunner.class));
        List<SceneInput> scenes = List.of(scene(1, "One"), scene(2, "Two"));
        Path srt = tmp.resolve("nominal.srt");

        // Null arrays → old behaviour: 0 and 10s on whole seconds.
        sb.writeSrt(scenes, srt, /*introOffsetSec=*/ 0.0, null, null);
        String out = Files.readString(srt);

        assertThat(out).contains("00:00:10,000 -->");
    }
}
