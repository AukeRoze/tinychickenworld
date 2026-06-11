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

    public AssemblyResult assemble(AssemblyRequest req) {
        Workspace ws = workspaces.create(req.jobId());
        int w = req.width()  != null && req.width()  > 0 ? req.width()  : props.output().width();
        int h = req.height() != null && req.height() > 0 ? req.height() : props.output().height();
        log.info("Assembling job={} canvas={}x{} in {}", req.jobId(), w, h, ws.root());

        // Step 2 — scene clips in parallel
        List<Path> clips = buildSceneClipsParallel(req.scenes(), ws, w, h);

        // Step 3 — concat scenes (with title card opener + end-card + logo + color grade).
        // Per-scene phases (aligned to clip order = scenes sorted by seq) drive
        // the transition style between scenes.
        List<String> phases = req.scenes().stream()
                .sorted(Comparator.comparingInt(SceneInput::seq))
                .map(SceneInput::phase)
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
            afterMusic = mixer.mixBackgroundMusic(branded, req.backgroundMusicPath(),
                    ws.withMusic(), ws.root(), swell[0], swell[1]);
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
        int introOffset = introOffsetSeconds(req, ws);
        subtitles.writeSrt(req.scenes(), ws.subs(), introOffset);
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
        return concatenator.concatHeterogeneous(parts, w, h, ws.branded(), ws.root());
    }

    private boolean existing(String path) {
        return path != null && !path.isBlank() && Files.exists(Paths.get(path));
    }

    private List<Path> buildSceneClipsParallel(List<SceneInput> scenes, Workspace ws,
                                               int w, int h) {
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
        try {
            List<Callable<Path>> tasks = scenes.stream().sorted(Comparator.comparingInt(SceneInput::seq))
                    .<Callable<Path>>map(scene -> () -> {
                        boolean hasClip = scene.clipPath() != null
                                && !scene.clipPath().isBlank()
                                && Files.exists(Paths.get(scene.clipPath()));
                        if (hasClip) {
                            log.info("scene seq={} using pre-rendered Veo clip {}",
                                    scene.seq(), scene.clipPath());
                            return sceneBuilder.buildFromClip(scene, w, h,
                                    ws.root(), ws.sceneClip(scene.seq()));
                        }
                        return sceneBuilder.build(scene, motionBySeq.get(scene.seq()),
                                w, h, ws.root(), ws.sceneClip(scene.seq()));
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
