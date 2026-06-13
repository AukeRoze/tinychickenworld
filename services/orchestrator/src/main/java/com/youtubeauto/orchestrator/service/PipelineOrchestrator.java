package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.api.dto.CreateVideoRequest;
import com.youtubeauto.orchestrator.api.dto.VideoJobResponse;
import com.youtubeauto.orchestrator.client.*;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.SceneDto;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.review.ReviewConfigLoader;
import com.youtubeauto.orchestrator.review.QaBoard;
import com.youtubeauto.orchestrator.review.ReviewProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.TreeSet;

/**
 * Resumable, gated pipeline.
 *
 *   topic
 *     ─▶ runScriptStage    ── gate: afterScript
 *     ─▶ runAssetsStage    ── gate: afterAssets / beforeVeo
 *     ─▶ runVeoStage       (optional, motionMode=veo)
 *     ─▶ runAssemblyStage  ── gate: beforeUpload
 *     ─▶ runUploadStage    ─▶ COMPLETED
 *
 * Each stage is independently @Async on the pipelineExecutor. When a gate
 * is configured (bible.review.*), the stage flips the job to *_REVIEW_PENDING,
 * fires an approval mail, and returns. A subsequent /approve call invokes
 * advance(jobId) which dispatches to the next stage based on current status.
 *
 * Intermediate state survives JVM restarts via VideoJob.assemblyScenesJson
 * (jsonb) plus the persisted request inputs on VideoJob.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PipelineOrchestrator {

    private final VideoJobRepository repo;
    private final ScriptServiceClient scriptClient;
    private final VoiceServiceClient voiceClient;
    private final ImageServiceClient imageClient;
    private final VideoGenerationServiceClient videoGenClient;
    private final AssemblyServiceClient assemblyClient;
    private final UploadServiceClient uploadClient;
    private final ThumbnailServiceClient thumbnailClient;
    private final PropAnchorService propAnchorService;
    private final MetadataGenerator metadata;
    private final MetadataPolicy metadataPolicy;
    private final LyricsGenerator lyricsGenerator;
    private final QualityReviewer qualityReviewer;
    private final com.youtubeauto.orchestrator.review.QaBoard qaBoard;
    private final SceneImageQc sceneImageQc;
    private final ClipQc clipQc;
    private final ThumbnailQc thumbnailQc;
    private final QcInsights qcInsights;
    private final com.youtubeauto.orchestrator.repository.VideoAuditRepository auditRepo;
    private final SeriesContinuity seriesContinuity;
    private final InsightsAggregator insights;
    private final PerformanceLoop performanceLoop;
    private final OrchestratorProperties props;
    private final VeoPromptCompiler veoPromptCompiler;
    private final ReviewConfigLoader reviewConfig;
    private final ObjectMapper mapper = new ObjectMapper();

    /** QA Board publish gate. When enabled, a master scoring below the gate (or
     *  flagged unsafe) is held for human review instead of auto-uploaded. */
    @org.springframework.beans.factory.annotation.Value("${qa.gate.enabled:true}")
    private boolean qaGateEnabled;

    /** Dedicated manual review gate for the thumbnail, between assembly and the
     *  Planning/publish gate. Off → assembly flows straight to the publish gate
     *  (legacy behaviour). */
    @org.springframework.beans.factory.annotation.Value("${pipeline.thumbnail-gate.enabled:true}")
    private boolean thumbnailGateEnabled;

    /** Generate an original Suno instrumental per normal episode (needs a
     *  SUNO_API_KEY in the assembly-service). Off (default) → use the bible/music
     *  royalty-free library. Flip on (env PIPELINE_SUNO_INSTRUMENTAL=true) once a
     *  Suno provider is wired. */
    @org.springframework.beans.factory.annotation.Value("${pipeline.suno-instrumental:false}")
    private boolean sunoInstrumental;

    /** Dedicated manual distribution gate after the YouTube upload. Off → upload
     *  completes the job immediately (legacy behaviour, zero-touch). */
    @org.springframework.beans.factory.annotation.Value("${pipeline.distribution-gate.enabled:true}")
    private boolean distributionGateEnabled;

    /** Series anchors (Series-ConsistencyState, backlog P3): on a successful
     *  upload a series job's episode character anchors are PROMOTED to the
     *  durable {bible-dir}/refs/series/{seriesId}/ folder, and the NEXT
     *  episode's first image batch is seeded from that folder — so the canon
     *  carries across episodes, not just within one. Off → both promotion and
     *  seeding are skipped; per-episode Story B behaviour is untouched. (In
     *  plain unit tests without Spring this field keeps its Java default
     *  false — an extra no-op guarantee on top of the tests' seriesId=null.) */
    @org.springframework.beans.factory.annotation.Value("${app.series.anchors-enabled:true}")
    private boolean seriesAnchorsEnabled;

    /** Frame-chaining: consecutive same-location/same-cast Veo scenes render
     *  sequentially, each starting on the previous clip's extracted last frame
     *  (pixel-level shot continuity). Off → all scenes render independently in
     *  parallel (legacy behaviour). */
    @org.springframework.beans.factory.annotation.Value("${pipeline.frame-chaining.enabled:true}")
    private boolean frameChainingEnabled;

    // Auto vision-QC of scene images before montage/Veo.
    @org.springframework.beans.factory.annotation.Value("${app.qc.enabled:true}")
    private boolean qcEnabled;
    @org.springframework.beans.factory.annotation.Value("${app.qc.max-rerolls-per-scene:1}")
    private int qcMaxRerollsPerScene;
    @org.springframework.beans.factory.annotation.Value("${app.qc.max-total-rerolls:4}")
    private int qcMaxTotalRerolls;

    // AI-Critic Auto-Fix loop caps.
    @org.springframework.beans.factory.annotation.Value("${app.autofix.default-target:90}")
    private int autofixDefaultTarget;
    @org.springframework.beans.factory.annotation.Value("${app.autofix.max-iterations:3}")
    private int autofixMaxIterations;
    @org.springframework.beans.factory.annotation.Value("${app.autofix.max-rerolls:8}")
    private int autofixMaxRerolls;
    /** Per-axis lever threshold for the extended Auto-Fix. QA Board axes are
     *  /10; this threshold is on the /100 scale (axis×10 &lt; threshold ⇒ the
     *  axis lever fires). 0 disables every axis lever — which is also the
     *  implicit value in plain unit tests without a Spring context. */
    @org.springframework.beans.factory.annotation.Value("${app.autofix.axis-threshold:70}")
    private int autofixAxisThreshold;

    /** Self-reference so stage-to-stage calls go through the @Async proxy
     *  (Spring otherwise short-circuits self-invocations). */
    @Autowired @Lazy private PipelineOrchestrator self;

    // ─────────────────────────── ENTRY POINTS ───────────────────────────

    public VideoJobResponse submit(CreateVideoRequest req) {
        // NOT @Transactional — we want repo.save() to commit IMMEDIATELY so
        // the async runScriptStage() can find the job. With @Transactional
        // around submit(), the INSERT only commits when submit returns; the
        // async stage fires earlier and tries to load a row that's still
        // inside the pending transaction → IllegalArgumentException.
        VideoJob job = self.saveNewJob(req);   // through proxy → @Transactional commits before we return
        self.runScriptStage(job.getId());      // through proxy → @Async fires only after commit
        return toResponse(job);
    }

    /** Save the new job in its own small transaction so the INSERT commits
     *  before the async script stage tries to load it. */
    @Transactional
    public VideoJob saveNewJob(CreateVideoRequest req) {
        String motionMode = req.motionMode() != null && !req.motionMode().isBlank()
                ? req.motionMode()
                : (props.defaults().motionMode() != null ? props.defaults().motionMode() : "ken_burns");
        VideoJob job = VideoJob.builder()
                .topic(req.topic())
                .brief(req.brief())
                .lesson(req.lesson())
                .mood(req.mood())
                .angle(req.angle())
                .hook(req.hook())
                .plannedPublishAt(req.plannedPublishAt())
                .seriesId(req.seriesId())
                .episodeNumber(req.episodeNumber())
                .audience(req.audience() != null ? req.audience() : props.defaults().audience())
                .targetSeconds(req.targetSeconds() != null ? req.targetSeconds() : props.defaults().targetSeconds())
                .format(req.format())
                .motionMode(motionMode)
                .veoModel(req.veoModel())
                .burnSubtitles(req.burnSubtitles() != null ? req.burnSubtitles() : props.defaults().burnSubtitles())
                .privacyStatus(req.privacyStatus() != null ? req.privacyStatus() : "private")
                .backgroundMusicPath(req.backgroundMusicPath())
                .reuseImagesFromJob(req.reuseImagesFromJob())
                .recurringMotif(req.recurringMotif())
                .status(JobStatus.PENDING)
                .build();
        return repo.save(job);
    }

    /** Called by ReviewController on POST /approve. Dispatches to next stage. */
    public void approve(UUID jobId) {
        VideoJob job = load(jobId);
        switch (job.getStatus()) {
            case SCRIPT_REVIEW_PENDING -> self.runAssetsStage(jobId);
            case IMAGES_REVIEW_PENDING -> {
                if (usesVeo(job.getMotionMode())) self.runVeoStage(jobId);
                else                              self.runAssemblyStage(jobId);
            }
            case ASSETS_REVIEW_PENDING -> {
                if (usesVeo(job.getMotionMode())) self.runVeoStage(jobId);
                else                              self.runAssemblyStage(jobId);
            }
            case VEO_REVIEW_PENDING    -> self.runVeoStage(jobId);
            case THUMBNAIL_REVIEW_PENDING -> self.runUploadGate(jobId);
            case UPLOAD_REVIEW_PENDING -> self.runUploadStage(jobId);
            case DISTRIBUTION_PENDING  -> self.finalizeDistribution(jobId);
            default -> throw new IllegalStateException(
                    "Job " + jobId + " is not awaiting review (status=" + job.getStatus() + ")");
        }
    }

    /**
     * Retry a FAILED job, resuming from the stage that failed instead of
     * restarting. Whatever already succeeded is reused: a generated script,
     * scene images + voice, or even the assembled master. This avoids paying
     * again for script/image generation when the failure was a downstream
     * (e.g. assembly) bug.
     */
    public void retry(UUID jobId) {
        VideoJob job = load(jobId);
        if (job.getStatus() != JobStatus.FAILED) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not FAILED (status=" + job.getStatus()
                    + "); retry only resumes a failed job.");
        }
        JobStatus resume = resumePoint(job);
        log.info("Job {} retry → resuming from {} (previous error: {})",
                jobId, resume, job.getError());
        clearError(jobId);
        switch (resume) {
            case SCRIPT_GENERATING -> self.runScriptStage(jobId);
            case ASSETS_GENERATING -> self.runAssetsStage(jobId);
            case VEO_GENERATING    -> self.runVeoStage(jobId);
            case ASSEMBLING        -> self.runAssemblyStage(jobId);
            case UPLOADING         -> self.runUploadStage(jobId);
            default                -> self.runScriptStage(jobId);
        }
    }

    /**
     * P4 — crash-recovery hook. Re-triggers the stage a job was in when the JVM
     * stopped. Each stage reloads/reuses whatever assets already exist, so
     * restarting the current stage is idempotent and safe (same contract the
     * {@code retry} path relies on). Called by {@link JobRecovery} on startup;
     * terminal and review-pending states are filtered out before we get here.
     */
    public void resumeAfterRestart(UUID jobId, JobStatus status) {
        switch (status) {
            case PENDING, SCRIPT_GENERATING -> self.runScriptStage(jobId);
            case ASSETS_GENERATING          -> self.runAssetsStage(jobId);
            case VEO_GENERATING             -> self.runVeoStage(jobId);
            case ASSEMBLING                 -> self.runAssemblyStage(jobId);
            case UPLOADING                  -> self.runUploadStage(jobId);
            default -> log.warn("resumeAfterRestart: {} is not an in-flight stage — skipping job {}",
                    status, jobId);
        }
    }

    /**
     * Re-assemble a job from its EXISTING assets — same script, scene images and
     * voice, nothing regenerated. Used to apply assembly / outro / thumbnail
     * improvements to an already-finished (or review-pending) video at no
     * content-generation cost. Re-runs metadata + thumbnail too so those
     * improvements land, then pauses at the upload-review gate like a normal run.
     */
    public void reassemble(UUID jobId) {
        VideoJob job = load(jobId);
        List<SceneDto> scenes = loadAssemblyScenes(job);
        if (!assetsComplete(scenes)) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no complete scene assets to re-assemble.");
        }
        log.info("Job {} re-assemble requested (status={}) — reusing existing script/images/audio",
                jobId, job.getStatus());
        clearError(jobId);
        self.runAssemblyStage(jobId);
    }

    /**
     * 🔁 Re-render visuals (new cast) — rebuild the SAME video after a
     * character redesign: identical script and voices, every visual fresh so
     * the new images anchor on the redesigned character refs.
     *
     * <p>Per scene we clear {@code imagePath}, {@code clipPath} and
     * {@code endImagePath} (SceneDto is NON_NULL, so a null setter removes the
     * key — the runAssetsStage reuse block then sees "no image" and
     * regenerates). {@code audioPath} is deliberately KEPT: the voices don't
     * change on a cast redesign, and the same reuse block skips re-voicing a
     * scene whose audio file still exists.
     *
     * <p>LOCKED scenes are re-rendered too — deliberately. A reviewer lock
     * means "this still is approved", but a cast redesign makes every old
     * still stale by definition: keeping a locked still would mix the old and
     * the new cast in one video. The locked set is therefore cleared so the
     * reviewer re-approves the fresh stills at the image gate.
     *
     * <p>Job-level stale state cleared in the same write:
     * {@code episodeAnchorsJson} (the per-episode canon — it points at
     * OLD-cast stills and would otherwise seed the new batch with the old
     * look) and {@code thumbnailPath} (composed from old stills; the assembly
     * stage regenerates it). After that the pipeline restarts at the assets
     * stage via the {@code self.}-dispatch (same async pattern as
     * {@link #retry}/{@link #resumeAfterRestart}); QC + review gates run as
     * normal, {@code collectEpisodeAnchors} elects a NEW post-QC canon from
     * the fresh stills, and for series jobs the promoted series anchors of the
     * new cast (if present) seed the first batch.
     *
     * <p>Guard: only allowed while the job is NOT mid-stage — any review-pending
     * status, COMPLETED or FAILED is fine; an active generate/assemble/upload
     * status throws (same style as the approve/retry guards).
     */
    public void rerenderVisuals(UUID jobId) {
        VideoJob job = load(jobId);
        JobStatus st = job.getStatus();
        if (!st.isAwaitingReview() && !st.isTerminal()) {
            throw new IllegalStateException(
                    "Job " + jobId + " is mid-stage (status=" + st + "); re-render visuals "
                    + "needs a paused (review-pending), COMPLETED or FAILED job.");
        }
        if (job.getScriptJobId() == null) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no script yet — nothing to re-render.");
        }
        List<SceneDto> scenes = loadAssemblyScenes(job);
        if (scenes.isEmpty()) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no scenes yet — nothing to re-render.");
        }
        for (SceneDto s : scenes) {
            s.setImagePath(null);     // fresh still per scene (key disappears)
            s.setClipPath(null);      // old-cast Veo clip is stale
            s.setEndImagePath(null);  // old-cast directed end-still is stale
        }
        final String scenesJson = serialise(scenes);
        retryOnConflict(jobId, j -> {
            j.setAssemblyScenesJson(scenesJson);
            j.setError(null);
            j.setEpisodeAnchorsJson(null);   // old-cast canon must not seed the new batch
            j.setThumbnailPath(null);        // composed from old stills; assembly regenerates
            j.setLockedSceneSeqs(null);      // every still changes → reviewer re-approves
            // Feature-B copy path would re-copy the OLD-cast images from the
            // source job's workdir instead of generating fresh ones — exactly
            // what a re-render must not do. One-shot clear, this job only.
            j.setReuseImagesFromJob(null);
        });
        log.info("Job {} re-render visuals (new cast): cleared {} scene image/clip/end-still "
                + "paths + episode anchors + thumbnail (voices kept) — restarting assets stage",
                jobId, scenes.size());
        mark(jobId, JobStatus.ASSETS_GENERATING, "re-render visuals (new cast) queued");
        self.runAssetsStage(jobId);
    }

    /**
     * 🎬 Generate a Veo clip for EVERY scene that doesn't have one yet ("alles
     * bewegend"). Flips the job to {@code motionMode=veo} so {@link #runVeoStage}
     * takes ALL scenes as candidates (not just the hook/climax hero beats that
     * {@code hybrid} routes), then restarts the Veo stage exactly like
     * {@link #retry}/{@link #resumeAfterRestart}/{@link #rerenderVisuals} do —
     * via {@code mark(...) + self.runVeoStage(jobId)}. The stage ends in assembly
     * on its own (no dedicated post-Veo gate).
     *
     * <p>COST-CRITICAL — does NOT regenerate clips you already have. The scene
     * images and voices are reused untouched, and {@code clipPath}s are
     * deliberately KEPT: {@link #runVeoStage} now skips every scene whose clip
     * file still exists on disk (see the skip block there), so this only pays for
     * the MISSING clips. A hybrid episode that already has its hook+climax clips
     * therefore only fills in the remaining setup/development/resolution scenes;
     * a ken_burns episode generates one clip per scene.
     *
     * <p>NB — {@code runVeoStage}/{@code buildVeoScenes} do NOT inherently skip
     * existing clips: {@code buildVeoScenes} builds a request for every candidate.
     * The skip is enforced by the explicit on-disk-clip filter added to
     * {@code runVeoStage}; without it this method would re-pay for clips that are
     * already fine. The skip is a no-op for a normal first Veo run (no clips yet)
     * and for re-render (clips cleared first), so existing behaviour is unchanged.
     *
     * <p>Guard: same safe-status set as {@link #rerenderVisuals}/{@code approve} —
     * any review-pending status, COMPLETED or FAILED. A mid-stage job throws
     * IllegalStateException.
     */
    public void generateAllClips(UUID jobId) {
        VideoJob job = load(jobId);
        JobStatus st = job.getStatus();
        if (!st.isAwaitingReview() && !st.isTerminal()) {
            throw new IllegalStateException(
                    "Job " + jobId + " is mid-stage (status=" + st + "); generate-all-clips "
                    + "needs a paused (review-pending), COMPLETED or FAILED job.");
        }
        List<SceneDto> scenes = loadAssemblyScenes(job);
        if (scenes.isEmpty()) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no scenes yet — nothing to animate.");
        }
        if (!assetsComplete(scenes)) {
            throw new IllegalStateException(
                    "Job " + jobId + " has incomplete scene assets (image + voice) — "
                    + "generate the storyboard + voices before animating every scene.");
        }
        long already = scenes.stream().filter(SceneDto::hasClip).count();
        // Flip to full-Veo so buildVeoScenes takes ALL scenes as candidates.
        // clipPaths are KEPT — runVeoStage skips scenes whose clip exists on disk.
        retryOnConflict(jobId, j -> {
            j.setMotionMode("veo");
            j.setError(null);
        });
        log.info("Job {} generate-all-clips: motionMode→veo, {} of {} scenes already have a clip "
                + "(kept); restarting Veo stage to fill the remaining {} scene(s)",
                jobId, already, scenes.size(), scenes.size() - already);
        mark(jobId, JobStatus.VEO_GENERATING, "alle scènes → Veo");
        self.runVeoStage(jobId);
    }

    /** Determines the earliest stage that still has work left, based on which
     *  artifacts already exist. Used by {@link #retry}. */
    private JobStatus resumePoint(VideoJob job) {
        // No script yet → start from the beginning.
        if (job.getScriptId() == null) return JobStatus.SCRIPT_GENERATING;
        List<SceneDto> scenes = loadAssemblyScenes(job);
        if (scenes.isEmpty()) return JobStatus.SCRIPT_GENERATING;
        // Any scene missing its image or voice → (re)run assets. The assets
        // stage itself reuses the scenes that DO already have files.
        if (!assetsComplete(scenes)) return JobStatus.ASSETS_GENERATING;
        // Veo mode but no motion clips yet → run Veo.
        if (usesVeo(job.getMotionMode()) && !veoComplete(scenes)) {
            return JobStatus.VEO_GENERATING;
        }
        // Assets done but no master MP4 → assemble.
        if (job.getVideoPath() == null || job.getVideoPath().isBlank()) {
            return JobStatus.ASSEMBLING;
        }
        // Everything but the upload succeeded.
        return JobStatus.UPLOADING;
    }

    /** True when every scene already has an image AND a voice file on disk. */
    private boolean assetsComplete(List<SceneDto> scenes) {
        if (scenes.isEmpty()) return false;
        for (SceneDto s : scenes) {
            if (!fileExists(s.getImagePath())) return false;
            if (!fileExists(s.getAudioPath())) return false;
        }
        return true;
    }

    /** True when at least one scene already has a Veo clip (good enough to skip
     *  the Veo stage on retry; per-scene Veo failures fall back to Ken Burns). */
    private boolean veoComplete(List<SceneDto> scenes) {
        for (SceneDto s : scenes) {
            if (s.hasClip()) {
                return true;
            }
        }
        return false;
    }

    private boolean fileExists(Object path) {
        if (path == null) return false;
        String p = path.toString();
        return !p.isBlank() && java.nio.file.Files.exists(java.nio.file.Paths.get(p));
    }

    /** Null-safe string: a missing (null) SceneDto field reads as "" — the same
     *  result the legacy {@code String.valueOf(map.getOrDefault(key, ""))} gave
     *  for an absent key. */
    private static String nz(String s) {
        return s == null ? "" : s;
    }

    public void clearError(UUID id) {
        retryOnConflict(id, j -> j.setError(null));
    }

    /** Regenerate the image for a single scene of an IMAGES_REVIEW_PENDING
     *  job. The scene is removed from the locked set. Returns the new
     *  image path. */
    public String regenerateSceneImage(UUID jobId, int seq) {
        return regenerateSceneImage(jobId, seq, null);
    }

    /**
     * Regenerate one scene's image with an optional free-form CORRECTION hint
     * ("no second chicken", "hat a bit smaller", "move the character left").
     * The hint (null/blank = exactly the legacy blind re-roll) rides along as
     * {@code correctionHint} on the scene payload, where the image-service
     * appends it as a high-priority correction clause to the prompt — so a
     * manual re-roll is steered instead of a blind one.
     */
    public String regenerateSceneImage(UUID jobId, int seq, String correctionHint) {
        VideoJob job = load(jobId);
        // Allowed during the image-review step AND on a finished job (so a single
        // weak still can be re-rolled after completion; the video itself only
        // changes after a re-roll/re-assemble — see regenAndRerollScene).
        if (job.getStatus() != JobStatus.IMAGES_REVIEW_PENDING
                && job.getStatus() != JobStatus.COMPLETED) {
            throw new IllegalStateException("Scene image regen needs IMAGES_REVIEW_PENDING or "
                    + "COMPLETED (was " + job.getStatus() + ")");
        }
        VideoFormat format = VideoFormat.parse(job.getFormat());
        List<SceneDto> assembly = loadAssemblyScenes(job);
        SceneDto scene = assembly.stream()
                .filter(a -> a.effectiveSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scene seq " + seq));

        JsonNode scriptBody = scriptClient.get(job.getScriptJobId()).path("script");
        Map<String, Object> imgScene = null;
        for (JsonNode s : scriptBody.path("scenes")) {
            if (s.path("seq").asInt() == seq) {
                List<String> chars = new ArrayList<>();
                for (JsonNode c : s.path("characters")) chars.add(c.asText());
                Map<String, Object> m = new HashMap<>();
                m.put("seq", seq);
                m.put("visualDesc", s.path("visualDesc").asText());
                m.put("characters", chars);
                m.put("locationId", s.path("locationId").asText(""));
                if (correctionHint != null && !correctionHint.isBlank()) {
                    m.put("correctionHint", correctionHint.trim());
                }
                imgScene = m;
                break;
            }
        }
        if (imgScene == null) throw new IllegalStateException("Scene " + seq + " not in script");
        attachEpisodeAnchors(imgScene, job);   // Story B: re-roll matches episode canon

        JsonNode resp = imageClient.generate(jobId, List.of(imgScene), format.imageFormat);
        String newPath = null;
        for (JsonNode n : resp.path("scenes")) {
            if (n.path("seq").asInt() == seq) {
                newPath = n.path("imagePath").asText();
                break;
            }
        }
        if (newPath == null) throw new IllegalStateException("image-service did not return scene " + seq);

        scene.setImagePath(newPath);
        saveAssemblyScenes(jobId, assembly);
        // Removing seq from locked set so the reviewer must re-approve.
        unlockScene(jobId, seq);
        log.info("Job {} scene {} image regenerated{} -> {}", jobId, seq,
                (correctionHint != null && !correctionHint.isBlank())
                        ? " (hint: " + correctionHint.trim() + ")" : "",
                newPath);
        return newPath;
    }

    /**
     * Edit a scene's visual description and regenerate its image from the NEW
     * text. The edited visualDesc is stored on the assembly scene (so it sticks
     * through the final render), the character/location/world fields come from
     * the existing assembly scene, and the new still replaces the old one.
     */
    public String editSceneAndRegenerate(UUID jobId, int seq, String newVisualDesc) {
        VideoJob job = load(jobId);
        // Allowed during image-review AND on a finished job (edit a weak still
        // after completion; the video only changes after a re-roll/re-assemble).
        if (job.getStatus() != JobStatus.IMAGES_REVIEW_PENDING
                && job.getStatus() != JobStatus.COMPLETED) {
            throw new IllegalStateException("Scene edit needs IMAGES_REVIEW_PENDING or "
                    + "COMPLETED (was " + job.getStatus() + ")");
        }
        if (newVisualDesc == null || newVisualDesc.isBlank()) {
            throw new IllegalArgumentException("visualDesc must not be empty");
        }
        VideoFormat format = VideoFormat.parse(job.getFormat());
        List<SceneDto> assembly = loadAssemblyScenes(job);
        SceneDto scene = assembly.stream()
                .filter(a -> a.effectiveSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scene seq " + seq));

        // Persist the edit, then generate the image from the new text using the
        // scene's own cast/location/world fields (+ the per-episode motif).
        scene.setVisualDesc(newVisualDesc.trim());
        Map<String, Object> imgScene = new HashMap<>();
        imgScene.put("seq", seq);
        imgScene.put("visualDesc", withMotif(newVisualDesc.trim(), job));
        imgScene.put("characters", scene.charactersOrEmpty());
        imgScene.put("locationId", nz(scene.getLocationId()));
        imgScene.put("timeOfDay", nz(scene.getTimeOfDay()));
        imgScene.put("weather", nz(scene.getWeather()));
        attachEpisodeAnchors(imgScene, job);   // Story B: edited re-roll matches episode canon

        JsonNode resp = imageClient.generate(jobId, List.of(imgScene), format.imageFormat);
        String newPath = null;
        for (JsonNode n : resp.path("scenes")) {
            if (n.path("seq").asInt() == seq) { newPath = n.path("imagePath").asText(); break; }
        }
        if (newPath == null) throw new IllegalStateException("image-service did not return scene " + seq);

        scene.setImagePath(newPath);
        saveAssemblyScenes(jobId, assembly);
        unlockScene(jobId, seq);
        log.info("Job {} scene {} edited + regenerated -> {}", jobId, seq, newPath);
        return newPath;
    }

    /**
     * Manually generate (or refresh) the directed END-still for a single scene,
     * on demand from the UI — so the start→end pair is visible even on scenes the
     * pipeline didn't auto-pick as hero beats. Locked to the start still's
     * identity/framing (only the pose changes), written as scene_{900+seq}.png so
     * {@code GET /review/images/{id}/file/{900+seq}.png} serves it.
     *
     * @param endPoseOverride optional end pose; when blank the scene's own Shot-DNA
     *        {@code endPose} is used, else a sensible default derived from the scene.
     * @return the generated end-still path.
     */
    public String generateEndStillFor(UUID jobId, int seq, String endPoseOverride) {
        VideoJob job = load(jobId);
        VideoFormat format = VideoFormat.parse(job.getFormat());
        List<SceneDto> assembly = loadAssemblyScenes(job);
        if (assembly.isEmpty()) {
            throw new IllegalStateException("No scenes yet — generate the storyboard first.");
        }
        SceneDto scene = assembly.stream()
                .filter(a -> a.effectiveSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scene seq " + seq));

        // Pick the end pose: explicit override > scene's own endPose > a default
        // that resolves the action so the still is meaningfully different.
        String pose = endPoseOverride == null ? "" : endPoseOverride.trim();
        if (pose.isEmpty() || "null".equals(pose)) {
            pose = nz(scene.getEndPose()).trim();
        }
        if (pose.isEmpty() || "null".equals(pose)) {
            pose = "the natural resolved END of the action in this scene — the "
                    + "character(s) having just completed the movement/gesture, settled "
                    + "and still, same place and framing.";
        }

        // Same identity/framing lock as the auto end-still path (P6).
        String lockedDesc = "Same scene and the EXACT same character(s), outfit, "
                + "colours, accessories, background and camera framing as the start "
                + "image — do NOT change identity, props or composition. ONLY change "
                + "the pose/action to: " + pose;
        Map<String, Object> endScene = new HashMap<>();
        endScene.put("seq", END_STILL_SEQ_OFFSET + seq);
        endScene.put("visualDesc", lockedDesc);
        endScene.put("characters", scene.charactersOrEmpty());
        endScene.put("locationId", nz(scene.getLocationId()));
        endScene.put("timeOfDay", nz(scene.getTimeOfDay()));
        endScene.put("weather", nz(scene.getWeather()));
        endScene.put("cameraFraming", nz(scene.getCameraFraming()));
        attachEpisodeAnchors(endScene, job);   // Story B: end-still matches episode canon

        JsonNode resp = imageClient.generate(jobId, List.of(endScene), format.imageFormat);
        String newPath = null;
        for (JsonNode n : resp.path("scenes")) {
            if (n.path("seq").asInt() == END_STILL_SEQ_OFFSET + seq) {
                newPath = n.path("imagePath").asText("");
                break;
            }
        }
        if (newPath == null || newPath.isBlank()) {
            throw new IllegalStateException("image-service did not return end-still for scene " + seq);
        }
        // Persist the pose on the scene so a later full render reuses this intent.
        scene.setEndPose(pose);
        saveAssemblyScenes(jobId, assembly);
        log.info("Job {} manual end-still for scene {} -> {}", jobId, seq, newPath);
        return newPath;
    }

    /**
     * Edit a scene's dialogue and re-generate ONLY that scene's audio (voice or
     * sounds-mode SFX). Input is "speaker: text" lines (one per line). The new
     * narration is stored on the scene so the burned/soft subtitles update too;
     * the captions SRT is rebuilt at re-assembly from this narration.
     */
    public String editSceneDialogueAndRegenerate(UUID jobId, int seq, String dialogueText) {
        VideoJob job = load(jobId);
        if (job.getStatus() != JobStatus.IMAGES_REVIEW_PENDING) {
            throw new IllegalStateException("Job not in IMAGES_REVIEW_PENDING (was " + job.getStatus() + ")");
        }
        if (dialogueText == null || dialogueText.isBlank()) {
            throw new IllegalArgumentException("dialogue must not be empty");
        }
        List<SceneDto> assembly = loadAssemblyScenes(job);
        SceneDto scene = assembly.stream()
                .filter(a -> a.effectiveSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scene seq " + seq));

        // Parse "speaker: text" lines (default speaker = pip, the host).
        List<Map<String, Object>> lines = new ArrayList<>();
        StringBuilder narration = new StringBuilder();
        for (String raw : dialogueText.split("\\r?\\n")) {
            String line = raw.trim();
            if (line.isEmpty()) continue;
            int colon = line.indexOf(':');
            String speaker, text;
            if (colon > 0 && colon <= 6) {
                speaker = line.substring(0, colon).trim().toLowerCase();
                text = line.substring(colon + 1).trim();
            } else {
                speaker = "pip";
                text = line;
            }
            if (text.isEmpty()) continue;
            Map<String, Object> lm = new HashMap<>();
            lm.put("speaker", speaker);
            lm.put("text", text);
            String sceneEmotion = nz(scene.getEmotion());
            if (sceneEmotion.isBlank() || "null".equals(sceneEmotion)) {
                sceneEmotion = phaseDefaultEmotion(nz(scene.getPhase()));
            }
            lm.put("emotion", sceneEmotion);
            lines.add(lm);
            if (narration.length() > 0) narration.append(' ');
            narration.append(text);
        }
        if (lines.isEmpty()) throw new IllegalArgumentException("No usable dialogue lines");

        scene.setLines(lines);
        scene.setNarration(narration.toString());

        // Re-voice just this scene.
        Map<String, Object> voiceScene = new HashMap<>();
        voiceScene.put("seq", seq);
        voiceScene.put("lines", lines);
        voiceScene.put("locationId", nz(scene.getLocationId()));
        voiceScene.put("weather", nz(scene.getWeather()));
        JsonNode resp = voiceClient.synthesize(jobId, List.of(voiceScene));
        String newAudio = null;
        for (JsonNode n : resp.path("scenes")) {
            if (n.path("seq").asInt() == seq) { newAudio = n.path("audioPath").asText(); break; }
        }
        if (newAudio != null && !newAudio.isBlank()) scene.setAudioPath(newAudio);
        saveAssemblyScenes(jobId, assembly);
        log.info("Job {} scene {} dialogue edited + re-voiced -> {}", jobId, seq, newAudio);
        return newAudio;
    }

    /**
     * Retry the upload stage on a FAILED (or stuck) job without re-running
     * the upstream pipeline. Master MP4 + thumbnail + metadata are already
     * on disk + in the DB row, so we just kick off runUploadStage again.
     * Use this when youtube-upload failed (OAuth, quota, transient network)
     * so the user doesn't pay Veo + Opus + Replicate costs again.
     */
    @Transactional
    public void retryUpload(UUID jobId) {
        VideoJob job = repo.findById(jobId).orElseThrow(
                () -> new IllegalArgumentException("Unknown job " + jobId));
        if (job.getVideoPath() == null || job.getVideoPath().isBlank()) {
            throw new IllegalStateException(
                    "Cannot retry upload: master video path missing (assembly never finished)");
        }
        if (job.getMetadataTitle() == null) {
            throw new IllegalStateException(
                    "Cannot retry upload: metadata not generated yet");
        }
        // Reset error + status, then dispatch async.
        job.setError(null);
        job.setStatus(JobStatus.UPLOAD_REVIEW_PENDING);
        job.setStep("retry queued");
        repo.save(job);
        log.info("Job {} upload retry — kicking off runUploadStage", jobId);
        // Run through @Lazy self so @Async proxy fires properly.
        self.runUploadStage(jobId);
    }

    /**
     * Delete a job: removes the row from video_jobs and best-effort deletes
     * the job's workdir on disk. Permanent — no recovery. Used by the
     * dashboard's delete button when the user wants to prune failed/old runs.
     * Allowed in any state; if you really want to delete a mid-flight job
     * that's a workdir/orphan concern for you, not us.
     */
    @Transactional
    public void deleteJob(UUID jobId) {
        VideoJob job = repo.findById(jobId).orElse(null);
        if (job == null) return;
        repo.delete(job);
        // Best-effort workdir cleanup. Don't fail the delete if the disk
        // bits are gone or locked — the row is gone, that's the contract.
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("/workdir", jobId.toString());
            if (java.nio.file.Files.exists(dir)) {
                try (var stream = java.nio.file.Files.walk(dir)) {
                    stream.sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> { try { java.nio.file.Files.deleteIfExists(p); }
                                            catch (Exception ignore) {} });
                }
            }
        } catch (Exception e) {
            log.warn("Workdir cleanup for job {} hit error: {}", jobId, e.getMessage());
        }
        log.info("Job {} deleted", jobId);
    }

    /**
     * Update planning fields on an existing job. Any field passed in null is
     * left unchanged; pass an empty string to clear seriesId. Used by the
     * dashboard's inline planning section.
     */
    @Transactional
    public void updatePlanning(UUID jobId, java.time.OffsetDateTime plannedPublishAt,
                                String seriesId, Integer episodeNumber, boolean clearPlanned,
                                String privacyStatus) {
        VideoJob job = repo.findById(jobId).orElseThrow(
                () -> new IllegalArgumentException("Unknown job " + jobId));
        if (clearPlanned) {
            job.setPlannedPublishAt(null);
        } else if (plannedPublishAt != null) {
            job.setPlannedPublishAt(plannedPublishAt);
        }
        if (seriesId != null) {
            job.setSeriesId(seriesId.isBlank() ? null : seriesId);
        }
        if (episodeNumber != null) {
            job.setEpisodeNumber(episodeNumber);
        }
        if (privacyStatus != null && !privacyStatus.isBlank()) {
            // Whitelist: only YouTube-valid values.
            if (privacyStatus.equals("private") || privacyStatus.equals("unlisted")
                    || privacyStatus.equals("public")) {
                job.setPrivacyStatus(privacyStatus);
            }
        }
        repo.save(job);
    }

    /**
     * Picks a music track from the bible based on the script's mood string.
     * Maps common mood words to track moods (calm/energetic/thoughtful).
     * Returns the first matching track's path, or null if no bible/no match.
     *
     * The bible YAML structure read here:
     *   music:
     *     tracks:
     *       - id: ...
     *         path: /bible/music/xxx.mp3
     *         mood: calm
     */
    private String autoPickMusic(String moodText) {
        try {
            String biblePath = props.bible().path();
            java.nio.file.Path p = java.nio.file.Paths.get(biblePath);
            if (!java.nio.file.Files.exists(p)) return null;
            com.fasterxml.jackson.databind.JsonNode root =
                    new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                            .readTree(p.toFile());
            com.fasterxml.jackson.databind.JsonNode tracks = root.path("music").path("tracks");
            if (!tracks.isArray() || tracks.isEmpty()) return null;

            String wanted = mapMoodToBucket(moodText);
            // First pass: collect ALL existing tracks for this mood, pick one at
            // random so repeated episodes with the same mood don't reuse the same
            // track (variety). Add 2-3 tracks per mood in the bible for best effect.
            java.util.List<String> moodMatches = new java.util.ArrayList<>();
            java.util.List<String> anyAvailable = new java.util.ArrayList<>();
            for (com.fasterxml.jackson.databind.JsonNode t : tracks) {
                String path = t.path("path").asText("");
                if (path.isBlank() || !java.nio.file.Files.exists(java.nio.file.Paths.get(path))) continue;
                anyAvailable.add(path);
                if (wanted.equalsIgnoreCase(t.path("mood").asText(""))) moodMatches.add(path);
            }
            if (!moodMatches.isEmpty()) {
                return moodMatches.get(java.util.concurrent.ThreadLocalRandom.current()
                        .nextInt(moodMatches.size()));
            }
            // Fallback: any available track (random) when no mood match exists.
            if (!anyAvailable.isEmpty()) {
                String pick = anyAvailable.get(java.util.concurrent.ThreadLocalRandom.current()
                        .nextInt(anyAvailable.size()));
                log.debug("No '{}' mood track for '{}', using random available: {}", wanted, moodText, pick);
                return pick;
            }
        } catch (Exception e) {
            log.warn("autoPickMusic failed: {}", e.getMessage());
        }
        return null;
    }

    /** Buckets free-form mood text into the 3 bible mood categories. */
    private String mapMoodToBucket(String moodText) {
        if (moodText == null) return "calm";
        String m = moodText.toLowerCase();
        if (m.contains("energetic") || m.contains("adventure") || m.contains("excited")
                || m.contains("playful") || m.contains("chaotic") || m.contains("silly")) {
            return "energetic";
        }
        if (m.contains("thoughtful") || m.contains("curious") || m.contains("wonder")
                || m.contains("discovery") || m.contains("mystery")) {
            return "thoughtful";
        }
        return "calm";   // default / cozy / quiet / bedtime / warm
    }

    public void lockScene(UUID jobId, int seq) {
        VideoJob job = load(jobId);
        Set<Integer> locked = parseLocked(job.getLockedSceneSeqs());
        locked.add(seq);
        saveLocked(jobId, locked);
    }

    public void unlockScene(UUID jobId, int seq) {
        VideoJob job = load(jobId);
        Set<Integer> locked = parseLocked(job.getLockedSceneSeqs());
        locked.remove(seq);
        saveLocked(jobId, locked);
    }

    /** Lock every scene + advance the pipeline past IMAGES_REVIEW_PENDING. */
    public void lockAllAndContinue(UUID jobId) {
        VideoJob job = load(jobId);
        List<SceneDto> assembly = loadAssemblyScenes(job);
        Set<Integer> all = assembly.stream()
                .map(SceneDto::effectiveSeq)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
        saveLocked(jobId, all);
        approve(jobId);
    }

    private Set<Integer> parseLocked(String csv) {
        if (csv == null || csv.isBlank()) return new TreeSet<>();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(Integer::parseInt)
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new));
    }

    public void saveLocked(UUID jobId, Set<Integer> locked) {
        String csv = locked.stream().sorted()
                .map(String::valueOf)
                .collect(java.util.stream.Collectors.joining(","));
        retryOnConflict(jobId, j -> j.setLockedSceneSeqs(csv));
    }

    /** Copy all scene images from oldJobId's workdir to newJobId's workdir.
     *  Returns a fake image-service response so downstream code is unchanged. */
    private JsonNode reuseImagesFromJob(UUID newJobId, UUID oldJobId,
                                        List<Map<String, Object>> imageScenes) throws java.io.IOException {
        java.nio.file.Path srcDir = java.nio.file.Paths.get("/workdir", oldJobId.toString(), "images");
        java.nio.file.Path dstDir = java.nio.file.Paths.get("/workdir", newJobId.toString(), "images");
        java.nio.file.Files.createDirectories(dstDir);

        com.fasterxml.jackson.databind.node.ObjectNode resp = mapper.createObjectNode();
        resp.put("jobId", newJobId.toString());
        com.fasterxml.jackson.databind.node.ArrayNode scenes = resp.putArray("scenes");
        int copied = 0;
        for (Map<String, Object> s : imageScenes) {
            int seq = ((Number) s.get("seq")).intValue();
            String name = String.format("scene_%02d.png", seq);
            java.nio.file.Path src = srcDir.resolve(name);
            java.nio.file.Path dst = dstDir.resolve(name);
            if (!java.nio.file.Files.exists(src)) {
                throw new java.io.IOException("Source image missing: " + src);
            }
            java.nio.file.Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            com.fasterxml.jackson.databind.node.ObjectNode n = scenes.addObject();
            n.put("seq", seq);
            n.put("imagePath", dst.toString());
            n.put("bytes", java.nio.file.Files.size(dst));
            copied++;
        }
        log.info("Job {} reused {} images from job {}", newJobId, copied, oldJobId);
        return resp;
    }

    public void reject(UUID jobId, String reason) {
        VideoJob job = load(jobId);
        if (!job.getStatus().isAwaitingReview()) {
            throw new IllegalStateException(
                    "Job " + jobId + " is not awaiting review (status=" + job.getStatus() + ")");
        }
        fail(jobId, "REJECTED: " + (reason == null ? "no reason given" : reason));
    }

    // ─────────────────────────────── STAGES ───────────────────────────────

    @Async("pipelineExecutor")
    public void runScriptStage(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            VideoFormat format = VideoFormat.parse(job.getFormat());
            int targetSeconds = capTargetSeconds(jobId, job.getTargetSeconds(), format);

            mark(jobId, JobStatus.SCRIPT_GENERATING, "submitting script");
            // Bible snapshot (audit #20): freeze the EXACT channel.yml this
            // episode renders with. The bible is hot-edited (Cast-editor,
            // transition tuning) — without a per-job copy, "which bible did
            // ep 12 use?" is unanswerable when chasing consistency drift
            // across a 100+ episode run. Best-effort, never blocks.
            snapshotBible(jobId);
            // SeriesState: prepend cross-episode memory to the brief. Explicit
            // series get the rich "previously on..." handoff; standalone
            // episodes fall back to channel-wide memory (recent topics +
            // lessons) so no video repeats itself and the world feels
            // connected for returning viewers.
            String brief = job.getBrief();
            java.util.Optional<String> memory = java.util.Optional.empty();
            if (job.getSeriesId() != null && job.getEpisodeNumber() != null) {
                memory = seriesContinuity.previouslyOn(
                        job.getSeriesId(), job.getEpisodeNumber());
                if (memory.isPresent()) {
                    log.info("Job {} got series continuity context from prior episode", jobId);
                }
            }
            if (memory.isEmpty()) {
                memory = seriesContinuity.channelMemory(jobId);
                if (memory.isPresent()) {
                    log.info("Job {} got channel-wide memory context", jobId);
                }
            }
            if (memory.isPresent()) {
                brief = prependMemory(memory.get(), brief);
            }
            // Build performance-feedback hint from analytics (top moods/lessons).
            String performanceHint = buildPerformanceHint();
            // Performance-weighted arc selection (epsilon-greedy; cold-start =
            // uniform random, same as legacy).
            String preferredArc = performanceLoop.pickArc();
            UUID scriptJobId = scriptClient.submit(
                    job.getTopic(), job.getAudience(), targetSeconds,
                    brief, job.getLesson(), job.getMood(), job.getAngle(),
                    job.getHook(), performanceHint, preferredArc);
            saveScriptJobId(jobId, scriptJobId);

            JsonNode scriptResp = pollScript(scriptJobId);
            JsonNode scriptBody = scriptResp.path("script");
            // Persist the arc the script ACTUALLY used (script-service may have
            // fallen back to random if the preferred id was unknown).
            saveStoryArc(jobId, scriptBody.path("storyArc").asText(null));
            UUID scriptId = UUID.fromString(scriptBody.path("id").asText());

            // Build initial assemblyScenes — only script-derived fields; voice/image come later.
            // Phase carried through so Veo hybrid routing can filter on it.
            List<SceneDto> assemblyScenes = new ArrayList<>();
            for (JsonNode s : scriptBody.path("scenes")) {
                SceneDto asm = new SceneDto();
                asm.setSeq(s.path("seq").asInt());
                // PEAK-HOLD (board #14): the emotional summit — a climax beat
                // scripted at maximum intensity "(5/5)" — gets 2 extra seconds
                // of air. "Big sister." was cut short by the schedule; Pixar
                // holds the peak. The voice doesn't fill it: the held image +
                // ambient ARE the moment.
                int durSec = s.path("durationSeconds").asInt();
                String phaseId = s.path("phase").asText("").toLowerCase();
                String emo = s.path("emotion").asText("");
                if ("climax".equals(phaseId) && emo.matches(".*\\(\\s*5\\s*/\\s*5\\s*\\).*")) {
                    durSec += 2;
                    log.info("Job {} scene {} peak-hold: +2s on a 5/5 climax beat", jobId,
                            s.path("seq").asInt());
                }
                asm.setDurationSeconds(durSec);
                asm.setNarration(s.path("narration").asText(""));
                // Carry the per-character dialogue lines so the dashboard can show
                // + edit them, and a per-scene re-voice can rebuild the audio.
                List<Map<String, Object>> lns = new ArrayList<>();
                for (JsonNode l : s.path("lines")) {
                    lns.add(Map.of("speaker", l.path("speaker").asText(""),
                                   "text",    l.path("text").asText("")));
                }
                asm.setLines(lns);
                asm.setVisualDesc(s.path("visualDesc").asText());
                asm.setPhase(s.path("phase").asText(""));
                asm.setLocationId(s.path("locationId").asText(""));
                asm.setTimeOfDay(s.path("timeOfDay").asText(""));
                asm.setWeather(s.path("weather").asText(""));
                asm.setGoal(s.path("goal").asText(""));
                asm.setEmotion(s.path("emotion").asText(""));
                asm.setMotionSpeed(s.path("motionSpeed").asText(""));
                asm.setEndPose(s.path("endPose").asText(""));
                asm.setMotionDesc(s.path("motionDesc").asText(""));
                // Carry the cast list on the assembly scene: the vision-QC needs it
                // to verify accessories/colour, and the Veo tic-injection +
                // per-character compiler read it too. Without this they ran blind.
                List<String> chars = new ArrayList<>();
                for (JsonNode c : s.path("characters")) chars.add(c.asText());
                asm.setCharacters(chars);
                assemblyScenes.add(asm);
            }
            saveScriptIdAndScenes(jobId, scriptId, assemblyScenes,
                    nullableInt(scriptBody, "structureScore"),
                    nullableInt(scriptBody, "criticScore"));

            ReviewProperties rp = reviewConfig.getReview();
            if (rp.afterScript()) {
                pauseForReview(jobId, JobStatus.SCRIPT_REVIEW_PENDING, "script ready for review");
                return;
            }
            self.runAssetsStage(jobId);
        } catch (Exception e) {
            log.error("Job {} script stage FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    @Async("pipelineExecutor")
    public void runAssetsStage(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            VideoFormat format = VideoFormat.parse(job.getFormat());
            mark(jobId, JobStatus.ASSETS_GENERATING, "voice + images");

            // Re-fetch script for the per-scene voice + image payloads (cheap GET).
            JsonNode scriptResp = scriptClient.get(job.getScriptJobId());
            JsonNode scriptBody = scriptResp.path("script");

            List<Map<String, Object>> voiceScenes = new ArrayList<>();
            List<Map<String, Object>> imageScenes = new ArrayList<>();

            // Per-episode PROP ANCHORS (flag-gated, best-effort): lock recurring
            // props (watering can, ball, …) to one colour/design across scenes.
            List<String> allVisualDescs = new ArrayList<>();
            for (JsonNode s : scriptBody.path("scenes")) allVisualDescs.add(s.path("visualDesc").asText(""));
            List<PropAnchorService.Prop> propAnchors =
                    propAnchorService.buildAnchors(jobId, allVisualDescs, format.imageFormat);

            // Board-audit — auto-establishing: track the previous scene's phase so
            // the one setup scene that directly follows the hook can be framed as
            // a wide establishing shot (geography after the close-up hook).
            String prevPhase = null;
            for (JsonNode s : scriptBody.path("scenes")) {
                int seq = s.path("seq").asInt();
                // The scene's Shot-DNA emotion drives the voice delivery: each
                // line is acted with the scene's emotion (or its own, if the
                // script ever emits per-line emotion) so the TTS isn't flat.
                String sceneEmotion = s.path("emotion").asText("");
                String phase = s.path("phase").asText("");
                List<Map<String, Object>> lines = new ArrayList<>();
                for (JsonNode l : s.path("lines")) {
                    Map<String, Object> lm = new HashMap<>();
                    lm.put("speaker", l.path("speaker").asText());
                    lm.put("text", l.path("text").asText());
                    String lineEmotion = l.path("emotion").asText("");
                    // 100% emotion coverage: line emotion > scene Shot-DNA >
                    // phase default. Every line gets a tag, so the TTS always
                    // acts — never the flat base voice by accident.
                    String emo = !lineEmotion.isBlank() ? lineEmotion
                            : (!sceneEmotion.isBlank() ? sceneEmotion : phaseDefaultEmotion(phase));
                    lm.put("emotion", emo);
                    lines.add(lm);
                }
                Map<String, Object> voiceScene = new HashMap<>();
                voiceScene.put("seq", seq);
                voiceScene.put("lines", lines);
                voiceScene.put("locationId", s.path("locationId").asText(""));
                // Weather travels with the voice payload too: the voice-service
                // AmbientMixer lets an ambient/{weather}.mp3 bed override the
                // location bed, matching the visual fx/weather overlay.
                voiceScene.put("weather", s.path("weather").asText(""));
                // Silent visual beats (lines: []) get a silent track sized to
                // the scripted duration, so the edit holds the intended pause.
                voiceScene.put("durationSeconds", s.path("durationSeconds").asInt(4));
                voiceScenes.add(voiceScene);

                List<String> chars = new ArrayList<>();
                for (JsonNode c : s.path("characters")) chars.add(c.asText());
                Map<String, Object> img = new HashMap<>();
                img.put("seq", seq);
                img.put("visualDesc", withMotif(s.path("visualDesc").asText(), job));
                img.put("characters", chars);
                img.put("locationId", s.path("locationId").asText(""));
                img.put("timeOfDay", s.path("timeOfDay").asText(""));
                img.put("weather", s.path("weather").asText(""));
                // Board-audit — auto-establishing (image side): the FIRST setup
                // scene right after the hook gets an explicit wide-establishing
                // framing hint, mirroring the Veo-side rule in buildVeoScenes.
                // Deterministic and once per video: phases are contiguous, so
                // exactly one setup scene has a hook beat as its predecessor.
                if ("setup".equalsIgnoreCase(phase) && "hook".equalsIgnoreCase(prevPhase)) {
                    String locId = s.path("locationId").asText("");
                    String locName = locId.isBlank() || "null".equals(locId)
                            ? "the scene's location" : locId.replace('-', ' ');
                    img.put("cameraFraming", "wide establishing shot of " + locName
                            + ", the whole setting readable with the character small within it");
                }
                // Attach the prop anchors whose keyword appears in this scene's text.
                if (!propAnchors.isEmpty()) {
                    String vd = s.path("visualDesc").asText("").toLowerCase();
                    List<Map<String, Object>> refs = new ArrayList<>();
                    for (PropAnchorService.Prop p : propAnchors) {
                        if (p.keyword() != null && !p.keyword().isBlank()
                                && p.anchorPath() != null && vd.contains(p.keyword())) {
                            refs.add(Map.of("name", p.name(), "imagePath", p.anchorPath()));
                        }
                    }
                    if (!refs.isEmpty()) img.put("propRefs", refs);
                }
                // Episode-ConsistencyState (Story B): on a RETRY/restart of this
                // stage the episode anchors may already exist — re-attach them so
                // the re-generated scenes keep the established canon. On the very
                // first run there are no anchors yet (this batch IS the canon
                // source); for a SERIES job the fallback inside then seeds the
                // previous episode's promoted anchors from {bible-dir}/refs/
                // series/{seriesId}/ instead (episode canon > series canon >
                // bible refs). Without a seriesId: a no-op, exactly as before.
                attachEpisodeAnchors(img, job);
                imageScenes.add(img);
                prevPhase = phase;
            }

            // Retry-friendly reuse: if a previous attempt already produced an
            // image/voice file for a scene (still on disk), don't regenerate it.
            // This is what makes a retry skip re-paying for existing assets.
            List<SceneDto> existingScenes = loadAssemblyScenes(job);
            Set<Integer> haveImage = new java.util.HashSet<>();
            Set<Integer> haveAudio = new java.util.HashSet<>();
            for (SceneDto s : existingScenes) {
                int seq = s.effectiveSeq();
                if (fileExists(s.getImagePath())) haveImage.add(seq);
                if (fileExists(s.getAudioPath())) haveAudio.add(seq);
            }
            final List<Map<String, Object>> voiceTodo = voiceScenes.stream()
                    .filter(v -> !haveAudio.contains(((Number) v.get("seq")).intValue())).toList();
            final List<Map<String, Object>> imageTodo = imageScenes.stream()
                    .filter(v -> !haveImage.contains(((Number) v.get("seq")).intValue())).toList();
            if (!haveImage.isEmpty() || !haveAudio.isEmpty()) {
                log.info("Job {} assets reuse: keeping {} images + {} voice, generating {} images + {} voice",
                        jobId, haveImage.size(), haveAudio.size(), imageTodo.size(), voiceTodo.size());
            }

            final String imageFormat = format.imageFormat;
            var pool = Executors.newFixedThreadPool(2);
            JsonNode voiceResp, imageResp;
            try {
                var fVoice = CompletableFuture.supplyAsync(() ->
                        voiceTodo.isEmpty() ? emptyScenesResponse()
                                            : voiceClient.synthesize(jobId, voiceTodo), pool);
                var fImage = CompletableFuture.supplyAsync(() -> {
                    if (imageTodo.isEmpty()) return emptyScenesResponse();
                    // Feature B: reuse images from a previous job if requested,
                    // skipping the (paid) image-service call entirely.
                    if (job.getReuseImagesFromJob() != null) {
                        try {
                            return reuseImagesFromJob(jobId, job.getReuseImagesFromJob(), imageTodo);
                        } catch (Exception ex) {
                            log.warn("Job {} image reuse failed ({}) — falling back to fresh generation",
                                    jobId, ex.getMessage());
                        }
                    }
                    return imageClient.generate(jobId, imageTodo, imageFormat);
                }, pool);
                CompletableFuture.allOf(fVoice, fImage).join();
                voiceResp = fVoice.get();
                imageResp = fImage.get();
            } finally {
                pool.shutdown();
            }

            List<SceneDto> assemblyScenes = loadAssemblyScenes(job);
            mergeAssets(assemblyScenes, voiceResp, imageResp);
            // Automated vision-QC: reroll weak scene images (missing accessory,
            // cut-off subject, duplicate cast) before the gate / Veo spend.
            autoQcImages(jobId, assemblyScenes, imageFormat);
            // Episode-ConsistencyState (Story B): AFTER the QC pass — so only
            // approved stills qualify — elect per character the best still of
            // THIS episode and persist it as the job's visual canon. Every
            // later re-roll is conditioned on these exemplars.
            collectEpisodeAnchors(jobId, assemblyScenes, propAnchors);
            saveAssemblyScenes(jobId, assemblyScenes);

            ReviewProperties rp = reviewConfig.getReview();

            // Feature A: per-scene image review gate. Pipeline pauses here,
            // user reviews each image in /review/images/{id} and either
            // regenerates or locks. When all scenes locked → continues.
            if (rp.reviewImages()) {
                pauseForReview(jobId, JobStatus.IMAGES_REVIEW_PENDING, "review scene images");
                return;
            }

            boolean veo = usesVeo(job.getMotionMode());
            boolean gateOpen = rp.afterAssets() || (veo && rp.beforeVeo());
            if (gateOpen) {
                JobStatus state = veo ? JobStatus.VEO_REVIEW_PENDING : JobStatus.ASSETS_REVIEW_PENDING;
                pauseForReview(jobId, state, "assets ready for review");
                return;
            }
            if (veo) self.runVeoStage(jobId);
            else     self.runAssemblyStage(jobId);
        } catch (Exception e) {
            log.error("Job {} assets stage FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    /** True for both full Veo and hybrid (which still sends some scenes through Veo). */
    private boolean usesVeo(String motionMode) {
        return "veo".equalsIgnoreCase(motionMode) || "hybrid".equalsIgnoreCase(motionMode);
    }
    private boolean isHybrid(String motionMode) {
        return "hybrid".equalsIgnoreCase(motionMode);
    }
    private boolean isSongMode(String motionMode) {
        return "song".equalsIgnoreCase(motionMode);
    }

    /**
     * Lazily generate the song for a Song Mode job. Idempotent — if the
     * job already has a songPath the existing track is reused (resume after
     * a review gate doesn't burn another Suno credit).
     *
     * Returns the assembly-service response ({songPath, karaokePath, enabled})
     * or null if anything fails — caller falls back to royalty-free music.
     */
    private JsonNode generateSongIfNeeded(UUID jobId, VideoJob job) {
        try {
            if (job.getSongPath() != null && !job.getSongPath().isBlank()) {
                log.info("Job {} song already generated — reusing {}", jobId, job.getSongPath());
                return mapper.createObjectNode()
                        .put("songPath", job.getSongPath())
                        .put("karaokePath", job.getKaraokePath() == null ? "" : job.getKaraokePath())
                        .put("enabled", true);
            }
            log.info("Job {} song mode — generating lyrics via Claude", jobId);
            LyricsGenerator.SongLyrics lyrics = lyricsGenerator.generate(
                    job.getTopic(), job.getLesson(), job.getMood());

            log.info("Job {} song mode — calling Suno: '{}' [{}]",
                    jobId, lyrics.title(), lyrics.style());
            JsonNode song = assemblyClient.generateSong(
                    jobId, lyrics.lyrics(), lyrics.style(), null);

            // Persist whether or not Suno was enabled — keeps lyrics for audit.
            retryOnConflict(jobId, j -> {
                j.setSongTitle(lyrics.title());
                j.setSongStyle(lyrics.style());
                j.setSongLyrics(lyrics.lyrics());
                if (song != null && song.path("enabled").asBoolean(false)) {
                    j.setSongPath(song.path("songPath").asText(null));
                    j.setKaraokePath(song.path("karaokePath").asText(null));
                }
            });
            return song;
        } catch (Exception e) {
            log.warn("Job {} song-mode generation failed; falling back to royalty-free: {}",
                    jobId, e.getMessage());
            return null;
        }
    }

    @Async("veoExecutor")   // P3b — long blocking Veo call gets its own pool
    public void runVeoStage(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            VideoFormat format = VideoFormat.parse(job.getFormat());
            mark(jobId, JobStatus.VEO_GENERATING, "veo image-to-video");

            List<SceneDto> assemblyScenes = loadAssemblyScenes(job);
            // Hybrid mode: only HOOK + CLIMAX scenes get cinematic Veo motion.
            // Everything else stays as Ken Burns on the still image, saving
            // ~70% of the Veo cost while keeping the algorithm-critical
            // opening and the emotional peak cinematic.
            boolean hybrid = isHybrid(job.getMotionMode());
            List<SceneDto> veoCandidates = hybrid
                    ? assemblyScenes.stream().filter(this::isHeroPhase).toList()
                    : assemblyScenes;
            if (hybrid) {
                log.info("Job {} hybrid Veo routing: {} of {} scenes get Veo (hook+climax only)",
                        jobId, veoCandidates.size(), assemblyScenes.size());
            }
            // SKIP-ON-EXISTING-CLIP: never re-pay for a clip that's already on
            // disk. A scene that still has its clip.mp4 keeps it (its clipPath is
            // untouched in assemblyScenes); only the scenes WITHOUT a usable clip
            // are sent to Veo. No-op for a normal first run (no clips yet) and for
            // re-render (clipPaths cleared upstream); load-bearing for
            // generateAllClips, which keeps the good clips and fills the gaps.
            int beforeSkip = veoCandidates.size();
            veoCandidates = veoCandidates.stream()
                    .filter(s -> !fileExists(s.getClipPath()))
                    .toList();
            if (veoCandidates.size() < beforeSkip) {
                log.info("Job {} Veo skip-on-existing-clip: {} of {} candidate scenes already "
                        + "have a clip on disk (kept) — generating the remaining {}",
                        jobId, beforeSkip - veoCandidates.size(), beforeSkip, veoCandidates.size());
            }
            if (veoCandidates.isEmpty()) {
                log.info("Job {} no scenes to Veo-ify, skipping to assembly", jobId);
                self.runAssemblyStage(jobId);
                return;
            }
            // Generate end-pose stills (best-effort) so Veo interpolates a
            // directed start→end motion instead of wiebelen. Only hero scenes
            // that carry a Shot-DNA endPose get one.
            Map<Integer, String> endStills = generateEndStills(jobId, format.imageFormat, veoCandidates);
            List<Map<String, Object>> veoScenes = buildVeoScenes(veoCandidates, endStills);
            // Job-level Veo model choice (UI "Veo model" select): overrides the
            // bible routing for EVERY Veo scene in this job. Blank = auto
            // (bible routing per sceneType). The cost cap still wins.
            applyModelOverride(veoScenes, job.getVeoModel());
            // Submit + poll (generateAsync): same result shape as the old
            // blocking call, but no 15-min-open HTTP connection to the
            // video-generation-service anymore.
            JsonNode clipsResp = videoGenClient.generateAsync(jobId, format.imageFormat, veoScenes);
            applyClipPaths(assemblyScenes, clipsResp);
            // Output-side QC gate: judge what Veo ACTUALLY produced (headcount,
            // disappearance, accessory drift across frames). One re-roll for a
            // failing clip; a clip that fails twice is dropped so the scene
            // falls back to its already-QC'd still (Ken Burns beats a broken clip).
            clipQcGate(jobId, format, assemblyScenes, veoScenes);
            saveAssemblyScenes(jobId, assemblyScenes);

            // P1 — Veo success-rate visibility. A "green €0" job can mean EVERY
            // scene silently fell back to Ken Burns (broken lastFrame, preview-model
            // 404, quota). Aggregate OK vs FALLBACK/FAILED so a degraded run is loud
            // instead of looking like a clean Ken-Burns video.
            int veoTotal = 0, veoOk = 0;
            StringBuilder veoReasons = new StringBuilder();
            for (JsonNode c : clipsResp.path("clips")) {
                veoTotal++;
                if ("OK".equals(c.path("status").asText())) {
                    veoOk++;
                } else if (veoReasons.length() < 500) {
                    veoReasons.append(" seq").append(c.path("seq").asInt())
                              .append('=').append(c.path("status").asText())
                              .append('(').append(c.path("error").asText("")).append(')');
                }
            }
            double fallbackRatio = veoTotal == 0 ? 0.0 : (double) (veoTotal - veoOk) / veoTotal;
            log.info("Job {} Veo done: {}/{} OK, fallbackRatio={}, cost=€{}, capReached={}",
                    jobId, veoOk, veoTotal,
                    String.format(java.util.Locale.ROOT, "%.2f", fallbackRatio),
                    clipsResp.path("totalCostEur").asDouble(),
                    clipsResp.path("costCapReached").asBoolean());
            if (veoTotal > 0 && fallbackRatio > 0.5) {
                log.error("Job {} Veo DEGRADED — {}/{} scenes fell back to Ken Burns (>50%)."
                        + " Check lastFrame SDK call, Veo 3.1 model access, and quota.{}",
                        jobId, veoTotal - veoOk, veoTotal, veoReasons);
            }
            // Persist Veo cost/success metrics on the job (architecture.md step 10,
            // never built until now) — the job page and analytics can finally show
            // what an episode actually cost.
            try {
                VideoJob fresh = load(jobId);
                com.fasterxml.jackson.databind.node.ObjectNode m = mapper.createObjectNode();
                if (fresh.getMetricsJson() != null && !fresh.getMetricsJson().isBlank()) {
                    try { m = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fresh.getMetricsJson()); }
                    catch (Exception ignore) { /* start clean */ }
                }
                m.put("veoOk", veoOk);
                m.put("veoTotal", veoTotal);
                m.put("veoCostEur", clipsResp.path("totalCostEur").asDouble(0));
                fresh.setMetricsJson(mapper.writeValueAsString(m));
                repo.save(fresh);
            } catch (Exception metricsErr) {
                log.warn("Job {} Veo metrics persist failed (non-fatal): {}",
                        jobId, metricsErr.getMessage());
            }

            // No dedicated "after Veo" gate — flow continues straight to assembly.
            self.runAssemblyStage(jobId);
        } catch (Exception e) {
            log.error("Job {} veo stage FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    /**
     * Appends YouTube-style chapter markers to the description. YouTube
     * auto-detects the format "MM:SS Label" on its own line and renders
     * clickable timeline chapters. Requirements YouTube enforces:
     *   - First chapter must start at 00:00
     *   - Minimum 3 chapters total
     *   - Each chapter ≥ 10 seconds
     *   - Chapters listed in chronological order
     */
    private static final int MIN_CHAPTER_SEC = 10;   // YouTube ignores ALL chapters if any is shorter
    private static final int MIN_CHAPTERS    = 3;     // YouTube needs at least 3 to render

    private MetadataGenerator.Metadata enrichWithChapters(
            MetadataGenerator.Metadata meta, JsonNode scriptBody, boolean isShort) {
        // Chapters are unsupported / pointless on Shorts (<60s vertical).
        if (isShort) return meta;
        // Topic-specific chapter titles from the LLM (one per distinct phase),
        // falling back to the static prettifyPhase labels on any miss.
        Map<String, String> phaseTitles = generatePhaseTitles(meta, scriptBody);
        try {
            // 1) Collapse scenes into phase groups, tracking each group's duration.
            record Group(String label, int startSec, int durSec) {}
            List<Group> groups = new ArrayList<>();
            String currentPhase = null;
            int cumSec = 0, groupStart = 0, groupDur = 0;
            String groupLabel = null;
            for (JsonNode s : scriptBody.path("scenes")) {
                String phase = s.path("phase").asText("").toLowerCase();
                int dur = s.path("durationSeconds").asInt(0);
                if (!phase.equals(currentPhase)) {
                    if (currentPhase != null) groups.add(new Group(groupLabel, groupStart, groupDur));
                    currentPhase = phase;
                    groupLabel = phaseTitles.getOrDefault(phase, prettifyPhase(phase));
                    groupStart = cumSec;
                    groupDur = 0;
                }
                groupDur += dur;
                cumSec += dur;
            }
            if (currentPhase != null) groups.add(new Group(groupLabel, groupStart, groupDur));

            // 2) Merge so every chapter is >= MIN_CHAPTER_SEC. Accumulate phases
            //    until the running chapter is long enough; the chapter keeps the
            //    label of the FIRST phase it covers.
            record Chapter(String label, int startSec) {}
            List<Chapter> chapters = new ArrayList<>();
            String accLabel = null;
            int accStart = 0, accDur = 0;
            for (int i = 0; i < groups.size(); i++) {
                Group g = groups.get(i);
                if (accLabel == null) { accLabel = g.label(); accStart = g.startSec(); accDur = 0; }
                accDur += g.durSec();
                boolean last = i == groups.size() - 1;
                if (accDur >= MIN_CHAPTER_SEC && !last) {
                    chapters.add(new Chapter(accLabel, accStart));
                    accLabel = null;
                }
            }
            // Flush the tail. If it's too short, merge it into the previous chapter
            // (which just means: don't emit a new short chapter at the end).
            if (accLabel != null) {
                if (accDur >= MIN_CHAPTER_SEC || chapters.isEmpty()) {
                    chapters.add(new Chapter(accLabel, accStart));
                }
                // else: tail < 10s and we already have chapters → absorbed silently.
            }

            // 3) YouTube needs >= 3 valid chapters; otherwise emit none.
            if (chapters.size() < MIN_CHAPTERS) return meta;
            // First chapter MUST start at 00:00.
            if (chapters.get(0).startSec() != 0) {
                chapters.set(0, new Chapter(chapters.get(0).label(), 0));
            }

            StringBuilder sb = new StringBuilder(meta.description() == null ? "" : meta.description());
            if (sb.length() > 0 && !sb.toString().endsWith("\n\n")) sb.append("\n\n");
            sb.append("📍 Chapters:\n");
            for (var c : chapters) {
                sb.append(String.format("%02d:%02d  %s%n",
                        c.startSec() / 60, c.startSec() % 60, c.label()));
            }
            return new MetadataGenerator.Metadata(meta.title(), sb.toString(), meta.tags());
        } catch (Exception e) {
            log.warn("Chapter enrichment failed: {}", e.getMessage());
            return meta;
        }
    }

    /** Builds an ordered phase -> sample-text map from the script and asks the
     *  MetadataGenerator for topic-specific chapter titles. Fails safe to an
     *  empty map (caller then uses the static labels). */
    private Map<String, String> generatePhaseTitles(MetadataGenerator.Metadata meta, JsonNode scriptBody) {
        try {
            java.util.LinkedHashMap<String, String> phaseToSample = new java.util.LinkedHashMap<>();
            for (JsonNode s : scriptBody.path("scenes")) {
                String phase = s.path("phase").asText("").toLowerCase();
                if (phase.isBlank() || phaseToSample.containsKey(phase)) continue;
                String sample = s.path("narration").asText("");
                if (sample.isBlank()) sample = s.path("visualDesc").asText("");
                if (sample.length() > 160) sample = sample.substring(0, 160);
                phaseToSample.put(phase, sample);
            }
            if (phaseToSample.isEmpty()) return Map.of();
            return metadata.chapterTitles(scriptBody.path("title").asText(meta.title()),
                    meta.title(), phaseToSample);
        } catch (Exception e) {
            log.warn("Phase-title prep failed (static labels): {}", e.getMessage());
            return Map.of();
        }
    }

    private String prettifyPhase(String phase) {
        return switch (phase) {
            case "hook"        -> "Pip notices something...";
            case "setup"       -> "The friends gather";
            case "development" -> "The adventure";
            case "climax"      -> "The big moment";
            case "resolution"  -> "What they learned";
            case "closer"      -> "See you next time!";
            default            -> phase.isBlank() ? "Scene" :
                    Character.toUpperCase(phase.charAt(0)) + phase.substring(1);
        };
    }

    /** Distills analytics into a performance hint string for Opus. */
    private String buildPerformanceHint() {
        // Single source of truth lives in InsightsAggregator so the dashboard can
        // show the user the exact same hint the writer is being fed.
        return insights.performanceHint();
    }

    /** Returns true if the scene's phase qualifies as a hero scene
     *  (HOOK or CLIMAX) — the ones that get Veo in hybrid mode. */
    private boolean isHeroPhase(SceneDto scene) {
        String p = scene.getPhase();
        if (p == null) return false;
        String phase = p.toLowerCase();
        return phase.equals("hook") || phase.equals("climax");
    }

    /** Picks up to 3 scene stills to seed the thumbnail, preferring shots with
     *  the MOST characters (cast/group shots make the best thumbnails). */
    private List<String> pickCastStills(List<SceneDto> assembly) {
        return assembly.stream()
                .filter(SceneDto::hasImage)
                .sorted((x, y) -> Integer.compare(castSize(y), castSize(x)))
                .map(SceneDto::getImagePath)
                .limit(3)
                .toList();
    }

    private int castSize(SceneDto a) {
        return a.getCharacters() == null ? 0 : a.getCharacters().size();
    }

    /** Ground truth voor de thumbnail-keuze: bible character ids die
     *  SUBSTANTIEEL in deze aflevering zitten (≥35% van de scènes, minimaal 2
     *  scènes). De host haalt dat altijd (staat per regel in elke scène); een
     *  sidekick alleen als die echt een rol heeft — één cameo-scène triggert
     *  dus geen groeps-thumbnail en een afwezig castlid wordt nooit gerenderd. */
    private List<String> presentCast(List<SceneDto> assembly) {
        if (assembly == null || assembly.isEmpty()) return List.of();
        Map<String, Integer> counts = new HashMap<>();
        for (SceneDto a : assembly) {
            for (String c : a.charactersOrEmpty()) {
                String id = String.valueOf(c).trim().toLowerCase();
                if (!id.isBlank()) counts.merge(id, 1, Integer::sum);
            }
        }
        int threshold = Math.max(2, (int) Math.ceil(assembly.size() * 0.35));
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }

    @Async("pipelineExecutor")
    public void runAssemblyStage(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            VideoFormat format = VideoFormat.parse(job.getFormat());
            mark(jobId, JobStatus.ASSEMBLING, "ffmpeg assembly");

            List<SceneDto> assemblyScenes = loadAssemblyScenes(job);

            // ─── Song Mode ───────────────────────────────────────────────
            // motionMode=song → lyrics + Suno vocal track become the primary
            // audio. The vocal MP3 is fed as background music; voice-service
            // narration is already on the per-scene clips so they layer
            // naturally. Karaoke export is saved for bonus content.
            String musicPath = job.getBackgroundMusicPath();
            if (isSongMode(job.getMotionMode())) {
                JsonNode song = generateSongIfNeeded(jobId, job);
                if (song != null) {
                    String sp = song.path("songPath").asText(null);
                    if (sp != null && !sp.isBlank()) musicPath = sp;
                }
            }
            // Normal episodes: prefer an ORIGINAL Suno instrumental tailored to
            // the episode mood (replaces the small royalty-free library). Falls
            // back to the bible/music tracks when Suno is off/unavailable.
            if ((musicPath == null || musicPath.isBlank()) && sunoInstrumental) {
                try {
                    JsonNode instr = assemblyClient.generateInstrumental(jobId, job.getMood());
                    if (instr != null && instr.path("enabled").asBoolean(false)) {
                        String mp = instr.path("musicPath").asText(null);
                        if (mp != null && !mp.isBlank()) {
                            musicPath = mp;
                            log.info("Job {} Suno instrumental for mood='{}': {}",
                                    jobId, job.getMood(), mp);
                        }
                    }
                } catch (Exception e) {
                    log.warn("Job {} Suno instrumental failed ({}) — falling back to library",
                            jobId, e.getMessage());
                }
            }
            if (musicPath == null || musicPath.isBlank()) {
                musicPath = autoPickMusic(job.getMood());
                if (musicPath != null) {
                    log.info("Job {} auto-picked music for mood='{}': {}",
                            jobId, job.getMood(), musicPath);
                }
            }

            // Title for the opening title card (animated overlay).
            // Prefer the script's title; fall back to the topic.
            String title = job.getMetadataTitle();
            if (title == null || title.isBlank()) title = job.getTopic();

            // HTTP boundary: the assembly-service request keeps its exact legacy
            // shape — List<Map<String,Object>> with identical keys/value types.
            // Submit + poll (assembleAsync): same result shape as the old
            // blocking call, but no long-open HTTP connection that a Netty
            // responseTimeout can kill mid-render (incident 2026-06-12,
            // job e2ec9448 — >20-min ffmpeg render, connection dropped).
            JsonNode assembled = assemblyClient.assembleAsync(
                    jobId, job.getScriptId(), SceneDto.toMapList(assemblyScenes),
                    musicPath,
                    props.brand().introPath(),
                    props.brand().outroPath(),
                    format.videoWidth, format.videoHeight,
                    Boolean.TRUE.equals(job.getBurnSubtitles()),
                    title
            );
            String videoPath = assembled.path("outputPath").asText();
            String captionsPath = assembled.path("captionsPath").asText(null);
            String shortPath = assembled.path("shortPath").asText(null);

            // Duration-discipline gate (audit: 123s script → 158s master, +28%).
            // The voice-stretch growth is invisible until you compare scripted vs
            // rendered seconds — do exactly that, record it, and warn loudly when
            // the master overshoots the scripted total by more than the threshold.
            recordDurationMetrics(jobId, assembled.path("durationSeconds").asDouble(0), assemblyScenes);

            // Metadata + thumbnail (using cached script title + hook)
            JsonNode scriptBody = scriptClient.get(job.getScriptJobId()).path("script");
            MetadataGenerator.Metadata meta;
            // METADATA LOCK (board-audit, KRITIEK): a re-assembly used to
            // REGENERATE the metadata and silently degraded an approved title
            // ("Pip Found a Wobbly Egg!" → "Pip Found an Egg!"). Anything that
            // passed a gate is frozen: existing metadata is reused verbatim.
            // Want a fresh take? Clear the title (empty string) via
            // POST /api/v1/videos/{id}/metadata first (PATCH delegates there).
            if (job.getMetadataTitle() != null && !job.getMetadataTitle().isBlank()) {
                List<String> lockedTags = job.getMetadataTags() == null || job.getMetadataTags().isBlank()
                        ? List.of()
                        : Arrays.stream(job.getMetadataTags().split(",")).map(String::trim).toList();
                meta = new MetadataGenerator.Metadata(
                        job.getMetadataTitle(),
                        job.getMetadataDescription() == null ? "" : job.getMetadataDescription(),
                        new ArrayList<>(lockedTags));
                log.info("Job {} metadata LOCKED — reusing approved title \"{}\" (no regen on re-assembly)",
                        jobId, meta.title());
            } else {
                meta = metadata.generate(
                        job.getTopic(),
                        scriptBody.path("title").asText(),
                        scriptBody.path("hook").asText(),
                        format.isVertical()
                );
                // Auto-append YouTube chapter markers built from script phases.
                meta = enrichWithChapters(meta, scriptBody, format.isVertical());
                // Deterministic brand-policy gate: banned hashtags out, required
                // hashtags + "Episode N of Tiny Chicken World" in.
                MetadataPolicy.Result polished = metadataPolicy.apply(meta, job.getEpisodeNumber());
                if (!polished.fixes().isEmpty()) {
                    try { qcInsights.record(jobId, null, polished.fixes(), "metadata-policy"); }
                    catch (Exception ignore) { /* insights are best-effort */ }
                }
                meta = polished.metadata();
            }
            // Use real cast scene stills as the thumbnail base so the thumbnail
            // characters EXACTLY match the film (scenes are Gemini-consistent).
            List<String> castStills = pickCastStills(assemblyScenes);
            JsonNode thumb = thumbnailClient.generate(
                    jobId, job.getTopic(), meta.title(), scriptBody.path("hook").asText(), castStills,
                    performanceLoop.bestThumbnailLayout(),
                    null, presentCast(assemblyScenes));
            String thumbPath = thumb.path("thumbnailPath").asText();
            // Persist the winning layout so analytics can score layout styles.
            saveThumbnailLayout(jobId, thumb.path("layout").asText(null));
            // Squint-test scorer (audit #11): rank the variants as a scrolling
            // phone viewer sees them and PRESELECT the winner as the default —
            // the human gate still decides, but starts from the strongest CTR
            // candidate instead of from variant 0. Best-effort.
            preselectBestThumbnail(jobId, meta.title());

            saveAssemblyResults(jobId, videoPath, thumbPath, meta, captionsPath, shortPath);

            // AI quality audit — best-effort, never blocks the pipeline. Runs
            // BEFORE the upload-review gate so the human reviewer sees the AI
            // critique alongside the master video.
            com.youtubeauto.orchestrator.domain.VideoAudit audit = null;
            try {
                audit = qualityReviewer.auditJob(jobId);
            } catch (Exception auditErr) {
                log.warn("Job {} quality audit failed (non-fatal): {}", jobId, auditErr.getMessage());
            }

            // QA Board (role 12) — consolidate every reviewer into one 8-axis
            // /100 verdict + publish gate. Best-effort; never blocks the pipeline.
            QaBoard.Result qa = null;
            try {
                qa = qaBoard.evaluate(load(jobId), scriptBody,
                        SceneDto.toMapList(assemblyScenes), audit, thumbPath);
                saveQaBoard(jobId, qa.total(), qa.json());
            } catch (Exception qaErr) {
                log.warn("Job {} QA Board failed (non-fatal): {}", jobId, qaErr.getMessage());
            }

            // Auto-Fix loop: when active, hand control to the loop (it reads the
            // fresh audit, re-rolls weak scenes or finishes + pauses for review)
            // instead of falling through to the normal upload gate.
            if (load(jobId).getAutofixTarget() != null) {
                self.runAutoFixPass(jobId);
                return;
            }

            // Dedicated manual thumbnail-review gate. The thumbnail (3 variants)
            // is generated above; pause here so the human picks/approves it
            // BEFORE the publish settings. The QA/publish decision is deferred to
            // runUploadGate, reached on approval. When the gate is off, flow goes
            // straight to the publish gate (legacy behaviour).
            if (thumbnailGateEnabled) {
                String note = (qa != null)
                        ? ("QA " + qa.total() + "/100 — " + qa.verdict())
                        : "thumbnail ready";
                pauseForReview(jobId, JobStatus.THUMBNAIL_REVIEW_PENDING, "thumbnail review — " + note);
                return;
            }
            self.runUploadGate(jobId);
        } catch (Exception e) {
            log.error("Job {} assembly stage FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    /**
     * Publish-gate decision: reached after the thumbnail review is approved (or
     * directly from assembly when the thumbnail gate is off). Holds the master
     * at the Planning/QA review, or proceeds to upload when configured to.
     * Mirrors the legacy assembly-tail logic, reading the QA verdict from the
     * persisted QA Board JSON.
     */
    @Async("pipelineExecutor")
    public void runUploadGate(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            ReviewProperties rp = reviewConfig.getReview();
            // QA publish gate: a master below the gate (or flagged unsafe) is held
            // for human review instead of auto-uploaded.
            if (qaGateEnabled && !qaPublishable(job)) {
                pauseForReview(jobId, JobStatus.UPLOAD_REVIEW_PENDING,
                        "QA Board " + (job.getQaBoardScore() == null ? "?" : job.getQaBoardScore())
                        + "/100 — below publish gate");
                return;
            }
            if (rp.beforeUpload()) {
                pauseForReview(jobId, JobStatus.UPLOAD_REVIEW_PENDING, "master ready for review");
                return;
            }
            self.runUploadStage(jobId);
        } catch (Exception e) {
            log.error("Job {} upload-gate stage FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    /** Reads the persisted QA Board verdict; defaults to publishable=true when
     *  no QA ran (so a failed/absent QA Board never blocks the pipeline). */
    private boolean qaPublishable(VideoJob job) {
        if (job.getQaBoardJson() == null || job.getQaBoardJson().isBlank()) return true;
        try { return mapper.readTree(job.getQaBoardJson()).path("publishable").asBoolean(true); }
        catch (Exception e) { return true; }
    }

    // ─────────────────────────── AI-Critic Auto-Fix ───────────────────────────
    // Closes the loop the human used to run by hand: take the AI-Critic findings,
    // re-roll the image-fixable weak scenes (located via the per-scene vision QC),
    // re-assemble, re-audit, and repeat until the score hits the target or the
    // hard caps run out — then pause for review. Never auto-uploads. Image-gen is
    // stochastic so it's best-effort; the caps bound the spend.
    //
    // EXTENDED (backlog P2): the pass also automates the QA Board's per-axis
    // levers when an axis drops below app.autofix.axis-threshold (default 70):
    //   Thumbnail            → regenerate via the existing thumbnail path +
    //                          squint-test preselect (max 1× per run, and only
    //                          when no re-assembly runs — assembly regenerates
    //                          the thumbnail itself);
    //   Characters/Animation → broaden the scene-reroll selection with the
    //                          weakest hero beats, inside the existing caps;
    //   Sound                → advisory only (assets are user-domain);
    //   Story                → advisory only (a rewrite = full re-render,
    //                          outside the caps — see the inline comment).
    // All within the SAME iteration/reroll caps; no new cost paths without a brake.

    /** Triggers Auto-Fix. {@code target} null → {@link #autofixDefaultTarget};
     *  {@code iterations} null → the configured max, else clamped to [1, max]
     *  (the UI's "1 round + review" button passes 1). */
    public VideoJobResponse startAutoFix(UUID jobId, Integer target, Integer iterations) {
        VideoJob job = load(jobId); // 404s if unknown
        // Auto-Fix needs a rendered master to audit + re-assemble from.
        if (job.getVideoPath() == null || job.getVideoPath().isBlank()) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no assembled master yet — run/assemble it first.");
        }
        int tgt = (target == null) ? autofixDefaultTarget : Math.max(1, Math.min(100, target));
        int iter = (iterations == null) ? autofixMaxIterations
                : Math.max(1, Math.min(autofixMaxIterations, iterations));
        initAutoFix(jobId, tgt, iter, autofixMaxRerolls);
        log.info("Job {} Auto-Fix started (target={}, maxIter={}, maxRerolls={})",
                jobId, tgt, iter, autofixMaxRerolls);
        self.runAutoFixPass(jobId);
        return toResponse(load(jobId));
    }

    // ── Parallel-safe per-scene rerolls ───────────────────────────────────────
    // Multiple "🎬 Maak clip" clicks may run at the same time. The SLOW work
    // (image + Veo/Seedance generation) overlaps freely; only the
    // read-modify-write of the job's assemblyScenes is serialized per job
    // (merge-on-save, so parallel rerolls never overwrite each other's
    // clipPath), and the expensive full re-assembly runs ONCE — triggered by
    // whichever in-flight reroll finishes LAST.
    private final java.util.concurrent.ConcurrentHashMap<UUID, Object> rerollLocks =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicInteger> rerollsInFlight =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<UUID, java.util.concurrent.atomic.AtomicBoolean> rerollDirty =
            new java.util.concurrent.ConcurrentHashMap<>();

    // ── Per-RUN guards for the Auto-Fix axis levers ──────────────────────────
    // Cleared on init/clear of a run. The direct thumbnail regeneration may run
    // at most ONCE per Auto-Fix run (its own small cost brake, on top of the
    // existing iteration/reroll caps), and the sound/story advisories are
    // recorded once per run instead of on every pass. In-memory on purpose: a
    // JVM restart mid-run resets them, which at worst repeats one advisory or
    // one thumbnail regen — same best-effort level as the loop itself.
    private final java.util.Set<UUID> autofixThumbRegenDone =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> autofixSoundAdvised =
            java.util.concurrent.ConcurrentHashMap.newKeySet();
    private final java.util.Set<UUID> autofixStoryNoted =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    private void rerollStarted(UUID jobId) {
        rerollsInFlight.computeIfAbsent(jobId, k -> new java.util.concurrent.atomic.AtomicInteger())
                .incrementAndGet();
    }

    /** Last in-flight reroll out re-assembles — and only if anything was saved. */
    private void rerollFinished(UUID jobId, int seq) {
        int left = rerollsInFlight
                .computeIfAbsent(jobId, k -> new java.util.concurrent.atomic.AtomicInteger(1))
                .decrementAndGet();
        if (left <= 0) {
            boolean dirty = rerollDirty
                    .computeIfAbsent(jobId, k -> new java.util.concurrent.atomic.AtomicBoolean())
                    .getAndSet(false);
            if (dirty) {
                log.info("Job {} — last in-flight reroll (scene {}) done, re-assembling once", jobId, seq);
                self.runAssemblyStage(jobId);
            }
        } else {
            log.info("Job {} — reroll scene {} done, {} reroll(s) still running; assembly deferred",
                    jobId, seq, left);
        }
    }

    /** Merge-on-save under the per-job lock: reload the LATEST scene list (a
     *  parallel reroll may have saved meanwhile) and apply only THIS scene's
     *  changes — no lost updates. */
    private void mergeSceneUpdate(UUID jobId, int seq,
                                  java.util.function.Consumer<SceneDto> patch,
                                  JsonNode clipsResp) {
        synchronized (rerollLocks.computeIfAbsent(jobId, k -> new Object())) {
            List<SceneDto> fresh = loadAssemblyScenes(load(jobId));
            if (patch != null) {
                for (SceneDto a : fresh) {
                    if (a.effectiveSeq() == seq) patch.accept(a);
                }
            }
            if (clipsResp != null) applyClipPaths(fresh, clipsResp);
            saveAssemblyScenes(jobId, fresh);
        }
        rerollDirty.computeIfAbsent(jobId, k -> new java.util.concurrent.atomic.AtomicBoolean())
                .set(true);
    }

    /** Throws with videogen's reason when the rerolled scene came back WITHOUT a
     *  usable clip — so the UI shows WHY (quota, fallback, corrupt output) instead
     *  of "succeeding" silently and re-assembling with the old Ken Burns still
     *  (what happened on ep-2 scene 3). */
    private void requireClipOk(JsonNode clipsResp, int seq) {
        for (JsonNode c : clipsResp.path("clips")) {
            if (c.path("seq").asInt() != seq) continue;
            String status = c.path("status").asText("");
            if ("OK".equals(status)) return;
            String err = c.path("error").asText("");
            throw new IllegalStateException("Clip voor scène " + seq + " is NIET gegenereerd: "
                    + status + (err.isBlank() ? "" : " — " + err)
                    + ". De video is niet aangepast; zie het video-generation-service log.");
        }
        throw new IllegalStateException(
                "video-generation-service gaf geen resultaat terug voor scène " + seq);
    }

    /**
     * Re-roll the VEO clip for ONE scene only (1 clip = 1 VEO cost) instead of
     * re-running the whole VEO stage, then re-assemble reusing every other
     * clip/image. Use when a single VEO clip is weak. Parallel-safe: multiple
     * scenes may re-roll at once; assembly runs once, after the last.
     */
    public Map<String, Object> rerollVeoScene(UUID jobId, int seq, String modelOverride) {
        VideoJob job = load(jobId);
        if (!usesVeo(job.getMotionMode())) {
            throw new IllegalStateException("Job " + jobId + " is not a Veo job (motionMode="
                    + job.getMotionMode() + ") — nothing to re-roll.");
        }
        VideoFormat format = VideoFormat.parse(job.getFormat());
        List<SceneDto> assembly = loadAssemblyScenes(job);
        SceneDto scene = assembly.stream()
                .filter(a -> a.effectiveSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scene " + seq + " not found on job " + jobId));

        rerollStarted(jobId);
        try {
            // Slow generation runs OUTSIDE the lock so parallel rerolls overlap.
            Map<Integer, String> endStills = generateEndStills(jobId, format.imageFormat, List.of(scene));
            List<Map<String, Object>> veoScenes = buildVeoScenes(List.of(scene), endStills);
            applyModelOverride(veoScenes, modelOverride);
            JsonNode clipsResp = videoGenClient.generateAsync(jobId, format.imageFormat, veoScenes);
            requireClipOk(clipsResp, seq); // fallback/quota → loud error, no silent reassemble
            mergeSceneUpdate(jobId, seq, null, clipsResp);
            log.info("Job {} re-rolled VEO clip for scene {} (cost=€{})",
                    jobId, seq, clipsResp.path("totalCostEur").asDouble());
            return Map.of("status", "rerolling", "seq", seq,
                    "costEur", clipsResp.path("totalCostEur").asDouble());
        } finally {
            rerollFinished(jobId, seq);
        }
    }

    /** Injects an optional per-scene model override (UI dropdown, e.g. "veo3_1"
     *  premium 1080p) into the Veo request maps. No-op when blank. */
    private void applyModelOverride(List<Map<String, Object>> veoScenes, String modelOverride) {
        if (modelOverride == null || modelOverride.isBlank()) return;
        for (Map<String, Object> m : veoScenes) m.put("modelOverride", modelOverride.trim());
    }

    /**
     * Fix ONE weak scene end-to-end: generate a NEW still for it (optionally from
     * an edited description), then — for a Veo job — re-roll that scene's Veo clip
     * from the new still, and finally re-assemble reusing every other scene. The
     * tool for "one mediocre image in a finished video": targeted, ~1 Veo cost,
     * no full re-render. Works on a COMPLETED job. For a non-Veo (Ken Burns) job
     * it just refreshes the still and re-assembles (no Veo cost).
     *
     * @param newVisualDesc optional new description; blank → reuse the scene's own.
     */
    public Map<String, Object> regenAndRerollScene(UUID jobId, int seq, String newVisualDesc, String modelOverride) {
        VideoJob job = load(jobId);
        VideoFormat format = VideoFormat.parse(job.getFormat());
        List<SceneDto> assembly = loadAssemblyScenes(job);
        if (assembly.isEmpty()) {
            throw new IllegalStateException("Job " + jobId + " has no assembled scenes yet.");
        }
        SceneDto scene = assembly.stream()
                .filter(a -> a.effectiveSeq() == seq)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Scene " + seq + " not found on job " + jobId));

        // 1) New still — from the edited description if given, else the scene's own.
        String vd = (newVisualDesc != null && !newVisualDesc.isBlank())
                ? newVisualDesc.trim()
                : nz(scene.getVisualDesc());
        if (newVisualDesc != null && !newVisualDesc.isBlank()) scene.setVisualDesc(vd);
        Map<String, Object> imgScene = new HashMap<>();
        imgScene.put("seq", seq);
        imgScene.put("visualDesc", withMotif(vd, job));
        imgScene.put("characters", scene.charactersOrEmpty());
        imgScene.put("locationId", nz(scene.getLocationId()));
        imgScene.put("timeOfDay", nz(scene.getTimeOfDay()));
        imgScene.put("weather", nz(scene.getWeather()));
        attachEpisodeAnchors(imgScene, job);   // Story B: regen+reroll matches episode canon
        rerollStarted(jobId);
        try {
            JsonNode imgResp = imageClient.generate(jobId, List.of(imgScene), format.imageFormat);
            String newPath = null;
            for (JsonNode n : imgResp.path("scenes")) {
                if (n.path("seq").asInt() == seq) { newPath = n.path("imagePath").asText(""); break; }
            }
            if (newPath == null || newPath.isBlank()) {
                throw new IllegalStateException("image-service returned no new still for scene " + seq);
            }
            scene.setImagePath(newPath); // local copy → end-still/Veo prompt uses the new still

            // 2) Veo job → re-roll this scene's clip FROM THE NEW STILL.
            double cost = 0.0;
            JsonNode clipsResp = null;
            if (usesVeo(job.getMotionMode())) {
                Map<Integer, String> endStills = generateEndStills(jobId, format.imageFormat, List.of(scene));
                List<Map<String, Object>> veoScenes = buildVeoScenes(List.of(scene), endStills);
                applyModelOverride(veoScenes, modelOverride);
                clipsResp = videoGenClient.generateAsync(jobId, format.imageFormat, veoScenes);
                requireClipOk(clipsResp, seq); // fallback/quota → loud error
                cost = clipsResp.path("totalCostEur").asDouble();
            }

            // 3) Merge-on-save (parallel-safe), then the LAST in-flight reroll
            // re-assembles once — see rerollFinished().
            final String vdFinal = vd, pathFinal = newPath;
            final boolean vdEdited = newVisualDesc != null && !newVisualDesc.isBlank();
            mergeSceneUpdate(jobId, seq, a -> {
                if (vdEdited) a.setVisualDesc(vdFinal);
                a.setImagePath(pathFinal);
            }, clipsResp);
            log.info("Job {} regen+reroll scene {} -> new still {} (Veo cost=€{})",
                    jobId, seq, newPath, cost);
            return Map.of("status", "regenerating", "seq", seq, "imagePath", newPath, "costEur", cost);
        } finally {
            rerollFinished(jobId, seq);
        }
    }

    @Async("pipelineExecutor")
    public void runAutoFixPass(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            Integer target = job.getAutofixTarget();
            if (target == null) return; // loop not active

            int score = auditRepo.findTopByVideoJobIdOrderByCreatedAtDesc(jobId)
                    .map(com.youtubeauto.orchestrator.domain.VideoAudit::getScore).orElse(-1);
            Integer itLeft = job.getAutofixIterationsLeft();
            Integer rrLeft = job.getAutofixRerollsLeft();

            if (score >= target) { finishAutoFix(jobId, "reached " + score + "/" + target); return; }
            if (itLeft == null || itLeft <= 0 || rrLeft == null || rrLeft <= 0) {
                finishAutoFix(jobId, "budget exhausted at " + score + "/" + target); return;
            }

            // Locate weak scenes three ways: (1) the per-scene vision QC,
            // (2) the AI-Critic's own critical/major findings mapped to the
            // scenes containing the character the Critic complained about — so
            // Auto-Fix acts on what the Critic actually flagged, not only what
            // the localizer QC happens to catch — and (3) when the QA Board's
            // Characters/Animation axis sags below app.autofix.axis-threshold,
            // the weakest hero (hook/climax) beats are ADDED to the selection.
            // QC + Critic stay leading; the axis only BROADENS the set, always
            // inside the same reroll caps (no new budget).
            VideoFormat format = VideoFormat.parse(job.getFormat());
            List<SceneDto> assembly = loadAssemblyScenes(job);
            Map<String, String> dna = dnaAccessoryLines();
            Set<Integer> criticTargets = criticTargetedSeqs(jobId, assembly);

            // ── QA Board axis levers (extended Auto-Fix) ─────────────────────
            // Axes come from the persisted QA Board JSON written by the last
            // assembly pass; no QA Board → empty map → every lever stays off.
            Map<String, Integer> axes = qaAxes(job);         // name → 0-100
            int thr = autofixAxisThreshold;
            boolean charsLow = axes.getOrDefault("Characters", 100) < thr;
            boolean animLow  = axes.getOrDefault("Animation", 100) < thr;
            Set<Integer> axisTargets = (charsLow || animLow)
                    ? weakestHeroSeqs(assembly, parseLocked(job.getLockedSceneSeqs()), rrLeft)
                    : Set.of();
            if (!axisTargets.isEmpty()) {
                log.info("Job {} Auto-Fix: {} axis low — broadening reroll selection with hero scenes {}",
                        jobId, charsLow ? (animLow ? "Characters+Animation" : "Characters") : "Animation",
                        axisTargets);
            }

            // Sound axis low → ADVISE ONLY (once per run). Music/ambient/sting
            // assets are user-domain — Auto-Fix never generates audio. The QA
            // Board details say exactly WHAT is missing; surface that to the
            // reviewer via a QC insight + the job step/log.
            int soundAxis = axes.getOrDefault("Sound", 100);
            if (soundAxis < thr && autofixSoundAdvised.add(jobId)) {
                List<String> gaps = qaSoundGaps(job);
                String what = gaps.isEmpty()
                        ? "check the music bed, ambient loops (bible/sfx/ambient), foley (bible/sfx), "
                          + "brand sting (bible/sting.mp3) and the voice/music mix"
                        : String.join("; ", gaps);
                String msg = "Sound axis low (" + (soundAxis / 10) + "/10): " + what
                        + " — sound assets are user-domain, Auto-Fix does not generate them.";
                qcInsights.record(jobId, null, List.of(msg), "autofix-sound");
                log.info("Job {} Auto-Fix sound advice: {}", jobId, msg);
            }

            // Story axis low → existing behaviour ON PURPOSE: the Critic rewrite
            // lives in the SCRIPT stage. Here the master is already rendered, so
            // a script rewrite would force a full re-render (voice + images +
            // Veo + assembly) — far outside the Auto-Fix caps. Log + insight
            // only (once per run); the human can re-run the script stage.
            int storyAxis = axes.getOrDefault("Story", 100);
            if (storyAxis < thr && autofixStoryNoted.add(jobId)) {
                String msg = "Story axis low (" + (storyAxis / 10) + "/10): a script rewrite means a "
                        + "full re-render and is outside the Auto-Fix caps — review the script stage manually.";
                qcInsights.record(jobId, null, List.of(msg), "autofix-story");
                log.info("Job {} Auto-Fix story note: {}", jobId, msg);
            }

            int rerolled = 0;
            for (SceneDto a : assembly) {
                if (rrLeft - rerolled <= 0) break;
                if (!a.hasImage()) continue;
                int seq = a.effectiveSeq();
                List<String> charIds = a.charactersOrEmpty();
                List<String> expected = new ArrayList<>();
                for (String id : charIds) expected.add(dna.getOrDefault(id == null ? "" : id.toLowerCase(), id));
                SceneImageQc.Result r = sceneImageQc.check(
                        java.nio.file.Paths.get(a.getImagePath()), expected, charIds);
                boolean qcWeak = !r.ok();
                boolean criticWeak = criticTargets.contains(seq);
                boolean axisWeak = axisTargets.contains(seq);
                if (!qcWeak && !criticWeak && !axisWeak) continue;
                String why = qcWeak ? ("QC: " + r.issues())
                        : criticWeak ? "AI-Critic flagged a character in this scene"
                        : "QA Board Characters/Animation axis low — weakest hero beat";
                log.info("Job {} Auto-Fix rerolling scene {} ({})", jobId, seq, why);
                if (qcWeak) qcInsights.record(jobId, seq, r.issues(), "auto-fix");
                // Feed the reason into the re-roll. For a QC-weak scene the
                // concrete issue list is the sharpest hint; the Critic/axis cases
                // pass their (shorter, directional) reason. Null-safe throughout.
                String autofixHint = qcWeak ? qcIssuesHint(r.issues()) : why;
                String newPath = regenScene(jobId, a, format.imageFormat, autofixHint);
                if (newPath != null && !newPath.isBlank()) { a.setImagePath(newPath); rerolled++; }
            }

            if (rerolled == 0) {
                // Thumbnail axis lever — applied ONLY here, on the finishing
                // pass: when scenes DID reroll, the re-assembly below already
                // regenerates the thumbnail (and re-runs the squint-test
                // preselect), so a direct regen would just double the cost.
                // Hard brake: at most once per Auto-Fix run.
                int thumbAxis = axes.getOrDefault("Thumbnail", 100);
                if (thumbAxis < thr && autofixThumbRegenDone.add(jobId)) {
                    try {
                        log.info("Job {} Auto-Fix: Thumbnail axis low ({}/10) — regenerating thumbnail",
                                jobId, thumbAxis / 10);
                        // Existing path (cast stills + presentCast), no reviewer hint.
                        regenerateThumbnail(jobId, null);
                        String title = job.getMetadataTitle();
                        if (title == null || title.isBlank()) title = job.getTopic();
                        preselectBestThumbnail(jobId, title);
                    } catch (Exception te) {
                        log.warn("Job {} Auto-Fix thumbnail regen failed (non-fatal): {}",
                                jobId, te.getMessage());
                    }
                }
                finishAutoFix(jobId, "no image-fixable issues at " + score + "/" + target); return;
            }

            saveAssemblyScenes(jobId, assembly);
            decrementAutoFix(jobId, rerolled);
            log.info("Job {} Auto-Fix rerolled {} scene(s) — re-assembling", jobId, rerolled);
            self.runAssemblyStage(jobId); // rebuilds + re-audits; loops back here while active
        } catch (Exception e) {
            log.warn("Job {} Auto-Fix pass failed ({}), finishing", jobId, e.getMessage());
            try { finishAutoFix(jobId, "error"); } catch (Exception ignore) {}
        }
    }

    /** Maps the AI-Critic's critical/major findings to scene seqs: a finding that
     *  names a character (pip/mo/bo) targets every scene that contains that
     *  character, so Auto-Fix re-rolls the shots the Critic actually complained
     *  about even when the per-scene QC didn't flag them. Best-effort. */
    private Set<Integer> criticTargetedSeqs(UUID jobId, List<SceneDto> assembly) {
        Set<Integer> out = new HashSet<>();
        try {
            var audit = auditRepo.findTopByVideoJobIdOrderByCreatedAtDesc(jobId).orElse(null);
            if (audit == null || audit.getFindings() == null || audit.getFindings().isBlank()) return out;
            JsonNode findings = mapper.readTree(audit.getFindings());
            Set<String> chars = new HashSet<>();
            for (JsonNode f : findings) {
                String sev = f.path("severity").asText("minor");
                if (!sev.equals("critical") && !sev.equals("major")) continue;
                String msg = (f.path("area").asText("") + " " + f.path("message").asText("")).toLowerCase();
                if (msg.matches("(?s).*\\bpip\\b.*")) chars.add("pip");
                if (msg.matches("(?s).*\\bmo\\b.*"))  chars.add("mo");
                if (msg.matches("(?s).*\\bbo\\b.*"))  chars.add("bo");
            }
            if (chars.isEmpty()) return out;
            for (SceneDto a : assembly) {
                for (String id : a.charactersOrEmpty()) {
                    if (id != null && chars.contains(id.toLowerCase())) {
                        out.add(a.effectiveSeq());
                        break;
                    }
                }
            }
            if (!out.isEmpty()) log.info("Job {} Auto-Fix: Critic targets scenes {} (chars {})", jobId, out, chars);
        } catch (Exception e) {
            log.warn("Job {} criticTargetedSeqs failed: {}", jobId, e.getMessage());
        }
        return out;
    }

    /**
     * Best-effort correction hint to PRE-FILL the per-scene "Regen" prompt with
     * the most useful feedback the system already holds for scene {@code seq},
     * in descending specificity:
     *   a. the latest persisted per-scene QC issue (most specific);
     *   b. an AI-Critic critical/major finding that names a character present in
     *      this scene — reusing the SAME character→scene mapping as
     *      {@link #criticTargetedSeqs} (here scene {@code seq} is in its set);
     *   c. a generic QA-Board nudge when the Characters/Animation axis is below
     *      the Auto-Fix axis threshold (more general).
     * Combined into one compact sentence (~200 char cap, de-duplicated). No
     * feedback available → empty string. Never throws on a missing audit/board.
     */
    @Transactional(readOnly = true)
    public String regenHint(UUID jobId, int seq) {
        List<String> parts = new ArrayList<>();
        try {
            VideoJob job = load(jobId);

            // (a) Most specific: latest persisted per-scene QC issue.
            try {
                String qc = qcInsights.latestIssueFor(jobId, seq);
                if (qc != null && !qc.isBlank()) parts.add(qc.trim());
            } catch (Exception ignore) { /* best-effort */ }

            List<SceneDto> assembly;
            try { assembly = loadAssemblyScenes(job); }
            catch (Exception e) { assembly = new ArrayList<>(); }

            // (b) AI-Critic critical/major finding naming a character in THIS
            //     scene. Reuse the finding→scene mapping; only add the finding's
            //     message text when seq is among the Critic's targeted scenes.
            try {
                if (criticTargetedSeqs(jobId, assembly).contains(seq)) {
                    var audit = auditRepo.findTopByVideoJobIdOrderByCreatedAtDesc(jobId).orElse(null);
                    if (audit != null && audit.getFindings() != null && !audit.getFindings().isBlank()) {
                        Set<String> here = new HashSet<>();
                        for (SceneDto a : assembly) {
                            if (a.effectiveSeq() == seq) {
                                for (String c : a.charactersOrEmpty())
                                    if (c != null) here.add(c.toLowerCase());
                            }
                        }
                        for (JsonNode f : mapper.readTree(audit.getFindings())) {
                            String sev = f.path("severity").asText("minor");
                            if (!sev.equals("critical") && !sev.equals("major")) continue;
                            String message = f.path("message").asText("");
                            if (message.isBlank()) continue;
                            String hay = (f.path("area").asText("") + " " + message).toLowerCase();
                            boolean mentionsHere =
                                    (here.contains("pip") && hay.matches("(?s).*\\bpip\\b.*"))
                                 || (here.contains("mo")  && hay.matches("(?s).*\\bmo\\b.*"))
                                 || (here.contains("bo")  && hay.matches("(?s).*\\bbo\\b.*"));
                            if (mentionsHere) { parts.add(message.trim()); break; }
                        }
                    }
                }
            } catch (Exception ignore) { /* best-effort */ }

            // (c) General: QA-Board Characters/Animation axis below threshold.
            try {
                Map<String, Integer> axes = qaAxes(job);
                int thr = autofixAxisThreshold > 0 ? autofixAxisThreshold : 70;
                if (axes.getOrDefault("Characters", 100) < thr)
                    parts.add("character-consistentie verbeteren (juiste kleuren/accessoires per personage)");
                if (axes.getOrDefault("Animation", 100) < thr)
                    parts.add("dynamischer beeld met duidelijke houding/beweging");
            } catch (Exception ignore) { /* best-effort */ }
        } catch (Exception e) {
            log.warn("Job {} regenHint failed (non-fatal): {}", jobId, e.getMessage());
            return "";
        }

        // De-duplicate (case-insensitive) and join into one compact sentence.
        List<String> uniq = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : parts) {
            if (p == null) continue;
            String t = p.trim();
            if (t.isEmpty()) continue;
            if (seen.add(t.toLowerCase())) uniq.add(t);
        }
        String hint = String.join("; ", uniq).trim();
        if (hint.length() > 200) hint = hint.substring(0, 197).trim() + "...";
        return hint;
    }

    /** Per-axis QA Board scores on the /100 scale (axis is /10 in the JSON,
     *  ×10 here so it compares against app.autofix.axis-threshold). Empty map
     *  when no QA Board ran or the JSON is unreadable — every axis lever then
     *  stays off (fail-safe). */
    private Map<String, Integer> qaAxes(VideoJob job) {
        Map<String, Integer> out = new HashMap<>();
        if (job.getQaBoardJson() == null || job.getQaBoardJson().isBlank()) return out;
        try {
            for (JsonNode ax : mapper.readTree(job.getQaBoardJson()).path("axes")) {
                String name = ax.path("name").asText("");
                if (!name.isBlank()) out.put(name, ax.path("score").asInt(10) * 10);
            }
        } catch (Exception e) {
            log.warn("Job {} qaAxes parse failed (levers off): {}", job.getId(), e.getMessage());
        }
        return out;
    }

    /** The QA Board's sound-design gap notes ("Sound: Music bed MISSING → …",
     *  "Sound: Ambient library empty → …") from the persisted details array —
     *  so the autofix-sound advisory says exactly WHAT is missing instead of a
     *  generic "sound is weak". Best-effort, empty on any parse problem. */
    private List<String> qaSoundGaps(VideoJob job) {
        List<String> out = new ArrayList<>();
        if (job.getQaBoardJson() == null || job.getQaBoardJson().isBlank()) return out;
        try {
            for (JsonNode d : mapper.readTree(job.getQaBoardJson()).path("details")) {
                String s = d.asText("");
                if (s.startsWith("Sound: ") && (s.contains("MISSING") || s.contains("empty"))) {
                    out.add(s.substring("Sound: ".length()));
                }
            }
        } catch (Exception e) {
            log.warn("Job {} qaSoundGaps parse failed: {}", job.getId(), e.getMessage());
        }
        return out;
    }

    /** Hero (hook/climax) scenes ranked weakest-first on the same motion
     *  signals the QA Board's Animation axis rewards (motionDesc with real
     *  content, an endPose, a pacing cue), excluding locked scenes; at most
     *  {@code limit} seqs. Used to BROADEN the Auto-Fix reroll selection when
     *  the Characters/Animation axis sags — bounded by the EXISTING reroll
     *  caps, never a budget of its own. */
    private Set<Integer> weakestHeroSeqs(List<SceneDto> assembly, Set<Integer> locked, int limit) {
        record Cand(int seq, int richness) {}
        List<Cand> cands = new ArrayList<>();
        for (SceneDto a : assembly) {
            String phase = nz(a.getPhase()).toLowerCase();
            if (!phase.equals("hook") && !phase.equals("climax")) continue;
            if (!a.hasImage() || locked.contains(a.effectiveSeq())) continue;
            int rich = 0;                                       // mirrors QaBoard.motionRichness weights
            if (!nz(a.getMotionDesc()).isBlank())   rich += 40;
            if (!nz(a.getEndPose()).isBlank())      rich += 30;
            if (!nz(a.getMotionSpeed()).isBlank())  rich += 15;
            if (nz(a.getMotionDesc()).length() >= 40) rich += 15;
            cands.add(new Cand(a.effectiveSeq(), rich));
        }
        cands.sort(java.util.Comparator.comparingInt(Cand::richness));
        Set<Integer> out = new LinkedHashSet<>();
        for (Cand c : cands) {
            if (out.size() >= Math.max(0, limit)) break;
            out.add(c.seq());
        }
        return out;
    }

    /** Ends the loop: clears state and always pauses at the upload review gate
     *  (the human reviews the result — Auto-Fix never uploads on its own). */
    private void finishAutoFix(UUID jobId, String reason) {
        // Surface the axis-lever actions in the step note the reviewer sees.
        if (autofixThumbRegenDone.contains(jobId)) reason += "; thumbnail regenerated (Thumbnail axis was low)";
        if (autofixSoundAdvised.contains(jobId))   reason += "; sound advice recorded (see QC insights)";
        clearAutoFix(jobId);
        pauseForReview(jobId, JobStatus.UPLOAD_REVIEW_PENDING, "Auto-Fix done (" + reason + ") — review");
        log.info("Job {} Auto-Fix finished: {}", jobId, reason);
    }

    public void initAutoFix(UUID id, int target, int iters, int rerolls) {
        // Fresh run → fresh per-run axis-lever guards.
        autofixThumbRegenDone.remove(id);
        autofixSoundAdvised.remove(id);
        autofixStoryNoted.remove(id);
        retryOnConflict(id, j -> {
            j.setAutofixTarget(target);
            j.setAutofixIterationsLeft(iters);
            j.setAutofixRerollsLeft(rerolls);
        });
    }
    public void decrementAutoFix(UUID id, int rerolled) {
        retryOnConflict(id, j -> {
            int it = j.getAutofixIterationsLeft() == null ? 0 : j.getAutofixIterationsLeft();
            int rr = j.getAutofixRerollsLeft() == null ? 0 : j.getAutofixRerollsLeft();
            j.setAutofixIterationsLeft(Math.max(0, it - 1));
            j.setAutofixRerollsLeft(Math.max(0, rr - rerolled));
        });
    }
    public void clearAutoFix(UUID id) {
        autofixThumbRegenDone.remove(id);
        autofixSoundAdvised.remove(id);
        autofixStoryNoted.remove(id);
        retryOnConflict(id, j -> {
            j.setAutofixTarget(null);
            j.setAutofixIterationsLeft(null);
            j.setAutofixRerollsLeft(null);
        });
    }

    @Async("pipelineExecutor")
    public void runUploadStage(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            mark(jobId, JobStatus.UPLOADING, "youtube upload");

            List<String> tags = job.getMetadataTags() == null || job.getMetadataTags().isBlank()
                    ? List.of()
                    : Arrays.stream(job.getMetadataTags().split(",")).map(String::trim).toList();

            JsonNode up = uploadClient.upload(
                    jobId, job.getVideoPath(), job.getThumbnailPath(),
                    job.getMetadataTitle(),
                    job.getMetadataDescription(),
                    tags,
                    job.getPrivacyStatus(),
                    job.getPlannedPublishAt(),
                    job.getCaptionsPath()
            );
            String ytId = up.path("youtubeVideoId").asText();
            String ytUrl = up.path("youtubeUrl").asText();

            // Caption-track guard: an SRT was sent but the caption upload failed
            // → YouTube falls back to AUTO-captions, which garble the channel's
            // invented words ("tok tok" → "Tuk talk" on ep 3). Loud, and stored
            // on the job so the dashboard shows it instead of a silent warn-log.
            if (job.getCaptionsPath() != null && !job.getCaptionsPath().isBlank()
                    && !up.path("captionsUploaded").asBoolean(true)) {
                log.error("Job {} CAPTION UPLOAD FAILED for {} — auto-captions will "
                        + "show. Usually an OAuth scope issue (youtube.force-ssl); "
                        + "re-upload the SRT via YouTube Studio or re-consent.",
                        jobId, ytId);
                try {
                    retryOnConflict(jobId, j -> j.setError(
                            "Caption upload failed — video is live, but YouTube "
                            + "shows auto-captions. Upload " + job.getCaptionsPath()
                            + " manually in Studio, or fix the OAuth scope and re-run."));
                } catch (Exception ignore) { /* never block the upload flow */ }
            }

            // Manual distribution gate: stop here so the human pushes the other
            // platforms (TikTok / Instagram / Facebook / community post) from the
            // dashboard, then finalises. Off → complete immediately (zero-touch,
            // with the legacy auto Facebook cross-post below).
            if (distributionGateEnabled) {
                enterDistribution(jobId, ytId, ytUrl);
                // Series playlist: after the YouTube id is persisted, so a crash
                // here never loses the upload result. Best-effort, never blocks.
                addToSeriesPlaylist(jobId, job, ytId);
                // Series anchors: a human-approved upload promotes this episode's
                // character canon to the series folder. Best-effort, never blocks.
                promoteSeriesAnchors(jobId, job);
                pauseForReview(jobId, JobStatus.DISTRIBUTION_PENDING,
                        "on YouTube — push other platforms, then finish");
                log.info("Job {} uploaded -> {} ; awaiting distribution", jobId, ytUrl);
                return;
            }

            complete(jobId, ytId, ytUrl);
            log.info("Job {} COMPLETED -> {}", jobId, ytUrl);

            // Series playlist (best-effort, after persist — see helper).
            addToSeriesPlaylist(jobId, job, ytId);
            // Series anchors (best-effort, after persist — see helper).
            promoteSeriesAnchors(jobId, job);

            // Best-effort cross-post to Facebook Page. Never blocks completion;
            // the user can re-trigger from the dashboard if it fails or is
            // off (FACEBOOK_PAGE_ACCESS_TOKEN not configured).
            try {
                String fbScheduledUnix = null;
                if (job.getPlannedPublishAt() != null
                        && job.getPlannedPublishAt().isAfter(java.time.OffsetDateTime.now().plusMinutes(15))) {
                    fbScheduledUnix = String.valueOf(job.getPlannedPublishAt().toEpochSecond());
                }
                JsonNode fb = uploadClient.distributeFacebook(
                        job.getVideoPath(),
                        job.getMetadataTitle(),
                        job.getMetadataDescription(),
                        fbScheduledUnix);
                if (fb != null && fb.path("success").asBoolean(false)) {
                    String fbId = fb.path("postId").asText(null);
                    if (fbId != null && !fbId.isBlank()) {
                        retryOnConflict(jobId, j -> {
                            j.setFacebookVideoId(fbId);
                            j.setFacebookUrl(fb.path("url").asText("https://www.facebook.com/" + fbId));
                        });
                        log.info("Job {} cross-posted to Facebook: {}", jobId, fbId);
                    }
                }
            } catch (Exception fbErr) {
                log.warn("Job {} Facebook cross-post failed (non-fatal): {}", jobId, fbErr.getMessage());
            }
        } catch (Exception e) {
            log.error("Job {} upload stage FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    /**
     * Best-effort: add the freshly uploaded episode to its series playlist
     * (named after the bible series; the upload-service creates the playlist
     * on first use and is idempotent on re-runs, so nothing is persisted on
     * the job — a retry simply resolves the same playlist again).
     *
     * Skipped on purpose for vertical (Shorts) jobs: vertical videos pollute
     * the landscape series playlist. Note the pipeline itself never uploads
     * the derived Short (job.shortPath) to YouTube — it only feeds the manual
     * TikTok/Reels pushes — so only the master needs this guard.
     *
     * Null response (upload-service down, or a mocked client in tests) is a
     * silent ok: never fail or block the upload flow over a playlist.
     */
    private void addToSeriesPlaylist(UUID jobId, VideoJob job, String ytId) {
        try {
            if (job.getSeriesId() == null || job.getSeriesId().isBlank()) return;
            if (ytId == null || ytId.isBlank()) return;
            try {
                if (VideoFormat.parse(job.getFormat()).isVertical()) {
                    log.info("Job {} is vertical/Short — not adding to the series playlist", jobId);
                    return;
                }
            } catch (IllegalArgumentException ignore) { /* unknown format → treat as landscape */ }

            String title = resolveSeriesTitle(job.getSeriesId());
            JsonNode res = uploadClient.addToPlaylist(ytId, title,
                    "Alle afleveringen van " + title + " — Tiny Chicken World.");
            if (res == null) {
                // Best-effort by design (and the state-machine test's mock
                // returns null here) — log and move on.
                log.warn("Job {} series-playlist add gave no response (non-fatal)", jobId);
                return;
            }
            if (res.path("success").asBoolean(false)) {
                log.info("Job {} {} series playlist '{}' ({}{})", jobId,
                        res.path("added").asBoolean(false) ? "added to" : "already in",
                        title, res.path("playlistId").asText(""),
                        res.path("playlistCreated").asBoolean(false) ? ", playlist created" : "");
            } else {
                String err = res.path("error").asText("unknown error");
                log.warn("Job {} series-playlist add failed (non-fatal): {}", jobId, err);
                try {
                    qcInsights.record(jobId, null,
                            List.of("Series playlist add failed: " + err), "distribution");
                } catch (Exception ignore) { /* insights are optional */ }
            }
        } catch (Exception e) {
            log.warn("Job {} series-playlist add failed (non-fatal): {}", jobId, e.getMessage());
        }
    }

    /** Display title of a bible series (bible.series[].name) — falls back to
     *  the raw seriesId when the bible is missing or the id is unknown. */
    private String resolveSeriesTitle(String seriesId) {
        try {
            for (JsonNode s : readBible().path("series")) {
                if (seriesId.equals(s.path("id").asText(null))) {
                    String name = s.path("name").asText("");
                    if (!name.isBlank()) return name;
                }
            }
        } catch (Exception ignore) { /* no bible in tests / misconfig → fallback */ }
        return seriesId;
    }

    /**
     * Approve from the distribution gate → mark the job COMPLETED. The actual
     * platform pushes (TikTok / Instagram / Facebook / community post / end-screen)
     * happen via the dashboard's Distribution card while the job sits in this
     * gate; this just closes it out once the user is done.
     */
    @Async("pipelineExecutor")
    public void finalizeDistribution(UUID jobId) {
        try {
            VideoJob job = load(jobId);
            complete(jobId, job.getYoutubeVideoId(), job.getYoutubeUrl());
            log.info("Job {} distribution finalised -> COMPLETED", jobId);
        } catch (Exception e) {
            log.error("Job {} finalizeDistribution FAILED", jobId, e);
            fail(jobId, e.getMessage());
        }
    }

    // ─────────────────────────── HELPERS ───────────────────────────

    private int capTargetSeconds(UUID jobId, int requested, VideoFormat format) {
        if (requested > format.maxSeconds) {
            log.warn("Job {} requested {}s in {} format — capping at {}s",
                    jobId, requested, format, format.maxSeconds);
            return format.maxSeconds;
        }
        return requested;
    }

    private JsonNode pollScript(UUID scriptJobId) throws InterruptedException {
        for (int i = 0; i < props.poll().maxAttempts(); i++) {
            JsonNode r = scriptClient.get(scriptJobId);
            String status = r.path("status").asText();
            if ("COMPLETED".equals(status)) return r;
            if ("FAILED".equals(status)) {
                throw new IllegalStateException("script-service FAILED: " + r.path("error").asText());
            }
            Thread.sleep(props.poll().intervalMs());
        }
        throw new IllegalStateException("script-service timeout");
    }

    /**
     * Automated vision-QC of every scene still before the review gate / Veo.
     * Weak images (missing accessory, cut-off subject, duplicate cast) are
     * re-generated up to {@code qcMaxRerollsPerScene}, bounded by a per-video
     * {@code qcMaxTotalRerolls} cost cap. Best-effort: never throws, never blocks.
     */
    private void autoQcImages(UUID jobId, List<SceneDto> assembly, String imageFormat) {
        if (!qcEnabled) return;
        Map<String, String> dna = dnaAccessoryLines();
        int totalRerolls = 0;
        for (SceneDto a : assembly) {
            if (!a.hasImage()) continue;
            int seq = a.effectiveSeq();
            List<String> charIds = a.charactersOrEmpty();
            List<String> expected = new ArrayList<>();
            for (String id : charIds) {
                expected.add(dna.getOrDefault(id == null ? "" : id.toLowerCase(), id));
            }
            // Weather/time continuity (ep-2 audit: sunshine rendered during a
            // scripted rain beat). The QC fails hard contradictions only.
            String weather = nz(a.getWeather()).trim();
            String tod = nz(a.getTimeOfDay()).trim();
            if (!weather.isEmpty() || !tod.isEmpty()) {
                expected.add("Scene weather/time of day: "
                        + (weather.isEmpty() ? "unspecified" : weather)
                        + ", " + (tod.isEmpty() ? "unspecified" : tod));
            }
            for (int attempt = 0; attempt <= qcMaxRerollsPerScene; attempt++) {
                SceneImageQc.Result r = sceneImageQc.check(
                        java.nio.file.Paths.get(a.getImagePath()), expected, charIds);
                if (r.ok()) break;
                log.info("Job {} scene {} QC fail (attempt {}/{}): {}",
                        jobId, seq, attempt + 1, qcMaxRerollsPerScene + 1, r.issues());
                qcInsights.record(jobId, seq, r.issues(), "auto-qc");
                if (attempt >= qcMaxRerollsPerScene || totalRerolls >= qcMaxTotalRerolls) {
                    log.warn("Job {} scene {} still weak — keeping it (human gate is the backstop)", jobId, seq);
                    break;
                }
                // The QC knows WHAT it disliked — feed that into the re-roll as a
                // correction hint so it's targeted, not blind. Null-safe: an empty
                // issues list yields a blank hint = legacy blind re-roll.
                String qcHint = qcIssuesHint(r.issues());
                String newPath = regenScene(jobId, a, imageFormat, qcHint);
                if (newPath == null || newPath.isBlank()) break;
                a.setImagePath(newPath);
                totalRerolls++;
            }
        }
        if (totalRerolls > 0) log.info("Job {} QC rerolled {} scene image(s)", jobId, totalRerolls);
    }

    /**
     * Episode-ConsistencyState (Story B) — canon election. Runs ONCE per
     * episode, right after the image vision-QC pass in runAssetsStage, so only
     * QC-APPROVED stills can become canon. Per character the best scene still
     * of THIS episode is elected ({@link PropAnchorService#selectEpisodeAnchors}:
     * fewest characters in frame = least occlusion, hero phases preferred) and
     * persisted in {@code video_jobs.episode_anchors} (V27) together with the
     * rendered prop anchors — so every later re-roll (QC, Auto-Fix, manual,
     * end-stills) is conditioned on the same canon, even after a JVM restart.
     *
     * <p>Fail-safe by design: any failure or an empty selection (e.g. the image
     * paths don't exist, as in plain unit tests) leaves the column NULL and
     * every downstream path behaves exactly as before. An EXISTING canon is
     * never overwritten — a retry/restart keeps the episode's first-established
     * look stable instead of re-electing against re-rolled stills.
     */
    private void collectEpisodeAnchors(UUID jobId, List<SceneDto> assembly,
                                       List<PropAnchorService.Prop> propAnchors) {
        try {
            VideoJob job = load(jobId);
            if (job.getEpisodeAnchorsJson() != null && !job.getEpisodeAnchorsJson().isBlank()) {
                return; // canon already established — keep it stable
            }
            Map<String, String> chars = PropAnchorService.selectEpisodeAnchors(
                    assembly, this::fileExists);
            boolean haveProps = propAnchors != null && propAnchors.stream()
                    .anyMatch(p -> p != null && p.anchorPath() != null && !p.anchorPath().isBlank());
            if (chars.isEmpty() && !haveProps) return;
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode cn = root.putObject("characters");
            chars.forEach(cn::put);
            com.fasterxml.jackson.databind.node.ArrayNode pn = root.putArray("props");
            if (haveProps) {
                for (PropAnchorService.Prop p : propAnchors) {
                    if (p == null || p.anchorPath() == null || p.anchorPath().isBlank()) continue;
                    com.fasterxml.jackson.databind.node.ObjectNode o = pn.addObject();
                    o.put("name", p.name() == null ? "" : p.name());
                    o.put("keyword", p.keyword() == null ? "" : p.keyword());
                    o.put("imagePath", p.anchorPath());
                }
            }
            String json = mapper.writeValueAsString(root);
            retryOnConflict(jobId, j -> j.setEpisodeAnchorsJson(json));
            log.info("Job {} episode anchors registered: characters {} + {} prop ref(s)",
                    jobId, chars.keySet(), pn.size());
        } catch (Exception e) {
            log.warn("Job {} episode-anchor collection failed (non-fatal): {}",
                    jobId, e.getMessage());
        }
    }

    /**
     * Episode-ConsistencyState (Story B) — canon attachment. Decorates an
     * image-service scene payload with this episode's persisted canon:
     * <ul>
     *   <li>{@code episodeAnchors}: per in-scene character the QC-approved
     *       episode still (the provider attaches it as an extra reference image
     *       NEXT TO the bible refs);</li>
     *   <li>{@code propRefs}: the persisted prop anchors whose keyword occurs in
     *       this scene's text — so re-rolls keep prop canon too (the original
     *       batch attaches these itself; this only fills the gap when absent);</li>
     *   <li>a compact EPISODE CANON prompt clause on the scene text.</li>
     * </ul>
     * No canon on the job (null/blank column) → fall back to the SERIES canon
     * ({@link #attachSeriesAnchors}); without that too (no seriesId / flag off /
     * empty folder) the payload stays byte-identical to today. The priority is
     * therefore explicit: <b>own episode canon &gt; series canon &gt; bible refs
     * only</b> — the first image batch (and any pre-canon re-roll) of a series
     * episode is seeded from the previous episode's promoted anchors, and the
     * moment {@link #collectEpisodeAnchors} fills the column the episode's own
     * canon takes over automatically. Anchor files that no longer exist are
     * skipped. Never throws — a failure here only means a re-roll without canon.
     */
    private void attachEpisodeAnchors(Map<String, Object> imgScene, VideoJob job) {
        try {
            if (imgScene == null || job == null) return;
            String json = job.getEpisodeAnchorsJson();
            if (json == null || json.isBlank()) {
                attachSeriesAnchors(imgScene, job);
                return;
            }
            JsonNode root = mapper.readTree(json);

            // 1) Character anchors for THIS scene's cast (file must still exist).
            Set<String> sceneChars = sceneCharacterIds(imgScene);
            List<Map<String, String>> eps = new ArrayList<>();
            var charFields = root.path("characters").fields();
            while (charFields.hasNext()) {
                var e = charFields.next();
                String path = e.getValue().asText("");
                if (sceneChars.contains(e.getKey().toLowerCase()) && fileExists(path)) {
                    eps.add(Map.of("characterId", e.getKey(), "imagePath", path));
                }
            }
            if (!eps.isEmpty()) imgScene.put("episodeAnchors", eps);

            // 2) Prop canon on re-rolls: re-attach the persisted prop refs whose
            // keyword occurs in this scene's text (same match rule as the batch).
            String vd = String.valueOf(imgScene.getOrDefault("visualDesc", ""));
            if (!imgScene.containsKey("propRefs")) {
                String vdLower = vd.toLowerCase();
                List<Map<String, String>> refs = new ArrayList<>();
                for (JsonNode p : root.path("props")) {
                    String kw = p.path("keyword").asText("").toLowerCase();
                    String ip = p.path("imagePath").asText("");
                    if (!kw.isBlank() && !ip.isBlank() && vdLower.contains(kw) && fileExists(ip)) {
                        refs.add(Map.of("name", p.path("name").asText(""), "imagePath", ip));
                    }
                }
                if (!refs.isEmpty()) imgScene.put("propRefs", refs);
            }

            // 3) Compact episode-canon clause: identity/props match the episode,
            // lighting follows the NEW scene (the per-phase light arc must keep
            // working — canon locks WHAT things look like, not HOW they are lit).
            if (!vd.isBlank() && !vd.contains("EPISODE CANON:")) {
                imgScene.put("visualDesc", vd.trim()
                        + " EPISODE CANON: earlier scenes of this exact episode are canon — "
                        + "every character keeps the exact look already established in this "
                        + "episode's stills, and recurring props and set dressing keep exactly "
                        + "the colour, material and design they had earlier in this episode. "
                        + "Lighting and mood follow THIS scene's own time of day and weather.");
            }
        } catch (Exception e) {
            log.warn("attachEpisodeAnchors failed (continuing without canon): {}", e.getMessage());
        }
    }

    /** The scene payload's cast ids, trimmed + lowercased (the form both the
     *  episode-anchor json keys and the series anchor files use). */
    private static Set<String> sceneCharacterIds(Map<String, Object> imgScene) {
        Set<String> out = new HashSet<>();
        if (imgScene.get("characters") instanceof Collection<?> col) {
            for (Object c : col) {
                if (c != null) out.add(c.toString().trim().toLowerCase());
            }
        }
        return out;
    }

    /**
     * Series-ConsistencyState — SERIES canon seeding. Used only while the job
     * has no episode canon of its own (the {@link #attachEpisodeAnchors}
     * fallback): for a series job, the previous approved episode's promoted
     * anchors ({bible-dir}/refs/series/{seriesId}/{characterId}.png) are sent
     * as {@code episodeAnchors} — same payload field, same filter (only
     * characters actually in the scene, only files that exist) and the same
     * provider cap mechanics — but each entry is tagged {@code source:"series"}
     * so the image provider phrases the match clause honestly ("the previous
     * episode of this exact series", not "earlier in this exact episode").
     *
     * <p>Characters without a series file (they didn't appear in the previous
     * episode — e.g. the duckling joining mid-season) are skipped silently;
     * their bible refs remain the baseline. Strict no-op when the flag is off,
     * the job has no seriesId (every state-machine-test job), or the folder
     * doesn't exist. Never throws.
     */
    private void attachSeriesAnchors(Map<String, Object> imgScene, VideoJob job) {
        try {
            if (!seriesAnchorsEnabled) return;
            if (job.getSeriesId() == null || job.getSeriesId().isBlank()) return;
            if (imgScene.containsKey("episodeAnchors")) return;
            java.nio.file.Path dir = seriesAnchorsDir(job.getSeriesId());
            if (dir == null || !java.nio.file.Files.isDirectory(dir)) return;

            Map<String, String> seeds = SeriesAnchors.selectSeeds(
                    sceneCharacterIds(imgScene),
                    id -> dir.resolve(id + ".png").toString(),
                    this::fileExists);
            if (seeds.isEmpty()) return;
            List<Map<String, String>> eps = new ArrayList<>();
            seeds.forEach((id, path) -> eps.add(Map.of(
                    "characterId", id, "imagePath", path, "source", "series")));
            imgScene.put("episodeAnchors", eps);

            // Compact series-canon clause — the cross-episode sibling of the
            // EPISODE CANON clause: identity comes from the previous episode,
            // lighting/mood stay with THIS scene.
            String vd = String.valueOf(imgScene.getOrDefault("visualDesc", ""));
            if (!vd.isBlank() && !vd.contains("SERIES CANON:") && !vd.contains("EPISODE CANON:")) {
                imgScene.put("visualDesc", vd.trim()
                        + " SERIES CANON: this is a new episode of an existing series — "
                        + "every returning character keeps the exact look established in "
                        + "the previous episode's approved stills (feather colours and "
                        + "markings, accessories, proportions and relative size). Lighting "
                        + "and mood follow THIS scene's own time of day and weather.");
            }
        } catch (Exception e) {
            log.warn("attachSeriesAnchors failed (continuing without series canon): {}",
                    e.getMessage());
        }
    }

    /**
     * {bible-dir}/refs/series/{seriesId} — the durable home of a series'
     * promoted character anchors. bible-dir is the parent of
     * {@code props.bible().path()}, i.e. the same refs/ tree BibleEditor and
     * BrandController use ({@code bible/refs}); it is mounted read-write on the
     * orchestrator and survives workdir cleanups, which is exactly why the
     * anchors are promoted INTO it instead of referenced in the workdir.
     *
     * <p>Null-safe by contract: a null/blank/unsafe seriesId, missing
     * properties, a blank bible path (or any resolution surprise) → null, and
     * every caller no-ops. The state-machine test's non-existent bible file
     * ("no-such-bible-for-this-test.yml", no parent) resolves to the same
     * "refs"-relative fallback BibleEditor.refsDir() uses — and is never
     * touched because those jobs carry no seriesId.
     */
    private java.nio.file.Path seriesAnchorsDir(String seriesId) {
        try {
            String sid = SeriesAnchors.sanitizeId(seriesId);
            if (sid == null) return null;
            if (props == null || props.bible() == null) return null;
            String biblePath = props.bible().path();
            if (biblePath == null || biblePath.isBlank()) return null;
            java.nio.file.Path parent = java.nio.file.Paths.get(biblePath).getParent();
            java.nio.file.Path refs = parent == null
                    ? java.nio.file.Paths.get("refs") : parent.resolve("refs");
            return refs.resolve("series").resolve(sid);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Series-ConsistencyState — promotion at human approval. Called from
     * {@link #runUploadStage} right after the upload result is persisted (the
     * same best-effort spot as the series-playlist add): an episode that made
     * it PAST every human gate onto YouTube is by definition approved, so its
     * episode character anchors are copied to the durable series folder,
     * OVERWRITING what's there — the newest approved episode wins, keeping the
     * series canon fresh instead of frozen at episode 1.
     *
     * <p>NOTE lifestage changes (the chicks grow up, the duckling fledges): a
     * DELIBERATE look change is done by emptying {bible-dir}/refs/series/
     * {seriesId}/ (and updating the bible refs) — the next approved episode
     * then re-establishes the series canon from scratch. See the README this
     * method drops in refs/series/.
     *
     * <p>Best-effort: no flag / no seriesId (every state-machine-test job) /
     * no episode canon / vanished source files → silent no-op; any I/O failure
     * is logged and swallowed — promotion can never break an upload.
     */
    private void promoteSeriesAnchors(UUID jobId, VideoJob job) {
        try {
            if (!seriesAnchorsEnabled || job == null) return;
            if (job.getSeriesId() == null || job.getSeriesId().isBlank()) return;
            Map<String, String> picks = SeriesAnchors.selectPromotions(
                    job.getEpisodeAnchorsJson(), this::fileExists);
            if (picks.isEmpty()) return;
            java.nio.file.Path dir = seriesAnchorsDir(job.getSeriesId());
            if (dir == null) return;
            java.nio.file.Files.createDirectories(dir);
            writeSeriesAnchorsReadme(dir.getParent());
            int copied = 0;
            for (var e : picks.entrySet()) {
                try {
                    java.nio.file.Files.copy(
                            java.nio.file.Paths.get(e.getValue()),
                            dir.resolve(e.getKey() + ".png"),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    copied++;
                } catch (Exception one) {
                    log.warn("Job {} series-anchor copy failed for '{}' (non-fatal): {}",
                            jobId, e.getKey(), one.getMessage());
                }
            }
            if (copied > 0) {
                log.info("Job {} promoted {} character anchor(s) to series canon {} ({})",
                        jobId, copied, dir, picks.keySet());
            }
        } catch (Exception e) {
            log.warn("Job {} series-anchor promotion failed (non-fatal): {}",
                    jobId, e.getMessage());
        }
    }

    /** Drops a one-time README.md in {bible-dir}/refs/series/ explaining the
     *  folder's lifecycle (promotion, seeding, ducklings, lifestage resets).
     *  Never overwrites a hand-edited one; never throws. */
    private void writeSeriesAnchorsReadme(java.nio.file.Path seriesRoot) {
        try {
            if (seriesRoot == null) return;
            java.nio.file.Path readme = seriesRoot.resolve("README.md");
            if (java.nio.file.Files.exists(readme)) return;
            java.nio.file.Files.createDirectories(seriesRoot);
            java.nio.file.Files.writeString(readme, """
                    # Series anchors — refs/series/{seriesId}/{characterId}.png

                    Written automatically by the orchestrator (flag:
                    `app.series.anchors-enabled`, default true).

                    * **Promotion** — when a series episode passes every human gate and
                      its YouTube upload succeeds, the episode's QC-approved character
                      anchors are copied here, overwriting what exists: the NEWEST
                      approved episode defines the series canon.
                    * **Seeding** — the next episode of the same series sends these
                      files as extra reference images on its FIRST image batch (and any
                      re-roll before its own canon is elected), so episode N+1's cast
                      matches episode N — not just the bible. As soon as the new episode
                      elects its own episode anchors, those take priority:
                      episode canon > series canon > bible refs.
                    * **New characters** — a character that did not appear in the
                      previous episode has no file here and is skipped silently; its
                      `bible/refs/{id}.png` anchors remain the baseline.
                    * **Deliberate look changes** (lifestage: chicks grow up, the
                      duckling fledges, a new outfit) — EMPTY this series' folder (and
                      update the bible refs); the next approved episode re-establishes
                      the canon with the new look. Otherwise seeding would keep pulling
                      the cast back to their old appearance.
                    """);
        } catch (Exception e) {
            log.debug("series-anchors README write skipped: {}", e.getMessage());
        }
    }

    /** Re-generate a single scene's still from its current assembly fields. */
    private String regenScene(UUID jobId, SceneDto a, String imageFormat) {
        return regenScene(jobId, a, imageFormat, null);
    }

    /**
     * Re-roll one scene's still. The optional {@code correctionHint} (null/blank
     * = exactly the legacy blind re-roll) is passed through to the image-service
     * as {@code correctionHint} on the scene payload, where anchor-capable
     * providers append it as a high-priority correction clause — so a QC fail
     * reason, a Critic finding or the user's own "what's wrong" note steers the
     * re-roll instead of a blind re-generation.
     */
    private String regenScene(UUID jobId, SceneDto a, String imageFormat, String correctionHint) {
        try {
            int seq = a.effectiveSeq();
            Map<String, Object> m = new HashMap<>();
            m.put("seq", seq);
            m.put("visualDesc", nz(a.getVisualDesc()));
            m.put("characters", a.charactersOrEmpty());
            m.put("locationId", nz(a.getLocationId()));
            m.put("timeOfDay", nz(a.getTimeOfDay()));
            m.put("weather", nz(a.getWeather()));
            if (correctionHint != null && !correctionHint.isBlank()) {
                m.put("correctionHint", correctionHint.trim());
            }
            // Episode-ConsistencyState: a QC/Auto-Fix re-roll must match the
            // episode's canon. During the first batch (autoQcImages before the
            // canon is collected) there are no episode anchors yet — a series
            // job then falls back to its SERIES anchors; otherwise no-op.
            attachEpisodeAnchors(m, repo.findById(jobId).orElse(null));
            JsonNode resp = imageClient.generate(jobId, List.of(m), imageFormat);
            for (JsonNode n : resp.path("scenes")) {
                if (n.path("seq").asInt() == seq) return n.path("imagePath").asText(null);
            }
        } catch (Exception e) {
            log.warn("Job {} QC reroll image-gen failed: {}", jobId, e.getMessage());
        }
        return null;
    }

    /** Compress a QC/Critic issue list into a short, prompt-friendly correction
     *  hint. Null/empty → null (= legacy blind re-roll). Capped so a verbose QC
     *  report can't bloat the image prompt's terminal tokens. */
    private static String qcIssuesHint(List<String> issues) {
        if (issues == null || issues.isEmpty()) return null;
        String joined = issues.stream()
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .collect(java.util.stream.Collectors.joining("; "));
        if (joined.isBlank()) return null;
        final int CAP = 400;
        if (joined.length() > CAP) joined = joined.substring(0, CAP - 1) + "…";
        return joined;
    }

    /** script-service caps brief at @Size(max=4000). The creative brief always
     *  wins over nice-to-have memory: the memory block is shortened to fit, and
     *  dropped entirely when fewer than ~200 usable chars remain — a mangled
     *  memory fragment is worse than none. */
    private static String prependMemory(String memory, String brief) {
        final int MAX = 4000;
        String own = brief == null ? "" : brief;
        int room = MAX - own.length() - 2;          // "\n\n" separator
        if (room < 200) return brief;               // no sensible space — skip memory
        if (memory.length() > room) {
            memory = memory.substring(0, room - 1) + "…";
        }
        return own.isBlank() ? memory : memory + "\n\n" + own;
    }

    /** Fallback emotion per episode phase, for legacy scripts whose scenes
     *  carry no Shot-DNA emotion. Guarantees every voice line ships WITH a
     *  tag (100% coverage) so the TTS always acts instead of reading flat. */
    private static String phaseDefaultEmotion(String phase) {
        return switch (phase == null ? "" : phase) {
            case "hook" -> "excited surprise (4/5)";
            case "setup" -> "curious (3/5)";
            case "development" -> "curious wonder (3/5)";
            case "climax" -> "amazed joy (5/5)";
            case "resolution" -> "warm content (3/5)";
            case "closer" -> "warm gentle (3/5)";
            default -> "warm curious (3/5)";
        };
    }

    /** Builds character id -> "Name: <colour> chick wearing <accessory>; eyes ...;
     *  silhouette ...; size ..." from bible DNA. The extra drift fields (eye
     *  colour, silhouette, scale anchor) let the vision-QC catch the subtle
     *  cross-scene drift a human spots instantly (wrong iris colour, missing
     *  head detail, a chick rendered too big vs the trio). */
    private Map<String, String> dnaAccessoryLines() {
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                if (id.isBlank()) continue;
                String name = ch.path("name").asText(id);
                JsonNode dna = ch.path("dna");
                String color = dna.path("coreColor").asText("").trim();
                String acc = dna.path("accessory").asText("").trim();
                String anti = dna.path("antiAccessory").asText("").trim();
                String eyes = dna.path("eyeColor").asText("").trim();
                String silhouette = dna.path("silhouette").asText("").trim();
                String scale = dna.path("scaleAnchor").asText("").trim();
                StringBuilder b = new StringBuilder(name).append(": ");
                if (!color.isBlank()) b.append(color).append(" chick");
                if (!acc.isBlank()) b.append(color.isBlank() ? "chick " : " ").append("wearing ").append(acc);
                if (!anti.isBlank()) b.append(" — must NOT wear ").append(anti);
                if (!eyes.isBlank()) b.append(" | eye colour: ").append(eyes);
                if (!silhouette.isBlank()) b.append(" | silhouette: ").append(silhouette);
                if (!scale.isBlank()) b.append(" | size: ").append(scale);
                out.put(id, b.toString());
            }
        } catch (Exception e) {
            log.warn("QC dna load failed: {}", e.getMessage());
        }
        return out;
    }

    private static final int END_STILL_SEQ_OFFSET = 900;

    /**
     * Best-effort end-pose still generation (P6 — directed motion, used sparingly).
     *
     * <p>End frames are only worth their cost + morph risk on the cinematic HERO
     * beats (hook/climax), so this skips every non-hero scene even in full-Veo
     * mode — bulk scenes stay start-only. For each qualifying scene carrying a
     * Shot-DNA {@code endPose}, it generates a SECOND still that is locked to the
     * start's identity/framing (same anchors + an explicit "only the pose changes"
     * brief), so Veo interpolates a clean start→end move instead of morphing
     * between two slightly different chicks. Failures are swallowed (the scene
     * simply runs without an end frame).
     *
     * @return map of scene seq -> generated end-still path.
     */
    private Map<Integer, String> generateEndStills(UUID jobId, String imageFormat,
                                                   List<SceneDto> veoCandidates) {
        Map<Integer, String> out = new HashMap<>();
        // Story B: end-stills are conditioned on the episode canon too (null-safe
        // — without a job/canon attachEpisodeAnchors is a no-op).
        VideoJob epJob = repo.findById(jobId).orElse(null);
        for (SceneDto a : veoCandidates) {
            // P6 — gericht: only hero (hook/climax) beats earn a directed end frame.
            if (!isHeroPhase(a)) continue;
            String endPose = nz(a.getEndPose()).trim();
            if (endPose.isEmpty() || "null".equals(endPose)) continue;
            int seq = a.effectiveSeq();
            try {
                // P6 — identity lock: keep EVERYTHING the start still nailed
                // (character, outfit, colours, background, framing) and change only
                // the pose, so the start→end interpolation reads as one character
                // moving, not a morph between two different-looking ones.
                String lockedDesc = "Same scene and the EXACT same character(s), outfit, "
                        + "colours, accessories, background and camera framing as the start "
                        + "image — do NOT change identity, props or composition. ONLY change "
                        + "the pose/action to: " + endPose;
                Map<String, Object> endScene = new HashMap<>();
                endScene.put("seq", END_STILL_SEQ_OFFSET + seq);
                endScene.put("visualDesc", lockedDesc);
                endScene.put("characters", a.charactersOrEmpty());
                endScene.put("locationId", nz(a.getLocationId()));
                endScene.put("timeOfDay", nz(a.getTimeOfDay()));
                endScene.put("weather", nz(a.getWeather()));
                // Match the start's framing so only the pose differs, not the shot.
                endScene.put("cameraFraming", nz(a.getCameraFraming()));
                attachEpisodeAnchors(endScene, epJob);   // Story B: episode canon
                JsonNode resp = imageClient.generate(jobId, List.of(endScene), imageFormat);
                for (JsonNode n : resp.path("scenes")) {
                    String pth = n.path("imagePath").asText("");
                    if (!pth.isBlank()) { out.put(seq, pth); break; }
                }
                log.info("Job {} end-still for scene {} -> {}", jobId, seq, out.get(seq));
            } catch (Exception e) {
                log.warn("Job {} end-still gen failed for scene {} (continuing without): {}",
                        jobId, seq, e.getMessage());
            }
        }
        return out;
    }

    /** Builds the video-generation-service request maps (HTTP payload shape —
     *  stays {@code Map<String,Object>}) from the typed assembly scenes. */
    private List<Map<String, Object>> buildVeoScenes(List<SceneDto> assembly,
                                                     Map<Integer, String> endStills) {
        int last = assembly.stream().mapToInt(SceneDto::effectiveSeq).max().orElse(1);
        List<Map<String, Object>> out = new ArrayList<>();
        // G5 — inter-clip continuity: remember the previous scene's type + end
        // pose so two back-to-back hero clips flow instead of jumping. Null on a
        // single-scene reroll, so the continuity clause is simply never emitted.
        String prevType = null;
        String prevEndPose = null;
        // Board-audit — auto-establishing: the previous scene's PHASE identifies
        // the one setup beat that directly follows the hook. Null on a
        // single-scene reroll, so the establishing rule never fires there.
        String prevPhase = null;
        // Frame-chaining state: DIRECTLY consecutive scenes (seq N, N+1) in the
        // SAME location with the SAME cast form a chain group. The video-gen
        // service renders a group sequentially and starts each next clip on the
        // extracted LAST FRAME of the previous one — pixel-level continuity
        // instead of the textual G5 hint alone. Group id = seq of first member.
        Integer chainGroupId = null;
        int prevSeq = Integer.MIN_VALUE;
        String prevLoc = null;
        Set<String> prevCast = null;
        Map<String, Object> prevM = null;
        for (SceneDto a : assembly) {
            int seq = a.effectiveSeq();
            // Scene-type routing for Veo model selection. First/last scenes are
            // intro/outro; in between we use the episode PHASE so the climax (and
            // any hook beyond scene 1) gets the high-quality "hero" model instead
            // of defaulting everything to "standard" (lite).
            String phase = nz(a.getPhase()).toLowerCase();
            String type;
            if (seq == 1) type = "intro";
            else if (seq == last) type = "outro";
            else type = switch (phase) {
                case "hook", "climax" -> "hero";
                case "closer"         -> "outro";
                default               -> "standard";
            };
            Map<String, Object> m = new HashMap<>();
            m.put("seq", seq);
            m.put("sceneType", type);
            m.put("startImagePath", a.getImagePath());
            // Veo is image-to-video: the still is frame 1, so the prompt must
            // describe MOTION, not a static composition. Prefer the script's
            // dedicated motionDesc (start→end movement, written for hero scenes);
            // fall back to the static visualDesc/narration when there's no motion
            // brief. veoPromptCompiler.compile wraps it with camera/world/tics/identity.
            String motionDesc = nz(a.getMotionDesc());
            String vd = (!motionDesc.isBlank() && !"null".equals(motionDesc))
                    ? motionDesc
                    : (a.getVisualDesc() != null ? a.getVisualDesc() : nz(a.getNarration()));
            // Inject each present character's signature tic (bible dna.tic) so the
            // animation is characteristic — Bo pushes his glasses up, Pip tips her
            // hat back, Mo gives a slow blink.
            List<String> sceneChars = a.charactersOrEmpty();
            String locationId = nz(a.getLocationId());
            String timeOfDay = nz(a.getTimeOfDay());
            String weather = nz(a.getWeather());
            String goal = nz(a.getGoal());
            String emotion = nz(a.getEmotion());
            String motionSpeed = nz(a.getMotionSpeed());
            String compiled = veoPromptCompiler.compile(vd, phase, sceneChars, locationId,
                    timeOfDay, weather, goal, emotion, motionSpeed);
            // G5 — when this hero clip directly follows another hero clip, tell
            // VEO to start from where the previous one ended so the cut flows.
            if ("hero".equals(type) && "hero".equals(prevType)
                    && prevEndPose != null && !prevEndPose.isBlank()) {
                compiled = compiled + "Continuity: this shot continues seamlessly from the "
                        + "previous shot, which ended with " + prevEndPose.trim()
                        + ". Begin on that same pose, lighting and framing, then carry the "
                        + "motion forward — no jump, no reset. ";
            }
            // Board-audit — auto-establishing: the FIRST setup scene (the one
            // beat that directly follows the hook) is a wide establishing shot,
            // so right after the intimate extreme-close-up hook the viewer gets
            // the geography. Deterministic and once per video: phases are
            // contiguous, so exactly one setup scene has a hook as predecessor.
            boolean firstSetupScene = "setup".equals(phase) && "hook".equals(prevPhase);
            // Story C (shot-size variation) — the camera-bible is keyed per phase,
            // so consecutive bulk (setup/development) beats would otherwise share
            // the SAME framing and read as an "AI preset". Rotate the shot size by
            // seq so the montage breathes establish → medium → close. Hero
            // (hook/climax) and intro/outro keep their deliberate cinematic framing.
            // The first setup scene skips the rotation: its framing is the
            // mandatory wide establishing shot.
            if ("standard".equals(type)) {
                if (firstSetupScene) {
                    String locName = (locationId.isBlank() || "null".equals(locationId))
                            ? "the scene's location"
                            : locationId.replace('-', ' ');
                    compiled = compiled + "Framing: wide establishing shot of " + locName
                            + " — show the whole setting and where the character is within "
                            + "it, so the viewer instantly understands the geography after "
                            + "the close-up hook. ";
                } else {
                    String[] sizes = {
                            "a wider shot that lets the setting read around the character",
                            "a medium shot framing the character from the chest up",
                            "a closer shot favouring the character's face and expression"
                    };
                    compiled = compiled + "Shot framing for rhythm: make this beat "
                            + sizes[Math.floorMod(seq, sizes.length)] + " (vary the framing from the "
                            + "neighbouring shots so the cut feels edited, not a fixed camera). ";
                }
            }
            // Story C (eyeline / 180° staging) — when two or more chicks share the
            // frame, give Veo explicit screen-direction so they actually look AT
            // each other and keep stable left/right positions, instead of facing
            // the camera in parallel. Prompt-only — no extra clips, no extra cost.
            if (sceneChars.size() >= 2) {
                compiled = compiled + "Staging: keep each character on a stable side "
                        + "of the frame (consistent left/right placement) and have them "
                        + "face and look TOWARD whoever they are interacting with — real "
                        + "eyelines between them, not both staring at the camera. Hold the "
                        + "180-degree axis so left stays left and right stays right. "
                        // Board #16 — the listener ACTS: empty-faced bystanders are
                        // the deadest tell of AI animation. Whoever is not speaking
                        // visibly processes what they hear.
                        + "The LISTENING character visibly reacts while the other acts or "
                        + "speaks — a head tilt, leaning in, eyes widening or following "
                        + "the action — never standing idle with a blank face. ";
            }
            // Board #21 — auto-establishing: on a LOCATION CHANGE the first shot
            // breathes wider for a beat so a child instantly knows where we are.
            // Skipped when the full wide-establishing rule above already fired —
            // two framing instructions on one shot contradict each other.
            if (!firstSetupScene
                    && prevLoc != null && !locationId.isBlank() && !"null".equals(locationId)
                    && !locationId.equalsIgnoreCase(prevLoc)) {
                compiled = compiled + "New location: open this shot a touch WIDER for the "
                        + "first beat so the setting reads instantly (a brief establishing "
                        + "breath), then settle into the framing described above. ";
            }
            m.put("visualDesc", compiled);
            m.put("negativePrompt", VEO_NEGATIVE);
            // FROZEN-TAIL GUARD (audit #9): when the voice runs longer than the
            // scripted scene duration, assembly stretches the scene — but a Veo
            // clip ordered at the SCRIPTED length gets tpad-cloned, i.e. a
            // frozen last frame for the overhang. Order the clip at the REAL
            // voice length (known exactly via lineTimings) instead, capped at
            // Veo's 8s. Scenes without timing keep the scripted duration.
            int veoDur = a.getDurationSeconds().intValue();
            List<Map<String, Object>> lts = a.getLineTimings();
            if (lts != null && !lts.isEmpty()) {
                long endMs = 0;
                for (Map<String, Object> mm : lts) {
                    if (mm != null) {
                        long ls = (mm.get("startMs") instanceof Number n1) ? n1.longValue() : 0;
                        long ld = (mm.get("durMs") instanceof Number n2) ? n2.longValue() : 0;
                        endMs = Math.max(endMs, ls + ld);
                    }
                }
                int voiceSec = (int) Math.ceil(endMs / 1000.0);
                if (voiceSec > veoDur) {
                    int effective = Math.min(8, voiceSec);
                    if (effective > veoDur) {
                        log.info("Scene {} voice runs {}s > scripted {}s — ordering Veo clip "
                                + "at {}s to avoid a frozen tail", seq, voiceSec, veoDur, effective);
                        veoDur = effective;
                    }
                }
            }
            m.put("durationSeconds", veoDur);
            // Cast ids — the video-gen service attaches each character's
            // approved reference stills (bible/refs) to the Veo call, so
            // identity is anchored in PIXELS, not just prompt text (audit #5).
            m.put("characters", sceneChars);
            // Directed motion: if an end-pose still was generated, hand Veo the
            // last frame so it interpolates start→end instead of guessing.
            String endPath = endStills == null ? null : endStills.get(seq);
            if (endPath != null && !endPath.isBlank()) m.put("endImagePath", endPath);
            out.add(m);
            // Frame-chaining: link this scene to the previous one when they are
            // directly consecutive, in the same location, with the same cast.
            Set<String> cast = new HashSet<>();
            for (String c : sceneChars) if (c != null) cast.add(c.toLowerCase());
            boolean chainable = frameChainingEnabled
                    && seq == prevSeq + 1
                    && !locationId.isBlank() && !"null".equals(locationId)
                    && locationId.equalsIgnoreCase(prevLoc)
                    && cast.equals(prevCast);
            if (chainable && prevM != null) {
                if (chainGroupId == null) {
                    chainGroupId = prevSeq;
                    prevM.put("chainGroup", chainGroupId);
                }
                m.put("chainGroup", chainGroupId);
            } else {
                chainGroupId = null;
            }
            prevSeq = seq;
            prevLoc = locationId;
            prevCast = cast;
            prevM = m;
            prevType = type;
            prevPhase = phase;
            prevEndPose = nz(a.getEndPose());
        }
        return out;
    }

    /** Stability negative prompt for every Veo clip — the failure modes that
     *  image-to-video models hit (identity/accessory drift, flicker, morphing). */
    private static final String VEO_NEGATIVE =
            "morphing, warping, identity change, face flicker, feature drift, "
            + "extra limbs, extra wings, duplicate character, second chicken, "
            + "colour shift, accessory change, hat disappearing, glasses disappearing, "
            // Hat-removal drift — Veo read Pip's old "tips her hat back" tic as
            // "takes the hat off and puts it on the ground" (ep-3 feedback).
            + "taking the hat off, hat off the head, hat in the wing, hat held in a wing, "
            + "hat on the ground, removing an accessory, putting down the hat, "
            + "scarf changing, accessory on the wrong chicken, hat on the wrong chicken, "
            + "glasses on the wrong chicken, swapped accessories, extra hat, extra glasses, "
            + "two accessories on one chicken, deformed beak, deformed eyes, melting, jitter, "
            // Body-proportion drift — a chick suddenly rendering thin/stretched mid-clip.
            + "thin chicken, skinny chicken, slim chicken, elongated body, stretched body, "
            + "lanky chicken, deformed proportions, body shrinking, body stretching, "
            + "changing body size, "
            // Presence drift — a character walking/fading out of frame mid-clip,
            // or a new character/animal wandering in (user-audit: "characters
            // verdwijnen ineens" / "soms te veel characters").
            + "character disappearing, character vanishing, character fading out, "
            + "character leaving the frame, character exiting frame, walking out of frame, "
            + "character dissolving, empty frame, new character entering, extra character "
            + "appearing, additional chicken appearing, background chickens, crowd of chicks, "
            + "strobing, fast motion, frantic camera, text, captions, watermark, logo";

    /** Appends the episode's optional recurring motif to a scene's visual
     *  description (so it shows up in every image prompt). No-op when unset. */
    private String withMotif(String visualDesc, VideoJob job) {
        String motif = job == null ? null : job.getRecurringMotif();
        if (motif == null || motif.isBlank()) return visualDesc;
        String vd = visualDesc == null ? "" : visualDesc.trim();
        return vd + " Recurring background motif (subtle, in the scene): " + motif.trim() + ".";
    }

    /** Reads an int field that may be absent or JSON null → boxed Integer/null. */
    private Integer nullableInt(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asInt();
    }

    private JsonNode readBible() throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(props.bible().path());
        return new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(p.toFile());
    }


    private void applyClipPaths(List<SceneDto> assembly, JsonNode clipsResp) {
        Map<Integer, String> clipBySeq = new HashMap<>();
        for (JsonNode c : clipsResp.path("clips")) {
            if ("OK".equals(c.path("status").asText())) {
                clipBySeq.put(c.path("seq").asInt(), c.path("clipPath").asText());
            }
        }
        for (SceneDto a : assembly) {
            String p = clipBySeq.get(a.effectiveSeq());
            if (p != null) a.setClipPath(p);
        }
    }

    /** Max QC-triggered Veo re-rolls per job — keeps the worst case bounded
     *  (each re-roll costs a fresh clip generation). */
    private static final int CLIP_QC_MAX_REROLLS = 2;

    /**
     * Output-side Veo QC gate. For every scene that got a clip, the three QC
     * frames (first/mid/last, extracted by video-generation-service next to
     * clip.mp4) are vision-checked against the scene's cast DNA: exact
     * headcount per frame, no disappearance across frames, no accessory/colour
     * swaps between frames. A failing clip gets ONE re-roll; if the re-roll
     * also fails, the clipPath is dropped and clip.mp4 deleted so assembly
     * falls back to the scene's already-QC'd still (Ken Burns). Fail-safe:
     * QC errors pass, never block.
     */
    private void clipQcGate(UUID jobId, VideoFormat format,
                            List<SceneDto> assemblyScenes,
                            List<Map<String, Object>> veoScenes) {
        Map<Integer, Map<String, Object>> veoBySeq = new HashMap<>();
        for (Map<String, Object> v : veoScenes) {
            veoBySeq.put(((Number) v.get("seq")).intValue(), v);
        }
        Map<String, String> dna = dnaAccessoryLines();
        int rerolls = 0;
        for (SceneDto a : assemblyScenes) {
            if (!a.hasClip()) continue;
            int seq = a.effectiveSeq();
            List<String> charIds = a.charactersOrEmpty();
            List<String> expected = new ArrayList<>();
            for (String id : charIds) {
                expected.add(dna.getOrDefault(id == null ? "" : id.toLowerCase(), id));
            }
            java.nio.file.Path clip = java.nio.file.Paths.get(a.getClipPath());

            ClipQc.Result r = clipQc.check(clip, expected, charIds);
            if (r.ok()) continue;
            log.warn("Job {} scene {} clip FAILED QC: {}", jobId, seq, r.issues());
            qcInsights.record(jobId, seq, r.issues(), "clip-qc");

            // One re-roll: same scene request (fresh sample), same output path.
            Map<String, Object> veoScene = veoBySeq.get(seq);
            if (veoScene != null && rerolls < CLIP_QC_MAX_REROLLS) {
                rerolls++;
                try {
                    log.info("Job {} scene {} clip QC re-roll {}/{}", jobId, seq,
                            rerolls, CLIP_QC_MAX_REROLLS);
                    JsonNode resp = videoGenClient.generateAsync(
                            jobId, format.imageFormat, List.of(veoScene));
                    applyClipPaths(assemblyScenes, resp);
                    if (a.getClipPath() != null) {
                        ClipQc.Result r2 = clipQc.check(
                                java.nio.file.Paths.get(a.getClipPath()),
                                expected, charIds);
                        if (r2.ok()) {
                            log.info("Job {} scene {} clip QC re-roll PASSED", jobId, seq);
                            continue;
                        }
                        log.warn("Job {} scene {} clip re-roll ALSO failed QC: {}",
                                jobId, seq, r2.issues());
                        qcInsights.record(jobId, seq, r2.issues(), "clip-qc-retry");
                    }
                } catch (Exception e) {
                    log.warn("Job {} scene {} clip QC re-roll errored: {}",
                            jobId, seq, e.getMessage());
                }
            }

            // Still bad → drop the clip; assembly uses the QC'd still instead.
            // (null field = absent key on serialisation, same as map.remove)
            a.setClipPath(null);
            try {
                java.nio.file.Files.deleteIfExists(clip);
            } catch (Exception e) {
                log.warn("Job {} scene {} could not delete rejected clip: {}",
                        jobId, seq, e.getMessage());
            }
            log.warn("Job {} scene {} clip rejected by QC — falling back to Ken Burns still",
                    jobId, seq);
        }
    }

    /** An empty {"scenes":[]} response — used when a stage has nothing to do
     *  (e.g. all images/voice already exist on a retry) so mergeAssets simply
     *  keeps the existing paths. */
    private JsonNode emptyScenesResponse() {
        com.fasterxml.jackson.databind.node.ObjectNode n = mapper.createObjectNode();
        n.set("scenes", mapper.createArrayNode());
        return n;
    }

    private void mergeAssets(List<SceneDto> assembly, JsonNode voice, JsonNode image) {
        Map<Integer, String> audio = new HashMap<>();
        Map<Integer, String> imgs = new HashMap<>();
        Map<Integer, JsonNode> timings = new HashMap<>();
        voice.path("scenes").forEach(n -> {
            audio.put(n.path("seq").asInt(), n.path("audioPath").asText());
            JsonNode lt = n.path("lineTimings");
            if (lt.isArray() && lt.size() > 0) timings.put(n.path("seq").asInt(), lt);
        });
        image.path("scenes").forEach(n -> imgs.put(n.path("seq").asInt(), n.path("imagePath").asText()));
        for (SceneDto a : assembly) {
            int seq = a.effectiveSeq();
            // Per-line voice timing → flows into the assembly request so the
            // SRT gets one cue per LINE instead of one per scene (audit #3).
            JsonNode lt = timings.get(seq);
            if (lt != null) {
                try {
                    a.setLineTimings(mapper.convertValue(lt,
                            new TypeReference<List<Map<String, Object>>>() {}));
                } catch (Exception ignore) { /* timing is an enhancement, never fatal */ }
            }
            // Non-destructive: only overwrite when the response actually carries
            // a value for this scene, so reused (skipped) scenes keep their
            // existing image/voice paths on a retry.
            String newAudio = audio.get(seq);
            String newImg = imgs.get(seq);
            if (newAudio != null && !newAudio.isBlank()) a.setAudioPath(newAudio);
            if (newImg != null && !newImg.isBlank())     a.setImagePath(newImg);
        }
    }

    private List<SceneDto> loadAssemblyScenes(VideoJob job) {
        if (job.getAssemblyScenesJson() == null || job.getAssemblyScenesJson().isBlank()) {
            return new ArrayList<>();
        }
        try {
            return mapper.readValue(job.getAssemblyScenesJson(),
                    new TypeReference<List<SceneDto>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("Could not read assemblyScenes JSON", e);
        }
    }

    private String serialise(List<SceneDto> scenes) {
        try { return mapper.writeValueAsString(scenes); }
        catch (Exception e) { throw new IllegalStateException("Could not write assemblyScenes JSON", e); }
    }

    // ─────────────────────────── DB writes ───────────────────────────

    private VideoJob load(UUID id) {
        return repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job " + id));
    }

    /** Max attempts for {@link #retryOnConflict}: 1 initial try + 2 retries. */
    private static final int OPTIMISTIC_RETRY_MAX_ATTEMPTS = 3;

    /**
     * Optimistic-lock-aware load→mutate→save (V26, @Version on VideoJob).
     * Every attempt reloads the FRESHEST row and re-applies the mutation, so a
     * concurrent writer's committed changes are preserved instead of lost.
     *
     * Deliberately NOT @Transactional: repo.save() runs in its own short
     * transaction (SimpleJpaRepository.save is @Transactional), so the version
     * check fires inside the save call where this loop can catch
     * OptimisticLockingFailureException and retry. With an outer transaction
     * the conflict would only surface at commit, after the catch.
     *
     * A missing row is a silent no-op — the same contract the old
     * {@code repo.findById(id).ifPresent(...)} helpers had. After
     * {@value #OPTIMISTIC_RETRY_MAX_ATTEMPTS} conflicting attempts the
     * exception propagates to the caller (stage catch-blocks fail the job
     * loudly; controllers surface a 500 the user can retry).
     */
    void retryOnConflict(UUID id, java.util.function.Consumer<VideoJob> mutation) {
        for (int attempt = 1; ; attempt++) {
            Optional<VideoJob> found = repo.findById(id);
            if (found.isEmpty()) return;
            try {
                VideoJob j = found.get();
                mutation.accept(j);
                repo.save(j);
                return;
            } catch (org.springframework.dao.OptimisticLockingFailureException e) {
                if (attempt >= OPTIMISTIC_RETRY_MAX_ATTEMPTS) {
                    log.warn("Job {} optimistic-lock conflict persisted after {} attempts — rethrowing",
                            id, attempt);
                    throw e;
                }
                log.info("Job {} optimistic-lock conflict (attempt {}/{}) — reloading and retrying",
                        id, attempt, OPTIMISTIC_RETRY_MAX_ATTEMPTS);
            }
        }
    }

    public void mark(UUID id, JobStatus s, String step) {
        retryOnConflict(id, j -> { j.setStatus(s); j.setStep(step); });
    }
    public void pauseForReview(UUID id, JobStatus s, String step) {
        retryOnConflict(id, j -> { j.setStatus(s); j.setStep(step); });
        log.info("Job {} paused at {}", id, s);
    }
    public void saveBackgroundMusicPath(UUID id, String path) {
        retryOnConflict(id, j -> j.setBackgroundMusicPath(path));
    }

    public void savePlannedPublishAt(UUID id, java.time.OffsetDateTime at) {
        retryOnConflict(id, j -> j.setPlannedPublishAt(at));
    }

    public void saveStoryArc(UUID id, String arc) {
        if (arc == null || arc.isBlank()) return;
        retryOnConflict(id, j -> j.setStoryArc(arc));
    }

    /**
     * Regenerates the thumbnail (all 3 variants) for a job that already has
     * assembled assets, steering generation with a free-text reviewer direction
     * (e.g. "exactly three chicks, no extra chickens"). Synchronous — takes
     * minutes (3 × image generation); 1 thumbnail-generation cost, nothing else
     * is touched. Works on any job past assembly (THUMBNAIL_REVIEW_PENDING,
     * UPLOAD_REVIEW_PENDING, COMPLETED, ...).
     */
    public Map<String, Object> regenerateThumbnail(UUID jobId, String hint) {
        VideoJob job = load(jobId);
        List<SceneDto> assembly = loadAssemblyScenes(job);
        if (assembly.isEmpty()) {
            throw new IllegalStateException(
                    "Job " + jobId + " has no assembled scenes yet — nothing to base a thumbnail on.");
        }
        String hook = "";
        try {
            if (job.getScriptJobId() != null) {
                hook = scriptClient.get(job.getScriptJobId()).path("script").path("hook").asText("");
            }
        } catch (Exception e) {
            log.warn("Job {} thumbnail regen: script hook unavailable ({}) — continuing without",
                    jobId, e.getMessage());
        }
        String title = job.getMetadataTitle();
        if (title == null || title.isBlank()) title = job.getTopic();

        JsonNode thumb = thumbnailClient.generate(
                jobId, job.getTopic(), title, hook,
                pickCastStills(assembly),
                performanceLoop.bestThumbnailLayout(),
                hint, presentCast(assembly));
        String thumbPath = thumb.path("thumbnailPath").asText();
        saveThumbnailLayout(jobId, thumb.path("layout").asText(null));
        saveThumbnailPath(jobId, thumbPath);
        log.info("Job {} thumbnail regenerated with reviewer hint '{}' -> {}", jobId, hint, thumbPath);
        return Map.of("id", jobId.toString(),
                "thumbnailPath", thumbPath == null ? "" : thumbPath,
                "result", "THUMBNAIL_REGENERATED");
    }

    public void saveThumbnailPath(UUID id, String path) {
        if (path == null || path.isBlank()) return;
        retryOnConflict(id, j -> j.setThumbnailPath(path));
    }

    public void saveThumbnailLayout(UUID id, String layout) {
        if (layout == null || layout.isBlank()) return;
        retryOnConflict(id, j -> j.setThumbnailLayout(layout));
    }

    public void saveScriptJobId(UUID id, UUID sjId) {
        retryOnConflict(id, j -> j.setScriptJobId(sjId));
    }
    public void saveScriptIdAndScenes(UUID id, UUID sId, List<SceneDto> scenes,
                                      Integer structureScore, Integer criticScore) {
        retryOnConflict(id, j -> {
            j.setScriptId(sId);
            j.setAssemblyScenesJson(serialise(scenes));
            j.setStructureScore(structureScore);
            j.setCriticScore(criticScore);
        });
    }
    public void saveAssemblyScenes(UUID id, List<SceneDto> scenes) {
        retryOnConflict(id, j -> j.setAssemblyScenesJson(serialise(scenes)));
    }
    public void saveAssemblyResults(UUID id, String videoPath, String thumbPath,
                                    MetadataGenerator.Metadata meta, String captionsPath,
                                    String shortPath) {
        retryOnConflict(id, j -> {
            j.setVideoPath(videoPath);
            j.setThumbnailPath(thumbPath);
            j.setMetadataTitle(meta.title());
            j.setMetadataDescription(meta.description());
            j.setMetadataTags(String.join(",", meta.tags()));
            j.setCaptionsPath(captionsPath);
            if (shortPath != null && !shortPath.isBlank()) j.setShortPath(shortPath);
        });
    }

    /** Squint-test preselect: vision-rank the thumbnail variants and copy the
     *  winner onto thumbnail.png (the default the gate shows). Scores land in
     *  the job metrics so the dashboard can show WHY this one won. */
    private void preselectBestThumbnail(UUID jobId, String title) {
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get("/workdir", jobId.toString(), "thumbnail");
            Map<Integer, java.nio.file.Path> variants = new java.util.LinkedHashMap<>();
            for (int v = 0; v < 8; v++) {
                java.nio.file.Path p = dir.resolve("thumbnail-" + v + ".png");
                if (java.nio.file.Files.exists(p)) variants.put(v, p);
            }
            if (variants.size() < 2) return;
            ThumbnailQc.Result tq = thumbnailQc.rank(variants, title);
            if (tq == null) return;
            java.nio.file.Files.copy(variants.get(tq.bestVariant()),
                    dir.resolve("thumbnail.png"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            log.info("Job {} thumbnail squint-test: variant {} preselected (scores {})",
                    jobId, tq.bestVariant(), tq.scores());
            try {
                VideoJob fresh = load(jobId);
                com.fasterxml.jackson.databind.node.ObjectNode m = mapper.createObjectNode();
                if (fresh.getMetricsJson() != null && !fresh.getMetricsJson().isBlank()) {
                    try { m = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fresh.getMetricsJson()); }
                    catch (Exception ignore) { /* start clean */ }
                }
                m.put("thumbnailBestVariant", tq.bestVariant());
                m.put("thumbnailScores", tq.scores().toString());
                fresh.setMetricsJson(mapper.writeValueAsString(m));
                repo.save(fresh);
            } catch (Exception ignore) { /* metrics best-effort */ }
        } catch (Exception e) {
            log.warn("Job {} thumbnail preselect failed (default stays): {}", jobId, e.getMessage());
        }
    }

    /** Copies the current bible to workdir/<jobId>/bible_snapshot.yml and stores
     *  its SHA-256 in the job metrics, so every episode is forensically tied to
     *  the exact config it rendered with. Best-effort. */
    private void snapshotBible(UUID jobId) {
        try {
            java.nio.file.Path bible = java.nio.file.Paths.get(props.bible().path());
            if (!java.nio.file.Files.exists(bible)) return;
            java.nio.file.Path dir = java.nio.file.Paths.get("/workdir", jobId.toString());
            java.nio.file.Files.createDirectories(dir);
            java.nio.file.Files.copy(bible, dir.resolve("bible_snapshot.yml"),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            byte[] hash = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(java.nio.file.Files.readAllBytes(bible));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) hex.append(String.format("%02x", b));
            VideoJob fresh = load(jobId);
            com.fasterxml.jackson.databind.node.ObjectNode m = mapper.createObjectNode();
            if (fresh.getMetricsJson() != null && !fresh.getMetricsJson().isBlank()) {
                try { m = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fresh.getMetricsJson()); }
                catch (Exception ignore) { /* start clean */ }
            }
            m.put("bibleSha256", hex.substring(0, 16));   // short id is plenty
            fresh.setMetricsJson(mapper.writeValueAsString(m));
            repo.save(fresh);
        } catch (Exception e) {
            log.warn("Job {} bible snapshot failed (non-fatal): {}", jobId, e.getMessage());
        }
    }

    /** Threshold for the duration-discipline warning: master may run at most
     *  this factor over the SCRIPTED scene total (intro/outro excluded from the
     *  scripted side, so ~1.25 leaves room for bumpers + voice stretch). */
    @org.springframework.beans.factory.annotation.Value("${pipeline.duration.stretch-max:1.30}")
    private double stretchMax;

    /** Records scripted vs rendered duration + Veo metrics on the job and warns
     *  loudly when the render overshoots — the ep-3 audit found +28% growth that
     *  nobody saw because the two numbers never sat side by side. Best-effort. */
    private void recordDurationMetrics(UUID jobId, double masterSeconds,
                                       List<SceneDto> assemblyScenes) {
        try {
            int scripted = 0;
            for (SceneDto s : assemblyScenes) {
                Number d = s.getDurationSeconds();
                if (d != null) scripted += d.intValue();
            }
            if (scripted <= 0 || masterSeconds <= 0) return;
            double stretch = masterSeconds / scripted;
            com.fasterxml.jackson.databind.node.ObjectNode m = mapper.createObjectNode();
            VideoJob fresh = load(jobId);
            if (fresh.getMetricsJson() != null && !fresh.getMetricsJson().isBlank()) {
                try { m = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(fresh.getMetricsJson()); }
                catch (Exception ignore) { /* start clean */ }
            }
            m.put("scriptedSeconds", scripted);
            m.put("masterSeconds", Math.round(masterSeconds * 10.0) / 10.0);
            m.put("stretchFactor", Math.round(stretch * 100.0) / 100.0);
            // Render-duur-gate (board): compare the RENDERED master against the
            // REQUESTED target plus the intro/outro bumpers assembly adds around
            // the scenes. Off by more than 10% of the target → loud warning +
            // QC-insight + a "withinTarget" flag in metricsJson that the
            // QualityScorer surfaces as a soft polish check. Deliberately a
            // SOFT signal: nothing fails or blocks here — the human review
            // gate stays the backstop.
            int target = fresh.getTargetSeconds();
            if (target > 0) {
                double bumpers = introOutroMarginSeconds();
                double expected = target + bumpers;
                double offBy = Math.abs(masterSeconds - expected);
                double tolerance = 0.10 * target;
                m.put("targetSeconds", target);
                m.put("introOutroMarginSeconds", Math.round(bumpers * 10.0) / 10.0);
                m.put("targetDeltaSeconds", Math.round((masterSeconds - expected) * 10.0) / 10.0);
                m.put("withinTarget", offBy <= tolerance);
                if (offBy > tolerance) {
                    log.warn("Job {} DURATION OFF TARGET: master {}s vs target {}s "
                            + "(+{}s intro/outro = {}s expected) — off by {}s, more than "
                            + "10% of target ({}s). Soft QA signal only; the human gate decides.",
                            jobId,
                            String.format(java.util.Locale.ROOT, "%.1f", masterSeconds),
                            target,
                            String.format(java.util.Locale.ROOT, "%.1f", bumpers),
                            String.format(java.util.Locale.ROOT, "%.1f", expected),
                            String.format(java.util.Locale.ROOT, "%.1f", offBy),
                            String.format(java.util.Locale.ROOT, "%.1f", tolerance));
                    try {
                        qcInsights.record(jobId, null, List.of(
                                "Master duration off target: rendered "
                                + String.format(java.util.Locale.ROOT, "%.1f", masterSeconds)
                                + "s vs expected " + String.format(java.util.Locale.ROOT, "%.1f", expected)
                                + "s (target " + target + "s + "
                                + String.format(java.util.Locale.ROOT, "%.1f", bumpers)
                                + "s intro/outro), off by "
                                + String.format(java.util.Locale.ROOT, "%.1f", offBy)
                                + "s (> 10% of target)"),
                                "duration-gate");
                    } catch (Exception ignore) { /* insights best-effort */ }
                }
            }
            fresh.setMetricsJson(mapper.writeValueAsString(m));
            repo.save(fresh);
            if (stretch > stretchMax) {
                log.warn("Job {} DURATION DRIFT: scripted {}s rendered {}s (×{} > ×{} cap) — "
                        + "tighten scene durations or the voice-stretch settings",
                        jobId, scripted, Math.round(masterSeconds),
                        String.format(java.util.Locale.ROOT, "%.2f", stretch),
                        stretchMax);
                try {
                    qcInsights.record(jobId, null, List.of(
                            "Duration drift: scripted " + scripted + "s → rendered "
                            + Math.round(masterSeconds) + "s (×"
                            + String.format(java.util.Locale.ROOT, "%.2f", stretch) + ")"),
                            "duration-gate");
                } catch (Exception ignore) { /* insights best-effort */ }
            }
        } catch (Exception e) {
            log.warn("Job {} duration metrics failed (non-fatal): {}", jobId, e.getMessage());
        }
    }

    /** Intro+outro bumper seconds the assembly wraps around the scenes — the
     *  margin the render-duur-gate allows on top of the requested target.
     *  Read from the bible (brand.intro/outro.durationSeconds), falling back
     *  to 3s+3s when the bible or those fields are missing. Never throws. */
    private double introOutroMarginSeconds() {
        double intro = 3.0, outro = 3.0;
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(props.bible().path());
            if (java.nio.file.Files.exists(p)) {
                com.fasterxml.jackson.databind.JsonNode brand =
                        new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                                .readTree(p.toFile()).path("brand");
                intro = brand.path("intro").path("durationSeconds").asDouble(intro);
                outro = brand.path("outro").path("durationSeconds").asDouble(outro);
            }
        } catch (Exception e) {
            log.debug("introOutroMarginSeconds: bible read failed ({}) — using defaults", e.getMessage());
        }
        return intro + outro;
    }

    public void saveQaBoard(UUID id, int score, String json) {
        retryOnConflict(id, j -> {
            j.setQaBoardScore(score);
            j.setQaBoardJson(json);
        });
    }
    public void complete(UUID id, String videoId, String url) {
        retryOnConflict(id, j -> {
            j.setStatus(JobStatus.COMPLETED);
            j.setStep("done");
            j.setYoutubeVideoId(videoId);
            j.setYoutubeUrl(url);
        });
    }
    /** Records the YouTube result and parks the job at the distribution gate. */
    public void enterDistribution(UUID id, String videoId, String url) {
        retryOnConflict(id, j -> {
            j.setStatus(JobStatus.DISTRIBUTION_PENDING);
            j.setStep("uploaded — awaiting distribution");
            j.setYoutubeVideoId(videoId);
            j.setYoutubeUrl(url);
        });
    }
    public void fail(UUID id, String err) {
        retryOnConflict(id, j -> { j.setStatus(JobStatus.FAILED); j.setError(err); });
    }

    @Transactional(readOnly = true)
    public VideoJobResponse get(UUID id) {
        return repo.findById(id).map(this::toResponse)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job " + id));
    }

    /** Clone a job's creative settings into a fresh PENDING job (a new variant).
     *  Does NOT copy schedule, series episode number or any output/YouTube state. */
    public VideoJobResponse cloneJob(UUID id) {
        VideoJob j = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Unknown job " + id));
        var req = new com.youtubeauto.orchestrator.api.dto.CreateVideoRequest(
                (j.getTopic() == null ? "Untitled" : j.getTopic()) + " (copy)",
                j.getBrief(), j.getLesson(), j.getMood(), j.getAngle(), j.getHook(),
                null,                                   // reuseImagesFromJob
                j.getAudience(), j.getTargetSeconds(), j.getBurnSubtitles(),
                j.getBackgroundMusicPath(), j.getPrivacyStatus(), j.getFormat(), j.getMotionMode(),
                j.getVeoModel(),                        // keep the Veo model choice
                null,                                   // plannedPublishAt
                j.getSeriesId(), null,                  // episodeNumber (don't duplicate)
                j.getRecurringMotif());
        return submit(req);
    }

    private VideoJobResponse toResponse(VideoJob j) {
        return new VideoJobResponse(j.getId(), j.getTopic(), j.getStatus(), j.getStep(),
                j.getError(), j.getVideoPath(), j.getYoutubeVideoId(), j.getYoutubeUrl(),
                j.getFacebookVideoId(), j.getFacebookUrl(),
                j.getTiktokPublishId(), j.getInstagramMediaId(), j.getInstagramUrl());
    }
}
