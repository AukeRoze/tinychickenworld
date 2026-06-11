package com.youtubeauto.voice.config;

import io.netty.channel.ChannelOption;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;

@Configuration
public class WebClientConfig {

    @Bean
    public WebClient elevenLabsWebClient(VoiceProperties props) {
        HttpClient http = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
                .responseTimeout(Duration.ofSeconds(props.elevenlabs().timeoutSeconds()));

        return WebClient.builder()
                .baseUrl(props.elevenlabs().baseUrl())
                .clientConnector(new ReactorClientHttpConnector(http))
                .defaultHeader("xi-api-key", props.elevenlabs().apiKey())
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }
}
