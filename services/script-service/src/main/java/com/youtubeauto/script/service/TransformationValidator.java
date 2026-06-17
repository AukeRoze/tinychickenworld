package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic LIVE-TRANSFORMATION gate.
 *
 * <p>The defect (EP3 review, scene 20): the hatch beat lists the NEW character
 * (the duckling) in its cast from frame&nbsp;0, while the action describes the
 * egg cracking open and revealing it partway through the clip. A video model has
 * no notion of "appears at second&nbsp;3": told the duckling is in the scene, it
 * renders it from frame&nbsp;0 — so the duckling is already trotting beside the
 * egg, or an egg/duckling hybrid morphs into being. AI cannot do a clean live
 * egg&nbsp;→&nbsp;duckling transformation inside one continuous shot.
 *
 * <p>Generic rule (no character hard-coded): if a scene introduces a NEWCOMER
 * (a character not present in the previous scene) AND its action contains a
 * transformation/reveal verb (hatch, crack open, split open, burst, emerge,
 * reveal…), the beat is asking for a live transformation. The fix the gate
 * re-prompts for mirrors the proven hard-cut: keep the transformation scene on
 * the pre-existing cast only (the egg cracking with golden light), and stage the
 * CLEAN reveal of the newcomer — already out of / sitting in the shell — as the
 * NEXT scene. Never blocks the final attempt (human gate + critic remain the
 * backstop), exactly like the other deterministic gates.
 */
@Slf4j
@Component
public class TransformationValidator {

    /** Verbs/phrases that describe a thing changing INTO / revealing a new being
     *  within the shot — the constructions AI cannot animate cleanly in one clip. */
    private static final Pattern TRANSFORM = Pattern.compile(
            "\\b(hatch(es|ing|ed)?|crack(s|ing|ed)?\\s+(open|apart)|split(s|ting)?\\s+(open|apart)"
            + "|burst(s|ing)?\\s+(open|out)|breaks?\\s+(open|free|apart)|emerg(e|es|ing)"
            + "|reveal(s|ing)?|transform(s|ing|ed)?|morph(s|ing|ed)?|turns?\\s+into"
            + "|pops?\\s+out|steps?\\s+out|climbs?\\s+out|wriggles?\\s+out)\\b",
            Pattern.CASE_INSENSITIVE);

    public record Result(List<String> violations) {
        public boolean failed() { return !violations.isEmpty(); }
    }

    public Result validate(GeneratedScript script) {
        List<String> v = new ArrayList<>();
        List<GeneratedScript.Scene> scenes = script == null ? List.of() : script.scenes();
        if (scenes == null || scenes.size() < 2) return new Result(v);

        Set<String> prevCast = castOf(scenes.get(0));
        for (int i = 1; i < scenes.size(); i++) {
            GeneratedScript.Scene s = scenes.get(i);
            Set<String> cast = castOf(s);

            // Newcomers = characters in THIS scene but not the previous one.
            Set<String> newcomers = new HashSet<>(cast);
            newcomers.removeAll(prevCast);

            String desc = s.visualDesc() == null ? "" : s.visualDesc();
            if (!newcomers.isEmpty() && TRANSFORM.matcher(desc).find()) {
                v.add(String.format(
                        "scene %d stages a LIVE transformation: it both introduces %s AND describes "
                        + "the change happening on-screen (hatch/crack-open/reveal). A video model "
                        + "renders a listed character from frame 0, so it cannot cleanly turn the egg "
                        + "INTO the new character in one shot. Split it: keep scene %d on the existing "
                        + "cast only (the shell cracking with golden light, no %s in the cast), and "
                        + "stage the CLEAN reveal of %s (already out of / sitting in the shell) as the "
                        + "NEXT scene with a hard cut.",
                        s.seq(), newcomers, s.seq(), newcomers, newcomers));
            }
            prevCast = cast;
        }
        return new Result(v);
    }

    private static Set<String> castOf(GeneratedScript.Scene s) {
        Set<String> out = new HashSet<>();
        if (s == null || s.characters() == null) return out;
        for (String c : s.characters()) {
            if (c != null && !c.isBlank()) out.add(c.trim().toLowerCase(Locale.ROOT));
        }
        return out;
    }
}
