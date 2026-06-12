package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.anthropic.GeneratedScript.Scene;
import com.youtubeauto.script.bible.EpisodePhase;
import com.youtubeauto.script.bible.EpisodeStructure;
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
 * The duration + location + cast rules are exercised with es = null; the
 * phase-ORDER rule (phases must follow the bible's declaration order, no
 * returning to an earlier phase) gets its own minimal EpisodeStructure below.
 */
class StructureValidatorTest {

    private final StructureValidator validator = new StructureValidator();

    private static Scene scene(int seq, int dur, String loc, List<String> chars) {
        return scene(seq, dur, loc, "development", chars);
    }

    private static Scene scene(int seq, int dur, String loc, String phase, List<String> chars) {
        return new Scene(seq, List.of(), "Pip does something interesting in great detail here.",
                chars, loc, phase, "midday", "clear", "goal",
                "joy (3/5)", "natural", "", "", dur);
    }

    private static GeneratedScript script(Scene... scenes) {
        return new GeneratedScript("t", "h", "cta", List.of(scenes));
    }

    /** Minimal bible structure: hook → development → climax → closer.
     *  minScenes 0 and seconds 0 keep the count/duration checks quiet so the
     *  order tests below pin exactly ONE rule. */
    private static EpisodeStructure structure() {
        return new EpisodeStructure(120, List.of(
                phase("hook"), phase("development"), phase("climax"), phase("closer")),
                12, 1, 99);
    }

    private static EpisodePhase phase(String id) {
        return new EpisodePhase(id, id.toUpperCase(), 0, 0, 2, "standard", List.of());
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

    @Test
    void phasesInBibleOrderPass() {
        // hook → development → climax → closer, exactly the declared order.
        GeneratedScript s = script(
                scene(1, 30, "yard", "hook", List.of("pip")),
                scene(2, 30, "pond", "development", List.of("pip")),
                scene(3, 30, "barn", "climax", List.of("pip")),
                scene(4, 30, "yard", "closer", List.of("pip")));
        List<String> v = validator.validate(s, structure(), 120);
        assertTrue(v.isEmpty(), "valid phase order must pass, got: " + v);
    }

    @Test
    void climaxBeforeDevelopmentFails() {
        // Counts/durations all fine and the last scene IS the closer — only
        // the ORDER is broken. This used to pass.
        GeneratedScript s = script(
                scene(1, 30, "yard", "hook", List.of("pip")),
                scene(2, 30, "pond", "climax", List.of("pip")),
                scene(3, 30, "barn", "development", List.of("pip")),
                scene(4, 30, "yard", "closer", List.of("pip")));
        List<String> v = validator.validate(s, structure(), 120);
        assertTrue(v.contains(
                "Phase order violated: scene 3 ('development') appears after phase 'climax' started."),
                "climax before development must trip the order check, got: " + v);
    }

    @Test
    void returningPhaseFails() {
        // hook → development → hook: a phase may not come back once a later
        // phase has started.
        GeneratedScript s = script(
                scene(1, 30, "yard", "hook", List.of("pip")),
                scene(2, 30, "pond", "development", List.of("pip")),
                scene(3, 30, "barn", "hook", List.of("pip")),
                scene(4, 30, "yard", "closer", List.of("pip")));
        List<String> v = validator.validate(s, structure(), 120);
        assertTrue(v.contains(
                "Phase order violated: scene 3 ('hook') appears after phase 'development' started."),
                "returning hook must trip the order check, got: " + v);
    }

    @Test
    void unknownPhaseIsFlaggedButSkippedByOrderCheck() {
        // The unknown-phase check (check 3) owns this failure; the order
        // check must skip the scene, not crash or double-report.
        GeneratedScript s = script(
                scene(1, 30, "yard", "hook", List.of("pip")),
                scene(2, 30, "pond", "freestyle", List.of("pip")),
                scene(3, 30, "barn", "closer", List.of("pip")));
        List<String> v = validator.validate(s, structure(), 90);
        assertTrue(v.stream().anyMatch(x -> x.contains("unknown phase 'freestyle'")),
                "unknown phase must still be flagged, got: " + v);
        assertTrue(v.stream().noneMatch(x -> x.contains("Phase order violated")),
                "order check must skip unknown phases, got: " + v);
    }
}
