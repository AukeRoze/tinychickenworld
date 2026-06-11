package com.youtubeauto.video.api;

import com.youtubeauto.video.api.dto.AssemblyRequest;
import com.youtubeauto.video.api.dto.AssemblyResult;
import com.youtubeauto.video.service.AssemblyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/assemble")
@RequiredArgsConstructor
public class AssemblyController {

    private final AssemblyService assembly;

    /** Synchronous for MVP. Wrap in async/queue later. */
    @PostMapping
    public ResponseEntity<AssemblyResult> assemble(@Valid @RequestBody AssemblyRequest req) {
        return ResponseEntity.ok(assembly.assemble(req));
    }
}
