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
 * (≈8s) and, AFTER the introductions, holds the last frame and reveals the
 * "TINY CHICKEN WORLD" title LETTER BY LETTER on a fixed wooden board in the
 * lower centre — each letter a different cheerful colour, dropping in with a
 * little bounce and a "ding". The board is drawn here at a FIXED position so it
 * never moves (decoupled from Veo's own sign), and the title comes after the
 * intros so the open feels calm, not busy.
 *
 * Everything is a single ffmpeg pass over the clip — cheap and re-runnable
 * without re-generating the (paid) Veo clip.
 */
@Slf4j
@Service
public class IntroBuilder {

    @Value("${app.brand.intro-path:/bible/intro.mp4}")
    private String introPath;
    // Rounded "candy" font to match the logo (installed in the assembly Dockerfile
    // via fonts-comfortaa). Falls back to DejaVu if the file isn't found.
    @Value("${app.brand.title-font:/usr/share/fonts/truetype/comfortaa/Comfortaa-Bold.ttf}")
    private String font;
    private static final String FONT_FALLBACK = "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf";
    @Value("${app.brand.letter-ding:/bible/sfx/intro/letter_ding.mp3}")
    private String ding;
    @Value("${app.brand.title-sparkle:/bible/sfx/intro/title_sparkle.mp3}")
    private String sparkle;
    @Value("${app.ffmpeg-bin:ffmpeg}")
    private String ffmpeg;
    @Value("${app.ffprobe-bin:ffprobe}")
    private String ffprobe;

    // SHORT INTRO (kids-YouTube retention: branding ≤5s, the hook must own
    // the first 15s). The old 12.5s intro held a frozen last frame for ~6s —
    // and the freeze landed on a BLINK, so all three chicks stood eyes-closed
    // under the title. The short intro stays on LIVE video (no freeze needed:
    // total < clip length), title up early, one greeting, done.
    private static final double REVEAL_AT  = 1.2;   // first letter drops
    private static final double LETTER_STEP = 0.09; // per-letter cadence
    private static final double HOLD_AFTER = 1.4;   // beat after egg, then cut

    /** One title letter: glyph + its fixed position, cheerful colour, reveal time.
     *  Positions were measured once (DejaVu Bold, fontsize 76) so the fixed
     *  "TINY CHICKEN WORLD" branding lays out with correct kerning on 3 lines. */
    private record L(String ch, int x, int y, String color, int tMs) {}

    // Brand colours from the channel avatar: TINY + CHICKEN gold-yellow, WORLD
    // blue. (Hex approximated from the avatar; tweak YELLOW/BLUE for an exact
    // match — it's a free re-composite, no Veo render needed.)
    private static final String YELLOW = "0xF0B010";
    private static final String BLUE   = "0x3E72C8";

    private static final List<L> TITLE = List.of(
            new L("T", 860, 712, YELLOW, 8200),
            new L("I", 912, 712, YELLOW, 8360),
            new L("N", 940, 712, YELLOW, 8520),
            new L("Y", 1004, 712, YELLOW, 8680),
            new L("C", 771, 806, YELLOW, 8840),
            new L("H", 827, 806, YELLOW, 9000),
            new L("I", 890, 806, YELLOW, 9160),
            new L("C", 919, 806, YELLOW, 9320),
            new L("K", 974, 806, YELLOW, 9480),
            new L("E", 1036, 806, YELLOW, 9640),
            new L("N", 1085, 806, YELLOW, 9800),
            new L("W", 801, 900, BLUE, 9960),
            new L("O", 885, 900, BLUE, 10120),
            new L("R", 949, 900, BLUE, 10280),
            new L("L", 1008, 900, BLUE, 10440),
            new L("D", 1056, 900, BLUE, 10600)
    );

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
        boolean haveDing  = Files.isReadable(Paths.get(ding));
        boolean haveSpark = Files.isReadable(Paths.get(sparkle));

        // Inputs: 0 = clip, then optional ding, then optional sparkle, then voices.
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y", "-loglevel", "error", "-i", clipPath));
        int dingIdx = -1, sparkIdx = -1, idx = 1;
        if (haveDing)  { cmd.add("-i"); cmd.add(ding);    dingIdx = idx++; }
        if (haveSpark) { cmd.add("-i"); cmd.add(sparkle); sparkIdx = idx++; }
        int[] voiceIdx = new int[voices.size()];
        for (int i = 0; i < voices.size(); i++) { cmd.add("-i"); cmd.add(voices.get(i)); voiceIdx[i] = idx++; }

        String titleFont = (font != null && Files.isReadable(Paths.get(font))) ? font : FONT_FALLBACK;
        if (!titleFont.equals(font)) log.warn("Title font {} not found — using fallback {}", font, titleFont);
        String style = "fontfile=" + titleFont
                + ":borderw=7:bordercolor=0xFFFFFF:shadowcolor=0x2A1B10@0.5:shadowx=4:shadowy=4";

        // SHORT timeline: fixed reveal early in the clip, total ≈ 4-5s. The clip
        // keeps PLAYING under the title (no frozen blink); tpad only kicks in
        // for the rare clip shorter than the total.
        double clipDur = durationSeconds(clip);
        double lastT = REVEAL_AT + (TITLE.size() - 1) * LETTER_STEP;
        double eggAt = lastT + 0.15;

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

        // Total = title timeline OR the voices, whichever needs more room.
        // The voice tail needs 1.9s: the intro→episode concat now runs a SLOW
        // 1.1s DISSOLVE that overlaps (and audio-crossfades!) the intro's
        // tail — Bo's "And I'm Bo!" must end BEFORE that fade starts, plus
        // breathing room. (History: 0.6s margin ate her line entirely.)
        double totalDur = Math.max(eggAt + HOLD_AFTER, lastVoiceEnd + 1.9);

        StringBuilder fc = new StringBuilder();
        fc.append("[0:v]scale=1920:1080:force_original_aspect_ratio=increase,")
          .append("crop=1920:1080,setsar=1,tpad=stop_mode=clone:stop_duration=")
          .append(fmt(Math.max(0, totalDur - clipDur))).append("[base];");
        // Letters sit directly over the VEO clip; their white border + drop
        // shadow keep them legible without a board.
        String prev = "base";
        for (int i = 0; i < TITLE.size(); i++) {
            L l = TITLE.get(i);
            String t = fmt(REVEAL_AT + i * LETTER_STEP);
            fc.append("[").append(prev).append("]drawtext=").append(style)
              .append(":fontcolor=").append(l.color())
              .append(":text='").append(l.ch()).append("':fontsize=76")
              .append(":x=").append(l.x())
              .append(":y='").append(l.y()).append("-max(0,(1-(t-").append(t).append(")/0.22))*42'")
              .append(":alpha='min(1,max(0,(t-").append(t).append(")/0.18))'")
              .append(":enable='gte(t,").append(t).append(")'[L").append(i).append("];");
            prev = "L" + i;
        }
        // Golden egg after WORLD (matches the channel avatar) — pops in with the
        // last letter. Drawn as two stacked ellipses (dark rim + gold body) so no
        // external asset is needed.
        String eggT = fmt(eggAt);
        fc.append("color=c=0x9A6B1E:s=74x94:d=14,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':")
          .append("a='if(lt(pow((X-37)/35,2)+pow((Y-47)/45,2),1),255,0)'[eggrim];");
        fc.append("color=c=0xF2C84B:s=66x86:d=14,format=rgba,geq=r='r(X,Y)':g='g(X,Y)':b='b(X,Y)':")
          .append("a='if(lt(pow((X-33)/30,2)+pow((Y-43)/40,2),1),255,0)'[eggbody];");
        fc.append("[").append(prev).append("][eggrim]overlay=x=1116:y=876:enable='gte(t,").append(eggT).append(")'[eggA];");
        fc.append("[eggA][eggbody]overlay=x=1120:y=880:enable='gte(t,").append(eggT).append(")'[v];");

        // Audio: branded chicken voices (or Veo's own track as fallback) + a ding
        // per letter + a sparkle.
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
        if (haveDing) {
            fc.append("[").append(dingIdx).append(":a]asplit=").append(TITLE.size());
            for (int i = 0; i < TITLE.size(); i++) fc.append("[d").append(i).append("]");
            fc.append(";");
            for (int i = 0; i < TITLE.size(); i++) {
                int ms = (int) Math.round((REVEAL_AT + i * LETTER_STEP) * 1000);
                fc.append("[d").append(i).append("]adelay=").append(ms).append("|").append(ms)
                  .append(",volume=0.6,aresample=48000[D").append(i).append("];");
                amix.add("[D" + i + "]");
            }
        }
        if (haveSpark) {
            int ms = (int) Math.round((lastT + 0.2) * 1000);
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
        log.info("Intro rebuilt -> {} (voices={}, clipAudio={}, ding={}, sparkle={})",
                introPath, voices.size(), clipAudio, haveDing, haveSpark);
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
