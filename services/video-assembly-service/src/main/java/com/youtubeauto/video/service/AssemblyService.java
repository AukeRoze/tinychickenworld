package com.youtubeauto.video.service;

import com.youtubeauto.video.api.dto.AssemblyRequest;
import com.youtubeauto.video.api.dto.AssemblyRequest.SceneInput;
import com.youtubeauto.video.api.dto.AssemblyResult;
import com.youtubeauto.video.config.VideoProperties;
import com.youtubeauto.video.service.WorkspaceManager.Workspace;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * Pipeline orchestrator. Canvas dimensions come from the request
 * (or default to landscape) so the same code handles 1920×1080 + 1080×1920.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AssemblyService {

    private final WorkspaceManager workspaces;
    private final SceneClipBuilder sceneBuilder;
    private final Concatenator concatenator;
    private final AudioMixer mixer;
    private final SubtitleBurner subtitles;
    private final FinalEncoder finalEncoder;
    private final MediaProbe probe;
    private final MotionSelector motionSelector;
    private final ShortsBuilder shortsBuilder;
    private final VideoProperties props;

    /**
     * Pixar audit E2 — beat-sheet score plan: per-phase music level in dB
     * relative to the bed, as "phase:dB" pairs, e.g.
     * "hook:0,setup:-2,development:-1.5,climax:+1.5,resolution:-1,closer:-3".
     * Empty/blank = feature off → exactly the current behaviour (flat bed +
     * swell + dip). Parsed defensively: malformed pairs and unknown phases
     * are ignored; an unparseable value just means no plan.
     */
    @org.springframework.beans.factory.annotation.Value("${app.assembly.score-plan:}")
    private String scorePlanSpec;

    /** The only phases the score plan understands — anything else in the
     *  property is silently ignored (typo-proof). */
    private static final Set<String> SCORE_PHASES = Set.of(
            "hook", "setup", "development", "climax", "resolution", "closer");

    /** Per-scene key Concatenator reads to choose the transition INTO that scene:
     *  a user override encoded as {@code "@t:<type>:<seconds>"} when set, else the
     *  scene's plain phase (the existing phase-driven transition). Keeping the
     *  override inside the phase list means the cut logic needs no new parameters. */
    private static String transitionPhaseKey(SceneInput s) {
        String t = s.transitionType();
        if (t == null || t.isBlank()) return s.phase();
        double sec = s.transitionSeconds() == null ? 0.0 : s.transitionSeconds();
        return "@t:" + t.trim().toLowerCase(Locale.ROOT) + ":" + sec;
    }

    /**
     * Seconds the FIRST scene's voice is held back when a branded intro is
     * attached, so the chick speaks only AFTER the intro→episode dissolve has
     * resolved and she is fully in frame (feedback 2026-06-13). Must stay ≥ the
     * intro dissolve in {@link Concatenator#concatHeterogeneous}
     * (DISSOLVE_INTRO = 2.0s) plus a small settle margin. SceneClipBuilder
     * clamps this against the scene length, and shifting the voice does NOT
     * change any clip duration, so caption timing / A/V sync are untouched.
     * Verhoogd 1.8→2.0s mee met de langere 2.0s intro-dissolve (feedback 2026-06-14).
     */
    private static final double FIRST_SCENE_VOICE_LEAD_IN = 2.0;

    /**
     * Lead-in voor ELKE NIET-eerste scène (gebruikersfeedback 2026-06-14: "de hele
     * zin komt te vroeg"). De stem startte op frame 0 van de scèneclip, dus de
     * gesproken regel kwam vóór de kip goed in beweging/beeld was. We schuiven elke
     * regel een halve beat op zodat hij landt nadat de scène is begonnen. Verandert
     * geen clipduur (adelay binnen de scène) — alleen het instapmoment van de stem.
     * Tunebaar; verhoog/verlaag als het nog te vroeg/laat voelt.
     */
    private static final double DEFAULT_VOICE_LEAD_IN = 0.5;

    public AssemblyResult assemble(AssemblyRequest req) {
        Workspace ws = workspaces.create(req.jobId());
        int w = req.width()  != null && req.width()  > 0 ? req.width()  : props.output().width();
        int h = req.height() != null && req.height() > 0 ? req.height() : props.output().height();
        log.info("Assembling job={} canvas={}x{} in {}", req.jobId(), w, h, ws.root());

        // Step 2 — scene clips in parallel. When a branded intro is attached, the
        // FIRST scene's voice is shifted a beat later so it lands AFTER the
        // intro→episode dissolve (feedback 2026-06-13: "stemgeluid begint voordat
        // de eerste kip echt in beeld is"). 0 when there's no intro.
        double firstSceneVoiceLeadIn = existing(req.introPath()) ? FIRST_SCENE_VOICE_LEAD_IN : 0.0;
        List<Path> clips = buildSceneClipsParallel(req.scenes(), ws, w, h, firstSceneVoiceLeadIn);

        // Step 3 — concat scenes (with title card opener + end-card + logo + color grade).
        // Per-scene phases (aligned to clip order = scenes sorted by seq) drive
        // the transition style between scenes. A user-chosen transition override is
        // encoded into this same list as "@t:<type>:<seconds>" so Concatenator reads
        // it without any signature change (it only ever uses this list for cuts).
        List<String> phases = req.scenes().stream()
                .sorted(Comparator.comparingInt(SceneInput::seq))
                .map(AssemblyService::transitionPhaseKey)
                .toList();
        Path joined = concatenator.concat(clips, phases, ws.concatList(), ws.joined(),
                ws.root(), req.title(), req.outroPath());

        // Step 3.5 — prepend intro / append outro (heterogeneous, re-encoded)
        Path branded = attachIntroOutro(req, joined, ws, w, h);

        // Step 4 — optional music, with a gentle climax swell so the score follows
        // the story arc. Climax centre = intro offset + scripted seconds up to the
        // middle of the climax phase. Approximate (ignores crossfade overlap +
        // voice-stretch), but the swell is broad so a few seconds drift is fine.
        Path afterMusic = branded;
        if (req.backgroundMusicPath() != null && !req.backgroundMusicPath().isBlank()) {
            double[] swell = climaxSwell(req, ws);
            // Board #18 / backlog P2 — the SILENT visual beat earns near-silence
            // from the score too: a held breath with full music isn't a held
            // breath. Window = the silent scene (preferred) or the climax-phase
            // start; depth via app.assembly.climax-dip-db (0 = off). {0,0} when
            // timing is unknown — the dip then simply doesn't happen.
            double[] dip = musicDipWindow(req, ws);
            // Pixar audit E2 — the score follows the WHOLE beat-sheet, not just
            // the climax: a per-phase base arc (quiet under setup/development,
            // lifted at the climax, settled under resolution, soft under the
            // closer). The swell and dip stack on top of this base arc.
            List<AudioMixer.ScoreSegment> plan = scorePlan(req, ws);
            afterMusic = mixer.mixBackgroundMusic(branded, req.backgroundMusicPath(),
                    ws.withMusic(), ws.root(), swell[0], swell[1], dip[0], dip[1], plan);
        }

        // Step 4.5 — branded audio sting (channel sonic logo) at intro.
        // Mixed under the title card so it lands during the visual brand reveal.
        Path withSting = mixer.mixIntroSting(afterMusic, "/bible/sting.mp3",
                -3.0, ws.root().resolve("with_sting.mkv"), ws.root());
        afterMusic = withSting;

        // Step 5 — subtitles. Always WRITE the SRT (shifted by the intro
        // duration so it lines up with the final video) — it's uploaded to
        // YouTube as a real, toggleable caption track, keeping the image clean.
        // Only BURN it into the pixels when explicitly requested.
        //
        // Caption timing is anchored to the REAL joined-video timeline (probed
        // clip durations minus the crossfade overlap at each cut) + the
        // fractional intro length, instead of summed nominal scene seconds —
        // that old path ignored the xfade overlaps and rounded each scene to a
        // whole second, so captions drifted progressively later in the episode
        // (gebruikersfeedback 2026-06-25). sceneTimeline reuses the exact same
        // math as the concat above; falls back to nominal timing if it can't be
        // computed for any reason.
        int introOffset = introOffsetSeconds(req, ws);
        double introOffsetExact = introOffsetSecondsExact(req, ws);
        double[] sceneStarts = null, sceneDurs = null;
        try {
            Concatenator.SceneTimeline tl = concatenator.sceneTimeline(clips, phases, ws.root());
            sceneStarts = tl.startsSec();
            sceneDurs = tl.dursSec();
        } catch (Exception e) {
            log.warn("Could not compute real caption timeline, falling back to nominal "
                    + "seconds: {}", e.getMessage());
        }
        subtitles.writeSrt(req.scenes(), ws.subs(), introOffsetExact, sceneStarts, sceneDurs);
        Path captionsSrt = ws.subs();
        Path afterSubs = afterMusic;
        if (req.burnSubtitles()) {
            afterSubs = subtitles.burn(afterMusic, ws.subs(), ws.withSubs(), ws.root());
        }

        // Step 6 — final encode
        Path finalOut = finalEncoder.encode(afterSubs, ws.finalOut(), ws.root());

        // Step 7 — probe
        MediaProbe.Info info = probe.probe(finalOut, ws.root());
        long size;
        try { size = Files.size(finalOut); } catch (Exception e) { size = -1; }

        // Step 8 — bonus auto-Shorts. Only for landscape masters (vertical
        // would just be the original). Failures don't break the main video.
        // The hook extraction SKIPS the branded intro (introOffset) so the
        // Short opens on the cold-open beat, and the path is returned so the
        // orchestrator can store/surface/upload it (it used to be generated
        // and then forgotten on disk).
        String shortPath = null;
        if (h < w && info.durationSeconds() >= 30) {
            try {
                Path shortOut = ws.out().resolve("short.mp4");
                shortsBuilder.build(finalOut, (int) info.durationSeconds(),
                        introOffset, ws.subs(), req.title(), shortOut, ws.root());
                shortPath = shortOut.toString();
                log.info("Auto-Shorts generated: {}", shortOut);
            } catch (Exception e) {
                log.warn("Shorts build failed (not blocking): {}", e.getMessage());
            }
        }

        log.info("Assembled job={} -> {} ({}s, {}x{})",
                req.jobId(), finalOut, info.durationSeconds(), info.width(), info.height());

        return new AssemblyResult(
                req.jobId(), finalOut.toString(), size, info.durationSeconds(),
                info.videoCodec(), info.audioCodec(), info.width(), info.height(),
                captionsSrt.toString(), shortPath
        );
    }

    /** Cap for the climax-start fallback dip: dipping an entire long climax
     *  scene would hollow out the score, so the fallback dip covers at most
     *  this many seconds from the phase start. */
    private static final double CLIMAX_DIP_MAX_SEC = 6.0;

    /** {start, end} (seconds, final-video time) of the deliberate music dip
     *  for the held-silence beat. Preference order:
     *    (b) the scripted SILENT scene — no narration AND no line timings —
     *        the whole scene is the window;
     *    (a) else the START of the climax phase, capped at
     *        {@link #CLIMAX_DIP_MAX_SEC}.
     *  Returns {0,0} (→ mixer keeps a flat bed) when neither exists, when a
     *  scene carries broken timing, or on any exception — a cosmetic dip must
     *  never break the render. Same approximation rules as the climax swell
     *  (scripted durations, ignores crossfade overlap + voice-stretch; the
     *  mixer's 0.8s edge fades absorb that drift). */
    private double[] musicDipWindow(AssemblyRequest req, Workspace ws) {
        try {
            double t = introOffsetSeconds(req, ws);
            double climaxStart = -1, climaxDur = 0;
            var scenes = req.scenes().stream()
                    .sorted(Comparator.comparingInt(SceneInput::seq)).toList();
            for (SceneInput s : scenes) {
                double dur = s.durationSeconds();
                if (dur <= 0) return new double[]{0, 0};   // timing in doubt → skip
                boolean silent = (s.narration() == null || s.narration().isBlank())
                        && (s.lineTimings() == null || s.lineTimings().isEmpty());
                if (silent) return new double[]{t, t + dur};
                if (climaxStart < 0 && "climax".equalsIgnoreCase(s.phase())) {
                    climaxStart = t;
                    climaxDur = dur;
                }
                t += dur;
            }
            if (climaxStart >= 0) {
                return new double[]{climaxStart,
                        climaxStart + Math.min(climaxDur, CLIMAX_DIP_MAX_SEC)};
            }
        } catch (Exception ignore) { /* no dip */ }
        return new double[]{0, 0};
    }

    /** Beat-sheet score plan (Pixar audit E2): one segment per RUN of scenes
     *  sharing the same music level — phase time-spans walked with the same
     *  cursor as {@link #musicDipWindow} (intro offset + scripted durations,
     *  ignoring crossfade overlap / voice-stretch; the mixer's 1.5s boundary
     *  ramps absorb that drift). Scenes whose phase is missing or not in the
     *  configured plan sit at 0 dB (neutral bed). Returns an empty list —
     *  flat bed, exactly the pre-E2 behaviour — when the property is blank,
     *  when any scene timing is in doubt, when every level is 0 dB, or on any
     *  exception: the arc is cosmetic and must never break a render. */
    private List<AudioMixer.ScoreSegment> scorePlan(AssemblyRequest req, Workspace ws) {
        try {
            Map<String, Double> levels = parseScorePlanSpec();
            if (levels.isEmpty()) return List.of();
            double t = introOffsetSeconds(req, ws);
            List<AudioMixer.ScoreSegment> segments = new ArrayList<>();
            double segStart = t, segGain = Double.NaN;
            var scenes = req.scenes().stream()
                    .sorted(Comparator.comparingInt(SceneInput::seq)).toList();
            for (SceneInput s : scenes) {
                double dur = s.durationSeconds();
                if (dur <= 0) return List.of();   // timing in doubt → no plan
                String phase = s.phase() == null
                        ? "" : s.phase().trim().toLowerCase(Locale.ROOT);
                double gain = levels.getOrDefault(phase, 0.0);
                if (Double.isNaN(segGain)) {
                    segGain = gain;               // first scene opens the first run
                } else if (gain != segGain) {     // level change → close the run
                    segments.add(new AudioMixer.ScoreSegment(segStart, t, segGain));
                    segStart = t;
                    segGain = gain;
                }
                t += dur;
            }
            if (!Double.isNaN(segGain)) {
                segments.add(new AudioMixer.ScoreSegment(segStart, t, segGain));
            }
            boolean allFlat = segments.stream().allMatch(seg -> seg.gainDb() == 0.0);
            return allFlat ? List.of() : segments;
        } catch (Exception e) {
            return List.of();   // safe: flat bed
        }
    }

    /** Parses {@code app.assembly.score-plan} ("phase:dB,phase:dB,…") into a
     *  phase→dB map. Defensive on purpose: blank property → empty map (feature
     *  off); pairs that don't split into exactly two parts, phases outside
     *  {@link #SCORE_PHASES}, and non-numeric levels are each skipped without
     *  complaint. Levels are clamped to [-24, +6] dB. */
    private Map<String, Double> parseScorePlanSpec() {
        Map<String, Double> levels = new HashMap<>();
        if (scorePlanSpec == null || scorePlanSpec.isBlank()) return levels;
        for (String pair : scorePlanSpec.split(",")) {
            String[] kv = pair.trim().split(":");
            if (kv.length != 2) continue;
            String phase = kv[0].trim().toLowerCase(Locale.ROOT);
            if (!SCORE_PHASES.contains(phase)) continue;
            try {
                double db = Double.parseDouble(kv[1].trim());
                levels.put(phase, Math.max(-24.0, Math.min(6.0, db)));
            } catch (NumberFormatException ignore) { /* skip the bad pair */ }
        }
        return levels;
    }

    /** Intro duration (seconds) used to shift the caption timing. 0 when no
     *  intro is attached. Probed from the file; falls back to 0 on any error. */
    private int introOffsetSeconds(AssemblyRequest req, Workspace ws) {
        if (!existing(req.introPath())) return 0;
        try {
            return (int) Math.round(probe.probe(Paths.get(req.introPath()), ws.root()).durationSeconds());
        } catch (Exception e) {
            log.warn("Could not probe intro duration for caption offset: {}", e.getMessage());
            return 0;
        }
    }

    /** Fractional intro length (seconds) for caption timing — same probe as
     *  {@link #introOffsetSeconds} but WITHOUT rounding to a whole second, so a
     *  ~5.4s intro shifts captions by 5.4s, not 5s (the rounding was a constant
     *  sub-second skew on every cue). 0 when no intro is attached. */
    private double introOffsetSecondsExact(AssemblyRequest req, Workspace ws) {
        if (!existing(req.introPath())) return 0.0;
        try {
            return probe.probe(Paths.get(req.introPath()), ws.root()).durationSeconds();
        } catch (Exception e) {
            log.warn("Could not probe intro duration for caption offset: {}", e.getMessage());
            return 0.0;
        }
    }

    /** {center, spread} seconds for the climax music swell, in the FINAL video's
     *  timeline (intro offset + scripted seconds to the middle of the climax
     *  phase). {0,0} when there's no climax phase → mixer keeps a flat mix. */
    private double[] climaxSwell(AssemblyRequest req, Workspace ws) {
        try {
            double t = introOffsetSeconds(req, ws);
            double start = -1, end = -1;
            var scenes = req.scenes().stream()
                    .sorted(Comparator.comparingInt(SceneInput::seq)).toList();
            for (SceneInput s : scenes) {
                double dur = s.durationSeconds();
                if ("climax".equalsIgnoreCase(s.phase())) {
                    if (start < 0) start = t;
                    end = t + dur;
                }
                t += dur;
            }
            if (start < 0 || end <= start) return new double[]{0, 0};
            double center = (start + end) / 2.0;
            double spread = Math.max(4.0, (end - start) / 1.5);
            return new double[]{center, spread};
        } catch (Exception e) {
            return new double[]{0, 0};   // safe: no swell
        }
    }

    private Path attachIntroOutro(AssemblyRequest req, Path scenes, Workspace ws, int w, int h) {
        List<Path> parts = new ArrayList<>();
        if (existing(req.introPath())) parts.add(Paths.get(req.introPath()));
        parts.add(scenes);
        if (existing(req.outroPath())) parts.add(Paths.get(req.outroPath()));

        if (parts.size() == 1) {
            return scenes; // no branding files supplied or found
        }
        log.info("Attaching {}{} to scenes",
                existing(req.introPath()) ? "intro " : "",
                existing(req.outroPath()) ? "outro" : "");
        // Bumper-overgangen alleen meegeven als de bijbehorende bumper er is, zodat
        // de eerste/laatste grens de juiste (intro- resp. outro-)overgang krijgt.
        String introT = existing(req.introPath()) ? req.introTransitionType() : null;
        Double introS = existing(req.introPath()) ? req.introTransitionSeconds() : null;
        String outroT = existing(req.outroPath()) ? req.outroTransitionType() : null;
        Double outroS = existing(req.outroPath()) ? req.outroTransitionSeconds() : null;
        return concatenator.concatHeterogeneous(parts, w, h, ws.branded(), ws.root(),
                introT, introS, outroT, outroS);
    }

    private boolean existing(String path) {
        return path != null && !path.isBlank() && Files.exists(Paths.get(path));
    }

    private List<Path> buildSceneClipsParallel(List<SceneInput> scenes, Workspace ws,
                                               int w, int h, double firstSceneVoiceLeadIn) {
        int parallelism = Math.max(1, props.ffmpeg().sceneParallelism());
        ExecutorService pool = Executors.newFixedThreadPool(parallelism);
        MotionSelector.MotionPicker picker = motionSelector.startVideo();
        Map<Integer, MotionPreset> motionBySeq = new HashMap<>();
        scenes.stream().sorted(Comparator.comparingInt(SceneInput::seq))
                .forEach(s -> {
                    // The hook (scene 1) always gets a punch-in toward the face —
                    // far more arresting than a random pan for the retention beat.
                    boolean isHook = "hook".equalsIgnoreCase(s.phase()) || s.seq() == 1;
                    motionBySeq.put(s.seq(), isHook ? MotionPreset.ZOOM_IN : picker.next());
                });
        // The voice lead-in applies ONLY to the very first clip (lowest seq) —
        // that's the scene the intro dissolves into.
        int firstSeq = scenes.stream().mapToInt(SceneInput::seq).min().orElse(Integer.MIN_VALUE);
        try {
            List<Callable<Path>> tasks = scenes.stream().sorted(Comparator.comparingInt(SceneInput::seq))
                    .<Callable<Path>>map(scene -> () -> {
                        double leadIn = scene.seq() == firstSeq ? firstSceneVoiceLeadIn : DEFAULT_VOICE_LEAD_IN;
                        boolean hasClip = scene.clipPath() != null
                                && !scene.clipPath().isBlank()
                                && Files.exists(Paths.get(scene.clipPath()));
                        if (hasClip) {
                            log.info("scene seq={} using pre-rendered Veo clip {}",
                                    scene.seq(), scene.clipPath());
                            return sceneBuilder.buildFromClip(scene, w, h,
                                    ws.root(), ws.sceneClip(scene.seq()), leadIn);
                        }
                        return sceneBuilder.build(scene, motionBySeq.get(scene.seq()),
                                w, h, ws.root(), ws.sceneClip(scene.seq()), leadIn);
                    })
                    .toList();
            List<Future<Path>> futures = pool.invokeAll(tasks);
            List<Path> out = new ArrayList<>(futures.size());
            for (Future<Path> f : futures) out.add(f.get());
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while building scenes", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof RuntimeException re) throw re;
            throw new IllegalStateException(cause);
        } finally {
            pool.shutdownNow();
        }
    }
}
