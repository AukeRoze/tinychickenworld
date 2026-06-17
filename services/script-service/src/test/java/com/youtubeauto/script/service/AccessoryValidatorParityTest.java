package com.youtubeauto.script.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUARD-PARITY CONTRACT (T2 — third copy). {@link AccessoryValidator} is the
 * script-service re-prompt sibling of the orchestrator/image AccessoryGuard.
 * Its detection MUST agree with the guards on the canonical cases (it is
 * detection-only, so it flags the same contradictions the guards rewrite, PLUS
 * the {@code <Name>'s <accessory>} form the compile-time guard cannot rewrite).
 *
 * <p>Keep these cases identical to {@code AccessoryGuardTest} (orchestrator) and
 * {@code AccessoryGuardParityTest} (image). If any of the three copies drifts,
 * its module's parity test fails the build.
 */
class AccessoryValidatorParityTest {

    private static final List<AccessoryValidator.Cm> CAST = List.of(
            new AccessoryValidator.Cm("pip", "Pip", "straw farmer hat",
                    Set.of("hat", "bandana"), Set.of("glasses", "scarf"), "she"),
            new AccessoryValidator.Cm("mo", "Mo", "thick red knitted scarf",
                    Set.of("scarf"), Set.of("glasses", "hat", "bandana"), "he"),
            new AccessoryValidator.Cm("bo", "Bo", "round thin-framed eyeglasses",
                    Set.of("glasses", "scarf"), Set.of("hat", "bandana"), "she"));

    private static boolean flags(String desc) {
        return !AccessoryValidator.contradictionsInText(desc, CAST).isEmpty();
    }

    @Test
    void flagsTheRealContradictions() {
        assertTrue(flags("Over-the-shoulder past Pip: Mo waddles in, adjusting his glasses."));
        assertTrue(flags("Bo stands beside Pip, leaning in behind her straw farmer hat."));
        assertTrue(flags("Pip tugs her scarf snug."));
        // Name-possessive — the form the compile-time guard misses:
        assertTrue(flags("Mo's spectacles glint in the sun."));
        assertTrue(flags("Bo's hat slips over her eyes."));
    }

    @Test
    void passesLegitimateSelfReferences() {
        assertFalse(flags("Bo pushes her glasses up the beak."));
        assertFalse(flags("Pip tips her straw farmer hat while Bo laughs."));
        assertFalse(flags("Mo's scarf is snug."));
        assertFalse(flags("Pip's straw hat tips back."));
        assertFalse(flags("The three chicks gather quietly by the egg."));
    }
}
