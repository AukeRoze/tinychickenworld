package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Startup guard: every bible character must have approved reference stills.
 * Since the refs now anchor BOTH generation (Veo asset references) and QC
 * (vision checks against canonical pixels), a character without refs silently
 * loses the strongest consistency mechanism the pipeline has. This check makes
 * that loud at boot — exactly the moment a new cast member (the duckling, a
 * future season's addition) is most likely to slip through ref-less.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RefIntegrityCheck {

    private final OrchestratorProperties props;
    private final CharacterRefStills refStills;

    public record Status(int ok, List<String> missing) {}

    /** Live ref-coverage status — used at boot AND by the dashboard health strip. */
    public Status status() {
        List<String> missing = new ArrayList<>();
        int ok = 0;
        try {
            Path bible = Paths.get(props.bible().path());
            if (Files.exists(bible)) {
                JsonNode root = new YAMLMapper().readTree(bible.toFile());
                for (JsonNode ch : root.path("characters")) {
                    String id = ch.path("id").asText("");
                    if (id.isBlank()) continue;
                    if (refStills.resolve(List.of(id)).isEmpty()) missing.add(id);
                    else ok++;
                }
            }
        } catch (Exception e) {
            log.warn("Ref integrity check failed (non-fatal): {}", e.getMessage());
        }
        return new Status(ok, missing);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void verify() {
        Status s = status();
        if (s.missing().isEmpty()) {
            log.info("Ref integrity: all {} bible character(s) have approved reference stills.", s.ok());
        } else {
            log.error("REF INTEGRITY: character(s) {} have NO reference stills in bible/refs — "
                    + "they will render WITHOUT pixel anchoring (Veo refs) and QC falls back "
                    + "to text-only checks. Generate + approve refs via the Cast page before "
                    + "their next episode.", s.missing());
        }
    }
}
