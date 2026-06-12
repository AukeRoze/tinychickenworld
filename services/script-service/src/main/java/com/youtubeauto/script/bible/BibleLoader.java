package com.youtubeauto.script.bible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class BibleLoader {

    private final YAMLMapper yaml = new YAMLMapper();

    @Value("${app.bible.path:./bible/channel.yml}")
    private String biblePath;

    @Getter
    private ChannelBible bible;

    @PostConstruct
    public void load() throws IOException {
        Path p = Paths.get(biblePath);
        if (!Files.exists(p)) {
            log.warn("Bible not found at {} — characters/locations will be empty", p.toAbsolutePath());
            bible = new ChannelBible("", "", List.of(), List.of(), List.of(),
                    EpisodeStructure.empty(), "", "", SeriesMythology.empty());
            return;
        }
        JsonNode root = yaml.readTree(p.toFile());

        String name = root.path("channel").path("name").asText("");
        String audience = root.path("channel").path("audience").asText("");

        List<BibleCharacter> chars = new ArrayList<>();
        for (JsonNode c : root.path("characters")) {
            List<String> openers = new ArrayList<>();
            for (JsonNode o : c.path("catchphrases").path("opener")) openers.add(o.asText());
            List<String> closers = new ArrayList<>();
            for (JsonNode cl : c.path("catchphrases").path("closer")) closers.add(cl.asText());
            chars.add(new BibleCharacter(
                    c.path("id").asText(),
                    c.path("name").asText(),
                    c.path("role").asText(""),
                    c.path("personality").asText(""),
                    openers,
                    closers
            ));
        }

        List<BibleLocation> locs = new ArrayList<>();
        for (JsonNode l : root.path("locations")) {
            locs.add(new BibleLocation(l.path("id").asText(), l.path("name").asText()));
        }

        List<StoryArc> arcs = new ArrayList<>();
        for (JsonNode a : root.path("storyArcs")) {
            List<String> beats = new ArrayList<>();
            for (JsonNode b : a.path("beats")) beats.add(b.asText());
            arcs.add(new StoryArc(
                    a.path("id").asText(),
                    a.path("label").asText(""),
                    beats
            ));
        }

        EpisodeStructure structure = parseEpisodeStructure(root.path("episodeStructure"));

        // Editable persona blocks (optional) — tune the writer's voice + comedy
        // style from the bible instead of recompiling.
        String storyWriter = root.path("personas").path("storyWriter").asText("").trim();
        String humorSpecialist = root.path("personas").path("humorSpecialist").asText("").trim();

        SeriesMythology mythology = parseSeriesMythology(root.path("seriesMythology"));

        bible = new ChannelBible(name, audience, chars, locs, arcs, structure,
                storyWriter, humorSpecialist, mythology);
        log.info("Loaded bible '{}': {} characters, {} locations, {} story arcs, {} episode phases, "
                        + "personas[storyWriter={}, humorSpecialist={}], mythology[ritual={}, gags={}]",
                name, chars.size(), locs.size(), arcs.size(), structure.phases().size(),
                storyWriter.isBlank() ? "default" : "custom",
                humorSpecialist.isBlank() ? "default" : "custom",
                !mythology.openingRitual().isBlank(), mythology.runningGags().size());
    }

    /**
     * Parses the optional {@code seriesMythology} block: an {@code openingRitual}
     * scalar plus a {@code runningGags} map of characterId → gag description.
     * Missing/empty block → {@link SeriesMythology#empty()} (older bibles keep
     * working; PromptBuilder then skips the section entirely).
     */
    private SeriesMythology parseSeriesMythology(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return SeriesMythology.empty();
        String ritual = node.path("openingRitual").asText("").trim();
        java.util.Map<String, String> gags = new java.util.LinkedHashMap<>();
        JsonNode gagsNode = node.path("runningGags");
        if (gagsNode.isObject()) {
            gagsNode.fields().forEachRemaining(e -> {
                String v = e.getValue().asText("").trim();
                if (!v.isEmpty()) gags.put(e.getKey(), v);
            });
        }
        return new SeriesMythology(ritual, gags);
    }

    private EpisodeStructure parseEpisodeStructure(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return EpisodeStructure.empty();
        int total = node.path("totalSecondsTarget").asInt(75);
        int rehook = node.path("rules").path("rehookEverySeconds").asInt(12);
        int minScenes = node.path("rules").path("minScenesTotal").asInt(12);
        int maxScenes = node.path("rules").path("maxScenesTotal").asInt(18);
        List<EpisodePhase> phases = new ArrayList<>();
        for (JsonNode p : node.path("phases")) {
            List<String> reqs = new ArrayList<>();
            for (JsonNode r : p.path("requirements")) reqs.add(r.asText());
            phases.add(new EpisodePhase(
                    p.path("id").asText(),
                    p.path("label").asText(""),
                    p.path("seconds").asInt(0),
                    p.path("minScenes").asInt(1),
                    p.path("maxScenes").asInt(3),
                    p.path("sceneType").asText("standard"),
                    reqs
            ));
        }
        return new EpisodeStructure(total, phases, rehook, minScenes, maxScenes);
    }
}
