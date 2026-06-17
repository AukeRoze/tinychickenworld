package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link VeoPromptLinter}: the deterministic prompt invariants.
 * Crafted strings exercise each rule; {@link VeoPromptCompilerLeanTest} proves
 * the REAL compiler output passes the linter (the regression net).
 */
class VeoPromptLinterTest {

    private static final String HEALTHY =
            "Animate from the start frame with gentle motion. "
          + "Action: Pip tucks the straw. "
          + "Camera: eye-level, 50mm, slow move. "
          + "Setting: a cozy coop, warm light. "
          + "Cast lock: this shot has EXACTLY 2 characters (Pip and Mo) — these and no others. "
          + "Soft 3D Pixar / Illumination cartoon look.";

    @Test
    void healthyPromptHasNoFindings() {
        assertTrue(VeoPromptLinter.lint(HEALTHY, 2).isEmpty(),
                "a complete, consistent prompt must pass: " + VeoPromptLinter.lint(HEALTHY, 2));
        assertTrue(VeoPromptLinter.isHealthy(HEALTHY, 2));
    }

    @Test
    void truncatedMidWordIsFlagged() {
        // The scene-16 defect: "…bright, soft midday sunlight. C"
        String cut = HEALTHY.substring(0, HEALTHY.indexOf("Cast lock:")) + "Colour mood: warm. C";
        assertTrue(VeoPromptLinter.lint(cut, 2).stream().anyMatch(s -> s.contains("truncated")),
                "a mid-word cut must be flagged");
    }

    @Test
    void truncatedMidClauseIsFlagged() {
        // The scene-23 defect: "…each keeps ONLY its own body colour;"
        String cut = HEALTHY.substring(0, HEALTHY.indexOf("Soft 3D")) + "each keeps its own body colour;";
        assertTrue(VeoPromptLinter.lint(cut, 2).stream().anyMatch(s -> s.contains("truncated")),
                "a cut ending on a semicolon must be flagged");
    }

    @Test
    void missingSectionIsFlagged() {
        String noCamera = HEALTHY.replace("Camera: eye-level, 50mm, slow move. ", "");
        assertTrue(VeoPromptLinter.lint(noCamera, 2).stream().anyMatch(s -> s.contains("Camera:")),
                "a dropped Camera section must be flagged");
    }

    @Test
    void countMismatchIsFlagged() {
        // Prompt says EXACTLY 2 but the scene cast is 3.
        List<String> f = VeoPromptLinter.lint(HEALTHY, 3);
        assertTrue(f.stream().anyMatch(s -> s.contains("does not match")),
                "a cast-lock count that disagrees with the cast size must be flagged: " + f);
    }

    @Test
    void countCheckSkippedWhenCastUnknown() {
        // castCount <= 0 → skip the count rule (e.g. caller has no cast handy).
        assertFalse(VeoPromptLinter.lint(HEALTHY, 0).stream().anyMatch(s -> s.contains("does not match")));
    }

    @Test
    void lightTimeContradictionIsFlagged() {
        // timeOfDay drove a midday Setting, but the action describes dusk —
        // the scene-19/20/21 defect.
        String mixed = HEALTHY
                .replace("Setting: a cozy coop, warm light. ",
                         "Setting: a cozy coop, bright, soft midday sunlight. ")
                .replace("Action: Pip tucks the straw. ",
                         "Action: Pip tucks the straw under a deep purple twilight sky. ");
        assertTrue(VeoPromptLinter.lint(mixed, 2).stream().anyMatch(s -> s.contains("contradiction")),
                "midday light under a dusk/twilight action must be flagged: " + VeoPromptLinter.lint(mixed, 2));
    }

    @Test
    void daytimeSceneWithoutDarkCuesIsClean() {
        // A plain midday prompt with no night words must NOT be flagged.
        String day = HEALTHY.replace("Setting: a cozy coop, warm light. ",
                                     "Setting: a sunny garden, bright, soft midday sunlight. ");
        assertTrue(VeoPromptLinter.lint(day, 2).stream().noneMatch(s -> s.contains("contradiction")),
                "a consistent daytime prompt must not be flagged: " + VeoPromptLinter.lint(day, 2));
    }

    @Test
    void scaleContradictionIsFlagged() {
        // Action calls the chicks "the same size" while the prompt locks a
        // canonical Relative size where they differ (the scene-26 defect).
        String s = HEALTHY + " Relative size: Pip is the smallest, Mo is slightly larger. "
                + "The three chicks are the same size as each other.";
        assertTrue(VeoPromptLinter.lint(s, 2).stream().anyMatch(x -> x.contains("Scale contradiction")),
                "a 'same size' action against the Relative size lock must be flagged: " + VeoPromptLinter.lint(s, 2));
    }

    @Test
    void legacySameSmallSizeWordingIsNotFlagged() {
        // The legacy scale clause says "the SAME small size" — must NOT trip the
        // 'same size' marker (no contiguous "same size" substring).
        String s = HEALTHY + " Scale lock: all chicks present are the SAME small size and shape.";
        assertTrue(VeoPromptLinter.lint(s, 2).stream().noneMatch(x -> x.contains("Scale contradiction")),
                "the compiler's own legacy scale wording must not be flagged: " + VeoPromptLinter.lint(s, 2));
    }

    @Test
    void cameraContradictionIsFlagged() {
        // Climax push-in preset copied onto a wide-reveal beat (scene-22/23/24).
        String s = HEALTHY
                .replace("Camera: eye-level, 50mm, slow move. ",
                         "Camera: 85mm long lens, slow push-in toward the emotional peak. ")
                .replace("Action: Pip tucks the straw. ",
                         "Action: the camera pulls back slowly to reveal the full scene. ");
        assertTrue(VeoPromptLinter.lint(s, 2).stream().anyMatch(x -> x.contains("Camera contradiction")),
                "push-in camera under a pull-back action must be flagged: " + VeoPromptLinter.lint(s, 2));
    }

    @Test
    void focusContradictionIsFlagged() {
        // A Pip close-up under the resolution preset's "soft focus on the flock"
        // (the scene-27 defect).
        String s = HEALTHY
                .replace("Action: Pip tucks the straw. ",
                         "Action: Close-up of Pip looking directly at the camera. ")
                .replace("Camera: eye-level, 50mm, slow move. ",
                         "Camera: eye-level, 50mm, very slow drift, soft focus on the flock together. ");
        assertTrue(VeoPromptLinter.lint(s, 2).stream().anyMatch(x -> x.contains("Focus contradiction")),
                "a close-up with flock focus must be flagged: " + VeoPromptLinter.lint(s, 2));
    }

    @Test
    void overLongPromptIsFlaggedForTruncationRisk() {
        String longP = HEALTHY + " filler".repeat(500);   // ~500 extra words
        assertTrue(VeoPromptLinter.lint(longP, 2).stream().anyMatch(s -> s.contains("soft budget")),
                "an over-long prompt should be flagged for truncation risk");
    }

    @Test
    void normalLengthPromptIsNotFlaggedForLength() {
        assertTrue(VeoPromptLinter.lint(HEALTHY, 2).stream().noneMatch(s -> s.contains("soft budget")),
                "a short healthy prompt must not trip the length budget");
    }

    @Test
    void emptyPromptIsFlagged() {
        assertFalse(VeoPromptLinter.lint("", 2).isEmpty());
        assertFalse(VeoPromptLinter.lint(null, 2).isEmpty());
    }
}
