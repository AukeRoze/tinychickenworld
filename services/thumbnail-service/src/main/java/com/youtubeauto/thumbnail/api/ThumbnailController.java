package com.youtubeauto.thumbnail.api;

import com.youtubeauto.thumbnail.api.dto.GenerateThumbnailRequest;
import com.youtubeauto.thumbnail.api.dto.GenerateThumbnailResponse;
import com.youtubeauto.thumbnail.api.dto.ThumbnailPromptPreview;
import com.youtubeauto.thumbnail.service.ThumbnailGenerator;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/thumbnails")
@RequiredArgsConstructor
public class ThumbnailController {

    private final ThumbnailGenerator generator;

    @PostMapping("/generate")
    public ResponseEntity<GenerateThumbnailResponse> generate(@Valid @RequestBody GenerateThumbnailRequest req) {
        return ResponseEntity.ok(generator.generate(req));
    }

    /** Preview-only: the assembled thumbnail prompt(s) per variant, no image
     *  generation. Cheap + idempotent — drives the dashboard "copy thumbnail
     *  prompt" view. */
    @PostMapping("/preview-prompt")
    public ResponseEntity<ThumbnailPromptPreview> previewPrompt(@Valid @RequestBody GenerateThumbnailRequest req) {
        return ResponseEntity.ok(generator.describe(req));
    }
}
