package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.review.CostEstimator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Read-only cost view for the job page (backlog P2 "Cost panel", from
 * analyse/FRONTEND-GAP-REVIEW.md).
 *
 * GET /api/v1/videos/{id}/cost
 *
 * Surfaces ONLY what the backend already computes/persists — nothing invented:
 *   - the {@link CostEstimator} estimate incl. its human-readable breakdown
 *     (the /review endpoint only exposes estimateEur + capEur, not the notes);
 *   - the ACTUAL Veo spend from the job's persisted metricsJson
 *     ({veoCostEur, veoOk, veoTotal}), filled by the pipeline per stage.
 *
 * Deliberately a separate tiny controller: VideoController and
 * PipelineOrchestrator are not touched (review constraint).
 */
@RestController
@RequiredArgsConstructor
public class CostController {

    private final VideoJobRepository jobs;
    private final CostEstimator estimator;
    private final ObjectMapper mapper;

    @GetMapping("/api/v1/videos/{id}/cost")
    public ResponseEntity<Map<String, Object>> cost(@PathVariable UUID id) {
        VideoJob job = jobs.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("motionMode", job.getMotionMode());

        try {
            CostEstimator.Result r = estimator.estimate(job);
            out.put("estimateEur", r.estimateEur());
            out.put("capEur", r.capEur());
            out.put("breakdown", r.breakdown());
        } catch (Exception e) {
            // Estimator failure must never break the panel — honest empty values.
            out.put("estimateEur", null);
            out.put("capEur", null);
            out.put("breakdown", List.of());
        }

        // Actuals: the per-stage production metrics the pipeline already persists
        // on the job (see VideoJob.metricsJson). null when no render ran yet.
        Map<String, Object> actual = new LinkedHashMap<>();
        try {
            String json = job.getMetricsJson();
            if (json != null && !json.isBlank()) {
                JsonNode m = mapper.readTree(json);
                if (m.hasNonNull("veoCostEur")) actual.put("veoCostEur", m.get("veoCostEur").asDouble());
                if (m.hasNonNull("veoOk"))      actual.put("veoOk",      m.get("veoOk").asInt());
                if (m.hasNonNull("veoTotal"))   actual.put("veoTotal",   m.get("veoTotal").asInt());
            }
        } catch (Exception ignore) { /* metrics are informative only */ }
        out.put("actual", actual.isEmpty() ? null : actual);

        return ResponseEntity.ok(out);
    }
}
