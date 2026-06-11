package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;

/**
 * Serves the GOLDEN TEST results to the dashboard. The bench
 * (infra/eval/golden-test.py) runs on the host and publishes its latest run,
 * the baseline and the keyframe contact-sheets into {workdir}/golden/ — the
 * one volume both worlds share. This controller is a thin read-only window
 * onto that folder: no state, no logic, refresh = re-read.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/golden")
@RequiredArgsConstructor
public class GoldenController {

    private static final Path GOLDEN_DIR = Paths.get("/workdir", "golden");
    private final ObjectMapper mapper;

    /** Latest golden run + baseline (null fields when never run). */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        Map<String, Object> out = new HashMap<>();
        out.put("latest", readJson(GOLDEN_DIR.resolve("latest.json")));
        out.put("baseline", readJson(GOLDEN_DIR.resolve("baseline.json")));
        return ResponseEntity.ok(out);
    }

    /** Keyframe contact-sheet image (e.g. pilot-sheet.jpg,
     *  pilot-sheet-baseline.jpg). Name is sanitised to this folder. */
    @GetMapping(value = "/sheet/{name}", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> sheet(@PathVariable String name) {
        if (!name.matches("[A-Za-z0-9._-]+\\.jpg")) return ResponseEntity.badRequest().build();
        Path p = GOLDEN_DIR.resolve(name).normalize();
        if (!p.startsWith(GOLDEN_DIR) || !Files.isReadable(p)) return ResponseEntity.notFound().build();
        try {
            return ResponseEntity.ok(Files.readAllBytes(p));
        } catch (Exception e) {
            return ResponseEntity.notFound().build();
        }
    }

    private JsonNode readJson(Path p) {
        try {
            if (!Files.isReadable(p)) return null;
            return mapper.readTree(p.toFile());
        } catch (Exception e) {
            log.warn("golden read {} failed: {}", p, e.getMessage());
            return null;
        }
    }
}
