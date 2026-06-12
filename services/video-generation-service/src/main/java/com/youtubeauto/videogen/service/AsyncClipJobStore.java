package com.youtubeauto.videogen.service;

import com.youtubeauto.videogen.api.dto.GenerateClipsRequest;
import com.youtubeauto.videogen.api.dto.GenerateClipsResponse;
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
 * In-memory job store + runner for the async clip-generation endpoints
 * (POST /api/v1/clips/generate-async + GET /api/v1/clips/jobs/{jobId}).
 *
 * Why: the synchronous endpoint keeps one HTTP connection open for the full
 * Veo render (up to 15 minutes on the orchestrator side). The async pair
 * decouples that: submit returns a jobId immediately, the orchestrator polls.
 *
 * Deliberately simple — a ConcurrentHashMap, no persistence. Jobs expire
 * 2 hours after their last state change (a lazy timestamp check on every
 * access; no background reaper thread). A service restart loses the store:
 * pollers then get 404 and treat the generation as failed, which is no worse
 * than the dropped connection a restart causes for the synchronous endpoint.
 *
 * The executor is bounded at 2 threads (same sizing as the orchestrator's
 * runAssetsStage pool); the per-scene parallelism INSIDE one generation is
 * still governed by ClipGenerationService's own semaphore/pool, so this cap
 * only limits how many whole generate-requests run concurrently.
 */
@Slf4j
@Component
public class AsyncClipJobStore {

    public enum Status { RUNNING, DONE, FAILED }

    public record JobState(Status status, GenerateClipsResponse result,
                           String error, Instant updatedAt) {}

    static final Duration EXPIRY = Duration.ofHours(2);

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private final Map<UUID, JobState> jobs = new ConcurrentHashMap<>();

    /** Starts the (slow) generation on the bounded executor and returns the
     *  poll handle immediately. Reuses the exact same service method the
     *  synchronous endpoint calls, so behaviour/cost-caps/fallbacks are identical. */
    public UUID submit(ClipGenerationService service, GenerateClipsRequest req) {
        evictExpired();
        UUID id = UUID.randomUUID();
        jobs.put(id, new JobState(Status.RUNNING, null, null, Instant.now()));
        pool.submit(() -> {
            try {
                GenerateClipsResponse resp = service.generate(req);
                jobs.put(id, new JobState(Status.DONE, resp, null, Instant.now()));
                log.info("Async clip job {} DONE (videoJob={}, clips={}, cost=€{})",
                        id, req.jobId(), resp.clips().size(), resp.totalCostEur());
            } catch (Exception e) {
                log.error("Async clip job {} FAILED (videoJob={}): {}",
                        id, req.jobId(), e.getMessage(), e);
                jobs.put(id, new JobState(Status.FAILED, null,
                        e.getClass().getSimpleName() + ": " + e.getMessage(), Instant.now()));
            }
        });
        log.info("Async clip job {} submitted (videoJob={}, scenes={})",
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
     *  RUNNING entries are safe in practice — a generation finishes (or fails)
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
