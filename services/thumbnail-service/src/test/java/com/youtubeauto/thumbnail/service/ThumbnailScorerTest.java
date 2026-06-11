package com.youtubeauto.thumbnail.service;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

class ThumbnailScorerTest {

    @Test
    void score_handlesNull() {
        assertEquals(0.0, ThumbnailScorer.score(null));
    }

    @Test
    void score_rejectsTinyImages() {
        BufferedImage tiny = new BufferedImage(50, 50, BufferedImage.TYPE_INT_RGB);
        assertEquals(0.0, ThumbnailScorer.score(tiny));
    }

    @Test
    void score_punishesPureBlackImage() {
        BufferedImage black = solidColor(400, 300, Color.BLACK);
        double s = ThumbnailScorer.score(black);
        // Pure black: very low brightness, no contrast, no saturation
        assertTrue(s < 30, "Pure black should score low, got " + s);
    }

    @Test
    void score_punishesPureWhiteImage() {
        BufferedImage white = solidColor(400, 300, Color.WHITE);
        double s = ThumbnailScorer.score(white);
        // Pure white: too bright, no contrast
        assertTrue(s < 50, "Pure white should score below mid, got " + s);
    }

    @Test
    void score_rewardsVividHighContrastCentredImage() {
        BufferedImage vivid = new BufferedImage(400, 300, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = vivid.createGraphics();
        // Saturated background
        g.setColor(new Color(60, 130, 200));
        g.fillRect(0, 0, 400, 300);
        // Bright punchy "face" area in centre
        g.setColor(new Color(255, 220, 60));
        g.fillOval(120, 80, 160, 140);
        g.setColor(new Color(40, 20, 0));
        g.fillOval(160, 130, 30, 30);
        g.fillOval(220, 130, 30, 30);
        g.dispose();
        double s = ThumbnailScorer.score(vivid);
        assertTrue(s > 50, "Vivid centred image should score above mid, got " + s);
    }

    @Test
    void score_isBetterThanFlat() {
        BufferedImage flat = solidColor(400, 300, new Color(120, 120, 120));
        BufferedImage punchy = punchyImage(400, 300);
        double flatScore = ThumbnailScorer.score(flat);
        double punchyScore = ThumbnailScorer.score(punchy);
        assertTrue(punchyScore > flatScore,
                "Punchy should beat flat: punchy=" + punchyScore + " flat=" + flatScore);
    }

    private BufferedImage solidColor(int w, int h, Color c) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(c);
        g.fillRect(0, 0, w, h);
        g.dispose();
        return img;
    }

    private BufferedImage punchyImage(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        for (int y = 0; y < h; y += 20) {
            for (int x = 0; x < w; x += 20) {
                int r = (x * 255 / w);
                int gr = ((x + y) % 256);
                int b = (y * 255 / h);
                g.setColor(new Color(r, gr, b));
                g.fillRect(x, y, 20, 20);
            }
        }
        // Bright centre
        g.setColor(new Color(255, 240, 80));
        g.fillOval(w / 2 - 60, h / 2 - 50, 120, 100);
        g.dispose();
        return img;
    }
}
