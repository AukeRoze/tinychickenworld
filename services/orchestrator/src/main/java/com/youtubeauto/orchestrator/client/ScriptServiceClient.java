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

    /**
     * Import a ready-made script as a COMPLETED script job — no Anthropic call.
     * The AI-free counterpart to {@link #submit}: feeds a hand-authored script
     * (emit_script JSON) into script-service, which persists it verbatim and
     * returns a jobId that {@link #get} then polls as COMPLETED. Used when the
     * Anthropic kill-switch is on. Connect-refused retries only (like submit) so
     * a transient hiccup never spawns a duplicate orphan job.
     */
    public UUID importScript(String topic, String audience, int targetSeconds, String scriptJson) {
        JsonNode scriptNode;
        try {
            scriptNode = new com.fasterxml.jackson.databind.ObjectMapper().readTree(scriptJson);
        } catch (Exception e) {
            throw new IllegalArgumentException("import-script: invalid script JSON: " + e.getMessage(), e);
        }
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("topic", topic);
        body.put("audience", audience);
        body.put("targetSeconds", targetSeconds);
        body.put("script", scriptNode);
        JsonNode resp = Resilience.paid(
                client.post()
                        .uri("/api/v1/scripts/import")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofSeconds(60), "script-service import");
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

    /**
     * Canonical SOURCE-FIX write-back: persist orchestrator-sanitized scene
     * action text (visualDesc / motionDesc) onto the stored script_scenes rows.
     * Each patch is {@code {seq, visualDesc?, motionDesc?}}; null fields are left
     * untouched. Idempotent on the server, so this is treated as a safe-to-retry
     * call. Best-effort by contract — callers should not fail the render when the
     * canonical write-back is unavailable (the assembly-store fix already keeps
     * THIS job correct).
     */
    public JsonNode patchScenes(UUID scriptJobId, java.util.List<Map<String, Object>> scenePatches) {
        Map<String, Object> body = java.util.Map.of("scenes", scenePatches);
        return Resilience.idempotent(
                client.patch()
                        .uri("/api/v1/scripts/{id}/scenes", scriptJobId)
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofSeconds(30), "script-service patchScenes");
    }

    /** Story-treatment preview (front-end stage #1) — synchronous, creates no
     *  job. Idempotent: a re-fire just recomputes a (cheap) treatment. */
    public JsonNode treatment(String topic, String audience, int targetSeconds,
                              String brief, String lesson, String mood, String angle,
                              String hook, Integer numScenes) {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("topic", topic);
        body.put("audience", audience);
        body.put("targetSeconds", targetSeconds);
        if (numScenes != null) body.put("numScenes", numScenes);
        if (brief  != null && !brief.isBlank())  body.put("brief",  brief);
        if (lesson != null && !lesson.isBlank()) body.put("lesson", lesson);
        if (mood   != null && !mood.isBlank())   body.put("mood",   mood);
        if (angle  != null && !angle.isBlank())  body.put("angle",  angle);
        if (hook   != null && !hook.isBlank())   body.put("hook",   hook);
        return Resilience.idempotent(
                client.post()
                        .uri("/api/v1/scripts/treatment")
                        .bodyValue(body)
                        .retrieve().bodyToMono(JsonNode.class),
                java.time.Duration.ofSeconds(90), "script-service treatment");
    }
}
