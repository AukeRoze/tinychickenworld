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
 * Generic prompt-hygiene fixes in {@link VeoPromptCompiler}, so future prompts
 * stay clean regardless of bible content:
 *   1. a location description ending in a period must not yield "...world., light";
 *   2. the opening motion line must never double "natural" ("gentle, natural, natural");
 *   3. a tight close-up relaxes the cast lock so off-frame members aren't crammed in.
 */
class VeoPromptCompilerHygieneTest {

    private static final String BIBLE =
            "veoLeanPrompts: true\n"
          + "veoScaleRule: \"SCALE\"\n"
          + "cameraBible:\n"
          + "  default: { angle: \"eye-level\", lens: \"50mm\", movement: \"slow move\", focus: \"f\", depthOfField: \"d\" }\n"
          + "locations:\n"
          + "  - id: coopx\n"
          + "    description: >\n"
          + "      A cosy wooden room with a small window. The smallest scale in the world.\n"
          + "characters:\n"
          + "  - id: pip\n"
          + "    name: \"Pip\"\n"
          + "    dna:\n"
          + "      coreColor: \"cream-white\"\n"
          + "      veoKey: \"PIP_KEY\"\n"
          + "  - id: mo\n"
          + "    name: \"Mo\"\n"
          + "    dna:\n"
          + "      coreColor: \"blue-grey\"\n"
          + "      veoKey: \"MO_KEY\"\n";

    private VeoPromptCompiler compiler(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        Files.writeString(bible, BIBLE);
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
    void locationEndingInPeriod_doesNotBreakSettingPunctuation(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String out = c.compile("Pip looks around", "setup",
                List.of("pip"), "coopx", "", "", "", "", "natural");
        assertFalse(out.contains("world., "), "trailing period before the light phrase must be stripped");
        assertTrue(out.contains("world, warm golden-hour light"),
                "the location should flow cleanly into the light phrase");
    }

    @Test
    void openingMotionLine_neverDoublesNatural(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String out = c.compile("Pip looks around", "setup",
                List.of("pip"), "coopx", "", "", "", "", "natural");
        assertFalse(out.contains("natural, natural"), "the word 'natural' must not be duplicated");
        assertTrue(out.contains("gentle, natural, child-friendly motion"),
                "the natural-pace opening should read cleanly");
    }

    @Test
    void closeUp_relaxesCastLock(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String out = c.compile("Extreme close-up of Pip", "development",
                List.of("pip", "mo"), "coopx", "", "", "", "", "natural",
                null, "extreme close-up, 85mm, very shallow depth");
        assertTrue(out.contains("tight close-up: only the framed character"),
                "a close-up should tell Veo the other cast members may be out of frame");
    }

    @Test
    void wideShot_keepsFullCastLock(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String out = c.compile("Pip and Mo in a wide shot", "development",
                List.of("pip", "mo"), "coopx", "", "", "", "", "natural");
        assertFalse(out.contains("tight close-up: only the framed character"),
                "a non-close-up shot must NOT get the close-up cast relaxation");
    }
}
