package com.youtubeauto.voice.elevenlabs;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.voice.config.VoiceProperties;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@Component
@RequiredArgsConstructor
public class ElevenLabsClient {

    private final WebClient elevenLabsWebClient;
    private final VoiceProperties props;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Back-compat: synth with the global default delivery settings. */
    public byte[] synthesize(String text, String voiceId) {
        return synthesize(text, voiceId, new VoiceSettings(
                props.elevenlabs().stability(), props.elevenlabs().similarityBoost(), 0.0, true));
    }

    /**
     * Synthesize one line with explicit delivery settings — the per-character
     * base (bible voiceSettings) already modulated by the line's emotion. This
     * is what lets the same voice act: an excited line and a tender line use
     * different stability/style, and Pip vs Mo use different bases.
     */
    @Retry(name = "elevenlabs")
    public byte[] synthesize(String text, String voiceId, VoiceSettings settings) {
        return synthesize(text, voiceId, settings, null, null);
    }

    /**
     * Synthesize with PROSODY CONTEXT: {@code previousText}/{@code nextText} are
     * the surrounding lines of the SAME speaker, so ElevenLabs shapes intonation
     * as one continuous performance instead of resetting per line — the per-line
     * "reset" cadence was the most audible AI-tell in the ep-3 audit.
     */
    @Retry(name = "elevenlabs")
    public byte[] synthesize(String text, String voiceId, VoiceSettings settings,
                             String previousText, String nextText) {
        ObjectNode body = mapper.createObjectNode();
        body.put("text", text);
        body.put("model_id", props.elevenlabs().modelId());
        if (previousText != null && !previousText.isBlank()) {
            body.put("previous_text", previousText);
        }
        if (nextText != null && !nextText.isBlank()) {
            body.put("next_text", nextText);
        }
        ObjectNode vs = body.putObject("voice_settings");
        vs.put("stability", settings.stability());
        vs.put("similarity_boost", settings.similarityBoost());
        vs.put("style", settings.style());
        vs.put("use_speaker_boost", settings.useSpeakerBoost());

        byte[] audio = elevenLabsWebClient.post()
                .uri("/text-to-speech/{vid}", voiceId)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.parseMediaType("audio/mpeg"))
                .bodyValue(body)
                .retrieve()
                .bodyToMono(byte[].class)
                .block();

        if (audio == null || audio.length == 0) {
            throw new IllegalStateException("ElevenLabs returned empty audio");
        }
        log.debug("ElevenLabs returned {} bytes", audio.length);
        return audio;
    }
}
