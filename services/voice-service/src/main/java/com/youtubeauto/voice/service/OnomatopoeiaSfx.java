package com.youtubeauto.voice.service;

import com.youtubeauto.voice.config.VoiceProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Turns spoken sound-effect words ("Bonk!", "Boom boom!", "Tap tap tap") into
 * REAL sound effects instead of letting the TTS voice say them out loud.
 *
 * <p>For each spoken line we {@link #parse} the text into the words that should
 * actually be spoken plus the onomatopoeia that should become SFX. The
 * onomatopoeia is removed from the TTS text and the matching clip from
 * {@code bible/sfx/words/<name>.mp3} is layered into that line's audio at the
 * spot the word sat. If no clip exists for a recognised word we still strip it
 * (so the chicken never says "bonk") — it just produces no sound.
 *
 * <p>Conservative on purpose: a token only counts as an effect when it is in the
 * known vocabulary AND it looks like an effect (ends with "!", is ALL-CAPS, is
 * repeated, or is the whole line). That spares ordinary words like "tap the
 * door" or "pop in".
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OnomatopoeiaSfx {

    private final VoiceProperties props;
    private final FfmpegRunner ffmpeg;

    /** Recognised onomatopoeia → canonical clip name (file: words/<name>.mp3).
     *  Many spellings collapse onto one clip. */
    private static final Map<String, String> VOCAB = buildVocab();

    private static Map<String, String> buildVocab() {
        Map<String, String> m = new HashMap<>();
        for (String s : new String[]{"bonk", "bonik", "boink"}) m.put(s, "bonk");
        for (String s : new String[]{"boom", "kaboom", "badaboom", "bang"}) m.put(s, "boom");
        for (String s : new String[]{"tap", "taptap", "tippy", "tik", "tick"}) m.put(s, "tap");
        for (String s : new String[]{"whoosh", "woosh", "swoosh", "whooosh"}) m.put(s, "whoosh");
        for (String s : new String[]{"splat", "splot"}) m.put(s, "splat");
        for (String s : new String[]{"splash", "sploosh", "splosh"}) m.put(s, "splash");
        for (String s : new String[]{"plop", "plip", "ploop", "bloop"}) m.put(s, "plop");
        for (String s : new String[]{"pop", "poppy"}) m.put(s, "pop");
        for (String s : new String[]{"boing", "boink", "sproing", "doing"}) m.put(s, "boing");
        for (String s : new String[]{"buzz", "bzz", "bzzz", "bzzzz", "buzzz"}) m.put(s, "buzz");
        for (String s : new String[]{"thud", "thump", "bump"}) m.put(s, "thud");
        for (String s : new String[]{"crash", "smash"}) m.put(s, "crash");
        for (String s : new String[]{"ding", "dong", "ting"}) m.put(s, "ding");
        for (String s : new String[]{"zoom", "vroom", "zoooom"}) m.put(s, "zoom");
        for (String s : new String[]{"knock", "rap"}) m.put(s, "knock");
        for (String s : new String[]{"squeak", "squeek", "eek"}) m.put(s, "squeak");
        return Map.copyOf(m);
    }

    /** Result of parsing one line: text to actually speak (may be empty) plus
     *  the resolved SFX clip paths to layer in, in order. */
    public record Parsed(String spoken, List<String> clipPaths, boolean stripped) {}

    public Parsed parse(String text) {
        if (text == null || text.isBlank()) return new Parsed("", List.of(), false);
        String[] raw = text.trim().split("\\s+");
        String[] core = new String[raw.length];
        Map<String, Integer> counts = new HashMap<>();
        for (int i = 0; i < raw.length; i++) {
            core[i] = normalize(raw[i]);
            if (VOCAB.containsKey(core[i])) counts.merge(core[i], 1, Integer::sum);
        }

        List<String> spokenTokens = new ArrayList<>();
        List<String> canonRun = new ArrayList<>();
        String lastCanon = null;
        boolean stripped = false;

        for (int i = 0; i < raw.length; i++) {
            String tok = raw[i], c = core[i];
            boolean known = VOCAB.containsKey(c);
            boolean looksLikeSfx = known && (tok.endsWith("!") || isAllCaps(tok)
                    || counts.getOrDefault(c, 0) >= 2 || raw.length == 1);
            if (looksLikeSfx) {
                stripped = true;
                String canon = VOCAB.get(c);
                if (!canon.equals(lastCanon)) { canonRun.add(canon); lastCanon = canon; }
            } else {
                spokenTokens.add(tok);
                lastCanon = null;
            }
        }

        String spoken = cleanup(String.join(" ", spokenTokens));
        List<String> paths = new ArrayList<>();
        for (String canon : canonRun) {
            Path p = resolveClip(canon);
            if (p != null) paths.add(p.toString());
        }
        return new Parsed(spoken, paths, stripped);
    }

    /**
     * Builds one line's audio: the (optional) spoken part followed by its SFX
     * clips. Everything is re-encoded to a uniform mono 44.1k MP3 so the
     * scene-level concat (stream copy) stays glitch-free across all lines.
     * Empty input (a stripped-but-clipless line) yields a short silence so the
     * beat still exists.
     */
    public void composeLine(byte[] spokenMp3, List<String> clipPaths, Path lineFile, Path workDir) {
        List<Path> pieces = new ArrayList<>();
        try {
            if (spokenMp3 != null && spokenMp3.length > 0) {
                Path raw = workDir.resolve("spk_" + System.nanoTime() + ".mp3");
                Files.write(raw, spokenMp3);
                pieces.add(uniform(raw, workDir));
                Files.deleteIfExists(raw);
            }
            for (String c : clipPaths) pieces.add(uniformSfx(Paths.get(c), workDir));

            if (pieces.isEmpty()) { writeSilent(lineFile, 0.45, workDir); return; }
            if (pieces.size() == 1) {
                Files.move(pieces.remove(0), lineFile, StandardCopyOption.REPLACE_EXISTING);
                return;
            }
            concatCopy(pieces, lineFile, workDir);
        } catch (IOException e) {
            throw new IllegalStateException("composeLine failed: " + e.getMessage(), e);
        } finally {
            for (Path p : pieces) { try { Files.deleteIfExists(p); } catch (IOException ignored) {} }
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Path resolveClip(String canon) {
        var cfg = props.bible() != null ? props.bible().voiceSfx() : null;
        String root = cfg != null && cfg.rootPath() != null ? cfg.rootPath() : "/bible/sfx";
        Path p = Paths.get(root, "words", canon + ".mp3");
        if (Files.isReadable(p)) return p;
        Path wav = Paths.get(root, "words", canon + ".wav");
        return Files.isReadable(wav) ? wav : null;
    }

    private Path uniform(Path src, Path workDir) {
        Path dst = workDir.resolve("u_" + System.nanoTime() + ".mp3");
        ffmpeg.run(List.of("-y", "-i", src.toString(),
                "-ar", "44100", "-ac", "1", "-c:a", "libmp3lame", "-q:a", "4",
                dst.toString()), workDir);
        return dst;
    }

    /** Word-SFX variant of {@link #uniform}: a 150ms comic beat of silence
     *  before the clip (the pause IS the joke — Bo falls… beat… BONK) and a
     *  +5dB punch so the effect lands as a real gag instead of disappearing
     *  at voice level under the music bed (feedback ep 3: the slippery-egg
     *  fall begged for a surprising sound and got a whisper). */
    private Path uniformSfx(Path src, Path workDir) {
        Path dst = workDir.resolve("ux_" + System.nanoTime() + ".mp3");
        ffmpeg.run(List.of("-y", "-i", src.toString(),
                "-af", "adelay=150|150,volume=5dB",
                "-ar", "44100", "-ac", "1", "-c:a", "libmp3lame", "-q:a", "4",
                dst.toString()), workDir);
        return dst;
    }

    private void concatCopy(List<Path> inputs, Path out, Path workDir) throws IOException {
        Path list = workDir.resolve("oc_" + System.nanoTime() + ".txt");
        try {
            String body = inputs.stream()
                    .map(p -> "file '" + p.toAbsolutePath().toString().replace("'", "'\\''") + "'")
                    .collect(Collectors.joining("\n"));
            Files.writeString(list, body);
            ffmpeg.run(List.of("-y", "-f", "concat", "-safe", "0",
                    "-i", list.toString(), "-c", "copy", out.toString()), workDir);
        } finally {
            try { Files.deleteIfExists(list); } catch (IOException ignored) {}
        }
    }

    private void writeSilent(Path out, double seconds, Path workDir) {
        ffmpeg.run(List.of("-y", "-f", "lavfi",
                "-i", "anullsrc=channel_layout=mono:sample_rate=44100",
                "-t", String.valueOf(seconds),
                "-c:a", "libmp3lame", "-q:a", "4", out.toString()), workDir);
    }

    private static String normalize(String tok) {
        String t = tok.toLowerCase();
        t = t.replaceAll("^[^a-z]+", "").replaceAll("[^a-z]+$", "");
        return t;
    }

    private static boolean isAllCaps(String tok) {
        String letters = tok.replaceAll("[^A-Za-z]", "");
        return letters.length() >= 2 && letters.equals(letters.toUpperCase())
                && !letters.equals(letters.toLowerCase());
    }

    /** Tidy the leftover spoken text after stripping effect words. */
    private static String cleanup(String s) {
        String out = s.replaceAll("\\s+", " ").trim();
        out = out.replaceAll("\\s+([,.!?;:])", "$1");        // no space before punctuation
        out = out.replaceAll("(^|\\s)[\\-–—]+(\\s|$)", " ");  // drop orphan dashes
        out = out.replaceAll("\\s+", " ").trim();
        // Trim leading/trailing stray punctuation left dangling.
        out = out.replaceAll("^[\\-–—,;:]+\\s*", "").replaceAll("\\s*[\\-–—,;:]+$", "").trim();
        return out;
    }
}
