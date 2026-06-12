package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.api.dto.CreateVideoRequest;
import com.youtubeauto.orchestrator.api.dto.JobSummary;
import com.youtubeauto.orchestrator.api.dto.SceneSummary;
import com.youtubeauto.orchestrator.api.dto.VideoJobResponse;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.review.CostEstimator;
import com.youtubeauto.orchestrator.service.PipelineOrchestrator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/videos")
@RequiredArgsConstructor
public class VideoController {

    private final PipelineOrchestrator orchestrator;
    private final VideoJobRepository jobRepo;
    private final ObjectMapper mapper;
    private final CostEstimator costEstimator;
    private final com.youtubeauto.orchestrator.repository.QcFindingRepository qcFindingRepo;

    /** Per-scene QC findings for the job page — shows WHICH scenes the vision-QC
     *  flagged (and from which source: scene-qc, clip-qc, auto-fix, …). */
    @GetMapping("/{id}/qc-findings")
    public List<Map<String, Object>> qcFindings(@PathVariable UUID id) {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            for (var f : qcFindingRepo.findByVideoJobId(id)) {
                Map<String, Object> m = new HashMap<>();
                m.put("seq", f.getSeq());
                m.put("source", f.getSource());
                m.put("category", f.getCategory());
                m.put("issue", f.getIssue());
                out.add(m);
            }
        } catch (Exception ignore) { /* findings are informative only */ }
        return out;
    }

    /** Combined review payload for the static detail page: the stored QA-board
     *  blob (axes + verdict) plus a fresh cost estimate. */
    @GetMapping("/{id}/review")
    public ResponseEntity<Map<String, Object>> review(@PathVariable UUID id) {
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        Map<String, Object> out = new HashMap<>();
        JsonNode qa = null;
        if (job.getQaBoardJson() != null && !job.getQaBoardJson().isBlank()) {
            try { qa = mapper.readTree(job.getQaBoardJson()); } catch (Exception ignore) {}
        }
        out.put("qaBoard", qa);
        try {
            CostEstimator.Result c = costEstimator.estimate(job);
            out.put("cost", Map.of("estimateEur", c.estimateEur(), "capEur", c.capEur()));
        } catch (Exception e) {
            out.put("cost", null);
        }

        // Metadata (editable via PATCH /metadata).
        Map<String, Object> meta = new HashMap<>();
        meta.put("title", nz(job.getMetadataTitle()));
        meta.put("description", nz(job.getMetadataDescription()));
        meta.put("tags", nz(job.getMetadataTags()));
        out.put("metadata", meta);

        // Planning (read-only display).
        Map<String, Object> planning = new HashMap<>();
        planning.put("seriesId", job.getSeriesId());
        planning.put("episodeNumber", job.getEpisodeNumber());
        planning.put("plannedPublishAt",
                job.getPlannedPublishAt() == null ? null : job.getPlannedPublishAt().toString());
        planning.put("youtubeUrl", job.getYoutubeUrl());
        out.put("planning", planning);

        // Self-learning loop data: per-scene retention (filled by the
        // AnalyticsPoller once YouTube data lands) + the arc/layout this
        // episode used, so the dashboard shows WHAT the loop learns from.
        try {
            out.put("retentionScenes",
                    job.getRetentionScenesJson() == null || job.getRetentionScenesJson().isBlank()
                            ? null : mapper.readTree(job.getRetentionScenesJson()));
        } catch (Exception e) {
            out.put("retentionScenes", null);
        }
        out.put("storyArc", job.getStoryArc());
        out.put("thumbnailLayout", job.getThumbnailLayout());

        // Media: master video presence + available thumbnail variant indices.
        List<Integer> variants = new ArrayList<>();
        for (int v = 0; v < 8; v++) {
            if (java.nio.file.Files.exists(java.nio.file.Paths.get(
                    "/workdir", id.toString(), "thumbnail", "thumbnail-" + v + ".png"))) {
                variants.add(v);
            }
        }
        Map<String, Object> media = new HashMap<>();
        media.put("hasVideo", job.getVideoPath() != null && !job.getVideoPath().isBlank());
        media.put("thumbnailVariants", variants);
        media.put("hasShort", job.getShortPath() != null && !job.getShortPath().isBlank()
                && java.nio.file.Files.exists(java.nio.file.Paths.get(job.getShortPath())));
        out.put("media", media);

        // Production metrics (Veo cost, duration stretch) — filled per stage.
        try {
            out.put("metrics", job.getMetricsJson() == null || job.getMetricsJson().isBlank()
                    ? null : mapper.readTree(job.getMetricsJson()));
        } catch (Exception e) {
            out.put("metrics", null);
        }

        return ResponseEntity.ok(out);
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** JSON jobs list for the static dashboard (/ui/). Compact summary, newest first. */
    @GetMapping
    public List<JobSummary> list() {
        return jobRepo.findAll().stream()
                .sorted(Comparator.comparing(VideoJob::getCreatedAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .map(j -> new JobSummary(
                        j.getId(),
                        j.getTopic(),
                        j.getStatus() == null ? null : j.getStatus().name(),
                        j.getCreatedAt() == null ? null : j.getCreatedAt().toString(),
                        j.getPlannedPublishAt() == null ? null : j.getPlannedPublishAt().toString(),
                        j.getSeriesId(),
                        j.getEpisodeNumber(),
                        j.getQaBoardScore(),
                        j.getFormat()))
                .toList();
    }

    /** Per-scene summary for the static job-detail grid. Parsed from the job's
     *  stored assembly scenes; empty list when assets aren't generated yet. */
    @GetMapping("/{id}/scenes")
    public List<SceneSummary> scenes(@PathVariable UUID id) {
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job == null || job.getAssemblyScenesJson() == null || job.getAssemblyScenesJson().isBlank()) {
            return List.of();
        }
        java.util.Set<Integer> locked = new java.util.HashSet<>();
        String csv = job.getLockedSceneSeqs();
        if (csv != null && !csv.isBlank()) {
            for (String part : csv.split(",")) {
                try { locked.add(Integer.parseInt(part.trim())); } catch (Exception ignore) {}
            }
        }
        List<SceneSummary> out = new ArrayList<>();
        try {
            for (JsonNode s : mapper.readTree(job.getAssemblyScenesJson())) {
                int seq = s.path("seq").asInt();
                String narration = s.path("narration").asText("");
                List<SceneSummary.Line> lines = new ArrayList<>();
                for (JsonNode l : s.path("lines")) {
                    lines.add(new SceneSummary.Line(
                            l.path("speaker").asText(""), l.path("text").asText("")));
                }
                // Silent beat = no dialogue AND no narration — decide BEFORE the
                // display-fallback below masks the emptiness.
                boolean silentBeat = lines.isEmpty() && narration.isBlank();
                if (narration.isBlank()) narration = s.path("visualDesc").asText("");
                // Directed end-still (hero scenes only): generateEndStills writes it
                // as scene_{900+seq}.png. Surface it so the UI can show start → end.
                int endStillSeq = 900 + seq;
                boolean hasEndStill = java.nio.file.Files.exists(java.nio.file.Paths.get(
                        "/workdir", id.toString(), "images",
                        String.format("scene_%02d.png", endStillSeq)));
                // Still's last-modified millis → a ?v= cache token so a regenerated
                // image refreshes in the UI (and only when it actually changed).
                long imageVersion = stillMtime(id, seq);
                out.add(new SceneSummary(
                        seq,
                        s.path("durationSeconds").asInt(0),
                        s.path("phase").asText(""),
                        narration,
                        !s.path("clipPath").asText("").isBlank(),
                        locked.contains(seq),
                        s.path("visualDesc").asText(""),
                        lines,
                        hasEndStill,
                        hasEndStill ? endStillSeq : -1,
                        imageVersion,
                        silentBeat));
            }
        } catch (Exception e) {
            return List.of();
        }
        return out;
    }

    /** Last-modified millis of a scene still (0 if absent) — a cache-busting token. */
    private long stillMtime(UUID id, int seq) {
        java.nio.file.Path p = java.nio.file.Paths.get(
                "/workdir", id.toString(), "images", String.format("scene_%02d.png", seq));
        try {
            return java.nio.file.Files.exists(p)
                    ? java.nio.file.Files.getLastModifiedTime(p).toMillis() : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /** Pick a different background-music track for this job (UI select on the
     *  job page). Body: {"trackId": "cloud_watching"}. Takes effect on the
     *  next Reassemble — the original auto-pick is stored on the job and
     *  would otherwise be reused forever (which is how a rain episode ended
     *  up with sunny_adventure after a library hiccup). */
    @PostMapping("/{id}/music")
    public ResponseEntity<?> music(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        String trackId = body.get("trackId");
        if (trackId == null || !trackId.matches("[A-Za-z0-9_-]+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid trackId"));
        }
        java.nio.file.Path f = java.nio.file.Paths.get("/bible", "music", trackId + ".mp3");
        if (!java.nio.file.Files.exists(f)) {
            return ResponseEntity.badRequest().body(Map.of("error", "track not found: " + trackId));
        }
        orchestrator.saveBackgroundMusicPath(id, f.toString());
        return ResponseEntity.ok(Map.of("backgroundMusicPath", f.toString(),
                "note", "run Reassemble to apply"));
    }

    /** Set or clear the planned publish moment from the UI (upload-review
     *  gate / planning). Body: {"plannedPublishAt": "2026-06-12T07:00:00Z"}
     *  or null/blank to clear (= publish immediately on approve). Refused
     *  once the video is already on YouTube. */
    @PostMapping("/{id}/planning")
    public ResponseEntity<?> planning(@PathVariable UUID id,
                                      @RequestBody Map<String, String> body) {
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        if (job.getYoutubeVideoId() != null && !job.getYoutubeVideoId().isBlank()) {
            return ResponseEntity.status(409).body(Map.of(
                    "error", "already uploaded — change the schedule in YouTube Studio"));
        }
        String iso = body.get("plannedPublishAt");
        try {
            orchestrator.savePlannedPublishAt(id,
                    (iso == null || iso.isBlank()) ? null : java.time.OffsetDateTime.parse(iso));
            return ResponseEntity.ok(Map.of("plannedPublishAt", iso == null ? "" : iso));
        } catch (java.time.format.DateTimeParseException e) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid ISO-8601 datetime"));
        }
    }

    @PostMapping
    public ResponseEntity<VideoJobResponse> create(@Valid @RequestBody CreateVideoRequest req) {
        VideoJobResponse r = orchestrator.submit(req);
        return ResponseEntity.created(URI.create("/api/v1/videos/" + r.id())).body(r);
    }

    @GetMapping("/{id}")
    public ResponseEntity<VideoJobResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(orchestrator.get(id));
    }

    /** Clone this job's creative settings into a fresh job (a new variant). */
    @PostMapping("/{id}/clone")
    public ResponseEntity<VideoJobResponse> clone(@PathVariable UUID id) {
        VideoJobResponse r = orchestrator.cloneJob(id);
        return ResponseEntity.created(URI.create("/api/v1/videos/" + r.id())).body(r);
    }

    /**
     * Retry a FAILED job, resuming from the stage that failed. Already-completed
     * work (script, scene images, voice, master) is reused — no re-generation.
     * POST only — the old GET variant was a link-preview hazard; one-click
     * email links now use the signed-token flow (/api/v1/review/confirm).
     */
    @PostMapping("/{id}/retry")
    public ResponseEntity<VideoJobResponse> retry(@PathVariable UUID id) {
        orchestrator.retry(id);
        return ResponseEntity.ok(orchestrator.get(id));
    }

    /**
     * Re-assemble the video from the SAME script, images and voice — nothing is
     * regenerated. Use to apply assembly/outro/thumbnail changes to an existing
     * video at no content-generation cost.
     */
    @PostMapping("/{id}/reassemble")
    public ResponseEntity<VideoJobResponse> reassemble(@PathVariable UUID id) {
        orchestrator.reassemble(id);
        return ResponseEntity.ok(orchestrator.get(id));
    }

    /**
     * AI-Critic Auto-Fix: iteratively re-roll the image-fixable weak scenes and
     * re-assemble until the AI-Critic score reaches {@code target} (default 90)
     * or the hard caps run out, then pause for review. Never auto-uploads.
     */
    @PostMapping("/{id}/autofix")
    public ResponseEntity<VideoJobResponse> autofix(@PathVariable UUID id,
                                                    @RequestParam(required = false) Integer target,
                                                    @RequestParam(required = false) Integer iterations) {
        return ResponseEntity.ok(orchestrator.startAutoFix(id, target, iterations));
    }
}
