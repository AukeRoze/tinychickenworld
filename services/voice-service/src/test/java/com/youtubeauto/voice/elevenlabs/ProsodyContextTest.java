package com.youtubeauto.voice.elevenlabs;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests the pure selection logic behind the ElevenLabs previous_text/next_text
 * prosody context: same-speaker only, window = same scene + adjacent scene,
 * 300-char cap per side, null when there is no candidate (the client then
 * omits the field instead of sending an empty string).
 */
class ProsodyContextTest {

    private static ProsodyContext.Entry e(int scene, String speaker, String text) {
        return new ProsodyContext.Entry(scene, speaker, text);
    }

    // --- same-speaker selection -------------------------------------------

    @Test
    void picksNearestSameSpeakerLineSkippingOtherSpeakers() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "First pip line."),
                e(1, "mo", "Mo interjects."),
                e(1, "pip", "Current pip line."),
                e(1, "bo", "Bo replies."),
                e(1, "pip", "Last pip line."));
        var ctx = ProsodyContext.contextFor(lines, 2);
        assertEquals("First pip line.", ctx.previousText());
        assertEquals("Last pip line.", ctx.nextText());
    }

    @Test
    void speakerMatchIsCaseInsensitive() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "Pip", "Earlier."),
                e(1, "pip", "Current."));
        assertEquals("Earlier.", ProsodyContext.previousFor(lines, 1));
    }

    @Test
    void otherSpeakerIsNeverUsedAsContext() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "mo", "Mo before."),
                e(1, "pip", "Current."),
                e(1, "mo", "Mo after."));
        var ctx = ProsodyContext.contextFor(lines, 1);
        assertNull(ctx.previousText());
        assertNull(ctx.nextText());
    }

    // --- window: same scene + adjacent scene ------------------------------

    @Test
    void crossesSceneBoundaryToAdjacentScene() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "Pip in scene one."),
                e(2, "mo", "Mo opens scene two."),
                e(2, "pip", "Current, scene two."),
                e(3, "pip", "Pip in scene three."));
        var ctx = ProsodyContext.contextFor(lines, 2);
        assertEquals("Pip in scene one.", ctx.previousText());
        assertEquals("Pip in scene three.", ctx.nextText());
    }

    @Test
    void lineTwoScenesAwayIsOutsideTheWindow() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "Pip in scene one."),
                e(2, "mo", "Only mo in scene two."),
                e(3, "pip", "Current, scene three."),
                e(4, "bo", "Only bo in scene four."),
                e(5, "pip", "Pip in scene five."));
        var ctx = ProsodyContext.contextFor(lines, 2);
        assertNull(ctx.previousText(), "scene 1 is two scenes back -> outside window");
        assertNull(ctx.nextText(), "scene 5 is two scenes ahead -> outside window");
    }

    @Test
    void nearerSameSceneLineWinsOverAdjacentSceneLine() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "Adjacent-scene pip."),
                e(2, "pip", "Same-scene pip."),
                e(2, "pip", "Current."));
        assertEquals("Same-scene pip.", ProsodyContext.previousFor(lines, 2));
    }

    // --- 300-char cap ------------------------------------------------------

    @Test
    void previousTextIsCappedKeepingTheTail() {
        String longText = "A".repeat(200) + "B".repeat(200);   // 400 chars
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", longText),
                e(1, "pip", "Current."));
        String prev = ProsodyContext.previousFor(lines, 1);
        assertNotNull(prev);
        assertEquals(ProsodyContext.MAX_CHARS, prev.length());
        assertTrue(prev.endsWith("B"), "previous_text keeps the tail (words leading into the line)");
        assertEquals(longText.substring(400 - ProsodyContext.MAX_CHARS), prev);
    }

    @Test
    void nextTextIsCappedKeepingTheHead() {
        String longText = "A".repeat(200) + "B".repeat(200);   // 400 chars
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "Current."),
                e(1, "pip", longText));
        String next = ProsodyContext.nextFor(lines, 0);
        assertNotNull(next);
        assertEquals(ProsodyContext.MAX_CHARS, next.length());
        assertTrue(next.startsWith("A"), "next_text keeps the head (words following the line)");
        assertEquals(longText.substring(0, ProsodyContext.MAX_CHARS), next);
    }

    @Test
    void exactly300CharsIsNotTruncated() {
        String text = "C".repeat(ProsodyContext.MAX_CHARS);
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", text),
                e(1, "pip", "Current."));
        assertEquals(text, ProsodyContext.previousFor(lines, 1));
    }

    // --- no candidate -> null ---------------------------------------------

    @Test
    void firstAndLastLineOfEpisodeHaveNoContext() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "Only line."));
        var ctx = ProsodyContext.contextFor(lines, 0);
        assertNull(ctx.previousText());
        assertNull(ctx.nextText());
    }

    @Test
    void blankCandidateTextIsSkipped() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "   "),
                e(1, "pip", "Current."));
        assertNull(ProsodyContext.previousFor(lines, 1));
    }

    @Test
    void nullSpeakerOnCurrentLineYieldsNoContext() {
        List<ProsodyContext.Entry> lines = List.of(
                e(1, "pip", "Before."),
                e(1, null, "Current."));
        var ctx = ProsodyContext.contextFor(lines, 1);
        assertNull(ctx.previousText());
        assertNull(ctx.nextText());
    }

    @Test
    void outOfRangeIndexYieldsNoContext() {
        List<ProsodyContext.Entry> lines = List.of(e(1, "pip", "x"));
        assertNull(ProsodyContext.previousFor(lines, 5));
        assertNull(ProsodyContext.nextFor(lines, -1));
        assertNull(ProsodyContext.previousFor(null, 0));
    }
}
