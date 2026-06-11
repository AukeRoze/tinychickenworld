package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic PACING gate — the benchmark's hardest finding made machine-
 * checkable. Subtitle block 17 of episode 1 packed THREE speakers and a
 * participation question into five seconds; no validator could see it.
 * Now one can:
 *
 *   1. WORDS PER SECOND — a 3-6 year old follows ~3 words/sec at most.
 *   2. SPEAKER CHANGES — max 2 changes per scene (three voices in one short
 *      scene reads as noise to a preschooler).
 *   3. THE SILENT VISUAL BEAT — exactly ONE scene must act without dialogue
 *      (lines: []) on the emotional peak. Zero = the script tells instead of
 *      shows; more than one = dead air.
 *
 * Violations re-prompt once with exact fixes (same philosophy as Structure/
 * Comedy: never hard-block the final attempt — critic + human gate remain
 * the backstop).
 */
@Slf4j
@Component
public class PacingValidator {

    /** ~3 w/s is the preschool ceiling; small grace for short exclamations. */
    private static final double MAX_WORDS_PER_SEC = 3.2;
    private static final int MAX_SPEAKER_CHANGES = 2;

    public record Result(List<String> violations) {
        public boolean failed() { return !violations.isEmpty(); }
    }

    public Result validate(GeneratedScript script) {
        List<String> v = new ArrayList<>();
        List<GeneratedScript.Scene> scenes = script == null ? List.of() : script.scenes();
        if (scenes == null || scenes.isEmpty()) return new Result(v);

        int silentBeats = 0;
        for (GeneratedScript.Scene s : scenes) {
            List<GeneratedScript.Line> lines = s.lines() == null ? List.of() : s.lines();
            if (lines.isEmpty()) {
                silentBeats++;
                if (s.visualDesc() == null || s.visualDesc().split("\\s+").length < 10) {
                    v.add("scene " + s.seq() + " is a silent beat but its visualDesc is not a full "
                            + "ACTING beat — describe what the character DOES and FEELS, beat by beat");
                }
                continue;
            }
            int words = 0;
            for (GeneratedScript.Line l : lines) {
                if (l.text() != null) words += l.text().trim().split("\\s+").length;
            }
            int dur = Math.max(1, s.durationSeconds());
            double wps = (double) words / dur;
            if (wps > MAX_WORDS_PER_SEC) {
                v.add(String.format(
                        "scene %d packs %d words into %ds (%.1f w/s — max %.1f for ages 3-6): "
                        + "cut dialogue or lengthen the scene", s.seq(), words, dur, wps, MAX_WORDS_PER_SEC));
            }
            int changes = 0;
            String prev = null;
            for (GeneratedScript.Line l : lines) {
                String sp = l.speaker() == null ? "" : l.speaker();
                if (prev != null && !prev.equalsIgnoreCase(sp)) changes++;
                prev = sp;
            }
            if (changes > MAX_SPEAKER_CHANGES) {
                v.add(String.format(
                        "scene %d has %d speaker changes (max %d): a preschooler cannot follow "
                        + "three voices in one short scene — split the scene or merge lines",
                        s.seq(), changes, MAX_SPEAKER_CHANGES));
            }
        }

        if (silentBeats == 0) {
            v.add("no SILENT VISUAL BEAT: exactly one scene must have lines: [] on the emotional "
                    + "peak (the moment of discovery), with a full acting-beat visualDesc");
        } else if (silentBeats > 1) {
            v.add(silentBeats + " silent scenes found — exactly ONE is the rule; give the others dialogue");
        }
        return new Result(v);
    }
}
