package com.youtubeauto.video.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the bible-driven transition styling: valid entries override, typos are
 * rejected (fallback to built-ins) and durations are clamped — a YAML slip
 * must never break a render.
 */
class TransitionConfigTest {

    @TempDir
    Path tmp;

    private TransitionConfig configFor(String yaml) throws Exception {
        Path bible = tmp.resolve("channel.yml");
        Files.writeString(bible, yaml);
        TransitionConfig c = new TransitionConfig();
        ReflectionTestUtils.setField(c, "biblePath", bible.toString());
        return c;
    }

    @Test
    void validEntryIsUsed() throws Exception {
        TransitionConfig c = configFor("""
                assembly:
                  transitions:
                    climax: { type: fadewhite, seconds: 0.35 }
                """);
        var spec = c.forPhase("climax");
        assertTrue(spec.isPresent());
        assertEquals("fadewhite", spec.get().type());
        assertEquals(0.35, spec.get().seconds(), 1e-9);
    }

    @Test
    void unknownPhaseFallsBackToDefaultEntry() throws Exception {
        TransitionConfig c = configFor("""
                assembly:
                  transitions:
                    default: { type: dissolve, seconds: 0.2 }
                """);
        var spec = c.forPhase("development");
        assertTrue(spec.isPresent());
        assertEquals("dissolve", spec.get().type());
    }

    @Test
    void typoTypeIsRejected() throws Exception {
        TransitionConfig c = configFor("""
                assembly:
                  transitions:
                    closer: { type: circelclose, seconds: 0.45 }
                """);
        assertTrue(c.forPhase("closer").isEmpty(),
                "invalid xfade type must be rejected so the built-in default applies");
    }

    @Test
    void secondsAreClamped() throws Exception {
        TransitionConfig c = configFor("""
                assembly:
                  transitions:
                    closer: { type: circleclose, seconds: 9.0 }
                """);
        assertEquals(1.50, c.forPhase("closer").orElseThrow().seconds(), 1e-9);
    }

    @Test
    void missingFileMeansEmpty() {
        TransitionConfig c = new TransitionConfig();
        ReflectionTestUtils.setField(c, "biblePath", tmp.resolve("nope.yml").toString());
        assertTrue(c.forPhase("hook").isEmpty());
    }

    @Test
    void missingSectionMeansEmpty() throws Exception {
        TransitionConfig c = configFor("channel:\n  name: test\n");
        assertTrue(c.forPhase("hook").isEmpty());
    }
}
