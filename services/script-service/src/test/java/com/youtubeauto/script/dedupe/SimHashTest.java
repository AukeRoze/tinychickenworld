package com.youtubeauto.script.dedupe;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SimHashTest {

    @Test
    void identicalTexts_haveZeroDistance() {
        long h1 = SimHash.compute("Pip notices something tiny and shiny.");
        long h2 = SimHash.compute("Pip notices something tiny and shiny.");
        assertEquals(0, SimHash.hamming(h1, h2));
    }

    @Test
    void verySimilarTexts_haveLowDistance() {
        long h1 = SimHash.compute("Pip notices something tiny and shiny on the floor.");
        long h2 = SimHash.compute("Pip notices something small and shiny on the floor.");
        int d = SimHash.hamming(h1, h2);
        assertTrue(d < 16, "Similar texts should be close, distance=" + d);
    }

    @Test
    void totallyDifferentTexts_haveHighDistance() {
        long h1 = SimHash.compute("Pip wakes up early in the cozy coop.");
        long h2 = SimHash.compute("Bo dramatically misses a step on the staircase.");
        int d = SimHash.hamming(h1, h2);
        assertTrue(d > 16, "Different texts should be far, distance=" + d);
    }

    @Test
    void emptyAndBlankText_doesNotThrow() {
        long h1 = SimHash.compute("");
        long h2 = SimHash.compute("   ");
        // Should not throw — both are valid degenerate inputs
        SimHash.hamming(h1, h2);
    }

    @Test
    void hashIsStableAcrossCalls() {
        String text = "Mo carefully observes a butterfly landing on his glasses.";
        assertEquals(SimHash.compute(text), SimHash.compute(text),
                "Hash should be deterministic");
    }
}
