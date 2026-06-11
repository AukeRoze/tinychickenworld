package com.youtubeauto.orchestrator.service;

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
 * Resolves the approved character reference stills (bible/refs) for the
 * vision-QC checkers, so Claude judges generated stills/clips against the
 * ACTUAL canonical pixels instead of against a text description of them.
 * Counterpart of the video-gen service's CharacterRefs (which feeds the same
 * refs to Veo as asset reference images) — generation and QC now share one
 * ground truth.
 */
@Slf4j
@Component
public class CharacterRefStills {

    /** Keep the QC payload lean: one canonical ref per character, max 3. */
    private static final int MAX_REFS = 3;

    @Value("${app.qc.refs-dir:/bible/refs}")
    private String refsDir;

    public record RefStill(String characterId, Path path) {}

    /** One approved ref per character (canonical {@code <id>.png} first, else
     *  the first angle shot in {@code <id>/}), capped at {@link #MAX_REFS}.
     *  Files containing "candidate" are never used. */
    public List<RefStill> resolve(List<String> characterIds) {
        List<RefStill> out = new ArrayList<>();
        if (characterIds == null || characterIds.isEmpty()) return out;
        Path dir = Paths.get(refsDir);
        if (!Files.isDirectory(dir)) return out;
        for (String id : characterIds) {
            if (out.size() >= MAX_REFS) break;
            if (id == null || id.isBlank()) continue;
            Path ref = canonicalRef(dir, id.toLowerCase(Locale.ROOT));
            if (ref != null) out.add(new RefStill(id.toLowerCase(Locale.ROOT), ref));
        }
        return out;
    }

    private Path canonicalRef(Path dir, String id) {
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            Path p = dir.resolve(id + ext);
            if (Files.isRegularFile(p)) return p;
        }
        Path sub = dir.resolve(id);
        if (Files.isDirectory(sub)) {
            List<Path> files = new ArrayList<>();
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(sub, "*.{png,jpg,jpeg}")) {
                for (Path p : ds) {
                    if (!p.getFileName().toString().toLowerCase(Locale.ROOT).contains("candidate")) {
                        files.add(p);
                    }
                }
            } catch (Exception e) {
                log.debug("Ref subfolder scan failed for '{}': {}", id, e.getMessage());
            }
            files.sort(Path::compareTo);
            if (!files.isEmpty()) return files.get(0);
        }
        return null;
    }
}
