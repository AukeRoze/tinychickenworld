package com.youtubeauto.script.bible;

import java.util.List;

/**
 * One phase of the hard episode structure (HOOK / SETUP / DEVELOPMENT /
 * CLIMAX / RESOLUTION / CLOSER). Phases are time-boxed and Claude must
 * stay within minScenes..maxScenes for each.
 *
 * sceneType maps to videoGen routing: hero phases get veo3_1 (high quality),
 * standard phases get veo3_1_lite, outro phase gets the outro Veo profile.
 */
public record EpisodePhase(
        String id,
        String label,
        int seconds,
        int minScenes,
        int maxScenes,
        String sceneType,
        List<String> requirements
) {}
