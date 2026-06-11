package com.youtubeauto.orchestrator.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Map;
import java.util.UUID;

@Component
public class ScriptServiceClient {

    private final WebClient client;

    public ScriptServiceClient(WebClient.Builder builder, OrchestratorProperties props) {
        this.client = builder.clone().baseUrl(props.services().script()).build();
    }

    public UUID submit(String topic, String audience, int targetSeconds,
                       String brief, String lesson, String mood, String angle,
                       String hook) {
        return submit(topic, audience, targetSeconds, brief, lesson, mood, angle, hook, null, null);
    }

    public UUID submit(String topic, String audience, int targetSeconds,
                       String brief, String lesson, String mood, String angle,
                       String hook, String performanceHint) {
        return submit(topic, audience, targetSeconds, brief, lesson, mood, angle,
                hook, performanceHint, null);
    }

    public UUID submit(String topic, String audience, int targetSeconds,
                       String brief, String lesson, String mood, String angle,
                       String hook, String performanceHint, String preferredArc) {
        java.util.Map<String, Object> body = new java.util.HashMap<>();
        body.put("topic", topic);
        body.put("audience", audience);
        body.put("targetSeconds", targetSeconds);
        if (brief  != null && !brief.isBlank())  body.put("brief",  brief);
        if (lesson != null && !lesson.isBlank()) body.put("lesson", lesson);
        if (mood   != null && !mood.isBlank())   body.put("mood",   mood);
        if (angle  != null && !angle.isBlank())  body.put("angle",  angle);
        if (hook   != null && !hook.isBlank())   body.put("hook",   hook);
        if (performanceHint != null && !performanceHint.isBlank())
            body.put("performanceHint", performanceHint);
        if (preferredArc != null && !preferredArc.isBlank())
            body.put("preferredArc", preferredArc);
        // Submit is a fast job-create, but a duplicate submit spawns an orphan
        // (paid) script job — so paid profile: connect-refused retries only.
        JsonNode resp = Resilience.paid(
                client.post()
                        .uri("/api/v1/scripts")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofSeconds(60), "script-service submit");
        return UUID.fromString(resp.get("jobId").asText());
    }

    public JsonNode get(UUID jobId) {
        // Status poll — fully idempotent, retry freely on any transient error.
        return Resilience.idempotent(
                client.get()
                        .uri("/api/v1/scripts/{id}", jobId)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofSeconds(30), "script-service get");
    }
}
