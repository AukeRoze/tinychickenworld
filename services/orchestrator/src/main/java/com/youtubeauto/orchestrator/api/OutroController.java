package com.youtubeauto.orchestrator.api;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.service.OutroRebuildService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * One-click outro rebuild for the dashboard. POST kicks off the async chain
 * (still → Veo clip → composite); GET reports progress for the button to poll.
 * GET /current.mp4 streams the current outro clip. Mirrors {@link IntroController}.
 */
@RestController
@RequestMapping("/api/v1/outro")
@RequiredArgsConstructor
public class OutroController {

    private final OutroRebuildService service;
    private final OrchestratorProperties props;

    @PostMapping("/rebuild")
    public ResponseEntity<?> rebuild(
            @org.springframework.web.bind.annotation.RequestParam(required = false) String model) {
        if (service.running()) {
            return ResponseEntity.ok(Map.of("status", service.status(), "running", true));
        }
        service.rebuild(model);
        return ResponseEntity.accepted().body(Map.of("status", "started", "running", true));
    }

    @GetMapping("/status")
    public ResponseEntity<?> status() {
        return ResponseEntity.ok(Map.of(
                "status", service.status(),
                "running", service.running(),
                "hasClip", service.hasClip()));
    }

    /** Re-run only the CTA/credits/SFX assembly on the last Veo clip (no Veo cost). */
    @PostMapping("/recomposite")
    public ResponseEntity<?> recomposite() {
        if (service.running()) {
            return ResponseEntity.ok(Map.of("status", service.status(), "running", true));
        }
        if (!service.hasClip()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No cached outro clip yet — do a full rebuild first."));
        }
        service.recomposite();
        return ResponseEntity.accepted().body(Map.of("status", "recompositing", "running", true));
    }

    /** Streams the current outro clip for in-UI preview. */
    @GetMapping(value = "/current.mp4", produces = "video/mp4")
    public ResponseEntity<Resource> current() {
        Path p = Paths.get(props.brand().outroPath());
        if (!Files.exists(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("video/mp4"))
                .body(new FileSystemResource(p));
    }
}
