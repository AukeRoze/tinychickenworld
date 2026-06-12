package com.youtubeauto.video.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Bakes the branded OUTRO: composites an animated "SUBSCRIBE FOR MORE" call-to-
 * action + the channel logo + a sparkle sting over a one-time Veo "chickens
 * waving goodbye" clip, and writes the result to the outro path the assembly
 * stage appends to every video.
 *
 * Unlike the intro (which overlays a pre-rendered title .mov), the outro CTA is
 * cheap text + logo, so we draw it here with ffmpeg drawtext — no extra brand
 * asset to maintain. Mirrors {@link IntroBuilder}'s process model.
 */
@Slf4j
@Service
public class OutroBuilder {

    @Value("${app.brand.outro-path:/bible/outro.mp4}")
    private String outroPath;
    @Value("${app.brand.logo:/bible/logo.png}")
    private String logo;
    @Value("${app.brand.sparkle-sfx:/bible/sfx/intro/title_sparkle.mp3}")
    private String sparkleSfx;
    @Value("${app.brand.outro-font:/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}")
    private String font;
    @Value("${app.ffmpeg-bin:ffmpeg}")
    private String ffmpeg;
    @Value("${app.ffprobe-bin:ffprobe}")
    private String ffprobe;

    // 9.0s with a calm arc instead of 6s wall-to-wall: ~3s of just waving and
    // goodbyes (the wind-down the episode needs), CTA at 3.4s, then a long
    // fade of BOTH picture and sound — no more "boem, einde video".
    // Fade 1.7 → 2.6 op kijkersfeedback (2026-06-12): het uitfaden beviel
    // maar mocht trager — een langzame uitgeleide past het bedtime-ritme.
    // DUR +0.5 zodat de langere fade de CTA-leestijd niet opeet: de CTA
    // staat nog steeds ±3s vol in beeld (3.4 → 6.4) vóór de fade inzet.
    private static final double DUR = 9.0;     // outro length (s)
    private static final double CTA_AT = 3.4;  // when the CTA bounces in (s)
    private static final double FADE = 2.6;    // tail fade (video + audio)

    /** Back-compat: no spoken-voice track. */
    public String build(String clipPath) {
        return build(clipPath, List.of());
    }

    /**
     * @param voiceLines ordered ElevenLabs (or sounds-mode) MP3s of the chickens
     *   waving goodbye — Pip, then Mo, then Bo. These are the SAME voices used in
     *   the episodes and REPLACE Veo's own (irritating, inconsistent) chatter.
     *   Spread across the wave beat before the CTA. Empty = sparkle only (legacy).
     * @return the written outro path.
     */
    public String build(String clipPath, List<String> voiceLines) {
        Path clip = Paths.get(clipPath);
        if (!Files.isReadable(clip)) {
            throw new IllegalArgumentException("Chicken clip not readable: " + clipPath);
        }
        // We deliberately DROP the Veo clip's own audio for the outro — Veo
        // generates unpredictable chicken chatter/giggling that sounds irritating.
        // The outro carries our OWN branded farewell voices + the sparkle (and the
        // episode's music context). So clipAudio is forced off regardless.
        boolean clipAudio = false;
        // Keep only readable voice tracks, in order.
        List<String> voices = new ArrayList<>();
        if (voiceLines != null) {
            for (String v : voiceLines) {
                if (v != null && !v.isBlank() && Files.isReadable(Paths.get(v))) voices.add(v);
                else if (v != null && !v.isBlank()) log.warn("Outro voice line not readable, skipping: {}", v);
            }
        }
        boolean haveVoices = !voices.isEmpty();
        boolean haveLogo  = Files.isReadable(Paths.get(logo));
        boolean haveSpark = Files.isReadable(Paths.get(sparkleSfx));

        // Inputs: 0 = Veo clip, then optional sparkle, then optional logo, then voices.
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y", "-loglevel", "error",
                "-i", clipPath));
        int sparkleIdx = -1, logoIdx = -1, idx = 1;
        if (haveSpark) { cmd.add("-i"); cmd.add(sparkleSfx); sparkleIdx = idx++; }
        if (haveLogo)  { cmd.add("-loop"); cmd.add("1"); cmd.add("-i"); cmd.add(logo); logoIdx = idx++; }
        int[] voiceIdx = new int[voices.size()];
        for (int i = 0; i < voices.size(); i++) { cmd.add("-i"); cmd.add(voices.get(i)); voiceIdx[i] = idx++; }

        // Bouncy, outlined CTA styling (matches the intro look).
        String c = "fontfile=" + font
                + ":borderw=10:bordercolor=0xFFFFFF:shadowcolor=0x3A2A1E@0.5:shadowx=6:shadowy=6";

        StringBuilder fc = new StringBuilder();
        fc.append("[0:v]scale=1920:1080:force_original_aspect_ratio=increase,")
          .append("crop=1920:1080,setsar=1[v0]");

        String prev = "v0";
        if (haveLogo) {
            // Logo TOP-LEFT, small. (It used to sit bottom-right — exactly the
            // zone where YouTube end-screen elements land; the subscribe element
            // would cover our own logo.)
            fc.append(";[").append(logoIdx).append(":v]scale=220:-1,format=rgba,")
              .append("fade=t=in:st=").append(CTA_AT).append(":d=0.4:alpha=1[logo]");
            fc.append(";[").append(prev).append("][logo]overlay=x=56:y=48[vl]");
            prev = "vl";
        }
        // CTA: a calm boxed line at the BOTTOM — off the faces (the 3-6
        // audience can't read; the line is for the parent), and the box also
        // masks the bottom strip where older source clips carried baked-in
        // Veo captions. The spoken farewells are the real CTA for the child.
        fc.append(";[").append(prev).append("]drawtext=").append(c)
          .append(":fontcolor=0xFFFFFF:text='SUBSCRIBE FOR MORE ADVENTURES!':fontsize=56")
          .append(":box=1:boxcolor=0xC2483B@0.88:boxborderw=22")
          .append(":x='(w-text_w)/2'")
          .append(":y='956-max(0,(1-(t-").append(CTA_AT).append(")/0.3))*60'")
          .append(":alpha='min(1,max(0,(t-").append(CTA_AT).append(")/0.25))'")
          .append(":enable='gte(t,").append(CTA_AT).append(")'[vtxt]");
        fc.append(";[vtxt]format=yuv420p,fade=t=in:st=0:d=0.3,")
          .append("fade=t=out:st=").append(DUR - FADE).append(":d=").append(FADE).append("[v]");

        // Audio: branded chicken farewells (same voices as the episodes) + a
        // delayed sparkle on the CTA.
        // All branches resampled to a common 48 kHz so amix never fails on a
        // sample-rate mismatch (ElevenLabs MP3s and the SFX library can differ).
        List<String> amix = new ArrayList<>();
        if (clipAudio) { fc.append(";[0:a]volume=1,aresample=48000[ca]"); amix.add("[ca]"); }
        // Farewell voices (Pip → Mo → Bo) spread across the wave beat, before the
        // CTA, so each chick is heard saying goodbye in its own voice.
        if (haveVoices) {
            int n = voiceIdx.length;
            // Farewells land in the calm opening beat, BEFORE the CTA pops.
            double vStart = DUR * 0.08, vEnd = DUR * 0.40;
            for (int i = 0; i < n; i++) {
                double at = (n == 1) ? DUR * 0.25 : vStart + (vEnd - vStart) * i / (n - 1);
                int ms = (int) Math.round(at * 1000);
                fc.append(";[").append(voiceIdx[i]).append(":a]adelay=").append(ms).append("|").append(ms)
                  .append(",volume=1.0,aresample=48000[vo").append(i).append("]");
                amix.add("[vo" + i + "]");
            }
        }
        if (haveSpark) {
            fc.append(";[").append(sparkleIdx).append(":a]adelay=")
              .append((int) (CTA_AT * 1000)).append("|").append((int) (CTA_AT * 1000))
              .append(",volume=0.7,aresample=48000[spark]");
            amix.add("[spark]");
        }
        boolean haveAudio = !amix.isEmpty();
        if (haveAudio) {
            // Audio fades out WITH the picture — the abrupt cut was here: the
            // mix simply stopped on the last sample.
            fc.append(";").append(String.join("", amix))
              .append("amix=inputs=").append(amix.size())
              .append(":normalize=0:dropout_transition=0[apre];")
              .append("[apre]afade=t=out:st=").append(DUR - FADE)
              .append(":d=").append(FADE).append("[a]");
        }

        cmd.add("-filter_complex"); cmd.add(fc.toString());
        cmd.add("-map"); cmd.add("[v]");
        if (haveAudio) { cmd.add("-map"); cmd.add("[a]"); }
        cmd.add("-t"); cmd.add(String.valueOf(DUR));
        cmd.add("-c:v"); cmd.add("libx264"); cmd.add("-pix_fmt"); cmd.add("yuv420p");
        if (haveAudio) { cmd.add("-c:a"); cmd.add("aac"); cmd.add("-b:a"); cmd.add("192k");
                         cmd.add("-ar"); cmd.add("48000"); }
        cmd.add(outroPath);

        run(cmd);
        log.info("Outro rebuilt -> {} (voices={}, clipAudio={}, logo={}, sparkle={})",
                outroPath, voices.size(), clipAudio, haveLogo, haveSpark);
        return outroPath;
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
                throw new IllegalStateException("ffmpeg outro composite timed out");
            }
            if (p.exitValue() != 0) {
                throw new IllegalStateException("ffmpeg outro composite failed: " + out);
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("ffmpeg outro composite error: " + e.getMessage(), e);
        }
    }
}
