package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hero-prop canon ({@code props:} in the bible). A hero prop named in the action
 * text gets a KEY OBJECT block injected into the director's brief — the same hard
 * look + anti-drift lock the cast already gets — so the object stays identical
 * across the whole episode. The optional {@code propStates} map selects its current
 * state. A scene without the prop, or a bible without a {@code props:} block, is
 * byte-identical to before (no KEY OBJECT section).
 */
class VeoPromptCompilerPropCanonTest {

    private static final String CAMERA =
            "cameraBible:\n"
          + "  default: { angle: \"eye-level\", lens: \"50mm\", movement: \"slow move\", focus: \"f\", depthOfField: \"d\" }\n"
          + "  setup: { angle: \"eye-level\", lens: \"35mm\", movement: \"slow drift\", focus: \"main character sharp\", depthOfField: \"medium\" }\n";

    private static final String CHARS =
            "characters:\n"
          + "  - id: pip\n    name: \"Pip\"\n    dna:\n"
          + "      coreColor: \"cream-white\"\n      veoKey: \"PIP_VEOKEY\"\n"
          + "      signatureSound: \"a bright rising curious chirp\"\n";

    private static final String PROPS =
            "props:\n"
          + "  - id: egg\n"
          + "    name: \"The Wobbly Egg\"\n"
          + "    role: hero\n"
          + "    aliases: [\"egg\", \"wobbly egg\"]\n"
          + "    veoKey: \"EGG_VEOKEY_CREAM\"\n"
          + "    scaleAnchor: \"about the size of Pip's head\"\n"
          + "    antiDrift: \"ANTIDRIFT_NEVER_GLOSSY\"\n"
          + "    signatureSound: \"a soft hollow tok-tok\"\n"
          + "    states:\n"
          + "      - id: intact\n        look: \"INTACT_LOOK whole shell\"\n        behaviour: \"rests heavy and still\"\n"
          + "      - id: cracked\n        look: \"CRACKED_LOOK jagged crack\"\n        behaviour: \"settles chipped side down\"\n";

    private VeoPromptCompiler compilerWithProps(Path dir) throws Exception {
        Path bible = dir.resolve("channel.yml");
        Files.writeString(bible,
                "veoLeanPrompts: true\nveoNativeAudio: true\nveoScaleRule: \"SCALE_RULE\"\n"
                        + CAMERA + CHARS + PROPS);
        OrchestratorProperties props = new OrchestratorProperties(
                new OrchestratorProperties.Services("", "", "", "", "", "", ""),
                new OrchestratorProperties.Poll(10, 5),
                new OrchestratorProperties.Anthropic(null, null, null, "claude-test", null, null),
                new OrchestratorProperties.Defaults("preschoolers", 60, false, "ken_burns"),
                new OrchestratorProperties.Brand("", ""),
                new OrchestratorProperties.Bible(bible.toString()));
        return new VeoPromptCompiler(props);
    }

    /** 14-arg compile helper (lines + propStates). */
    private static String compile(VeoPromptCompiler c, String action, Map<String, String> propStates) {
        return c.compile(action, "setup", List.of("pip"), "", "", "", "", "", "natural",
                null, null, null, null, propStates);
    }

    @Test
    void heroPropInAction_injectsKeyObjectCanon(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        String out = compile(c, "Pip leans in close to the egg", null);
        assertTrue(out.contains("KEY OBJECT — THE WOBBLY EGG"), "KEY OBJECT header expected: " + out);
        assertTrue(out.contains("EGG_VEOKEY_CREAM"), "prop veoKey canon expected: " + out);
        assertTrue(out.contains("ANTIDRIFT_NEVER_GLOSSY"), "prop anti-drift expected: " + out);
        assertTrue(out.contains("never reverts to an"), "no-revert lock expected: " + out);
    }

    @Test
    void noPropStates_locksLookButClaimsNoState(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        String out = compile(c, "Pip leans in close to the egg", null);
        assertTrue(out.contains("EGG_VEOKEY_CREAM"), "look still locked: " + out);
        assertFalse(out.contains("Current state:"), "no state claimed without propStates: " + out);
        assertFalse(out.contains("INTACT_LOOK"), "must not default to a state look: " + out);
    }

    @Test
    void propStates_selectsCurrentState(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        String out = compile(c, "Pip taps the egg and it splits open", Map.of("egg", "cracked"));
        assertTrue(out.contains("Current state: CRACKED_LOOK jagged crack"), "cracked state expected: " + out);
        assertFalse(out.contains("INTACT_LOOK"), "must not show the wrong state: " + out);
    }

    @Test
    void noHeroPropInAction_noKeyObjectSection(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        String out = compile(c, "Pip hops happily across the grass", null);
        assertFalse(out.contains("KEY OBJECT"), "no KEY OBJECT section without a hero prop: " + out);
        assertFalse(out.contains("EGG_VEOKEY_CREAM"), "prop canon must not leak in: " + out);
    }

    @Test
    void wordBoundary_substringDoesNotFalseMatch(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        // "eggplant" / "begged" contain "egg" as a substring but not as a word.
        String out = compile(c, "Pip nibbles an eggplant in the garden", null);
        assertFalse(out.contains("KEY OBJECT"), "substring 'egg' in 'eggplant' must not match: " + out);
    }

    @Test
    void propStateOrders_readsBibleOrder(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        Map<String, List<String>> orders = c.propStateOrders();
        assertTrue(orders.containsKey("egg"), "egg order expected: " + orders);
        assertEquals(List.of("intact", "cracked"), orders.get("egg"), "bible state order expected");
    }

    @Test
    void monotonicity_flagsRegressionButPassesForwardProgression(@TempDir Path tmp) throws Exception {
        VeoPromptCompiler c = compilerWithProps(tmp);
        Map<String, List<String>> orders = c.propStateOrders();
        // intact -> cracked -> intact : the last scene un-cracks the egg → flagged.
        List<Map<String, String>> regressing = List.of(
                Map.of("egg", "intact"), Map.of("egg", "cracked"), Map.of("egg", "intact"));
        assertFalse(VeoPromptLinter.lintPropMonotonicity(regressing, orders).isEmpty(),
                "a state regression must be flagged");
        // intact -> intact -> cracked : monotone → clean.
        List<Map<String, String>> forward = List.of(
                Map.of("egg", "intact"), Map.of("egg", "intact"), Map.of("egg", "cracked"));
        assertTrue(VeoPromptLinter.lintPropMonotonicity(forward, orders).isEmpty(),
                "a monotone progression must pass");
    }
}
