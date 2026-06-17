package com.youtubeauto.image.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GUARD-PARITY CONTRACT (T2). The image-service {@link AccessoryGuard} is a hand
 * copy of the orchestrator's (separate Maven modules, no shared lib). They MUST
 * behave identically, or a scene's still and its Veo clip disagree about whose
 * accessory is whose.
 *
 * <p>This test mirrors, case-for-case, the orchestrator
 * {@code AccessoryGuardTest}. When you change the guard logic in EITHER module,
 * change BOTH and keep these canonical cases identical in BOTH test files — if
 * one copy drifts, its module's parity test fails the build. (Same convention as
 * {@code PromptComposerDnaTest} for the DNA split.)
 */
class AccessoryGuardParityTest {

    // pip (she) → hat+bandana; mo (he) → scarf; bo (she) → glasses+scarf.
    // KEEP IDENTICAL to orchestrator AccessoryGuardTest.CAST.
    private static final List<AccessoryGuard.CharModel> CAST = List.of(
            new AccessoryGuard.CharModel("pip", "Pip", "straw farmer hat",
                    Set.of("hat", "bandana"), Set.of("glasses", "scarf"), "she"),
            new AccessoryGuard.CharModel("mo", "Mo", "thick red knitted scarf",
                    Set.of("scarf"), Set.of("glasses", "hat", "bandana"), "he"),
            new AccessoryGuard.CharModel("bo", "Bo", "round thin-framed eyeglasses",
                    Set.of("glasses", "scarf"), Set.of("hat", "bandana"), "she"));

    private static String fix(String in) {
        return AccessoryGuard.sanitize(in, List.of("pip", "mo", "bo"), CAST);
    }

    @Test
    void moGlassesBecomesHisScarf() {
        String out = fix("Over-the-shoulder past Pip: Mo waddles into frame from the right, "
                + "adjusting his glasses with a wingtip, peering down at the egg.");
        assertTrue(out.contains("adjusting his thick red knitted scarf"), out);
        assertFalse(out.toLowerCase().contains("glasses"), out);
    }

    @Test
    void boOwnGlassesUntouched() {
        String in = "Close-up: Bo pushes her glasses up the beak before a joke.";
        assertEquals(in, fix(in));
    }

    @Test
    void pipWrongScarfBecomesHerHat() {
        String out = fix("Pip tugs her scarf snug.");
        assertTrue(out.contains("her straw farmer hat"), out);
        assertFalse(out.toLowerCase().contains("scarf"), out);
    }

    @Test
    void ownerScarfUntouched() {
        assertEquals("Bo tugs her scarf snug.", fix("Bo tugs her scarf snug."));
        assertEquals("Mo tugs his scarf snug.", fix("Mo tugs his scarf snug."));
    }

    @Test
    void boHatBecomesHerGlassesEvenWhenPipAdjacent() {
        String out = fix("Bo stands beside Pip, leaning in with enormous curious eyes "
                + "behind her straw farmer hat.");
        assertTrue(out.contains("her round thin-framed eyeglasses"), out);
        assertFalse(out.toLowerCase().contains("hat"), out);
    }

    @Test
    void legitPipHatUntouchedWhenBoPresent() {
        String in = "Pip tips her straw farmer hat while Bo laughs.";
        assertEquals(in, fix(in));
    }

    // ---- Non-possessive worn-accessory branch (EP3 scene-7 defect class) -------
    // KEEP IDENTICAL to orchestrator AccessoryGuardTest.

    @Test
    void rewritesScene7NonPossessiveGlassesOnMo() {
        String out = fix("Pip mid-fall with wings flailing, Mo toppling with glasses askew, "
                + "Bo flat on her back with feet in the air.");
        assertFalse(out.toLowerCase().contains("glasses"), out);
        assertTrue(out.contains("Mo toppling with his thick red knitted scarf askew"), out);
        assertTrue(out.contains("Pip mid-fall with wings flailing"), out);
        assertTrue(out.contains("Bo flat on her back with feet in the air"), out);
    }

    @Test
    void leavesBoNonPossessiveOwnGlassesUntouched() {
        String in = "Bo wobbles on the egg, glasses sliding down her beak.";
        assertEquals(in, fix(in));
    }

    @Test
    void leavesBareAccessoryObjectMentionUntouched() {
        String in = "Mo slides the spare glasses across the table to Bo.";
        assertEquals(in, fix(in));
    }
}
