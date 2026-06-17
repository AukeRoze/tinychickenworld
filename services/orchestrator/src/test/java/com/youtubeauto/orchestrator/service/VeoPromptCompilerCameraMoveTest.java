package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for the ADDITIVE per-scene camera-move override in
 * {@link VeoPromptCompiler}. Verifies that:
 * <ul>
 *   <li>with no/blank/unknown {@code cameraMove} the prompt keeps the
 *       phase-default movement from the cameraBible (existing behaviour);</li>
 *   <li>with a known {@code cameraMove} id the move's directive REPLACES the
 *       phase-default movement and is added as a dedicated "Camera move:" clause.</li>
 * </ul>
 * No Spring context — the compiler is built straight through its
 * {@code @RequiredArgsConstructor} with a temp bible directory holding a minimal
 * {@code channel.yml} plus a {@code camera-moves.json} sibling.
 */
class VeoPromptCompilerCameraMoveTest {

    private static final String CHANNEL_YML =
            "cameraBible:\n"
          + "  default: { angle: \"eye-level\", lens: \"50mm normal\", movement: \"DEFAULT_PHASE_MOVE\", focus: \"lock focus on the main character\", depthOfField: \"medium depth\" }\n"
          + "  setup:   { angle: \"eye-level\", lens: \"35mm\", movement: \"DEFAULT_PHASE_MOVE\", focus: \"main character sharp\", depthOfField: \"medium depth\" }\n";

    private static final String CAMERA_MOVES_JSON =
            "{ \"moves\": [ { \"id\": \"slow-dolly-in\", \"camera\": \"SLOW DOLLY IN (PUSH). The camera glides forward toward the subject.\" } ] }";

    private VeoPromptCompiler compilerFor(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        Files.writeString(bible, CHANNEL_YML);
        Files.writeString(dir.resolve("camera-moves.json"), CAMERA_MOVES_JSON);
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
    void blankCameraMove_keepsPhaseDefaultMovement(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp);
        String out = c.compile("A chick hops", "setup", List.of(), "", "", "",
                "", "", "natural", null);
        assertTrue(out.contains("DEFAULT_PHASE_MOVE"),
                "phase-default movement should be present when no move is chosen");
        assertFalse(out.contains("Camera move:"),
                "no explicit Camera move clause should be added");
    }

    @Test
    void unknownCameraMove_keepsPhaseDefaultMovement(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp);
        String out = c.compile("A chick hops", "setup", List.of(), "", "", "",
                "", "", "natural", "does-not-exist");
        assertTrue(out.contains("DEFAULT_PHASE_MOVE"));
        assertFalse(out.contains("Camera move:"));
    }

    @Test
    void knownCameraMove_overridesPhaseDefaultMovement(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp);
        String out = c.compile("A chick hops", "setup", List.of(), "", "", "",
                "", "", "natural", "slow-dolly-in");
        assertTrue(out.contains("Camera move: SLOW DOLLY IN (PUSH)."),
                "the chosen move's directive should appear as a Camera move clause");
        assertFalse(out.contains("DEFAULT_PHASE_MOVE"),
                "phase-default movement should be dropped to avoid a contradiction");
    }

    @Test
    void nineArgOverload_isUnchanged(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerFor(tmp);
        String nine = c.compile("A chick hops", "setup", List.of(), "", "", "",
                "", "", "natural");
        String tenNull = c.compile("A chick hops", "setup", List.of(), "", "", "",
                "", "", "natural", null);
        org.junit.jupiter.api.Assertions.assertEquals(nine, tenNull,
                "9-arg compile must delegate to the 10-arg path with identical output");
    }
}
