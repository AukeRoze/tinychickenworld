package com.youtubeauto.thumbnail.service;

/**
 * Thumbnail composition templates. Each one combines:
 *   - a hint for the OpenAI image prompt (where to leave room for text)
 *   - a different text overlay placement
 *
 * Rotating these is the single biggest fix for the "every video has the same
 * thumbnail composition" AI-farm fingerprint.
 */
public enum LayoutTemplate {

    // ---- FACE-DRIVEN layouts (preferred for the 3-6 audience) ----
    // The target audience CANNOT READ. Browse-CTR in this vertical is driven by
    // big expressive faces and one clear visual mystery — text is at best
    // decoration for the parent, at worst it covers the faces that earn the
    // click. These two layouts keep ≥95% of the canvas for the image.

    /** No text at all — the face and the visual hook ARE the thumbnail. */
    NO_TEXT("Main character's face large and central, filling the frame; " +
            "the episode's surprise/mystery clearly visible in shot."),

    /** Tiny one/two-word badge pinned bottom-left, faces stay dominant. */
    BADGE_BOTTOM_LEFT("Main subject large and central; keep only the bottom-left " +
                      "corner (about 25% width) free of important detail."),

    // ---- legacy text-heavy layouts (kept for A/B and non-kids reuse) ----

    /** Image right-heavy, title big on the left half. */
    TEXT_LEFT("Leave the LEFT 45% of the image relatively empty or simple background; " +
              "main subject on the right side."),

    /** Subject fills the canvas, title sits over a dark gradient at the bottom. */
    TEXT_BOTTOM("Subject fills most of the frame, leave the BOTTOM third slightly less busy."),

    /** Big title centered with thick outline, image as backdrop. */
    TEXT_OUTLINE_CENTER("Bright, vibrant background scene with a strong central focal point but " +
                        "no busy text-like patterns; subject not blocking the center."),

    /** Coloured ribbon across the top with the title; subject below. */
    TEXT_TOP_BANNER("Leave the TOP 20% of the image as plain sky or simple background.");

    public final String promptHint;
    LayoutTemplate(String hint) { this.promptHint = hint; }
}
