package com.youtubeauto.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Bakes the branded intro. Takes the Veo "chickens introduce themselves" clip
 * (≈8s) and flies the channel LOGO into the TOP-LEFT corner with a quick
 * ease-out swoop + sparkle — no title text, no egg. (Kijkersfeedback
 * 2026-06-12: de letter-voor-letter "TINY CHICKEN WORLD"-tekst + het gouden ei
 * mochten eruit; het logo zegt hetzelfde in één beeld en houdt de open rustig.
 * Zelfde hoekpositie als het outro-logo, dus de branding bookend't de video.)
 *
 * Everything is a single ffmpeg pass over the clip — cheap and re-runnable
 * without re-generating the (paid) Veo clip.
 */
@Slf4j
@Service
public class IntroBuilder {

    @Value("${app.brand.intro-path:/bible/intro.mp4}")
    private String introPath;
    /** Channel logo (transparant, de-haloed) — zelfde asset als het outro-logo. */
    @Value("${app.brand.logo:/bible/logo.png}")
    private String logo;
    @Value("${app.brand.title-sparkle:/bible/sfx/intro/title_sparkle.mp3}")
    private String sparkle;
    @Value("${app.ffmpeg-bin:ffmpeg}")
    private String ffmpeg;
    @Value("${app.ffprobe-bin:ffprobe}")
    private String ffprobe;

    // SHORT INTRO (kids-YouTube retention: branding ≤5s, the hook must own
    // the first 15s). The clip stays LIVE under the logo (no freeze); the
    // logo swoops in early, one greeting per chick, done.
    private static final double LOGO_AT    = 1.0;   // logo starts its fly-in
    private static final double FLY_DUR    = 0.7;   // swoop duration (ease-out)
    private static final double HOLD_AFTER = 1.4;   // beat after the logo lands

    // Logo landing position (top-left) + size — mirrors the outro logo
    // (OutroBuilder: scale 220, x=56, y=48) so the branding bookends the video.
    private static final int LOGO_W = 240;
    private static final int LOGO_X = 56;
    private static final int LOGO_Y = 48;
    // Fly-in start: just off-screen beyond the top-left corner.
    private static final int LOGO_FROM_X = -320;
    private static final int LOGO_FROM_Y = -320;

    /** Back-compat: no spoken-voice track (keeps the Veo clip's own audio). */
    public String build(String clipPath) {
        return build(clipPath, List.of());
    }

    /**
     * @param voiceLines ordered ElevenLabs (or sounds-mode) MP3s of the chickens
     *   introducing themselves — Pip, then Mo, then Bo. When non-empty these
     *   REPLACE the Veo clip's own synthetic audio (Veo's voices are off-brand
     *   and inconsistent) and are placed at evenly-spaced offsets across the
     *   introduction beat so each chicken is heard in its own voice — the SAME
     *   voices used in the episodes. Empty = legacy behaviour (keep Veo audio).
     * @return the written intro path.
     */
    public String build(String clipPath, List<String> voiceLines) {
        Path clip = Paths.get(clipPath);
        if (!Files.isReadable(clip)) {
            throw new IllegalArgumentException("Chicken clip not readable: " + clipPath);
        }
        // Keep only readable voice tracks, in order.
        List<String> voices = new ArrayList<>();
        if (voiceLines != null) {
            for (String v : voiceLines) {
                if (v != null && !v.isBlank() && Files.isReadable(Paths.get(v))) voices.add(v);
                else if (v != null && !v.isBlank()) log.warn("Intro voice line not readable, skipping: {}", v);
            }
        }
        boolean haveVoices = !voices.isEmpty();
        // When we have our own branded voices, DROP Veo's audio (its synthetic
        // chicken chatter is off-brand and varies run to run); otherwise keep it.
        boolean clipAudio = !haveVoices && hasAudio(clip);
        boolean haveSpark = Files.isReadable(Paths.get(sparkle));
        boolean haveLogo  = Files.isReadable(Paths.get(logo));
        if (!haveLogo) log.warn("Intro logo {} not readable — building without the fly-in", logo);

        // Inputs: 0 = clip, then optional sparkle, then optional logo, then voices.
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y", "-loglevel", "error", "-i", clipPath));
        int sparkIdx = -1, logoIdx = -1, idx = 1;
        if (haveSpark) { cmd.add("-i"); cmd.add(sparkle); sparkIdx = idx++; }
        if (haveLogo)  { cmd.add("-loop"); cmd.add("1"); cmd.add("-i"); cmd.add(logo); logoIdx = idx++; }
        int[] voiceIdx = new int[voices.size()];
        for (int i = 0; i < voices.size(); i++) { cmd.add("-i"); cmd.add(voices.get(i)); voiceIdx[i] = idx++; }

        // SHORT timeline: the logo swoops in early, total ≈ 4-5s. The clip
        // keeps PLAYING under the logo (no frozen blink); tpad only kicks in
        // for the rare clip shorter than the total.
        double clipDur = durationSeconds(clip);
        double logoLanded = LOGO_AT + FLY_DUR;

        // ALL three greetings on FIXED SLOTS that mirror the Veo clip's
        // scripted beak-turns (MOTION_DESC: Pip 0.5-1.6, Mo 1.7-2.8, Bo
        // 2.9-4.0) — so the voice plays exactly while THAT chicken's beak
        // moves. Back-to-back packing sounded fine but visually desynced:
        // Pip spoke before any beak moved. Slots win over packing; a measured
        // line that overruns its slot pushes the next start just enough to
        // never overlap.
        final double[] VOICE_SLOTS = {0.55, 1.75, 2.95};
        final double VOICE_GAP = 0.10;
        List<Integer> voiceMs = new ArrayList<>();
        double prevEnd = 0;
        for (int i = 0; i < voices.size(); i++) {
            double slot = i < VOICE_SLOTS.length ? VOICE_SLOTS[i] : prevEnd + VOICE_GAP;
            double start = Math.max(slot, prevEnd + VOICE_GAP);
            voiceMs.add((int) Math.round(start * 1000));
            prevEnd = start + voiceLineSeconds(voices.get(i));
        }
        double lastVoiceEnd = prevEnd;

        // Total = logo timeline OR the voices, whichever needs more room.
        // The voice tail needs 1.9s: the intro→episode concat runs a SLOW
        // 1.1s DISSOLVE that overlaps (and audio-crossfades!) the intro's
        // tail — Bo's "And I'm Bo!" must end BEFORE that fade starts, plus
        // breathing room. (History: 0.6s margin ate her line entirely.)
        double totalDur = Math.max(logoLanded + HOLD_AFTER, lastVoiceEnd + 1.9);

        StringBuilder fc = new StringBuilder();
        fc.append("[0:v]scale=1920:1080:force_original_aspect_ratio=increase,")
          .append("crop=1920:1080,setsar=1,tpad=stop_mode=clone:stop_duration=")
          .append(fmt(Math.max(0, totalDur - clipDur))).append("[base];");
        if (haveLogo) {
            // Logo fly-in TOP-LEFT: swoops in diagonally from just off-screen
            // with a quadratic ease-out (fast in, soft landing) + a quick
            // alpha fade so the first frames never pop. Lands on the same
            // corner as the outro logo, so the branding bookends the video.
            String t0 = fmt(LOGO_AT), d = fmt(FLY_DUR);
            String ease = "pow(max(0,1-(t-" + t0 + ")/" + d + "),2)";
            fc.append("[").append(logoIdx).append(":v]scale=").append(LOGO_W).append(":-1,format=rgba,")
              .append("fade=t=in:st=").append(t0).append(":d=0.25:alpha=1[logo];");
            fc.append("[base][logo]overlay=")
              .append("x='").append(LOGO_X).append("-").append(LOGO_X - LOGO_FROM_X).append("*").append(ease).append("'")
              .append(":y='").append(LOGO_Y).append("-").append(LOGO_Y - LOGO_FROM_Y).append("*").append(ease).append("'")
              .append(":enable='gte(t,").append(t0).append(")'[v];");
        } else {
            fc.append("[base]null[v];");
        }

        // Audio: branded chicken voices (or Veo's own track as fallback) + one
        // sparkle when the logo lands.
        // All branches are resampled to a common 48 kHz so amix never fails on a
        // sample-rate mismatch (ElevenLabs MP3s and the SFX library can differ).
        List<String> amix = new ArrayList<>();
        if (clipAudio) { fc.append("[0:a]volume=1,aresample=48000[ca];"); amix.add("[ca]"); }
        // Branded chicken voices (Pip → Mo → Bo) placed across the introduction
        // beat — these REPLACE Veo's audio so the chicks speak in the SAME voices
        // as the episodes. Spread evenly over [0.18, 0.72]·clipDur: the clip opens
        // calm for a beat before Pip greets, so the first line starts a touch later
        // than the very top; each still lands in its own beat and finishes before
        // the title reveals. (If a line reads early/late, nudge this window.)
        if (haveVoices) {
            // All greetings, back-to-back per the schedule computed above.
            for (int i = 0; i < voiceIdx.length && i < voiceMs.size(); i++) {
                int ms = voiceMs.get(i);
                fc.append("[").append(voiceIdx[i]).append(":a]adelay=").append(ms).append("|").append(ms)
                  .append(",volume=1.0,aresample=48000[vo").append(i).append("];");
                amix.add("[vo" + i + "]");
            }
        }
        if (haveSpark && haveLogo) {
            // One sparkle exactly when the logo lands (the old per-letter dings
            // went out with the title text).
            int ms = (int) Math.round(logoLanded * 1000);
            fc.append("[").append(sparkIdx).append(":a]adelay=").append(ms).append("|").append(ms)
              .append(",volume=0.7,aresample=48000[sp];");
            amix.add("[sp]");
        }
        boolean haveAudio = !amix.isEmpty();
        if (haveAudio) {
            fc.append(String.join("", amix))
              .append("amix=inputs=").append(amix.size())
              .append(":normalize=0:dropout_transition=0,")
              // Tail fade: ElevenLabs lines sometimes carry a trailing breath /
              // "oooh" vocalisation that landed naked in the intro's 1.9s tail
              // margin (feedback ep 3). The dissolve into scene 1 overlaps this
              // window anyway, so fading the last 0.8s only removes strays.
              .append("afade=t=out:st=").append(fmt(Math.max(0, totalDur - 0.8)))
              .append(":d=0.8[a]");
        }

        cmd.add("-filter_complex"); cmd.add(fc.toString());
        cmd.add("-map"); cmd.add("[v]");
        if (haveAudio) { cmd.add("-map"); cmd.add("[a]"); }
        cmd.add("-t"); cmd.add(fmt(totalDur));
        cmd.add("-c:v"); cmd.add("libx264"); cmd.add("-pix_fmt"); cmd.add("yuv420p");
        if (haveAudio) { cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("192k");
                         cmd.add("-ar"); cmd.add("48000"); }
        cmd.add(introPath);

        run(cmd);
        log.info("Intro rebuilt -> {} (voices={}, clipAudio={}, logo={}, sparkle={})",
                introPath, voices.size(), clipAudio, haveLogo, haveSpark);
        return introPath;
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.3f", d); }

    /** Duration of a short voice-line MP3 via ffprobe; falls back to 1.2s so a
     *  probe failure shifts the next line slightly instead of breaking the mix. */
    private double voiceLineSeconds(String path) {
        try {
            Process p = new ProcessBuilder(ffprobe, "-v", "error", "-show_entries",
                    "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", path)
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(20, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                try {
                    double d = Double.parseDouble(line.trim());
                    if (d > 0.2 && d < 10) return d;
                } catch (NumberFormatException ignore) { /* next */ }
            }
        } catch (Exception e) {
            log.warn("voice line probe failed ({}) — assuming 1.2s", e.getMessage());
        }
        return 1.2;
    }

    /** Clip duration in seconds via ffprobe; falls back to 8.0 on any problem. */
    private double durationSeconds(Path clip) {
        try {
            Process p = new ProcessBuilder(ffprobe, "-v", "error", "-show_entries",
                    "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", clip.toString())
                    .start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(20, TimeUnit.SECONDS);
            for (String line : out.split("\\R")) {
                try {
                    double d = Double.parseDouble(line.trim());
                    if (d > 0.5 && d < 30) return d;
                } catch (NumberFormatException ignore) { /* try next line */ }
            }
        } catch (Exception e) {
            log.warn("ffprobe duration failed ({}) — assuming 8s clip", e.getMessage());
        }
        return 8.0;
    }

    private boolean hasAudio(Path clip) {
        try {
            Process p = new ProcessBuilder(ffprobe, "-v", "error", "-select_streams", "a",
                    "-show_entries", "stream=index", "-of", "csv=p=0", clip.toString())
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor(20, TimeUnit.SECONDS);
            return !out.isBlank();
        } catch (Exception e) {
            log.warn("ffprobe audio check failed ({}) — assuming no clip audio", e.getMessage());
            return false;
        }
    }

    private void run(List<String> cmd) {
        try {
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes());
            if (!p.waitFor(10, TimeUnit.MINUTES)) {
                p.destroyForcibly();
                throw new IllegalStateException("ffmpeg intro composite timed out");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg intro composite failed: " + out);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ffmpeg intro composite error: " + e.getMessage(), e);
        }
    }
}
