package com.youtubeauto.image.service;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.bible.ChannelBible;
import com.youtubeauto.image.bible.Character;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P7 follow-up — DNA lock-step contract guard.
 *
 * <p>Character identity is supposed to come from a SINGLE source: the bible's
 * {@code characters[].dna} block. Two prompt builders consume it and must stay
 * in sync: the image side ({@link PromptComposer#composeReference} via
 * {@code dnaLine}) and the Veo side (orchestrator {@code dnaIdentityClause}).
 * They live in separate Maven modules, so this test locks the image side —
 * asserting every canonical DNA field surfaces in the composed prompt — so a
 * future edit that silently drops a field (the exact way identity drift creeps
 * back in) fails the build. The Veo side reads the same fields; keep both
 * lists identical when you add a DNA field.
 *
 * <p>Note: {@code weight} is deliberately NOT asserted — it is a motion cue
 * reserved for the Veo compiler and intentionally omitted from the still image.
 */
class PromptComposerDnaTest {

    private PromptComposer composerWith(Character character) {
        ChannelBible bible = new ChannelBible(
                "soft watercolour storybook style",   // visualStyle (must be non-null)
                List.of(character),
                List.of(),                            // no locations needed
                null);                                // imageGen unused by composeReference
        BibleLoader loader = mock(BibleLoader.class);
        when(loader.getBible()).thenReturn(bible);
        return new PromptComposer(loader);
    }

    private static Character pipWithFullDna() {
        Character.Dna dna = new Character.Dna(
                "cream-white",            // coreColor
                "tiny round chick",       // silhouette
                "a yellow straw hat",     // accessory
                "tips her hat back",      // tic
                "soft peep",              // signatureSound (audio layer)
                "downy ruffled feathers", // feathers
                "small plump body",       // build
                "feather-light, bouncy",  // weight (Veo-only — not on the still)
                "big black shiny eyes",   // eyeColor
                "glasses or a scarf");    // antiAccessory (never-wear lock)
        return new Character("pip", "Pip", "the curious chick", "PIPTOKEN", "baby chick", dna);
    }

    @Test
    void composeReference_emitsEveryCanonicalDnaField() {
        PromptComposer composer = composerWith(pipWithFullDna());
        SceneVisual scene = new SceneVisual(1, "Pip looks around the coop", List.of("pip"), "");

        String prompt = composer.composeReference(scene, List.of("pip"), "landscape");

        assertTrue(prompt.contains("Pip"), "character name must appear");
        assertTrue(prompt.contains("cream-white"), "coreColor must appear");
        assertTrue(prompt.contains("a yellow straw hat"), "accessory must appear");
        assertTrue(prompt.contains("tiny round chick"), "silhouette must appear");
        assertTrue(prompt.contains("downy ruffled feathers"), "feathers must appear");
        assertTrue(prompt.contains("small plump body"), "build must appear");
        assertTrue(prompt.contains("big black shiny eyes"), "eyeColor must appear");
        assertTrue(prompt.contains("tips her hat back"), "signature tic must appear");
        assertTrue(prompt.contains("glasses or a scarf"), "antiAccessory never-wear lock must appear");
    }

    @Test
    void composeReference_emptyDna_doesNotThrow() {
        Character bare = new Character("mo", "Mo", "calm chick", "MOTOKEN", "baby chick",
                Character.Dna.empty());
        PromptComposer composer = composerWith(bare);
        SceneVisual scene = new SceneVisual(1, "Mo takes a nap", List.of("mo"), "");

        String prompt = composer.composeReference(scene, List.of("mo"), "landscape");

        // Empty DNA → dnaLine returns "" → no DNA block, but the scene is still composed.
        assertTrue(prompt.contains("Mo takes a nap"), "scene action must still be present");
    }
}
