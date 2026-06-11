package com.youtubeauto.orchestrator.service;

/**
 * Output format the user requested. Orchestrator maps this to concrete
 * dimensions and image-provider sizes before dispatching to services.
 */
public enum VideoFormat {

    LANDSCAPE(1920, 1080, "landscape", 600),
    VERTICAL(1080, 1920, "vertical", 60);

    public final int videoWidth;
    public final int videoHeight;
    public final String imageFormat;
    public final int maxSeconds;

    VideoFormat(int w, int h, String imgFmt, int maxSec) {
        this.videoWidth = w;
        this.videoHeight = h;
        this.imageFormat = imgFmt;
        this.maxSeconds = maxSec;
    }

    public static VideoFormat parse(String raw) {
        if (raw == null || raw.isBlank() || raw.equalsIgnoreCase("landscape")) return LANDSCAPE;
        if (raw.equalsIgnoreCase("vertical") || raw.equalsIgnoreCase("shorts")) return VERTICAL;
        throw new IllegalArgumentException("Unknown format: " + raw + " (expected landscape|vertical)");
    }

    public boolean isVertical() { return this == VERTICAL; }
}
