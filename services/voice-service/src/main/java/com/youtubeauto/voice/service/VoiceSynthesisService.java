package com.youtubeauto.voice.service;

import com.youtubeauto.voice.api.dto.SynthesizeRequest;
import com.youtubeauto.voice.api.dto.SynthesizeResponse;
import com.youtubeauto.voice.bible.BibleLoader;
import com.youtubeauto.voice.config.VoiceProperties;
import com.youtubeauto.voice.elevenlabs.ElevenLabsClient;
import com.youtubeauto.voice.elevenlabs.ProsodyContext;
import com.youtubeauto.voice.elevenlabs.VoiceSettings;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class VoiceSynthesisService {

    private final ElevenLabsClient client;
    private final VoiceProperties props;
    private final BibleLoader bibleLoader;
    private final AudioConcat audioConcat;
    private final SilentAudio silent;
    private final SfxComposer sfxComposer;
    private final AmbientMixer ambientMixer;
    private final OnomatopoeiaSfx onomatopoeia;
    private final FoleyMixer foley;

    public SynthesizeResponse synthesize(SynthesizeRequest req) {
        Path dir = Paths.get(props.storage().workRoot(), req.jobId().toString(), "audio");
        try { Files.createDirectories(dir); }
        catch (IOException e) { throw new IllegalStateException(e); }

        // Mode resolution. The mode property (app.mode) takes priority; if not
        // set, fall back to legacy elevenlabs.enabled flag for backwards compat.
        String mode = (props.mode() != null && !props.mode().isBlank())
                ? props.mode().toLowerCase()
                : (props.elevenlabs() != null && props.elevenlabs().enabled() ? "elevenlabs" : "silent");
        log.info("job={} voice mode = {}", req.jobId(), mode);

        List<SynthesizeResponse.SceneAudio> results = new ArrayList<>();

        // Episode-order flat line list + each scene's offset into it, so the
        // prosody-context window (ProsodyContext: same speaker, same scene +
        // adjacent scene) can look across scene boundaries. Flag off = no
        // context fields at all → request body identical to context-less synth.
        boolean contextEnabled = props.elevenlabs() != null
                && Boolean.TRUE.equals(props.elevenlabs().contextEnabled());
        List<ProsodyContext.Entry> allLines = new ArrayList<>();
        Map<Integer, Integer> sceneOffset = new HashMap<>();
        for (SynthesizeRequest.SceneAudio s : req.scenes()) {
            sceneOffset.put(s.seq(), allLines.size());
            if (s.lines() != null) {
                for (SynthesizeRequest.Line l : s.lines()) {
                    allLines.add(new ProsodyContext.Entry(s.seq(), l.speaker(), l.text()));
                }
            }
        }

        for (SynthesizeRequest.SceneAudio scene : req.scenes()) {
            Path sceneFile = dir.resolve(String.format("scene_%02d.mp3", scene.seq()));
            List<SynthesizeResponse.LineTiming> lineTimings = List.of();

            if (scene.lines() == null || scene.lines().isEmpty()) {
                // SILENT VISUAL BEAT — the scene acts without dialogue; the
                // ambient layer (below) + the music bed carry the moment.
                // Sized to the scripted duration so the edit holds the pause.
                double sec = Math.max(2.0,
                        (scene.durationSeconds() == null ? 4 : scene.durationSeconds()) - 0.4);
                silent.writeSilent(sec, sceneFile);
            } else if ("silent".equals(mode)) {
                // Silent placeholder mode — skip both TTS and SFX.
                List<String> texts = scene.lines().stream()
                        .map(SynthesizeRequest.Line::text).toList();
                double sec = silent.estimateSeconds(texts);
                silent.writeSilent(sec, sceneFile);
            } else if ("sounds".equals(mode)) {
                // Sounds mode — pick character SFX from bible/sfx/{char}/{emotion}.mp3
                sfxComposer.composeScene(scene, sceneFile, dir);
            } else {
                // Normal path: per-line ElevenLabs synth, then concat.
                // Sound-effect words ("Bonk!", "Boom boom!") are stripped from the
                // spoken text and rendered as REAL SFX clips so the chicken voice
                // never says them out loud.
                List<Path> lineFiles = new ArrayList<>();
                lineTimings = new ArrayList<>();
                long cumMs = 0;
                int lineIdx = 0;
                List<SynthesizeRequest.Line> sceneLines = scene.lines();
                for (int li = 0; li < sceneLines.size(); li++) {
                    SynthesizeRequest.Line line = sceneLines.get(li);
                    String voiceId = bibleLoader.voiceFor(line.speaker());
                    // Per-character base delivery (bible) modulated by this line's
                    // emotion → the voice ACTS instead of reading flat.
                    VoiceSettings settings = bibleLoader.voiceSettingsFor(line.speaker())
                            .withEmotion(line.emotion());
                    OnomatopoeiaSfx.Parsed parsed = onomatopoeia.parse(line.text());
                    // Prosody context: the nearest line of the SAME speaker before/
                    // after this one (window = this scene + the adjacent scene,
                    // capped at 300 chars/side), so intonation flows as one
                    // performance instead of resetting per request.
                    String prevText = null, nextText = null;
                    if (contextEnabled) {
                        int flatIdx = sceneOffset.get(scene.seq()) + li;
                        ProsodyContext.Context ctx = ProsodyContext.contextFor(allLines, flatIdx);
                        prevText = ctx.previousText();
                        nextText = ctx.nextText();
                    }
                    byte[] spoken = parsed.spoken().isBlank()
                            ? null : client.synthesize(parsed.spoken(), voiceId, settings,
                                                       prevText, nextText);
                    Path lineFile = dir.resolve(
                            String.format("scene_%02d_line_%02d.mp3", scene.seq(), ++lineIdx));
                    onomatopoeia.composeLine(spoken, parsed.clipPaths(), lineFile, dir);
                    lineFiles.add(lineFile);
                    // Record this line's slot in the scene audio (the concat below
                    // is gapless, so cumulative duration IS the start offset).
                    long durMs = Math.round(probeSeconds(lineFile, dir) * 1000);
                    lineTimings.add(new SynthesizeResponse.LineTiming(
                            line.speaker(), line.text(), cumMs, durMs));
                    cumMs += durMs;
                    log.debug("job={} scene={} line={} speaker={} emotion={} stab={} style={} spoken='{}' sfx={}",
                            req.jobId(), scene.seq(), lineIdx, line.speaker(), line.emotion(),
                            settings.stability(), settings.style(),
                            parsed.spoken(), parsed.clipPaths().size());
                }
                audioConcat.concat(lineFiles, sceneFile, dir);
                for (Path lf : lineFiles) {
                    try { Files.deleteIfExists(lf); } catch (IOException ignored) {}
                }
            }

            // Foley (board #17): one matching contact-sound under the scene
            // when the dialogue mentions a physical action. Dormant until
            // clips exist in /bible/sfx/foley/.
            if (scene.lines() != null && !scene.lines().isEmpty()) {
                StringBuilder allText = new StringBuilder();
                for (SynthesizeRequest.Line l : scene.lines()) {
                    if (l.text() != null) allText.append(l.text()).append(' ');
                }
                foley.mix(sceneFile, allText.toString(), dir);
            }

            // Mix ambient sound layer under the main audio. Skips silently
            // when the scene has no location or the location has no ambient
            // file configured. A weather bed (ambient/{weather}.mp3) overrides
            // the location bed so picture (fx/weather overlay) and sound match.
            boolean hasLocation = scene.locationId() != null && !scene.locationId().isBlank();
            boolean hasWeather = scene.weather() != null && !scene.weather().isBlank();
            if (hasLocation || hasWeather) {
                Path ambientOut = dir.resolve(String.format("scene_%02d_amb.mp3", scene.seq()));
                try {
                    Path mixed = ambientMixer.mix(sceneFile, scene.locationId(), scene.weather(),
                            ambientOut, dir);
                    if (!mixed.equals(sceneFile) && Files.exists(mixed)) {
                        Files.move(mixed, sceneFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                } catch (Exception e) {
                    log.warn("ambient mix failed for scene={}: {} — keeping unmixed audio",
                            scene.seq(), e.getMessage());
                }
            }

            long size;
            try { size = Files.size(sceneFile); } catch (IOException e) { size = -1; }
            results.add(new SynthesizeResponse.SceneAudio(
                    scene.seq(), sceneFile.toString(), size, lineTimings));
            log.info("job={} scene={} -> {} ({} lines, mode={}, location={})",
                    req.jobId(), scene.seq(), sceneFile, scene.lines().size(), mode, scene.locationId());
        }
        return new SynthesizeResponse(req.jobId(), results);
    }

    /** Audio duration in seconds via ffprobe; 0 on failure (the cue then
     *  collapses harmlessly and the SRT falls back to scene-level timing). */
    private static double probeSeconds(Path audio, Path workDir) {
        try {
            Process p = new ProcessBuilder(
                    "ffprobe", "-v", "error", "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1", audio.toString())
                    .directory(workDir.toFile()).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Double.parseDouble(out);
        } catch (Exception e) {
            return 0;
        }
    }

}
