package com.youtubeauto.orchestrator.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

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
        // FIX "Connection prematurely closed BEFORE response": Reactor Netty's
        // default pool heeft GEEN maxIdleTime, dus een connectie die de downstream
        // (of het Docker-netwerk) al sloot wordt alsnog hergebruikt → de call valt
        // weg vóór het antwoord, vaak midden in een stage. Met idle-eviction ruimt
        // de pool stale connecties op vóór hergebruik; maxLifeTime + achtergrond-
        // eviction vangen de rest. Nul kosten-risico (we re-firen geen betaalde call).
        ConnectionProvider provider = ConnectionProvider.builder("orchestrator-pool")
                .maxConnections(100)
                .maxIdleTime(Duration.ofSeconds(20))      // < downstream keep-alive: nooit een stale conn hergebruiken
                .maxLifeTime(Duration.ofMinutes(5))       // harde max-leeftijd per connectie
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(30))// ruim stale conns ook op zonder verkeer
                .build();
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .option(ChannelOption.SO_KEEPALIVE, true) // TCP keep-alive: dode peers sneller detecteren
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
