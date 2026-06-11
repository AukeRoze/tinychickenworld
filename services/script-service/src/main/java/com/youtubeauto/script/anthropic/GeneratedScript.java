package com.youtubeauto.script.anthropic;

import java.util.List;

/**
 * Matches {@link ScriptTool#schema}.
 *
 * Scene-level shape now carries character + location context so downstream
 * services (image, voice) can do per-character work.
 */
public record GeneratedScript(
        String title,
        String hook,
        String cta,
        List<Scene> scenes
) {
    public record Scene(
            int seq,
            List<Line> lines,
            String visualDesc,
            List<String> characters,
            String locationId,
            /** Episode-structure phase id (hook, setup, development, climax,
             *  resolution, closer). Optional — null on older scripts. */
            String phase,
            /** Time-of-day mood id from the bible (goldenHour, midday, dusk, night). */
            String timeOfDay,
            /** Optional weather mood id from the bible (clear, lightRain, …). */
            String weather,
            /** Shot-DNA: the shot's purpose in one phrase. */
            String goal,
            /** Shot-DNA: primary emotion + intensity (e.g. "wonder (5/5)"). */
            String emotion,
            /** Shot-DNA: pace — slow | natural | quick. */
            String motionSpeed,
            /** Shot-DNA: character's end-of-shot pose, for last-frame generation. */
            String endPose,
            /** Motion brief for hero (hook/climax) scenes — the start→end MOVEMENT
             *  for Veo (camera + character action + ambient). Empty otherwise;
             *  visualDesc stays a still description. */
            String motionDesc,
            int durationSeconds
    ) {}

    /** A single spoken line. speaker = character id from the bible. */
    public record Line(String speaker, String text) {}
}
