package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Calculates a "polish completeness" score per video. Returns:
 *   - score 0-100 (weighted sum)
 *   - per-check list of boolean status + label
 *
 * Used by the dashboard detail page to give the reviewer a quick visual
 * sense of "is this video complete enough to ship". Higher = more polished.
 */
@Component
@RequiredArgsConstructor
public class QualityScorer {

    public record Check(String label, boolean ok, int weight, String detail) {}
    public record Result(int score, List<Check> checks) {}

    public Result evaluate(VideoJob job) {
        List<Check> checks = new ArrayList<>();
        Path workdir = Paths.get("/workdir", job.getId().toString());

        addCheck(checks, "Script generated",
                job.getScriptJobId() != null, 10,
                job.getScriptJobId() == null ? "Script not yet generated" : "ID: " + job.getScriptJobId());

        // Story structure — the deterministic beat-sheet score from the script
        // stage. Counts as "ok" at >=80 so weak arc/timing is visible BEFORE
        // upload instead of only after a full render. Unknown (null) = not ok.
        Integer struct = job.getStructureScore();
        addCheck(checks, "Story structure validated",
                struct != null && struct >= 80, 8,
                struct == null ? "No structure score" : "Beat-sheet score " + struct + "/100");

        // Story-critic verdict — qualitative arc/re-hook/ending/language score.
        // Optional (critic can be disabled); only counts when present and >=70.
        Integer crit = job.getCriticScore();
        addCheck(checks, "Story-critic passed",
                crit != null && crit >= 70, 6,
                crit == null ? "Critic disabled / no score" : "Critic score " + crit + "/100");

        addCheck(checks, "Images on disk",
                Files.exists(workdir.resolve("images")), 10,
                "Per-scene PNGs");

        addCheck(checks, "Audio per scene",
                Files.exists(workdir.resolve("audio")), 8,
                "Voice / SFX / ambient mixed");

        addCheck(checks, "Music selected",
                job.getBackgroundMusicPath() != null && !job.getBackgroundMusicPath().isBlank(), 6,
                "Background track");

        addCheck(checks, "Ambient sounds library",
                Files.isDirectory(Paths.get("/bible/sfx/ambient")), 4,
                "/bible/sfx/ambient/*.mp3");

        addCheck(checks, "Logo file",
                Files.exists(Paths.get("/bible/logo.png")), 5,
                "/bible/logo.png");

        addCheck(checks, "Brand audio sting",
                Files.exists(Paths.get("/bible/sting.mp3")), 4,
                "/bible/sting.mp3 — opening jingle");

        addCheck(checks, "Thumbnail variants (3)",
                Files.exists(workdir.resolve("thumbnail/thumbnail-3.png")), 8,
                "3 A/B variants generated");

        addCheck(checks, "Thumbnail picked",
                job.getThumbnailPath() != null, 4,
                "Primary thumbnail set");

        addCheck(checks, "Metadata + chapters",
                job.getMetadataDescription() != null
                        && job.getMetadataDescription().contains("Chapters:"), 8,
                "YouTube chapter markers in description");

        addCheck(checks, "Master video assembled",
                job.getVideoPath() != null
                        && job.getVideoPath() != null
                        && Files.exists(Paths.get(job.getVideoPath() == null ? "" : job.getVideoPath())), 10,
                "Final MP4 on disk");

        // Captions present either as a soft YouTube track (preferred — clean
        // image) or burned in. Either counts.
        boolean hasCaptions = (job.getCaptionsPath() != null && !job.getCaptionsPath().isBlank())
                || Boolean.TRUE.equals(job.getBurnSubtitles());
        addCheck(checks, "Closed captions",
                hasCaptions, 5,
                job.getCaptionsPath() != null && !job.getCaptionsPath().isBlank()
                        ? "Soft YouTube caption track (.srt)"
                        : "burnSubtitles flag");

        addCheck(checks, "Series + episode tagged",
                job.getSeriesId() != null && job.getEpisodeNumber() != null, 6,
                "Planning fields filled");

        addCheck(checks, "Planned publish date",
                job.getPlannedPublishAt() != null, 6,
                "Scheduled or planned");

        addCheck(checks, "YouTube uploaded",
                job.getYoutubeVideoId() != null, 6,
                job.getYoutubeUrl());

        int got = 0, max = 0;
        for (Check c : checks) {
            max += c.weight;
            if (c.ok) got += c.weight;
        }
        int score = max == 0 ? 0 : Math.round(100f * got / max);
        // Failed jobs cap at the work they actually finished.
        if (job.getStatus() == JobStatus.FAILED) {
            score = Math.min(score, 50);
        }
        return new Result(score, checks);
    }

    private void addCheck(List<Check> out, String label, boolean ok, int weight, String detail) {
        out.add(new Check(label, ok, weight, detail));
    }
}
