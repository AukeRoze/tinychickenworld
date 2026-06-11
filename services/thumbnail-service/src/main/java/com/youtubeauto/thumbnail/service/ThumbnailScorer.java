package com.youtubeauto.thumbnail.service;

import lombok.extern.slf4j.Slf4j;

import java.awt.Color;
import java.awt.image.BufferedImage;

/**
 * Heuristic scoring for thumbnail variants. Picks the one that's most
 * likely to score well on YouTube's kids-vertical CTR signals:
 *   - Bright central area (face is centred and well-lit)
 *   - High contrast (stands out in feed thumbnails)
 *   - Color saturation in mid-tones (vivid, not muddy)
 *   - Edge density in the centre 60%% (busy = interesting)
 *
 * Scores are normalised to 0-100. Higher = better.
 */
@Slf4j
public final class ThumbnailScorer {

    private ThumbnailScorer() {}

    public static double score(BufferedImage img) {
        if (img == null) return 0;
        int w = img.getWidth();
        int h = img.getHeight();
        if (w < 100 || h < 100) return 0;

        // Sample on a 32×32 grid to keep this cheap (1024 pixel reads).
        int gw = 32, gh = 32;
        double sumLum = 0, sumSat = 0;
        double sumLumCentre = 0;
        int centreCount = 0;
        double sumContrast = 0;
        int prevLum = -1;

        int centreXMin = (int) (w * 0.20);
        int centreXMax = (int) (w * 0.80);
        int centreYMin = (int) (h * 0.20);
        int centreYMax = (int) (h * 0.80);

        for (int gy = 0; gy < gh; gy++) {
            for (int gx = 0; gx < gw; gx++) {
                int px = (gx * w) / gw;
                int py = (gy * h) / gh;
                int rgb = img.getRGB(px, py);
                Color c = new Color(rgb);
                int r = c.getRed(), g = c.getGreen(), b = c.getBlue();
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                int sat = max == 0 ? 0 : ((max - min) * 255) / max;

                sumLum += lum;
                sumSat += sat;
                if (px >= centreXMin && px <= centreXMax
                        && py >= centreYMin && py <= centreYMax) {
                    sumLumCentre += lum;
                    centreCount++;
                }
                if (prevLum >= 0) sumContrast += Math.abs(lum - prevLum);
                prevLum = lum;
            }
        }

        int n = gw * gh;
        double avgLum    = sumLum    / n;             // 0-255
        double avgSat    = sumSat    / n;             // 0-255
        double avgLumC   = centreCount == 0 ? 0 : sumLumCentre / centreCount;
        double contrast  = sumContrast / (n - 1);     // typically 0-50 for smooth images

        // Score components (each 0-1, then weighted):
        //  - centreBrightness: optimal around 0.5-0.7 luminance
        //  - saturationScore: more sat = more vivid (cap at 200)
        //  - contrastScore: higher = punchier (cap at 60)
        //  - exposureBalance: penalize too-dark or too-burnt overall
        double centreBright = clamp((avgLumC - 50) / 150.0, 0, 1);
        double saturation   = clamp(avgSat / 200.0, 0, 1);
        double contrastN    = clamp(contrast / 60.0, 0, 1);
        double exposure     = 1.0 - Math.abs((avgLum - 130.0) / 130.0);  // peaks at 130/255

        double weighted =
                centreBright * 0.35 +
                saturation   * 0.25 +
                contrastN    * 0.25 +
                exposure     * 0.15;
        double score100 = Math.max(0, Math.min(100, weighted * 100));

        log.debug("score={} centreBright={} sat={} contrast={} exposure={}",
                score100, centreBright, saturation, contrastN, exposure);
        return score100;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
