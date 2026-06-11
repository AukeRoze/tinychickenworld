package com.youtubeauto.voice.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

@ConfigurationProperties(prefix = "app")
public record VoiceProperties(
        /** Voice mode. "silent" = empty MP3 placeholders, "elevenlabs" = TTS,
         *  "sounds" = character-specific chicken SFX from sfx library. */
        String mode,
        ElevenLabs elevenlabs,
        Sfx sfx,
        Narrator narrator,
        Storage storage,
        /** Per-location ambient loop paths (relative to sfx root). Populated
         *  from the bible at runtime if present; can stay empty here. */
        Map<String, String> ambientByLocation
) {
    // Compact canonical constructor — keeps VoiceProperties a SINGLE-constructor
    // record so Spring Boot's @ConfigurationProperties constructor binding works
    // (a second constructor made binding ambiguous → "No default constructor").
    // Also defaults ambientByLocation so it's never null at runtime.
    public VoiceProperties {
        if (ambientByLocation == null) ambientByLocation = Map.of();
    }

    public record ElevenLabs(
            /** When false (or mode != elevenlabs), voice-service skips
             *  ElevenLabs entirely. */
            boolean enabled,
            String baseUrl, String apiKey, String voiceId,
            String modelId, double stability, double similarityBoost,
            int timeoutSeconds
    ) {}

    /** Sounds-mode config — see bible.voice.sfx for documentation. */
    public record Sfx(
            String rootPath,
            double defaultClipDuration,
            int maxLoopRepeat,
            Map<String, Double> gainDb,
            String fallbackEmotion
    ) {}

    /** Optional narrator config — short TTS lines mixed in at hook/closer phases. */
    public record Narrator(
            boolean enabled,
            String tts,           // espeak | elevenlabs
            List<String> phases,  // which phases get narrator (eg hook, closer)
            Map<String, String> phrases,
            double voiceGainDb,
            double musicDuck
    ) {}

    public record Storage(String workRoot) {}

    // Convenience: lots of code wants the SFX config directly with safe defaults.
    public BibleVoiceSfx voiceSfx() {
        return new BibleVoiceSfx(
                sfx != null && sfx.rootPath() != null ? sfx.rootPath() : "/bible/sfx",
                sfx != null && sfx.gainDb() != null ? sfx.gainDb() : Map.of(),
                sfx != null && sfx.fallbackEmotion() != null ? sfx.fallbackEmotion() : "content"
        );
    }
    public BibleVoiceSfx bible() { return voiceSfx(); }   // back-compat shim

    public record BibleVoiceSfx(String rootPath, Map<String, Double> gainDb, String fallbackEmotion) {
        public BibleVoiceSfx voiceSfx() { return this; }
    }
}
