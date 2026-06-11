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
