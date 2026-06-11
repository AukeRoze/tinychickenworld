package com.youtubeauto.videogen.config;

import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.genai.Client;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;

/**
 * Wires Vertex AI Veo client + GCS client. Both beans are only registered
 * when {@code GCP_PROJECT_ID} is set to a non-empty value. Without that the
 * whole Veo chain (VertexVeoClient, GcsClient, ClipGenerationService,
 * ClipsController) silently doesn't exist — the service still boots so the
 * Ken-Burns pipeline isn't blocked.
 */
@Slf4j
@Configuration
@ConditionalOnExpression("'${gcp.project-id:}' != ''")
public class GenAiClientConfig {

    private Client genaiClient;

    @Bean
    public Client genaiClient(GcpProperties gcp) {
        log.info("Initialising Vertex AI Veo Client (project={}, region={})",
                gcp.projectId(), gcp.region());
        this.genaiClient = Client.builder()
                .project(gcp.projectId())
                .location(gcp.region())
                .vertexAI(true)
                .build();
        return this.genaiClient;
    }

    @Bean
    public Storage gcsStorage(GcpProperties gcp) {
        log.info("Initialising GCS storage client (project={})", gcp.projectId());
        return StorageOptions.newBuilder()
                .setProjectId(gcp.projectId())
                .build()
                .getService();
    }

    @PreDestroy
    void shutdown() {
        if (genaiClient != null) {
            try { genaiClient.close(); } catch (Exception ignored) {}
        }
    }
}
