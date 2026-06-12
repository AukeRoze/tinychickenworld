package com.youtubeauto.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Pins the Story-I phase/retention coupling: retentionScenesJson payloads
 * (as RetentionMapper writes them) aggregated per story phase, plus the
 * ≥3-videos threshold that keeps the hint silent on thin data.
 * Pure functions — no Spring context needed.
 */
class PhaseRetentionTest {

    // Payloads in the exact shape RetentionMapper persists.
    private static final String VIDEO_A = """
            [{"seq":1,"phase":"hook","startSec":0.0,"endSec":5.0,"avgWatchRatio":0.95,"drop":0.02},
             {"seq":2,"phase":"development","startSec":5.0,"endSec":12.0,"avgWatchRatio":0.80,"drop":0.10},
             {"seq":3,"phase":"development","startSec":12.0,"endSec":18.0,"avgWatchRatio":0.70,"drop":0.06}]""";

    private static final String VIDEO_B = """
            [{"seq":1,"phase":"hook","startSec":0.0,"endSec":4.0,"avgWatchRatio":0.90,"drop":0.04},
             {"seq":2,"phase":"development","startSec":4.0,"endSec":11.0,"avgWatchRatio":0.60,"drop":0.20}]""";

    private static final String VIDEO_C = """
            [{"seq":1,"phase":"hook","startSec":0.0,"endSec":5.0,"avgWatchRatio":0.92,"drop":0.00},
             {"seq":2,"phase":"climax","startSec":5.0,"endSec":10.0,"avgWatchRatio":0.85,"drop":0.01}]""";

    @Test
    void aggregatesPerPhaseAcrossVideos_skippingMalformedPayloads() {
        var agg = PhaseRetention.aggregate(
                List.of(VIDEO_A, VIDEO_B, VIDEO_C, "not json", "", "[]"));

        assertEquals(3, agg.videos(), "malformed/blank/empty payloads must not count as videos");

        // Canonical story order, only phases that occur.
        assertEquals(List.of("hook", "development", "climax"),
                agg.phases().stream().map(PhaseRetention.PhaseStat::phase).toList());

        var hook = agg.phases().get(0);
        assertEquals(3, hook.videos());
        assertEquals(0.02, hook.avgDrop(), 1e-9);          // (0.02+0.04+0.00)/3
        assertEquals(0.923, hook.avgWatchRatio(), 1e-9);   // (0.95+0.90+0.92)/3 rounded to 3

        // Per video the in-scene drops are SUMMED, then averaged across videos:
        // A: 0.10+0.06=0.16, B: 0.20 → avg 0.18. Watch: A mean 0.75, B 0.60 → 0.675.
        var dev = agg.phases().get(1);
        assertEquals(2, dev.videos());
        assertEquals(0.18, dev.avgDrop(), 1e-9);
        assertEquals(0.675, dev.avgWatchRatio(), 1e-9);
    }

    @Test
    void hintStaysSilentBelowThreshold_andNamesWorstPhaseAtThreshold() {
        // 2 videos with data → below MIN_VIDEOS → silence.
        var thin = PhaseRetention.aggregate(List.of(VIDEO_A, VIDEO_B));
        assertNull(PhaseRetention.hintSection(thin, PhaseRetention.MIN_VIDEOS));

        // Nothing at all → silence, never an exception.
        assertNull(PhaseRetention.hintSection(PhaseRetention.Aggregate.empty(), PhaseRetention.MIN_VIDEOS));
        assertNull(PhaseRetention.hintSection(null, PhaseRetention.MIN_VIDEOS));

        // 3 videos → speaks up, naming the worst-dropping phase (development).
        var agg = PhaseRetention.aggregate(List.of(VIDEO_A, VIDEO_B, VIDEO_C));
        String hint = PhaseRetention.hintSection(agg, PhaseRetention.MIN_VIDEOS);
        assertNotNull(hint);
        assertTrue(hint.contains("development"), "worst phase must be named: " + hint);
        assertTrue(hint.contains("3 published videos"), hint);
        assertTrue(hint.contains("-18.0%"), "avg drop must be reported as percent: " + hint);
    }
}
