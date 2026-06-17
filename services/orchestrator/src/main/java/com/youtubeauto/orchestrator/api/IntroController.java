package com.youtubeauto.orchestrator.api;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.service.IntroRebuildService;
import lombok.RequiredArgsConstructor;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

/**
 * One-click intro rebuild for the dashboard. POST kicks off the async chain
 * (still → Veo clip → composite); GET reports progress for the button to poll.
 * GET /current.mp4 streams the current intro clip so the UI can preview it.
 */
@RestController
@RequestMapping("/api/v1/intro")
@RequiredArgsConstructor
public class IntroController {

    private final IntroRebuildService service;
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

    /** Re-run only the title/SFX assembly on the last Veo clip (no Veo cost). */
    @PostMapping("/recomposite")
    public ResponseEntity<?> recomposite() {
        if (service.running()) {
            return ResponseEntity.ok(Map.of("status", service.status(), "running", true));
        }
        if (!service.hasClip()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "No cached intro clip yet — do a full rebuild first."));
        }
        service.recomposite();
        return ResponseEntity.accepted().body(Map.of("status", "recompositing", "running", true));
    }

    /** Streams the current intro clip for in-UI preview. */
    @GetMapping(value = "/current.mp4", produces = "video/mp4")
    public void current(@RequestHeader HttpHeaders headers, HttpServletResponse response) throws IOException {
        Path p = Paths.get(props.brand().introPath());
        if (!Files.exists(p)) { response.sendError(404); return; }
        VideoStreaming.serve(p, headers, response);
    }
}
