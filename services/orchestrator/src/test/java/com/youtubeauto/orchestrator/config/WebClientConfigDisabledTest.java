package com.youtubeauto.orchestrator.config;

import org.junit.jupiter.api.Test;
import org.springframework.web.reactive.function.client.WebClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Kill-switch test: when ANTHROPIC_ENABLED=false the orchestrator's
 * anthropicWebClient must short-circuit EVERY request before any socket opens,
 * so no paid call can fire regardless of which feature triggers it.
 */
class WebClientConfigDisabledTest {

    private static OrchestratorProperties props() {
        return new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(
                        "http://localhost:9", "key", "2023-06-01", "claude-test", 1024, 0.7),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible("none"));
    }

    @Test
    void disabled_short_circuits_every_request() {
        WebClient client = new WebClientConfig()
                .anthropicWebClient(props(), WebClient.builder(), /*anthropicEnabled=*/ false);

        // The filter returns Mono.error before reaching the network; localhost:9
        // is never contacted, so the failure is our kill-switch ISE, not a
        // connection error.
        assertThatThrownBy(() -> client.post()
                .uri("/messages")
                .bodyValue("{}")
                .retrieve()
                .bodyToMono(String.class)
                .block())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_ENABLED=false");
    }

    /**
     * Regression: the kill-switch filter must NOT leak onto OTHER clients that
     * clone the same shared WebClient.Builder bean (e.g. AssemblyServiceClient).
     * The Anthropic bean once mutated the shared builder in place, so the filter
     * blocked the assembly call — which never touches Anthropic.
     */
    @Test
    void disabled_does_not_leak_to_other_clients_sharing_the_builder() {
        WebClient.Builder shared = WebClient.builder();
        // Build the disabled Anthropic client from the shared builder...
        new WebClientConfig().anthropicWebClient(props(), shared, /*anthropicEnabled=*/ false);
        // ...then a sibling client clones that SAME shared builder, exactly like
        // AssemblyServiceClient does.
        WebClient sibling = shared.clone().baseUrl("http://localhost:9").build();

        // The sibling must reach (and fail to connect to) localhost:9 — i.e. a
        // transport error — NOT the kill-switch ISE. If the filter leaked, this
        // would throw "ANTHROPIC_ENABLED=false" instead.
        assertThatThrownBy(() -> sibling.post()
                .uri("/api/v1/assemble-async")
                .bodyValue("{}")
                .retrieve()
                .bodyToMono(String.class)
                .block())
                .satisfies(e -> org.assertj.core.api.Assertions.assertThat(
                        String.valueOf(e.getMessage())).doesNotContain("ANTHROPIC_ENABLED=false"));
    }
}
