package com.youtubeauto.videogen.api;

import com.youtubeauto.videogen.api.dto.GenerateClipsRequest;
import com.youtubeauto.videogen.api.dto.GenerateClipsResponse;
import com.youtubeauto.videogen.service.ClipGenerationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Single endpoint for video-generation. Uses an ObjectProvider so the
 * controller still wires even when ClipGenerationService is absent
 * (GCP not configured) — requests then return 503 instead of crashing
 * the whole service at startup.
 */
@RestController
@RequestMapping("/api/v1/clips")
public class ClipsController {

    private final ObjectProvider<ClipGenerationService> serviceProvider;

    public ClipsController(ObjectProvider<ClipGenerationService> serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerateClipsRequest req) {
        ClipGenerationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error", "Veo not configured",
                            "detail", "Set GCP_PROJECT_ID + GCS_OUTPUT_BUCKET + GOOGLE_APPLICATION_CREDENTIALS to enable motionMode=veo."
                    ));
        }
        GenerateClipsResponse resp = service.generate(req);
        return ResponseEntity.ok(resp);
    }
}
