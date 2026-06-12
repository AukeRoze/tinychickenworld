package com.youtubeauto.videogen.api;

import com.youtubeauto.videogen.api.dto.GenerateClipsRequest;
import com.youtubeauto.videogen.api.dto.GenerateClipsResponse;
import com.youtubeauto.videogen.service.AsyncClipJobStore;
import com.youtubeauto.videogen.service.ClipGenerationService;
import jakarta.validation.Valid;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Endpoints for video-generation. Uses an ObjectProvider so the
 * controller still wires even when ClipGenerationService is absent
 * (GCP not configured) — requests then return 503 instead of crashing
 * the whole service at startup.
 *
 * Two flavours of the same work:
 *  - POST /generate        — synchronous (legacy): blocks until all clips done.
 *  - POST /generate-async  — returns {jobId} immediately; the caller polls
 *    GET /jobs/{jobId} for {status, result?} (result = the exact same
 *    GenerateClipsResponse the synchronous endpoint would have returned).
 */
@RestController
@RequestMapping("/api/v1/clips")
public class ClipsController {

    private final ObjectProvider<ClipGenerationService> serviceProvider;
    private final AsyncClipJobStore asyncJobs;

    public ClipsController(ObjectProvider<ClipGenerationService> serviceProvider,
                           AsyncClipJobStore asyncJobs) {
        this.serviceProvider = serviceProvider;
        this.asyncJobs = asyncJobs;
    }

    @PostMapping("/generate")
    public ResponseEntity<?> generate(@Valid @RequestBody GenerateClipsRequest req) {
        ClipGenerationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return veoNotConfigured();
        }
        GenerateClipsResponse resp = service.generate(req);
        return ResponseEntity.ok(resp);
    }

    /** Async submit: kicks off the generation on the job store's bounded
     *  executor and returns a poll handle straight away — no 15-minute-open
     *  HTTP connection. */
    @PostMapping("/generate-async")
    public ResponseEntity<?> generateAsync(@Valid @RequestBody GenerateClipsRequest req) {
        ClipGenerationService service = serviceProvider.getIfAvailable();
        if (service == null) {
            return veoNotConfigured();
        }
        UUID jobId = asyncJobs.submit(service, req);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId.toString()));
    }

    /** Poll: {status: RUNNING|DONE|FAILED, result?, error?}. 404 = unknown or
     *  expired (results are kept 2h) or the service restarted in between. */
    @GetMapping("/jobs/{jobId}")
    public ResponseEntity<?> jobStatus(@PathVariable UUID jobId) {
        return asyncJobs.get(jobId)
                .<ResponseEntity<?>>map(state -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("status", state.status().name());
                    if (state.result() != null) body.put("result", state.result());
                    if (state.error() != null) body.put("error", state.error());
                    return ResponseEntity.ok(body);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "Unknown or expired jobId: " + jobId)));
    }

    private ResponseEntity<?> veoNotConfigured() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of(
                        "error", "Veo not configured",
                        "detail", "Set GCP_PROJECT_ID + GCS_OUTPUT_BUCKET + GOOGLE_APPLICATION_CREDENTIALS to enable motionMode=veo."
                ));
    }
}
