package com.youtubeauto.script.anthropic;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.config.AnthropicProperties;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Kill-switch test: with ANTHROPIC_ENABLED=false the client must never open a
 * paid call. It fails fast and the underlying WebClient is left untouched.
 */
class AnthropicClientDisabledTest {

    @Test
    void disabled_throws_and_never_calls_webclient() throws Exception {
        WebClient webClient = mock(WebClient.class);
        AnthropicProperties props = new AnthropicProperties(
                "https://api.anthropic.com/v1", "key", "2023-06-01",
                "claude-test", 1024, 0.7, 60);
        AnthropicClient client = new AnthropicClient(webClient, props);
        // Field-injected @Value flag — simulate ANTHROPIC_ENABLED=false.
        ReflectionTestUtils.setField(client, "anthropicEnabled", false);

        var schema = new ObjectMapper().readTree("{\"type\":\"object\"}");

        assertThatThrownBy(() -> client.callTool(
                "system", List.of(new AnthropicClient.ChatMessage("user", "hi")),
                "emit_script", "desc", schema))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ANTHROPIC_ENABLED=false");

        // The whole point: no socket was opened.
        verifyNoInteractions(webClient);
    }
}
