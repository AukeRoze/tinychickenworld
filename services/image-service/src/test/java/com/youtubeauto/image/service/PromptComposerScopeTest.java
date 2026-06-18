package com.youtubeauto.image.service;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.bible.ChannelBible;
import com.youtubeauto.image.bible.Character;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * EP3 scene-24/25 "trio-telling" paradox guard.
 *
 * <p>Two image-prompt defects, both structural:
 * <ol>
 *   <li>The roster counted a mixed cast as "Exactly 3 chicks total", so the model
 *       turned the duckling into a third chicken. It must count by species:
 *       "Exactly 2 chickens and 1 duckling total (3 characters maximum in frame)".</li>
 *   <li>A character's DNA prose named an ABSENT cast member (the duckling's
 *       "about two-thirds of Bo's height", Pip's "(… and Bo is a vertical line)"),
 *       dragging Bo into a Bo-less scene. {@link PromptComposer#scopeDnaText}
 *       strips comparative fragments that name absent characters.</li>
 * </ol>
 */
class PromptComposerScopeTest {

    // ---- the absent-cast DNA scrubber (the risky regex) -----------------------

    @Test
    void scopeDropsParentheticalNamingAbsentCharacter() {
        String pipBuild = "the SMALLEST and roundest of the trio — a petite, dainty little "
                + "circle of a body, noticeably smaller and finer than Mo (never chubby: small "
                + "and delicate, not plump), with an extra-oversized round head — baby proportions "
                + "that read as instantly adorable (where Mo is base and weight, and Bo is a vertical line)";
        String out = PromptComposer.scopeDnaText(pipBuild, List.of("Bo"));
        assertFalse(out.contains("Bo"), "the absent character Bo must be gone: " + out);
        assertTrue(out.contains("than Mo"), "a PRESENT character (Mo) comparison stays: " + out);
        assertTrue(out.contains("never chubby"), "a Bo-free parenthetical is preserved: " + out);
        // Reduced cast → the count word "trio" is neutralised to "flock".
        assertFalse(out.toLowerCase().contains("trio"), "count word 'trio' must be neutralised: " + out);
        assertTrue(out.contains("of the flock"), "trio → flock expected: " + out);
    }

    @Test
    void scopeNeutralisesCountWordsWhenCastReduced() {
        // No absent NAME in the text, but the cast is reduced (Bo absent) → the
        // count words still mislead, so "trio"/"the three"/"the four" → "the flock".
        assertEquals("the smallest of the flock",
                PromptComposer.scopeDnaText("the smallest of the trio", List.of("Bo")));
        assertEquals("very slightly the largest of the flock",
                PromptComposer.scopeDnaText("very slightly the largest of the three", List.of("Bo")));
        assertEquals("the tiniest of the flock",
                PromptComposer.scopeDnaText("the tiniest of the four", List.of("Bo")));
    }

    @Test
    void scopeDropsClauseNamingAbsentCharacter() {
        String duckBuild = "clearly the smallest of the group — about two-thirds of Bo's height, "
                + "round baby proportions, oversized head, tiny webbed feet";
        String out = PromptComposer.scopeDnaText(duckBuild, List.of("Bo"));
        assertFalse(out.contains("Bo"), "the absent character Bo must be gone: " + out);
        assertTrue(out.contains("round baby proportions") && out.contains("tiny webbed feet"),
                "the rest of the build description survives: " + out);
        assertTrue(out.contains("clearly the smallest of the group"), out);
    }

    @Test
    void scopeLeavesTextWithoutAbsentNameOrCountWordUntouched() {
        String s = "a round yellow puffball with a FLAT WIDE BILL; reads as 'baby duck' at a glance";
        // No absent name AND no count word → returned byte-for-byte unchanged (no churn).
        assertEquals(s, PromptComposer.scopeDnaText(s, List.of("Bo")));
    }

    @Test
    void scopeNoAbsentNamesIsIdentity() {
        String s = "noticeably smaller and finer than Mo";
        assertEquals(s, PromptComposer.scopeDnaText(s, List.of()));
    }

    // ---- species-aware roster count via the real compose path -----------------

    @Test
    void mixedCastCountsBySpeciesAndScrubsAbsentBo() {
        Character.Dna pipDna = dna("cream-white", "tiny round chick", "straw hat", "small plump body");
        Character.Dna moDna  = dna("blue-grey", "broad anvil chick", "red scarf", "sturdy bottom-heavy body");
        Character.Dna boDna  = dna("tan", "thin vertical chick", "round glasses", "slim upright body");
        // The duckling's build names Bo — who is NOT in this scene.
        Character.Dna duckDna = new Character.Dna("lemon-yellow", "round yellow puffball", "none", "",
                "", "soft down",
                "clearly the smallest of the group — about two-thirds of Bo's height, round baby "
                        + "proportions, oversized head, tiny webbed feet",
                "", "dark glossy eyes", "any hat", "none");

        Character pip  = new Character("pip", "Pip", "curious", "PIP", "baby chick", "chicken", "", pipDna);
        Character mo   = new Character("mo", "Mo", "steady", "MO", "baby chick", "chicken", "", moDna);
        Character bo   = new Character("bo", "Bo", "witty", "BO", "baby chick", "chicken", "", boDna);
        Character duck = new Character("duckling", "Duckling", "baby", "DUCK", "newly hatched",
                "duck", "duckling", duckDna);

        ChannelBible bible = new ChannelBible("storybook style", List.of(pip, mo, bo, duck), List.of(), null);
        BibleLoader loader = mock(BibleLoader.class);
        when(loader.getBible()).thenReturn(bible);
        PromptComposer composer = new PromptComposer(loader);

        // Scene 24/25 cast = Pip + Mo + Duckling (NO Bo).
        SceneVisual scene = new SceneVisual(24, "the three look at the new duckling at dusk",
                List.of("pip", "mo", "duckling"), "coop");
        String out = composer.composeReference(scene, List.of("pip", "mo", "duckling"), "landscape");

        assertTrue(out.contains("Exactly 2 chickens and 1 duckling total (3 characters maximum in frame)"),
                "species-aware roster count expected: " + out);
        assertFalse(out.contains("chicks total"),
                "must never lump the duckling in as a chick: " + out);
        assertFalse(out.contains("Bo"),
                "absent Bo must not be named anywhere in the prompt: " + out);
        assertTrue(out.contains("tiny webbed feet"),
                "the duckling's own build detail survives the scrub: " + out);
    }

    // ---- proza → gestructureerde DNA-velden (migratie, pilot Mo) ---------------

    @Test
    void prefersStructuredShapeFieldsOverLegacyProza() {
        // Migrated character: cast-neutral silhouetteShape/bodyBuild present → used;
        // the legacy free-text silhouette/build is ignored (no scrub needed).
        Character.Dna migrated = new Character.Dna(
                "blue-grey", "LEGACY silhouette proza", "a red scarf", "", "", "",
                "LEGACY build proza", "", "", "", "",
                "an ANVIL-shaped chick, broad and bottom-heavy",
                "a solid little body with a broad base, oversized head");
        Character mo = new Character("mo", "Mo", "steady", "MO", "baby chick", "chicken", "", migrated);
        PromptComposer composer = composerWith(mo);
        SceneVisual scene = new SceneVisual(1, "Mo stands by the egg", List.of("mo"), "coop");
        String out = composer.composeReference(scene, List.of("mo"), "landscape");
        assertTrue(out.contains("an ANVIL-shaped chick, broad and bottom-heavy"),
                "structured silhouetteShape must be used: " + out);
        assertTrue(out.contains("a solid little body with a broad base"),
                "structured bodyBuild must be used: " + out);
        assertFalse(out.contains("LEGACY silhouette proza"),
                "legacy silhouette must be ignored when silhouetteShape is set: " + out);
        assertFalse(out.contains("LEGACY build proza"),
                "legacy build must be ignored when bodyBuild is set: " + out);
    }

    @Test
    void fallsBackToLegacyProzaWhenStructuredFieldsAbsent() {
        // Non-migrated character (11-arg DNA, no shape/build fields) → legacy proza.
        Character.Dna legacy = dna("tan", "a slim vertical chick", "round glasses", "thin and upright");
        Character bo = new Character("bo", "Bo", "witty", "BO", "baby chick", "chicken", "", legacy);
        PromptComposer composer = composerWith(bo);
        SceneVisual scene = new SceneVisual(1, "Bo stands by the egg", List.of("bo"), "coop");
        String out = composer.composeReference(scene, List.of("bo"), "landscape");
        assertTrue(out.contains("a slim vertical chick"), "legacy silhouette still used: " + out);
        assertTrue(out.contains("thin and upright"), "legacy build still used: " + out);
    }

    @Test
    void neutralisesFlockCountWordsForReducedCast() {
        // GUARD-PARITY: keep IDENTICAL to orchestrator
        // VeoPromptCompilerLeanTest.neutralisesFlockCountWordsForReducedCast.
        assertEquals("the smallest chick of the flock",
                PromptComposer.neutraliseCountWords("the smallest chick of the trio"));
        assertEquals("slightly the largest of the flock",
                PromptComposer.neutraliseCountWords("slightly the largest of the three"));
        assertEquals("the tiniest of the flock",
                PromptComposer.neutraliseCountWords("the tiniest of the four"));
    }

    @Test
    void guardsAreNoOpOnMigratedCastNeutralText() {
        // Contract (proza→velden migratie, stap 4): a migrated silhouetteShape/
        // bodyBuild is cast-neutral, so the scrub guard must leave it BYTE-FOR-BYTE
        // unchanged even with the whole cast passed as "absent". If this ever
        // fails, someone put another character's name or a flock count
        // ("trio"/"three"/"four") back into a structured field.
        String[] migrated = {
            "an ANVIL-shaped chick, broad and bottom-heavy, with an oversized head",
            "a petite, dainty little circle of a body, never chubby, all soft curves",
            "a slim, VERTICAL, springy little body stretched upward, a tall thin small chick",
            "a round lemon-yellow puffball with a FLAT WIDE BILL and one upright head-tuft"
        };
        java.util.List<String> wholeCast = List.of("Pip", "Mo", "Bo", "Duckling");
        for (String field : migrated) {
            assertEquals(field, PromptComposer.scopeDnaText(field, wholeCast),
                    "scrub must be a no-op on cast-neutral text: " + field);
        }
    }

    private PromptComposer composerWith(Character character) {
        ChannelBible bible = new ChannelBible("storybook style", List.of(character), List.of(), null);
        BibleLoader loader = mock(BibleLoader.class);
        when(loader.getBible()).thenReturn(bible);
        return new PromptComposer(loader);
    }

    private static Character.Dna dna(String color, String silhouette, String accessory, String build) {
        return new Character.Dna(color, silhouette, accessory, "", "", "", build, "", "", "", "");
    }
}
