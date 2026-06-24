package com.youtubeauto.script.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Builds the WebClient used by AnthropicClient to call Claude's Messages API.
 * Includes the required {@code x-api-key} and {@code anthropic-version} headers.
 */
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
    public WebClient anthropicWebClient(AnthropicProperties props) {
        int timeoutSec = props.timeoutSeconds() != null ? props.timeoutSeconds() : 60;
        HttpClient httpClient = HttpClient.create(evictingPool("script-anthropic-pool"))
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(timeoutSec))
                .doOnConnected(c -> c.addHandlerLast(
                        new ReadTimeoutHandler(timeoutSec, TimeUnit.SECONDS)));

        return WebClient.builder()
                .baseUrl(props.baseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .defaultHeader("x-api-key", props.apiKey())
                .defaultHeader("anthropic-version", props.anthropicVersion())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }
}
