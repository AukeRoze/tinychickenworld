package com.youtubeauto.script.api;

import com.youtubeauto.script.api.dto.*;
import com.youtubeauto.script.service.ScriptOrchestrator;
import com.youtubeauto.script.service.TreatmentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/scripts")
@RequiredArgsConstructor
public class ScriptController {

    private final ScriptOrchestrator orchestrator;
    private final TreatmentService treatmentService;

    @PostMapping
    public ResponseEntity<ScriptJobResponse> create(@Valid @RequestBody GenerateScriptRequest req) {
        ScriptJobResponse job = orchestrator.submit(req);
        return ResponseEntity.created(URI.create("/api/v1/scripts/" + job.jobId())).body(job);
    }

    /** Story-treatment preview (front-end review stage #1) — synchronous, no job
     *  created; the user edits the result and only then submits the episode. */
    @PostMapping("/treatment")
    public ResponseEntity<TreatmentResponse> treatment(@Valid @RequestBody GenerateScriptRequest req) {
        return ResponseEntity.ok(treatmentService.generate(req));
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ScriptResponse> get(@PathVariable UUID jobId) {
        return ResponseEntity.ok(orchestrator.get(jobId));
    }
}
