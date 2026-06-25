package com.youtubeauto.orchestrator.config;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit test for the central kill-switch gate. The gate is the single source of
 * truth every call-site consults before a paid Anthropic call.
 */
class AnthropicGateTest {

    @Test
    void enabled_gate_allows_calls_and_never_skips() {
        AnthropicGate gate = new AnthropicGate(true);
        assertThat(gate.enabled()).isTrue();
        assertThat(gate.skip("Clip QC")).isFalse();
    }

    @Test
    void disabled_gate_blocks_calls_and_skips() {
        AnthropicGate gate = new AnthropicGate(false);
        assertThat(gate.enabled()).isFalse();
        assertThat(gate.skip("Clip QC")).isTrue();
    }
}
