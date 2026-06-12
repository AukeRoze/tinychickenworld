package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.bible.EpisodePhase;
import com.youtubeauto.script.bible.EpisodeStructure;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic beat-sheet / structure check. The prompt asks the LLM to follow
 * the episode structure, story arc, re-hooks and a satisfying ending — but
 * nothing enforced it (the old validate() only logged a duration warning). This
 * turns the bible's {@link EpisodeStructure} into HARD checks so a script that
 * skips the structure, plays its phases out of order, jams everything in one
 * location, or ends on the wrong phase is rejected and re-prompted instead of
 * rendered.
 *
 * Tolerances are deliberately lenient (the goal is to catch real failures, not
 * nitpick) so we don't burn endless re-prompts.
 */
@Component
public class StructureValidator {

    // ±10% scripted (was ±20%): voice-stretching at assembly adds another
    // ~10% on top of SCRIPTED durations, so a script passing at +20% renders
    // at ~+30% (ep 2: 90s target → 124s master). Tight here keeps the
    // delivered master inside the render-time duration gate.
    private static final double TOTAL_DRIFT_MAX = 0.10;
    private static final double PHASE_DRIFT_MAX = 0.40;   // ±40% per phase
    private static final int    MAX_SAME_LOCATION_RUN = 4;

    /** @return list of human-readable violations; empty = the script is valid. */
    public List<String> validate(GeneratedScript s, EpisodeStructure es, int targetSeconds) {
        List<String> v = new ArrayList<>();
        if (s == null || s.scenes() == null || s.scenes().isEmpty()) {
            v.add("Script has no scenes.");
            return v;
        }
        List<GeneratedScript.Scene> scenes = s.scenes();

        // 1) Scene sequence is contiguous 1..n.
        for (int i = 0; i < scenes.size(); i++) {
            if (scenes.get(i).seq() != i + 1) {
                v.add("Scene seq out of order at position " + (i + 1) + " (got " + scenes.get(i).seq() + ").");
            }
        }

        // 2) Total duration near target.
        int total = scenes.stream().mapToInt(GeneratedScript.Scene::durationSeconds).sum();
        if (targetSeconds > 0) {
            double drift = Math.abs(total - targetSeconds) / (double) targetSeconds;
            if (drift > TOTAL_DRIFT_MAX) {
                v.add(String.format("Total duration is %ds, %d%% off the %ds target (max %d%%) — adjust scene durations.",
                        total, Math.round(drift * 100), targetSeconds, Math.round(TOTAL_DRIFT_MAX * 100)));
            }
        }

        // 3) Phase checks against the bible episode structure.
        if (es != null && es.phases() != null && !es.phases().isEmpty()) {
            Set<String> valid = es.phases().stream()
                    .map(p -> p.id().toLowerCase()).collect(Collectors.toSet());

            for (GeneratedScript.Scene sc : scenes) {
                String ph = sc.phase() == null ? "" : sc.phase().trim().toLowerCase();
                if (ph.isBlank()) {
                    v.add("Scene " + sc.seq() + " is missing its phase field.");
                } else if (!valid.contains(ph)) {
                    v.add("Scene " + sc.seq() + " has unknown phase '" + sc.phase()
                            + "' (must be one of " + valid + ").");
                }
            }

            for (EpisodePhase p : es.phases()) {
                List<GeneratedScript.Scene> inPhase = scenes.stream()
                        .filter(sc -> p.id().equalsIgnoreCase(sc.phase()))
                        .toList();
                int cnt = inPhase.size();
                if (cnt < p.minScenes()) {
                    v.add(String.format("Phase '%s' has %d scene(s); needs at least %d.", p.id(), cnt, p.minScenes()));
                } else if (cnt > p.maxScenes()) {
                    v.add(String.format("Phase '%s' has %d scene(s); allows at most %d.", p.id(), cnt, p.maxScenes()));
                }
                if (cnt > 0 && p.seconds() > 0) {
                    int sec = inPhase.stream().mapToInt(GeneratedScript.Scene::durationSeconds).sum();
                    double d = Math.abs(sec - p.seconds()) / (double) p.seconds();
                    if (d > PHASE_DRIFT_MAX) {
                        v.add(String.format("Phase '%s' runs %ds; target ~%ds (>%d%% off).",
                                p.id(), sec, p.seconds(), Math.round(PHASE_DRIFT_MAX * 100)));
                    }
                }
            }

            // 4) The last scene must belong to the last declared phase (the closer)
            //    — a satisfying, intentional ending, not a mid-arc cut-off.
            String lastDeclared = es.phases().get(es.phases().size() - 1).id();
            String lastScenePhase = scenes.get(scenes.size() - 1).phase();
            if (lastScenePhase != null && !lastDeclared.equalsIgnoreCase(lastScenePhase.trim())) {
                v.add(String.format("Last scene is phase '%s'; it must be the closing phase '%s'.",
                        lastScenePhase, lastDeclared));
            }

            // 4b) Phases must appear in bible order (the declaration order of
            //     es.phases() is the canonical retention template). Each scene's
            //     phase index must be >= the previous scene's — non-decreasing —
            //     so a phase may not return once a later phase has started. Without
            //     this, a script with the climax up front passed as long as the
            //     counts/durations matched and the last scene was the closer.
            //     Unknown/blank phases are skipped here; check 3 already flags them.
            Map<String, Integer> order = new HashMap<>();
            for (int i = 0; i < es.phases().size(); i++) {
                order.put(es.phases().get(i).id().toLowerCase(), i);
            }
            int furthest = -1;
            String furthestId = null;
            for (GeneratedScript.Scene sc : scenes) {
                String ph = sc.phase() == null ? "" : sc.phase().trim().toLowerCase();
                Integer idx = order.get(ph);
                if (idx == null) continue;
                if (idx < furthest) {
                    v.add(String.format("Phase order violated: scene %d ('%s') appears after phase '%s' started.",
                            sc.seq(), es.phases().get(idx).id(), furthestId));
                    break;
                }
                furthest = idx;
                furthestId = es.phases().get(idx).id();
            }
        }

        // 5) Location variety — anti "every scene is the same barn".
        long distinctLoc = scenes.stream()
                .map(GeneratedScript.Scene::locationId)
                .filter(x -> x != null && !x.isBlank())
                .distinct().count();
        if (scenes.size() >= 6 && distinctLoc < 2) {
            v.add("All scenes share a single location — use at least 2-3 distinct locations for visual variety.");
        }
        int run = 1;
        for (int i = 1; i < scenes.size(); i++) {
            String a = nz(scenes.get(i).locationId());
            String b = nz(scenes.get(i - 1).locationId());
            if (!a.isBlank() && a.equals(b)) {
                if (++run > MAX_SAME_LOCATION_RUN) {
                    v.add("More than " + MAX_SAME_LOCATION_RUN + " consecutive scenes in location '"
                            + a + "' — rotate the setting or time-of-day.");
                    break;
                }
            } else {
                run = 1;
            }
        }

        // 6) Cast continuity — no character may vanish for a SINGLE scene and then
        //    reappear (the jarring 1→2→3→1 cast-count bouncing). One sidekick
        //    appearing/leaving with a run is fine; a one-scene gap is "flicker".
        List<Set<String>> casts = scenes.stream()
                .map(sc -> sc.characters() == null ? Set.<String>of()
                        : sc.characters().stream()
                            .filter(x -> x != null && !x.isBlank())
                            .map(String::toLowerCase)
                            .collect(Collectors.toSet()))
                .collect(Collectors.toList());
        Set<String> allChars = casts.stream().flatMap(Set::stream).collect(Collectors.toSet());
        for (String c : allChars) {
            for (int i = 1; i + 1 < casts.size(); i++) {
                if (casts.get(i - 1).contains(c) && !casts.get(i).contains(c)
                        && casts.get(i + 1).contains(c)) {
                    v.add("Character '" + c + "' flickers — present in scenes " + i + " and "
                            + (i + 2) + " but gone in scene " + (i + 1)
                            + ". Keep cast presence continuous (no single-scene disappearances).");
                    break;
                }
            }
        }

        // 7) Cast size per scene — every extra character in frame raises the odds
        //    of AI drift (accessory swaps, morphing, extra chickens hallucinated
        //    into wide shots). Normal beats are capped at a two-shot; only the
        //    peak beats (hook/climax), the closer and the first/last scene (the
        //    flock intro/outro wave) may stage three. The image/Veo prompt locks
        //    downstream assume this cap.
        for (int i = 0; i < scenes.size(); i++) {
            GeneratedScript.Scene sc = scenes.get(i);
            int cast = sc.characters() == null ? 0 : (int) sc.characters().stream()
                    .filter(x -> x != null && !x.isBlank()).distinct().count();
            String ph = sc.phase() == null ? "" : sc.phase().trim().toLowerCase();
            boolean groupAllowed = ph.equals("hook") || ph.equals("climax") || ph.equals("closer")
                    || i == 0 || i == scenes.size() - 1;
            int cap = groupAllowed ? MAX_CAST_HERO : MAX_CAST_STANDARD;
            if (cast > cap) {
                v.add("Scene " + sc.seq() + " stages " + cast + " characters; max " + cap
                        + (groupAllowed ? "" : " for a normal scene (3 only on hook/climax/closer)")
                        + " — split the beat into a two-shot plus a reaction shot.");
            }
        }

        return v;
    }

    // Cast caps per scene: standard beats are solo/two-shots; hero beats and the
    // intro/outro group wave may stage the full trio. Wide shots with 3+ chicks
    // are where image-to-video models hallucinate extras.
    private static final int MAX_CAST_STANDARD = 2;
    private static final int MAX_CAST_HERO     = 3;

    private static String nz(String s) { return s == null ? "" : s.trim(); }
}
