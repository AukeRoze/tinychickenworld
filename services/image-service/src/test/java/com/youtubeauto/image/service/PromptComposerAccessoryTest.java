package com.youtubeauto.image.service;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.bible.ChannelBible;
import com.youtubeauto.image.bible.Character;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Locks the image side of the EP3 scene-5 fix: an action that hands Bo's glasses
 * to Mo is rewritten to Mo's own scarf BEFORE it reaches the SCENE line, so the
 * action stops fighting the CHARACTER DNA "Mo must NEVER wear glasses" lock in
 * the very same prompt.
 */
class PromptComposerAccessoryTest {

    private PromptComposer composerWith(Character... cast) {
        ChannelBible bible = new ChannelBible(
                "soft 3D cartoon style", List.of(cast), List.of(), null);
        BibleLoader loader = mock(BibleLoader.class);
        when(loader.getBible()).thenReturn(bible);
        return new PromptComposer(loader);
    }

    private static Character mo() {
        Character.Dna dna = new Character.Dna(
                "blue-grey", "anvil chick", "a red knitted neck scarf",
                "tugs his scarf", "low hum", "downy", "broad body", "heavy",
                "warm-brown eyes",
                "any bandana, any eyeglasses or glasses, any hat",  // antiAccessory
                "thick red knitted scarf");                          // signatureAccessoryShort
        return new Character("mo", "Mo", "the steady chick", "MOTOKEN", "baby chick", dna);
    }

    private static Character bo() {
        Character.Dna dna = new Character.Dna(
                "tan", "upright chick", "round thin-framed eyeglasses and a green neck scarf",
                "pushes glasses up", "hiccup", "spiky", "slim body", "light",
                "amber eyes",
                "any hat, any bandana, any red scarf",               // antiAccessory
                "round thin-framed eyeglasses");                      // signatureAccessoryShort
        return new Character("bo", "Bo", "the witty chick", "BOTOKEN", "baby chick", dna);
    }

    @Test
    void rewritesMoGlassesActionToHisScarf() {
        PromptComposer composer = composerWith(mo(), bo());
        SceneVisual scene = new SceneVisual(5,
                "Mo waddles into frame from the right, adjusting his glasses with a wingtip, "
                        + "peering down at the pale-cream egg.",
                List.of("mo"), "");

        String prompt = composer.composeReference(scene, List.of("mo"), "landscape");

        assertTrue(prompt.contains("adjusting his thick red knitted scarf"),
                "Mo's action must reference his own scarf: " + prompt);
        // The DNA never-wear lock still says "glasses" (that is correct), but the
        // SCENE action line must not attribute glasses TO Mo any more. Assert the
        // exact defect string is gone.
        assertFalse(prompt.contains("adjusting his glasses"),
                "the glasses action must be gone from the SCENE line");
    }
}
