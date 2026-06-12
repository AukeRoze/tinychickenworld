package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest;
import com.youtubeauto.video.api.dto.AssemblyResult;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * In-memory job store + runner for the async assembly endpoints
 * (POST /api/v1/assemble-async + GET /api/v1/assemble/jobs/{jobId}).
 *
 * Why: the synchronous endpoint keeps one HTTP connection open for the full
 * ffmpeg render. A full render (chunked concat with extra encode passes +
 * two-pass loudnorm + Short export) can run past 20 minutes, and on
 * 2026-06-12 (job e2ec9448) that tripped the orchestrator's Netty
 * responseTimeout mid-render while ffmpeg kept going. The async pair
 * decouples that: submit returns a jobId immediately, the orchestrator polls.
 * Same design as video-generation-service's AsyncClipJobStore.
 *
 * Deliberately simple — a ConcurrentHashMap, no persistence. Jobs expire
 * 2 hours after their last state change (a lazy timestamp check on every
 * access; no background reaper thread). A service restart loses the store:
 * pollers then get 404 and treat the assembly as failed, which is no worse
 * than the dropped connection a restart causes for the synchronous endpoint.
 *
 * The executor is bounded at 1 thread ON PURPOSE: assembly is the heaviest
 * stage in the pipeline (ffmpeg saturates the box), so one render at a time
 * is the deliberate cap — additional submits simply queue. The per-scene
 * parallelism INSIDE one render is still governed by AssemblyService's own
 * scene-parallelism pool.
 */
@Slf4j
@Component
public class AsyncAssemblyJobStore {

    public enum Status { RUNNING, DONE, FAILED }

    public record JobState(Status status, AssemblyResult result,
                           String error, Instant updatedAt) {}

    static final Duration EXPIRY = Duration.ofHours(2);

    private final ExecutorService pool = Executors.newFixedThreadPool(1);
    private final Map<UUID, JobState> jobs = new ConcurrentHashMap<>();

    /** Starts the (slow) render on the single-thread executor and returns the
     *  poll handle immediately. Reuses the exact same service method the
     *  synchronous endpoint calls, so behaviour/output are identical. */
    public UUID submit(AssemblyService service, AssemblyRequest req) {
        evictExpired();
        UUID id = UUID.randomUUID();
        jobs.put(id, new JobState(Status.RUNNING, null, null, Instant.now()));
        pool.submit(() -> {
            try {
                AssemblyResult resp = service.assemble(req);
                jobs.put(id, new JobState(Status.DONE, resp, null, Instant.now()));
                log.info("Async assembly job {} DONE (videoJob={}, output={}, {}s)",
                        id, req.jobId(), resp.outputPath(), resp.durationSeconds());
            } catch (Exception e) {
                log.error("Async assembly job {} FAILED (videoJob={}): {}",
                        id, req.jobId(), e.getMessage(), e);
                jobs.put(id, new JobState(Status.FAILED, null,
                        e.getClass().getSimpleName() + ": " + e.getMessage(), Instant.now()));
            }
        });
        log.info("Async assembly job {} submitted (videoJob={}, scenes={})",
                id, req.jobId(), req.scenes().size());
        return id;
    }

    /** Empty = unknown OR expired jobId (the caller can't tell the difference,
     *  and shouldn't need to: both mean "this result is gone"). */
    public Optional<JobState> get(UUID id) {
        evictExpired();
        return Optional.ofNullable(jobs.get(id));
    }

    /** Lazy cleanup on access: anything not touched for 2h is dropped.
     *  RUNNING entries are safe in practice — a render finishes (or fails)
     *  well inside 2h and refreshes its timestamp on completion. */
    private void evictExpired() {
        Instant cutoff = Instant.now().minus(EXPIRY);
        jobs.entrySet().removeIf(e -> e.getValue().updatedAt().isBefore(cutoff));
    }

    @PreDestroy
    void shutdown() {
        pool.shutdownNow();
    }
}
