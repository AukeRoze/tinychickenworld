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
 * (≈8s) and flies the channel LOGO into the TOP-RIGHT corner with a quick
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
    /** Minimum intro length (s). 0 = keep the clip's NATURAL length, no boomerang
     *  padding. See the MIN-DURATION note above. */
    @Value("${app.brand.intro-min-seconds:0}")
    private double minDur;
    @Value("${app.ffmpeg-bin:ffmpeg}")
    private String ffmpeg;
    @Value("${app.ffprobe-bin:ffprobe}")
    private String ffprobe;

    // The clip stays LIVE under the logo (no freeze); the logo swoops in early,
    // one greeting per chick.
    //
    // MIN-DURATION (was a hard 12.0): de oude 12s-vloer padde de korte ~8s
    // Veo-intro met boomerang/tpad omhoog om de outro te matchen. Auke's intro
    // komt nu uit Google Flow/Omni en is al ~10s — die wil hij op zijn EIGEN
    // lengte houden, geen 2s boomerang erachter (wens 2026-06-26). Daarom is de
    // minimumlengte nu configureerbaar en standaard 0 = natuurlijke cliplengte
    // (geen padding). Wil je tóch de oude 12s-vloer terug, zet dan env
    // app.brand.intro-min-seconds=12. De voice-staart (2.2s, alleen relevant bij
    // losse ElevenLabs-stemmen — Flow draagt z'n eigen audio) blijft sowieso
    // leidend als die langer is.
    private static final double LOGO_AT    = 1.0;   // logo starts its fly-in
    private static final double FLY_DUR    = 0.7;   // swoop duration (ease-out)
    private static final double HOLD_AFTER = 1.4;   // beat after the logo lands
    // Het logo vliegt aan het EIND ook weer weg (gebruikerswens 2026-06-14: "moet
    // wegvliegen voordat de video begint"). FLY_OUT_LEAD = aantal seconden vóór het
    // intro-einde dat het logo volledig buiten beeld is — ruim genoeg om de 2.0s
    // intro→scène-1 dissolve te clearen, zodat er geen logo meer staat als de
    // aflevering invloeit.
    private static final double FLY_OUT_LEAD = 2.2;

    // BLINK-BACKOFF (kijkersfeedback 2026-06-13: "2 van de 3 kippen met ogen
    // dicht na de intro"). De tpad-clone bevroor het ALLERLAATSTE frame van de
    // Veo-clip, en dat landde op een knipper — die bevroren blik bleef seconden
    // staan. We bevriezen nu een fractie VÓÓR het echte einde: een knipper duurt
    // ~0.1-0.2s, dus een frame ~0.45s eerder valt vrijwel zeker op open ogen.
    // De afgesneden staart wordt door de langere tpad-hold gecompenseerd, dus de
    // totale introduur verandert niet. Alleen actief als de clip lang genoeg is.
    private static final double BLINK_BACKOFF = 0.45;  // s vóór clip-einde om te bevriezen

    // Logo landing position (TOP-RIGHT) + size. Gebruikerswens 14 juni 2026:
    // logo iets kleiner (480 → 400) én naar de rechterbovenhoek i.p.v. links.
    // X wordt afgeleid uit de framebreedte (1920) zodat het logo met dezelfde
    // 64px-marge tegen de rechterrand landt als het eerder links had. (Outro-logo
    // blijft 220 top-left — bewust kleiner, want daar botst het anders met de
    // end-screen-elementen.)
    private static final int FRAME_W = 1920;
    private static final int LOGO_W = 400;   // 480 → 400 (gebruikerswens 14 juni: iets kleiner)
    private static final int LOGO_X = FRAME_W - LOGO_W - 64;  // top-right, 64px marge
    private static final int LOGO_Y = 56;
    // Fly-in start: just off-screen beyond the top-RIGHT corner (ruim genoeg dat
    // het logo volledig buiten beeld begint).
    private static final int LOGO_FROM_X = FRAME_W + 640;
    private static final int LOGO_FROM_Y = -640;

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
        // The voice tail needs 2.2s: the intro→episode concat runs a SLOW
        // 2.0s DISSOLVE (Concatenator.DISSOLVE_INTRO) that overlaps (and
        // audio-crossfades!) the intro's tail — Bo's "And I'm Bo!" must end
        // BEFORE that fade starts, plus breathing room. The 2.2s margin still
        // clears the 2.0s dissolve; if DISSOLVE_INTRO is raised further, raise
        // this too. (History: 1.9s cleared the old 1.6s dissolve; 0.6s ate her
        // line entirely.)
        // Totale introduur. KRITIEK: nooit korter dan de clip zelf, anders kapt
        // de '-t totalDur' aan het eind de clip af. We nemen daarom clipDur mee in
        // de max, plus de configureerbare minDur (default 0 = natuurlijke lengte),
        // de logo-beat en de voice-staart (2.2s, alleen relevant bij losse
        // stemmen). Bij een 10s Flow-clip + minDur=0 wint clipDur → intro blijft
        // 10s, holdPad=0, dus geen boomerang.
        double totalDur = Math.max(clipDur,
                Math.max(minDur, Math.max(logoLanded + HOLD_AFTER, lastVoiceEnd + 2.2)));

        // BLINK-BACKOFF: when the clip is held to fill the intro, freeze a frame
        // a touch BEFORE the true end so the held frame can't land on a terminal
        // blink (feedback 2026-06-13). We trim the last BLINK_BACKOFF seconds off
        // the LIVE clip, then tpad clones the new (earlier, eyes-open) last frame.
        // Only when there is genuinely a hold (totalDur > clipDur) and the clip is
        // long enough to spare the tail; otherwise behave exactly as before.
        // Fill the intro to totalDur WITHOUT a static freeze. The old approach
        // froze a single frame near the end and held it ~4s; that frame kept
        // landing on a blink, so the chickens sat with shut eyes under the logo
        // (feedback 13 juni — bleef terugkomen, ook na een verse render). Now we
        // BOOMERANG: play the clip forward, then append a REVERSED tail so the
        // chickens keep MOVING for the rest of the intro. A blink is then a
        // natural ~0.1s flicker mid-motion — never a multi-second frozen shut-eye
        // pose. The reversed tail's first frame equals the last forward frame, so
        // the turn is seamless. Total = clipDur + holdPad = totalDur exactly.
        double holdPad = Math.max(0, totalDur - clipDur);
        final String SCALE =
                "scale=1920:1080:force_original_aspect_ratio=increase,crop=1920:1080,setsar=1";
        StringBuilder fc = new StringBuilder();
        boolean boomerang = holdPad > 0.05 && clipDur > holdPad + 0.5;
        if (boomerang) {
            // forward [0..clipDur] + reverse of [clipDur-holdPad .. clipDur].
            fc.append("[0:v]").append(SCALE).append(",split[ifwd][irev];");
            fc.append("[ifwd]setpts=PTS-STARTPTS[fwd];");
            fc.append("[irev]trim=start=").append(fmt(clipDur - holdPad))
              .append(":end=").append(fmt(clipDur))
              .append(",setpts=PTS-STARTPTS,reverse[rev];");
            fc.append("[fwd][rev]concat=n=2:v=1:a=0[base];");
        } else if (holdPad > 0.05) {
            // Clip too short to source a clean reverse tail — fall back to the
            // old clone-freeze, backed off a touch to avoid a terminal blink.
            double backoff = clipDur > BLINK_BACKOFF + 1.0 ? BLINK_BACKOFF : 0.0;
            double liveDur = clipDur - backoff;
            double pad = Math.max(0, totalDur - liveDur);
            fc.append("[0:v]").append(SCALE).append(",");
            if (backoff > 0) {
                fc.append("trim=duration=").append(fmt(liveDur)).append(",setpts=PTS-STARTPTS,");
            }
            fc.append("tpad=stop_mode=clone:stop_duration=").append(fmt(pad)).append("[base];");
        } else {
            // Clip already long enough — no fill needed.
            fc.append("[0:v]").append(SCALE).append("[base];");
        }
        if (haveLogo) {
            // Logo fly-in TOP-RIGHT, hold, then fly-OUT before the episode starts
            // (gebruikerswens 2026-06-14: "logo moet wegvliegen voordat de video
            // begint"). Eén positie-factor f stuurt béide bewegingen:
            //   fIn  = quadratische ease-OUT op de weg IN  (snel in, zachte landing);
            //   fOut = quadratische ease-IN  op de weg UIT (versnelt het beeld uit).
            // f=0 → gelande hoek, f=1 → buiten beeld (zelfde off-screen hoek als de
            // fly-in, dus het logo verlaat het beeld waar het binnenkwam). fIn en
            // fOut overlappen niet (hold ertussen), dus f blijft netjes in [0,1].
            // Het logo is FLY_OUT_LEAD seconden vóór het intro-einde volledig weg,
            // ruim vóór de intro→scène-1 dissolve.
            String tIn = fmt(LOGO_AT), dIn = fmt(FLY_DUR);
            double flyOutStart = Math.max(logoLanded + HOLD_AFTER,
                                          totalDur - FLY_OUT_LEAD - FLY_DUR);
            String tOut = fmt(flyOutStart), dOut = fmt(FLY_DUR);
            String fIn  = "pow(max(0,1-(t-" + tIn + ")/" + dIn + "),2)";
            String fOut = "pow(min(1,max(0,(t-" + tOut + ")/" + dOut + ")),2)";
            String f = "(" + fIn + "+" + fOut + ")";
            fc.append("[").append(logoIdx).append(":v]scale=").append(LOGO_W).append(":-1,format=rgba,")
              .append("fade=t=in:st=").append(tIn).append(":d=0.25:alpha=1[logo];");
            fc.append("[base][logo]overlay=")
              .append("x='").append(LOGO_X).append("-").append(LOGO_X - LOGO_FROM_X).append("*").append(f).append("'")
              .append(":y='").append(LOGO_Y).append("-").append(LOGO_Y - LOGO_FROM_Y).append("*").append(f).append("'")
              .append(":enable='gte(t,").append(tIn).append(")'[v];");
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
