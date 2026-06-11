package com.youtubeauto.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Bible-driven scene-transition styling. Reads the {@code assembly.transitions}
 * section of {@code channel.yml} so the editor can experiment with the cut
 * language (per episode phase: ffmpeg xfade type + duration) WITHOUT a
 * recompile — the file is re-read at most once a minute, so a YAML edit shows
 * up on the next render.
 *
 * <pre>
 * assembly:
 *   transitions:
 *     hook:        { type: fade,        seconds: 0.10 }
 *     climax:      { type: fadewhite,   seconds: 0.35 }
 *     closer:      { type: circleclose, seconds: 0.45 }
 *     default:     { type: fade,        seconds: 0.20 }
 * </pre>
 *
 * Unknown/typo'd xfade types are rejected with a loud log line and that phase
 * falls back to the built-in defaults in {@link Concatenator} — a config slip
 * must never produce a failed render. Missing file/section = all defaults.
 */
@Slf4j
@Component
public class TransitionConfig {

    @Value("${app.assembly.biblePath:/bible/channel.yml}")
    private String biblePath;

    public record Spec(String type, double seconds) {}

    /** Stock ffmpeg xfade transition names (ffmpeg 5/6/7). Guards the bible
     *  against typos that would otherwise fail the whole filtergraph. */
    private static final Set<String> VALID_XFADE = Set.of(
            "fade", "fadeblack", "fadewhite", "fadegrays", "dissolve", "distance",
            "wipeleft", "wiperight", "wipeup", "wipedown",
            "wipetl", "wipetr", "wipebl", "wipebr",
            "slideleft", "slideright", "slideup", "slidedown",
            "smoothleft", "smoothright", "smoothup", "smoothdown",
            "circlecrop", "rectcrop", "circleopen", "circleclose",
            "horzopen", "horzclose", "vertopen", "vertclose",
            "diagtl", "diagtr", "diagbl", "diagbr",
            "hlslice", "hrslice", "vuslice", "vdslice",
            "pixelize", "radial", "hblur", "zoomin", "squeezev", "squeezeh",
            "coverleft", "coverright", "coverup", "coverdown",
            "revealleft", "revealright", "revealup", "revealdown");

    private static final double MIN_SECONDS = 0.05;
    private static final double MAX_SECONDS = 1.50;
    private static final long   TTL_MS      = 60_000;

    private volatile Map<String, Spec> cache;
    private volatile long cacheAtMs;

    /** @return the bible's transition for this phase (or its `default` entry),
     *  empty when the bible doesn't configure one — caller uses built-ins. */
    public Optional<Spec> forPhase(String phase) {
        Map<String, Spec> m = load();
        Spec s = m.get(phase == null ? "" : phase.toLowerCase());
        if (s == null) s = m.get("default");
        return Optional.ofNullable(s);
    }

    private Map<String, Spec> load() {
        Map<String, Spec> c = cache;
        long now = System.currentTimeMillis();
        if (c != null && now - cacheAtMs < TTL_MS) return c;
        Map<String, Spec> out = new HashMap<>();
        try {
            Path p = Paths.get(biblePath);
            if (Files.exists(p)) {
                try (InputStream in = Files.newInputStream(p)) {
                    Object root = new Yaml().load(in);
                    Object section = dig(root, "assembly", "transitions");
                    if (section instanceof Map<?, ?> map) {
                        for (Map.Entry<?, ?> e : map.entrySet()) {
                            String phase = String.valueOf(e.getKey()).toLowerCase();
                            Spec spec = parseSpec(phase, e.getValue());
                            if (spec != null) out.put(phase, spec);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Could not load assembly.transitions from {} ({}) — using built-in "
                    + "transition defaults", biblePath, e.getMessage());
        }
        if (!out.isEmpty()) {
            log.info("Bible transitions active for phases {}", out.keySet());
        }
        cache = out;
        cacheAtMs = now;
        return out;
    }

    private Spec parseSpec(String phase, Object v) {
        if (!(v instanceof Map<?, ?> m)) return null;
        String type = String.valueOf(m.get("type")).trim().toLowerCase();
        if (!VALID_XFADE.contains(type)) {
            log.error("assembly.transitions.{}: '{}' is not a valid ffmpeg xfade type — "
                    + "falling back to the built-in default for this phase. Valid: {}",
                    phase, type, VALID_XFADE);
            return null;
        }
        double seconds;
        try {
            seconds = Double.parseDouble(String.valueOf(m.get("seconds")));
        } catch (Exception e) {
            log.error("assembly.transitions.{}: seconds '{}' is not a number — "
                    + "falling back for this phase", phase, m.get("seconds"));
            return null;
        }
        double clamped = Math.max(MIN_SECONDS, Math.min(MAX_SECONDS, seconds));
        if (clamped != seconds) {
            log.warn("assembly.transitions.{}: seconds {} clamped to {} (allowed {}–{})",
                    phase, seconds, clamped, MIN_SECONDS, MAX_SECONDS);
        }
        return new Spec(type, clamped);
    }

    @SuppressWarnings("unchecked")
    private static Object dig(Object root, String... keys) {
        Object cur = root;
        for (String k : keys) {
            if (!(cur instanceof Map<?, ?> m)) return null;
            cur = ((Map<String, Object>) m).get(k);
        }
        return cur;
    }
}
