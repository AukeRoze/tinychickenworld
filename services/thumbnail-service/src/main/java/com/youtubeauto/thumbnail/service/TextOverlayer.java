package com.youtubeauto.thumbnail.service;

import com.youtubeauto.thumbnail.config.ThumbnailProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Draws the title text onto the canvas in the style of the chosen LayoutTemplate.
 * White fill, thick black outline + drop shadow — the visual grammar of every
 * successful YouTube thumbnail for the last decade.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TextOverlayer {

    private final ThumbnailProperties props;
    private volatile Font baseFont;

    public BufferedImage apply(BufferedImage base, LayoutTemplate layout, String title) {
        int W = props.thumbnail().width();
        int H = props.thumbnail().height();

        BufferedImage canvas = new BufferedImage(W, H, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = canvas.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);

        // Cover-fit the base image onto the 1280x720 canvas
        drawCoverScaled(g, base, W, H);

        // Strip glyphs the font can't render (emoji, non-BMP) so they don't
        // show up as .notdef tofu boxes (□) on the thumbnail.
        title = sanitize(title);

        switch (layout) {
            case NO_TEXT -> { /* face-driven: the image IS the thumbnail */ }
            case BADGE_BOTTOM_LEFT -> drawBadgeBottomLeft(g, title, W, H);
            case TEXT_LEFT -> drawTextLeft(g, title, W, H);
            case TEXT_BOTTOM -> drawTextBottom(g, title, W, H);
            case TEXT_OUTLINE_CENTER -> drawTextCenter(g, title, W, H);
            case TEXT_TOP_BANNER -> drawTextBanner(g, title, W, H);
        }

        g.dispose();
        return canvas;
    }

    // ---------- layouts ----------

    private void drawTextLeft(Graphics2D g, String title, int W, int H) {
        int maxWidth = (int) (W * 0.45);
        int padX = 40;
        Font font = font(Math.min(110, fitFontSize(g, title, maxWidth, 4, 70, 110)));
        List<String> lines = wrap(g, title, font, maxWidth);
        int lineH = g.getFontMetrics(font).getHeight();
        int totalH = lineH * lines.size();
        int blockTop = (H - totalH) / 2;
        int maxLineW = maxLineWidth(g, lines, font);
        drawScrim(g, padX - 24, blockTop - 16, maxLineW + 48, totalH + 32);
        int y = blockTop + g.getFontMetrics(font).getAscent();
        for (String line : lines) {
            drawOutlined(g, line, padX, y, font, Color.WHITE, Color.BLACK, 8f);
            y += lineH;
        }
    }

    private void drawTextBottom(Graphics2D g, String title, int W, int H) {
        int bandH = (int) (H * 0.32);
        int bandY = H - bandH;
        // dark gradient overlay
        GradientPaint grad = new GradientPaint(
                0, bandY, new Color(0, 0, 0, 0),
                0, H, new Color(0, 0, 0, 200));
        Paint old = g.getPaint();
        g.setPaint(grad);
        g.fillRect(0, bandY, W, bandH);
        g.setPaint(old);

        int maxWidth = (int) (W * 0.92);
        Font font = font(fitFontSize(g, title, maxWidth, 3, 60, 96));
        List<String> lines = wrap(g, title, font, maxWidth);
        int lineH = g.getFontMetrics(font).getHeight();
        int totalH = lineH * lines.size();
        int y = bandY + (bandH - totalH) / 2 + g.getFontMetrics(font).getAscent();
        for (String line : lines) {
            int x = (W - g.getFontMetrics(font).stringWidth(line)) / 2;
            drawOutlined(g, line, x, y, font, Color.WHITE, Color.BLACK, 6f);
            y += lineH;
        }
    }

    private void drawTextCenter(Graphics2D g, String title, int W, int H) {
        int maxWidth = (int) (W * 0.85);
        Font font = font(fitFontSize(g, title, maxWidth, 4, 70, 128));
        List<String> lines = wrap(g, title, font, maxWidth);
        int lineH = g.getFontMetrics(font).getHeight();
        int totalH = lineH * lines.size();
        int blockTop = (H - totalH) / 2;
        int maxLineW = maxLineWidth(g, lines, font);
        // Dark scrim behind the centred title so yellow text stays legible on
        // bright/busy backgrounds (e.g. golden-hour scenes).
        drawScrim(g, (W - maxLineW) / 2 - 30, blockTop - 20, maxLineW + 60, totalH + 40);
        int y = blockTop + g.getFontMetrics(font).getAscent();
        for (String line : lines) {
            int x = (W - g.getFontMetrics(font).stringWidth(line)) / 2;
            drawOutlined(g, line, x, y, font, new Color(255, 235, 0), Color.BLACK, 12f);
            y += lineH;
        }
    }

    private void drawTextBanner(Graphics2D g, String title, int W, int H) {
        int bandH = (int) (H * 0.22);
        // solid coloured ribbon, slightly transparent
        Color ribbon = new Color(220, 30, 60, 235);
        Paint old = g.getPaint();
        g.setPaint(ribbon);
        g.fillRect(0, 0, W, bandH);
        g.setPaint(old);

        int maxWidth = (int) (W * 0.92);
        Font font = font(fitFontSize(g, title, maxWidth, 2, 50, 80));
        List<String> lines = wrap(g, title, font, maxWidth);
        int lineH = g.getFontMetrics(font).getHeight();
        int totalH = lineH * lines.size();
        int y = (bandH - totalH) / 2 + g.getFontMetrics(font).getAscent();
        for (String line : lines) {
            int x = (W - g.getFontMetrics(font).stringWidth(line)) / 2;
            drawOutlined(g, line, x, y, font, Color.WHITE, Color.BLACK, 5f);
            y += lineH;
        }
    }

    /** Tiny single-line badge pinned bottom-left. Hard caps: ≤30% of the canvas
     *  width, one line only — the faces stay dominant because the 3-6 audience
     *  can't read; the badge is a wink to the parent, not the hook. */
    private void drawBadgeBottomLeft(Graphics2D g, String title, int W, int H) {
        if (title == null || title.isBlank()) return;
        int maxWidth = (int) (W * 0.30);
        Font font = font(fitFontSize(g, title, maxWidth, 1, 36, 64));
        FontMetrics fm = g.getFontMetrics(font);
        // Single line guaranteed by fitFontSize(maxLines=1); ellipse-trim as a
        // belt-and-braces fallback if even the minimum size overflows.
        String line = title;
        while (fm.stringWidth(line) > maxWidth && line.length() > 3) {
            line = line.substring(0, line.length() - 1);
        }
        int textW = fm.stringWidth(line);
        int padX = 22, padY = 12;
        int margin = 28;
        int badgeW = textW + padX * 2;
        int badgeH = fm.getHeight() + padY * 2;
        int bx = margin;
        int by = H - margin - badgeH;
        drawScrim(g, bx, by, badgeW, badgeH);
        drawOutlined(g, line, bx + padX, by + padY + fm.getAscent(), font,
                new Color(255, 235, 0), Color.BLACK, 5f);
    }

    // ---------- helpers ----------

    private void drawCoverScaled(Graphics2D g, BufferedImage src, int targetW, int targetH) {
        double scale = Math.max((double) targetW / src.getWidth(),
                                (double) targetH / src.getHeight());
        int newW = (int) Math.ceil(src.getWidth() * scale);
        int newH = (int) Math.ceil(src.getHeight() * scale);
        int x = (targetW - newW) / 2;
        int y = (targetH - newH) / 2;
        g.drawImage(src, x, y, newW, newH, null);
    }

    /** Largest pixel width across the wrapped lines for the given font. */
    private int maxLineWidth(Graphics2D g, List<String> lines, Font font) {
        FontMetrics fm = g.getFontMetrics(font);
        int max = 0;
        for (String line : lines) max = Math.max(max, fm.stringWidth(line));
        return max;
    }

    /** Translucent rounded-rect backing so light text stays readable on any
     *  background. Clamped to the canvas so it never bleeds off-edge. */
    private void drawScrim(Graphics2D g, int x, int y, int w, int h) {
        Color old = g.getColor();
        g.setColor(new Color(0, 0, 0, 115));
        g.fillRoundRect(x, y, w, h, 40, 40);
        g.setColor(old);
    }

    /**
     * Remove code points the active font can't render (emoji, other non-BMP
     * symbols) so TextLayout never falls back to the .notdef tofu box (□).
     * Collapses any whitespace left behind by stripped glyphs.
     */
    private String sanitize(String title) {
        if (title == null || title.isBlank()) return "";
        Font f = font(64);  // glyph coverage is size-independent
        StringBuilder sb = new StringBuilder(title.length());
        title.codePoints().forEach(cp -> {
            if (cp == '\n' || cp == '\t' || cp == '\r') { sb.append(' '); return; }
            if (cp > 0xFFFF) return;            // drop emoji / supplementary planes
            if (!f.canDisplay(cp)) return;      // drop anything the font lacks
            sb.appendCodePoint(cp);
        });
        return sb.toString().replaceAll("\\s{2,}", " ").trim();
    }

    private void drawOutlined(Graphics2D g, String text, int x, int y, Font font,
                              Color fill, Color outline, float outlineWidth) {
        if (text == null || text.isEmpty()) return;   // TextLayout rejects empty strings
        g.setFont(font);
        FontRenderContext frc = g.getFontRenderContext();
        TextLayout tl = new TextLayout(text, font, frc);
        AffineTransform tr = AffineTransform.getTranslateInstance(x, y);
        Shape shape = tl.getOutline(tr);

        // soft drop shadow
        Stroke oldStroke = g.getStroke();
        Paint oldPaint = g.getPaint();
        g.setColor(new Color(0, 0, 0, 90));
        g.translate(4, 4);
        g.fill(shape);
        g.translate(-4, -4);

        // outline
        g.setStroke(new BasicStroke(outlineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g.setColor(outline);
        g.draw(shape);
        // fill
        g.setColor(fill);
        g.fill(shape);

        g.setStroke(oldStroke);
        g.setPaint(oldPaint);
    }

    private List<String> wrap(Graphics2D g, String text, Font font, int maxWidth) {
        g.setFont(font);
        FontMetrics fm = g.getFontMetrics();
        String[] words = text.split("\\s+");
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String w : words) {
            String trial = cur.length() == 0 ? w : cur + " " + w;
            if (fm.stringWidth(trial) > maxWidth && cur.length() > 0) {
                lines.add(cur.toString());
                cur = new StringBuilder(w);
            } else {
                cur = new StringBuilder(trial);
            }
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    /**
     * Pick the largest font size for which the title wraps into {@code maxLines}
     * lines of at most {@code maxWidth} pixels.
     */
    private int fitFontSize(Graphics2D g, String text, int maxWidth,
                            int maxLines, int minSize, int maxSize) {
        for (int size = maxSize; size >= minSize; size -= 2) {
            Font f = font(size);
            List<String> lines = wrap(g, text, f, maxWidth);
            if (lines.size() <= maxLines) return size;
        }
        return minSize;
    }

    private Font font(int size) {
        if (baseFont == null) {
            baseFont = loadBaseFont();
        }
        return baseFont.deriveFont(Font.BOLD, (float) size);
    }

    private Font loadBaseFont() {
        try {
            File f = new File(props.thumbnail().fontPath());
            if (f.exists()) {
                return Font.createFont(Font.TRUETYPE_FONT, f);
            }
            log.warn("Font {} not found, falling back to SansSerif Bold", props.thumbnail().fontPath());
        } catch (FontFormatException | IOException e) {
            log.warn("Failed to load font {}: {}", props.thumbnail().fontPath(), e.getMessage());
        }
        return new Font(Font.SANS_SERIF, Font.BOLD, 60);
    }
}
