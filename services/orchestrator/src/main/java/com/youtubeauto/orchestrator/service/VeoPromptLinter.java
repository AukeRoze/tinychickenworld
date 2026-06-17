package com.youtubeauto.orchestrator.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic invariant checks on a COMPILED Veo prompt — the exact text
 * {@link VeoPromptCompiler#compile} produces and the videogen service sends to
 * Veo. Pure functions (no Spring, no I/O), so it is trivially unit-testable AND
 * can be called inline right before the (paid) videogen request to GATE a
 * malformed prompt before any clip is rendered.
 *
 * <p>Scope is deliberately narrow: only invariants that are 100% reliable from
 * the prompt text alone, so a finding always means a real defect and the linter
 * can safely block / warn without false positives. The fuzzy, semantic checks
 * (camera intent matching the shot, prop / location continuity across scenes)
 * live in the LLM critic and the emit_script guidance — a deterministic rule on
 * free text there would false-positive on legitimate scripts (proven against the
 * golden fixtures: off-frame cast members, intentional location revisits).
 *
 * <p>The two defects this would have caught on the EP3 review:
 * <ul>
 *   <li>truncated prompts ("…bright, soft midday sunlight. C", "…its own body colour;")
 *       — {@link #endsCleanly};</li>
 *   <li>a cast-lock count that disagrees with the scene's cast size.</li>
 * </ul>
 */
public final class VeoPromptLinter {

    private VeoPromptLinter() {}

    private static final Pattern COUNT =
            Pattern.compile("EXACTLY (\\d+) (?:character|chicken)", Pattern.CASE_INSENSITIVE);

    // Mixed-species rosters spell out an explicit "(TOTAL N CHARACTERS)" — the
    // real headcount — because the leading "EXACTLY 3 CHICKENS" only counts one
    // species. Prefer this total over the first per-species count when present.
    private static final Pattern TOTAL =
            Pattern.compile("TOTAL (\\d+) CHARACTER", Pattern.CASE_INSENSITIVE);

    /**
     * @param prompt    the compiled Veo prompt
     * @param castCount the scene's cast size (scene.characters); pass &lt;= 0 when unknown to skip the count check
     * @return human-readable findings; empty list = the prompt passes every invariant
     */
    public static List<String> lint(String prompt, int castCount) {
        List<String> f = new ArrayList<>();
        if (prompt == null || prompt.isBlank()) {
            f.add("Prompt is empty.");
            return f;
        }
        String p = prompt.trim();

        // 1) Integrity / truncation. A complete English prompt ends on terminal
        //    punctuation (the compiler closes with the render-look sentence). A cut
        //    prompt ends mid-word ("… sunlight. C") or mid-clause ("… body colour;").
        if (!endsCleanly(p)) {
            f.add("Prompt looks truncated — it does not end on a complete sentence (tail: \"…"
                    + tail(p) + "\").");
        }

        // 2) Required sections — the front-loaded action plus the locks that
        //    protect it. A missing one means the compiler dropped a clause.
        // Accept BOTH the legacy paragraph labels and the director's-brief section
        // headers (the veoNativeAudio output format).
        if (!p.contains("Action:") && !p.contains("CHRONOLOGICAL ACTION"))
            f.add("Missing required section: Action / Chronological Action.");
        if (!p.contains("Camera:") && !p.contains("Camera Setup"))
            f.add("Missing required section: Camera.");
        if (!p.contains("Setting:")) f.add("Missing required section: Setting:");
        if (!p.contains("Cast lock:") && !p.contains("Headcount lock:")
                && !p.contains("CHARACTER ROSTER")) {
            f.add("Missing the cast/headcount lock / character roster.");
        }

        // 3) Count consistency — the "EXACTLY N" in the lock must equal the scene
        //    cast size, or the lock is fighting the actual cast.
        if (castCount > 0) {
            Integer stated = statedCount(p);
            if (stated != null && stated != castCount) {
                f.add("Cast-lock count (" + stated + ") does not match the scene cast size ("
                        + castCount + ").");
            }
        }

        // 4) Light / time-of-day contradiction. The Setting light comes from the
        //    scene's timeOfDay; if the action then describes a different time the
        //    prompt fights itself (the scene-19/20/21 "dusk action under midday
        //    sunlight" defect). Day markers are limited to the unambiguous
        //    lightPhrase outputs ("midday sunlight", "dawn light") so a hit is a
        //    real contradiction — "golden-hour" is the everywhere-default and is
        //    deliberately excluded, and "fireflies" lives in the fixed ambient
        //    clause so it is NOT used as a night marker.
        String lc = p.toLowerCase();
        boolean daylight = lc.contains("midday sunlight") || lc.contains("dawn light");
        boolean darkTime = lc.contains("dusk") || lc.contains("twilight")
                || lc.contains("moonlit") || lc.contains("moonlight")
                || lc.contains("starlit") || lc.contains("starlight")
                || lc.contains("nighttime") || lc.contains("night sky");
        if (daylight && darkTime) {
            f.add("Light/time contradiction: the prompt mixes a daytime light "
                    + "(midday/dawn sunlight) with dusk/night cues — set the scene's "
                    + "timeOfDay to match the action.");
        }

        // 5) Scale contradiction. The compiler always injects the canonical
        //    "Relative size" rule (the chicks differ in size). An action that
        //    calls them "the same size" fights it → scale-flicker (the scene-26
        //    defect). Reliable: the canon is always "they differ", so any
        //    "same size" / "identical size" in the text is a contradiction.
        if (lc.contains("relative size") || lc.contains("scale")) {
            if (lc.contains("same size") || lc.contains("identical size")
                    || lc.contains("equal size") || lc.contains("same in size")) {
                f.add("Scale contradiction: the action calls the characters the same/equal "
                        + "size, but the prompt locks a canonical Relative size where they differ.");
            }
        }

        // 6) Camera self-contradiction. A single beat must not order both a
        //    push-in and a pull-back/wide reveal (the scene-22/23/24 defect where
        //    the climax phase preset's "push-in toward the emotional peak" was
        //    copied onto a wide-reveal / tumble beat). Advisory: "pulls back" can
        //    occasionally be a character action, so this flags for review.
        boolean pushIn = lc.contains("push-in") || lc.contains("push in toward");
        boolean pullBack = lc.contains("pull-back") || lc.contains("pulls back")
                || lc.contains("pull back") || lc.contains("wide reveal")
                || lc.contains("reveal the full");
        if (pushIn && pullBack) {
            f.add("Camera contradiction: the prompt orders both a push-in and a "
                    + "pull-back / wide reveal — give the scene a veoCameraOverride that "
                    + "matches its actual shot.");
        }
        // 7) Focus contradiction. A close-up of one subject must not be told to
        //    focus on the whole group (the scene-27 defect: a Pip close-up under
        //    the resolution preset's "soft focus on the flock together").
        boolean closeUpShot = lc.contains("close-up") || lc.contains("close up");
        if (closeUpShot && lc.contains("focus on the flock")) {
            f.add("Focus contradiction: a close-up shot but the camera focuses on the whole "
                    + "flock — give the scene a veoCameraOverride with focus on the framed subject.");
        }

        // 8) Length budget (advisory). Long prompts risk truncation at the model's
        //    token limit, which silently drops the TRAILING style/identity locks
        //    (Pixar look, no-morph, render-look) — exactly the bits that hold the
        //    look together. The shared boilerplate is the main bloat; if this fires
        //    a lot, compact it. Advisory only — it never blocks.
        int words = p.split("\\s+").length;
        if (words > SOFT_WORD_BUDGET) {
            f.add("Prompt is " + words + " words (soft budget " + SOFT_WORD_BUDGET
                    + ") — at this length the model may truncate and drop the trailing style "
                    + "locks; compact the shared boilerplate or move the critical locks earlier.");
        }
        return f;
    }

    /** Soft word budget above which truncation risk is worth flagging. Advisory:
     *  tune to the actual model limit; the goal is to surface the longest prompts. */
    private static final int SOFT_WORD_BUDGET = 450;

    /** True when the prompt is free of every invariant violation. */
    public static boolean isHealthy(String prompt, int castCount) {
        return lint(prompt, castCount).isEmpty();
    }

    private static boolean endsCleanly(String p) {
        char c = p.charAt(p.length() - 1);
        return c == '.' || c == '!' || c == '?' || c == '"';
    }

    private static String tail(String p) {
        return p.length() <= 28 ? p : p.substring(p.length() - 28);
    }

    private static Integer statedCount(String p) {
        Matcher t = TOTAL.matcher(p);
        if (t.find()) return Integer.valueOf(Integer.parseInt(t.group(1)));
        Matcher m = COUNT.matcher(p);
        return m.find() ? Integer.valueOf(Integer.parseInt(m.group(1))) : null;
    }
}
