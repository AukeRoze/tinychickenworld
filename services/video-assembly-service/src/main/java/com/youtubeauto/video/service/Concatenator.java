package com.youtubeauto.video.service;

import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.ffmpeg.FfmpegRunner;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@lombok.extern.slf4j.Slf4j
public class Concatenator {

    private final FfmpegRunner runner;
    private final VideoProperties props;
    private final TransitionConfig transitionConfig;

    /** Branding watermark config. Hardcoded defaults; tweak via env vars
     *  if you want different position or size. Logo file must live at the
     *  path below (mounted from bible/logo.png on the host). */
    private static final String LOGO_PATH      = "/bible/logo.png";
    private static final String LOGO_POSITION  = "top-left"; // top-left|top-right|bottom-left|bottom-right
    private static final int    LOGO_MARGIN_PX = 40;
    private static final int    LOGO_WIDTH_PX  = 240;
    private static final double LOGO_OPACITY   = 0.85;

    /** Optional transition whoosh. A short swoosh mixed at each scene
     *  transition hides the cut and gives the montage rhythm. Looked up at
     *  these paths (first hit wins); if none exists the feature stays OFF and
     *  the audio chain is untouched. Drop a ~0.4s whoosh.mp3 to enable. */
    private static final String[] WHOOSH_PATHS = {
            "/bible/sfx/transitions/whoosh.mp3",
            "/bible/sfx/whoosh.mp3"
    };
    // 0.5 (was 0.6): with ~28 cuts in a full episode the whoosh at 0.6 became a
    // pattern of its own — softer reads as rhythm, not as a sound effect.
    private static final double WHOOSH_VOLUME = 0.5;

    /**
     * J/L-cut lead: how many extra seconds the incoming scene's AUDIO blends in
     * ahead of the visual cut (audio leads picture — the classic montage feel).
     * 0 = OFF (audio and video transition together, perfectly in sync).
     *
     * Drift-safe implementation: when > 0 every clip's audio gets a silent tail
     * of exactly this length ({@code apad}), so the longer audio crossfade eats
     * that silence instead of real audio. The total audio then runs only a single
     * constant {@code lead} longer than the video (one trailing pad), NOT a
     * per-cut accumulating lead — so dialogue can't progressively desync on a
     * long video. 0.12s gives a subtle J-cut feel; validate on a render before
     * pushing it higher.
     */
    private static final double JL_CUT_LEAD_SEC = 0.12;

    private String whooshPath() {
        for (String p : WHOOSH_PATHS) {
            if (Files.exists(Paths.get(p))) return p;
        }
        return null;
    }

    /** Optional shaking bell icon shown next to the subscribe end-card. Drop a
     *  transparent PNG here to enable; dormant otherwise. */
    private static final String BELL_PATH = "/bible/fx/bell.png";

    private String bellPath() {
        return Files.exists(Paths.get(BELL_PATH)) ? BELL_PATH : null;
    }

    /** Overlays a bell PNG to the right of the subscribe button, shaking
     *  (sinusoidal x wobble) during the end-card window. */
    private String appendBellOverlay(StringBuilder f, String videoIn, int idx, double totalDur) {
        double start = Math.max(0, totalDur - END_CARD_SECONDS);
        String st = fmt(start);
        f.append('[').append(idx).append(":v]scale=150:-1,format=rgba[bellimg];");
        f.append('[').append(videoIn).append("][bellimg]overlay=")
         .append("x=(W-w)/2+300+14*sin(16*(t-").append(st).append("))")
         .append(":y=H*0.55")
         .append(":enable=gte(t\\,").append(st).append(")")
         .append("[vbell]");
        return "vbell";
    }

    /**
     * Pixar / Studio-Ghibli style "warm cinematic" color grade.
     * Applied as the FINAL video filter before logo overlay. Recipe:
     *   - lift shadows toward warm orange (cozy feel)
     *   - cool highlights slightly (avoid burnt look)
     *   - bump saturation +5%% (lush but not garish)
     *   - light contrast curve (S-curve simulation)
     *   - very subtle vignette (focus on centre)
     * Skipped if the variable is set to "none".
     */
    @org.springframework.beans.factory.annotation.Value("${app.assembly.colorGrade:warm-pixar}")
    private String colorGrade;   // "none" to disable, set via app.assembly.colorGrade

    /**
     * Animated title-card overlay on the FIRST 2 seconds. Branded opener
     * that doesn't require a separate intro sting MP4.
     * Two lines:
     *   - Main title from the script (large, centred upper-third)
     *   - "Tiny Chicken World" channel name (smaller, below)
     * Both fade in within 400ms and stay visible until t=2s, then fade out.
     */
    @org.springframework.beans.factory.annotation.Value("${app.assembly.titleCard:auto}")
    private String titleCard;    // "none" to disable, set via app.assembly.titleCard
    private static final double TITLE_CARD_SECONDS = 2.5;

    /** Format a time/number for an ffmpeg expression — fixed 2 decimals, dot
     *  separator, no float artefacts. */
    private static String fmt(double v) {
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    private String appendTitleCard(StringBuilder f, String videoIn, String title) {
        if ("none".equalsIgnoreCase(titleCard)) return videoIn;
        if (title == null || title.isBlank()) title = "An adventure";
        // FFmpeg drawtext doesn't love single quotes in the text — strip.
        String safeTitle = title.replace("'", "").replace(":", "");
        if (safeTitle.length() > 50) safeTitle = safeTitle.substring(0, 47) + "...";

        // COLD-OPEN THE HOOK: hold the title card back ~1.5s so the emotional
        // close-up lands FIRST (the make-or-break retention beat), then the
        // brand fades in. Keeps it upper-third so it doesn't bury the eyes.
        // Commas are backslash-escaped (ProcessBuilder = no shell).
        double D = 1.5;            // title appears
        double inEnd = D + 0.4;    // fade-in done
        double E = D + 2.1;        // gone by here (~3.6s)
        double outStart = E - 0.5; // fade-out begins
        String d = fmt(D), ie = fmt(inEnd), e = fmt(E), os = fmt(outStart);
        String ds = fmt(D + 0.15), ies = fmt(inEnd + 0.15);  // channel name lags slightly
        String enable = "between(t\\," + d + "\\," + e + ")";
        String alphaMain = "if(lt(t\\," + d + ")\\,0\\,if(lt(t\\," + ie + ")\\,(t-" + d
                + ")/0.4\\,if(gt(t\\," + os + ")\\,max(0\\,(" + e + "-t)/0.5)\\,1)))";
        String alphaSub = "if(lt(t\\," + ds + ")\\,0\\,if(lt(t\\," + ies + ")\\,(t-" + ds
                + ")/0.4\\,if(gt(t\\," + os + ")\\,max(0\\,(" + e + "-t)/0.5)\\,1)))";
        // SAFE-ZONE: title sits in the LOWER THIRD (never over the character's
        // face/hat in the hook close-up), each line on a soft dark scrim so it
        // stays legible over any background. It still fades out before scene 2.
        // ADAPTIVE SIZING: the old fixed fontsize=78 made longer titles spill off
        // the frame (the "title too big / unreadable" bug). Size the title to a
        // SAFE text width, and wrap to two centred lines when one line would get
        // too small. Sized for the narrowest format (vertical, 1080w) so it never
        // overflows; on wider landscape frames it just centres comfortably.
        TitleLayout tl = layoutTitle(safeTitle);
        int fs = tl.fontSize();
        int lineH = (int) Math.round(fs * 1.30);
        java.util.List<String> lines = tl.lines();

        StringBuilder filter = new StringBuilder();
        // Title line(s) — lower third, each on a scrim box, individually centred.
        for (int i = 0; i < lines.size(); i++) {
            String y = (lines.size() == 1)
                    ? "(h*0.72)"
                    : (i == 0 ? "(h*0.70)-" + lineH : "(h*0.70)");   // two balanced lines
            filter.append("drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf")
                  .append(":text='").append(lines.get(i)).append("'")
                  .append(":fontsize=").append(fs).append(":fontcolor=white@1.0")
                  .append(":box=1:boxcolor=black@0.42:boxborderw=22")
                  .append(":shadowcolor=black@0.7:shadowx=4:shadowy=4")
                  .append(":x=(w-text_w)/2:y=").append(y)
                  .append(":enable=").append(enable)
                  .append(":alpha=").append(alphaMain)
                  .append(",");
        }
        // Channel name — below the title block (drops lower when the title wraps),
        // and never larger than the title.
        int chanFs = Math.max(26, Math.min(42, fs - 6));
        String chanY = (lines.size() == 1) ? "(h*0.83)" : "(h*0.70)+" + (lineH + 28);
        filter.append("drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf")
              .append(":text='Tiny Chicken World'")
              .append(":fontsize=").append(chanFs).append(":fontcolor=white@0.95")
              .append(":box=1:boxcolor=black@0.38:boxborderw=14")
              .append(":shadowcolor=black@0.7:shadowx=3:shadowy=3")
              .append(":x=(w-text_w)/2:y=").append(chanY)
              .append(":enable=").append(enable)
              .append(":alpha=").append(alphaSub);

        f.append('[').append(videoIn).append(']').append(filter).append("[vtitle];");
        return "vtitle";
    }

    /** One or two centred title lines + the fontsize that fits them. */
    private record TitleLayout(java.util.List<String> lines, int fontSize) {}

    private static final int    TITLE_TARGET_W = 930;   // safe text width @1080-wide
    private static final double GLYPH_ADV      = 0.60;  // DejaVu Bold avg advance ÷ fontsize
    private static final int    TITLE_MAX_FS   = 72;
    private static final int    TITLE_MIN_FS   = 32;

    /** Largest fontsize whose rendered line stays within TITLE_TARGET_W. */
    private int fitFontSize(int chars) {
        if (chars <= 0) chars = 1;
        int fs = (int) Math.floor(TITLE_TARGET_W / (GLYPH_ADV * chars));
        return Math.max(TITLE_MIN_FS, Math.min(TITLE_MAX_FS, fs));
    }

    /** Fit the title on one line; if that forces the font too small, split it
     *  into two balanced lines at the space nearest the middle. */
    private TitleLayout layoutTitle(String title) {
        int oneLine = fitFontSize(title.length());
        if (oneLine >= 50 || !title.contains(" ")) {
            return new TitleLayout(java.util.List.of(title), oneLine);
        }
        int mid = title.length() / 2, best = -1, bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < title.length(); i++) {
            if (title.charAt(i) == ' ') {
                int dist = Math.abs(i - mid);
                if (dist < bestDist) { bestDist = dist; best = i; }
            }
        }
        if (best < 0) return new TitleLayout(java.util.List.of(title), oneLine);
        String l1 = title.substring(0, best).trim();
        String l2 = title.substring(best + 1).trim();
        int fs = Math.min(64, fitFontSize(Math.max(l1.length(), l2.length())));
        return new TitleLayout(java.util.List.of(l1, l2), fs);
    }

    /**
     * Animated "Subscribe for more!" end-card overlaid on the last 3
     * seconds. Uses ffmpeg drawtext with a soft bouncy alpha fade-in.
     * Free, no extra assets needed beyond a system font.
     *
     * The overlay appears in the lower-third (above any captions area).
     * Set to "none" to skip — useful when an outro sting MP4 is in use
     * (that sting takes over the closing job).
     */
    @org.springframework.beans.factory.annotation.Value("${app.assembly.endCard:subscribe}")
    private String endCard;   // "none" to disable, set via app.assembly.endCard
    private static final double END_CARD_SECONDS = 3.0;   // duration before end

    private String appendEndCard(StringBuilder f, String videoIn, double totalDuration) {
        if ("none".equalsIgnoreCase(endCard)) return videoIn;
        double start = Math.max(0, totalDuration - END_CARD_SECONDS);
        // Drawtext animated alpha: 0 at start of card, 1 by start+0.3s,
        // hold until end. Plus a subtle scale-up emoji prefix and arrow.
        // Font path is the Liberation/DejaVu Sans Bold preinstalled in
        // the Spring Boot Alpine/Debian containers we use.
        // Use OS-installed font (ffmpeg's drawtext requires a fontfile).
        // Animated YouTube-style subscribe end-card: a red "SUBSCRIBE" pill that
        // slides up and gently bounces in, with a "for more + tap the bell" line
        // beneath it. Commas are backslash-escaped (ProcessBuilder = no shell);
        // numbers formatted to avoid float artefacts.
        String st  = fmt(start);          // button appears
        String st2 = fmt(start + 0.25);   // sub-line appears slightly later
        String filter =
                // SUBSCRIBE button: white text on a barn-red box, slide-up + bounce.
                "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
                + ":text='SUBSCRIBE'"
                + ":fontsize=80:fontcolor=white"
                + ":box=1:boxcolor=0xB83A1F@0.92:boxborderw=28"
                + ":x=(w-text_w)/2"
                + ":y=(h*0.60)+(1-min((t-" + st + ")/0.4\\,1))*120+8*sin(6.2832*(t-" + st + "))"
                + ":enable=gte(t\\," + st + ")"
                + ":alpha=if(gte(t\\," + st + ")\\,min((t-" + st + ")/0.3\\,1)\\,0)"
                + ","
                // Supporting line below the button.
                + "drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"
                + ":text='for more  +  tap the bell'"
                + ":fontsize=44:fontcolor=white"
                + ":shadowcolor=black@0.75:shadowx=3:shadowy=3"
                + ":x=(w-text_w)/2"
                + ":y=(h*0.76)+(1-min((t-" + st2 + ")/0.4\\,1))*120"
                + ":enable=gte(t\\," + st2 + ")"
                + ":alpha=if(gte(t\\," + st2 + ")\\,min((t-" + st2 + ")/0.3\\,1)\\,0)";
        f.append('[').append(videoIn).append(']').append(filter).append("[vend];");
        return "vend";
    }

    /** Builds the grading filter chunk for the given video input label. */
    private String appendColorGrade(StringBuilder f, String videoIn) {
        if ("none".equalsIgnoreCase(colorGrade)) return videoIn;
        // colorbalance shifts: rs/gs/bs = shadow R/G/B (-1..+1), midtones, highlights
        // eq: contrast/saturation tweaks
        // vignette: subtle dark edges
        f.append('[').append(videoIn).append("]")
         .append("colorbalance=rs=0.10:gs=0.04:bs=-0.06")    // warm shadows
         .append(":rm=0.04:gm=0.0:bm=-0.03")                  // mild warm midtones
         .append(":rh=-0.03:gh=0.0:bh=0.05,")                 // slight cool highlights
         .append("eq=saturation=1.06:contrast=1.04:brightness=0.01:gamma=0.98,")
         .append("vignette=PI/5")                              // soft vignette
         .append("[vgrade];");
        return "vgrade";
    }

    private boolean logoExists() {
        return java.nio.file.Files.exists(java.nio.file.Paths.get(LOGO_PATH));
    }

    /** Builds the overlay filter chain segment for the given video stream
     *  label, appending it to the filter complex. Returns the new stream
     *  label that contains the watermarked video. Caller is responsible
     *  for adding the logo PNG as an ffmpeg input. */
    private String appendLogoOverlay(StringBuilder f, String videoIn, int logoInputIdx) {
        // Scale logo to LOGO_WIDTH_PX and apply opacity via colorchannelmixer.
        f.append('[').append(logoInputIdx).append(":v]")
         .append("scale=").append(LOGO_WIDTH_PX).append(":-1,")
         .append("format=rgba,colorchannelmixer=aa=").append(LOGO_OPACITY)
         .append("[logo];");
        String x = switch (LOGO_POSITION) {
            case "top-right", "bottom-right" -> "W-w-" + LOGO_MARGIN_PX;
            default                          -> String.valueOf(LOGO_MARGIN_PX);
        };
        String y = switch (LOGO_POSITION) {
            case "bottom-left", "bottom-right" -> "H-h-" + LOGO_MARGIN_PX;
            default                            -> String.valueOf(LOGO_MARGIN_PX);
        };
        f.append('[').append(videoIn).append("][logo]")
         .append("overlay=").append(x).append(':').append(y)
         .append("[vlogo]");
        return "vlogo";
    }

    /**
     * Concat scene clips with 0.4s crossfade transitions between them.
     * Re-encodes (concat demuxer can't do xfade) but the jump from hard
     * cuts to smooth transitions makes the video feel much less like a
     * slideshow. Single-clip input is just copied.
     */
    /** Back-compat overload — no title means no title card. */
    public Path concat(List<Path> clips, Path concatList, Path output, Path workdir) {
        return concat(clips, concatList, output, workdir, null);
    }

    public Path concat(List<Path> clips, Path concatList, Path output, Path workdir, String title) {
        return concat(clips, null, concatList, output, workdir, title);
    }

    /** A scene-to-scene transition: ffmpeg xfade type + duration in seconds. */
    private record Transition(String type, double duration) {}

    /**
     * Maps the incoming scene's episode phase to a transition style.
     * Kids content cuts hard and fast on energetic beats and dissolves only
     * on calm/time-jump beats — a single uniform crossfade everywhere is what
     * makes a montage feel amateurish.
     */
    private Transition transitionFor(String phase) {
        // Bible first: channel.yml assembly.transitions lets the editor restyle
        // the cut language (any ffmpeg xfade type) without a recompile. Falls
        // through to the built-in mapping when unset/invalid.
        var bible = transitionConfig.forPhase(phase);
        if (bible.isPresent()) {
            return new Transition(bible.get().type(), bible.get().seconds());
        }
        String p = phase == null ? "" : phase.toLowerCase();
        return switch (p) {
            case "hook"        -> new Transition("fade", 0.10);       // near-cut, snappy
            case "setup"       -> new Transition("fade", 0.15);
            case "development" -> new Transition("fade", 0.15);       // quick cuts keep tempo
            case "climax"      -> new Transition("smoothleft", 0.30); // push accent into the peak
            case "resolution"  -> new Transition("dissolve", 0.35);   // gentle settle
            case "closer"      -> new Transition("fadeblack", 0.40);  // soft close
            default            -> new Transition("fade", 0.20);
        };
    }

    private String phaseAt(List<String> phases, int i) {
        return (phases != null && i >= 0 && i < phases.size()) ? phases.get(i) : null;
    }

    public Path concat(List<Path> clips, List<String> phases, Path concatList, Path output,
                       Path workdir, String title) {
        return concat(clips, phases, concatList, output, workdir, title, null);
    }

    public Path concat(List<Path> clips, List<String> phases, Path concatList, Path output,
                       Path workdir, String title, String outroPath) {
        boolean withLogo = logoExists();
        if (clips.size() == 1 && !withLogo) {
            // Trivial fast path — no logo, single clip, just stream-copy.
            runner.runFfmpeg(List.of(
                    "-y", "-i", clips.get(0).toString(),
                    "-c", "copy", output.toString()
            ), workdir);
            return output;
        }
        if (clips.size() == 1) {
            // Logo enabled but only one clip — overlay without crossfade.
            return concatSingleWithLogo(clips.get(0), output, workdir);
        }
        // OOM guard: a single filtergraph with 29 decoder chains + overlays got
        // the encoder OOM-killed (exit=137, ep-3 "Wobbly Egg" — which silently
        // shipped with hard cuts, no grade, no title card). Big jobs are built
        // in bounded passes instead: xfade per chunk → merge chunks (boundary
        // xfades) → one single-input overlay pass. Same visual output, capped
        // memory.
        if (clips.size() > XFADE_CHUNK) {
            try {
                return concatChunked(clips, phases, output, workdir, title, outroPath);
            } catch (RuntimeException e) {
                log.warn("Chunked concat failed ({}). Falling back to bare xfade concat.",
                        e.getMessage());
                int fbFps = props.output().fps();
                double[] fbDurs = new double[clips.size()];
                for (int i = 0; i < clips.size(); i++) fbDurs[i] = probeDuration(clips.get(i), workdir);
                return concatBare(clips, phases, output, workdir, fbFps, fbDurs);
            }
        }

        int fps = props.output().fps();
        double[] durs = new double[clips.size()];
        for (int i = 0; i < clips.size(); i++) durs[i] = probeDuration(clips.get(i), workdir);

        StringBuilder f = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            f.append('[').append(i).append(":v]setsar=1,fps=").append(fps)
             .append("[v").append(i).append("];");
            // J/L-cut: pad each clip's audio with a silent tail = the lead, so the
            // longer audio crossfade below blends into silence rather than real
            // audio (keeps the lead from accumulating across cuts — see
            // JL_CUT_LEAD_SEC). No-op string when the lead is 0.
            f.append('[').append(i).append(":a]aresample=48000");
            if (JL_CUT_LEAD_SEC > 0) {
                f.append(",apad=pad_dur=")
                 .append(String.format(java.util.Locale.ROOT, "%.3f", JL_CUT_LEAD_SEC));
            }
            f.append("[a").append(i).append("];");
        }

        String prevV = "v0";
        String prevA = "a0";
        double cumDur = durs[0];
        List<Double> transitionMids = new ArrayList<>();   // transition centres for whoosh SFX
        for (int i = 1; i < clips.size(); i++) {
            // Transition is chosen by the INCOMING scene's phase.
            Transition tr = transitionFor(phaseAt(phases, i));
            double xf = tr.duration();
            // Clamp so the overlap never exceeds half of either clip (short
            // clips otherwise make xfade/acrossfade error out).
            double maxXf = Math.min(durs[i], durs[i - 1]) * 0.5;
            if (xf > maxXf) xf = maxXf;
            if (xf < 0.05) xf = 0.05;
            double offset = cumDur - xf;
            transitionMids.add(offset + xf / 2.0);   // centre of this transition
            String outV = (i == clips.size() - 1) ? "vout" : ("vx" + i);
            String outA = (i == clips.size() - 1) ? "axfade" : ("ax" + i);
            f.append('[').append(prevV).append("][v").append(i).append(']')
             .append("xfade=transition=").append(tr.type())
             .append(":duration=").append(String.format(java.util.Locale.ROOT, "%.3f", xf))
             .append(":offset=").append(String.format(java.util.Locale.ROOT, "%.3f", offset))
             .append('[').append(outV).append("];");
            // Audio crossfade. By default it uses the SAME overlap as the video
            // xfade so A/V stays perfectly in sync. With JL_CUT_LEAD_SEC > 0 the
            // audio blends in earlier (J/L-cut feel) at the cost of a small lead.
            double axf = xf;
            if (JL_CUT_LEAD_SEC > 0) {
                axf = Math.min(maxXf, xf + JL_CUT_LEAD_SEC);
            }
            f.append('[').append(prevA).append("][a").append(i).append(']')
             .append("acrossfade=d=").append(String.format(java.util.Locale.ROOT, "%.3f", axf))
             .append('[').append(outA).append("];");
            prevV = outV;
            prevA = outA;
            cumDur += durs[i] - xf;
        }
        // Optional: mix a transition whoosh at each cut centre. Dormant unless
        // a whoosh asset exists — keeps the default audio chain untouched.
        String whoosh = whooshPath();
        boolean withWhoosh = whoosh != null && !transitionMids.isEmpty();
        String audioPre = prevA;
        if (withWhoosh) {
            int whooshIdx = clips.size() + (withLogo ? 1 : 0);
            int n = transitionMids.size();
            f.append('[').append(whooshIdx).append(":a]aresample=48000,asplit=").append(n);
            for (int k = 0; k < n; k++) f.append("[ws").append(k).append(']');
            f.append(';');
            for (int k = 0; k < n; k++) {
                long ms = Math.max(0, Math.round(transitionMids.get(k) * 1000));
                f.append("[ws").append(k).append("]adelay=").append(ms).append('|').append(ms)
                 .append(",volume=").append(String.format(java.util.Locale.ROOT, "%.2f", WHOOSH_VOLUME))
                 .append("[wd").append(k).append("];");
            }
            f.append('[').append(prevA).append(']');
            for (int k = 0; k < n; k++) f.append("[wd").append(k).append(']');
            // normalize=0 keeps the main mix at full level (don't divide by N).
            f.append("amix=inputs=").append(n + 1).append(":normalize=0[amixed];");
            audioPre = "amixed";
        }

        // PEAK GUARD ONLY — no loudnorm here. Loudness is normalised exactly
        // once, by FinalEncoder's two-pass loudnorm; intermediate normalisation
        // stacked 3× dynamics compression (audit 2026-06-11: pumping, flattened
        // voice expression). alimiter just prevents inter-stage clipping.
        f.append('[').append(audioPre).append(']')
         .append("alimiter=limit=0.891:level=false[aout];");

        // Pipeline order: color grade → title card → end-card → logo.
        // (Logo last so it stays clean on top of everything.)
        String finalVideo = appendColorGrade(f, "vout");
        finalVideo = appendTitleCard(f, finalVideo, title);
        // Skip the built-in subscribe end-card when a real outro.mp4 will be
        // appended — the outro takes over the closing, no double "subscribe".
        boolean hasOutro = outroPath != null && !outroPath.isBlank()
                && Files.exists(Paths.get(outroPath));
        if (!hasOutro) {
            finalVideo = appendEndCard(f, finalVideo, cumDur);
        }
        if (withLogo) {
            finalVideo = appendLogoOverlay(f, finalVideo, clips.size());
        }
        // Optional shaking bell PNG next to the subscribe button (dormant unless
        // bible/fx/bell.png exists; skipped when the outro.mp4 takes over).
        String bell = bellPath();
        boolean withBell = bell != null && !hasOutro;
        if (withBell) {
            if (f.charAt(f.length() - 1) != ';') f.append(';');
            int bellIdx = clips.size() + (withLogo ? 1 : 0) + (withWhoosh ? 1 : 0);
            finalVideo = appendBellOverlay(f, finalVideo, bellIdx, cumDur);
        }
        // Strip trailing ';' if present so ffmpeg doesn't complain.
        if (f.charAt(f.length() - 1) == ';') f.deleteCharAt(f.length() - 1);

        List<String> args = new ArrayList<>();
        args.add("-y");
        for (Path p : clips) { args.add("-i"); args.add(p.toString()); }
        if (withLogo) { args.add("-i"); args.add(LOGO_PATH); }
        if (withWhoosh) { args.add("-i"); args.add(whoosh); }   // index = clips.size() + (withLogo?1:0)
        if (withBell) { args.add("-i"); args.add(bell); }        // index after logo + whoosh
        args.add("-filter_complex"); args.add(f.toString());
        args.add("-map"); args.add("[" + finalVideo + "]");
        args.add("-map"); args.add("[aout]");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        // Near-lossless intermediate — FinalEncoder does the delivery compression,
        // so we don't want to throw away detail this early (less generation loss).
        args.add("-crf"); args.add("16");
        args.add("-r"); args.add(String.valueOf(fps));
        // Force 4:2:0: clips are now 4:2:0, but pin it so a stray 4:4:4 input
        // never pushes libx264 into the memory-hungry High 4:4:4 profile.
        args.add("-pix_fmt"); args.add("yuv420p");
        // PCM intermediate (audit #1) — audio stays lossless between stages;
        // only FinalEncoder compresses to AAC, exactly once.
        args.add("-c:a"); args.add("pcm_s16le");
        args.add(output.toString());

        // Resilience: the cosmetic overlays (color grade, title/end card, logo,
        // whoosh) are "nice to have". If the full filtergraph fails for any
        // reason, fall back to a bare concat so the video still ships rather
        // than failing the whole job.
        try {
            runner.runFfmpeg(args, workdir);
        } catch (RuntimeException e) {
            log.warn("Concat with overlays failed ({}). Retrying bare — no color "
                    + "grade / title / end-card / logo / whoosh — so the video still ships.",
                    e.getMessage());
            return concatBare(clips, phases, output, workdir, fps, durs);
        }
        return output;
    }

    // ── Chunked transition concat (OOM-safe) ───────────────────────────────

    /** Max clip inputs per ffmpeg xfade pass. Above this the single-graph path
     *  has been OOM-killed in production (exit=137 with 29 inputs on a 1080p
     *  render); 6 inputs keeps each pass comfortably bounded. */
    private static final int XFADE_CHUNK = 6;

    private static String fmt3(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    /** Shared near-lossless intermediate encode settings (FinalEncoder does the
     *  delivery compression). */
    private void encodeArgs(List<String> args, int fps) {
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        args.add("-crf"); args.add("16");
        args.add("-r"); args.add(String.valueOf(fps));
        args.add("-pix_fmt"); args.add("yuv420p");
        // PCM intermediate (audit #1) — audio stays lossless between stages;
        // only FinalEncoder compresses to AAC, exactly once.
        args.add("-c:a"); args.add("pcm_s16le");
    }

    /**
     * OOM-safe equivalent of the monolithic transition graph, in three bounded
     * passes:
     *   1. xfade INSIDE chunks of ≤{@link #XFADE_CHUNK} clips → chunk files;
     *   2. merge the chunk files, rendering each chunk BOUNDARY's transition
     *      (recursively, so any count of chunks stays bounded);
     *   3. ONE single-video-input overlay pass: colour grade → title card →
     *      end-card → logo → bell, whoosh SFX at the precomputed global cut
     *      centres, and loudnorm.
     * Visual/audio output matches the single graph; the only deliberate
     * difference is that the 0.12s J/L-cut audio lead is skipped (a per-clip
     * pad would accumulate drift across passes).
     * Costs two extra crf-16 re-encodes — visually negligible before the
     * FinalEncoder delivery pass.
     */
    private Path concatChunked(List<Path> clips, List<String> phases, Path output,
                               Path workdir, String title, String outroPath) {
        int fps = props.output().fps();
        int n = clips.size();
        log.info("Chunked concat: {} clips in passes of ≤{} inputs (single-graph OOM guard)",
                n, XFADE_CHUNK);

        double[] durs = new double[n];
        for (int i = 0; i < n; i++) durs[i] = probeDuration(clips.get(i), workdir);

        // Pre-compute every cut's transition + the GLOBAL timeline, identical
        // to the monolithic graph (whoosh centres and end-card start must land
        // on the same timestamps).
        Transition[] trs = new Transition[n];
        double[] xfs = new double[n];
        double cumDur = durs[0];
        List<Double> transitionMids = new ArrayList<>();
        for (int i = 1; i < n; i++) {
            Transition tr = transitionFor(phaseAt(phases, i));
            double xf = tr.duration();
            double maxXf = Math.min(durs[i], durs[i - 1]) * 0.5;
            if (xf > maxXf) xf = maxXf;
            if (xf < 0.05) xf = 0.05;
            trs[i] = tr;
            xfs[i] = xf;
            transitionMids.add(cumDur - xf / 2.0);
            cumDur += durs[i] - xf;
        }
        double totalDur = cumDur;

        Path tmpDir = workdir.resolve("xfade_chunks");
        try {
            Files.createDirectories(tmpDir);
        } catch (IOException io) {
            throw new RuntimeException("mkdir xfade_chunks failed: " + io.getMessage(), io);
        }

        // Pass 1 — xfade inside chunks. Boundary cuts are rendered in pass 2,
        // so every cut still gets its phase-mapped transition.
        List<Path> level = new ArrayList<>();
        List<Integer> firstClipOf = new ArrayList<>();   // global index of each chunk's first clip
        int start = 0, chunkNo = 0;
        while (start < n) {
            int end = Math.min(start + XFADE_CHUNK, n);
            if (n - end == 1) end = n;                   // no trailing 1-clip chunk
            Path out = tmpDir.resolve("chunk_" + (chunkNo++) + ".mkv");
            xfadeRange(clips, start, end, trs, xfs, durs, fps, out, workdir);
            level.add(out);
            firstClipOf.add(start);
            start = end;
        }

        // Pass 2 — merge chunk files with the boundary transitions, repeating
        // until one file remains (bounded inputs per merge).
        int mergeNo = 0;
        while (level.size() > 1) {
            List<Path> next = new ArrayList<>();
            List<Integer> nextFirst = new ArrayList<>();
            for (int s = 0; s < level.size(); ) {
                int e = Math.min(s + XFADE_CHUNK, level.size());
                if (level.size() - e == 1) e = level.size();
                if (e - s == 1) {
                    next.add(level.get(s));
                    nextFirst.add(firstClipOf.get(s));
                } else {
                    Path out = tmpDir.resolve("merge_" + (mergeNo++) + ".mkv");
                    mergeChunks(level.subList(s, e), firstClipOf.subList(s, e),
                            trs, xfs, fps, out, workdir);
                    next.add(out);
                    nextFirst.add(firstClipOf.get(s));
                }
                s = e;
            }
            level = next;
            firstClipOf = nextFirst;
        }
        Path merged = level.get(0);

        // Pass 3 — overlays + whoosh + loudnorm on ONE video input.
        return overlayPass(merged, output, workdir, title, outroPath, totalDur,
                transitionMids, fps);
    }

    /** xfades clips[start..end) into one chunk file, using the precomputed
     *  global transitions ({@code trs}/{@code xfs} indexed by global clip i). */
    private void xfadeRange(List<Path> clips, int start, int end, Transition[] trs,
                            double[] xfs, double[] durs, int fps, Path out, Path workdir) {
        int m = end - start;
        if (m == 1) {
            runner.runFfmpeg(List.of("-y", "-i", clips.get(start).toString(),
                    "-c", "copy", out.toString()), workdir);
            return;
        }
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < m; i++) {
            f.append('[').append(i).append(":v]setsar=1,fps=").append(fps)
             .append("[v").append(i).append("];");
            f.append('[').append(i).append(":a]aresample=48000[a").append(i).append("];");
        }
        String prevV = "v0", prevA = "a0";
        double cum = durs[start];
        for (int i = 1; i < m; i++) {
            int g = start + i;                          // global clip index
            double xf = xfs[g];
            double offset = cum - xf;
            String outV = (i == m - 1) ? "vout" : ("vx" + i);
            String outA = (i == m - 1) ? "achunk" : ("ax" + i);
            f.append('[').append(prevV).append("][v").append(i).append(']')
             .append("xfade=transition=").append(trs[g].type())
             .append(":duration=").append(fmt3(xf))
             .append(":offset=").append(fmt3(offset))
             .append('[').append(outV).append("];");
            f.append('[').append(prevA).append("][a").append(i).append(']')
             .append("acrossfade=d=").append(fmt3(xf))
             .append('[').append(outA).append("];");
            prevV = outV;
            prevA = outA;
            cum += durs[g] - xf;
        }
        f.deleteCharAt(f.length() - 1);                 // strip trailing ';'
        List<String> args = new ArrayList<>();
        args.add("-y");
        for (int i = start; i < end; i++) { args.add("-i"); args.add(clips.get(i).toString()); }
        args.add("-filter_complex"); args.add(f.toString());
        args.add("-map"); args.add("[vout]");
        args.add("-map"); args.add("[achunk]");
        encodeArgs(args, fps);
        args.add(out.toString());
        runner.runFfmpeg(args, workdir);
    }

    /** Merges chunk files, rendering each boundary with the transition of the
     *  first ORIGINAL clip of the incoming chunk ({@code firstClip} gives that
     *  global index). Durations are re-probed from the chunk files so offsets
     *  land exactly on the encoded boundaries. */
    private void mergeChunks(List<Path> parts, List<Integer> firstClip, Transition[] trs,
                             double[] xfs, int fps, Path out, Path workdir) {
        int m = parts.size();
        double[] d = new double[m];
        for (int i = 0; i < m; i++) d[i] = probeDuration(parts.get(i), workdir);
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < m; i++) {
            f.append('[').append(i).append(":v]setsar=1,fps=").append(fps)
             .append("[v").append(i).append("];");
            f.append('[').append(i).append(":a]aresample=48000[a").append(i).append("];");
        }
        String prevV = "v0", prevA = "a0";
        double cum = d[0];
        for (int i = 1; i < m; i++) {
            int g = firstClip.get(i);                   // boundary's incoming global clip
            double xf = Math.min(xfs[g], Math.min(d[i], d[i - 1]) * 0.5);
            if (xf < 0.05) xf = 0.05;
            double offset = cum - xf;
            String outV = (i == m - 1) ? "vout" : ("vx" + i);
            String outA = (i == m - 1) ? "achunk" : ("ax" + i);
            f.append('[').append(prevV).append("][v").append(i).append(']')
             .append("xfade=transition=").append(trs[g].type())
             .append(":duration=").append(fmt3(xf))
             .append(":offset=").append(fmt3(offset))
             .append('[').append(outV).append("];");
            f.append('[').append(prevA).append("][a").append(i).append(']')
             .append("acrossfade=d=").append(fmt3(xf))
             .append('[').append(outA).append("];");
            prevV = outV;
            prevA = outA;
            cum += d[i] - xf;
        }
        f.deleteCharAt(f.length() - 1);
        List<String> args = new ArrayList<>();
        args.add("-y");
        for (Path p : parts) { args.add("-i"); args.add(p.toString()); }
        args.add("-filter_complex"); args.add(f.toString());
        args.add("-map"); args.add("[vout]");
        args.add("-map"); args.add("[achunk]");
        encodeArgs(args, fps);
        args.add(out.toString());
        runner.runFfmpeg(args, workdir);
    }

    /** Final cosmetic pass on the merged file: colour grade → title card →
     *  end-card → logo → bell on the video; whoosh SFX at the global cut
     *  centres + loudnorm on the audio. One video input = tiny footprint. */
    private Path overlayPass(Path merged, Path output, Path workdir, String title,
                             String outroPath, double totalDur,
                             List<Double> transitionMids, int fps) {
        boolean withLogo = logoExists();
        String whoosh = whooshPath();
        boolean withWhoosh = whoosh != null && !transitionMids.isEmpty();
        boolean hasOutro = outroPath != null && !outroPath.isBlank()
                && Files.exists(Paths.get(outroPath));
        String bell = bellPath();
        boolean withBell = bell != null && !hasOutro;

        int logoIdx = 1;
        int whooshIdx = 1 + (withLogo ? 1 : 0);
        int bellIdx = whooshIdx + (withWhoosh ? 1 : 0);

        StringBuilder f = new StringBuilder();
        f.append("[0:v]null[vbase];");
        String v = appendColorGrade(f, "vbase");
        v = appendTitleCard(f, v, title);
        if (!hasOutro) v = appendEndCard(f, v, totalDur);
        if (withLogo) {
            v = appendLogoOverlay(f, v, logoIdx);
            f.append(';');
        }
        if (withBell) {
            v = appendBellOverlay(f, v, bellIdx, totalDur);
            f.append(';');
        }
        if (withWhoosh) {
            int k = transitionMids.size();
            f.append('[').append(whooshIdx).append(":a]aresample=48000,asplit=").append(k);
            for (int i = 0; i < k; i++) f.append("[ws").append(i).append(']');
            f.append(';');
            for (int i = 0; i < k; i++) {
                long ms = Math.max(0, Math.round(transitionMids.get(i) * 1000));
                f.append("[ws").append(i).append("]adelay=").append(ms).append('|').append(ms)
                 .append(",volume=").append(fmt(WHOOSH_VOLUME))
                 .append("[wd").append(i).append("];");
            }
            f.append("[0:a]");
            for (int i = 0; i < k; i++) f.append("[wd").append(i).append(']');
            f.append("amix=inputs=").append(k + 1).append(":normalize=0[amixed];");
            // Peak guard only — FinalEncoder owns the single loudnorm pass.
            f.append("[amixed]alimiter=limit=0.891:level=false[aout]");
        } else {
            f.append("[0:a]alimiter=limit=0.891:level=false[aout]");
        }

        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-i"); args.add(merged.toString());
        if (withLogo)   { args.add("-i"); args.add(LOGO_PATH); }
        if (withWhoosh) { args.add("-i"); args.add(whoosh); }
        if (withBell)   { args.add("-i"); args.add(bell); }
        args.add("-filter_complex"); args.add(f.toString());
        args.add("-map"); args.add("[" + v + "]");
        args.add("-map"); args.add("[aout]");
        encodeArgs(args, fps);
        args.add(output.toString());
        runner.runFfmpeg(args, workdir);
        return output;
    }

    /**
     * Minimal fallback concat: scene xfade transitions + audio crossfade +
     * loudnorm only. No cosmetic overlays. Used when the full {@link #concat}
     * filtergraph fails so a single overlay bug never sinks the whole render.
     */
    private Path concatBare(List<Path> clips, List<String> phases, Path output,
                            Path workdir, int fps, double[] durs) {
        StringBuilder f = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            f.append('[').append(i).append(":v]setsar=1,fps=").append(fps)
             .append("[v").append(i).append("];");
            f.append('[').append(i).append(":a]aresample=48000[a").append(i).append("];");
        }
        String prevV = "v0";
        String prevA = "a0";
        double cumDur = durs[0];
        for (int i = 1; i < clips.size(); i++) {
            Transition tr = transitionFor(phaseAt(phases, i));
            double xf = tr.duration();
            double maxXf = Math.min(durs[i], durs[i - 1]) * 0.5;
            if (xf > maxXf) xf = maxXf;
            if (xf < 0.05) xf = 0.05;
            double offset = cumDur - xf;
            String outV = (i == clips.size() - 1) ? "vout" : ("vx" + i);
            String outA = (i == clips.size() - 1) ? "axfade" : ("ax" + i);
            f.append('[').append(prevV).append("][v").append(i).append(']')
             .append("xfade=transition=").append(tr.type())
             .append(":duration=").append(String.format(java.util.Locale.ROOT, "%.3f", xf))
             .append(":offset=").append(String.format(java.util.Locale.ROOT, "%.3f", offset))
             .append('[').append(outV).append("];");
            f.append('[').append(prevA).append("][a").append(i).append(']')
             .append("acrossfade=d=").append(String.format(java.util.Locale.ROOT, "%.3f", xf))
             .append('[').append(outA).append("];");
            prevV = outV;
            prevA = outA;
            cumDur += durs[i] - xf;
        }
        // Peak guard only — FinalEncoder owns the single loudnorm pass.
        f.append('[').append(prevA).append("]alimiter=limit=0.891:level=false[aout]");

        List<String> args = new ArrayList<>();
        args.add("-y");
        for (Path p : clips) { args.add("-i"); args.add(p.toString()); }
        args.add("-filter_complex"); args.add(f.toString());
        args.add("-map"); args.add("[vout]");
        args.add("-map"); args.add("[aout]");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        args.add("-crf"); args.add("16");
        args.add("-r"); args.add(String.valueOf(fps));
        args.add("-pix_fmt"); args.add("yuv420p");
        // PCM intermediate (audit #1) — audio stays lossless between stages;
        // only FinalEncoder compresses to AAC, exactly once.
        args.add("-c:a"); args.add("pcm_s16le");
        args.add(output.toString());

        // Last-resort resilience: if even the bare xfade re-encode fails (e.g.
        // the encoder is OOM-killed), fall back to a concat-demuxer stream copy.
        // No transitions, no re-encode — near-zero memory — so a render always
        // ships. Safe because all clips share identical codec/params by now.
        try {
            runner.runFfmpeg(args, workdir);
        } catch (RuntimeException e) {
            log.warn("Bare concat re-encode failed ({}). Falling back to "
                    + "stream-copy concat — no transitions — so the video still ships.",
                    e.getMessage());
            return concatStreamCopy(clips, output, workdir);
        }
        return output;
    }

    /**
     * Final fallback: concat-demuxer stream copy. No filters, no re-encode, so
     * it uses almost no memory and cannot be OOM-killed. Requires all inputs to
     * share codec/resolution/pix_fmt — which they do once scene clips are built
     * to a uniform spec. Produces hard cuts (no xfade/crossfade).
     */
    private Path concatStreamCopy(List<Path> clips, Path output, Path workdir) {
        Path listFile = workdir.resolve("streamcopy_concat.txt");
        try {
            StringBuilder sb = new StringBuilder();
            for (Path p : clips) {
                sb.append("file '").append(p.toAbsolutePath()).append("'\n");
            }
            java.nio.file.Files.writeString(listFile, sb.toString());
        } catch (java.io.IOException io) {
            throw new RuntimeException("failed writing concat list: " + io.getMessage(), io);
        }
        List<String> args = new ArrayList<>(List.of(
                "-y", "-f", "concat", "-safe", "0",
                "-i", listFile.toString(),
                "-c", "copy",
                output.toString()
        ));
        runner.runFfmpeg(args, workdir);
        return output;
    }

    /** Single-clip path with logo overlay applied. */
    private Path concatSingleWithLogo(Path clip, Path output, Path workdir) {
        StringBuilder f = new StringBuilder();
        f.append("[0:v]copy[vbase];");
        String graded = appendColorGrade(f, "vbase");
        appendLogoOverlay(f, graded, 1);

        List<String> args = new ArrayList<>();
        args.add("-y");
        args.add("-i"); args.add(clip.toString());
        args.add("-i"); args.add(LOGO_PATH);
        args.add("-filter_complex"); args.add(f.toString());
        args.add("-map"); args.add("[vlogo]");
        args.add("-map"); args.add("0:a?");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        args.add("-crf"); args.add("16");   // near-lossless intermediate
        // PCM intermediate (audit #1) — audio stays lossless between stages;
        // only FinalEncoder compresses to AAC, exactly once.
        args.add("-c:a"); args.add("pcm_s16le");
        args.add(output.toString());
        runner.runFfmpeg(args, workdir);
        return output;
    }

    /**
     * Detects thin BAKED-IN black bars (pillarbox/letterbox already inside the
     * pixels) via ffmpeg cropdetect over ~2s of the clip. Returns a crop spec
     * "w:h:x:y" when genuine thin bars are found, or {@code null} when the
     * frame is clean (or when the detection looks like a dark scene rather
     * than bars — we only strip ≤8% per edge, never real content).
     */
    private String detectBakedBars(Path clip, Path workdir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-ss", "0.5", "-t", "2",
                    "-i", clip.toString(),
                    "-vf", "cropdetect=limit=24:round=2:reset=0",
                    "-f", "null", "-");
            pb.redirectErrorStream(true);
            pb.directory(workdir.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes());
            p.waitFor();
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("crop=(\\d+):(\\d+):(\\d+):(\\d+)").matcher(out);
            int cw = -1, ch = -1, cx = -1, cy = -1;
            while (m.find()) {           // last (accumulated) detection wins
                cw = Integer.parseInt(m.group(1));
                ch = Integer.parseInt(m.group(2));
                cx = Integer.parseInt(m.group(3));
                cy = Integer.parseInt(m.group(4));
            }
            if (cw <= 0 || ch <= 0) return null;
            if (cx <= 0 && cy <= 0) return null;            // already clean
            int fullW = cw + 2 * cx, fullH = ch + 2 * cy;   // bars are symmetric
            boolean thinBars = cx <= fullW * 0.08 && cy <= fullH * 0.08
                    && cw >= fullW * 0.84 && ch >= fullH * 0.84;
            if (!thinBars) return null;                     // dark scene, not bars
            log.warn("Input {} carries baked-in black bars — stripping crop={}:{}:{}:{}",
                    clip.getFileName(), cw, ch, cx, cy);
            return cw + ":" + ch + ":" + cx + ":" + cy;
        } catch (Exception e) {
            log.debug("cropdetect on {} failed ({}) — skipping bar strip",
                    clip.getFileName(), e.getMessage());
            return null;
        }
    }

    private double probeDuration(Path clip, Path workdir) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "ffprobe", "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=noprint_wrappers=1:nokey=1",
                    clip.toString());
            pb.directory(workdir.toFile());
            Process p = pb.start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            p.waitFor();
            return Double.parseDouble(out);
        } catch (Exception e) {
            throw new IllegalStateException("ffprobe failed for " + clip, e);
        }
    }

    /**
     * Concat the branded clips (intro → body → outro) — re-encodes so inputs may
     * have different codecs, resolutions, frame rates. Scales every input to the
     * requested {@code width × height} so they line up cleanly, and eases between
     * them with a short DISSOLVE instead of a hard cut, so the intro flows into
     * the episode and the episode flows into the outro.
     */
    public Path concatHeterogeneous(List<Path> clips, int width, int height,
                                    Path output, Path workdir) {
        if (clips.size() < 2) {
            throw new IllegalArgumentException("concatHeterogeneous needs >=2 inputs");
        }
        int fps = props.output().fps();

        // Probe durations so the dissolve offsets land exactly on each boundary.
        double[] durs = new double[clips.size()];
        for (int i = 0; i < clips.size(); i++) durs[i] = probeDuration(clips.get(i), workdir);

        StringBuilder f = new StringBuilder();
        for (int i = 0; i < clips.size(); i++) {
            // Self-heal BAKED-IN bars: if an input (e.g. a brand intro built
            // long ago from a padded source) already carries thin black bars
            // inside its own pixels, strip them first — blur-fill can't see
            // them (the input is already canvas-sized).
            String bakedCrop = detectBakedBars(clips.get(i), workdir);
            // Blurred-fill instead of pad=black: an off-aspect input (e.g. a
            // 1.75:1 brand clip on the 16:9 canvas) gets a blurred, darkened
            // enlargement of itself behind the sharp fit — NEVER black bars.
            // This was the pillarbox bug: pad=black baked 14px side bars into
            // every video whose intro/outro didn't exactly match the canvas.
            f.append('[').append(i).append(":v:0]");
            if (bakedCrop != null) f.append("crop=").append(bakedCrop).append(',');
            f.append("split=2[fg").append(i).append("s][bg").append(i).append("s];")
             .append("[bg").append(i).append("s]scale=").append(width).append(':').append(height)
             .append(":force_original_aspect_ratio=increase,crop=")
             .append(width).append(':').append(height)
             .append(",boxblur=20:1,eq=brightness=-0.05[bg").append(i).append("];")
             .append("[fg").append(i).append("s]scale=").append(width).append(':').append(height)
             .append(":force_original_aspect_ratio=decrease[fg").append(i).append("];")
             .append("[bg").append(i).append("][fg").append(i).append(']')
             .append("overlay=(W-w)/2:(H-h)/2,setsar=1,fps=").append(fps)
             .append("[v").append(i).append("];");
            f.append('[').append(i).append(":a:0]aresample=48000[a").append(i).append("];");
        }

        // DISSOLVE between each part (intro→body→outro) instead of a hard cut.
        // Per-boundary durations: the intro→episode blend is deliberately SLOW
        // (a felt, dreamy hand-off into the story — 0.5s read as a jump cut on
        // the short intro), the episode→outro blend slightly relaxed, any
        // other boundary snappy. Offsets are cumulative — each xfade overlaps
        // the prior tail — and clamped to half the shorter clip so very short
        // clips can't error out. Mirrors the per-scene chain in concat().
        final double DISSOLVE_INTRO = 1.1;   // intro → episode
        final double DISSOLVE_OUTRO = 0.8;   // episode → outro
        final double DISSOLVE_OTHER = 0.5;
        String prevV = "v0", prevA = "a0";
        double cumDur = durs[0];
        for (int i = 1; i < clips.size(); i++) {
            double want = (i == 1) ? DISSOLVE_INTRO
                    : (i == clips.size() - 1 ? DISSOLVE_OUTRO : DISSOLVE_OTHER);
            double d = Math.min(want, Math.min(durs[i], durs[i - 1]) * 0.5);
            if (d < 0.05) d = 0.05;
            double offset = cumDur - d;
            String outV = (i == clips.size() - 1) ? "vout" : ("vx" + i);
            String outA = (i == clips.size() - 1) ? "aout" : ("ax" + i);
            f.append('[').append(prevV).append("][v").append(i).append(']')
             .append("xfade=transition=fade:duration=")
             .append(String.format(java.util.Locale.ROOT, "%.3f", d))
             .append(":offset=").append(String.format(java.util.Locale.ROOT, "%.3f", offset))
             .append('[').append(outV).append("];");
            // Audio: same crossfade length as the video (keeps A/V durations in
            // lock-step) but with an EXPONENTIAL fade-in curve on the incoming
            // side — the long 1.1s intro dissolve otherwise pulled the
            // episode's first spoken line audibly under the intro. With c2=exp
            // the incoming audio stays near-silent until the visual blend is
            // almost done, then arrives with the cut.
            f.append('[').append(prevA).append("][a").append(i).append(']')
             .append("acrossfade=d=").append(String.format(java.util.Locale.ROOT, "%.3f", d))
             .append(":c1=tri:c2=exp")
             .append('[').append(outA).append("];");
            prevV = outV;
            prevA = outA;
            cumDur += durs[i] - d;
        }
        // Loudnorm to YouTube reference — see concat() above.
        // Peak guard only — FinalEncoder owns the single loudnorm pass.
        f.append('[').append(prevA).append("]alimiter=limit=0.891:level=false[a]");

        List<String> args = new ArrayList<>();
        args.add("-y");
        for (Path p : clips) {
            args.add("-i");
            args.add(p.toString());
        }
        args.add("-filter_complex");
        args.add(f.toString());
        args.add("-map"); args.add("[" + prevV + "]");
        args.add("-map"); args.add("[a]");
        args.add("-c:v"); args.add("libx264");
        args.add("-preset"); args.add("veryfast");
        args.add("-crf"); args.add("16");   // near-lossless intermediate
        // PCM intermediate (audit #1) — audio stays lossless between stages;
        // only FinalEncoder compresses to AAC, exactly once.
        args.add("-c:a"); args.add("pcm_s16le");
        args.add(output.toString());

        runner.runFfmpeg(args, workdir);
        return output;
    }
}
