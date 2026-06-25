package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.AnthropicGate;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Kill-switch test: with ANTHROPIC_ENABLED=false, Clip QC must skip UP FRONT —
 * return PASS and never touch the WebClient (so no socket opens and no Reactor
 * exception is logged). The human review gate stays the backstop.
 */
class ClipQcKillSwitchTest {

    @Test
    void disabled_returns_pass_without_calling_webclient() {
        WebClient webClient = mock(WebClient.class);
        OrchestratorProperties props = mock(OrchestratorProperties.class);
        CharacterRefStills refStills = mock(CharacterRefStills.class);

        ClipQc qc = new ClipQc(webClient, props, refStills, new AnthropicGate(false));

        ClipQc.Result r = qc.check(Path.of("/tmp/clip.mp4"), List.of("Pip: white chick"));

        assertThat(r.ok()).isTrue();
        // The whole point: skipped up front, no paid call attempted.
        verifyNoInteractions(webClient);
        verifyNoInteractions(props);
        verifyNoInteractions(refStills);
    }
}
