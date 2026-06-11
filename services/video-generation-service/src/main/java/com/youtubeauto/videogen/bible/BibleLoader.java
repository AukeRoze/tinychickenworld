package com.youtubeauto.videogen.bible;

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
import java.util.ArrayList;
import java.util.List;

/**
 * Loads videoGen section of bible/channel.yml. Falls back to sensible
 * defaults if the file is missing or the section is absent — keeps the
 * service bootable in test/dev without a bible mount.
 */
@Slf4j
@Component
public class BibleLoader {

    private final YAMLMapper yaml = new YAMLMapper();

    @Value("${app.bible.path:/bible/channel.yml}")
    private String biblePath;

    @Getter
    private VideoGenConfig videoGen;

    @PostConstruct
    public void load() throws IOException {
        Path p = Paths.get(biblePath);
        if (!Files.exists(p)) {
            log.warn("Bible not found at {} — using defaults", p.toAbsolutePath());
            videoGen = VideoGenConfig.defaults();
            return;
        }
        JsonNode root = yaml.readTree(p.toFile());
        JsonNode vg = root.path("videoGen");
        if (vg.isMissingNode() || vg.isNull()) {
            log.warn("Bible has no videoGen section — using defaults");
            videoGen = VideoGenConfig.defaults();
            return;
        }
        videoGen = parse(vg);
        log.info("Loaded videoGen: defaultMode={}, defaultModel={}, costCap=€{}",
                videoGen.defaultMode(),
                videoGen.veo().defaultModel(),
                videoGen.veo().costCapEurPerVideo());
    }

    private VideoGenConfig parse(JsonNode vg) {
        VideoGenConfig def = VideoGenConfig.defaults();
        String defaultMode = vg.path("defaultMode").asText(def.defaultMode());
        JsonNode veo = vg.path("veo");

        List<VideoGenConfig.Routing> routes = new ArrayList<>();
        for (JsonNode r : veo.path("routing")) {
            routes.add(new VideoGenConfig.Routing(
                    r.path("sceneType").asText(),
                    r.path("model").asText(null),
                    r.path("maxSeconds").isMissingNode() ? null : r.path("maxSeconds").asInt()
            ));
        }
        if (routes.isEmpty()) routes = def.veo().routing();

        VideoGenConfig.Veo v = new VideoGenConfig.Veo(
                veo.path("defaultModel").asText(def.veo().defaultModel()),
                veo.path("heroModel").asText(def.veo().heroModel()),
                veo.path("heroQuality").asText(def.veo().heroQuality()),
                veo.path("fallbackModel").asText(def.veo().fallbackModel()),
                veo.path("maxClipSeconds").asInt(def.veo().maxClipSeconds()),
                veo.path("audio").asBoolean(def.veo().audio()),
                veo.path("costCapEurPerVideo").asDouble(def.veo().costCapEurPerVideo()),
                routes
        );
        return new VideoGenConfig(defaultMode, v);
    }
}
