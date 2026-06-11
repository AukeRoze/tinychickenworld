package com.youtubeauto.thumbnail.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.thumbnail.config.ThumbnailProperties;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Base64;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpenAiImageClient {

    private final WebClient openAiImageWebClient;
    private final ThumbnailProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    @Retry(name = "openai-image")
    public byte[] generatePng(String prompt) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.openai().model());
        body.put("prompt", prompt);
        body.put("size", props.openai().size());
        body.put("n", 1);

        JsonNode resp = openAiImageWebClient.post()
                .uri("/images/generations")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .block();

        if (resp == null) throw new IllegalStateException("Empty image response");
        String b64 = resp.path("data").path(0).path("b64_json").asText(null);
        if (b64 == null || b64.isEmpty()) {
            throw new IllegalStateException("OpenAI image response missing b64_json: " + resp);
        }
        byte[] png = Base64.getDecoder().decode(b64);
        log.debug("Thumbnail base image: {} bytes", png.length);
        return png;
    }
}
