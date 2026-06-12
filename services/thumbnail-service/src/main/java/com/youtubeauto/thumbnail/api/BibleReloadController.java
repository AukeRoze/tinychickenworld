package com.youtubeauto.thumbnail.api;

import com.youtubeauto.thumbnail.bible.BibleLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Hot-reload van de channel-bible (cast-omschrijvingen, DNA-clausules, stijl)
 * zonder service-herstart. Aangeroepen door de orchestrator-fan-out
 * (POST /api/v1/brand/bible/reload) na een bible-edit.
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class BibleReloadController {

    private final BibleLoader bibleLoader;

    @PostMapping("/api/v1/bible/reload")
    public ResponseEntity<Map<String, Object>> reload() {
        try {
            bibleLoader.load();   // load() is zelf synchronized + cleart de cast-collecties
            log.info("Bible reloaded on request");
            return ResponseEntity.ok(Map.of("reloaded", true));
        } catch (Exception e) {
            log.warn("Bible reload failed: {}", e.getMessage());
            return ResponseEntity.internalServerError()
                    .body(Map.of("reloaded", false, "error", String.valueOf(e.getMessage())));
        }
    }
}
