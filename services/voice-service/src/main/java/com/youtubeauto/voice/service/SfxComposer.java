package com.youtubeauto.voice.service;

import com.youtubeauto.voice.api.dto.SynthesizeRequest;
import com.youtubeauto.voice.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

/**
 * "Sounds mode" composer: instead of TTS, each line is rendered using a
 * character-specific chicken sound effect picked from bible/sfx/{char}/
 * based on the line's emotion tag. Variants per emotion are randomly
 * cycled so consecutive scenes don't repeat.
 *
 * Falls back to a generic "content" SFX when the requested emotion has no
 * file (still better than silence — the scene gets a sonic placeholder).
 *
 * Each scene's lines are concatenated into one MP3, then re-encoded with
 * ffmpeg to apply the character's per-character gain (Pip's sharp peeps
 * get pulled down, etc.).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SfxComposer {

    /** Emotion words we can detect inside narration/dialogue text when no
     *  explicit emotion tag is provided. Roughly ordered by specificity. */
    private static final Map<String, List<String>> EMOTION_KEYWORDS = Map.of(
            "gasping",   List.of("gasp", "wow", "whoa"),
            "surprised", List.of("oh!", "huh?!", "what?!", "!"),
            "laughing",  List.of("laugh", "giggle", "haha", "hehe"),
            "thinking",  List.of("hmm", "think", "wonder"),
            "sleepy",    List.of("yawn", "sleep", "tired"),
            "excited",   List.of("yes!", "yay", "excited"),
            "agreeing",  List.of("yes", "okay", "uh-huh", "mm-hmm"),
            "confused",  List.of("?", "huh", "what")
    );

    private final VoiceProperties props;
    private final FfmpegRunner ffmpeg;

    /** Generate the SFX-based audio for one scene. */
    public void composeScene(SynthesizeRequest.SceneAudio scene, Path sceneFile, Path workDir) {
        var sfxConfig = props.bible() != null ? props.bible().voiceSfx() : null;
        String sfxRoot = sfxConfig != null && sfxConfig.rootPath() != null
                ? sfxConfig.rootPath() : "/bible/sfx";
        String fallback = sfxConfig != null && sfxConfig.fallbackEmotion() != null
                ? sfxConfig.fallbackEmotion() : "content";

        List<Path> lineFiles = new ArrayList<>();
        int idx = 0;
        for (var line : scene.lines()) {
            idx++;
            String emotion = chooseEmotion(line, fallback);
            Path src = pickClip(sfxRoot, line.speaker(), emotion, fallback);
            if (src == null) {
                log.warn("scene={} line={} no SFX for speaker={} emotion={} — emitting 1s silence",
                        scene.seq(), idx, line.speaker(), emotion);
                Path silent = workDir.resolve(
                        String.format("scene_%02d_line_%02d_silent.mp3", scene.seq(), idx));
                writeSilent(silent, 1.0);
                lineFiles.add(silent);
                continue;
            }
            // Copy with character gain + per-character pitch shift.
            // Pitch identity gives each chick a distinctive voice without
            // ElevenLabs: Pip slightly higher, Mo slightly lower, Bo wobbles.
            Path target = workDir.resolve(
                    String.format("scene_%02d_line_%02d.mp3", scene.seq(), idx));
            double gainDb = lookupGain(sfxConfig, line.speaker());
            double pitchSemitones = lookupPitch(line.speaker(), idx);
            // ffmpeg "asetrate=44100*1.06,aresample=44100" raises pitch ~1
            // semitone (each semitone = 1.0594 factor) without changing
            // duration. For natural-feeling variation we use small values.
            double rateFactor = Math.pow(2.0, pitchSemitones / 12.0);
            String pitchFilter = String.format(java.util.Locale.ROOT,
                    "asetrate=44100*%.4f,aresample=44100,atempo=%.4f",
                    rateFactor, 1.0 / rateFactor);
            ffmpeg.run(List.of(
                    "-y", "-i", src.toString(),
                    "-af", pitchFilter + ",volume=" + gainDb + "dB",
                    "-c:a", "libmp3lame", "-q:a", "4",
                    target.toString()
            ), workDir);
            lineFiles.add(target);
            log.debug("scene={} line={} speaker={} emotion={} -> {}",
                    scene.seq(), idx, line.speaker(), emotion, src.getFileName());
        }

        // Concat the per-line SFX into the scene audio.
        if (lineFiles.size() == 1) {
            try { Files.move(lineFiles.get(0), sceneFile, StandardCopyOption.REPLACE_EXISTING); }
            catch (IOException e) { throw new IllegalStateException(e); }
        } else {
            concatList(lineFiles, sceneFile, workDir);
            for (Path p : lineFiles) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
        }
    }

    private String chooseEmotion(SynthesizeRequest.Line line, String fallback) {
        if (line.emotion() != null && !line.emotion().isBlank()) {
            return line.emotion().toLowerCase();
        }
        // Heuristic: scan the line text for emotion keywords.
        String t = line.text() == null ? "" : line.text().toLowerCase();
        for (var e : EMOTION_KEYWORDS.entrySet()) {
            for (String kw : e.getValue()) {
                if (t.contains(kw)) return e.getKey();
            }
        }
        return fallback;
    }

    private Path pickClip(String sfxRoot, String speaker, String emotion, String fallback) {
        Path dir = Paths.get(sfxRoot, speaker.toLowerCase());
        if (!Files.isDirectory(dir)) return null;
        List<Path> candidates = listClips(dir, emotion);
        if (candidates.isEmpty() && !emotion.equals(fallback)) {
            candidates = listClips(dir, fallback);
        }
        if (candidates.isEmpty()) {
            // Last resort: any clip in this character's folder.
            try (var stream = Files.list(dir)) {
                candidates = stream.filter(p -> {
                    String n = p.getFileName().toString().toLowerCase();
                    return n.endsWith(".mp3") || n.endsWith(".wav");
                }).collect(Collectors.toList());
            } catch (IOException ignored) {}
        }
        if (candidates.isEmpty()) return null;
        return candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
    }

    private List<Path> listClips(Path dir, String emotion) {
        try (var stream = Files.list(dir)) {
            return stream.filter(p -> {
                String n = p.getFileName().toString().toLowerCase();
                return n.startsWith(emotion.toLowerCase() + "-")
                        && (n.endsWith(".mp3") || n.endsWith(".wav"));
            }).collect(Collectors.toList());
        } catch (IOException e) {
            return List.of();
        }
    }

    /** Per-character pitch identity in semitones. Subtle but distinctive:
     *   Pip:  +1.0 (a bit higher, baby-energetic)
     *   Mo:  -0.8 (a bit lower, warm-thoughtful)
     *   Bo:  ±0.6 wobble (per-clip random, mischievous unpredictable)
     */
    private double lookupPitch(String speaker, int lineIdx) {
        if (speaker == null) return 0.0;
        return switch (speaker.toLowerCase()) {
            case "pip" -> 1.0;
            case "mo"  -> -0.8;
            case "bo"  -> {
                // Stable but varying per-clip wobble: use line index to seed.
                double phase = (lineIdx * 1.618) % 1.0;
                yield (phase - 0.5) * 1.2;   // range -0.6 to +0.6
            }
            default    -> 0.0;
        };
    }

    private double lookupGain(VoiceProperties.BibleVoiceSfx cfg, String speaker) {
        if (cfg == null || cfg.gainDb() == null) return 0.0;
        Double v = cfg.gainDb().get(speaker.toLowerCase());
        return v == null ? 0.0 : v;
    }

    private void concatList(List<Path> inputs, Path target, Path workDir) {
        Path concatFile = workDir.resolve("sfx_concat_" + System.nanoTime() + ".txt");
        try {
            List<String> lines = inputs.stream()
                    .map(p -> "file '" + p.toAbsolutePath().toString().replace("'", "'\\''") + "'")
                    .collect(Collectors.toList());
            Files.write(concatFile, lines);
            ffmpeg.run(List.of(
                    "-y", "-f", "concat", "-safe", "0",
                    "-i", concatFile.toString(),
                    "-c:a", "libmp3lame", "-q:a", "4",
                    target.toString()
            ), workDir);
        } catch (IOException e) {
            throw new IllegalStateException("Concat write failed", e);
        } finally {
            try { Files.deleteIfExists(concatFile); } catch (IOException ignored) {}
        }
    }

    private void writeSilent(Path target, double seconds) {
        ffmpeg.run(List.of(
                "-y", "-f", "lavfi", "-i", "anullsrc=channel_layout=mono:sample_rate=44100",
                "-t", String.valueOf(seconds),
                "-c:a", "libmp3lame", "-q:a", "4",
                target.toString()
        ), target.getParent());
    }
}
