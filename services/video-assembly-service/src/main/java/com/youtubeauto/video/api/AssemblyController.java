package com.youtubeauto.video.api;

import com.youtubeauto.video.api.dto.AssemblyRequest;
import com.youtubeauto.video.api.dto.AssemblyResult;
import com.youtubeauto.video.service.AssemblyService;
import com.youtubeauto.video.service.AsyncAssemblyJobStore;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Two flavours of the same work (mirrors video-generation-service's
 * ClipsController generate / generate-async pair):
 *  - POST /api/v1/assemble        — synchronous (legacy): blocks until the
 *    render is done. Kept unchanged for compatibility.
 *  - POST /api/v1/assemble-async  — returns {jobId} immediately; the caller
 *    polls GET /api/v1/assemble/jobs/{jobId} for {status, result?, error?}
 *    (result = the exact same AssemblyResult the synchronous endpoint would
 *    have returned). Added after the 2026-06-12 incident where a >20-min
 *    render broke the orchestrator's HTTP connection while ffmpeg kept going.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class AssemblyController {

    private final AssemblyService assembly;
    private final AsyncAssemblyJobStore asyncJobs;

    /** Synchronous (legacy). Prefer assemble-async for long renders. */
    @PostMapping("/assemble")
    public ResponseEntity<AssemblyResult> assemble(@Valid @RequestBody AssemblyRequest req) {
        return ResponseEntity.ok(assembly.assemble(req));
    }

    /** Async submit: queues the render on the job store's single-thread
     *  executor (one render at a time — assembly is the heaviest stage) and
     *  returns a poll handle straight away — no long-open HTTP connection. */
    @PostMapping("/assemble-async")
    public ResponseEntity<?> assembleAsync(@Valid @RequestBody AssemblyRequest req) {
        UUID jobId = asyncJobs.submit(assembly, req);
        return ResponseEntity.accepted().body(Map.of("jobId", jobId.toString()));
    }

    /** Poll: {status: RUNNING|DONE|FAILED, result?, error?}. 404 = unknown or
     *  expired (results are kept 2h) or the service restarted in between. */
    @GetMapping("/assemble/jobs/{jobId}")
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
}
