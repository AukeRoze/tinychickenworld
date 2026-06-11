package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.anthropic.GeneratedScript.Line;
import com.youtubeauto.script.anthropic.GeneratedScript.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the three pacing rules for ages 3-6 (max ~3.2 woorden/sec, max 2
 * speaker-wissels, exact één stille acteer-beat). These rules came straight
 * out of the episode-1 benchmark finding (subtitle block 17: three speakers
 * in five seconds) — a regression here is invisible until a child loses the
 * thread, so the gate itself needs a gate.
 */
class PacingValidatorTest {

    private final PacingValidator validator = new PacingValidator();

    private static Scene scene(int seq, int dur, List<Line> lines, String visualDesc) {
        return new Scene(seq, lines, visualDesc, List.of("pip"), "farmyard",
                "development", "midday", "clear", "goal", "joy (3/5)",
                "natural", "", "", dur);
    }

    private static Scene talking(int seq, int dur, String speaker, String text) {
        return new Scene(seq, List.of(new Line(speaker, text)), "Pip looks around the yard.",
                List.of("pip"), "farmyard", "development", "midday", "clear",
                "goal", "joy (3/5)", "natural", "", "", dur);
    }

    /** A silent beat with a real acting description (≥10 words). */
    private static Scene silentBeat(int seq) {
        return scene(seq, 4, List.of(),
                "Pip frozen mid-lean over the puddle, beak inches from the water, eyes huge, breath held.");
    }

    @Test
    void validScriptPasses() {
        GeneratedScript s = new GeneratedScript("t", "h", "cta", List.of(
                talking(1, 5, "pip", "Look at the big puddle over there"),
                silentBeat(2),
                talking(3, 5, "mo", "It looks just like the sky")));
        assertFalse(validator.validate(s).failed());
    }

    @Test
    void tooManyWordsPerSecondFails() {
        // 24 words in 5s = 4.8 w/s — way over the 3.2 preschool ceiling.
        GeneratedScript s = new GeneratedScript("t", "h", "cta", List.of(
                talking(1, 5, "pip",
                        "one two three four five six seven eight nine ten eleven twelve "
                        + "thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty "
                        + "twentyone twentytwo twentythree twentyfour"),
                silentBeat(2)));
        PacingValidator.Result r = validator.validate(s);
        assertTrue(r.failed());
        assertTrue(r.violations().get(0).contains("w/s"), "should flag words-per-second");
    }

    @Test
    void tooManySpeakerChangesFails() {
        GeneratedScript s = new GeneratedScript("t", "h", "cta", List.of(
                scene(1, 6, List.of(
                        new Line("pip", "Hi"), new Line("mo", "Hi"),
                        new Line("bo", "Hi"), new Line("pip", "Hi again")), "Yard."),
                silentBeat(2)));
        PacingValidator.Result r = validator.validate(s);
        assertTrue(r.failed());
        assertTrue(r.violations().get(0).contains("speaker changes"));
    }

    @Test
    void missingSilentBeatFails() {
        GeneratedScript s = new GeneratedScript("t", "h", "cta", List.of(
                talking(1, 5, "pip", "Hello there friends"),
                talking(2, 5, "mo", "Hello to you too")));
        PacingValidator.Result r = validator.validate(s);
        assertTrue(r.failed());
        assertTrue(r.violations().get(0).contains("SILENT VISUAL BEAT"));
    }

    @Test
    void twoSilentBeatsFail() {
        GeneratedScript s = new GeneratedScript("t", "h", "cta", List.of(
                silentBeat(1), silentBeat(2),
                talking(3, 5, "pip", "Hello there friends")));
        assertTrue(validator.validate(s).failed());
    }

    @Test
    void silentBeatWithLazyVisualDescFails() {
        // lines:[] but the visualDesc is scenery, not an acting beat.
        GeneratedScript s = new GeneratedScript("t", "h", "cta", List.of(
                scene(1, 4, List.of(), "A nice farmyard."),
                talking(2, 5, "pip", "Hello there friends")));
        PacingValidator.Result r = validator.validate(s);
        assertTrue(r.failed());
        assertTrue(r.violations().get(0).contains("ACTING beat"));
    }
}
