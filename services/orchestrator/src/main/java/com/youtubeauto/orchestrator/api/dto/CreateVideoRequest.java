package com.youtubeauto.orchestrator.api.dto;

import jakarta.validation.constraints.*;

/**
 * A video request. The minimum is {@code topic}; everything else is optional.
 * The optional fields let you give Claude a clear creative brief instead of
 * a one-line prompt:
 *
 * <pre>
 * {
 *   "topic": "How rainbows are made",
 *   "brief": "Pip sees one for the first time after a rainstorm. Mo explains it
 *             as sunlight bending through water drops, using a tea-steam comparison.
 *             Bo turns RAIN-BOW into a BRAIN-BOW joke.",
 *   "lesson": "Rainbows happen when sunlight passes through water droplets.",
 *   "mood":   "Wonder, gentle science discovery, warmth",
 *   "angle":  "Pip the curious discoverer; Mo the calm explainer; Bo the comic relief",
 *   "motionMode": "veo",
 *   "targetSeconds": 60
 * }
 * </pre>
 */
public record CreateVideoRequest(
        @NotBlank String topic,
        /** Free-form creative brief — set the narrative, the moments you want
         *  to land, character beats. Claude will treat this as priority. */
        String brief,
        /** What the viewer should walk away knowing. One sentence. */
        String lesson,
        /** Emotional tone in two-three words: "calm wonder", "silly chaotic",
         *  "cozy bedtime", "energetic discovery". */
        String mood,
        /** Narrative angle: who drives the story, what role each character
         *  plays in this particular episode. */
        String angle,
        /** Concrete HOOK seed (0-8s) for the channel's enforced episode
         *  structure. Strong emotion + question/mystery. Example:
         *  "Pip's eyes go wide. She hears a strange humming from outside.
         *   Whispers: 'What IS that?'". Optional; if blank Claude derives
         *  one from the brief. */
        String hook,
        /** Copy scene-images from this previous job instead of regenerating
         *  them. Saves €€ when iterating on script/voice/Veo for the same
         *  visual set. Voice still generates fresh. */
        java.util.UUID reuseImagesFromJob,
        String audience,
        Integer targetSeconds,
        Boolean burnSubtitles,
        String backgroundMusicPath,
        String privacyStatus, // "private" | "unlisted" | "public"
        String format,        // "landscape" (default) | "vertical" (Shorts)
        String motionMode,    // "ken_burns" (default, cheap) | "hybrid" | "veo"
        /** Veo model for ALL Veo scenes in this job: veo3_1_lite (~€0.05/s),
         *  veo3_1_fast (~€0.10/s), veo3_1 (~€0.40/s, 1080p hero quality).
         *  Blank = auto (bible routing per sceneType). Applies to hybrid + veo. */
        String veoModel,
        /** Scheduled YouTube publish date. If in the future, upload uses
         *  YouTube's scheduled-publish (private until this moment). */
        java.time.OffsetDateTime plannedPublishAt,
        /** Series this episode belongs to (eg "pips-first-year"). Used for
         *  grouping in the planning views. */
        String seriesId,
        /** Episode number within the series. */
        Integer episodeNumber,
        /** Optional recurring visual motif for THIS episode only (e.g.
         *  "decorated pastel eggs", "paper lanterns"). Injected into every
         *  scene image prompt. Leave blank for no motif — the decorated eggs
         *  are no longer a channel-wide default, so set this when you actually
         *  want a recurring prop. */
        String recurringMotif
) {}
