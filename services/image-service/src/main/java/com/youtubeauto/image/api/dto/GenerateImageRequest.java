package com.youtubeauto.image.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.util.List;
import java.util.UUID;

public record GenerateImageRequest(
        @NotNull UUID jobId,
        @NotEmpty @Valid List<SceneVisual> scenes,
        /** "landscape" (default) or "vertical" — drives provider-specific sizing. */
        @Pattern(regexp = "landscape|vertical")
        String format
) {
    public record SceneVisual(
            @Min(1) int seq,
            @NotBlank String visualDesc,
            List<String> characters,
            String locationId,
            /** Time-of-day id (dawn|morning|midday|goldenHour|dusk|night)
             *  matching bible.timeOfDay. */
            String timeOfDay,
            /** Weather id (sunny|cloudy|lightRain|afterRain|windy|snowy). */
            String weather,
            /** Camera framing hint: "wide establishing shot", "extreme close-up",
             *  "medium shot at eye level", "low-angle hero shot", etc. */
            String cameraFraming,
            /** Optional recurring-prop reference anchors that lock a prop's colour
             *  + design across scenes (mirrors the cast anchors). May be null. */
            List<PropRef> propRefs,
            /** Episode-ConsistencyState (Story B): QC-APPROVED stills from EARLIER
             *  IN THIS SAME EPISODE, selected by the orchestrator per character
             *  (least-occluded approved scene, hero phases preferred). Anchor-
             *  capable providers attach them as extra reference images NEXT TO the
             *  bible refs, so a re-rolled scene matches how THIS episode already
             *  drew the cast — not just the bible's version of the character.
             *  May be null (first generation batch / no canon yet). */
            List<EpisodeAnchor> episodeAnchors
    ) {
        /** A recurring prop's reference image: a name + a readable PNG path. */
        public record PropRef(String name, String imagePath) {}

        /** One character's canon exemplar: the bible character id + a readable
         *  PNG path of an approved still. {@code source} (additive, optional)
         *  says where the still comes from so providers can phrase the match
         *  instruction honestly: {@code "episode"} (default when null/unknown —
         *  a QC-approved still from earlier in this same episode) or
         *  {@code "series"} (the character's promoted anchor from the previous
         *  human-approved episode of the same series). */
        public record EpisodeAnchor(String characterId, String imagePath, String source) {
            /** Legacy two-arg form — source defaults to the episode itself. */
            public EpisodeAnchor(String characterId, String imagePath) {
                this(characterId, imagePath, null);
            }
            /** True when this anchor was promoted from a previous episode of the series. */
            public boolean fromSeries() { return "series".equalsIgnoreCase(source); }
        }

        public SceneVisual(int seq, String visualDesc, List<String> characters, String locationId) {
            this(seq, visualDesc, characters, locationId, null, null, null, null, null);
        }
        public SceneVisual(int seq, String visualDesc, List<String> characters, String locationId,
                           String timeOfDay, String weather, String cameraFraming) {
            this(seq, visualDesc, characters, locationId, timeOfDay, weather, cameraFraming, null, null);
        }
    }

    public String formatOrDefault() {
        return format == null || format.isBlank() ? "landscape" : format;
    }
}
