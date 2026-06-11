package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.domain.VideoAudit;
import com.youtubeauto.orchestrator.domain.VideoJob;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Role 12 — the QA Board. Consolidates every reviewer's verdict into ONE
 * 8-axis score (each /10), a /100 total and a ship verdict, with the publish
 * gate thresholds the user defined (80 good / 90 top channel / 95 Disney-Pixar).
 *
 * Where each axis comes from (re-using existing work, not re-judging):
 *   Story            ← script-critic overall + deterministic structure score
 *   Characters       ← vision audit character_drift (cast consistency)
 *   Animation        ← vision audit framing + hero-scene motionDesc coverage (proxy)
 *   Sound            ← {@link SoundScorer} (audit audio_balance + sound assets)
 *   Retention        ← {@link RetentionScorer} (deterministic structure)
 *   Humor            ← script-critic comedy axis
 *   Emotional Impact ← script-critic emotionalImpact axis
 *   Thumbnail        ← {@link ThumbnailCtrScorer} (vision CTR)
 *
 * The script-critic childPsychology axis is used as a SAFETY FLOOR: an
 * emotionally-unsafe or confusing script (<5/10) forces a hard block regardless
 * of the total, because kids content must be safe first.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QaBoard {

    public record Axis(String name, int score, String source) {}      // score 0-10

    public record Result(int total, String verdict, boolean safe, boolean publishable,
                         List<Axis> axes, String json) {}

    private final RetentionScorer retentionScorer;
    private final SoundScorer soundScorer;
    private final ThumbnailCtrScorer thumbnailScorer;
    private final ConsistencyChecker consistencyChecker;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Minimum total to allow publishing (the user's 80 gate). */
    public static final int PUBLISH_MIN = 80;

    public Result evaluate(VideoJob job, JsonNode scriptBody,
                           List<Map<String, Object>> scenes, VideoAudit audit,
                           String thumbnailPath) {

        // --- gather sub-scores ---
        RetentionScorer.Result ret = retentionScorer.evaluate(scenes, job.getTargetSeconds());
        Integer audioBalance = audit == null ? null : audit.getAudioBalance();
        SoundScorer.Result snd = soundScorer.evaluate(job, audioBalance);
        ThumbnailCtrScorer.Result thumb = thumbnailScorer.evaluate(thumbnailPath);

        Integer criticOverall = nint(scriptBody, "criticScore");
        Integer structure     = nint(scriptBody, "structureScore");
        Integer comedy        = nint(scriptBody, "comedy");           // 0-10
        Integer emotional     = nint(scriptBody, "emotionalImpact");  // 0-10
        Integer psychology    = nint(scriptBody, "childPsychology");  // 0-10

        Integer drift   = audit == null ? null : audit.getCharacterDrift();
        Integer framing = audit == null ? null : audit.getFraming();

        List<Axis> axes = new ArrayList<>();

        // 1. Story — blend critic-overall + structure (both /100). ---
        int story = avgTo10(criticOverall, structure, 6);
        axes.add(new Axis("Story", story,
                "critic " + nz(criticOverall) + "/100, structure " + nz(structure) + "/100"));

        // 2. Characters — cast consistency: blend the vision audit's drift score
        //    with the DETERMINISTIC cross-scene checker (prop-colour drift, cast
        //    sanity, accessory reinforcement) so a continuity bug the vision pass
        //    misses still pulls the axis down. Vision 0.6 / deterministic 0.4. ---
        ConsistencyChecker.Result cons = consistencyChecker.evaluate(scenes);
        int visionChars = to10(drift, 6);
        int detChars = clamp10(Math.round(cons.score() / 10f));
        int characters = (drift == null)
                ? detChars
                : clamp10(Math.round(visionChars * 0.6f + detChars * 0.4f));
        axes.add(new Axis("Characters", characters,
                "character_drift " + nz(drift) + "/100, continuity " + cons.score() + "/100"));

        // 3. Animation — framing (vision) + MOTION RICHNESS of the hero beats.
        //    Richness rewards real directed motion (a motion verb in the brief,
        //    an end-pose to interpolate to, a pacing cue) — not merely "a
        //    motionDesc string exists". Honest proxy until per-clip video QC. ---
        int motionRich = motionRichness(scenes);       // 0-100
        int animation = framing == null
                ? clamp10(Math.round(motionRich / 10f))
                : clamp10(Math.round((framing * 0.6f + motionRich * 0.4f) / 10f));
        axes.add(new Axis("Animation", animation,
                "framing " + nz(framing) + "/100, motion richness " + motionRich + "%"));

        // 4. Sound. ---
        int sound = clamp10(Math.round(snd.score() / 10f));
        axes.add(new Axis("Sound", sound, "sound design " + snd.score() + "/100"));

        // 5. Retention. ---
        int retention = clamp10(Math.round(ret.score() / 10f));
        axes.add(new Axis("Retention", retention, "retention " + ret.score() + "/100"));

        // 6. Humor — script-critic comedy axis. ---
        int humor = comedy == null ? 6 : clamp10(comedy);
        axes.add(new Axis("Humor", humor, "comedy " + nz(comedy) + "/10"));

        // 7. Emotional Impact — script-critic emotionalImpact axis. ---
        int emo = emotional == null ? 6 : clamp10(emotional);
        axes.add(new Axis("Emotional Impact", emo, "emotionalImpact " + nz(emotional) + "/10"));

        // 8. Thumbnail. ---
        int thumbnail = clamp10(Math.round(thumb.score() / 10f));
        axes.add(new Axis("Thumbnail", thumbnail, "CTR " + thumb.score() + "/100"));

        // --- total /100 (8 axes × /10 = /80, scaled to /100) ---
        int sum = axes.stream().mapToInt(Axis::score).sum();
        int total = Math.round(sum * 100f / 80f);

        // --- safety floor: an emotionally-unsafe / confusing script blocks ---
        boolean safe = psychology == null || psychology >= 5;

        String verdict;
        if (!safe)              verdict = "BLOCK — emotionally unsafe / confusing (child-fit " + psychology + "/10)";
        else if (total >= 95)   verdict = "DISNEY/PIXAR level (95+)";
        else if (total >= 90)   verdict = "TOP CHANNEL (90+)";
        else if (total >= PUBLISH_MIN) verdict = "GOOD — ship it (80+)";
        else                    verdict = "NEEDS REWORK (<80)";

        boolean publishable = safe && total >= PUBLISH_MIN;

        String emoNote = emotionBuildNote(scenes);
        String json = toJson(total, verdict, safe, publishable, axes,
                psychology, ret, snd, thumb, cons, emoNote);
        log.info("QA Board for job {} → {}/100 ({}), publishable={}",
                job.getId(), total, verdict, publishable);
        return new Result(total, verdict, safe, publishable, axes, json);
    }

    // ---- helpers -----------------------------------------------------------

    /** Motion verbs that signal a brief actually describes directed movement
     *  rather than a static composition. */
    private static final Set<String> MOTION_VERBS = Set.of(
            "run", "runs", "hop", "hops", "jump", "jumps", "fly", "flies", "flap",
            "flaps", "wave", "waves", "spin", "spins", "dig", "digs", "splash",
            "splashes", "dive", "dives", "climb", "climbs", "peck", "pecks",
            "bounce", "bounces", "tumble", "tumbles", "roll", "rolls", "dash",
            "dashes", "leap", "leaps", "wobble", "wobbles", "shake", "shakes",
            "nod", "nods", "tilt", "tilts", "lean", "leans", "reach", "reaches",
            "push", "pushes", "pull", "pulls", "lift", "lifts", "turn", "turns",
            "gasp", "gasps", "blink", "blinks", "wiggle", "wiggles", "scamper",
            "skip", "skips", "twirl", "twirls", "rush", "rushes", "scratch",
            "tip", "tips", "nudge", "nudges", "point", "points", "stomp", "stomps");

    /**
     * Animation richness of the hero (hook/climax) beats, 0-100. Per hero scene
     * we reward the signals that produce real directed motion in Veo: a motion
     * verb in the brief (40), an end-pose to interpolate toward (30), an explicit
     * pacing cue (15) and a sufficiently described brief (15). Averaged over the
     * hero scenes. No hero beats (e.g. a Ken Burns test) → neutral 70.
     */
    private int motionRichness(List<Map<String, Object>> scenes) {
        if (scenes == null || scenes.isEmpty()) return 0;
        int hero = 0, sum = 0;
        for (Map<String, Object> s : scenes) {
            String phase = str(s.get("phase")).toLowerCase();
            if (!phase.equals("hook") && !phase.equals("climax")) continue;
            hero++;
            String motion = str(s.get("motionDesc"));
            int sub = 0;
            if (!motion.isBlank() && hasMotionVerb(motion)) sub += 40;
            else if (!motion.isBlank())                     sub += 15; // some brief, but static-sounding
            if (!str(s.get("endPose")).isBlank())           sub += 30;
            if (!str(s.get("motionSpeed")).isBlank())       sub += 15;
            if (motion.length() >= 40)                      sub += 15;
            sum += Math.min(100, sub);
        }
        if (hero == 0) return 70;
        return Math.round((float) sum / hero);
    }

    private boolean hasMotionVerb(String text) {
        for (String w : text.toLowerCase().split("[^a-z]+")) {
            if (MOTION_VERBS.contains(w)) return true;
        }
        return false;
    }

    /**
     * Story E1 — a deterministic read of the episode's EMOTIONAL BUILD. Maps each
     * scene's emotion to an arousal value and checks that the climax phase is the
     * arousal peak (a Pixar episode rises to its climax; a flat curve means the
     * climax won't feel like one). Informational only — it adds a QA note, it does
     * not change the score, so it can never wrongly block a publish.
     */
    private String emotionBuildNote(List<Map<String, Object>> scenes) {
        if (scenes == null || scenes.isEmpty()) return null;
        double climaxSum = 0, climaxN = 0, otherMax = 0;
        boolean sawClimax = false;
        for (Map<String, Object> s : scenes) {
            double a = arousal(str(s.get("emotion")));
            String phase = str(s.get("phase")).toLowerCase();
            if (phase.equals("climax")) { climaxSum += a; climaxN++; sawClimax = true; }
            else otherMax = Math.max(otherMax, a);
        }
        if (!sawClimax || climaxN == 0) return null;
        double climaxAvg = climaxSum / climaxN;
        if (climaxAvg + 0.001 >= otherMax)
            return "build OK — the climax is the emotional peak (climax arousal "
                    + Math.round(climaxAvg * 100) + " vs pre-climax peak " + Math.round(otherMax * 100) + ").";
        return "build is FLAT — an earlier beat (arousal " + Math.round(otherMax * 100)
                + ") feels stronger than the climax (" + Math.round(climaxAvg * 100)
                + "). Raise the climax's emotion or soften the lull before it.";
    }

    /** Maps a free-text emotion to an arousal value 0..1 (kept simple + safe). */
    private static double arousal(String emotion) {
        if (emotion == null || emotion.isBlank()) return 0.5;
        String e = emotion.toLowerCase();
        if (containsAny(e, "excit", "surpris", "scared", "afraid", "joy", "amaz",
                "thrill", "panic", "wow", "shock")) return 0.9;
        if (containsAny(e, "curio", "wonder", "playful", "eager", "proud", "happy",
                "delight", "giggl", "silly")) return 0.7;
        if (containsAny(e, "calm", "sleep", "tender", "soft", "relax", "relie",
                "content", "cosy", "cozy", "gentle")) return 0.3;
        if (containsAny(e, "sad", "worried", "disappoint", "lonely", "miss", "sorry")) return 0.35;
        return 0.5;
    }

    private static boolean containsAny(String hay, String... needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }

    private String toJson(int total, String verdict, boolean safe, boolean publishable,
                          List<Axis> axes, Integer psychology,
                          RetentionScorer.Result ret, SoundScorer.Result snd,
                          ThumbnailCtrScorer.Result thumb, ConsistencyChecker.Result cons,
                          String emoNote) {
        ObjectNode root = mapper.createObjectNode();
        root.put("total", total);
        root.put("verdict", verdict);
        root.put("safe", safe);
        root.put("publishable", publishable);
        root.put("publishMin", PUBLISH_MIN);
        if (psychology != null) root.put("childPsychology", psychology);
        ArrayNode arr = root.putArray("axes");
        for (Axis a : axes) {
            ObjectNode n = arr.addObject();
            n.put("name", a.name());
            n.put("score", a.score());
            n.put("source", a.source());
        }
        ArrayNode details = root.putArray("details");
        ret.notes().forEach(x -> details.add("Retention: " + x));
        snd.notes().forEach(x -> details.add("Sound: " + x));
        thumb.notes().forEach(x -> details.add("Thumbnail: " + x));
        if (cons != null) cons.notes().forEach(x -> details.add("Consistency: " + x));
        if (emoNote != null && !emoNote.isBlank()) details.add("Emotion: " + emoNote);
        try { return mapper.writeValueAsString(root); }
        catch (Exception e) { return "{\"total\":" + total + ",\"verdict\":\"" + verdict + "\"}"; }
    }

    private Integer nint(JsonNode node, String field) {
        if (node == null) return null;
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asInt();
    }

    /** A /100 value to a /10 axis, with a default when null. */
    private int to10(Integer v100, int dflt) {
        return v100 == null ? dflt : clamp10(Math.round(v100 / 10f));
    }

    /** Average of two /100 values to a /10 axis (ignoring nulls). */
    private int avgTo10(Integer a, Integer b, int dflt) {
        if (a == null && b == null) return dflt;
        if (a == null) return clamp10(Math.round(b / 10f));
        if (b == null) return clamp10(Math.round(a / 10f));
        return clamp10(Math.round((a + b) / 20f));
    }

    private int clamp10(int v) { return Math.max(0, Math.min(10, v)); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String nz(Integer i) { return i == null ? "?" : i.toString(); }
}
