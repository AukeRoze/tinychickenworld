package com.youtubeauto.voice.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Locale;

/**
 * Foley layer (board-audit #17): the world used to be silent outside dialogue
 * and ambient — no straw rustle, no egg tap, no thud on the fall. This mixes a
 * matching foley clip under a scene whose dialogue mentions a CONTACT action
 * (the same verb list that triggers the Veo ground-physics cue, so sound and
 * image agree on when the world is touched).
 *
 * Dormant-by-default, like the transition whoosh: clips live in
 * {@code /bible/sfx/foley/<verb>.mp3} (e.g. foley/dig.mp3, foley/hop.mp3,
 * foley/knock.mp3). No file = no mix = behaviour unchanged. Drop clips in to
 * switch the feature on, per verb.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class FoleyMixer {

    /** Mirror of VeoPromptCompiler.CONTACT_VERBS — keep in sync. */
    private static final String[] CONTACT_VERBS = {
            "dig", "scratch", "splash", "dive", "hop", "jump", "stomp", "kick",
            "slip", "roll", "climb", "peck", "land", "bounce", "paddle", "wade",
            "scuttle", "scamper", "tumble", "skid", "burrow", "pounce", "knock", "tap"
    };

    private static final String FOLEY_DIR = "/bible/sfx/foley";
    private static final double FOLEY_VOLUME = 0.45;
    private static final int DELAY_MS = 300;   // just after the line starts

    private final FfmpegRunner ffmpeg;

    /** Mixes at most ONE foley clip (the first matching verb) under the scene
     *  audio. Best-effort: any failure leaves the original file untouched. */
    public void mix(Path sceneFile, String sceneText, Path workDir) {
        try {
            if (sceneText == null || sceneText.isBlank()) return;
            if (!Files.isDirectory(Paths.get(FOLEY_DIR))) return;   // feature off
            String t = sceneText.toLowerCase(Locale.ROOT);
            Path clip = null;
            String hit = null;
            for (String v : CONTACT_VERBS) {
                if (!t.contains(v)) continue;
                Path p = Paths.get(FOLEY_DIR, v + ".mp3");
                if (Files.isReadable(p)) { clip = p; hit = v; break; }
            }
            if (clip == null) return;
            Path out = workDir.resolve("foley_" + System.nanoTime() + ".mp3");
            ffmpeg.run(List.of(
                    "-y", "-i", sceneFile.toString(), "-i", clip.toString(),
                    "-filter_complex",
                    String.format(Locale.ROOT,
                            "[1:a]adelay=%d|%d,volume=%.2f,aresample=44100[f];"
                            + "[0:a][f]amix=inputs=2:duration=first:normalize=0[m]",
                            DELAY_MS, DELAY_MS, FOLEY_VOLUME),
                    "-map", "[m]", "-c:a", "libmp3lame", "-q:a", "4",
                    out.toString()), workDir);
            Files.move(out, sceneFile, StandardCopyOption.REPLACE_EXISTING);
            log.debug("Foley '{}' mixed into {}", hit, sceneFile.getFileName());
        } catch (Exception e) {
            log.warn("Foley mix skipped ({}) — scene audio unchanged", e.getMessage());
        }
    }
}
