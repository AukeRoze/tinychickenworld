package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Hot-reload van de channel-bible over de hele stack, zonder herstarts.
 *
 * <p>De bible wordt op twee plekken gecachet: (1) de BibleLoaders van
 * script-/voice-/image-/thumbnail-/videogen-service (@PostConstruct) en
 * (2) de prompt-caches van {@link VeoPromptCompiler} in de orchestrator
 * zelf. {@link #reloadAll()} leegt de orchestrator-caches en POSTt
 * best-effort {@code /api/v1/bible/reload} naar elke service; één
 * onbereikbare service blokkeert de rest niet. De assembly-service heeft
 * geen endpoint nodig (die hot-reloadt de bible al ≤1 min zelf) en de
 * upload-service leest de bible niet.
 *
 * <p>Aangeroepen door BrandController: automatisch na elke Cast-edit, en
 * handmatig via {@code POST /api/v1/brand/bible/reload} (voor met de hand
 * bewerkte channel.yml-wijzigingen).
 */
@Slf4j
@Service
public class BibleReloadService {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final VeoPromptCompiler veoPromptCompiler;
    private final WebClient client;
    private final Map<String, String> targets;

    public BibleReloadService(WebClient.Builder builder,
                              OrchestratorProperties props,
                              VeoPromptCompiler veoPromptCompiler) {
        this.veoPromptCompiler = veoPromptCompiler;
        this.client = builder.clone().build();
        // naam → base-URL; blanco URL's (test/dev zonder die service) worden geskipt.
        Map<String, String> t = new LinkedHashMap<>();
        OrchestratorProperties.Services s = props.services();
        t.put("script-service", s.script());
        t.put("voice-service", s.voice());
        t.put("image-service", s.image());
        t.put("thumbnail-service", s.thumbnail());
        t.put("video-generation-service", s.videoGen());
        this.targets = t;
    }

    /**
     * Leegt de orchestrator-bible-caches en fan-out naar alle services.
     * Best-effort: het resultaat per service staat in de map (true of een
     * foutomschrijving); deze methode gooit zelf nooit.
     */
    public Map<String, Object> reloadAll() {
        Map<String, Object> out = new LinkedHashMap<>();
        try {
            veoPromptCompiler.clearCaches();
            out.put("orchestrator", true);
        } catch (Exception e) {
            out.put("orchestrator", "failed: " + e.getMessage());
        }
        targets.forEach((name, base) -> {
            if (base == null || base.isBlank()) {
                out.put(name, "skipped (no base url configured)");
                return;
            }
            try {
                client.post().uri(base + "/api/v1/bible/reload")
                        .retrieve().toBodilessEntity().block(TIMEOUT);
                out.put(name, true);
            } catch (Exception e) {
                log.warn("Bible reload on {} failed: {}", name, e.getMessage());
                out.put(name, "failed: " + e.getMessage());
            }
        });
        log.info("Bible reload fan-out: {}", out);
        return out;
    }
}
