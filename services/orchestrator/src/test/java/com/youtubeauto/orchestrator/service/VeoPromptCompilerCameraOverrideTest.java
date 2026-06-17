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
 * Unit test for the per-scene {@code veoCameraOverride} in {@link VeoPromptCompiler}:
 * when set, it REPLACES the phase-default Camera line verbatim; when blank/null
 * the output is identical to the phase-preset path (existing scenes untouched).
 */
class VeoPromptCompilerCameraOverrideTest {

    private static final String CAMERA =
            "cameraBible:\n"
          + "  default: { angle: \"eye-level\", lens: \"50mm\", movement: \"slow move\", focus: \"f\", depthOfField: \"d\" }\n";

    private static final String CHARS =
            "characters:\n"
          + "  - id: pip\n"
          + "    name: \"Pip\"\n"
          + "    dna:\n"
          + "      coreColor: \"cream-white\"\n"
          + "      veoKey: \"PIP_VEOKEY\"\n";

    private VeoPromptCompiler compiler(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        Files.writeString(bible, CAMERA + CHARS);
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
    void override_replacesPhaseCameraLine(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String out = c.compile("Low-angle shot looking up at Pip", "setup",
                List.of("pip"), "", "", "", "", "", "natural",
                null, "low-angle, 35mm, slow drift");
        assertTrue(out.contains("Camera: low-angle, 35mm, slow drift."),
                "override text should be the Camera line verbatim");
        assertFalse(out.contains("eye-level"),
                "the phase-default camera angle must be gone when overridden");
    }

    @Test
    void blankOverride_keepsPhasePreset(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String out = c.compile("Low-angle shot looking up at Pip", "setup",
                List.of("pip"), "", "", "", "", "", "natural",
                null, null);
        assertTrue(out.contains("Camera: eye-level, 50mm"),
                "with no override the phase preset drives the Camera line");
    }
}
