package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.client.ScriptServiceClient;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.service.MetadataGenerator;
import com.youtubeauto.orchestrator.service.MetadataPolicy;
import com.youtubeauto.orchestrator.service.VideoFormat;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Inline metadata editing from the dashboard — lets a reviewer fix a weak title,
 * description or tags without re-running the whole pipeline. The fields are
 * already persisted on the job; this exposes a write path.
 *
 * Three endpoints:
 *  - PATCH  (legacy route, kept for older UI builds): same behaviour as POST —
 *           it delegates to the validated handler, so the limits and the
 *           status guard apply on both routes. Only the HTTP verb is legacy.
 *  - POST   (preferred): partial update with YouTube-limit validation and a
 *           status guard — metadata is frozen once the upload has started,
 *           because edits after that silently diverge from what is on YouTube.
 *  - POST /regenerate: fresh LLM metadata from the stored script (same
 *           topic/title/hook inputs the assembly stage uses), passed through
 *           the deterministic {@link MetadataPolicy} brand gate, then persisted.
 *
 * Note: {@code MetadataGenerator.enforceTitleLength} is package-private in the
 * service package, so it cannot be reused here; the POST endpoint REJECTS an
 * over-long title instead of silently truncating (the reviewer typed it — a
 * loud 400 beats a quiet edit). Regenerated titles ARE capped, because the
 * generator applies enforceTitleLength internally.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/videos/{id}/metadata")
@RequiredArgsConstructor
public class MetadataController {

    /** YouTube hard limits: title 100 chars, description 5000 chars (bytes,
     *  strictly — chars is the safe approximation), total tag length 500. */
    private static final int MAX_TITLE_CHARS = 100;
    private static final int MAX_DESCRIPTION_CHARS = 5000;
    private static final int MAX_TAGS_TOTAL_CHARS = 500;

    private final VideoJobRepository repo;
    private final MetadataGenerator metadataGenerator;
    private final MetadataPolicy metadataPolicy;
    private final ScriptServiceClient scriptClient;

    public record MetadataPatch(String title, String description, String tags) {}

    /** Legacy route — the verb older dashboard builds call. Previously this was
     *  an unguarded write path next to the validated POST (so a stale UI could
     *  edit metadata mid-upload, silently diverging from YouTube). Now it
     *  delegates to {@link #updateValidated} so both verbs share the same
     *  YouTube-limit validation and upload-freeze guard. The only known
     *  callers are the dashboard's PATCH fallback (job-page.js) and humans
     *  with curl; nothing pipeline-internal calls it over HTTP. */
    @PatchMapping
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID id,
                                                      @RequestBody MetadataPatch patch) {
        return updateValidated(id, patch);
    }

    /** Validated partial update. null field = leave unchanged; "" clears it
     *  (clearing the title also unlocks regeneration on the next reassembly —
     *  see the METADATA LOCK note in the assembly stage). */
    @PostMapping
    public ResponseEntity<Map<String, Object>> updateValidated(@PathVariable UUID id,
                                                               @RequestBody MetadataPatch patch) {
        VideoJob job = repo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        ResponseEntity<Map<String, Object>> guard = guardEditable(job);
        if (guard != null) return guard;

        if (patch.title() != null) {
            String t = patch.title().trim();
            if (t.length() > MAX_TITLE_CHARS) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "title is " + t.length() + " chars — YouTube allows at most "
                                + MAX_TITLE_CHARS));
            }
            job.setMetadataTitle(t);
        }
        if (patch.description() != null) {
            if (patch.description().length() > MAX_DESCRIPTION_CHARS) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "description is " + patch.description().length()
                                + " chars — YouTube allows at most " + MAX_DESCRIPTION_CHARS));
            }
            job.setMetadataDescription(patch.description());
        }
        if (patch.tags() != null) {
            String tags = patch.tags().trim();
            // Stored comma-separated; YouTube counts the total tag characters.
            if (tags.replace(",", "").length() > MAX_TAGS_TOTAL_CHARS) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "tags total more than " + MAX_TAGS_TOTAL_CHARS
                                + " chars — YouTube rejects the upload"));
            }
            job.setMetadataTags(tags);
        }
        repo.save(job);
        return ResponseEntity.ok(currentMetadata(job, "UPDATED"));
    }

    /**
     * Regenerate title/description/tags from the stored script via the same
     * {@link MetadataGenerator#generate} + {@link MetadataPolicy} path the
     * assembly stage uses (incl. SEO keywords). Synchronous LLM call (seconds).
     * Overwrites the current metadata — the UI asks for confirmation first.
     */
    @PostMapping("/regenerate")
    public ResponseEntity<Map<String, Object>> regenerate(@PathVariable UUID id) {
        VideoJob job = repo.findById(id).orElse(null);
        if (job == null) return ResponseEntity.notFound().build();
        ResponseEntity<Map<String, Object>> guard = guardEditable(job);
        if (guard != null) return guard;
        if (job.getScriptJobId() == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "no script yet — metadata can only be regenerated after the script stage"));
        }

        String scriptTitle = "";
        String hook = "";
        try {
            JsonNode script = scriptClient.get(job.getScriptJobId()).path("script");
            scriptTitle = script.path("title").asText("");
            hook = script.path("hook").asText("");
        } catch (Exception e) {
            return ResponseEntity.status(502).body(Map.of("error",
                    "script-service unreachable — cannot read title/hook: " + e.getMessage()));
        }

        boolean isShort;
        try {
            isShort = VideoFormat.parse(job.getFormat()).isVertical();
        } catch (Exception e) {
            isShort = false; // unknown format string → landscape rules
        }

        try {
            MetadataGenerator.Metadata meta =
                    metadataGenerator.generate(job.getTopic(), scriptTitle, hook, isShort);
            // Same deterministic brand gate as the pipeline (banned hashtags
            // out, required hashtags + episode branding in).
            MetadataPolicy.Result polished = metadataPolicy.apply(meta, job.getEpisodeNumber());
            meta = polished.metadata();

            job.setMetadataTitle(meta.title());
            job.setMetadataDescription(meta.description());
            job.setMetadataTags(String.join(",", meta.tags()));
            repo.save(job);
            log.info("Job {} metadata regenerated from dashboard — new title \"{}\"",
                    id, meta.title());

            Map<String, Object> out = currentMetadata(job, "REGENERATED");
            out.put("policyFixes", polished.fixes());
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            log.warn("Job {} metadata regeneration failed: {}", id, e.getMessage());
            return ResponseEntity.status(502).body(Map.of("error",
                    "metadata generation failed: " + e.getMessage()));
        }
    }

    /** Frozen once the upload has started: edits after UPLOADING/COMPLETED (or
     *  once a YouTube id exists, which covers DISTRIBUTION_PENDING) would
     *  silently diverge from what viewers actually see on YouTube. */
    private static ResponseEntity<Map<String, Object>> guardEditable(VideoJob job) {
        boolean uploaded = job.getYoutubeVideoId() != null && !job.getYoutubeVideoId().isBlank();
        if (job.getStatus() == JobStatus.UPLOADING
                || job.getStatus() == JobStatus.COMPLETED
                || uploaded) {
            return ResponseEntity.status(409).body(Map.of("error",
                    "metadata is frozen — the video is "
                            + (uploaded ? "already on YouTube; edit it in YouTube Studio"
                                        : "uploading right now")));
        }
        return null;
    }

    private static Map<String, Object> currentMetadata(VideoJob job, String result) {
        Map<String, Object> out = new HashMap<>();
        out.put("id", job.getId().toString());
        out.put("result", result);
        out.put("title", job.getMetadataTitle() == null ? "" : job.getMetadataTitle());
        out.put("description", job.getMetadataDescription() == null ? "" : job.getMetadataDescription());
        out.put("tags", job.getMetadataTags() == null ? "" : job.getMetadataTags());
        return out;
    }
}
