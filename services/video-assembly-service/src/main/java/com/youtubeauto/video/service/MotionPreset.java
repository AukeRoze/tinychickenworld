package com.youtubeauto.video.service;

/**
 * Camera motion presets applied per scene. Picked randomly so two consecutive
 * videos cannot share the "every scene slow-zooms-in" AI-farm signature.
 *
 * Zoom levels kept gentle (≤1.08) so the Ken Burns move stays VISIBLE without
 * cropping the subject — a strong 1.30 zoom cut ~23% off the frame on top of
 * the cover/blurred-fill base and chopped feet/heads. Dialled back further from
 * 1.12 → 1.08 (~8% max overscan) so feet/heads near the safe-area edge survive
 * the move. Combined with the blurred-fill base in SceneClipBuilder (no
 * aspect-crop) and centered zoom origins, the subject stays in frame. STATIC
 * removed from rotation.
 */
public enum MotionPreset {

    /** Strong continuous zoom-in. */
    ZOOM_IN,

    /** Strong continuous zoom-out — starts framed in, opens up. */
    ZOOM_OUT,

    /** Image static at zoom=1.3, virtual camera pans right (image shifts left). */
    PAN_LEFT,

    /** Image static at zoom=1.3, virtual camera pans left (image shifts right). */
    PAN_RIGHT,

    /** Subtle zoom-in combined with a slow diagonal pan. */
    ZOOM_PAN_DIAGONAL,

    /** Zoom-in WHILE panning right — most dynamic. */
    ZOOM_IN_PAN_RIGHT;

    public String filterChain(int frames, int w, int h, int fps) {
        return switch (this) {
            case ZOOM_IN -> String.format(
                    "zoompan=z='min(1.0+0.08*on/%d,1.08)':d=%d:s=%dx%d:fps=%d,format=yuv420p",
                    frames, frames, w, h, fps);

            case ZOOM_OUT -> String.format(
                    "zoompan=z='max(1.08-0.08*on/%d,1.0)':d=%d:s=%dx%d:fps=%d,format=yuv420p",
                    frames, frames, w, h, fps);

            case PAN_LEFT -> String.format(
                    "zoompan=z=1.08:x='(iw-iw/zoom)*(1-on/%d)':y='(ih-ih/zoom)/2'" +
                    ":d=%d:s=%dx%d:fps=%d,format=yuv420p",
                    frames, frames, w, h, fps);

            case PAN_RIGHT -> String.format(
                    "zoompan=z=1.08:x='(iw-iw/zoom)*on/%d':y='(ih-ih/zoom)/2'" +
                    ":d=%d:s=%dx%d:fps=%d,format=yuv420p",
                    frames, frames, w, h, fps);

            case ZOOM_PAN_DIAGONAL -> String.format(
                    "zoompan=z='min(1.0+0.07*on/%d,1.07)':x='(iw-iw/zoom)*on/%d'" +
                    ":y='(ih-ih/zoom)*0.5*on/%d':d=%d:s=%dx%d:fps=%d,format=yuv420p",
                    frames, frames, frames, frames, w, h, fps);

            case ZOOM_IN_PAN_RIGHT -> String.format(
                    "zoompan=z='min(1.0+0.08*on/%d,1.08)':x='(iw-iw/zoom)*on/%d'" +
                    ":y='(ih-ih/zoom)/2':d=%d:s=%dx%d:fps=%d,format=yuv420p",
                    frames, frames, frames, w, h, fps);
        };
    }
}
