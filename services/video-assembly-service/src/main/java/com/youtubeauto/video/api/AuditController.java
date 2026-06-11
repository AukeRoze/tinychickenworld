package com.youtubeauto.video.api;

import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import com.youtubeauto.video.service.MediaProbe;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Pulls evenly-spaced keyframes from a finished master MP4. Used by the
 * orchestrator's QualityReviewer (Claude vision) to audit the video.
 *
 * Frames are written under {workRoot}/{jobId}/audit/ so the orchestrator
 * (sharing the workdir mount) can read them as base64 and ship to Claude.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
public class AuditController {

    private final FfmpegRunner ffmpeg;
    private final MediaProbe probe;
    private final VideoProperties props;

    public record KeyframesRequest(UUID jobId, String videoPath, Integer count) {}
    public record KeyframesResponse(double durationSeconds, List<Frame> frames) {}
    public record Frame(double t, String path) {}

    public record RenderChecksRequest(UUID jobId, String videoPath) {}

    /** videoPath comes over HTTP — lock it inside the shared workdir (and the
     *  read-only bible, for intro/outro checks) so this endpoint can't be used
     *  to point ffmpeg at arbitrary files. */
    private Path safeVideoPath(String videoPath) {
        Path p = Paths.get(videoPath).normalize();
        Path workRoot = Paths.get(props.storage().workRoot()).normalize();
        if (p.startsWith(workRoot) || p.startsWith(Paths.get("/bible"))) return p;
        throw new IllegalArgumentException("videoPath must live under " + workRoot + " (was: " + p + ")");
    }
    public record Span(double start, double end) {}
    public record RenderChecksResponse(double durationSeconds,
                                       List<Span> silences,
                                       List<Span> blackouts) {}

    /**
     * Deterministic render checks on a finished master: exact duration,
     * dead-air detection (silencedetect, &lt;-45dB for ≥1.5s) and black-frame
     * detection (blackdetect, ≥0.5s of ≥90% black). One ffmpeg decode pass.
     * The orchestrator turns these into audit findings / score caps — a 138s
     * master on a 180s format or a black hole mid-video must never ship
     * unflagged.
     */
    @PostMapping("/render-checks")
    public ResponseEntity<RenderChecksResponse> renderChecks(@RequestBody RenderChecksRequest req) throws Exception {
        Path video = safeVideoPath(req.videoPath());
        if (!Files.exists(video)) {
            log.warn("Render checks: video missing at {}", video);
            return ResponseEntity.badRequest().build();
        }
        Path workdir = Paths.get(props.storage().workRoot(), req.jobId().toString());
        Files.createDirectories(workdir);
        double duration = probe.probe(video, workdir).durationSeconds();

        String out = ffmpeg.runFfmpegCaptured(List.of(
                "-hide_banner", "-nostats",
                "-i", video.toString(),
                "-af", "silencedetect=noise=-45dB:d=1.5",
                "-vf", "blackdetect=d=0.5:pic_th=0.90:pix_th=0.10",
                "-f", "null", "-"
        ), workdir);

        List<Span> silences = new ArrayList<>();
        java.util.regex.Matcher sm = java.util.regex.Pattern
                .compile("silence_start: ([0-9.]+)[\\s\\S]*?silence_end: ([0-9.]+)").matcher(out);
        while (sm.find()) {
            silences.add(new Span(Double.parseDouble(sm.group(1)), Double.parseDouble(sm.group(2))));
        }
        List<Span> blackouts = new ArrayList<>();
        java.util.regex.Matcher bm = java.util.regex.Pattern
                .compile("black_start:([0-9.]+) black_end:([0-9.]+)").matcher(out);
        while (bm.find()) {
            blackouts.add(new Span(Double.parseDouble(bm.group(1)), Double.parseDouble(bm.group(2))));
        }
        log.info("Render checks job {}: dur={}s silences={} blackouts={}",
                req.jobId(), duration, silences.size(), blackouts.size());
        return ResponseEntity.ok(new RenderChecksResponse(duration, silences, blackouts));
    }

    @PostMapping("/keyframes")
    public ResponseEntity<KeyframesResponse> keyframes(@RequestBody KeyframesRequest req) throws Exception {
        Path video = safeVideoPath(req.videoPath());
        if (!Files.exists(video)) {
            log.warn("Audit: video missing at {}", video);
            return ResponseEntity.badRequest().build();
        }
        int count = req.count() == null ? 8 : Math.max(4, Math.min(req.count(), 16));

        Path workdir = Paths.get(props.storage().workRoot(), req.jobId().toString());
        Files.createDirectories(workdir);
        MediaProbe.Info info = probe.probe(video, workdir);
        double duration = info.durationSeconds();

        Path outDir = workdir.resolve("audit");
        Files.createDirectories(outDir);

        List<Frame> frames = new ArrayList<>();
        // Spread evenly, skipping the very first/last 5% (intro/outro brand frames).
        double start = duration * 0.05;
        double end   = duration * 0.95;
        for (int i = 0; i < count; i++) {
            double t = start + (end - start) * i / Math.max(1, count - 1);
            Path frame = outDir.resolve(String.format("frame_%02d.png", i));
            ffmpeg.runFfmpeg(List.of(
                    "-ss", String.format("%.3f", t),
                    "-i", video.toString(),
                    "-vframes", "1",
                    "-q:v", "2",
                    "-y", frame.toString()
            ), workdir);
            if (Files.exists(frame)) frames.add(new Frame(t, frame.toString()));
        }
        log.info("Audit keyframes for job {}: extracted {} frames from {}s master",
                req.jobId(), frames.size(), duration);
        return ResponseEntity.ok(new KeyframesResponse(duration, frames));
    }
}
