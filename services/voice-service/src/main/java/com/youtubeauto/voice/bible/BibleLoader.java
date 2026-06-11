package com.youtubeauto.voice.bible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.voice.config.VoiceProperties;
import com.youtubeauto.voice.elevenlabs.VoiceSettings;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Loads the channel bible and produces a flat character-id → voiceId map.
 * Placeholders in the YAML (e.g. ${VOICE_ID_PIP}) are resolved from the
 * Spring Environment (which sees env vars). Missing values fall back to
 * the default ELEVENLABS_VOICE_ID.
 */
@Slf4j
@Component
public class BibleLoader {

    private final YAMLMapper yaml = new YAMLMapper();
    private final Environment env;
    private final VoiceProperties props;

    @Value("${app.bible.path:./bible/channel.yml}")
    private String biblePath;

    @Value("${app.elevenlabs.voice-id:}")
    private String defaultVoiceId;

    private Map<String, String> voiceMap = Map.of();
    private Map<String, VoiceSettings> voiceSettingsMap = Map.of();
    private Map<String, String> ambientByLocation = Map.of();

    public BibleLoader(Environment env, VoiceProperties props) {
        this.env = env;
        this.props = props;
    }

    /** Per-location ambient sound paths (relative to SFX root). */
    public Map<String, String> getAmbientByLocation() { return ambientByLocation; }

    @PostConstruct
    public void load() throws IOException {
        Path p = Paths.get(biblePath);
        if (!Files.exists(p)) {
            log.warn("Bible not found at {} — all lines will use default voice", p.toAbsolutePath());
            return;
        }
        JsonNode root = yaml.readTree(p.toFile());
        Map<String, String> map = new HashMap<>();
        Map<String, VoiceSettings> settings = new HashMap<>();
        VoiceSettings base = defaultVoiceSettings();
        for (JsonNode c : root.path("characters")) {
            String id = c.path("id").asText();
            String raw = c.path("voiceId").asText("");
            String resolved = resolve(raw);
            if (resolved == null || resolved.isBlank()) {
                log.warn("Character '{}' has no voiceId — falling back to default voice", id);
                resolved = defaultVoiceId;
            }
            map.put(id, resolved);

            // Per-character delivery (bible characters[].voiceSettings). Missing
            // fields fall back to the global ElevenLabs defaults so a partially
            // configured character still works. This is what makes Pip sound
            // excitable and Mo calm instead of every chick sounding identical.
            JsonNode vs = c.path("voiceSettings");
            settings.put(id, new VoiceSettings(
                    vs.path("stability").asDouble(base.stability()),
                    vs.path("similarity_boost").asDouble(base.similarityBoost()),
                    vs.path("style").asDouble(base.style()),
                    vs.path("use_speaker_boost").asBoolean(base.useSpeakerBoost())));
        }
        voiceMap = Map.copyOf(map);
        voiceSettingsMap = Map.copyOf(settings);

        // Ambient sound per location.
        Map<String, String> ambient = new HashMap<>();
        JsonNode amb = root.path("ambientByLocation");
        if (amb.isObject()) {
            var fieldIt = amb.fields();
            while (fieldIt.hasNext()) {
                var entry = fieldIt.next();
                ambient.put(entry.getKey(), entry.getValue().asText(""));
            }
        }
        ambientByLocation = Map.copyOf(ambient);

        log.info("Loaded {} character voices, {} ambient locations",
                voiceMap.size(), ambientByLocation.size());
    }

    /** Returns the voice id for a character, or the default if unknown. */
    public String voiceFor(String characterId) {
        if (characterId == null || characterId.isBlank()) return defaultVoiceId;
        return Optional.ofNullable(voiceMap.get(characterId)).orElse(defaultVoiceId);
    }

    /** Returns the per-character base delivery settings, or the global defaults
     *  for an unknown character. Callers layer the per-line emotion on top via
     *  {@link VoiceSettings#withEmotion(String)}. */
    public VoiceSettings voiceSettingsFor(String characterId) {
        if (characterId != null) {
            VoiceSettings s = voiceSettingsMap.get(characterId);
            if (s != null) return s;
        }
        return defaultVoiceSettings();
    }

    /** Global ElevenLabs defaults from config; style defaults to 0 (neutral) and
     *  speaker-boost on. Used when a character has no (or partial) voiceSettings. */
    private VoiceSettings defaultVoiceSettings() {
        VoiceProperties.ElevenLabs el = props == null ? null : props.elevenlabs();
        double stab = el != null ? el.stability() : 0.5;
        double sim  = el != null ? el.similarityBoost() : 0.8;
        return new VoiceSettings(stab, sim, 0.0, true);
    }

    private String resolve(String raw) {
        if (raw == null) return null;
        try { return env.resolveRequiredPlaceholders(raw); }
        catch (IllegalArgumentException unresolved) {
            // ${VAR} without default and var not set — treat as missing
            return "";
        }
    }
}
