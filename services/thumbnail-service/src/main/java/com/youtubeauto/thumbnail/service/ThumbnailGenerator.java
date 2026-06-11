package com.youtubeauto.thumbnail.service;

import com.youtubeauto.thumbnail.api.dto.GenerateThumbnailRequest;
import com.youtubeauto.thumbnail.api.dto.GenerateThumbnailResponse;
import com.youtubeauto.thumbnail.config.ThumbnailProperties;
import com.youtubeauto.thumbnail.openai.OpenAiImageClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailGenerator {

    // Modern kids-YouTube thumbnail directives. Big eyes + extreme close-up
    // + bright pop colors are the proven CTR drivers in the kids vertical
    // (Bluey, Cocomelon, Steve & Maggie all do this).
    private static final String PROMPT_POLICY =
            "THUMBNAIL DESIGNED FOR MAXIMUM CLICK-THROUGH (not realism). ONE clear "
            + "main character filling 60-80% of the frame, pushed up close to the "
            + "camera. EXTREME facial expression with a strong emotional reaction "
            + "(shock, awe, fear, disbelief, or delight) — oversized eyes wide open "
            + "with big round pupils and bright shine highlights, beak open mid-gasp, "
            + "SHARP focus on the eyes and face. Compose it as CHARACTER + PROBLEM + "
            + "EMOTION: the character reacting to this episode's surprise / mystery / "
            + "unexpected discovery / reward / funny failure (drawn from the topic "
            + "above), so the visual story is clear in UNDER ONE SECOND and makes the "
            + "viewer curious. Bright, saturated colours; high-contrast cinematic "
            + "lighting with a strong rim light; a CLEAN, simple background with "
            + "minimal distractions; big bold shapes, large elements, NO small "
            + "fiddly details. Must pop at small phone size. "
            + "No text, no letters, no numbers, no logos, no watermarks, no UI. "
            // Terminal locks — gpt-image-1 weights later tokens more heavily,
            // so repeat the two things that drift most: exact character and
            // 3D style. Without this the face drifts generic + painterly.
            + "The character MUST keep the exact colours and signature "
            + "accessories from the description above (scarf / straw hat / "
            + "round eyeglasses as applicable) and newly-hatched baby-chick "
            + "proportions with an oversized head — never a generic or adult "
            + "chicken. Rendered as glossy 3D Pixar / Illumination CGI "
            + "animation — absolutely NOT watercolor, NOT painterly, NOT a "
            + "2D flat or storybook illustration. ";

    // Group hero-shot policy — used when the title/topic references 2+ cast
    // members (e.g. "Meet Pip, Mo & Bo"). Shows the whole cast instead of a
    // single-character close-up.
    private static final String CAST_PROMPT_POLICY =
            "GROUP HERO SHOT of the whole cast together. The chicks fill the "
            + "FOREGROUND, pushed right up close to the camera and taking up most "
            + "of the frame; keep the background simple, soft and uncluttered so the "
            + "characters pop (no busy scenery). Keep only the bottom-left corner "
            + "free of important detail (a small badge may sit there). "
            + "Every character's face is large and clearly visible, each DISTINCT with their own "
            + "signature colours and accessories (never duplicated, never blended "
            + "into each other). Oversized expressive eyes with bright shine "
            + "highlights, open beaks mid-reaction, warm joyful energy. Strong "
            + "colour saturation, vivid warm palette, high contrast with clear rim "
            + "light. Must read clearly even at small phone size. "
            + "No text, no logos, no watermarks, no UI overlays. "
            + "Each character MUST keep newly-hatched baby-chick proportions with "
            + "an oversized head — never generic or adult chickens. Rendered as "
            + "glossy 3D Pixar / Illumination CGI animation — absolutely NOT "
            + "watercolor, NOT painterly, NOT a 2D flat or storybook illustration. ";

    private static final String[] CAST_VARIANT_MOODS = {
        "all the chicks side by side beaming at the viewer, shared joy — the safe winner",
        "the chicks reacting together to something just off-frame, a mix of "
            + "curious, surprised and giggling — the curiosity hook",
        "the chicks piled together mid-laugh in a high-energy group moment — "
            + "the attention grabber"
    };

    private final OpenAiImageClient client;
    private final LayoutPicker layoutPicker;
    private final TextOverlayer overlayer;
    private final ThumbnailProperties props;
    private final com.youtubeauto.thumbnail.bible.BibleLoader bible;
    private final com.youtubeauto.thumbnail.image.ImageServiceClient imageService;

    /** Number of variants generated per request — gives the reviewer a choice. */
    private static final int VARIANTS = 3;

    /** Variation seeds added to each generation so all variants come out
     *  visually distinct (slightly different mood / composition / emotion).
     *  These are tuned for kids-channel CTR — based on what generally
     *  performs well in the kids vertical. Order matters: variant 1 is the
     *  safest bet, variant 2 the cute-mischief, variant 3 the dramatic
     *  attention-grabber. Future: rotate based on ThumbnailScorer +
     *  analytics history (which variant style won most clicks). */
    private static final String[] VARIANT_MOODS = {
        "wide-eyed with joyful wonder, mouth open in a big bright smile, "
            + "looking directly at viewer — the proven safe winner",
        "tilting head curiously with one eye slightly squinted, mischievous "
            + "grin — the curiosity hook",
        "extreme close-up gasping with both wings up dramatically, mouth "
            + "wide open — the high-energy attention grabber"
    };

    public GenerateThumbnailResponse generate(GenerateThumbnailRequest req) {
        Path dir = Paths.get(props.storage().workRoot(), req.jobId().toString(), "thumbnail");
        try { Files.createDirectories(dir); }
        catch (IOException e) { throw new IllegalStateException(e); }

        Path primary = dir.resolve("thumbnail.png");
        Path bestVariant = null;
        String bestLayoutName = "";
        double bestScore = -1;

        // Cast thumbnail when the title/topic names 2+ characters (e.g.
        // "Meet Pip, Mo & Bo") — show the whole cast, not a single face.
        boolean castMode = isCastThumbnail(req);
        log.info("job={} thumbnail mode={}", req.jobId(), castMode ? "cast" : "single");

        // PRIMARY: render the thumbnail bases from the cast's REFERENCE ANCHORS
        // via image-service (the same Gemini pipeline the film uses), so the
        // thumbnail chicks are the EXACT same characters as the video. Only if
        // this is unavailable do we fall back to OpenAI text-to-image per variant
        // (which is what caused the thumbnail-vs-film gap).
        java.util.List<String> anchorBases = anchorBases(req, castMode);

        for (int v = 1; v <= VARIANTS; v++) {
            // Face-driven layouts: the 3-6 audience can't read, so the face and
            // the visual mystery carry the click — not the caption. Variant 1/3
            // ship with no text at all, variant 2 gets a tiny corner badge.
            // Analytics loop: variant 3's slot is given to the historically
            // best-performing layout once the orchestrator has enough data.
            LayoutTemplate layout = layoutPicker.pickFaceDriven(v);
            if (v == 3 && req.preferredLayout() != null && !req.preferredLayout().isBlank()) {
                try {
                    layout = LayoutTemplate.valueOf(req.preferredLayout());
                    log.info("job={} variant 3 uses analytics-preferred layout {}", req.jobId(), layout);
                } catch (IllegalArgumentException ignore) { /* unknown id — keep rotation */ }
            }
            String[] moods = castMode ? CAST_VARIANT_MOODS : VARIANT_MOODS;
            String moodVariation = moods[(v - 1) % moods.length];

            BufferedImage base = null;

            // 1) Anchor base (real cast chicks) — preferred.
            if (v - 1 < anchorBases.size()) {
                try {
                    base = ImageIO.read(Paths.get(anchorBases.get(v - 1)).toFile());
                } catch (Exception e) {
                    log.warn("job={} variant {} anchor base unreadable ({})",
                            req.jobId(), v, e.getMessage());
                }
            }

            // 2) Fallback: OpenAI text-to-image (legacy path — chicks may drift).
            if (base == null) {
                StringBuilder prompt = new StringBuilder();
                if (!bible.getStyle().isBlank()) prompt.append(bible.getStyle()).append(" ");
                if (castMode && !bible.getCast().isBlank()) {
                    prompt.append("Featuring the full cast together: ")
                          .append(bible.getCast()).append(" ");
                } else if (!bible.getMainCharacter().isBlank()) {
                    prompt.append("Featuring ").append(bible.getMainCharacter()).append(" ");
                }
                prompt.append("Scene topic: ").append(req.topic()).append(". ");
                if (req.hook() != null) prompt.append("Hook: ").append(req.hook()).append(". ");
                prompt.append("Composition: ").append(layout.promptHint).append(" ");
                prompt.append("Variant mood: ").append(moodVariation).append(". ");
                prompt.append(castMode ? CAST_PROMPT_POLICY : PROMPT_POLICY);
                // Reviewer direction LAST — gpt-image-1 weights terminal tokens
                // most heavily, so the human correction wins over the policy.
                prompt.append(hintClause(req));
                try {
                    byte[] basePng = client.generatePng(prompt.toString());
                    try (var in = new ByteArrayInputStream(basePng)) {
                        base = ImageIO.read(in);
                    }
                } catch (Exception e) {
                    log.warn("thumbnail variant {} OpenAI generation failed ({}), falling back to a cast still",
                            v, e.getMessage());
                }
            }

            // 3) Last resort: a zoomed real cast still supplied by the orchestrator.
            if (base == null) base = loadProvidedBase(req, v);
            if (base == null) continue;

            // Contrast/brightness pop on EVERY base (ep-2 audit: the warm
            // golden-haze look reads slightly washed at phone size; anchor
            // bases skipped punchify entirely until now).
            base = punchify(base);

            BufferedImage composed = overlayer.apply(base, layout, punchyCaption(req.title()));
            Path variantPath = dir.resolve("thumbnail-" + v + ".png");
            try {
                ImageIO.write(composed, "png", variantPath.toFile());
                double score = ThumbnailScorer.score(composed);
                log.info("job={} thumbnail variant {} -> {} layout={} score={}",
                        req.jobId(), v, variantPath.getFileName(), layout, score);
                if (score > bestScore) {
                    bestScore = score;
                    bestVariant = variantPath;
                    bestLayoutName = layout.name();
                    // Update primary on the fly so the highest-scoring image
                    // wins by the time the loop ends.
                    Files.copy(variantPath, primary, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException e) {
                log.warn("variant {} write failed: {}", v, e.getMessage());
            }
        }

        if (bestVariant == null) {
            throw new IllegalStateException("All thumbnail variants failed");
        }
        log.info("job={} auto-picked best thumbnail: {} (score={})",
                req.jobId(), bestVariant.getFileName(), bestScore);
        long size;
        try { size = Files.size(primary); } catch (IOException e) { size = -1; }
        return new GenerateThumbnailResponse(req.jobId(), primary.toString(), bestLayoutName, size);
    }

    /**
     * Renders the per-variant thumbnail bases from the cast REFERENCE ANCHORS via
     * image-service — the heart of closing the thumbnail-vs-film gap. The chicks
     * therefore match the video exactly (same Gemini anchors), framed as a CTR
     * close-up. Returns base-image paths ordered by variant, or an empty list on
     * any failure so the caller falls back to OpenAI text-to-image.
     */
    private java.util.List<String> anchorBases(GenerateThumbnailRequest req, boolean castMode) {
        java.util.List<String> chars = castMode
                ? bible.getCastIds()
                : (bible.getMainCharacterId().isBlank()
                    ? java.util.List.of()
                    : java.util.List.of(bible.getMainCharacterId()));
        if (chars.isEmpty()) {
            log.info("job={} no bible character ids — skipping anchor bases", req.jobId());
            return java.util.List.of();
        }
        String framing = castMode
                ? "group hero shot, all faces pushed close to the camera"
                : "extreme close-up hero shot, face filling the frame";
        String[] moods = castMode ? CAST_VARIANT_MOODS : VARIANT_MOODS;
        java.util.List<com.youtubeauto.thumbnail.image.ImageServiceClient.ThumbScene> scenes =
                new java.util.ArrayList<>();
        for (int v = 1; v <= VARIANTS; v++) {
            String mood = moods[(v - 1) % moods.length];
            StringBuilder d = new StringBuilder();
            d.append("Episode topic: ").append(req.topic()).append(". ");
            if (req.hook() != null && !req.hook().isBlank()) {
                d.append("Hook: ").append(req.hook()).append(". ");
            }
            d.append("The character(s) react with a strong, exaggerated emotion to this "
                    + "episode's surprise / discovery. Variant mood: ").append(mood).append('.');
            d.append(hintClause(req));
            scenes.add(new com.youtubeauto.thumbnail.image.ImageServiceClient.ThumbScene(
                    v, d.toString(), chars, framing));
        }
        return imageService.generateThumbnailBases(req.jobId(), scenes, "landscape");
    }

    /** Formats the reviewer's free-text direction as a mandatory terminal
     *  instruction, or "" when absent. Shared by the anchor (Gemini) and the
     *  OpenAI fallback path so the correction applies regardless of route. */
    private static String hintClause(GenerateThumbnailRequest req) {
        String h = req.customHint();
        if (h == null || h.isBlank()) return "";
        return " REVIEWER DIRECTION — MANDATORY, overrides any conflicting guidance above: "
                + h.trim() + ".";
    }

    /**
     * Turns a full YouTube title into an ultra-short badge caption: MAX 2 WORDS,
     * max 14 characters. The 3-6 audience can't read — the badge exists only as
     * a small wink to the browsing parent, so it must never compete with the
     * faces. Takes the LAST words of the pre-separator segment (kids titles
     * front-load character names: "Mo's Orange Pond" -> "ORANGE POND!").
     */
    private String punchyCaption(String title) {
        if (title == null) return "";
        String t = title.replaceAll("#\\w+", "").trim();           // drop #Shorts etc.
        String[] parts = t.split("\\s*[–—:|\\-]\\s*", 2); // split on – — : | -
        if (parts.length > 0 && !parts[0].isBlank()) t = parts[0].trim();
        t = t.replaceAll("[!?.]+$", "").trim();
        // Per-word cleanup: strip surrounding punctuation ("Pip," -> "Pip") and
        // drop symbol-only tokens ("&") so the badge never reads "& BO!".
        String[] words = java.util.Arrays.stream(t.split("\\s+"))
                .map(w -> w.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}!?']+$", ""))
                .filter(w -> w.chars().anyMatch(Character::isLetterOrDigit))
                .toArray(String[]::new);
        final int MAX = 14;
        String caption = "";
        // Prefer the last 2 words; fall back to the last word if too long.
        if (words.length >= 2) {
            String two = words[words.length - 2] + " " + words[words.length - 1];
            caption = two.length() <= MAX ? two : words[words.length - 1];
        } else if (words.length == 1) {
            caption = words[0];
        }
        if (caption.length() > MAX) caption = caption.substring(0, MAX).trim();
        if (caption.isBlank()) return "";
        // Uppercase + "!" — the punchy kids-channel look, at badge size.
        return caption.toUpperCase(java.util.Locale.ROOT) + "!";
    }

    /** Loads the supplied cast still for this variant (round-robin over the
     *  provided list), or null if none/unreadable so the caller falls back to
     *  OpenAI generation. Using a real film frame guarantees the thumbnail
     *  characters match the movie. */
    private BufferedImage loadProvidedBase(GenerateThumbnailRequest req, int variant) {
        var paths = req.baseImagePaths();
        if (paths == null || paths.isEmpty()) return null;
        String p = paths.get((variant - 1) % paths.size());
        try {
            java.nio.file.Path bp = java.nio.file.Paths.get(p);
            if (!java.nio.file.Files.exists(bp)) return null;
            BufferedImage img = ImageIO.read(bp.toFile());
            // Scene stills are cinematic WIDE shots → the chicks end up small in a
            // thumbnail. Zoom toward the faces (upper-middle) so the characters
            // fill the frame like a proper kids thumbnail. The vibrance pop
            // (punchify) is applied once, centrally, for ALL base paths.
            return cropForThumbnail(img, variant);
        } catch (Exception e) {
            log.warn("thumbnail base still {} load failed: {}", p, e.getMessage());
            return null;
        }
    }

    /** Centre-weighted, face-biased zoom crop (keeps 16:9). Tighter on variant 2,
     *  wider on variant 3, so the three variants feel different. */
    private BufferedImage cropForThumbnail(BufferedImage src, int variant) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();
        double frac = switch (variant) { case 2 -> 0.56; case 3 -> 0.78; default -> 0.66; };
        int cw = Math.max(16, (int) (w * frac));
        int ch = Math.max(16, (int) (h * frac));
        int cx = Math.max(0, Math.min((w - cw) / 2, w - cw));
        int cy = Math.max(0, Math.min((int) Math.round(0.40 * h - ch / 2.0), h - ch)); // bias up to faces
        try { return src.getSubimage(cx, cy, cw, ch); }
        catch (Exception e) { return src; }
    }

    /** Light contrast + brightness pop so the thumbnail reads bright and punchy
     *  at small sizes (kids vertical). Conservative so it never looks blown out. */
    private BufferedImage punchify(BufferedImage src) {
        if (src == null) return null;
        BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        rgb.getGraphics().drawImage(src, 0, 0, null);
        // ep-2 audit #8: the warm golden-haze grade reads washed at phone size
        // → slightly stronger pop (was 1.12f / 6f). Still conservative.
        float scale = 1.16f, offset = 8f;
        try {
            new java.awt.image.RescaleOp(scale, offset, null).filter(rgb, rgb);
        } catch (Exception ignored) {}
        // Saturation pop (+12%) — RescaleOp only lifts contrast/brightness;
        // without a saturation pass the golden-hour grade still reads slightly
        // grey next to the hyper-saturated kids-feed competition (assembly-
        // audit #11). Conservative cap so skin/feather tones never go neon.
        try {
            float satBoost = 1.12f;
            int w = rgb.getWidth(), h = rgb.getHeight();
            int[] px = rgb.getRGB(0, 0, w, h, null, 0, w);
            float[] hsb = new float[3];
            for (int i = 0; i < px.length; i++) {
                int p = px[i];
                java.awt.Color.RGBtoHSB((p >> 16) & 0xFF, (p >> 8) & 0xFF, p & 0xFF, hsb);
                hsb[1] = Math.min(1f, hsb[1] * satBoost);
                px[i] = java.awt.Color.HSBtoRGB(hsb[0], hsb[1], hsb[2]);
            }
            rgb.setRGB(0, 0, w, h, px, 0, w);
        } catch (Exception ignored) { /* punch is best-effort */ }
        return rgb;
    }

    /** Cast thumbnail when the title + topic together reference 2 or more
     *  cast members by name (e.g. "Meet Pip, Mo & Bo"). Falls back to the
     *  single-character close-up otherwise. */
    /** Words that imply the whole flock is involved even when no two cast names
     *  are mentioned ("Friends help out", "Playing together", "the gang"). */
    private static final String[] GROUP_CUES = {
            "friends", "together", "team", "everyone", "all three", "the gang",
            "the flock", "the chicks", "help each other", "share", "group",
            "playdate", "race", "party", "everybody", "we ", "us "
    };

    private boolean isCastThumbnail(GenerateThumbnailRequest req) {
        String hay = ((req.title() == null ? "" : req.title()) + " "
                + (req.topic() == null ? "" : req.topic())).toLowerCase();
        int hits = 0;
        for (String name : bible.getCastNames()) {
            if (!name.isBlank() && hay.contains(name)) hits++;
            if (hits >= 2) return true;
        }
        // Broaden: a single named character + a group cue (or any group cue on
        // its own) also means "show the cast together", since the episode clearly
        // involves more than one chick even if they aren't all named.
        for (String cue : GROUP_CUES) {
            if (hay.contains(cue)) return true;
        }
        return false;
    }
}
