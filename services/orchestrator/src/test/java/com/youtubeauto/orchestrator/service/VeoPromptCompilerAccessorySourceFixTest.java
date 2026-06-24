package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Locks the SOURCE-FIX helpers added to {@link VeoPromptCompiler}
 * ({@code findAccessoryContradictions} / {@code sanitizeAccessoryAction}) — the
 * methods the orchestrator uses to clean an accessory-vs-action contradiction
 * ONCE at the scene-store instead of letting the silent per-compile rewrite
 * re-run on the same dirty source forever.
 *
 * <p>Mirrors the bible-construction pattern of the other VeoPromptCompiler tests.
 * Mo owns a scarf and is FORBIDDEN glasses (Bo's signature) — so "Mo … his
 * glasses" is a machine-detectable contradiction that must be rewritten to Mo's
 * own signature accessory; clean text must round-trip unchanged (idempotent), so
 * the inline guard inside {@code compile} stays a safe no-op after the fix.
 */
class VeoPromptCompilerAccessorySourceFixTest {

    private static final String CAMERA =
            "cameraBible:\n"
          + "  default: { angle: \"eye-level\", lens: \"50mm\", movement: \"slow move\", focus: \"f\", depthOfField: \"d\" }\n";

    private static final String CHARS =
            "characters:\n"
          + "  - id: mo\n"
          + "    name: \"Mo\"\n"
          + "    dna:\n"
          + "      coreColor: \"blue-grey\"\n"
          + "      accessory: \"a thick red knitted scarf\"\n"
          + "      antiAccessory: \"eyeglasses or glasses\"\n"
          + "      signatureAccessoryShort: \"thick red knitted scarf\"\n"
          + "      tic: \"tugs his scarf when he thinks\"\n"
          + "  - id: bo\n"
          + "    name: \"Bo\"\n"
          + "    dna:\n"
          + "      coreColor: \"yellow\"\n"
          + "      accessory: \"round eyeglasses\"\n"
          + "      antiAccessory: \"any scarf\"\n"
          + "      signatureAccessoryShort: \"round eyeglasses\"\n"
          + "      tic: \"pushes her glasses up\"\n";

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
    void detectsAndRewritesForbiddenAccessory(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String dirty = "Mo waddles in, adjusting his glasses with a wingtip.";
        List<String> cast = List.of("mo", "bo");

        assertFalse(c.findAccessoryContradictions(dirty, cast).isEmpty(),
                "Mo wearing glasses (Bo's signature, forbidden on Mo) must be flagged");

        String clean = c.sanitizeAccessoryAction(dirty, cast);
        assertFalse(clean.toLowerCase().contains("his glasses"),
                "the forbidden 'his glasses' must be rewritten away");
        assertTrue(clean.contains("thick red knitted scarf"),
                "the action must be rewritten to Mo's OWN signature accessory");
    }

    @Test
    void cleanTextIsIdempotentNoOp(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compiler(tmp);
        String clean = "Mo tugs his thick red knitted scarf and grins at Bo.";
        List<String> cast = List.of("mo", "bo");

        assertTrue(c.findAccessoryContradictions(clean, cast).isEmpty(),
                "already-clean action has no contradiction");
        assertEquals(clean, c.sanitizeAccessoryAction(clean, cast),
                "sanitizing clean text must change nothing (safe backstop in compile)");
    }
}
