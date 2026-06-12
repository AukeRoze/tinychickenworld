package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.orchestrator.api.dto.CreateVideoRequest;
import com.youtubeauto.orchestrator.api.dto.VideoJobResponse;
import com.youtubeauto.orchestrator.client.AssemblyServiceClient;
import com.youtubeauto.orchestrator.client.ImageServiceClient;
import com.youtubeauto.orchestrator.client.ScriptServiceClient;
import com.youtubeauto.orchestrator.client.ThumbnailServiceClient;
import com.youtubeauto.orchestrator.client.UploadServiceClient;
import com.youtubeauto.orchestrator.client.VideoGenerationServiceClient;
import com.youtubeauto.orchestrator.client.VoiceServiceClient;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.domain.JobStatus;
import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoAuditRepository;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import com.youtubeauto.orchestrator.review.QaBoard;
import com.youtubeauto.orchestrator.review.ReviewConfigLoader;
import com.youtubeauto.orchestrator.review.ReviewProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyBoolean;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyList;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * State-machine integration test for {@link PipelineOrchestrator} with mocked
 * service clients and a Map-backed mock of {@link VideoJobRepository}.
 *
 * This is the safety net for putting {@code @Version} (optimistic locking) on
 * {@link VideoJob} later: it pins the exact status transitions, the review-gate
 * pauses (V2__review_gates_and_resumable_state.sql) and the resumable-state
 * behaviour (retry / resumeAfterRestart reuse persisted assemblyScenesJson)
 * before any concurrency changes are made.
 *
 * How async is handled: every stage method ({@code runScriptStage},
 * {@code runAssetsStage}, {@code runAssemblyStage}, {@code runUploadGate},
 * {@code runUploadStage}, {@code finalizeDistribution}) is {@code @Async} in
 * production but is dispatched through the {@code self} field (the Spring
 * proxy). Here {@code self} is set to the orchestrator instance itself via
 * ReflectionTestUtils, so the whole pipeline runs synchronously on the test
 * thread. The only real concurrency left is runAssetsStage's own 2-thread pool
 * for the parallel voice+image calls, which it joins before returning.
 *
 * ReflectionTestUtils is also used for the {@code @Value} gate booleans
 * (qaGateEnabled, thumbnailGateEnabled, distributionGateEnabled, qcEnabled,
 * stretchMax) because without a Spring context those private fields keep their
 * Java defaults (false/0) instead of their production defaults (mostly true).
 */
class PipelineOrchestratorStateMachineTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** bible/channel.yml deliberately does not exist: snapshotBible,
     *  autoPickMusic and dnaAccessoryLines all degrade gracefully then. */
    private static final String NO_BIBLE = "no-such-bible-for-this-test.yml";

    /** All bible review gates off — the job flows start to finish. */
    private static final ReviewProperties GATES_OFF = new ReviewProperties(
            false, false, false, false, false,
            new ReviewProperties.Mail("", "noreply@test", "http://localhost:8080"));

    // ── mocked collaborators (constructor order of PipelineOrchestrator) ──
    private VideoJobRepository repo;
    private ScriptServiceClient scriptClient;
    private VoiceServiceClient voiceClient;
    private ImageServiceClient imageClient;
    private VideoGenerationServiceClient videoGenClient;
    private AssemblyServiceClient assemblyClient;
    private UploadServiceClient uploadClient;
    private ThumbnailServiceClient thumbnailClient;
    private PropAnchorService propAnchorService;
    private MetadataGenerator metadataGenerator;
    private LyricsGenerator lyricsGenerator;
    private QualityReviewer qualityReviewer;
    private QaBoard qaBoard;
    private SceneImageQc sceneImageQc;
    private ClipQc clipQc;
    private ThumbnailQc thumbnailQc;
    private QcInsights qcInsights;
    private VideoAuditRepository auditRepo;
    private SeriesContinuity seriesContinuity;
    private InsightsAggregator insights;
    private PerformanceLoop performanceLoop;
    private VeoPromptCompiler veoPromptCompiler;
    private ReviewConfigLoader reviewConfig;
    private OrchestratorProperties props;   // real record, no HTTP behind it

    /** In-memory "database": save/findById stay consistent across stages. */
    private Map<UUID, VideoJob> store;
    /** Status at the moment of every repo.save(), in order. */
    private List<JobStatus> statusHistory;

    private UUID scriptJobId;
    private UUID scriptId;

    @BeforeEach
    void setUp() throws Exception {
        store = new ConcurrentHashMap<>();
        statusHistory = Collections.synchronizedList(new ArrayList<>());

        repo = mock(VideoJobRepository.class);
        scriptClient = mock(ScriptServiceClient.class);
        voiceClient = mock(VoiceServiceClient.class);
        imageClient = mock(ImageServiceClient.class);
        videoGenClient = mock(VideoGenerationServiceClient.class);
        assemblyClient = mock(AssemblyServiceClient.class);
        uploadClient = mock(UploadServiceClient.class);
        thumbnailClient = mock(ThumbnailServiceClient.class);
        propAnchorService = mock(PropAnchorService.class);
        metadataGenerator = mock(MetadataGenerator.class);
        lyricsGenerator = mock(LyricsGenerator.class);
        qualityReviewer = mock(QualityReviewer.class);
        qaBoard = mock(QaBoard.class);
        sceneImageQc = mock(SceneImageQc.class);
        clipQc = mock(ClipQc.class);
        thumbnailQc = mock(ThumbnailQc.class);
        qcInsights = mock(QcInsights.class);
        auditRepo = mock(VideoAuditRepository.class);
        seriesContinuity = mock(SeriesContinuity.class);
        insights = mock(InsightsAggregator.class);
        performanceLoop = mock(PerformanceLoop.class);
        veoPromptCompiler = mock(VeoPromptCompiler.class);
        reviewConfig = mock(ReviewConfigLoader.class);

        props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(NO_BIBLE));

        // Map-backed repository: what one stage saves, the next stage finds.
        when(repo.save(any(VideoJob.class))).thenAnswer(inv -> {
            VideoJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(UUID.randomUUID()); // @PrePersist stand-in
            store.put(j.getId(), j);
            statusHistory.add(j.getStatus());
            return j;
        });
        when(repo.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));

        // Collaborators that runScriptStage/runAssetsStage dereference directly
        // (a null default would NPE inside the stage, not exercise the flow).
        when(seriesContinuity.channelMemory(any())).thenReturn(Optional.empty());
        when(propAnchorService.buildAnchors(any(), anyList(), any())).thenReturn(List.of());
        when(sceneImageQc.check(any(), anyList(), anyList())).thenReturn(SceneImageQc.Result.pass());

        scriptJobId = UUID.randomUUID();
        scriptId = UUID.randomUUID();

        // script-service: submit returns the script job id; get() is COMPLETED
        // on the first poll (pollScript) and re-read by the assets + assembly
        // stages for the per-scene payloads.
        when(scriptClient.submit(any(), any(), anyInt(), any(), any(), any(),
                any(), any(), any(), any())).thenReturn(scriptJobId);
        when(scriptClient.get(any(UUID.class))).thenReturn(json(scriptResponse()));

        when(voiceClient.synthesize(any(), anyList())).thenReturn(json("""
                {"scenes":[
                  {"seq":1,"audioPath":"/workdir/test/audio/scene_01.mp3"},
                  {"seq":2,"audioPath":"/workdir/test/audio/scene_02.mp3"}]}
                """));
        when(imageClient.generate(any(), anyList(), any())).thenReturn(json("""
                {"scenes":[
                  {"seq":1,"imagePath":"/workdir/test/images/scene_01.png"},
                  {"seq":2,"imagePath":"/workdir/test/images/scene_02.png"}]}
                """));
        when(assemblyClient.assembleAsync(any(), any(), anyList(), any(), any(), any(),
                anyInt(), anyInt(), anyBoolean(), any())).thenReturn(json("""
                {"outputPath":"/workdir/test/out/master.mp4","durationSeconds":9.0}
                """));
        // 8-args full form: ... customHint, castPresent (ground-truth cast voor
        // de groeps-thumbnail-keuze). De orchestrator roept deze overload aan.
        when(thumbnailClient.generate(any(), any(), any(), any(), anyList(), any(), any(), any()))
                .thenReturn(json("""
                {"thumbnailPath":"/workdir/test/thumbnail/thumbnail.png","layout":"NO_TEXT"}
                """));
        when(uploadClient.upload(any(), any(), any(), any(), any(), anyList(),
                any(), any(), any())).thenReturn(json("""
                {"youtubeVideoId":"yt-123","youtubeUrl":"https://youtu.be/yt-123","captionsUploaded":true}
                """));

        when(metadataGenerator.generate(any(), any(), any(), anyBoolean()))
                .thenReturn(new MetadataGenerator.Metadata(
                        "Pip Finds a Rainbow!", "A warm little adventure.",
                        List.of("kids cartoon")));
        when(metadataGenerator.chapterTitles(any(), any(), any())).thenReturn(Map.of());
        when(qaBoard.evaluate(any(), any(), anyList(), any(), any()))
                .thenReturn(new QaBoard.Result(92, "publish", true, true,
                        List.of(), "{\"publishable\":true}"));
        // qualityReviewer.auditJob is left unstubbed (null audit) — the
        // assembly stage treats a failed/absent audit as non-fatal.
    }

    /** Script-service response used by pollScript + the assets/assembly stages. */
    private String scriptResponse() {
        return """
                {
                  "status": "COMPLETED",
                  "script": {
                    "id": "%s",
                    "title": "Pip Finds a Rainbow!",
                    "hook": "What is that glow?",
                    "storyArc": "discovery",
                    "structureScore": 85,
                    "criticScore": 88,
                    "scenes": [
                      { "seq": 1, "durationSeconds": 4, "phase": "hook",
                        "narration": "Pip sees a glow in the sky.",
                        "visualDesc": "Pip looks up at the sky",
                        "characters": ["pip"], "locationId": "meadow",
                        "timeOfDay": "day", "weather": "sunny",
                        "emotion": "excited surprise (4/5)",
                        "lines": [ { "speaker": "pip", "text": "Look up there!" } ] },
                      { "seq": 2, "durationSeconds": 5, "phase": "resolution",
                        "narration": "It was a rainbow all along.",
                        "visualDesc": "A rainbow over the meadow",
                        "characters": ["pip", "mo"], "locationId": "meadow",
                        "timeOfDay": "day", "weather": "sunny",
                        "emotion": "warm content (3/5)",
                        "lines": [ { "speaker": "mo", "text": "A rainbow!" } ] }
                    ]
                  }
                }
                """.formatted(scriptId);
    }

    private static JsonNode json(String s) {
        try {
            return MAPPER.readTree(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Builds the orchestrator through the Lombok @RequiredArgsConstructor
     * (25 final fields, declaration order) with a REAL MetadataPolicy (it has
     * no dependencies), then wires the private fields Spring would inject.
     */
    private PipelineOrchestrator newOrchestrator(ReviewProperties gates,
                                                 boolean qaGate,
                                                 boolean thumbnailGate,
                                                 boolean distributionGate) {
        when(reviewConfig.getReview()).thenReturn(gates);
        PipelineOrchestrator orch = new PipelineOrchestrator(
                repo, scriptClient, voiceClient, imageClient, videoGenClient,
                assemblyClient, uploadClient, thumbnailClient, propAnchorService,
                metadataGenerator, new MetadataPolicy(), lyricsGenerator,
                qualityReviewer, qaBoard, sceneImageQc, clipQc, thumbnailQc,
                qcInsights, auditRepo, seriesContinuity, insights, performanceLoop,
                props, veoPromptCompiler, reviewConfig);
        // self-reference: production routes stage→stage calls through the
        // @Async proxy; here it makes the pipeline run synchronously.
        ReflectionTestUtils.setField(orch, "self", orch);
        // @Value fields — set to the requested values (production defaults are
        // qa/thumbnail/distribution gates ON, qc ON, stretch-max 1.30).
        ReflectionTestUtils.setField(orch, "qaGateEnabled", qaGate);
        ReflectionTestUtils.setField(orch, "thumbnailGateEnabled", thumbnailGate);
        ReflectionTestUtils.setField(orch, "distributionGateEnabled", distributionGate);
        ReflectionTestUtils.setField(orch, "qcEnabled", true);
        ReflectionTestUtils.setField(orch, "qcMaxRerollsPerScene", 1);
        ReflectionTestUtils.setField(orch, "qcMaxTotalRerolls", 4);
        ReflectionTestUtils.setField(orch, "stretchMax", 1.30d);
        return orch;
    }

    /** Minimal landscape ken_burns request (no Veo, no song mode, no series). */
    private static CreateVideoRequest request() {
        return new CreateVideoRequest(
                "Pip finds a rainbow",      // topic
                "a short creative brief",   // brief
                "rainbows come after rain", // lesson
                "calm wonder",              // mood
                null,                       // angle
                null,                       // hook
                null,                       // reuseImagesFromJob
                "preschoolers",             // audience
                45,                         // targetSeconds
                false,                      // burnSubtitles
                null,                       // backgroundMusicPath
                "private",                  // privacyStatus
                "landscape",                // format
                "ken_burns",                // motionMode
                null,                       // veoModel
                null,                       // plannedPublishAt
                null,                       // seriesId
                null,                       // episodeNumber
                null);                      // recurringMotif
    }

    /** Seeds a job row directly into the map-backed store (no save() call). */
    private VideoJob seedJob(JobStatus status) {
        VideoJob job = VideoJob.builder()
                .id(UUID.randomUUID())
                .topic("Seeded episode")
                .audience("preschoolers")
                .targetSeconds(45)
                .format("landscape")
                .motionMode("ken_burns")
                .privacyStatus("private")
                .status(status)
                .build();
        store.put(job.getId(), job);
        return job;
    }

    /** Seeds a FAILED-after-assets job whose scene image+audio files really
     *  exist on disk, so resumePoint()/assetsComplete() see finished assets. */
    private VideoJob seedJobWithCompleteAssets(Path tmp, JobStatus status) throws Exception {
        Path img1 = Files.createFile(tmp.resolve("scene_01.png"));
        Path aud1 = Files.createFile(tmp.resolve("scene_01.mp3"));
        Path img2 = Files.createFile(tmp.resolve("scene_02.png"));
        Path aud2 = Files.createFile(tmp.resolve("scene_02.mp3"));
        List<Map<String, Object>> scenes = new ArrayList<>();
        scenes.add(scene(1, 4, "hook", img1, aud1, List.of("pip")));
        scenes.add(scene(2, 5, "resolution", img2, aud2, List.of("pip", "mo")));

        VideoJob job = seedJob(status);
        job.setScriptJobId(scriptJobId);
        job.setScriptId(scriptId);
        job.setAssemblyScenesJson(MAPPER.writeValueAsString(scenes));
        return job;
    }

    private static Map<String, Object> scene(int seq, int dur, String phase,
                                             Path img, Path aud, List<String> chars) {
        Map<String, Object> m = new HashMap<>();
        m.put("seq", seq);
        m.put("durationSeconds", dur);
        m.put("phase", phase);
        m.put("narration", "narration " + seq);
        m.put("visualDesc", "visual " + seq);
        m.put("imagePath", img.toString());
        m.put("audioPath", aud.toString());
        m.put("characters", chars);
        return m;
    }

    private List<JobStatus> distinctStatusSequence() {
        List<JobStatus> out = new ArrayList<>();
        synchronized (statusHistory) {
            for (JobStatus s : statusHistory) {
                if (out.isEmpty() || out.get(out.size() - 1) != s) out.add(s);
            }
        }
        return out;
    }

    private void verifyNeverAssembled() {
        verify(assemblyClient, never()).assembleAsync(any(), any(), anyList(), any(), any(),
                any(), anyInt(), anyInt(), anyBoolean(), any());
    }

    private void verifyNeverUploaded() {
        verify(uploadClient, never()).upload(any(), any(), any(), any(), any(),
                anyList(), any(), any(), any());
    }

    // ───────────────────────────── scenarios ─────────────────────────────

    /**
     * (a) Happy path with every gate off: submit() walks
     * runScriptStage → runAssetsStage → (auto image-QC) → runAssemblyStage →
     * runUploadGate → runUploadStage → complete(), and the repository sees the
     * status sequence PENDING → SCRIPT_GENERATING → ASSETS_GENERATING →
     * ASSEMBLING → UPLOADING → COMPLETED. Client call order is pinned
     * (voice+image run in parallel, so each is ordered separately against the
     * surrounding stages). videoGenClient stays untouched for ken_burns.
     */
    @Test
    void happyPath_gatesOff_runsStagesInOrderAndCompletes() {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, false, false, false);

        VideoJobResponse resp = orch.submit(request());

        VideoJob job = store.get(resp.id());
        assertNotNull(job, "job must be persisted");
        assertEquals(JobStatus.COMPLETED, job.getStatus());
        assertEquals(List.of(JobStatus.PENDING, JobStatus.SCRIPT_GENERATING,
                        JobStatus.ASSETS_GENERATING, JobStatus.ASSEMBLING,
                        JobStatus.UPLOADING, JobStatus.COMPLETED),
                distinctStatusSequence(),
                "persisted status transitions must match the state machine");

        // Persisted outputs of each stage (V2 resumable state columns).
        assertEquals(scriptId, job.getScriptId());
        assertEquals(scriptJobId, job.getScriptJobId());
        assertNotNull(job.getAssemblyScenesJson(), "assembly scenes must be persisted");
        assertEquals("/workdir/test/out/master.mp4", job.getVideoPath());
        assertEquals("/workdir/test/thumbnail/thumbnail.png", job.getThumbnailPath());
        assertEquals("Pip Finds a Rainbow!", job.getMetadataTitle());
        assertEquals("yt-123", job.getYoutubeVideoId());
        assertEquals("https://youtu.be/yt-123", job.getYoutubeUrl());
        assertEquals("discovery", job.getStoryArc());
        assertEquals(92, job.getQaBoardScore());

        // Stage order, voice branch: script → voice → assembly → upload.
        InOrder voiceOrder = inOrder(scriptClient, voiceClient, assemblyClient, uploadClient);
        voiceOrder.verify(scriptClient).submit(any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any());
        voiceOrder.verify(voiceClient).synthesize(any(), anyList());
        voiceOrder.verify(assemblyClient).assembleAsync(any(), any(), anyList(), any(), any(),
                any(), anyInt(), anyInt(), anyBoolean(), any());
        voiceOrder.verify(uploadClient).upload(any(), any(), any(), any(), any(),
                anyList(), any(), any(), any());

        // Stage order, image branch: image → assembly → thumbnail → upload.
        InOrder imageOrder = inOrder(imageClient, assemblyClient, thumbnailClient, uploadClient);
        imageOrder.verify(imageClient).generate(any(), anyList(), any());
        imageOrder.verify(assemblyClient).assembleAsync(any(), any(), anyList(), any(), any(),
                any(), anyInt(), anyInt(), anyBoolean(), any());
        imageOrder.verify(thumbnailClient).generate(any(), any(), any(), any(), anyList(), any(), any(), any());
        imageOrder.verify(uploadClient).upload(any(), any(), any(), any(), any(),
                anyList(), any(), any(), any());

        // The automated vision-QC (autoQcImages) ran on the generated stills.
        verify(sceneImageQc, atLeastOnce()).check(any(), anyList(), anyList());
        // ken_burns motion mode: the Veo stage is never entered.
        verifyNoInteractions(videoGenClient);
    }

    /**
     * (b) Production-default gates (ReviewProperties.defaults(): afterScript +
     * reviewImages + beforeUpload on, plus the @Value-default qa/thumbnail/
     * distribution gates on): the job pauses at EVERY gate, the next stage is
     * NOT called while paused, and each approve() advances exactly one hop —
     * the dispatch table in PipelineOrchestrator.approve().
     */
    @Test
    void defaultGates_jobPausesAtEveryGate_untilApproved() {
        PipelineOrchestrator orch = newOrchestrator(ReviewProperties.defaults(), true, true, true);

        // submit → runScriptStage pauses at the afterScript gate.
        UUID jobId = orch.submit(request()).id();
        assertEquals(JobStatus.SCRIPT_REVIEW_PENDING, store.get(jobId).getStatus());
        verify(voiceClient, never()).synthesize(any(), anyList());
        verify(imageClient, never()).generate(any(), anyList(), any());

        // approve #1 → runAssetsStage pauses at the per-scene image gate.
        orch.approve(jobId);
        assertEquals(JobStatus.IMAGES_REVIEW_PENDING, store.get(jobId).getStatus());
        verify(voiceClient).synthesize(any(), anyList());
        verify(imageClient).generate(any(), anyList(), any());
        verifyNeverAssembled();

        // approve #2 (ken_burns ⇒ no Veo) → runAssemblyStage pauses at the
        // dedicated thumbnail gate (thumbnailGateEnabled).
        orch.approve(jobId);
        assertEquals(JobStatus.THUMBNAIL_REVIEW_PENDING, store.get(jobId).getStatus());
        verify(assemblyClient).assembleAsync(any(), any(), anyList(), any(), any(),
                any(), anyInt(), anyInt(), anyBoolean(), any());
        verifyNeverUploaded();

        // approve #3 → runUploadGate: QA Board says publishable, but the
        // beforeUpload review gate still holds the master.
        orch.approve(jobId);
        assertEquals(JobStatus.UPLOAD_REVIEW_PENDING, store.get(jobId).getStatus());
        verifyNeverUploaded();

        // approve #4 → runUploadStage uploads, then parks the job at the
        // manual distribution gate (distributionGateEnabled).
        orch.approve(jobId);
        assertEquals(JobStatus.DISTRIBUTION_PENDING, store.get(jobId).getStatus());
        verify(uploadClient).upload(any(), any(), any(), any(), any(),
                anyList(), any(), any(), any());
        assertEquals("yt-123", store.get(jobId).getYoutubeVideoId());

        // approve #5 → finalizeDistribution completes the job.
        orch.approve(jobId);
        assertEquals(JobStatus.COMPLETED, store.get(jobId).getStatus());
        assertEquals("https://youtu.be/yt-123", store.get(jobId).getYoutubeUrl());
    }

    /**
     * QA publish gate (runUploadGate + qaPublishable): a master whose persisted
     * QA Board verdict says publishable=false is held at UPLOAD_REVIEW_PENDING
     * even when the bible's beforeUpload gate is OFF; a human approve then
     * overrides and the upload proceeds.
     */
    @Test
    void qaGate_holdsUnpublishableMaster_untilHumanApproves() {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, true, false, false);

        VideoJob job = seedJob(JobStatus.THUMBNAIL_REVIEW_PENDING);
        job.setQaBoardScore(55);
        job.setQaBoardJson("{\"publishable\":false}");
        job.setVideoPath("/workdir/test/out/master.mp4");
        job.setMetadataTitle("Held episode");

        // approve at the thumbnail gate dispatches runUploadGate, which must
        // pause instead of uploading.
        orch.approve(job.getId());
        assertEquals(JobStatus.UPLOAD_REVIEW_PENDING, store.get(job.getId()).getStatus());
        verifyNeverUploaded();

        // Human override: approve from UPLOAD_REVIEW_PENDING runs the upload.
        orch.approve(job.getId());
        assertEquals(JobStatus.COMPLETED, store.get(job.getId()).getStatus());
        verify(uploadClient).upload(any(), any(), any(), any(), any(),
                anyList(), any(), any(), any());
    }

    /**
     * (c) Stage failure: when the script-service client throws, runScriptStage's
     * catch block calls fail(jobId, message) → status FAILED + error persisted,
     * and no downstream service is ever called.
     */
    @Test
    void scriptStageFailure_marksJobFailed_andCallsNothingDownstream() {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, false, false, false);
        when(scriptClient.submit(any(), any(), anyInt(), any(), any(), any(),
                any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("script-service down"));

        UUID jobId = orch.submit(request()).id();

        VideoJob job = store.get(jobId);
        assertEquals(JobStatus.FAILED, job.getStatus());
        assertEquals("script-service down", job.getError());
        verifyNoInteractions(voiceClient, imageClient, videoGenClient,
                assemblyClient, thumbnailClient, uploadClient);
    }

    /**
     * (d) Resume via retry(): a FAILED job whose persisted assemblyScenesJson
     * points at on-disk image+audio files for every scene resumes at
     * ASSEMBLING (resumePoint(): script done, assets complete, no master yet)
     * — script/voice/image generation is NOT re-run, only assembly onwards.
     * Note: the assembly stage still does a cheap scriptClient.get() for the
     * metadata/hook payload; that is read-only, not regeneration.
     */
    @Test
    void retry_ofFailedJob_resumesAtAssembly_withoutRegeneratingAssets(@TempDir Path tmp)
            throws Exception {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, false, false, false);
        VideoJob job = seedJobWithCompleteAssets(tmp, JobStatus.FAILED);
        job.setError("assembly exploded last time");

        orch.retry(job.getId());

        assertEquals(JobStatus.COMPLETED, store.get(job.getId()).getStatus());
        assertEquals(List.of(JobStatus.FAILED, JobStatus.ASSEMBLING,
                        JobStatus.UPLOADING, JobStatus.COMPLETED),
                distinctStatusSequence(),
                "retry must resume at ASSEMBLING, not restart the pipeline");

        verify(scriptClient, never()).submit(any(), any(), anyInt(), any(), any(),
                any(), any(), any(), any(), any());
        verifyNoInteractions(voiceClient, imageClient, videoGenClient);
        verify(assemblyClient).assembleAsync(any(), any(), anyList(), any(), any(),
                any(), anyInt(), anyInt(), anyBoolean(), any());
        verify(uploadClient).upload(any(), any(), any(), any(), any(),
                anyList(), any(), any(), any());
    }

    /**
     * (d2) Crash recovery via resumeAfterRestart(): a job left in
     * ASSETS_GENERATING re-enters runAssetsStage, which detects that every
     * scene's image+audio already exist on disk (the retry-friendly reuse in
     * runAssetsStage) and therefore calls NEITHER voice- nor image-service —
     * it flows straight on to assembly and completes.
     */
    @Test
    void resumeAfterRestart_reusesExistingAssets_withoutNewGeneration(@TempDir Path tmp)
            throws Exception {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, false, false, false);
        VideoJob job = seedJobWithCompleteAssets(tmp, JobStatus.ASSETS_GENERATING);

        orch.resumeAfterRestart(job.getId(), JobStatus.ASSETS_GENERATING);

        assertEquals(JobStatus.COMPLETED, store.get(job.getId()).getStatus());
        // All assets existed → the (paid) generation clients are never hit.
        verify(voiceClient, never()).synthesize(any(), anyList());
        verify(imageClient, never()).generate(any(), anyList(), any());
        verify(assemblyClient).assembleAsync(any(), any(), anyList(), any(), any(),
                any(), anyInt(), anyInt(), anyBoolean(), any());

        // The reused paths survived mergeAssets (non-destructive merge).
        List<Map<String, Object>> persisted = MAPPER.readValue(
                store.get(job.getId()).getAssemblyScenesJson(),
                MAPPER.getTypeFactory().constructCollectionType(List.class, Map.class));
        assertEquals(tmp.resolve("scene_01.png").toString(), persisted.get(0).get("imagePath"));
        assertEquals(tmp.resolve("scene_01.mp3").toString(), persisted.get(0).get("audioPath"));
    }

    /** approve() on a job that is not at a review gate must refuse loudly. */
    @Test
    void approve_throws_whenJobIsNotAwaitingReview() {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, false, false, false);
        VideoJob job = seedJob(JobStatus.COMPLETED);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orch.approve(job.getId()));
        assertTrue(ex.getMessage().contains("not awaiting review"));
    }

    /** retry() only resumes FAILED jobs — anything else is a usage error. */
    @Test
    void retry_throws_whenJobIsNotFailed() {
        PipelineOrchestrator orch = newOrchestrator(GATES_OFF, false, false, false);
        VideoJob job = seedJob(JobStatus.PENDING);
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> orch.retry(job.getId()));
        assertTrue(ex.getMessage().contains("not FAILED"));
    }
}
