package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.OffsetDateTime;
import java.util.*;

/**
 * Workdir retention (architecture.md §8 TODO: "workdir volume vol → job faalt
 * mid-pipeline"). A finished 29-scene Veo job leaves behind hundreds of MB of
 * intermediates: scene stills, 29 Veo clips + QC frames, per-scene audio, tmp
 * encodes. Only {@code out/final.mp4}, the thumbnail and the captions SRT are
 * ever needed again (re-upload, distribution, audits).
 *
 * Daily sweep: for COMPLETED jobs older than {@code app.retention.days}, prune
 *   - the Veo/asset tree   {@code workdir/jobs/<jobId>/}      (clips + stills)
 *   - the assembly dirs    {@code workdir/<jobId>/audio, tmp} (voice + encodes)
 *   - root-level *.mp4 intermediates in {@code workdir/<jobId>/} (with_sting…)
 * keeping {@code out/}, the thumbnail and any .srt untouched.
 *
 * Safety rails: every deleted path must contain the job's UUID; deletion never
 * follows symlinks (walkFileTree default); failures log and move on — a
 * retention sweep must never break the pipeline. Idempotent: already-cleaned
 * jobs no-op because their directories are gone.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class WorkdirRetention {

    private final VideoJobRepository repo;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.retention.enabled:true}")
    private boolean enabled;

    /** Days after COMPLETED before a job's intermediates are pruned. */
    @Value("${app.retention.days:30}")
    private int retentionDays;

    /** Daily, first run 5 minutes after startup (let recovery settle first). */
    @Scheduled(initialDelay = 5L * 60L * 1000L, fixedDelay = 24L * 60L * 60L * 1000L)
    public void sweep() {
        if (!enabled) return;
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(Math.max(1, retentionDays));
        List<VideoJob> done;
        try {
            done = repo.findByStatusIn(List.of(JobStatus.COMPLETED));
        } catch (Exception e) {
            log.warn("Retention sweep: job query failed ({}) — skipping run", e.getMessage());
            return;
        }
        int cleaned = 0;
        long freed = 0;
        for (VideoJob job : done) {
            if (job.getUpdatedAt() == null || job.getUpdatedAt().isAfter(cutoff)) continue;
            try {
                long bytes = cleanJob(job);
                if (bytes > 0) { cleaned++; freed += bytes; }
            } catch (Exception e) {
                log.warn("Retention sweep: job {} cleanup failed ({}) — continuing",
                        job.getId(), e.getMessage());
            }
        }
        if (cleaned > 0) {
            log.info("Retention sweep: pruned intermediates of {} job(s), freed ~{} MB",
                    cleaned, freed / (1024 * 1024));
        }
    }

    /** @return bytes freed (0 = nothing left to clean for this job). */
    private long cleanJob(VideoJob job) {
        String id = job.getId().toString();
        long freed = 0;

        // 1. Veo/asset tree workdir/jobs/<id>/ — derived from any scene path so
        //    no extra workdir-root config is needed.
        for (Path p : sceneRoots(job, id)) {
            freed += deleteTreeSafe(p, id);
        }

        // 2. Assembly intermediates workdir/<id>/{audio,tmp} + root-level *.mp4
        //    (with_sting.mp4 etc.). out/, thumbnail and .srt stay.
        Path assemblyRoot = assemblyRoot(job, id);
        if (assemblyRoot != null) {
            freed += deleteTreeSafe(assemblyRoot.resolve("audio"), id);
            freed += deleteTreeSafe(assemblyRoot.resolve("tmp"), id);
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(assemblyRoot, "*.{mp4,mkv}")) {
                for (Path mp4 : ds) {
                    if (Files.isRegularFile(mp4, LinkOption.NOFOLLOW_LINKS)) {
                        long sz = sizeQuiet(mp4);
                        if (deleteQuiet(mp4)) freed += sz;
                    }
                }
            } catch (IOException ignore) { /* dir may not exist anymore */ }
        }

        if (freed > 0) {
            log.info("Retention: job {} pruned, ~{} MB freed (kept final.mp4/thumbnail/srt)",
                    id, freed / (1024 * 1024));
        }
        return freed;
    }

    /** Distinct "workdir/jobs/<id>" roots derived from scene clip/image paths. */
    private Set<Path> sceneRoots(VideoJob job, String id) {
        Set<Path> roots = new HashSet<>();
        try {
            if (job.getAssemblyScenesJson() == null || job.getAssemblyScenesJson().isBlank()) {
                return roots;
            }
            var scenes = mapper.readTree(job.getAssemblyScenesJson());
            for (var s : scenes) {
                for (String key : new String[]{"clipPath", "imagePath"}) {
                    String p = s.path(key).asText("");
                    // .../jobs/<id>/scenes/<seq>/file → root = 3 levels up
                    int at = p.indexOf("/jobs/" + id + "/");
                    if (at >= 0) {
                        roots.add(Paths.get(p.substring(0, at) + "/jobs/" + id));
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Retention: scene-root parse failed for {} ({})", id, e.getMessage());
        }
        return roots;
    }

    /** "workdir/<id>" derived from the captions/video path on the job. */
    private Path assemblyRoot(VideoJob job, String id) {
        for (String p : new String[]{job.getVideoPath(), job.getCaptionsPath()}) {
            if (p == null || p.isBlank()) continue;
            int at = p.indexOf("/" + id + "/");
            if (at >= 0) return Paths.get(p.substring(0, at + 1 + id.length()));
        }
        return null;
    }

    /** Recursively deletes a tree, but ONLY when its path contains the job id
     *  (rail against a mis-derived root wiping unrelated data). @return bytes freed. */
    private long deleteTreeSafe(Path root, String jobId) {
        if (root == null || !root.toString().contains(jobId)) return 0;
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return 0;
        final long[] freed = {0};
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                    freed[0] += attrs.size();
                    deleteQuiet(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                    deleteQuiet(dir);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFileFailed(Path file, IOException exc) {
                    return FileVisitResult.CONTINUE;   // skip and keep going
                }
            });
        } catch (IOException e) {
            log.debug("Retention: walk failed on {} ({})", root, e.getMessage());
        }
        return freed[0];
    }

    private static long sizeQuiet(Path p) {
        try { return Files.size(p); } catch (IOException e) { return 0; }
    }

    private static boolean deleteQuiet(Path p) {
        try { Files.deleteIfExists(p); return true; }
        catch (IOException e) { return false; }
    }
}
