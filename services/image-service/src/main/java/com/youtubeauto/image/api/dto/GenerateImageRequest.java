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
            List<PropRef> propRefs
    ) {
        /** A recurring prop's reference image: a name + a readable PNG path. */
        public record PropRef(String name, String imagePath) {}

        public SceneVisual(int seq, String visualDesc, List<String> characters, String locationId) {
            this(seq, visualDesc, characters, locationId, null, null, null, null);
        }
        public SceneVisual(int seq, String visualDesc, List<String> characters, String locationId,
                           String timeOfDay, String weather, String cameraFraming) {
            this(seq, visualDesc, characters, locationId, timeOfDay, weather, cameraFraming, null);
        }
    }

    public String formatOrDefault() {
        return format == null || format.isBlank() ? "landscape" : format;
    }
}
