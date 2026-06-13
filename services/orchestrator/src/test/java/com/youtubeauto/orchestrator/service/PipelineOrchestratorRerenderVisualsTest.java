package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for the ADDITIVE {@link PipelineOrchestrator#rerenderVisuals}
 * ("🔁 Re-render beelden (nieuwe cast)"). Plain Mockito, no Spring context —
 * the orchestrator is built through the same Lombok
 * {@code @RequiredArgsConstructor} (25 final fields, declaration order) the
 * state-machine test pins; that test is deliberately left untouched.
 *
 * The {@code self} field is set to a SEPARATE mock so the assets stage is not
 * actually executed — this test verifies exactly the new method's own
 * contract: the guard, the clear-semantics (imagePath/clipPath/endImagePath
 * gone, audioPath kept; episode anchors, thumbnail and locks wiped), the
 * status mark and the async dispatch.
 */
class PipelineOrchestratorRerenderVisualsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private VideoJobRepository repo;
    private PipelineOrchestrator orchestrator;
    private PipelineOrchestrator self;   // dispatch target — a mock, no real stage runs
    private Map<UUID, VideoJob> store;

    @BeforeEach
    void setUp() {
        store = new ConcurrentHashMap<>();
        repo = mock(VideoJobRepository.class);
        when(repo.save(any(VideoJob.class))).thenAnswer(inv -> {
            VideoJob j = inv.getArgument(0);
            if (j.getId() == null) j.setId(UUID.randomUUID());
            store.put(j.getId(), j);
            return j;
        });
        when(repo.findById(any(UUID.class)))
                .thenAnswer(inv -> Optional.ofNullable(store.get(inv.<UUID>getArgument(0))));

        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible("no-such-bible-for-this-test.yml"));

        orchestrator = new PipelineOrchestrator(
                repo,
                mock(ScriptServiceClient.class), mock(VoiceServiceClient.class),
                mock(ImageServiceClient.class), mock(VideoGenerationServiceClient.class),
                mock(AssemblyServiceClient.class), mock(UploadServiceClient.class),
                mock(ThumbnailServiceClient.class), mock(PropAnchorService.class),
                mock(MetadataGenerator.class), new MetadataPolicy(),
                mock(LyricsGenerator.class), mock(QualityReviewer.class),
                mock(QaBoard.class), mock(SceneImageQc.class), mock(ClipQc.class),
                mock(ThumbnailQc.class), mock(QcInsights.class),
                mock(VideoAuditRepository.class), mock(SeriesContinuity.class),
                mock(InsightsAggregator.class), mock(PerformanceLoop.class),
                props, mock(VeoPromptCompiler.class), mock(ReviewConfigLoader.class));
        self = mock(PipelineOrchestrator.class);
        ReflectionTestUtils.setField(orchestrator, "self", self);
    }

    private VideoJob seedJob(JobStatus status) {
        VideoJob job = VideoJob.builder()
                .id(UUID.randomUUID())
                .topic("Pip Finds a Rainbow!")
                .audience("preschoolers")
                .targetSeconds(60)
                .status(status)
                .scriptJobId(UUID.randomUUID())
                .assemblyScenesJson("""
                        [
                          {"seq":1,"durationSeconds":4,"narration":"Pip sees a glow.",
                           "imagePath":"/workdir/t/images/scene_01.png",
                           "audioPath":"/workdir/t/audio/scene_01.mp3",
                           "clipPath":"/workdir/t/clips/scene_01.mp4",
                           "endImagePath":"/workdir/t/images/scene_901.png"},
                          {"seq":2,"durationSeconds":5,"narration":"A rainbow!",
                           "imagePath":"/workdir/t/images/scene_02.png",
                           "audioPath":"/workdir/t/audio/scene_02.mp3"}
                        ]
                        """)
                .episodeAnchorsJson("{\"characters\":{\"pip\":\"/workdir/t/images/scene_01.png\"},\"props\":[]}")
                .thumbnailPath("/workdir/t/thumbnail/thumbnail.png")
                .lockedSceneSeqs("1,2")
                .reuseImagesFromJob(UUID.randomUUID())
                .build();
        store.put(job.getId(), job);
        return job;
    }

    @Test
    void rerenderVisualsClearsVisualsKeepsVoicesAndRestartsAssetsStage() throws Exception {
        VideoJob job = seedJob(JobStatus.IMAGES_REVIEW_PENDING);

        orchestrator.rerenderVisuals(job.getId());

        VideoJob saved = store.get(job.getId());
        JsonNode scenes = MAPPER.readTree(saved.getAssemblyScenesJson());
        assertEquals(2, scenes.size());
        for (JsonNode s : scenes) {
            // Visuals gone (NON_NULL serialisation: a cleared field loses its key)…
            assertFalse(s.has("imagePath"), "imagePath must be cleared");
            assertFalse(s.has("clipPath"), "clipPath must be cleared");
            assertFalse(s.has("endImagePath"), "endImagePath must be cleared");
            // …voices kept, so runAssetsStage's reuse block skips re-voicing.
            assertTrue(s.path("audioPath").asText("").endsWith(".mp3"), "audioPath must stay");
        }
        // Old-cast canon + old-stills thumbnail + reviewer locks wiped.
        assertNull(saved.getEpisodeAnchorsJson(), "old-cast episode anchors must be cleared");
        assertNull(saved.getThumbnailPath(), "thumbnail (old stills) must be cleared");
        assertNull(saved.getLockedSceneSeqs(), "locks must be cleared — every still is stale");
        assertNull(saved.getReuseImagesFromJob(),
                "reuse-from-job must be cleared or the assets stage would re-copy old-cast images");
        // Status marked for the assets stage and dispatched through self (async proxy).
        assertEquals(JobStatus.ASSETS_GENERATING, saved.getStatus());
        verify(self).runAssetsStage(job.getId());
    }

    @Test
    void rerenderVisualsRefusesAMidStageJob() {
        VideoJob job = seedJob(JobStatus.ASSETS_GENERATING);

        assertThrows(IllegalStateException.class,
                () -> orchestrator.rerenderVisuals(job.getId()));

        // Untouched: no clear, no dispatch.
        VideoJob saved = store.get(job.getId());
        assertEquals("1,2", saved.getLockedSceneSeqs());
        assertTrue(saved.getAssemblyScenesJson().contains("imagePath"));
        verify(self, never()).runAssetsStage(any(UUID.class));
    }
}
