package com.youtubeauto.orchestrator.api;

import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.service.QcInsights;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only channel overviews for the static UI: distribution status per
 * finished video and the recurring vision-QC patterns.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class OverviewController {

    private final VideoJobRepository jobRepo;
    private final QcInsights qcInsights;

    /** Finished videos with their per-platform publish status. */
    @GetMapping("/distribution")
    public List<Map<String, Object>> distribution() {
        return jobRepo.findAll().stream()
                .filter(j -> j.getVideoPath() != null && !j.getVideoPath().isBlank())
                .map(j -> Map.<String, Object>of(
                        "id", j.getId().toString(),
                        "topic", j.getTopic() == null ? "" : j.getTopic(),
                        "youtube", j.getYoutubeVideoId() != null,
                        "facebook", j.getFacebookUrl() != null && !j.getFacebookUrl().isBlank(),
                        // V23: persisted by the distribution proxy after a push.
                        "tiktok", j.getTiktokPublishId() != null && !j.getTiktokPublishId().isBlank(),
                        "instagram", j.getInstagramMediaId() != null && !j.getInstagramMediaId().isBlank()))
                .toList();
    }

    /** Recurring vision-QC findings, most frequent first. */
    @GetMapping("/qc-patterns")
    public List<Map<String, Object>> qcPatterns() {
        return qcInsights.patterns().stream()
                .map(p -> Map.<String, Object>of(
                        "count", p.count(),
                        "category", p.category() == null ? "" : p.category(),
                        "character", p.character() == null ? "" : p.character(),
                        "lastExample", p.lastExample() == null ? "" : p.lastExample()))
                .toList();
    }
}
