package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest.SceneInput;
import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.List;

/**
 * Step 2: build one self-contained scene clip via the Ken Burns filter graph.
 * The camera motion is parameterised by {@link MotionPreset} so consecutive
 * videos don't share the "every scene slow-zooms-in" AI-farm signature.
 *
 * Canvas size (width × height) is provided per call so the same builder
 * supports both 1920×1080 (landscape) and 1080×1920 (vertical Shorts).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SceneClipBuilder {

    private final FfmpegRunner runner;
    private final VideoProperties props;

    /** Optional ambient-effects overlay (drifting fireflies / butterflies /
     *  petals / rain drops / bokeh) composited over every Ken Burns scene to
     *  add life to an otherwise-still image. Expects a LOOPING clip with a
     *  transparent background. Dormant unless an asset exists — the default
     *  render is untouched. See {@link #resolveAmbientFx} for the
     *  per-scene (weather → time-of-day → location → global) selection. */
    private static final String FX_ROOT = "/bible/fx";
    private static final String[] FX_EXTENSIONS = {".mov", ".webm"};
    private static final double AMBIENT_FX_OPACITY = 0.8;

    /** Files.exists() cache for the FX lookups — without it a 30-scene render
     *  does up to 8 stat calls per scene. Refreshed every TTL so a freshly
     *  dropped asset is picked up by the next render without a restart
     *  (the whole feature is "drop a file and it lives" — no rebuild). */
    private static final long FX_CACHE_TTL_MS = 60_000;
    private final java.util.concurrent.ConcurrentHashMap<String, Boolean> fxExistsCache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private volatile long fxCacheStampMs = 0;

    private boolean fxExists(String path) {
        long now = System.currentTimeMillis();
        if (now - fxCacheStampMs > FX_CACHE_TTL_MS) {
            fxExistsCache.clear();          // benign race: worst case one extra stat
            fxCacheStampMs = now;
        }
        return fxExistsCache.computeIfAbsent(path,
                p -> java.nio.file.Files.exists(java.nio.file.Paths.get(p)));
    }

    /** First existing {@code {dir}/{id}.mov|.webm}, or null. Ids originate from
     *  the script LLM, so anything that isn't a plain token is rejected rather
     *  than ever joining a path outside /bible/fx. */
    private String firstExistingFx(String dir, String id) {
        if (id == null || id.isBlank() || !id.matches("[A-Za-z0-9_-]+")) return null;
        for (String ext : FX_EXTENSIONS) {
            String candidate = FX_ROOT + "/" + dir + "/" + id + ext;
            if (fxExists(candidate)) return candidate;
        }
        return null;
    }

    /**
     * Resolve the ambient FX overlay for ONE scene. First existing file wins
     * (each level tries .mov then .webm):
     * <ol>
     *   <li>{@code /bible/fx/weather/{weather}.*}     — weather beats everything
     *       (lightRain → drops on the lens);</li>
     *   <li>{@code /bible/fx/time/{timeOfDay}.*}      — night → fireflies / stars;</li>
     *   <li>{@code /bible/fx/location/{locationId}.*} — garden → butterflies;</li>
     *   <li>{@code /bible/fx/ambient.*}               — the original single global
     *       layer (backwards-compat).</li>
     * </ol>
     * Every level is dormant-until-asset: with an empty {@code bible/fx/} the
     * render is identical to before.
     *
     * <p><b>Effect ↔ sound coupling.</b> The matching SOUND bed is mixed per
     * scene by the voice-service's AmbientMixer from
     * {@code bible/sfx/ambient/{locationId}.mp3} — so when a LOCATION overlay
     * matches and that mp3 exists, picture and sound are already coupled by
     * the shared locationId; nothing extra to do here.
     * Weather is coupled the same way: the orchestrator passes the scene's
     * weather in the voice payload and the voice-service AmbientMixer lets a
     * {@code bible/sfx/ambient/{weather}.mp3} bed override the location bed —
     * mirroring the weather-beats-everything order below. Nothing extra to do
     * here either; see bible/sfx/README.md.
     */
    private String resolveAmbientFx(SceneInput scene) {
        String p;
        if ((p = firstExistingFx("weather", scene.weather())) != null) return p;
        if ((p = firstExistingFx("time", scene.timeOfDay())) != null) return p;
        if ((p = firstExistingFx("location", scene.locationId())) != null) return p;
        for (String ext : FX_EXTENSIONS) {
            String legacy = FX_ROOT + "/ambient" + ext;
            if (fxExists(legacy)) return legacy;
        }
        return null;
    }

    /** Effective scene length = the scripted duration. With native Omni clip audio
     *  (no separate ElevenLabs voice track any more) the clip itself carries the
     *  speech and is rendered at the fixed clip length, so there is nothing to
     *  probe and stretch to. */
    private int effectiveDur(SceneInput scene, Path workdir) {
        return scene.durationSeconds();
    }

    /**
     * Lead-in silence (seconds) prepended to a scene's VOICE so the first spoken
     * word lands a beat after the picture. Only the orchestrator's FIRST scene
     * uses it (> 0): the intro→episode DISSOLVE in
     * {@link Concatenator#concatHeterogeneous} overlaps the episode's opening,
     * and a voice that started at t=0 spoke while the chick was still
     * half-transparent in the blend (feedback 2026-06-13: "stemgeluid begint
     * voordat de eerste kip echt in beeld is"). Shifting only scene-1's voice
     * keeps every clip duration — and therefore the caption timing and the whole
     * A/V lock-step — unchanged; the voice simply starts later inside its own
     * (already voice-stretched) clip. 0 = no shift = exactly the old behaviour.
     */
    public Path build(SceneInput scene, MotionPreset motion,
                      int w, int h,
                      Path workdir, Path output) {
        return build(scene, motion, w, h, workdir, output, 0.0);
    }

    public Path build(SceneInput scene, MotionPreset motion,
                      int w, int h,
                      Path workdir, Path output, double voiceLeadInSeconds) {
        int fps = props.output().fps();
        int dur = effectiveDur(scene, workdir);
        int frames = dur * fps;
        // No separate voice track any more — the Ken-Burns (still-image) path has
        // no native audio, so it gets a silent track. In practice every scene is
        // an Omni clip (buildFromClip), so this path is a rare fallback.
        String audioLead = "";

        String motionChain = motion.filterChain(frames, w, h, fps);
        String fx = resolveAmbientFx(scene);
        if (fx != null) {
            // One line per scene, only on a match — silent when bible/fx is empty.
            log.info("scene seq={} ambient FX overlay {} (weather={} timeOfDay={} location={})",
                    scene.seq(), fx, scene.weather(), scene.timeOfDay(), scene.locationId());
        }

        String filter;
        List<String> args = new java.util.ArrayList<>(List.of("-y",
                "-loop", "1", "-t", String.valueOf(dur), "-i", scene.imagePath(),
                // silent stereo bed (input 1) in place of the old voice WAV
                "-f", "lavfi", "-t", String.valueOf(dur),
                "-i", "anullsrc=channel_layout=stereo:sample_rate=48000"));

        // Blurred-fill base: the SHARP image is scaled to FIT (decrease — nothing
        // cropped, full subject always visible) and centered over a blurred,
        // enlarged copy that fills the whole canvas (so there are never black
        // bars, whatever the source aspect). When the image already matches the
        // canvas aspect the blurred layer is fully hidden — zero downside. Ken
        // Burns motion is applied to the finished composite.
        String blurredBase =
                "[0:v]split=2[fgsrc][bgsrc];"
                + String.format("[bgsrc]scale=%d:%d:force_original_aspect_ratio=increase,"
                        + "crop=%d:%d,boxblur=20:1,eq=brightness=-0.05[bg];", w, h, w, h)
                + String.format("[fgsrc]scale=%d:%d:force_original_aspect_ratio=decrease[fg];", w, h)
                + "[bg][fg]overlay=(W-w)/2:(H-h)/2,setsar=1[based];";

        if (fx != null) {
            // [0]=image  [1]=audio  [2]=looping fx overlay (transparent bg)
            filter = blurredBase + String.format(
                    "[based]%s[base];" +
                    "[2:v]scale=%d:%d,format=rgba,colorchannelmixer=aa=%.2f[fx];" +
                    "[base][fx]overlay=eof_action=repeat:format=auto[v];" +
                    "[1:a]%sapad,atrim=duration=%d[a]",
                    motionChain, w, h, AMBIENT_FX_OPACITY, audioLead, dur
            );
            args.add("-stream_loop"); args.add("-1"); args.add("-i"); args.add(fx);
        } else {
            filter = blurredBase + String.format(
                    "[based]%s[v];" +
                    "[1:a]%sapad,atrim=duration=%d[a]",
                    motionChain, audioLead, dur
            );
        }

        log.debug("scene seq={} motion={} canvas={}x{} fx={}", scene.seq(), motion, w, h, fx != null);

        args.add("-filter_complex"); args.add(filter);
        args.add("-map"); args.add("[v]");
        args.add("-map"); args.add("[a]");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        // crf 16 (was 20): this is the FIRST re-encode of the Veo pixels and
        // every later pass compounds on it — the weakest link must not be the
        // first one (audit 2026-06-11, encode-cascade).
        args.add("-crf"); args.add("16");
        args.add("-r"); args.add(String.valueOf(fps));
        // Pin 4:2:0 so the concat doesn't inherit 4:4:4 (overlay=format=auto
        // can yield yuv444p, which ~doubles decode/encode memory and OOM-kills
        // the multi-input xfade graph). Final delivery is 4:2:0 anyway.
        args.add("-pix_fmt"); args.add("yuv420p");
        // PCM intermediate (audit #1) — voice stays lossless until FinalEncoder.
        args.add("-c:a"); args.add("pcm_s16le");
        args.add("-ar"); args.add("48000");
        args.add("-shortest");
        args.add(output.toString());

        runner.runFfmpeg(args, workdir);
        return output;
    }

    /** {@code adelay=…|…,} prefix for a positive lead-in, else "". Both channels
     *  delayed (stereo-safe); {@code all=1} would only cover the declared ones. */
    private static String voiceDelayFilter(double leadInSeconds) {
        if (leadInSeconds <= 0) return "";
        long ms = Math.round(leadInSeconds * 1000);
        if (ms <= 0) return "";
        return "adelay=" + ms + "|" + ms + ",";
    }

    /**
     * Build a scene clip from a pre-rendered video (e.g. a Google Flow / Omni
     * clip). Bypasses the Ken Burns filter graph entirely — the source clip
     * already has motion AND its own native audio (spoken dialogue + ambient,
     * generated by Omni with accurate beak lip-sync). We re-encode to the project
     * canvas + standard codecs and KEEP the clip's own audio (no separate
     * ElevenLabs voice track any more).
     */
    public Path buildFromClip(SceneInput scene, int w, int h,
                              Path workdir, Path output) {
        return buildFromClip(scene, w, h, workdir, output, 0.0);
    }

    /** @param voiceLeadInSeconds see {@link #build(SceneInput, MotionPreset, int,
     *  int, Path, Path, double)} — only the first scene shifts its voice so the
     *  intro dissolve finishes before the chick speaks. */
    public Path buildFromClip(SceneInput scene, int w, int h,
                              Path workdir, Path output, double voiceLeadInSeconds) {
        int fps = props.output().fps();
        int dur = effectiveDur(scene, workdir);
        // voiceLeadInSeconds is obsolete (it shifted the separate voice track) —
        // the clip carries its own synced audio, so there is nothing to delay.

        // BOOMERANG-FILL (gebruikerswens 2026-06-14: "geen stilstaand beeld of
        // dichte ogen aan het scène-eind"). Een Veo-clip die korter is dan de
        // gesproken scèneduur (bijv. de ~8s Veo-cap op een langere beat) werd
        // eerder opgevuld met tpad=clone — het LAATSTE frame bevroor, en dat
        // landde geregeld op een knipper (bevroren dichte ogen). Nu houden we de
        // beweging dóórlopend: speel de clip vooruit en hang het OMGEKEERDE
        // staartje eraan (zelfde techniek als IntroBuilder). Een knipper wordt zo
        // een korte flikkering mid-beweging i.p.v. een seconden lange dichte blik;
        // de overgang naar de volgende scène voelt vloeiend omdat het beeld nooit
        // stilstaat. De naad is naadloos: reverse begint op het laatste forward-
        // frame.
        //
        // Werking per geval (voice-track atrim=dur + -shortest cappen op `dur`):
        //  - clip >= dur            → alleen vooruit (reverse wordt nooit bereikt);
        //  - dur in (clipDur, 2*clipDur] → vooruit + omgekeerd staartje vult de gap;
        //  - dur > 2*clipDur (zeldzaam) → na de boomerang bevriest de tpad-clone op
        //    het EERSTE clipframe (= het on-model startbeeld, ogen open) i.p.v. het
        //    laatste — dus zelfs de fallback-freeze is een goede pose.
        // Geen ffprobe nodig: de split/reverse/concat regelt zich vanzelf en de
        // -shortest-cap bepaalt wat zichtbaar wordt.
        String filter = String.format(
                "[0:v]scale=%d:%d:force_original_aspect_ratio=increase," +
                "crop=%d:%d,setsar=1,fps=%d,split[fwd0][rv0];" +
                "[fwd0]setpts=PTS-STARTPTS[fwd];" +
                "[rv0]reverse,setpts=PTS-STARTPTS[rvr];" +
                "[fwd][rvr]concat=n=2:v=1:a=0,tpad=stop_mode=clone:stop_duration=30[v];" +
                // KEEP the clip's OWN audio (Omni native voice + ambient), resampled
                // to the lossless intermediate rate and padded/trimmed to the scene
                // length so a slightly-short clip never cuts the master's A/V drift.
                "[0:a]aresample=48000,apad,atrim=duration=%d[a]",
                w, h, w, h, fps, dur
        );

        log.debug("scene seq={} (from clip, native audio) canvas={}x{}", scene.seq(), w, h);

        // In-point trim (montage editor): seek to the user's chosen start BEFORE
        // -i (fast input seek, accurate enough for whole-clip trims), then -t caps
        // the read at the window length `dur`. So the scene shows [in, in+dur].
        // Null/0 → no -ss, byte-identical to the previous whole-clip behaviour.
        double trimSs = scene.trimStartSeconds() == null ? 0.0 : scene.trimStartSeconds();
        List<String> args = new java.util.ArrayList<>();
        args.add("-y");
        if (trimSs > 0.0) { args.add("-ss"); args.add(String.valueOf(trimSs)); }
        args.add("-t"); args.add(String.valueOf(dur));
        args.add("-i"); args.add(scene.clipPath());
        args.add("-filter_complex"); args.add(filter);
        args.add("-map"); args.add("[v]"); args.add("-map"); args.add("[a]");
        // crf 16 (was 20): first re-encode of the Veo clip — see above.
        args.add("-c:v"); args.add("libx264"); args.add("-preset"); args.add("veryfast");
        args.add("-crf"); args.add("16"); args.add("-r"); args.add(String.valueOf(fps));
        args.add("-pix_fmt"); args.add("yuv420p");
        args.add("-c:a"); args.add("pcm_s16le"); args.add("-ar"); args.add("48000");   // lossless intermediate (audit #1)
        args.add("-shortest");
        args.add(output.toString());
        runner.runFfmpeg(args, workdir);
        return output;
    }
}
