package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.service.BibleEditor;
import com.youtubeauto.orchestrator.service.BibleSuggestions;
import com.youtubeauto.orchestrator.service.QcInsights;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Surfaces RECURRING vision-QC failures so you can see what to harden in the
 * prompt/anchor instead of re-fixing the same thing every video.
 *
 *   GET /api/v1/insights/qc-patterns
 *     -> [{category, character, count, lastExample}, ...]  most frequent first
 */
@RestController
@RequiredArgsConstructor
public class QcInsightsController {

    private final QcInsights qcInsights;
    private final BibleSuggestions bibleSuggestions;
    private final BibleEditor bibleEditor;
    private final com.youtubeauto.orchestrator.client.UploadServiceClient uploadClient;
    private final com.youtubeauto.orchestrator.service.RefIntegrityCheck refIntegrity;
    private final com.youtubeauto.orchestrator.repository.VideoJobRepository jobRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** Dashboard health strip: OAuth-token status (proxied from the
     *  upload-service) + character ref-coverage. One red bar at the top of the
     *  jobs list beats a buried ERROR log line. */
    @GetMapping("/api/v1/insights/health")
    public Map<String, Object> health() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        var oauth = uploadClient.oauthHealth();
        Map<String, Object> o = new java.util.LinkedHashMap<>();
        if (oauth == null) {
            o.put("healthy", null);   // unknown — service unreachable / not yet probed
            o.put("note", "upload-service niet bereikbaar of nog niet gecheckt");
        } else {
            o.put("healthy", oauth.path("healthy").asBoolean(false));
            o.put("lastError", oauth.path("lastError").asText(""));
            o.put("checkedAt", oauth.path("checkedAt").asText(""));
        }
        out.put("oauth", o);
        var refs = refIntegrity.status();
        out.put("refs", Map.of("ok", refs.ok(), "missing", refs.missing()));
        return out;
    }

    /** Production metrics per job (kosten, stretch, bible-hash, thumbnail-keuze)
     *  for the analytics page — parsed from metrics_json, newest first. */
    @GetMapping("/api/v1/insights/production-metrics")
    public List<Map<String, Object>> productionMetrics() {
        List<Map<String, Object>> out = new java.util.ArrayList<>();
        for (var j : jobRepo.findAll()) {
            if (j.getMetricsJson() == null || j.getMetricsJson().isBlank()) continue;
            try {
                var m = mapper.readTree(j.getMetricsJson());
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("jobId", j.getId().toString());
                row.put("topic", j.getTopic());
                row.put("episodeNumber", j.getEpisodeNumber());
                row.put("createdAt", j.getCreatedAt() == null ? null : j.getCreatedAt().toString());
                row.put("veoCostEur", m.path("veoCostEur").isMissingNode() ? null : m.path("veoCostEur").asDouble());
                row.put("veoOk", m.path("veoOk").isMissingNode() ? null : m.path("veoOk").asInt());
                row.put("veoTotal", m.path("veoTotal").isMissingNode() ? null : m.path("veoTotal").asInt());
                row.put("scriptedSeconds", m.path("scriptedSeconds").isMissingNode() ? null : m.path("scriptedSeconds").asInt());
                row.put("masterSeconds", m.path("masterSeconds").isMissingNode() ? null : m.path("masterSeconds").asDouble());
                row.put("stretchFactor", m.path("stretchFactor").isMissingNode() ? null : m.path("stretchFactor").asDouble());
                row.put("bibleSha256", m.path("bibleSha256").asText(null));
                row.put("thumbnailBestVariant", m.path("thumbnailBestVariant").isMissingNode() ? null : m.path("thumbnailBestVariant").asInt());
                out.add(row);
            } catch (Exception ignore) { /* skip malformed */ }
        }
        out.sort((a, b) -> String.valueOf(b.get("createdAt")).compareTo(String.valueOf(a.get("createdAt"))));
        return out;
    }

    /** Learning-studio loop 2: recurring QC patterns translated into concrete,
     *  reviewable bible-fix proposals. ?refresh=true forces regeneration
     *  (each proposal costs one LLM call; otherwise cached ~30 min). */
    @GetMapping("/api/v1/insights/bible-suggestions")
    public List<BibleSuggestions.Suggestion> bibleSuggestions(
            @RequestParam(defaultValue = "false") boolean refresh) {
        return bibleSuggestions.suggestions(refresh);
    }

    /** Approve a proposal: writes the field via BibleEditor (backup +
     *  validation + rollback built in). Body: {characterId, field, value}. */
    @PostMapping("/api/v1/insights/bible-suggestions/apply")
    public ResponseEntity<?> applySuggestion(@RequestBody Map<String, String> body) {
        String id = body.get("characterId");
        String field = body.get("field");
        String value = body.get("value");
        if (id == null || field == null || value == null || value.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "characterId, field en value zijn verplicht"));
        }
        try {
            List<String> changed = bibleEditor.updateCharacter(id, Map.of(field, value));
            if (changed.isEmpty()) {
                return ResponseEntity.badRequest().body(Map.of(
                        "error", "veld '" + field + "' niet gevonden/bewerkbaar voor '" + id + "'"));
            }
            bibleSuggestions.invalidate();
            return ResponseEntity.ok(Map.of("result", "APPLIED", "changed", changed,
                    "note", "Herstart orchestrator + image-service om de bible-cache te verversen."));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/api/v1/insights/qc-patterns")
    public Map<String, Object> patterns() {
        List<QcInsights.Pattern> p = qcInsights.patterns();
        long total = p.stream().mapToLong(QcInsights.Pattern::count).sum();
        return Map.of(
                "totalFindings", total,
                "patterns", p.stream().map(x -> Map.of(
                        "category", x.category(),
                        "character", x.character(),
                        "count", x.count(),
                        "lastExample", x.lastExample() == null ? "" : x.lastExample()
                )).toList(),
                "hint", "High-count rows are recurring — harden the prompt/anchor for "
                        + "that category+character permanently so future renders start clean."
        );
    }
}
