package com.youtubeauto.orchestrator.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        // P3a — timeouts so a slow/stalled downstream service can never hang a
        // pipeline thread forever (every client clones this builder).
        //  - connect timeout (10s): fast-fail when a service is down/unreachable.
        //  - response timeout (20 min): a generous infinite-hang guard. It must
        //    stay ABOVE the slowest legitimate call: the synchronous Veo stage
        //    runs 5–15 min wallclock (see architecture.md §8), while image/voice
        //    finish in 1–2 min. If Veo jobs ever grow past this, give the
        //    video-generation client its own longer-timeout WebClient.
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofMinutes(20));
        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .codecs(c -> c.defaultCodecs().maxInMemorySize(20 * 1024 * 1024));
    }

    @Bean
    public WebClient anthropicWebClient(OrchestratorProperties props, WebClient.Builder builder) {
        return builder
                .baseUrl(props.anthropic().baseUrl())
                .defaultHeader("x-api-key", props.anthropic().apiKey())
                .defaultHeader("anthropic-version", props.anthropic().anthropicVersion())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
