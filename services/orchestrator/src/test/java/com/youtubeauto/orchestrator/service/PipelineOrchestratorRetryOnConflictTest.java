package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.OptimisticLockingFailureException;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Plain-Mockito unit tests for {@link PipelineOrchestrator#retryOnConflict},
 * the optimistic-locking retry helper introduced with @Version on
 * {@link VideoJob} (migration V26). No Spring context: only the repository
 * collaborator is real(ly mocked) — every other constructor dependency is
 * null because retryOnConflict touches nothing but the repo.
 */
class PipelineOrchestratorRetryOnConflictTest {

    private VideoJobRepository repo;
    private PipelineOrchestrator orch;
    private UUID jobId;

    @BeforeEach
    void setUp() {
        repo = mock(VideoJobRepository.class);
        // Constructor order = the 25 final fields of PipelineOrchestrator in
        // declaration order (see PipelineOrchestratorStateMachineTest); only
        // the repository is needed here.
        orch = new PipelineOrchestrator(
                repo, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null);
        jobId = UUID.randomUUID();
    }

    private VideoJob freshJob() {
        return VideoJob.builder()
                .id(jobId)
                .topic("Retry test episode")
                .audience("preschoolers")
                .targetSeconds(45)
                .status(JobStatus.PENDING)
                .build();
    }

    /** First save hits a stale-version conflict, the retry reloads a FRESH
     *  row, re-applies the mutation and succeeds. */
    @Test
    void retriesAfterConflict_reloadsAndReappliesMutation() {
        // A fresh entity per findById call — mimics the reload semantics.
        when(repo.findById(jobId)).thenAnswer(inv -> Optional.of(freshJob()));
        when(repo.save(any(VideoJob.class)))
                .thenThrow(new OptimisticLockingFailureException("stale version"))
                .thenAnswer(inv -> inv.getArgument(0));

        AtomicInteger mutationCalls = new AtomicInteger();
        orch.retryOnConflict(jobId, j -> {
            mutationCalls.incrementAndGet();
            j.setStep("mutated");
        });

        // Mutation re-applied on the reloaded row, one retry was enough.
        assertEquals(2, mutationCalls.get(), "mutation must run once per attempt");
        verify(repo, times(2)).findById(jobId);
        verify(repo, times(2)).save(any(VideoJob.class));
    }

    /** A conflict on every attempt is rethrown after 3 tries (max attempts). */
    @Test
    void rethrows_whenConflictPersistsAfterMaxAttempts() {
        when(repo.findById(jobId)).thenAnswer(inv -> Optional.of(freshJob()));
        when(repo.save(any(VideoJob.class)))
                .thenThrow(new OptimisticLockingFailureException("still stale"));

        assertThrows(OptimisticLockingFailureException.class,
                () -> orch.retryOnConflict(jobId, j -> j.setStep("never sticks")));

        verify(repo, times(3)).findById(jobId);
        verify(repo, times(3)).save(any(VideoJob.class));
    }

    /** Unknown job id = silent no-op, the contract the old
     *  repo.findById(id).ifPresent(...) helpers had. */
    @Test
    void missingJob_isSilentNoOp() {
        when(repo.findById(jobId)).thenReturn(Optional.empty());

        orch.retryOnConflict(jobId, j -> j.setStep("unreachable"));

        verify(repo, never()).save(any(VideoJob.class));
    }
}
