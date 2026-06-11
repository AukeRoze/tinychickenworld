package com.youtubeauto.orchestrator.review;

import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Role 7 — YouTube Retention Specialist, as a DETERMINISTIC scorer (no LLM).
 *
 * Scores the assembled episode's retention design 0-100 from structural signals
 * the script + assembly already carry: a strong emotional hook in scene 1, a
 * fast re-hook cadence (something changes every few seconds), location + emotion
 * variety (each change re-hooks attention), a deliberate closer, curiosity loops
 * (open questions in the dialogue) and a sane length. These are exactly the
 * levers that keep a 3-6yo watching, and they're all readable from the data.
 */
@Component
public class RetentionScorer {

    public record Result(int score, List<String> notes) {}

    public Result evaluate(List<Map<String, Object>> scenes, int targetSeconds) {
        if (scenes == null || scenes.isEmpty()) {
            return new Result(0, List.of("No scenes to score"));
        }
        int n = scenes.size();

        // --- Hook: scene 1 should be a hook beat with a clear emotion (close-up
        //     wonder), not a calm establishing shot. (weight 25) ---
        Map<String, Object> first = scenes.get(0);
        boolean hookPhase = "hook".equalsIgnoreCase(str(first.get("phase")));
        boolean hookEmotion = !str(first.get("emotion")).isBlank();
        int hook = (hookPhase ? 15 : 0) + (hookEmotion ? 10 : 0);

        // --- Re-hook cadence: average scene length. Modern kids content cuts
        //     every 2-4s; we reward short average scene duration. (weight 25) ---
        double totalDur = 0;
        for (Map<String, Object> s : scenes) totalDur += intval(s.get("durationSeconds"), 0);
        double avg = totalDur / n;
        int cadence;
        if (avg <= 4.0)      cadence = 25;
        else if (avg <= 6.0) cadence = 19;
        else if (avg <= 8.0) cadence = 12;
        else                 cadence = 5;

        // --- Location variety: at least 3 distinct locations re-hook the eye. (15) ---
        Set<String> locs = new HashSet<>();
        Set<String> emos = new HashSet<>();
        int questions = 0;
        for (Map<String, Object> s : scenes) {
            String loc = str(s.get("locationId"));
            if (!loc.isBlank()) locs.add(loc.toLowerCase());
            String emo = str(s.get("emotion"));
            if (!emo.isBlank()) emos.add(emo.toLowerCase());
            questions += countQuestions(s);
        }
        int locVariety = locs.size() >= 3 ? 15 : (locs.size() == 2 ? 9 : 3);

        // --- Emotion variety: aggressive rotation keeps it fresh; 6+ is the
        //     script rule. (weight 15) ---
        int emoVariety = emos.size() >= 6 ? 15 : Math.min(15, emos.size() * 2);

        // --- Deliberate closer beat. (weight 10) ---
        boolean closer = "closer".equalsIgnoreCase(str(scenes.get(n - 1).get("phase")));
        int close = closer ? 10 : 0;

        // --- Curiosity loops: open questions in the dialogue pull viewers
        //     forward. Reward roughly one question per ~3 scenes. (weight 10) ---
        int curiosity = questions >= Math.max(2, n / 3) ? 10 : Math.min(10, questions * 3);

        int score = hook + cadence + locVariety + emoVariety + close + curiosity;
        score = Math.max(0, Math.min(100, score));

        List<String> notes = List.of(
                "Hook " + hook + "/25 (phase=" + str(first.get("phase")) + ")",
                "Cadence " + cadence + "/25 (avg " + String.format("%.1f", avg) + "s/scene)",
                "Locations " + locs.size() + " → " + locVariety + "/15",
                "Emotions " + emos.size() + " → " + emoVariety + "/15",
                "Closer " + close + "/10",
                "Curiosity " + questions + " Qs → " + curiosity + "/10"
        );
        return new Result(score, notes);
    }

    private int countQuestions(Map<String, Object> scene) {
        int q = 0;
        Object lines = scene.get("lines");
        if (lines instanceof List<?> l) {
            for (Object o : l) {
                if (o instanceof Map<?, ?> m) {
                    Object t = m.get("text");
                    if (t != null && t.toString().contains("?")) q++;
                }
            }
        }
        // Fallback: narration text.
        String narr = str(scene.get("narration"));
        if (q == 0 && narr.contains("?")) q++;
        return q;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }

    private static int intval(Object o, int dflt) {
        if (o instanceof Number num) return num.intValue();
        try { return o == null ? dflt : (int) Double.parseDouble(o.toString()); }
        catch (NumberFormatException e) { return dflt; }
    }
}
