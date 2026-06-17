package com.youtubeauto.script.api.dto;

import java.util.List;

/**
 * A HIGH-LEVEL story treatment for one episode — the front-end review/edit
 * stage that comes BEFORE the full scene-by-scene script. The user tweaks these
 * fields and, on approval, they are folded into the creative brief the scene
 * generator must follow. {@code error} is non-null only when generation failed.
 */
public record TreatmentResponse(
        String logline,
        String theme,
        List<CharacterArc> characterArcs,
        List<Beat> beats,
        String hook,
        String twist,
        String lessonPayoff,
        String error
) {
    public record CharacterArc(String character, String arc) {}
    public record Beat(String name, String what, String emotion) {}
}
