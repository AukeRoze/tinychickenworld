package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link AccessoryGuard} — the EP3 scene-5 "Mo adjusts his
 * glasses" contradiction (glasses are Bo's; Mo's DNA forbids them).
 *
 * <p>GUARD-PARITY (T2): the image-service has a hand copy of this guard. Keep the
 * canonical cases here identical to image-service {@code AccessoryGuardParityTest}
 * — if the two guard copies drift, one module's parity test fails the build.
 */
class AccessoryGuardTest {

    // pip (she) → hat + bandana; mo (he) → scarf (forbids glasses/hat/bandana);
    // bo (she) → glasses + scarf (forbids hat/bandana). Gender lets a possessive
    // bind to the right chick even when another name sits closer.
    private static final List<AccessoryGuard.CharModel> CAST = List.of(
            new AccessoryGuard.CharModel("pip", "Pip", "straw farmer hat",
                    Set.of("hat", "bandana"), Set.of("glasses", "scarf"), "she"),
            new AccessoryGuard.CharModel("mo", "Mo", "thick red knitted scarf",
                    Set.of("scarf"), Set.of("glasses", "hat", "bandana"), "he"),
            new AccessoryGuard.CharModel("bo", "Bo", "round thin-framed eyeglasses",
                    Set.of("glasses", "scarf"), Set.of("hat", "bandana"), "she"));

    @Test
    void rewritesMoAdjustingGlassesToHisOwnScarf() {
        String in = "Over-the-shoulder past Pip: Mo waddles into frame from the right, "
                + "adjusting his glasses with a wingtip, peering down at the pale-cream egg "
                + "in its soil hollow.";
        String out = AccessoryGuard.sanitize(in, List.of("mo", "pip"), CAST);
        assertTrue(out.contains("adjusting his thick red knitted scarf"),
                "Mo's glasses action must be rewritten to his own scarf: " + out);
        assertFalse(out.toLowerCase().contains("glasses"),
                "no glasses must remain on Mo's action: " + out);
        // The rest of the sentence is untouched.
        assertTrue(out.contains("peering down at the pale-cream egg in its soil hollow"));
    }

    @Test
    void leavesBoOwnGlassesUntouched() {
        String in = "Close-up: Bo pushes her glasses up the beak with a wingtip before a joke.";
        String out = AccessoryGuard.sanitize(in, List.of("bo"), CAST);
        assertEquals(in, out, "Bo legitimately owns her glasses — must not change");
    }

    @Test
    void rewritesPipWrongScarfEvenThoughScarfIsShared() {
        // R3: Pip forbids a scarf and does NOT own one (scarf is Mo's/Bo's). The
        // old unique-owner gate skipped this; the forbids∧¬owns gate catches it.
        String in = "Pip tugs her scarf snug.";
        String out = AccessoryGuard.sanitize(in, List.of("pip"), CAST);
        assertTrue(out.contains("her straw farmer hat"), "Pip's wrong scarf → her own hat: " + out);
        assertFalse(out.toLowerCase().contains("scarf"));
    }

    @Test
    void leavesOwnerScarfUntouched() {
        // Bo and Mo each legitimately own a scarf — never rewritten.
        assertEquals("Bo tugs her scarf snug.",
                AccessoryGuard.sanitize("Bo tugs her scarf snug.", List.of("bo"), CAST));
        assertEquals("Mo tugs his scarf snug.",
                AccessoryGuard.sanitize("Mo tugs his scarf snug.", List.of("mo"), CAST));
    }

    @Test
    void rewritesBoHatEvenWhenPipSitsNextToHat() {
        // Scene-21 defect: "her" must bind to Bo (the leaning subject), not the
        // adjacent owner Pip. Gender alone can't split two 'she' chicks, so the
        // grammatical-subject-first heuristic (Bo is the sentence subject) wins.
        String in = "Bo stands beside Pip, leaning in with enormous curious eyes "
                + "behind her straw farmer hat.";
        String out = AccessoryGuard.sanitize(in, List.of("bo", "pip"), CAST);
        assertTrue(out.contains("her round thin-framed eyeglasses"),
                "Bo's wrong hat → her own glasses: " + out);
        assertFalse(out.toLowerCase().contains("hat"), "no hat must remain on Bo: " + out);
    }

    @Test
    void leavesLegitPipHatWhenBoAlsoPresent() {
        // Mirror image: Pip (sentence subject) legitimately tips her own hat while
        // Bo is mentioned — must NOT be rewritten.
        String in = "Pip tips her straw farmer hat while Bo laughs.";
        assertEquals(in, AccessoryGuard.sanitize(in, List.of("pip", "bo"), CAST));
    }

    @Test
    void rewritesPipWrongGlassesToHerHat() {
        String in = "Pip pushes her glasses up the beak.";
        String out = AccessoryGuard.sanitize(in, List.of("pip"), CAST);
        assertTrue(out.contains("pushes her straw farmer hat up the beak")
                        || out.contains("her straw farmer hat"),
                "Pip's wrong glasses must become her own hat: " + out);
        assertFalse(out.toLowerCase().contains("glasses"));
    }

    @Test
    void findContradictionsFlagsMoGlassesAndPassesCleanText() {
        String bad = "Mo adjusts his glasses.";
        assertFalse(AccessoryGuard.findContradictions(bad, List.of("mo"), CAST).isEmpty());

        String good = "Mo tugs his scarf and looks at the egg.";
        assertTrue(AccessoryGuard.findContradictions(good, List.of("mo"), CAST).isEmpty());
    }

    // ---- Non-possessive worn-accessory branch (EP3 scene-7 defect class) -------
    // KEEP IDENTICAL to image-service AccessoryGuardParityTest.

    @Test
    void rewritesScene7NonPossessiveGlassesOnMo() {
        // EP3 scene-7: the video action line hands glasses to Mo with NO possessive
        // ("with glasses askew"). The possessive pattern misses it; the worn
        // non-possessive branch must catch it and rewrite to Mo's own scarf.
        String in = "Pip mid-fall with wings flailing, Mo toppling with glasses askew, "
                + "Bo flat on her back with feet in the air.";
        String out = AccessoryGuard.sanitize(in, List.of("pip", "mo", "bo"), CAST);
        assertFalse(out.toLowerCase().contains("glasses"),
                "no glasses may remain attributed to Mo: " + out);
        assertTrue(out.contains("Mo toppling with his thick red knitted scarf askew"),
                "Mo's glasses must become his own scarf: " + out);
        assertTrue(out.contains("Pip mid-fall with wings flailing"), out);
        assertTrue(out.contains("Bo flat on her back with feet in the air"), out);
    }

    @Test
    void leavesBoNonPossessiveOwnGlassesUntouched() {
        // Same worn phrasing on the legitimate owner (Bo owns glasses) → unchanged.
        String in = "Bo wobbles on the egg, glasses sliding down her beak.";
        assertEquals(in, AccessoryGuard.sanitize(in, List.of("pip", "mo", "bo"), CAST));
    }

    @Test
    void leavesBareAccessoryObjectMentionUntouched() {
        // No wear-preposition and no displacement word → not attributable to a
        // body; the guard must stay conservative and leave it alone.
        String in = "Mo slides the spare glasses across the table to Bo.";
        assertEquals(in, AccessoryGuard.sanitize(in, List.of("pip", "mo", "bo"), CAST));
    }

    @Test
    void findContradictionsFlagsNonPossessiveMoGlasses() {
        String bad = "Mo toppling with glasses askew.";
        assertFalse(AccessoryGuard.findContradictions(bad, List.of("mo"), CAST).isEmpty());
    }

    @Test
    void noBibleOrNoSubjectIsSafe() {
        assertEquals("Mo adjusts his glasses.",
                AccessoryGuard.sanitize("Mo adjusts his glasses.", List.of("mo"), List.of()));
        // possessive with no resolvable named subject before it → left as-is.
        String orphan = "Adjusting his glasses by the fence.";
        assertEquals(orphan, AccessoryGuard.sanitize(orphan, List.of("mo"), CAST));
    }
}
