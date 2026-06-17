package com.youtubeauto.script.bible;

import java.util.List;

/**
 * The channel-wide retention template. Every script must follow this
 * structure regardless of which story arc is chosen. The HOOK phase
 * (first 8 seconds) is what protects watch-time on the YT kids algorithm.
 */
public record EpisodeStructure(
        int totalSecondsTarget,
        List<EpisodePhase> phases,
        int rehookEverySeconds,
        int minScenesTotal,
        int maxScenesTotal,
        /** When true the WHOLE episode must play in ONE location (bible
         *  rules.singleLocation). Drives both the prompt guidance and the
         *  StructureValidator. Default false keeps the legacy variety rule. */
        boolean singleLocation
) {
    public static EpisodeStructure empty() {
        return new EpisodeStructure(75, List.of(), 12, 12, 18, false);
    }
}
