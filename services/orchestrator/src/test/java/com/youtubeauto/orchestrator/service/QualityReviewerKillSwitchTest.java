package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.client.AssemblyServiceClient;
import com.youtubeauto.orchestrator.config.AnthropicGate;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.domain.VideoAudit;
import com.youtubeauto.orchestrator.repository.VideoAuditRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Kill-switch test: with ANTHROPIC_ENABLED=false, the quality audit must skip UP
 * FRONT — return null and never touch the job repo, assembly-service or the
 * WebClient. Previously the call fired and the filter's exception was caught and
 * logged with a full Reactor stack trace on every job.
 */
class QualityReviewerKillSwitchTest {

    @Test
    void disabled_returns_null_without_any_downstream_work() {
        WebClient webClient = mock(WebClient.class);
        AssemblyServiceClient assembly = mock(AssemblyServiceClient.class);
        VideoJobRepository jobRepo = mock(VideoJobRepository.class);
        VideoAuditRepository auditRepo = mock(VideoAuditRepository.class);
        OrchestratorProperties props = mock(OrchestratorProperties.class);

        QualityReviewer reviewer = new QualityReviewer(
                webClient, assembly, jobRepo, auditRepo, props, new AnthropicGate(false));

        VideoAudit result = reviewer.auditJob(UUID.randomUUID());

        assertThat(result).isNull();
        verifyNoInteractions(webClient);
        verifyNoInteractions(assembly);
        verifyNoInteractions(jobRepo);
        verifyNoInteractions(auditRepo);
    }
}
