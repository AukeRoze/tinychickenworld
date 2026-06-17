package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit test for the automatic, whitelisted camera-move selection
 * ({@link VeoPromptCompiler#autoCameraMove}). Verifies the bible flag gate,
 * the low/medium intensity whitelist (high-intensity candidates are dropped),
 * and the deterministic per-seq rotation.
 */
class VeoPromptCompilerAutoMoveTest {

    // establishing intent: primary is HIGH (must be filtered out); the two
    // alternatives are medium/low (the only safe candidates that may be picked).
    private static final String MAPPING_JSON =
            "{ \"intents\": [ { \"intent\": \"establishing\", \"primary\": \"drone-flyover\","
          + " \"alternatives\": [\"top-down-gods-eye\", \"optical-zoom-out\"], \"bold\": [] } ] }";

    private static final String MOVES_JSON =
            "{ \"moves\": [ "
          + "{ \"id\": \"drone-flyover\",      \"motion_intensity\": \"high\",   \"camera\": \"DRONE FLYOVER.\" },"
          + "{ \"id\": \"top-down-gods-eye\",  \"motion_intensity\": \"medium\", \"camera\": \"TOP-DOWN.\" },"
          + "{ \"id\": \"optical-zoom-out\",   \"motion_intensity\": \"low\",    \"camera\": \"ZOOM OUT.\" } ] }";

    private VeoPromptCompiler compilerFor(Path dir, boolean autoEnabled) throws Exception {
        Path bible = dir.resolve("channel.yml");
        String yml = "cameraBible:\n"
                   + "  default: { angle: \"eye-level\", lens: \"50mm\", movement: \"M\", focus: \"f\", depthOfField: \"d\" }\n"
                   + (autoEnabled ? "cameraMovesAuto: true\n" : "");
        Files.writeString(bible, yml);
        Files.writeString(dir.resolve("camera-moves.json"), MOVES_JSON);
        Files.writeString(dir.resolve("scene-camera-mapping.json"), MAPPING_JSON);
        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(bible.toString()));
        return new VeoPromptCompiler(props);
    }

    @Test
    void disabled_returnsNull(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp, false);
        assertNull(c.autoCameraMove("setup", 0),
                "with the bible flag off, no move should be auto-assigned");
    }

    @Test
    void enabled_dropsHighIntensityAndPicksSafe(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp, true);
        String pick0 = c.autoCameraMove("setup", 0);
        // The HIGH-intensity primary (drone-flyover) must never be chosen.
        assertNotEquals("drone-flyover", pick0,
                "high-intensity moves must be excluded from the auto rotation");
        // Only the two safe alternatives remain; with 2 candidates seq parity
        // rotates between them deterministically.
        assertEquals(c.autoCameraMove("setup", 0), c.autoCameraMove("setup", 2),
                "rotation is deterministic and periodic in seq");
        assertNotEquals(c.autoCameraMove("setup", 0), c.autoCameraMove("setup", 1),
                "consecutive seqs pick different safe moves so the montage varies");
    }
}
