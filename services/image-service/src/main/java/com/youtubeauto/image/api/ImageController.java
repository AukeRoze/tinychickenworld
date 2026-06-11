package com.youtubeauto.image.api;

import com.youtubeauto.image.api.dto.GenerateImageRequest;
import com.youtubeauto.image.api.dto.GenerateImageResponse;
import com.youtubeauto.image.service.ImageGenerationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/images")
@RequiredArgsConstructor
public class ImageController {

    private final ImageGenerationService service;

    @PostMapping("/generate")
    public ResponseEntity<GenerateImageResponse> generate(@Valid @RequestBody GenerateImageRequest req) {
        return ResponseEntity.ok(service.generate(req));
    }

    /** Thumbnail bases — reference-anchor CTR close-ups of the real cast. */
    @PostMapping("/thumbnail")
    public ResponseEntity<GenerateImageResponse> thumbnail(@Valid @RequestBody GenerateImageRequest req) {
        return ResponseEntity.ok(service.generateThumbnail(req));
    }
}
