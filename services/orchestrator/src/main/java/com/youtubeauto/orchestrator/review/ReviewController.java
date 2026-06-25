package com.youtubeauto.orchestrator.review;

import com.youtubeauto.orchestrator.service.PipelineOrchestrator;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
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

    /** Regenerate a scene's still. Optional body {"correctionHint": "..."} (also
     *  accepts the shorthand key "hint") steers the re-roll toward fixing a
     *  specific problem; absent/blank → the legacy blind re-roll. */
    @PostMapping("/scenes/{seq}/regenerate")
    public ResponseEntity<Map<String, Object>> regenerateScene(@PathVariable UUID id, @PathVariable int seq,
                                                              @RequestBody(required = false) Map<String, String> body) {
        String hint = body == null ? null
                : (body.get("correctionHint") != null ? body.get("correctionHint") : body.get("hint"));
        String newPath = orchestrator.regenerateSceneImage(id, seq, hint);
        return ResponseEntity.ok(Map.of(
                "id", id.toString(),
                "seq", seq,
                "imagePath", newPath,
                "result", "REGENERATED"
        ));
    }

    /** Read-only: a suggested correction hint to PRE-FILL the per-scene Regen
     *  prompt, assembled from the best feedback the system already has for this
     *  scene (persisted QC issue > AI-Critic finding naming a character in the
     *  scene > QA-Board axis nudge). Best-effort — no feedback → {"hint": ""}.
     *  Never 500s on a missing audit/board. */
    @GetMapping("/scenes/{seq}/regen-hint")
    public ResponseEntity<Map<String, Object>> regenHint(@PathVariable UUID id, @PathVariable int seq) {
        String hint;
        try { hint = orchestrator.regenHint(id, seq); }
        catch (Exception e) { hint = ""; }
        return ResponseEntity.ok(Map.of("hint", hint == null ? "" : hint));
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

    /** SAVE-ONLY dialogue edit (2026-06-25, Auke). Stores the per-scene dialogue
     *  lines from a plain-text body and does NOT call any voice/API. With Omni
     *  native audio the spoken words come from the clip; this just updates the
     *  prompt/script so the NEXT clip you make speaks the new lines. Re-assemble /
     *  re-make the clip to hear it. Body: {"dialogue": "pip: Hi!\nmo: Look..."};
     *  one "speaker: text" per line, empty body clears the dialogue (silent beat). */
    @PostMapping("/scenes/{seq}/edit-dialogue")
    public ResponseEntity<Map<String, Object>> editDialogue(@PathVariable UUID id, @PathVariable int seq,
                                                            @RequestBody(required = false) Map<String, String> body) {
        orchestrator.editSceneDialogue(id, seq, body == null ? null : body.get("dialogue"));
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq, "result", "DIALOGUE_SAVED"));
    }

    /** One-click subtitle fix: save the corrected dialogue for a scene AND re-burn
     *  the subtitle so the fix is immediately visible in the master. Reuses the
     *  existing clips/voice/music/thumbnail/metadata — no paid re-voice or
     *  re-clip; only the on-screen caption changes. Synchronous (re-assembles,
     *  minutes). Body: {"dialogue": "pip: Hi!\nmo: Look..."}. */
    @PostMapping("/scenes/{seq}/fix-subtitle")
    public ResponseEntity<Map<String, Object>> fixSubtitle(@PathVariable UUID id, @PathVariable int seq,
                                                           @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(orchestrator.editSceneDialogueAndReburn(
                id, seq, body == null ? null : body.get("dialogue")));
    }

    /** Re-burn the subtitles for the whole job from the CURRENT scene narration
     *  (after one or more save-only dialogue edits). Reuses everything — no paid
     *  re-voice/re-clip/thumbnail. Synchronous (re-assembles, minutes). */
    @PostMapping("/reburn-subtitles")
    public ResponseEntity<Map<String, Object>> reburnSubtitles(@PathVariable UUID id) {
        return ResponseEntity.ok(orchestrator.reburnSubtitles(id));
    }

    /** Set (or clear) a per-scene Veo camera override. Body:
     *  {"veoCameraOverride": "low-angle, 35mm, slow drift"}; empty/absent clears
     *  it. Replaces the phase-default Camera line for this scene only; takes
     *  effect on the NEXT clip re-roll (the still is unchanged). */
    @PostMapping("/scenes/{seq}/camera-override")
    public ResponseEntity<Map<String, Object>> setCameraOverride(@PathVariable UUID id, @PathVariable int seq,
                                                                 @RequestBody(required = false) Map<String, String> body) {
        String value = body == null ? null : body.get("veoCameraOverride");
        orchestrator.setCameraOverride(id, seq, value);
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "veoCameraOverride", value == null ? "" : value, "result", "CAMERA_OVERRIDE_SET"));
    }

    /** Trim a scene to an in/out window. Body: {"startSec": 1.5, "endSec": 7.0}
     *  (seconds within the clip). The montage renders [start, end] — seeks to
     *  start and runs for (end-start)s. Minimum 2s window. Takes effect on the
     *  next Re-assemble. */
    @PostMapping("/scenes/{seq}/trim")
    public ResponseEntity<Map<String, Object>> trimScene(@PathVariable UUID id, @PathVariable int seq,
                                                         @RequestBody Map<String, Number> body) {
        double start = body == null || body.get("startSec") == null ? 0.0 : body.get("startSec").doubleValue();
        double end = body == null || body.get("endSec") == null ? 0.0 : body.get("endSec").doubleValue();
        orchestrator.setSceneTrim(id, seq, start, end);
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "startSec", start, "endSec", end, "result", "TRIMMED"));
    }

    /** Set (or clear) the transition INTO this scene (the boundary before it).
     *  Body: {"type": "wipeleft", "seconds": 0.4}; type "cut" = hard cut, blank/absent
     *  clears it to the phase-default. Takes effect on the next Re-assemble. */
    @PostMapping("/scenes/{seq}/transition")
    public ResponseEntity<Map<String, Object>> setTransition(@PathVariable UUID id, @PathVariable int seq,
                                                             @RequestBody(required = false) Map<String, Object> body) {
        String type = body == null || body.get("type") == null ? null : String.valueOf(body.get("type"));
        Double seconds = null;
        Object sec = body == null ? null : body.get("seconds");
        if (sec instanceof Number n) seconds = n.doubleValue();
        orchestrator.setSceneTransition(id, seq, type, seconds);
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "type", type == null ? "" : type, "result", "TRANSITION_SET"));
    }

    /** Set the cast for this scene (single source of truth). Body:
     *  {"characters": ["pip","bo"]}. The cast drives the still ("exactly N
     *  chicks"), the Veo cast-lock AND the vision-QC, so correcting it here keeps
     *  all three consistent. Takes effect on the next still-regen / clip re-roll;
     *  unlocks the scene. Use when the stored cast includes someone who is not in
     *  the shot (Veo would otherwise try to cram them in). */
    @PostMapping("/scenes/{seq}/characters")
    public ResponseEntity<Map<String, Object>> setSceneCharacters(@PathVariable UUID id, @PathVariable int seq,
                                                                  @RequestBody Map<String, List<String>> body) {
        List<String> chars = body == null ? null : body.get("characters");
        orchestrator.setSceneCharacters(id, seq, chars);
        return ResponseEntity.ok(Map.of("id", id.toString(), "seq", seq,
                "characters", chars == null ? List.of() : chars, "result", "CHARACTERS_SET"));
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

    /** Re-roll ONLY this scene's VEO clip (1 clip = 1 VEO cost), reusing every
     *  other clip/image. Optional ?model= overrides the Veo model for this
     *  re-roll only (e.g. "veo3_1" premium 1080p). By default this does NOT
     *  hermonteren — the clip is saved and the user presses Re-assemble once
     *  after rolling all the scenes they want. Pass ?assemble=true to keep the
     *  old behaviour (re-assemble immediately after the last in-flight reroll). */
    @PostMapping("/scenes/{seq}/reroll-veo")
    public ResponseEntity<Map<String, Object>> rerollVeo(@PathVariable UUID id, @PathVariable int seq,
                                                         @RequestParam(required = false) String model,
                                                         @RequestParam(required = false, defaultValue = "false") boolean assemble) {
        return ResponseEntity.ok(orchestrator.rerollVeoScene(id, seq, model, assemble));
    }

    /** Generate a NEW still for this scene, then (for Veo jobs) re-roll its clip
     *  from that still. Does NOT auto-assemble (gebruikerswens 2026-06-14): the
     *  new still + clip are saved and the user presses Re-assemble once after all
     *  edits. The fix for ONE weak image. Body (optional): {"visualDesc": "...",
     *  "model": "veo3_1"}. */
    @PostMapping("/scenes/{seq}/regen-clip")
    public ResponseEntity<Map<String, Object>> regenClip(@PathVariable UUID id, @PathVariable int seq,
                                                         @RequestBody(required = false) Map<String, String> body) {
        return ResponseEntity.ok(orchestrator.regenAndRerollScene(id, seq,
                body == null ? null : body.get("visualDesc"),
                body == null ? null : body.get("model")));
    }

    /** QC-override: gebruik alsnog de door de clip-QC AFGEKEURDE Veo-clip van
     *  deze scène (clip.rejected.mp4) — promoveert 'm terug naar clip.mp4, zet
     *  clipPath weer op de scène, wist de QC-reden en hermonteert. Voor wanneer
     *  de reviewer oordeelt dat de QC ten onrechte afkeurde. Geen Veo-kosten. */
    @PostMapping("/scenes/{seq}/accept-rejected-clip")
    public ResponseEntity<Map<String, Object>> acceptRejectedClip(@PathVariable UUID id, @PathVariable int seq) {
        return ResponseEntity.ok(orchestrator.acceptRejectedClip(id, seq));
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

    /** Preview-only: the assembled thumbnail prompt(s) per variant (no image
     *  generation). Drives the dashboard "thumbnail-prompt" copy view, the same
     *  way the scene image-/Veo-prompts are exposed. */
    @GetMapping("/thumbnail-prompt")
    public ResponseEntity<com.fasterxml.jackson.databind.JsonNode> thumbnailPrompt(@PathVariable UUID id) {
        return ResponseEntity.ok(orchestrator.thumbnailPromptPreview(id));
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
