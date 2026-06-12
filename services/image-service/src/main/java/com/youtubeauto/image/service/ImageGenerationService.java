package com.youtubeauto.image.service;

import com.youtubeauto.image.api.dto.GenerateImageRequest;
import com.youtubeauto.image.api.dto.GenerateImageResponse;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.config.ImageProperties;
import com.youtubeauto.image.provider.ImageProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Service
public class ImageGenerationService {

    private final ImageProvider activeProvider;
    private final ImageProperties props;

    public ImageGenerationService(List<ImageProvider> providers,
                                  BibleLoader bibleLoader,
                                  ImageProperties props) {
        this.props = props;
        Map<String, ImageProvider> byName = providers.stream()
                .collect(Collectors.toMap(ImageProvider::name, p -> p));
        String wanted = bibleLoader.getBible().imageGen().provider();
        this.activeProvider = byName.getOrDefault(wanted, byName.get("openai"));
        if (this.activeProvider == null) {
            throw new IllegalStateException("No image provider available; wanted=" + wanted);
        }
        log.info("Image provider: {} (wanted={}, available={})",
                this.activeProvider.name(), wanted, byName.keySet());
    }

    public GenerateImageResponse generate(GenerateImageRequest req) {
        Path dir = Paths.get(props.storage().workRoot(), req.jobId().toString(), "images");
        try { Files.createDirectories(dir); }
        catch (IOException e) { throw new IllegalStateException(e); }

        String format = req.formatOrDefault();
        // Per-video shared seed → all scenes land in the same Flux style
        // neighbourhood (palette, brush density, lighting mood stay coherent).
        long sharedSeed = Math.abs((long) req.jobId().hashCode());
        log.info("job={} sharedSeed={} scenes={}", req.jobId(), sharedSeed, req.scenes().size());

        // ConsistencyState (Story B): every scene after the first is also
        // conditioned on stills ALREADY RENDERED for this episode (the episode
        // look-lock = lowest seq, plus the nearest preceding still for
        // continuity). State lives on disk, so a single-scene QC re-roll sees
        // the same anchors as the original batch run.
        java.util.TreeMap<Integer, Path> episodeStills = scanEpisodeStills(dir);

        List<GenerateImageResponse.SceneImage> out = new ArrayList<>();
        for (GenerateImageRequest.SceneVisual s : req.scenes()) {
            // Explicit per-scene anchors on the request (the orchestrator's
            // per-character, QC-approved canon — Story B) WIN over the local
            // disk scan: the provider reads them off the scene itself, so the
            // generic scan list stays empty to avoid double-anchoring.
            boolean explicitAnchors = s.episodeAnchors() != null && !s.episodeAnchors().isEmpty();
            List<Path> anchors = explicitAnchors
                    ? List.of()
                    : episodeAnchorsFor(s.seq(), episodeStills);
            byte[] png = activeProvider.generatePng(s, format, sharedSeed, anchors);
            Path file = dir.resolve(String.format("scene_%02d.png", s.seq()));
            try { Files.write(file, png); }
            catch (IOException e) { throw new IllegalStateException("Failed to write " + file, e); }
            episodeStills.put(s.seq(), file);
            out.add(new GenerateImageResponse.SceneImage(s.seq(), file.toString(), png.length));
            log.info("job={} scene={} provider={} format={} characters={} location={} epAnchors={}{} -> {}",
                    req.jobId(), s.seq(), activeProvider.name(), format,
                    s.characters(), s.locationId(),
                    explicitAnchors ? s.episodeAnchors().size() : anchors.size(),
                    explicitAnchors ? " (request canon)" : " (disk scan)", file);
        }
        return new GenerateImageResponse(req.jobId(), out);
    }

    /** Existing scene stills on disk (from this run's earlier scenes or a prior
     *  batch when this is a QC re-roll), keyed by seq. */
    private java.util.TreeMap<Integer, Path> scanEpisodeStills(Path dir) {
        java.util.TreeMap<Integer, Path> map = new java.util.TreeMap<>();
        java.util.regex.Pattern p = java.util.regex.Pattern.compile("scene_(\\d+)\\.png");
        try (var stream = Files.list(dir)) {
            stream.forEach(f -> {
                var m = p.matcher(f.getFileName().toString());
                if (m.matches()) map.put(Integer.parseInt(m.group(1)), f);
            });
        } catch (IOException e) {
            log.warn("ConsistencyState scan of {} failed: {}", dir, e.getMessage());
        }
        return map;
    }

    /** Picks ≤2 episode anchors for a scene: the episode look-lock (lowest seq
     *  below this scene) + the nearest preceding still. A re-roll of the very
     *  first scene falls back to the next still instead, so even seq 1 keeps
     *  the episode look. The current scene's own (stale) still never anchors
     *  its re-roll — it's the image being replaced. */
    private List<Path> episodeAnchorsFor(int seq, java.util.TreeMap<Integer, Path> stills) {
        List<Path> anchors = new ArrayList<>();
        var below = stills.headMap(seq, false);
        if (!below.isEmpty()) {
            anchors.add(below.firstEntry().getValue());                       // look-lock
            Path prev = below.lastEntry().getValue();
            if (!anchors.contains(prev)) anchors.add(prev);                   // continuity
        } else {
            var above = stills.tailMap(seq, false);
            if (!above.isEmpty()) anchors.add(above.firstEntry().getValue()); // seq-1 re-roll
        }
        return anchors;
    }

    /**
     * Thumbnail bases — same reference-anchor pipeline as {@link #generate}, but
     * each "scene" is rendered with the provider's CTR close-up prompt so the
     * thumbnail chicks are the EXACT same characters as the film. Written to a
     * separate {@code thumbnail/} subdir; the thumbnail-service reads them back
     * over the shared workdir volume and adds the text overlay + scoring.
     */
    public GenerateImageResponse generateThumbnail(GenerateImageRequest req) {
        Path dir = Paths.get(props.storage().workRoot(), req.jobId().toString(), "thumbnail");
        try { Files.createDirectories(dir); }
        catch (IOException e) { throw new IllegalStateException(e); }

        String format = req.formatOrDefault();
        long sharedSeed = Math.abs((long) req.jobId().hashCode());
        log.info("job={} THUMBNAIL bases sharedSeed={} variants={}",
                req.jobId(), sharedSeed, req.scenes().size());
        // Thumbnails inherit the episode look too: condition on the episode's
        // first + last rendered stills so the thumbnail chicks match the film.
        java.util.TreeMap<Integer, Path> episodeStills = scanEpisodeStills(
                Paths.get(props.storage().workRoot(), req.jobId().toString(), "images"));
        List<Path> epAnchors = new ArrayList<>();
        if (!episodeStills.isEmpty()) {
            epAnchors.add(episodeStills.firstEntry().getValue());
            Path last = episodeStills.lastEntry().getValue();
            if (!epAnchors.contains(last)) epAnchors.add(last);
        }

        List<GenerateImageResponse.SceneImage> out = new ArrayList<>();
        for (GenerateImageRequest.SceneVisual s : req.scenes()) {
            byte[] png = activeProvider.generateThumbnailPng(s, format, sharedSeed, epAnchors);
            Path file = dir.resolve(String.format("thumb_base_%02d.png", s.seq()));
            try { Files.write(file, png); }
            catch (IOException e) { throw new IllegalStateException("Failed to write " + file, e); }
            out.add(new GenerateImageResponse.SceneImage(s.seq(), file.toString(), png.length));
            log.info("job={} thumbnail-base variant={} provider={} characters={} -> {}",
                    req.jobId(), s.seq(), activeProvider.name(), s.characters(), file);
        }
        return new GenerateImageResponse(req.jobId(), out);
    }
}
