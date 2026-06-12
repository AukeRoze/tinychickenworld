package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.domain.SceneDto;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for the Episode-ConsistencyState anchor election
 * ({@link PropAnchorService#selectEpisodeAnchors}) — no Spring, no Mockito,
 * no disk I/O (file existence is an injected predicate).
 *
 * The heuristic under test, in order:
 *   1. fewest characters in the scene (least occlusion) wins;
 *   2. hero phase (hook/climax) wins a cast-size tie;
 *   3. lowest seq wins a remaining tie (determinism).
 * Scenes without an image, or whose image the predicate rejects, never anchor.
 */
class EpisodeAnchorSelectionTest {

    private static SceneDto scene(int seq, String phase, String imagePath, String... chars) {
        SceneDto s = new SceneDto();
        s.setSeq(seq);
        s.setPhase(phase);
        s.setImagePath(imagePath);
        s.setCharacters(List.of(chars));
        return s;
    }

    @Test
    void leastOccludedSceneWins_heroPhaseBreaksTies_deterministically() {
        List<SceneDto> scenes = List.of(
                scene(1, "hook",        "s1.png", "pip"),               // pip solo + hero
                scene(2, "setup",       "s2.png", "pip", "mo"),         // duo
                scene(3, "development", "s3.png", "mo"),                // mo solo, NOT hero
                scene(4, "climax",      "s4.png", "pip", "mo", "bo"),   // trio (bo's only scene)
                scene(5, "climax",      "s5.png", "mo"),                // mo solo + hero → beats s3
                scene(6, "hook",        "s6.png", "mo"));               // mo solo + hero, later seq

        Map<String, String> anchors =
                PropAnchorService.selectEpisodeAnchors(scenes, p -> true);

        // pip: solo hook beat is the least-occluded option.
        assertEquals("s1.png", anchors.get("pip"));
        // mo: three solo candidates — hero (climax/hook) beats the non-hero
        // development beat, and the LOWER seq (5) wins the hero-vs-hero tie.
        assertEquals("s5.png", anchors.get("mo"));
        // bo: only appears in the trio scene → that's the anchor (better than none).
        assertEquals("s4.png", anchors.get("bo"));
        assertEquals(3, anchors.size());
    }

    @Test
    void missingImagesAndUnusableScenes_degradeSilently() {
        List<SceneDto> scenes = List.of(
                scene(1, "hook",  "gone.png", "pip"),          // file rejected by predicate
                scene(2, "setup", "ok.png", "pip", "mo"),      // only usable still
                scene(3, "setup", null, "mo"),                 // no image at all → skipped
                scene(4, "setup", "ok2.png"));                 // image but NO cast → skipped

        // Only "ok.png" exists → both characters fall back to the duo scene.
        Map<String, String> some = PropAnchorService.selectEpisodeAnchors(
                scenes, "ok.png"::equals);
        assertEquals(Map.of("pip", "ok.png", "mo", "ok.png"), some);

        // Nothing on disk (the state-machine test's situation: mocked image
        // paths that don't exist) → EMPTY canon, callers keep legacy behaviour.
        assertTrue(PropAnchorService.selectEpisodeAnchors(scenes, p -> false).isEmpty());
        // Null/empty inputs are equally silent.
        assertTrue(PropAnchorService.selectEpisodeAnchors(null, p -> true).isEmpty());
        assertTrue(PropAnchorService.selectEpisodeAnchors(List.of(), p -> true).isEmpty());
        // A throwing existence check disqualifies the still instead of propagating.
        assertTrue(PropAnchorService.selectEpisodeAnchors(
                scenes, p -> { throw new RuntimeException("disk error"); }).isEmpty());
    }
}
