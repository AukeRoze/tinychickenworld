package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Inline metadata editing from the dashboard — lets a reviewer fix a weak title,
 * description or tags without re-running the whole pipeline. The fields are
 * already persisted on the job; this just exposes a write path.
 */
@RestController
@RequestMapping("/api/v1/videos/{id}/metadata")
@RequiredArgsConstructor
public class MetadataController {

    private final VideoJobRepository repo;

    public record MetadataPatch(String title, String description, String tags) {}

    @PatchMapping
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id,
                                                      @RequestBody MetadataPatch patch) {
        VideoJob job = repo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        if (patch.title() != null)       job.setMetadataTitle(patch.title());
        if (patch.description() != null) job.setMetadataDescription(patch.description());
        if (patch.tags() != null)        job.setMetadataTags(patch.tags());
        repo.save(job);
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "UPDATED"));
    }
}
