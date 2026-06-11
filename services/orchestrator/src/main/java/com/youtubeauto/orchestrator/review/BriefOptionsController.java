package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.service.BibleEditor;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Serves bible-managed dropdown options to the dashboard form.
 * GET /api/v1/options/{type} → JSON array of strings.
 * Reading the bible on every request keeps it live — edit YAML, refresh page.
 * Also lets the Series page add / edit / delete series (writes via BibleEditor).
 */
@RestController
@RequiredArgsConstructor
public class BriefOptionsController {

    private final OrchestratorProperties props;
    private final BibleEditor bibleEditor;
    private final YAMLMapper yaml = new YAMLMapper();

    @GetMapping(value = "/api/v1/options/{type}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<String>> options(@PathVariable String type) {
        // Whitelist the bible keys we want to expose. Prevents path-style access.
        String key = switch (type) {
            case "lessons" -> "lessons";
            case "moods"   -> "moods";
            case "hooks"   -> "hooks";
            default        -> null;
        };
        if (key == null) return ResponseEntity.badRequest().build();

        try {
            var path = Paths.get(props.bible().path());
            if (!Files.exists(path)) return ResponseEntity.ok(List.of());
            JsonNode root = yaml.readTree(path.toFile());
            JsonNode arr = root.path("briefOptions").path(key);
            List<String> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) out.add(n.asText());
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** A pre-defined series the user can pick in the New Job form. */
    public record SeriesOption(String id, String name, String description) {}

    /**
     * GET /api/v1/series → JSON array of {id, name, description} from bible.series.
     * Read live from the bible so editing channel.yml + refresh updates the dropdown.
     */
    @GetMapping(value = "/api/v1/series", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<List<SeriesOption>> series() {
        try {
            var path = Paths.get(props.bible().path());
            if (!Files.exists(path)) return ResponseEntity.ok(List.of());
            JsonNode root = yaml.readTree(path.toFile());
            JsonNode arr = root.path("series");
            List<SeriesOption> out = new ArrayList<>();
            if (arr.isArray()) {
                for (JsonNode n : arr) {
                    String id = n.path("id").asText("");
                    if (id.isBlank()) continue;
                    String name = n.path("name").asText(id);
                    String desc = n.path("description").asText("");
                    out.add(new SeriesOption(id, name, desc));
                }
            }
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /** Create a new series. Body: {"id": "...", "name": "...", "description": "..."}. */
    @PostMapping(value = "/api/v1/series", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> createSeries(@RequestBody Map<String, String> body) {
        try {
            String id = bibleEditor.addSeries(body.get("id"), body.get("name"), body.get("description"));
            return ResponseEntity.ok(Map.of("id", id, "result", "CREATED",
                    "note", "Shows on the Series page + New Job dropdown now; restart script-service for new scripts."));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Update a series' name/description. Body: {"name": "...", "description": "..."}. */
    @PostMapping(value = "/api/v1/series/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> updateSeries(@PathVariable String id, @RequestBody Map<String, String> body) {
        try {
            List<String> changed = bibleEditor.updateSeries(id, body.get("name"), body.get("description"));
            return ResponseEntity.ok(Map.of("id", id, "changed", changed, "result", "UPDATED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Delete a series. */
    @DeleteMapping(value = "/api/v1/series/{id}")
    public ResponseEntity<?> deleteSeries(@PathVariable String id) {
        try {
            bibleEditor.deleteSeries(id);
            return ResponseEntity.ok(Map.of("id", id, "result", "DELETED"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
