package com.youtubeauto.upload.api;

import com.youtubeauto.upload.api.dto.UploadRequest;
import com.youtubeauto.upload.api.dto.UploadResponse;
import com.youtubeauto.upload.service.StatsService;
import com.youtubeauto.upload.service.YouTubeUploadService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/upload")
@RequiredArgsConstructor
public class UploadController {

    private final YouTubeUploadService service;
    private final StatsService stats;

    @PostMapping
    public ResponseEntity<UploadResponse> upload(@Valid @RequestBody UploadRequest req) {
        return ResponseEntity.ok(service.upload(req));
    }

    @GetMapping("/stats/{videoId}")
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable String videoId) {
        return ResponseEntity.ok(stats.stats(videoId));
    }
}
