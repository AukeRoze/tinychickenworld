package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.client.ScriptServiceClient;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.domain.VideoLocalization;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.repository.VideoLocalizationRepository;
import com.youtubeauto.orchestrator.service.LocalizationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Localization endpoints:
 *   POST /api/v1/videos/{id}/localize/{lang}      → translate and persist
 *   GET  /api/v1/videos/{id}/localizations        → list status per lang
 *   GET  /api/v1/languages                        → supported codes
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class LocalizationController {

    private final VideoJobRepository jobs;
    private final VideoLocalizationRepository locs;
    private final LocalizationService localiser;
    private final com.youtubeauto.orchestrator.service.MetadataGenerator metadata;
    private final ScriptServiceClient scriptClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping("/api/v1/languages")
    public Map<String, Object> languages() {
        return Map.of(
                "supported", localiser.supportedLanguages(),
                "names", localiser.supportedLanguages().stream()
                        .collect(java.util.stream.Collectors.toMap(c -> c, localiser::displayName))
        );
    }

    @GetMapping("/api/v1/videos/{id}/localizations")
    public List<Map<String, Object>> list(@PathVariable UUID id) {
        return locs.findByVideoJobId(id).stream()
                .map(l -> Map.<String, Object>of(
                        "language", l.getLanguageCode(),
                        "name",     localiser.displayName(l.getLanguageCode()),
                        "status",   l.getStatus(),
                        "youtubeVideoId", l.getYoutubeVideoId() == null ? "" : l.getYoutubeVideoId(),
                        "error",    l.getError() == null ? "" : l.getError()))
                .toList();
    }

    @PostMapping("/api/v1/videos/{id}/localize/{lang}")
    public ResponseEntity<Map<String, String>> localize(@PathVariable UUID id, @PathVariable String lang) {
        Optional<VideoJob> opt = jobs.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();
        VideoJob job = opt.get();
        if (job.getScriptJobId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Script not generated yet"));
        }
        try {
            JsonNode scriptEN = scriptClient.get(job.getScriptJobId()).path("script");
            JsonNode translated = localiser.translate(scriptEN, lang);

            VideoLocalization loc = locs.findByVideoJobIdAndLanguageCode(id, lang.toLowerCase())
                    .orElseGet(() -> VideoLocalization.builder()
                            .videoJobId(id)
                            .languageCode(lang.toLowerCase())
                            .build());
            loc.setTranslatedScript(translated.toString());

            // Also localise the YouTube metadata (title/description/tags) when the
            // English metadata already exists for this job. Best-effort: a failure
            // here must not lose the translated script.
            if (job.getMetadataTitle() != null && !job.getMetadataTitle().isBlank()) {
                try {
                    boolean isShort = com.youtubeauto.orchestrator.service.VideoFormat
                            .parse(job.getFormat()).isVertical();
                    List<String> enTags = (job.getMetadataTags() == null || job.getMetadataTags().isBlank())
                            ? List.of() : List.of(job.getMetadataTags().split("\\s*,\\s*"));
                    var enMeta = new com.youtubeauto.orchestrator.service.MetadataGenerator.Metadata(
                            job.getMetadataTitle(),
                            job.getMetadataDescription() == null ? "" : job.getMetadataDescription(),
                            enTags);
                    var locMeta = metadata.localize(enMeta, localiser.displayName(lang.toLowerCase()), isShort);
                    loc.setLocalizedTitle(locMeta.title());
                    loc.setLocalizedDescription(locMeta.description());
                    loc.setLocalizedTags(String.join(",", locMeta.tags()));
                } catch (Exception me) {
                    log.warn("Metadata localisation failed for job {} lang {} (script still translated): {}",
                            id, lang, me.getMessage());
                }
            }

            loc.setStatus("TRANSLATED");
            loc.setError(null);
            loc.setUpdatedAt(OffsetDateTime.now());
            locs.save(loc);
            return ResponseEntity.ok(Map.of(
                    "result", "TRANSLATED",
                    "language", lang,
                    "title", translated.path("title").asText()));
        } catch (Exception e) {
            log.error("Localization failed for job {} lang {}", id, lang, e);
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
