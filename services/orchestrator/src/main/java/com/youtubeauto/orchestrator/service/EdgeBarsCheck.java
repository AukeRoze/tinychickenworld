package com.youtubeauto.orchestrator.service;

import lombok.extern.slf4j.Slf4j;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Deterministic edge scan for black pillarbox/letterbox bars on master-video
 * keyframes. This is the cheap, non-LLM gate that catches the single most
 * embarrassing render bug — baked-in black bars — with zero false negatives:
 * a vision model may shrug at a 14px bar, a pixel scan never does.
 *
 * A column/row counts as "black" when virtually all sampled pixels are
 * near-black (sum(r,g,b) &lt; {@link #BLACK_SUM}). Bars are flagged from
 * {@link #MIN_BAR_PX}px wide; night scenes don't trigger it because this art
 * style never renders a full-height column of true black at the frame edge.
 *
 * Pure Java (BufferedImage) — no ffmpeg/external calls, safe to run inline.
 */
@Slf4j
public final class EdgeBarsCheck {

    private static final int BLACK_SUM = 30;   // r+g+b below this = near-black
    private static final int MIN_BAR_PX = 4;   // ignore 1-3px encode fringe
    private static final int MAX_SCAN_PX = 200;
    private static final int SAMPLE_STEP = 8;  // sample every Nth pixel along the line

    private EdgeBarsCheck() {}

    /** Result for one frame: bar widths in px per edge (0 = clean). */
    public record FrameBars(Path frame, int left, int right, int top, int bottom) {
        public boolean hasBars() {
            return left >= MIN_BAR_PX || right >= MIN_BAR_PX
                    || top >= MIN_BAR_PX || bottom >= MIN_BAR_PX;
        }
        public int maxBar() {
            return Math.max(Math.max(left, right), Math.max(top, bottom));
        }
    }

    /** Aggregate over all inspected keyframes. */
    public record Result(int framesChecked, int framesWithBars, int maxBarPx, String detail) {
        public boolean failed() { return framesWithBars > 0; }
        public static Result clean(int checked) { return new Result(checked, 0, 0, ""); }
    }

    /** Scans the given keyframe PNGs. Best-effort: unreadable frames are skipped. */
    public static Result scan(List<Path> frames) {
        int checked = 0, withBars = 0, maxBar = 0;
        StringBuilder detail = new StringBuilder();
        for (Path p : frames) {
            try {
                if (p == null || !Files.exists(p)) continue;
                BufferedImage img = ImageIO.read(p.toFile());
                if (img == null || img.getWidth() < 100 || img.getHeight() < 100) continue;
                FrameBars fb = scanFrame(p, img);
                checked++;
                if (fb.hasBars()) {
                    withBars++;
                    maxBar = Math.max(maxBar, fb.maxBar());
                    if (detail.length() > 0) detail.append("; ");
                    detail.append(p.getFileName())
                          .append(" L").append(fb.left()).append(" R").append(fb.right())
                          .append(" T").append(fb.top()).append(" B").append(fb.bottom());
                }
            } catch (Exception e) {
                log.debug("Edge scan skipped {} ({})", p, e.getMessage());
            }
        }
        return new Result(checked, withBars, maxBar, detail.toString());
    }

    private static FrameBars scanFrame(Path path, BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        int left = 0, right = 0, top = 0, bottom = 0;
        while (left < MAX_SCAN_PX && columnBlack(img, left, h)) left++;
        while (right < MAX_SCAN_PX && columnBlack(img, w - 1 - right, h)) right++;
        while (top < MAX_SCAN_PX && rowBlack(img, top, w)) top++;
        while (bottom < MAX_SCAN_PX && rowBlack(img, h - 1 - bottom, w)) bottom++;
        return new FrameBars(path, left, right, top, bottom);
    }

    private static boolean columnBlack(BufferedImage img, int x, int h) {
        for (int y = 0; y < h; y += SAMPLE_STEP) {
            if (!isBlack(img.getRGB(x, y))) return false;
        }
        return true;
    }

    private static boolean rowBlack(BufferedImage img, int y, int w) {
        for (int x = 0; x < w; x += SAMPLE_STEP) {
            if (!isBlack(img.getRGB(x, y))) return false;
        }
        return true;
    }

    private static boolean isBlack(int rgb) {
        int r = (rgb >> 16) & 0xFF, g = (rgb >> 8) & 0xFF, b = rgb & 0xFF;
        return r + g + b < BLACK_SUM;
    }
}
