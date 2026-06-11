package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * P4 — crash recovery.
 *
 * <p>The pipeline runs each stage on an {@code @Async} thread with no message
 * queue, so a process restart mid-job strands that job in a non-terminal
 * "*_GENERATING" / ASSEMBLING / UPLOADING status forever: the worker thread is
 * gone, but the DB row still says "in progress" and nothing ever moves it on.
 *
 * <p>On startup we find those stranded jobs and re-trigger the stage they were
 * in (each stage reuses whatever assets already exist, so it is idempotent).
 * Review-pending and terminal states are deliberately left alone — the former
 * are legitimately waiting on a human, the latter are done.
 *
 * <p>Assumes a single orchestrator instance (the deployment constraint), so
 * there is no risk of two nodes resuming the same job.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JobRecovery {

    private final VideoJobRepository repo;
    private final PipelineOrchestrator orchestrator;

    /** Statuses that mean "actively being processed when the JVM stopped":
     *  not terminal (COMPLETED/FAILED) and not awaiting human review. */
    private static final List<JobStatus> IN_FLIGHT = List.of(
            JobStatus.PENDING,
            JobStatus.SCRIPT_GENERATING,
            JobStatus.ASSETS_GENERATING,
            JobStatus.VEO_GENERATING,
            JobStatus.ASSEMBLING,
            JobStatus.UPLOADING);

    @EventListener(ApplicationReadyEvent.class)
    public void resumeInFlightJobs() {
        List<VideoJob> stranded;
        try {
            stranded = repo.findByStatusIn(IN_FLIGHT);
        } catch (Exception e) {
            log.error("Crash recovery: could not query in-flight jobs ({}) — skipping", e.getMessage(), e);
            return;
        }
        if (stranded.isEmpty()) {
            log.info("Crash recovery: no in-flight jobs to resume.");
            return;
        }
        log.warn("Crash recovery: {} job(s) were mid-pipeline at last shutdown — resuming.",
                stranded.size());
        for (VideoJob job : stranded) {
            try {
                log.warn("Crash recovery: resuming job {} from {}", job.getId(), job.getStatus());
                orchestrator.resumeAfterRestart(job.getId(), job.getStatus());
            } catch (Exception e) {
                // One bad job must never block recovery of the others.
                log.error("Crash recovery: failed to resume job {} ({})", job.getId(), e.getMessage(), e);
            }
        }
    }
}
