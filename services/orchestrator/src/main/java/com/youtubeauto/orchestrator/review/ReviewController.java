package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.service.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

/**
 * Approve/reject endpoints for the dashboard (POST only). The old GET
 * variants — kept for one-click mail links — were removed: mail clients and
 * link-preview bots prefetch GET links and could approve a job unintentionally.
 * Mail links now go through the signed-token confirm flow instead
 * ({@link ReviewConfirmController}, GET /api/v1/review/confirm?token=...).
 */
@RestController
@RequestMapping("/api/v1/videos/{id}")
@RequiredArgsConstructor
public class ReviewController {

    private final PipelineOrchestrator orchestrator;
    private final com.youtubeauto.orchestrator.service.QualityReviewer qualityReviewer;

    @PostMapping("/approve")
    public ResponseEntity<Map<String, String>> approvePost(@PathVariable UUID id) { return doApprove(id); }

    @PostMapping("/reject")
    public ResponseEntity<Map<String, String>> rejectPost(@PathVariable UUID id,
                                                          @RequestParam(required = false) String reason) {
        return doReject(id, reason);
    }

    private ResponseEntity<Map<String, String>> doApprove(UUID id) {
        orchestrator.approve(id);
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "APPROVED",
                "message", "Pipeline continued."));
    }

    private ResponseEntity<Map<String, String>> doReject(UUID id, String reason) {
        orchestrator.reject(id, reason);
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "REJECTED",
                "reason", reason == null ? "" : reason));
    }

    // -------- per-scene image review (Feature A) --------

    @PostMapping("/scenes/{seq}/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateScene(@PathVariable UUID id, @PathVariable int seq) {
        String newPath = orchestrator.regenerateSceneImage(id, seq);
        return ResponseEntity.ok(Map.of(
                "id", id.toString(),
                "seq", seq,
                "imagePath", newPath,
                "result", "REGENERATED"
        ));
    }

    /** Edit a scene's visual description and regenerate its image from the new
     *  text. Body: {"visualDesc": "..."}. */
    @PostMapping("/scenes/{seq}/edit")
    public ResponseEntity<Map<String, Object>> editScene(@PathVariable UUID id, @PathVariable int seq,
                                                         @RequestBody Map<String, String> body) {
        String newPath = orchestrator.editSceneAndRegenerate(id, seq,
                body == null ? null : body.get("visualDesc"));
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "imagePath", newPath, "result", "EDITED"));
    }

    /** Edit a scene's dialogue and re-voice ONLY that scene. Body:
     *  {"dialogue": "pip: Hi!\nmo: Look..."}. Updates narration + subtitles too. */
    @PostMapping("/scenes/{seq}/edit-dialogue")
    public ResponseEntity<Map<String, Object>> editDialogue(@PathVariable UUID id, @PathVariable int seq,
                                                            @RequestBody Map<String, String> body) {
        String audio = orchestrator.editSceneDialogueAndRegenerate(id, seq,
                body == null ? null : body.get("dialogue"));
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "audioPath", audio == null ? "" : audio, "result", "DIALOGUE_EDITED"));
    }

    /** Generate (or refresh) the directed END-still for this scene on demand, so
     *  the start→end pair shows in the UI. Body (optional): {"endPose": "..."}. */
    @PostMapping("/scenes/{seq}/end-still")
    public ResponseEntity<Map<String, Object>> endStill(@PathVariable UUID id, @PathVariable int seq,
                                                        @RequestBody(required = false) Map<String, String> body) {
        String newPath = orchestrator.generateEndStillFor(id, seq,
                body == null ? null : body.get("endPose"));
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "imagePath", newPath, "result", "END_STILL_GENERATED"));
    }

    /** Re-roll ONLY this scene's VEO clip (1 clip = 1 VEO cost) and re-assemble,
     *  reusing every other clip/image. Optional ?model= overrides the Veo model
     *  for this re-roll only (e.g. "veo3_1" premium 1080p). */
    @PostMapping("/scenes/{seq}/reroll-veo")
    public ResponseEntity<Map<String, Object>> rerollVeo(@PathVariable UUID id, @PathVariable int seq,
                                                         @RequestParam(required = false) String model) {
        return ResponseEntity.ok(orchestrator.rerollVeoScene(id, seq, model));
    }

    /** Generate a NEW still for this scene, then (for Veo jobs) re-roll its clip
     *  from that still and re-assemble. The fix for ONE weak image in a finished
     *  video. Body (optional): {"visualDesc": "...", "model": "veo3_1"}. */
    @PostMapping("/scenes/{seq}/regen-clip")
    public ResponseEntity<Map<String, Object>> regenClip(@PathVariable UUID id, @PathVariable int seq,
                                                         @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(orchestrator.regenAndRerollScene(id, seq,
                body == null ? null : body.get("visualDesc"),
                body == null ? null : body.get("model")));
    }

    /** Regenerate the thumbnail (3 fresh variants) steered by a free-text
     *  reviewer direction, e.g. {"hint": "exactly three chicks, no extra
     *  chickens in the background"}. Synchronous (minutes); 1 thumbnail cost.
     *  The variant images refresh in place — re-fetch the PNGs afterwards. */
    @PostMapping("/thumbnail/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateThumbnail(@PathVariable UUID id,
                                                                   @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(orchestrator.regenerateThumbnail(id,
                body == null ? null : body.get("hint")));
    }

    @PostMapping("/scenes/{seq}/lock")
    public ResponseEntity<Map<String, Object>> lockScene(@PathVariable UUID id, @PathVariable int seq) {
        orchestrator.lockScene(id, seq);
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq, "result", "LOCKED"));
    }

    @PostMapping("/scenes/{seq}/unlock")
    public ResponseEntity<Map<String, Object>> unlockScene(@PathVariable UUID id, @PathVariable int seq) {
        orchestrator.unlockScene(id, seq);
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq, "result", "UNLOCKED"));
    }

    @PostMapping("/lock-all")
    public ResponseEntity<Map<String, String>> lockAll(@PathVariable UUID id) { return doLockAll(id); }

    /** Permanent delete: row + workdir on disk. Used by the dashboard. */
    @DeleteMapping
    public ResponseEntity<Map<String, String>> delete(@PathVariable UUID id) {
        orchestrator.deleteJob(id);
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "DELETED"));
    }

    /** Retry just the upload step on a failed job — skips all upstream work. */
    @PostMapping("/retry-upload")
    public ResponseEntity<Map<String, String>> retryUpload(@PathVariable UUID id) {
        orchestrator.retryUpload(id);
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "UPLOAD_RETRY_QUEUED"));
    }

    /** Trigger (or re-run) the AI critic audit on a finished master.
     *  Synchronous — typically 15-30s with 8 keyframes through Claude vision. */
    @PostMapping("/audit")
    public ResponseEntity<Map<String, Object>> runAudit(@PathVariable UUID id) {
        var audit = qualityReviewer.auditJob(id);
        if (audit == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "id", id.toString(),
                    "error", "Audit failed — see server log (likely missing video or Claude API issue)."));
        }
        return ResponseEntity.ok(Map.of(
                "id", id.toString(),
                "score", audit.getScore(),
                "framesInspected", audit.getFramesInspected() == null ? 0 : audit.getFramesInspected()));
    }

    /** Update planning fields (planned publish date, series, episode #, privacy). */
    public record PlanUpdate(java.time.OffsetDateTime plannedPublishAt,
                              String seriesId, Integer episodeNumber,
                              Boolean clearPlanned, String privacyStatus) {}

    @PatchMapping("/plan")
    public ResponseEntity<Map<String, String>> updatePlan(
            @PathVariable UUID id, @RequestBody PlanUpdate body) {
        orchestrator.updatePlanning(id,
                body.plannedPublishAt(),
                body.seriesId(),
                body.episodeNumber(),
                Boolean.TRUE.equals(body.clearPlanned()),
                body.privacyStatus());
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "PLAN_UPDATED"));
    }

    private ResponseEntity<Map<String, String>> doLockAll(UUID id) {
        orchestrator.lockAllAndContinue(id);
        return ResponseEntity.ok(Map.of("id", id.toString(), "result", "ALL_LOCKED",
                "message", "All scenes locked. Pipeline continues."));
    }
}
