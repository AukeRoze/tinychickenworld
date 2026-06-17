package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit tests for {@link OnomatopoeiaGuard} — the EP3 scene-8 "Bo says
 * 'Bonk!'" defect (an impact foley sound coded as a spoken, lip-synced line).
 */
class OnomatopoeiaGuardTest {

    @Test
    void detectsPureImpactSounds() {
        assertTrue(OnomatopoeiaGuard.isPureImpactSfx("Bonk!"));
        assertTrue(OnomatopoeiaGuard.isPureImpactSfx("bonk bonk"));
        assertTrue(OnomatopoeiaGuard.isPureImpactSfx("plop..."));
    }

    @Test
    void keepsRealSpeechAndMotionExclamations() {
        assertFalse(OnomatopoeiaGuard.isPureImpactSfx("Bonk, wait—"), "has a real word");
        assertFalse(OnomatopoeiaGuard.isPureImpactSfx("I'll keep it warm! Like a real hen!"));
        assertFalse(OnomatopoeiaGuard.isPureImpactSfx("Wheee!"), "motion vocalisation stays speech");
        assertFalse(OnomatopoeiaGuard.isPureImpactSfx("Whoosh!"), "not an impact sound");
        assertFalse(OnomatopoeiaGuard.isPureImpactSfx(""));
        assertFalse(OnomatopoeiaGuard.isPureImpactSfx(null));
    }

    @Test
    void splitsBonkOutOfSceneEightDialogue() {
        List<Map<String, Object>> lines = List.of(
                Map.of("speaker", "bo", "text", "I'll keep it warm! Like a real hen!"),
                Map.of("speaker", "pip", "text", "Bo, wait—"),
                Map.of("speaker", "bo", "text", "Bonk!"));

        List<Map<String, Object>> spoken = OnomatopoeiaGuard.spokenLines(lines);
        assertEquals(2, spoken.size(), "the 'Bonk!' line is removed from speech");
        assertTrue(spoken.stream().noneMatch(l -> "Bonk!".equals(l.get("text"))));

        String cue = OnomatopoeiaGuard.impactSfxCue(lines);
        assertTrue(cue.contains("\"bonk\""), "the impact sound is surfaced as foley: " + cue);
        assertTrue(cue.toLowerCase().contains("not spoken")
                || cue.toLowerCase().contains("not spoken or lip-synced"), cue);
    }

    @Test
    void cleanDialogueIsUnchanged() {
        List<Map<String, Object>> lines = List.of(
                Map.of("speaker", "pip", "text", "Look, an egg!"),
                Map.of("speaker", "mo", "text", "It feels cold."));
        assertEquals(2, OnomatopoeiaGuard.spokenLines(lines).size());
        assertEquals("", OnomatopoeiaGuard.impactSfxCue(lines));
    }
}
