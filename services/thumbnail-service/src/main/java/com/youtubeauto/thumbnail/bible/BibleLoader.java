package com.youtubeauto.thumbnail.bible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Loads the visual style + main character description from bible/channel.yml
 * so every thumbnail features the real cast in the locked style instead of
 * a generic AI cartoon.
 */
@Slf4j
@Component
public class BibleLoader {

    private final YAMLMapper yaml = new YAMLMapper();

    @Value("${app.bible.path:/bible/channel.yml}")
    private String biblePath;

    @Getter private String style = "";
    @Getter private String mainCharacter = "";
    /** Full descriptions of EVERY cast member, for multi-character thumbnails. */
    @Getter private String cast = "";
    /** Lower-cased cast names, used to detect when a title/topic references
     *  more than one character (→ generate a group thumbnail). */
    @Getter private final java.util.Set<String> castNames = new java.util.LinkedHashSet<>();
    /** Bible character id of the main character (e.g. "pip") — passed to
     *  image-service so the thumbnail base is rendered from that character's
     *  reference anchor. */
    @Getter private String mainCharacterId = "";
    /** Bible character ids of the whole cast (e.g. ["pip","mo","bo"]) — used
     *  for group thumbnails so every chick is rendered from its anchor. */
    @Getter private final java.util.List<String> castIds = new java.util.ArrayList<>();

    @PostConstruct
    public void load() throws IOException {
        Path p = Paths.get(biblePath);
        if (!Files.exists(p)) {
            log.warn("Bible not found at {} — thumbnails will use generic cast", p.toAbsolutePath());
            return;
        }
        JsonNode root = yaml.readTree(p.toFile());
        style = root.path("visualStyle").path("description").asText("").trim();

        // First character with role: main, else first listed.
        JsonNode main = null;
        for (JsonNode c : root.path("characters")) {
            if ("main".equalsIgnoreCase(c.path("role").asText())) { main = c; break; }
        }
        if (main == null && root.path("characters").size() > 0) {
            main = root.path("characters").get(0);
        }
        if (main != null) {
            // Include lifeStage (baby-chick proportions) so the thumbnail
            // character matches the cast instead of drifting to a generic
            // adult chicken — same lock the scene image-service applies.
            String life = main.path("lifeStage").asText("").trim();
            String desc = main.path("description").asText("").trim();
            mainCharacter = main.path("name").asText() + ": "
                    + (life.isBlank() ? "" : life + ", ") + desc + dnaClause(main);
            mainCharacterId = main.path("id").asText("").trim();
        }

        // Build the full-cast description + name set for group thumbnails.
        StringBuilder all = new StringBuilder();
        for (JsonNode c : root.path("characters")) {
            String nm = c.path("name").asText("").trim();
            if (nm.isBlank()) continue;
            castNames.add(nm.toLowerCase());
            String id = c.path("id").asText("").trim();
            if (!id.isBlank()) castIds.add(id);
            String life = c.path("lifeStage").asText("").trim();
            String desc = c.path("description").asText("").trim();
            if (all.length() > 0) all.append("  ");
            all.append(nm).append(": ")
               .append(life.isBlank() ? "" : life + ", ")
               .append(desc).append(dnaClause(c));
        }
        cast = all.toString();

        log.info("Bible loaded for thumbnails: main={} chars, cast={} ({} chars), style={} chars",
                mainCharacter.length(), castNames, cast.length(), style.length());
    }

    /** Compact character-DNA emphasis (bible characters[].dna) appended to a
     *  character's description so the thumbnail keeps the iconic accessory +
     *  silhouette. Returns "" when no DNA is defined. */
    private String dnaClause(JsonNode c) {
        JsonNode dna = c.path("dna");
        if (dna.isMissingNode() || dna.isNull()) return "";
        String accessory = dna.path("accessory").asText("").trim();
        String silhouette = dna.path("silhouette").asText("").trim();
        String antiAccessory = dna.path("antiAccessory").asText("").trim();
        StringBuilder b = new StringBuilder();
        if (!accessory.isBlank()) b.append(" ALWAYS wears ").append(accessory)
                .append(" (clearly visible).");
        if (!silhouette.isBlank()) b.append(" Silhouette: ").append(silhouette).append('.');
        // Anti-swap lock — keep the OpenAI fallback path consistent with the
        // image/Veo prompts (no Pip-with-glasses, no Mo-bandana, etc.).
        if (!antiAccessory.isBlank()) b.append(" Must NEVER wear ").append(antiAccessory).append('.');
        return b.toString();
    }
}
