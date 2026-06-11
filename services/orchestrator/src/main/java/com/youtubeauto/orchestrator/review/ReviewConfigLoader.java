package com.youtubeauto.orchestrator.review;

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

/**
 * Reads bible/channel.yml -> review block at startup. Each YAML value goes
 * through Spring placeholder resolution so REVIEW_* env vars override.
 */
@Slf4j
@Component
public class ReviewConfigLoader {

    private final YAMLMapper yaml = new YAMLMapper();
    private final Environment env;

    @Value("${app.bible.path:/bible/channel.yml}")
    private String biblePath;

    @Getter
    private ReviewProperties review;

    public ReviewConfigLoader(Environment env) { this.env = env; }

    @PostConstruct
    public void load() throws IOException {
        Path p = Paths.get(biblePath);
        if (!Files.exists(p)) {
            log.warn("Bible not found at {} — using review defaults", p.toAbsolutePath());
            review = ReviewProperties.defaults();
            return;
        }
        JsonNode root = yaml.readTree(p.toFile());
        JsonNode r = root.path("review");
        if (r.isMissingNode() || r.isNull()) {
            log.warn("Bible has no review block — using defaults");
            review = ReviewProperties.defaults();
            return;
        }
        review = new ReviewProperties(
                resolveBool(r.path("afterScript"),  true),
                resolveBool(r.path("reviewImages"), true),
                resolveBool(r.path("afterAssets"),  false),
                resolveBool(r.path("beforeVeo"),    false),
                resolveBool(r.path("beforeUpload"), true),
                new ReviewProperties.Mail(
                        resolveStr(r.path("notify").path("to"),      ""),
                        resolveStr(r.path("notify").path("from"),    "noreply@yt-pipeline.local"),
                        resolveStr(r.path("notify").path("baseUrl"), "http://localhost:8080")
                )
        );
        log.info("Review gates: script={} images={} assets={} veo={} upload={} mailTo={}",
                review.afterScript(), review.reviewImages(), review.afterAssets(),
                review.beforeVeo(),   review.beforeUpload(),
                review.mail().to().isBlank() ? "(none)" : review.mail().to());
    }

    private boolean resolveBool(JsonNode n, boolean dflt) {
        String s = resolveStr(n, String.valueOf(dflt));
        return Boolean.parseBoolean(s);
    }

    private String resolveStr(JsonNode n, String dflt) {
        if (n == null || n.isMissingNode() || n.isNull()) return dflt;
        String raw = n.asText();
        if (raw == null || raw.isBlank()) return dflt;
        try {
            String resolved = env.resolveRequiredPlaceholders(raw);
            return resolved == null || resolved.isBlank() ? dflt : resolved;
        } catch (IllegalArgumentException e) {
            return dflt;
        }
    }
}
