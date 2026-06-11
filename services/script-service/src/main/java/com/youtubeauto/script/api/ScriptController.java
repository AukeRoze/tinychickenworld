package com.youtubeauto.script.api;

import com.youtubeauto.script.api.dto.*;
import com.youtubeauto.script.service.ScriptOrchestrator;
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

    @PostMapping
    public ResponseEntity<ScriptJobResponse> create(@Valid @RequestBody GenerateScriptRequest req) {
        ScriptJobResponse job = orchestrator.submit(req);
        return ResponseEntity.created(URI.create("/api/v1/scripts/" + job.jobId())).body(job);
    }

    @GetMapping("/{jobId}")
    public ResponseEntity<ScriptResponse> get(@PathVariable UUID jobId) {
        return ResponseEntity.ok(orchestrator.get(jobId));
    }
}
