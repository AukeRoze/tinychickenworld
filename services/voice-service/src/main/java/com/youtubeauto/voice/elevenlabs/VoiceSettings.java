package com.youtubeauto.voice.elevenlabs;

/**
 * The ElevenLabs {@code voice_settings} payload for ONE synthesis call.
 *
 * Two things drive a line's delivery:
 *   1. the PER-CHARACTER base (bible characters[].voiceSettings) — Pip is loose
 *      and excitable (low stability, high style), Mo is calm and steady (high
 *      stability, low style), so the cast is auditively distinct; and
 *   2. the PER-LINE emotion ({@code Line.emotion} from the script), applied on
 *      top via {@link #withEmotion(String)} so the SAME character delivers an
 *      excited line and a tender line differently — i.e. the voice acts.
 *
 * ElevenLabs semantics: lower {@code stability} = more emotional swing/variation;
 * higher {@code style} = more expressive/exaggerated delivery; {@code similarityBoost}
 * sticks to the cloned timbre; {@code useSpeakerBoost} firms up the likeness.
 * All 0..1 fields are clamped so a modulation can never send an out-of-range value.
 */
public record VoiceSettings(double stability, double similarityBoost,
                            double style, boolean useSpeakerBoost) {

    public VoiceSettings {
        stability = clamp(stability);
        similarityBoost = clamp(similarityBoost);
        style = clamp(style);
    }

    private static double clamp(double v) { return Math.max(0.0, Math.min(1.0, v)); }

    /**
     * Returns a copy of these (per-character) settings nudged for the given
     * line emotion. Free-text emotions are matched by keyword to a small set of
     * arousal/valence buckets; an unknown or blank emotion leaves the base
     * untouched. The character's own personality (the base) is preserved — the
     * emotion only shifts it, so an "excited" Mo is still calmer than an
     * "excited" Pip.
     */
    public VoiceSettings withEmotion(String emotion) {
        if (emotion == null || emotion.isBlank()) return this;
        double[] d = delta(emotion.toLowerCase());
        if (d[0] == 0.0 && d[1] == 0.0) return this;
        // Shot-DNA intensity "(n/5)" scales the nudge: a 'gentle joy (1/5)'
        // barely moves the dial, an 'amazed (5/5)' swings it hard. Without an
        // intensity marker the full delta applies (legacy behaviour).
        double f = intensityFactor(emotion);
        return new VoiceSettings(stability + d[0] * f, similarityBoost,
                style + d[1] * f, useSpeakerBoost);
    }

    /** Parses "(n/5)" → scale factor 0.5..1.4 (n=3 ≈ 1.0); 1.0 when absent. */
    private static double intensityFactor(String emotion) {
        var m = java.util.regex.Pattern.compile("\\((\\d)\\s*/\\s*5\\)").matcher(emotion);
        if (!m.find()) return 1.0;
        int n = Math.max(1, Math.min(5, Integer.parseInt(m.group(1))));
        return 0.5 + 0.225 * (n - 1);   // 1→0.5, 3→0.95, 5→1.4
    }

    /** @return {stabilityDelta, styleDelta} for an emotion keyword. */
    private static double[] delta(String e) {
        // High-arousal positive — more swing, more expressive.
        if (containsAny(e, "excit", "surpris", "joy", "happy", "delight", "playful", "silly",
                "giggl", "amaz", "wow", "eager", "thrill", "cheer", "mischiev", "triumph"))
            return new double[]{-0.12, +0.15};
        // Fear / startle — lots of swing, expressive, but kept child-safe upstream.
        if (containsAny(e, "scared", "afraid", "fright", "nervous", "startl", "anxious", "panic"))
            return new double[]{-0.10, +0.12};
        // Curiosity / wonder — a little more life.
        if (containsAny(e, "curio", "wonder", "intrigu", "question", "puzzl", "interest",
                "confus", "awe", "mystery"))
            return new double[]{-0.05, +0.08};
        // Pride / confidence — expressive but steady.
        if (containsAny(e, "proud", "confiden", "brave", "determin"))
            return new double[]{-0.03, +0.10};
        // Tender / calm — steadier, softer, less exaggerated.
        if (containsAny(e, "calm", "gentle", "warm", "tender", "soft", "sleep", "sooth",
                "content", "relax", "relie", "cozy", "cosy", "tired", "yawn", "peace",
                "dream", "love", "grateful", "thank"))
            return new double[]{+0.10, -0.08};
        // Sad / down — steadier and a touch less expressive (mild for kids).
        if (containsAny(e, "sad", "sorry", "disappoint", "worried", "blue", "lonely", "miss",
                "shy", "embarrass", "sheepish"))
            return new double[]{+0.05, -0.03};
        return new double[]{0.0, 0.0};
    }

    private static boolean containsAny(String hay, String... needles) {
        for (String n : needles) if (hay.contains(n)) return true;
        return false;
    }
}
