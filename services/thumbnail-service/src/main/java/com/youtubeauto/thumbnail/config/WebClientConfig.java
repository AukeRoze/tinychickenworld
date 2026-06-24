package com.youtubeauto.thumbnail.config;

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

    /** Idle-eviction pool: voorkomt "Connection prematurely closed BEFORE
     *  response" door stale keep-alive-connecties niet meer te hergebruiken. */
    private static ConnectionProvider evictingPool(String name) {
        return ConnectionProvider.builder(name)
                .maxConnections(50)
                .maxIdleTime(Duration.ofSeconds(20))
                .maxLifeTime(Duration.ofMinutes(5))
                .pendingAcquireTimeout(Duration.ofSeconds(60))
                .evictInBackground(Duration.ofSeconds(30))
                .build();
    }

    @Bean
    public WebClient openAiImageWebClient(ThumbnailProperties props) {
        HttpClient http = HttpClient.create(evictingPool("thumb-openai-pool"))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(props.openai().timeoutSeconds()));

        return WebClient.builder()
                .baseUrl(props.openai().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.openai().apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    /** Client for image-service — renders anchor-based thumbnail bases. Gemini
     *  reference generation can take a while, so the read timeout is generous. */
    @Bean
    public WebClient imageServiceWebClient(ThumbnailProperties props) {
        HttpClient http = HttpClient.create(evictingPool("thumb-image-pool"))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(180));

        return WebClient.builder()
                .baseUrl(props.image().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }
}
