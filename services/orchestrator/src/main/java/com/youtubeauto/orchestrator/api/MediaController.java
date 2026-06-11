package com.youtubeauto.orchestrator.api;

import com.youtubeauto.orchestrator.domain.VideoJob;
import com.youtubeauto.orchestrator.repository.VideoJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.UUID;

/**
 * Serves the per-job media the static UI needs (scene images, thumbnails, the
 * assembled master video) and the thumbnail-select action. These were on the
 * old dashboard/review controllers; they live here so those can be retired
 * while the new UI keeps working. Paths are kept identical so no UI URL changes.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class MediaController {

    private final VideoJobRepository jobRepo;
    private final com.fasterxml.jackson.databind.ObjectMapper mapper;

    /** A scene's raw Veo clip for inline preview on the job page — WITHOUT
     *  voice/music (those join at assembly). Resolves the clipPath the
     *  orchestrator stored on the assembly scene; falls back to the
     *  video-generation-service disk convention. 404 = Ken Burns scene. */
    @GetMapping("/dashboard/{id}/scene/{seq}/clip.mp4")
    public ResponseEntity<Resource> sceneClip(@PathVariable UUID id, @PathVariable int seq) {
        Path p = null;
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job != null && job.getAssemblyScenesJson() != null && !job.getAssemblyScenesJson().isBlank()) {
            try {
                for (com.fasterxml.jackson.databind.JsonNode s : mapper.readTree(job.getAssemblyScenesJson())) {
                    if (s.path("seq").asInt() == seq) {
                        String cp = s.path("clipPath").asText("");
                        // Defence-in-depth: clipPath comes from our own DB, but
                        // serve it only when it stays inside /workdir — a
                        // tampered row must not turn this endpoint into an
                        // arbitrary-file reader.
                        if (!cp.isBlank()) {
                            Path candidate = Paths.get(cp).normalize();
                            if (candidate.startsWith(Paths.get("/workdir"))) p = candidate;
                        }
                        break;
                    }
                }
            } catch (Exception e) {
                log.warn("sceneClip {}/{}: assemblyScenes JSON unreadable ({}) — using convention path",
                        id, seq, e.getMessage());
            }
        }
        if (p == null || !Files.exists(p)) {
            p = Paths.get("/workdir", "jobs", id.toString(), "scenes", String.valueOf(seq), "clip.mp4");
        }
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).body(new FileSystemResource(p));
    }

    /** A scene's generated still — workdir/{id}/images/scene_NN.png. */
    @GetMapping(value = "/review/images/{id}/file/{seq}.png", produces = "image/png")
    public ResponseEntity<byte[]> sceneImage(@PathVariable UUID id, @PathVariable int seq) {
        Path p = Paths.get("/workdir", id.toString(), "images", String.format("scene_%02d.png", seq));
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok().header("Cache-Control", "no-store").body(Files.readAllBytes(p));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** A thumbnail variant by index — workdir/{id}/thumbnail/thumbnail-N.png. */
    @GetMapping(value = "/dashboard/{id}/thumbnail/{variant}.png", produces = "image/png")
    public ResponseEntity<byte[]> thumbnailVariant(@PathVariable UUID id, @PathVariable int variant) {
        Path p = Paths.get("/workdir", id.toString(), "thumbnail", "thumbnail-" + variant + ".png");
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).body(Files.readAllBytes(p));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Selects a thumbnail variant as the primary one used for upload. */
    @PostMapping("/api/v1/videos/{id}/thumbnail/{variant}")
    public ResponseEntity<Map<String, String>> selectThumbnail(@PathVariable UUID id, @PathVariable int variant) {
        Path dir = Paths.get("/workdir", id.toString(), "thumbnail");
        Path src = dir.resolve("thumbnail-" + variant + ".png");
        Path dst = dir.resolve("thumbnail.png");
        if (!Files.exists(src)) return ResponseEntity.notFound().build();
        try {
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok(Map.of("result", "SELECTED", "variant", String.valueOf(variant)));
        } catch (IOException e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** The assembled master MP4 for inline playback. */
    @GetMapping("/dashboard/{id}/master.mp4")
    public ResponseEntity<Resource> masterVideo(@PathVariable UUID id) {
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job == null || job.getVideoPath() == null) return ResponseEntity.notFound().build();
        Path p = Paths.get(job.getVideoPath());
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).body(new FileSystemResource(p));
    }

    /** The auto-derived vertical Short for inline playback / download. */
    @GetMapping("/dashboard/{id}/short.mp4")
    public ResponseEntity<Resource> shortVideo(@PathVariable UUID id) {
        VideoJob job = jobRepo.findById(id).orElse(null);
        if (job == null || job.getShortPath() == null) return ResponseEntity.notFound().build();
        Path p = Paths.get(job.getShortPath());
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.valueOf("video/mp4")).body(new FileSystemResource(p));
    }

    /** Root → the new UI (the classic dashboard at "/" is gone). */
    @GetMapping("/")
    public ResponseEntity<Void> root() {
        return ResponseEntity.status(302).header("Location", "/ui/index.html").build();
    }
}
