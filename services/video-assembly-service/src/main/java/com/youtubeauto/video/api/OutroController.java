package com.youtubeauto.video.api;

import com.youtubeauto.video.service.OutroBuilder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Composites the branded end-screen outro (logo + thin bottom line + music +
 * sparkle — see OutroBuilder's safe-zone schema) over a one-time Veo "chickens
 * giggling" clip and writes the outro the assembly stage appends to every
 * video. Called by the orchestrator's outro-rebuild flow.
 */
@RestController
@RequestMapping("/api/v1/outro")
@RequiredArgsConstructor
public class OutroController {

    private final OutroBuilder builder;

    /** voiceLines = ordered ElevenLabs MP3s of the farewell — normally ONE
     *  line (Pip); when present they replace Veo's own audio. */
    public record BuildRequest(String clipPath, List<String> voiceLines) {}

    @PostMapping("/build")
    public ResponseEntity<?> build(@RequestBody BuildRequest req) {
        if (req.clipPath() == null || req.clipPath().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "clipPath required"));
        }
        // Paths arrive over HTTP — keep them inside the two mounted roots.
        SafePaths.requireMounted(req.clipPath());
        List<String> voices = req.voiceLines() == null ? List.of() : req.voiceLines();
        voices.forEach(SafePaths::requireMounted);
        return ResponseEntity.ok(Map.of("outroPath", builder.build(req.clipPath(), voices)));
    }
}
