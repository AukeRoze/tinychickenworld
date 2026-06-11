package com.youtubeauto.videogen.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Resolves the APPROVED character reference stills (the same set the Cast page
 * manages for image generation) so they can be attached to Veo 3.1 calls as
 * asset reference images — identity anchored in pixels instead of prompt text.
 * Text DNA-locks are probability; a reference image is evidence. This is the
 * single biggest lever against the residual character drift (assembly-audit #5).
 *
 * Convention: {@code <refsDir>/<characterId>*.png|jpg} — e.g. {@code pip.png},
 * {@code pip-front.png}, {@code refs/pip/1.png}. Per character the first match
 * (sorted) wins; Veo accepts at most {@link #MAX_REFS} reference images total,
 * so with 3 cast members each contributes one.
 */
@Slf4j
@Component
public class CharacterRefs {

    /** Veo 3.1 accepts up to 3 asset reference images per request. */
    public static final int MAX_REFS = 3;

    @Value("${veo.reference-images.enabled:true}")
    private boolean enabled;

    @Value("${veo.reference-images.dir:/bible/refs}")
    private String refsDir;

    /** Up to {@link #MAX_REFS} reference stills for the given cast, in cast
     *  order (one per character first; remaining slots go to extra angles of
     *  the first characters). Empty when disabled or nothing is found. */
    public List<Path> resolve(List<String> characterIds) {
        if (!enabled || characterIds == null || characterIds.isEmpty()) return List.of();
        Path dir = Paths.get(refsDir);
        if (!Files.isDirectory(dir)) return List.of();

        List<Path> out = new ArrayList<>();
        // Round 1: one ref per character (identity coverage beats angle coverage).
        for (String id : characterIds) {
            if (out.size() >= MAX_REFS) break;
            List<Path> matches = refsFor(dir, id);
            if (!matches.isEmpty()) out.add(matches.get(0));
            else log.debug("No reference still found for character '{}' in {}", id, refsDir);
        }
        // Round 2: spare slots → extra angles of the earlier characters.
        for (String id : characterIds) {
            if (out.size() >= MAX_REFS) break;
            for (Path p : refsFor(dir, id)) {
                if (out.size() >= MAX_REFS) break;
                if (!out.contains(p)) out.add(p);
            }
        }
        return out;
    }

    /** All ref images for one character id, in priority order:
     *    1. the canonical {@code <id>.png|jpg} (the approved hero ref),
     *    2. angle shots in the {@code <id>/} subfolder (01-front, 02-…),
     *    3. other {@code <id>*}-prefixed files — but never anything containing
     *       "candidate" (rejected/proposal stills like mo-candidate.png). */
    private List<Path> refsFor(Path dir, String id) {
        if (id == null || id.isBlank()) return List.of();
        String lc = id.toLowerCase(Locale.ROOT);
        List<Path> exact = new ArrayList<>();
        List<Path> angles = new ArrayList<>();
        List<Path> rest = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir)) {
            for (Path p : ds) {
                String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
                if (Files.isRegularFile(p) && isImage(name) && name.startsWith(lc)) {
                    String stem = name.substring(0, name.lastIndexOf('.'));
                    if (stem.equals(lc)) exact.add(p);
                    else if (!name.contains("candidate")) rest.add(p);
                } else if (Files.isDirectory(p) && name.equals(lc)) {
                    try (DirectoryStream<Path> sub = Files.newDirectoryStream(p, "*.{png,jpg,jpeg}")) {
                        for (Path sp : sub) angles.add(sp);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Ref scan failed for '{}': {}", id, e.getMessage());
        }
        angles.sort(Path::compareTo);
        rest.sort(Path::compareTo);
        List<Path> out = new ArrayList<>(exact);
        out.addAll(angles);
        out.addAll(rest);
        return out;
    }

    private static boolean isImage(String name) {
        return name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg");
    }
}
