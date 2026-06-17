package com.youtubeauto.orchestrator.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic split of IMPACT-onomatopoeia "lines" out of spoken dialogue and
 * into the foley / sound-effects layer. Pure functions (no Spring, no I/O), so
 * trivially unit-testable.
 *
 * <p>The defect this fixes (EP3 review, scene 8): a physical-impact sound was
 * authored as a spoken line — {@code Bo says, "Bonk!"} — at the exact beat Bo
 * plops onto the egg. "Bonk" is foley, not speech: coded as dialogue the video
 * model makes Bo lip-sync the word "bonk" (a morphing beak), it counts as an
 * extra speaker turn that overstuffs the 10-second beat, and the subtitle track
 * prints "Bonk!" as if spoken. The sound belongs under AUDIO → Sound effects,
 * while Bo's real line simply ends at "…like a real hen!" and she then physically
 * falls.
 *
 * <p>Conservative by design: a line is treated as foley ONLY when EVERY one of
 * its word-tokens is a physical-impact onomatopoeia. A real line that merely
 * contains a sound word ("Bonk, wait—", "Whoosh, look at me!") is left as speech,
 * and motion vocalisations a chick actually says ("Wheee!", "Whoosh!", "Boing!")
 * are NOT in the impact set, so they stay spoken — only true collision foley
 * (bonk, plop, thud, …) is moved.
 */
public final class OnomatopoeiaGuard {

    /** Physical-IMPACT / collision foley sounds. Deliberately excludes motion or
     *  emotion exclamations a chick vocalises (wheee, whoosh, boing, ta-da, yay) —
     *  those stay as spoken comedy beats. Lowercase, matched on whole tokens. */
    private static final Set<String> IMPACT_SFX = Set.of(
            "bonk", "plop", "plonk", "thud", "thunk", "thump", "bump", "bonkk",
            "splat", "clunk", "clonk", "donk", "whump", "flump", "flop", "plomp",
            "oof", "oomph", "smack", "thwack", "crunch", "bofff", "boff");

    private OnomatopoeiaGuard() {}

    /** True when {@code text} is PURELY one or more impact-onomatopoeia tokens
     *  (e.g. "Bonk!", "Bonk bonk!", "plop…") and nothing else. */
    public static boolean isPureImpactSfx(String text) {
        if (text == null || text.isBlank()) return false;
        String[] toks = text.toLowerCase(Locale.ROOT).split("[^a-z]+");
        boolean any = false;
        for (String t : toks) {
            if (t.isBlank()) continue;
            any = true;
            if (!IMPACT_SFX.contains(t)) return false;
        }
        return any;
    }

    /** The scene's spoken lines with every pure-impact-onomatopoeia line removed,
     *  so the dialogue/lip-sync direction covers only what a chick actually says.
     *  Returns the input list unchanged (same instances) when nothing is foley. */
    public static List<Map<String, Object>> spokenLines(List<Map<String, Object>> lines) {
        if (lines == null) return null;
        List<Map<String, Object>> out = new ArrayList<>(lines.size());
        for (Map<String, Object> ln : lines) {
            Object tx = ln == null ? null : ln.get("text");
            if (tx != null && isPureImpactSfx(tx.toString())) continue;
            out.add(ln);
        }
        return out;
    }

    /** A foley cue describing the impact sound(s) lifted out of the dialogue, ready
     *  to append to the AUDIO → Sound effects line; "" when there are none. */
    public static String impactSfxCue(List<Map<String, Object>> lines) {
        if (lines == null) return "";
        Set<String> sounds = new LinkedHashSet<>();
        for (Map<String, Object> ln : lines) {
            Object tx = ln == null ? null : ln.get("text");
            if (tx == null) continue;
            String text = tx.toString();
            if (!isPureImpactSfx(text)) continue;
            for (String t : text.toLowerCase(Locale.ROOT).split("[^a-z]+")) {
                if (!t.isBlank()) sounds.add(t);
            }
        }
        if (sounds.isEmpty()) return "";
        List<String> quoted = new ArrayList<>();
        for (String s : sounds) quoted.add("\"" + s + "\"");
        String joined = joinOr(quoted);
        return "include the physical impact foley " + joined
                + " as a real collision sound effect at the moment of contact — NOT spoken or lip-synced by any character";
    }

    private static String joinOr(List<String> xs) {
        if (xs.isEmpty()) return "";
        if (xs.size() == 1) return xs.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < xs.size(); i++) {
            if (i > 0) b.append(i == xs.size() - 1 ? " and " : ", ");
            b.append(xs.get(i));
        }
        return b.toString();
    }
}
