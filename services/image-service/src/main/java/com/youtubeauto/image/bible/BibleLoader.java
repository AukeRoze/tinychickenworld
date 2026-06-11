package com.youtubeauto.image.bible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Component
public class BibleLoader {

    private final YAMLMapper yaml = new YAMLMapper();
    private final Environment env;

    @Value("${app.bible.path:./bible/channel.yml}")
    private String biblePath;

    @Getter
    private ChannelBible bible;

    public BibleLoader(Environment env) { this.env = env; }

    @PostConstruct
    public void load() throws IOException {
        Path p = Paths.get(biblePath);
        if (!Files.exists(p)) {
            log.warn("Bible not found at {} — using empty defaults", p.toAbsolutePath());
            bible = new ChannelBible("", List.of(), List.of(), defaultImageGen());
            return;
        }
        JsonNode root = yaml.readTree(p.toFile());

        String style = root.path("visualStyle").path("description").asText("").trim();

        List<Character> chars = new ArrayList<>();
        for (JsonNode c : root.path("characters")) {
            chars.add(new Character(
                    c.path("id").asText(),
                    c.path("name").asText(),
                    c.path("description").asText().trim(),
                    c.path("triggerWord").asText(""),
                    c.path("lifeStage").asText("").trim(),
                    parseDna(c.path("dna"))
            ));
        }

        List<Location> locs = new ArrayList<>();
        for (JsonNode l : root.path("locations")) {
            locs.add(new Location(
                    l.path("id").asText(),
                    l.path("name").asText(),
                    l.path("description").asText().trim()
            ));
        }

        ImageGenConfig imageGen = parseImageGen(root.path("imageGen"));

        // World context — overview + time-of-day moods + weather moods.
        String worldOverview = root.path("world").path("overview").asText("").trim();
        List<ChannelBible.WorldMood> tod = new ArrayList<>();
        for (JsonNode t : root.path("timeOfDay")) {
            tod.add(new ChannelBible.WorldMood(
                    t.path("id").asText(),
                    t.path("label").asText(""),
                    t.path("description").asText("").trim()));
        }
        List<ChannelBible.WorldMood> wx = new ArrayList<>();
        for (JsonNode w : root.path("weather")) {
            wx.add(new ChannelBible.WorldMood(
                    w.path("id").asText(),
                    w.path("label").asText(""),
                    w.path("description").asText("").trim()));
        }

        bible = new ChannelBible(style, chars, locs, imageGen, worldOverview, tod, wx);
        log.info("Loaded bible: {} characters, {} locations, {} TOD, {} weather, image provider={}",
                chars.size(), locs.size(), tod.size(), wx.size(), imageGen.provider());
    }

    private Character.Dna parseDna(JsonNode n) {
        if (n == null || n.isMissingNode() || n.isNull()) return Character.Dna.empty();
        return new Character.Dna(
                n.path("coreColor").asText("").trim(),
                n.path("silhouette").asText("").trim(),
                n.path("accessory").asText("").trim(),
                n.path("tic").asText("").trim(),
                n.path("signatureSound").asText("").trim(),
                n.path("feathers").asText("").trim(),
                n.path("build").asText("").trim(),
                n.path("weight").asText("").trim(),
                n.path("eyeColor").asText("").trim(),
                n.path("antiAccessory").asText("").trim()
        );
    }

    private ImageGenConfig parseImageGen(JsonNode node) {
        if (node.isMissingNode() || node.isNull()) return defaultImageGen();
        String provider = resolve(node.path("provider").asText("openai"));
        JsonNode r = node.path("replicate");
        ImageGenConfig.Replicate rep = new ImageGenConfig.Replicate(
                resolve(r.path("model").asText("")),
                resolve(r.path("castLoraUrl").asText("")),
                r.path("castLoraScale").asDouble(0.95),
                r.path("width").asInt(1280),
                r.path("height").asInt(720),
                r.path("numInferenceSteps").asInt(28),
                r.path("guidanceScale").asDouble(3.5)
        );
        return new ImageGenConfig(provider == null || provider.isBlank() ? "openai" : provider, rep);
    }

    private ImageGenConfig defaultImageGen() {
        return new ImageGenConfig("openai",
                new ImageGenConfig.Replicate("", "", 0.95, 1280, 720, 28, 3.5));
    }

    private String resolve(String raw) {
        if (raw == null || raw.isBlank()) return raw;
        try { return env.resolveRequiredPlaceholders(raw); }
        catch (IllegalArgumentException e) { return ""; }
    }
}
