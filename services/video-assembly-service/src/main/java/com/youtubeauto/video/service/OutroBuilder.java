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
 * Bakes the branded END-SCREEN OUTRO: a fixed 12s template over the one-time
 * Veo "chickens giggling at golden hour" clip, designed around YouTube's
 * end-screen elements. Writes the outro path the assembly stage appends to
 * every video. Mirrors {@link IntroBuilder}'s process model.
 *
 * <p><b>SAFE ZONES — do not build into the middle band.</b> YouTube end-screen
 * elements land in the horizontal middle band (~y 280-800 on 1080p). The
 * channel places them as: LEFT = subscribe, MIDDLE = playlist, RIGHT = video.
 * Our own overlays live strictly OUTSIDE that band:
 *
 * <pre>
 *  0    ┌──────────────────────────────────────────────────┐
 *       │ [logo x=56,y=48]      (top strip — ours, small)  │
 *  ~280 ├──────────────────────────────────────────────────┤
 *       │  LEFT:          MIDDLE:          RIGHT:          │
 *       │  YT subscribe   YT playlist      YT video        │  ← KEEP CLEAR
 *  ~800 ├──────────────────────────────────────────────────┤
 *       │   (Veo: the three chicks sit in the bottom       │
 *       │    third, giggling — picture, not overlay)       │
 *  ~980 │      one thin centered text line (ours)          │
 *  1080 └──────────────────────────────────────────────────┘
 * </pre>
 *
 * <p>The old red "SUBSCRIBE FOR MORE ADVENTURES!" box and any bell prompt are
 * deliberately GONE: YouTube's own subscribe end-screen element replaces them
 * (a baked-in subscribe button would double up with — and sit under — the real
 * one). The only text left is one thin, soft line at the very bottom (y≥980).
 *
 * <p><b>Clip length vs. DUR:</b> Veo clips run ~8s; DUR is 12s. The clip is
 * held on its last frame with {@code tpad=stop_mode=clone} (same trick as
 * {@link IntroBuilder}) for the remaining ~4s — the giggle freezes softly
 * under the 2.6s tail fade, which reads as a calm hold, not a glitch.
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
    /** Optional calm outro music bed — dormant-until-asset like every bible
     *  asset: no file = outro renders without music; drop the file and the
     *  next (re)build mixes it in. Looped/trimmed to DUR, ~-16 dB under the
     *  farewell voice, fading out with the shared tail afade.
     *  Documented in bible/sfx/README.md. */
    @Value("${app.brand.outro-music:/bible/sfx/outro/calm.mp3}")
    private String outroMusic;
    @Value("${app.brand.outro-font:/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}")
    private String font;
    @Value("${app.ffmpeg-bin:ffmpeg}")
    private String ffmpeg;
    @Value("${app.ffprobe-bin:ffprobe}")
    private String ffprobe;

    // 12.0s fixed end-screen template (YouTube end screens may cover the last
    // 5-20s; 12 gives the elements ~10s of real screen time after the 2s
    // settle). DUR 9.0 → 12.0 (2026-06-12, end-screen redesign); FADE stays
    // 2.6 — the slow uitgeleide from the earlier kijkersfeedback keeps the
    // bedtime ritme. The Veo clip is ~8s, so tpad clones the last frame for
    // the final ~4s; the freeze sits under the fade and reads as a hold.
    private static final double DUR = 12.0;    // outro length (s)
    private static final double FADE = 2.6;    // tail fade (video + audio)
    // One shared brand beat: logo fade-in, sparkle and the bottom text line
    // all land here — after the giggle has registered, well before the fade.
    private static final double BRAND_AT = 2.0;
    // Pip's single farewell line starts early so it is fully spoken long
    // before the tail fade (and before viewers reach for the end-screen).
    private static final double VOICE_AT = 0.8;

    /** Back-compat: no spoken-voice track. */
    public String build(String clipPath) {
        return build(clipPath, List.of());
    }

    /**
     * @param voiceLines ordered ElevenLabs (or sounds-mode) MP3s of the chickens'
     *   farewell — since the end-screen redesign this is normally ONE line (Pip:
     *   "See you in the next adventure!"), but any list still works: the first
     *   line lands at {@link #VOICE_AT}, extra lines follow in the opening beat.
     *   These are the SAME voices used in the episodes and REPLACE Veo's own
     *   (irritating, inconsistent) chatter. Empty = sparkle/music only.
     * @return the written outro path.
     */
    public String build(String clipPath, List<String> voiceLines) {
        Path clip = Paths.get(clipPath);
        if (!Files.isReadable(clip)) {
            throw new IllegalArgumentException("Chicken clip not readable: " + clipPath);
        }
        // We deliberately DROP the Veo clip's own audio for the outro — Veo
        // generates unpredictable chicken chatter/giggling that sounds irritating.
        // The outro carries our OWN branded farewell voice + calm music bed +
        // the sparkle. So clipAudio is forced off regardless.
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
        boolean haveMusic = Files.isReadable(Paths.get(outroMusic));
        if (!haveMusic) log.info("Outro music bed {} not present — building without music", outroMusic);

        // Inputs: 0 = Veo clip, then optional sparkle, then optional logo,
        // then optional music bed (looped), then voices.
        List<String> cmd = new ArrayList<>(List.of(
                ffmpeg, "-y", "-loglevel", "error",
                "-i", clipPath));
        int sparkleIdx = -1, logoIdx = -1, musicIdx = -1, idx = 1;
        if (haveSpark) { cmd.add("-i"); cmd.add(sparkleSfx); sparkleIdx = idx++; }
        if (haveLogo)  { cmd.add("-loop"); cmd.add("1"); cmd.add("-i"); cmd.add(logo); logoIdx = idx++; }
        if (haveMusic) { cmd.add("-stream_loop"); cmd.add("-1"); cmd.add("-i"); cmd.add(outroMusic); musicIdx = idx++; }
        int[] voiceIdx = new int[voices.size()];
        for (int i = 0; i < voices.size(); i++) { cmd.add("-i"); cmd.add(voices.get(i)); voiceIdx[i] = idx++; }

        // Veo clips are ~8s; DUR is 12 — hold the last frame for the remainder
        // (tpad clone, like IntroBuilder) so the picture never goes black early.
        double clipDur = durationSeconds(clip);

        StringBuilder fc = new StringBuilder();
        fc.append("[0:v]scale=1920:1080:force_original_aspect_ratio=increase,")
          .append("crop=1920:1080,setsar=1,tpad=stop_mode=clone:stop_duration=")
          .append(fmt(Math.max(0, DUR - clipDur))).append("[v0]");

        String prev = "v0";
        if (haveLogo) {
            // Logo TOP-LEFT, small, on y=48 — ABOVE the end-screen band (~y 280).
            // Same corner as the intro logo, so the branding bookends the video.
            fc.append(";[").append(logoIdx).append(":v]scale=220:-1,format=rgba,")
              .append("fade=t=in:st=").append(fmt(BRAND_AT)).append(":d=0.4:alpha=1[logo]");
            fc.append(";[").append(prev).append("][logo]overlay=x=56:y=48[vl]");
            prev = "vl";
        }
        // One thin, soft text line at the very BOTTOM (y>=980) — BELOW the
        // end-screen band. No box, no border armour: small type, gentle shadow,
        // slow fade-in on the brand beat. (The old red SUBSCRIBE box + bell are
        // gone on purpose — YouTube's own subscribe element takes that job.)
        fc.append(";[").append(prev).append("]drawtext=fontfile=").append(font)
          .append(":fontcolor=0xFFFFFF:text='What adventures will we discover tomorrow?'")
          .append(":fontsize=38")
          .append(":shadowcolor=0x3A2A1E@0.35:shadowx=2:shadowy=2")
          .append(":x='(w-text_w)/2':y=992")
          .append(":alpha='0.85*min(1,max(0,(t-").append(fmt(BRAND_AT)).append(")/0.6))'")
          .append(":enable='gte(t,").append(fmt(BRAND_AT)).append(")'[vtxt]");
        fc.append(";[vtxt]format=yuv420p,fade=t=in:st=0:d=0.3,")
          .append("fade=t=out:st=").append(fmt(DUR - FADE)).append(":d=").append(fmt(FADE)).append("[v]");

        // Audio: Pip's farewell (same voice as the episodes) + calm music bed
        // + a soft sparkle on the brand beat.
        // All branches resampled to a common 48 kHz so amix never fails on a
        // sample-rate mismatch (ElevenLabs MP3s and the SFX library can differ).
        List<String> amix = new ArrayList<>();
        if (clipAudio) { fc.append(";[0:a]volume=1,aresample=48000[ca]"); amix.add("[ca]"); }
        // Farewell voice(s): first line lands EARLY (VOICE_AT) so it is fully
        // spoken well before the tail fade; any extra lines (legacy multi-line
        // lists) follow in the opening beat.
        if (haveVoices) {
            int n = voiceIdx.length;
            for (int i = 0; i < n; i++) {
                double at = (n == 1) ? VOICE_AT
                        : VOICE_AT + (DUR * 0.35 - VOICE_AT) * i / (n - 1);
                int ms = (int) Math.round(at * 1000);
                fc.append(";[").append(voiceIdx[i]).append(":a]adelay=").append(ms).append("|").append(ms)
                  .append(",volume=1.0,aresample=48000[vo").append(i).append("]");
                amix.add("[vo" + i + "]");
            }
        }
        if (haveMusic) {
            // Calm bed: the -stream_loop -1 input is infinite, so trim to DUR
            // here; ~-16 dB under the voice; the shared tail afade closes it.
            fc.append(";[").append(musicIdx).append(":a]atrim=0:").append(fmt(DUR))
              .append(",volume=-16dB,aresample=48000[mus]");
            amix.add("[mus]");
        }
        if (haveSpark) {
            // Sparkle stays on the logo beat, but softer (0.7 → 0.5) — the
            // end screen is a wind-down, not a sting.
            fc.append(";[").append(sparkleIdx).append(":a]adelay=")
              .append((int) (BRAND_AT * 1000)).append("|").append((int) (BRAND_AT * 1000))
              .append(",volume=0.5,aresample=48000[spark]");
            amix.add("[spark]");
        }
        boolean haveAudio = !amix.isEmpty();
        if (haveAudio) {
            // Audio fades out WITH the picture — the abrupt cut was here: the
            // mix simply stopped on the last sample.
            fc.append(";").append(String.join("", amix))
              .append("amix=inputs=").append(amix.size())
              .append(":normalize=0:dropout_transition=0[apre];")
              .append("[apre]afade=t=out:st=").append(fmt(DUR - FADE))
              .append(":d=").append(fmt(FADE)).append("[a]");
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
        log.info("Outro rebuilt -> {} (voices={}, clipAudio={}, logo={}, sparkle={}, music={})",
                outroPath, voices.size(), clipAudio, haveLogo, haveSpark, haveMusic);
        return outroPath;
    }

    private static String fmt(double d) { return String.format(Locale.ROOT, "%.3f", d); }

    /** Clip duration in seconds via ffprobe; falls back to 8.0 on any problem
     *  (Veo clips run ~8s) — mirrors {@link IntroBuilder}. */
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
