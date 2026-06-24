package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit test for {@link VeoPromptCompiler#augmentPresentCast} — the cast-recovery
 * logic that keeps a present character (notably the just-hatched duckling) in the
 * roster even when the script left it out of the scene cast. Covers the EP3
 * sc.19/21/24 defects: a visible/speaking duckling that was missing from the cast
 * so the roster counted only chickens.
 *
 * <p>Tests the pure, dependency-free overload so no bible I/O is needed.
 */
class VeoPromptCompilerCastRecoveryTest {

    /** pip/mo/bo are chickens; the duckling is a non-chicken guest. */
    private static final Map<String, String> NOUNS = Map.of(
            "pip", "chicken",
            "mo", "chicken",
            "bo", "chicken",
            "duckling", "duckling");

    /** Match tokens per character (id + name + roster noun, lowercased). */
    private static final Map<String, List<String>> TOKENS = Map.of(
            "pip", List.of("pip"),
            "mo", List.of("mo"),
            "bo", List.of("bo"),
            "duckling", List.of("duckling"));

    private static Map<String, Object> line(String speaker) {
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("speaker", speaker);
        m.put("text", "...");
        return m;
    }

    private static List<String> augment(List<String> cast, String text, List<Map<String, Object>> lines) {
        return VeoPromptCompiler.augmentPresentCast(cast, text, lines, NOUNS, TOKENS);
    }

    @Test
    void speakingDucklingIsRecovered() {
        // sc.24: cast = Pip + Mo, but the duckling SPEAKS and is missing from the cast.
        List<String> out = augment(
                List.of("pip", "mo"),
                "Mo sits beside the straw nest; the tiny lemon-yellow duckling is curled up asleep.",
                List.of(line("mo"), line("duckling")));
        assertTrue(out.contains("duckling"), "speaking duckling must be recovered");
        assertEquals(List.of("pip", "mo", "duckling"), out, "declared cast first, guest appended");
    }

    @Test
    void visibleSilentDucklingIsRecoveredFromText() {
        // sc.21: duckling is visible in the nest but silent and not in the cast.
        List<String> out = augment(
                List.of("pip", "bo"),
                "Bo tumbles backward; the straw nest with the duckling is visible between them.",
                List.of(line("bo"), line("pip")));
        assertTrue(out.contains("duckling"), "visible duckling named in the action must be recovered");
        assertEquals(List.of("pip", "bo", "duckling"), out);
    }

    @Test
    void chickenOnlySceneIsUnchanged() {
        // sc.1: solo Pip, no duckling anywhere — roster must stay byte-identical.
        List<String> out = augment(
                List.of("pip"),
                "Extreme close-up of a pale-cream egg in dark soil; Pip's round fluffy head slides up.",
                List.of(line("pip")));
        assertEquals(List.of("pip"), out, "chicken-only scene must not gain any character");
    }

    @Test
    void chickenNamedInTextIsNotAdded() {
        // A chicken merely NAMED in the action (but not declared/speaking) must NOT be
        // pulled in — text-name recovery is restricted to non-chicken guests, so the
        // (already-correct) chicken rosters never change.
        List<String> out = augment(
                List.of("pip"),
                "Pip points off-frame and calls: Mo! Come see!",
                List.of(line("pip")));
        assertEquals(List.of("pip"), out, "a named-but-absent chicken must not be added");
    }

    @Test
    void wordBoundaryAvoidsFalsePositive() {
        // "duck" as part of another word must not trigger the duckling.
        List<String> out = augment(
                List.of("pip"),
                "Pip has to duck under a low sunflower stem.",
                List.of(line("pip")));
        assertEquals(List.of("pip"), out, "substring 'duck' must not match the 'duckling' token");
    }

    @Test
    void alreadyPresentDucklingIsNotDuplicated() {
        // sc.20/22/23/25: duckling already in the cast — order and contents unchanged.
        List<String> out = augment(
                List.of("pip", "duckling"),
                "Close-up of the duckling looking up at Pip.",
                List.of(line("duckling"), line("pip")));
        assertEquals(List.of("pip", "duckling"), out);
    }
}
