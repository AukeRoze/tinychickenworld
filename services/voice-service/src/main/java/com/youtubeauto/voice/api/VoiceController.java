package com.youtubeauto.voice.api;

import com.youtubeauto.voice.api.dto.SynthesizeRequest;
import com.youtubeauto.voice.api.dto.SynthesizeResponse;
import com.youtubeauto.voice.service.VoiceSynthesisService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/voice")
@RequiredArgsConstructor
public class VoiceController {

    private final VoiceSynthesisService service;

    @PostMapping("/synthesize")
    public ResponseEntity<SynthesizeResponse> synthesize(@Valid @RequestBody SynthesizeRequest req) {
        return ResponseEntity.ok(service.synthesize(req));
    }
}
