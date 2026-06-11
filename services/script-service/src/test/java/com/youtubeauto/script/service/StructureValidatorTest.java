package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.anthropic.GeneratedScript.Scene;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Guards the duration discipline tightened after episode 2 (90s target →
 * 124s master): scripted total may drift at most ±10%, because assembly's
 * voice-stretching adds another ~10% on top. A silent revert of that
 * constant would reintroduce 30%-too-long masters — exactly the kind of
 * regression nobody notices until a finished render.
 *
 * EpisodeStructure checks are exercised with es = null (they have their own
 * bible-coupled config); this test pins the duration + location + cast rules.
 */
class StructureValidatorTest {

    private final StructureValidator validator = new StructureValidator();

    private static Scene scene(int seq, int dur, String loc, List<String> chars) {
        return new Scene(seq, List.of(), "Pip does something interesting in great detail here.",
                chars, loc, "development", "midday", "clear", "goal",
                "joy (3/5)", "natural", "", "", dur);
    }

    private static GeneratedScript script(Scene... scenes) {
        return new GeneratedScript("t", "h", "cta", List.of(scenes));
    }

    @Test
    void totalWithinTenPercentPasses() {
        // 96s on a 90s target = +6.7% — must pass.
        GeneratedScript s = script(
                scene(1, 32, "yard", List.of("pip")),
                scene(2, 32, "pond", List.of("pip")),
                scene(3, 32, "barn", List.of("pip")));
        assertTrue(validator.validate(s, null, 90).isEmpty());
    }

    @Test
    void totalOverTenPercentFails() {
        // 108s on a 90s target = +20% — passed under the old ±20% rule,
        // must FAIL now (this is the ep-2 regression guard).
        GeneratedScript s = script(
                scene(1, 36, "yard", List.of("pip")),
                scene(2, 36, "pond", List.of("pip")),
                scene(3, 36, "barn", List.of("pip")));
        List<String> v = validator.validate(s, null, 90);
        assertTrue(v.stream().anyMatch(x -> x.contains("Total duration")),
                "20% drift must be rejected, got: " + v);
    }

    @Test
    void outOfOrderSeqFails() {
        GeneratedScript s = script(
                scene(1, 30, "yard", List.of("pip")),
                scene(3, 30, "pond", List.of("pip")),
                scene(2, 30, "barn", List.of("pip")));
        assertTrue(validator.validate(s, null, 90).stream()
                .anyMatch(x -> x.contains("seq out of order")));
    }

    @Test
    void singleLocationEverywhereFails() {
        GeneratedScript s = script(
                scene(1, 15, "yard", List.of("pip")), scene(2, 15, "yard", List.of("pip")),
                scene(3, 15, "yard", List.of("pip")), scene(4, 15, "yard", List.of("pip")),
                scene(5, 15, "yard", List.of("pip")), scene(6, 15, "yard", List.of("pip")));
        assertTrue(validator.validate(s, null, 90).stream()
                .anyMatch(x -> x.contains("location")));
    }

    @Test
    void threeCharactersInNormalSceneFails() {
        // A "development" beat staging the full trio is where image-to-video
        // models hallucinate extras / swap accessories — capped at a two-shot.
        GeneratedScript s = script(
                scene(1, 30, "yard", List.of("pip")),
                scene(2, 30, "pond", List.of("pip", "mo", "bo")),
                scene(3, 30, "barn", List.of("pip")));
        assertTrue(validator.validate(s, null, 90).stream()
                .anyMatch(x -> x.contains("stages 3 characters")),
                "3 characters in a normal scene must be rejected");
    }

    @Test
    void threeCharactersInFirstOrLastScenePasses() {
        // The intro/outro flock wave may stage all three.
        GeneratedScript s = script(
                scene(1, 32, "yard", List.of("pip", "mo", "bo")),
                scene(2, 32, "pond", List.of("pip")),
                scene(3, 32, "barn", List.of("pip", "mo", "bo")));
        assertTrue(validator.validate(s, null, 90).stream()
                .noneMatch(x -> x.contains("stages")),
                "trio in first/last scene must be allowed");
    }

    @Test
    void castFlickerFails() {
        // Mo present in 1 and 3 but gone in 2 — the jarring cast-bounce.
        GeneratedScript s = script(
                scene(1, 30, "yard", List.of("pip", "mo")),
                scene(2, 30, "pond", List.of("pip")),
                scene(3, 30, "barn", List.of("pip", "mo")));
        assertTrue(validator.validate(s, null, 90).stream()
                .anyMatch(x -> x.contains("flickers")));
    }
}
