package com.youtubeauto.script.bible;

import java.util.Map;

/**
 * Serie-mythologie uit de bible ({@code seriesMythology}): het vaste
 * openingsritueel + één running gag per personage. Maakt afleveringen
 * herkenbaar en "verzamelbaar" (Bluey/Pocoyo-principe). Gerenderd door
 * {@link com.youtubeauto.script.service.PromptBuilder} als een eigen
 * SERIES MYTHOLOGY-sectie in de system-prompt.
 *
 * @param openingRitual vast ritueel voor de eerste hook-scène (leeg = geen)
 * @param runningGags   characterId → gag-omschrijving (insertion-ordered)
 */
public record SeriesMythology(
        String openingRitual,
        Map<String, String> runningGags
) {
    public static SeriesMythology empty() {
        return new SeriesMythology("", Map.of());
    }

    public boolean isEmpty() {
        return (openingRitual == null || openingRitual.isBlank())
                && (runningGags == null || runningGags.isEmpty());
    }
}
