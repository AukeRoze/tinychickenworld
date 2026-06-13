package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.client.AssemblyServiceClient;
import com.youtubeauto.orchestrator.client.ImageServiceClient;
import com.youtubeauto.orchestrator.client.VideoGenerationServiceClient;
import com.youtubeauto.orchestrator.client.VoiceServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-click OUTRO rebuild: chains the running services to (re)make the branded
 * END-SCREEN outro without any manual step —
 *   1. image-service → a character-consistent end still (anchors pip/mo/bo),
 *   2. video-gen      → a Veo clip animating it (chicks giggle together, low in
 *                       frame, the upper two thirds calm/empty for YouTube's
 *                       end-screen elements),
 *   3. assembly       → composite logo + one thin bottom line + music + sparkle
 *                       → bible/outro.mp4 (see OutroBuilder's safe-zone schema).
 * Runs async (Veo takes minutes); the dashboard polls {@link #status()}.
 * Mirrors {@link IntroRebuildService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutroRebuildService {

    /** End-screen composition: chicks LOW (bottom third), upper two thirds
     *  deliberately empty — the still anchors the Veo clip, so the safe zones
     *  start here, not in the motion prompt. */
    private static final String STILL_DESC =
            "The SAME " + IntroRebuildService.BANNER_WORLD + ", but the warm sun now sits a "
            + "little LOWER and gently DIMMED for a soft, cosy end-of-day golden glow. "
            + "EXACTLY THREE chickens in the whole picture — Pip, Mo and Bo, each appearing "
            + "ONCE, no duplicates and no extra chickens anywhere. They sit CLOSE TOGETHER, "
            + "LOW in the frame — all three fully inside the BOTTOM THIRD of the picture — "
            + "smiling and giggling warmly at each other. The UPPER TWO THIRDS of the frame "
            + "are deliberately CALM and EMPTY: only soft warm evening sky and gently blurred "
            + "farm bokeh, NOTHING and NOBODY else there (that space is reserved for on-screen "
            + "elements added later — absolutely no extra chickens in that empty space). No "
            + "signboard, no sign, no banner and NO text anywhere. Wholesome, cosy composition.";

    /** End-screen motion prompt. The single dubbed farewell line is embedded
     *  VERBATIM (the "voice lines match MOTION_DESC word for word" contract),
     *  and the line is configurable — so this is a method, not a constant. */
    private String motionDesc() {
        return "STATIC, calm camera — locked off, or at most a very slow, minimal push-in. "
            + "EXACTLY THREE cartoon chickens in the whole clip — Pip, Mo and Bo, each "
            + "appearing ONCE; never duplicate a chick and never add extra chickens (not in "
            + "the background, the bokeh or the empty sky). The three little chickens sit "
            + "CLOSE TOGETHER, LOW in the frame — all three fully inside the BOTTOM THIRD of "
            + "the picture — in warm golden-hour light. "
            + "They giggle and laugh warmly WITH EACH OTHER first, leaning into each other "
            + "happily, then all three turn and beam warmly at the camera. While looking at "
            + "the camera, Pip says cheerfully: '" + outroLine + "'. "
            + "The UPPER TWO THIRDS of the frame stay deliberately CALM and EMPTY the whole "
            + "clip — just soft warm evening sky and gently blurred farm bokeh, with NOTHING "
            + "appearing there (no birds, no butterflies, no objects): on-screen end-screen "
            + "elements will be overlaid in that space later. "
            + "NO waving, NO held-up objects, NO signboard, no sign, no banner and NO text "
            + "anywhere. Only a soft breeze low in the grass, warm DIMMED end-of-day light, "
            + "cosy, gentle and unhurried. "
            // P5 (outro) — dub-friendly mouth motion: the farewell is dubbed in later as a
            // separate ElevenLabs track, so don't articulate the exact words. But Pip's beak
            // must still CLEARLY move while her line plays, or the audio reads as unsynced.
            + "BEAK / MOUTH: the spoken farewell is dubbed in separately, so do NOT lip-sync "
            + "or mouth the exact words and do NOT hold a wide gaping beak. BUT while Pip "
            + "says her line her beak MUST clearly open and close a few times — a simple, "
            + "visible 'talking' motion so it's obvious she is speaking — while Mo and Bo "
            + "only giggle softly with closed or barely-open beaks. Gentle rounded "
            + "open-close, never a wide gape, never word-shaped phonemes. "
            + IntroRebuildService.IDENTITY_LOCK;
    }

    /** The single farewell line, spoken by Pip. Configurable without a rebuild
     *  via {@code app.brand.outro-line} (or env OUTRO_LINE); read via @Value,
     *  NOT via OrchestratorProperties (the record binds positionally — see the
     *  app.seo note in application.yml). Channel language is English. */
    @Value("${app.brand.outro-line:See you in the next adventure!}")
    private String outroLine;

    /** The farewell as voice-service lines — built from {@link #outroLine} so
     *  the synthesized words ALWAYS match {@link #motionDesc()} verbatim.
     *  One line only since the end-screen redesign (the old three-voice
     *  Pip/Mo/Bo goodbye made the end screen too busy). */
    private List<Map<String, String>> outroLines() {
        return List.of(Map.of("speaker", "pip", "text", outroLine, "emotion", "warm"));
    }

    private final ImageServiceClient imageClient;
    private final VideoGenerationServiceClient videoGenClient;
    private final AssemblyServiceClient assemblyClient;
    private final VoiceServiceClient voiceClient;
    private final SceneImageQc sceneImageQc;
    private final com.youtubeauto.orchestrator.config.OrchestratorProperties props;

    private volatile String status = "idle";
    private volatile boolean running = false;
    /** Path of the last good Veo waving clip — lets us re-run ONLY the CTA/credits
     *  assembly (cheap, no Veo) to iterate on the outro composite. Also persisted
     *  to {@link #CLIP_MARKER} so a free re-composite survives an orchestrator
     *  restart (otherwise the cache would reset and force a paid Veo rebuild). */
    private volatile String lastClipPath;

    /** Disk marker holding the last Veo clip path (in the shared /workdir volume,
     *  so it persists across container restarts). */
    private static final java.nio.file.Path CLIP_MARKER =
            java.nio.file.Paths.get("/workdir/.last-outro-clip");

    public String status() { return status; }
    public boolean running() { return running; }

    /** Resolve the cached Veo clip path: in-memory first, then the disk marker. */
    private String resolveClip() {
        if (lastClipPath != null && !lastClipPath.isBlank()) return lastClipPath;
        try {
            if (java.nio.file.Files.exists(CLIP_MARKER)) {
                String p = java.nio.file.Files.readString(CLIP_MARKER).trim();
                if (!p.isBlank()) return p;
            }
        } catch (Exception ignore) { /* fall through */ }
        return null;
    }

    /** Vision-QC on the brand still before Veo — see IntroRebuildService. */
    private String qcStillOrRegen(UUID job, Map<String, Object> still, String firstPath, int maxRegens) {
        String path = firstPath;
        List<String> expected = IntroRebuildService.brandCastLines(props.bible().path());
        for (int attempt = 0; attempt <= maxRegens; attempt++) {
            SceneImageQc.Result r = sceneImageQc.check(java.nio.file.Paths.get(path), expected);
            if (r.ok()) return path;
            log.warn("Outro still QC FAIL (attempt {}/{}): {}", attempt + 1, maxRegens + 1, r.issues());
            if (attempt == maxRegens) break;
            try {
                JsonNode img = imageClient.generate(job, List.of(still), "landscape");
                String np = img.path("scenes").path(0).path("imagePath").asText("");
                if (np.isBlank()) break;
                path = np;
            } catch (Exception e) {
                log.warn("Outro still regen failed: {}", e.getMessage());
                break;
            }
        }
        return path;
    }

    private void rememberClip(String clip) {
        this.lastClipPath = clip;
        try { java.nio.file.Files.writeString(CLIP_MARKER, clip); }
        catch (Exception e) { log.warn("Could not persist outro clip marker: {}", e.toString()); }
    }

    public boolean hasClip() {
        String p = resolveClip();
        return p != null && java.nio.file.Files.exists(java.nio.file.Paths.get(p));
    }

    @Async("pipelineExecutor")
    public void rebuild() { rebuild(null); }

    /** @param model optional Veo model override from the UI (veo3_1_lite /
     *  veo3_1_fast / veo3_1); null/blank = bible routing for sceneType outro. */
    @Async("pipelineExecutor")
    public void rebuild(String model) {
        if (running) { log.info("Outro rebuild already running — ignoring"); return; }
        running = true;
        try {
            UUID job = UUID.randomUUID();

            status = "1/3 — generating end still…";
            log.info("Outro rebuild {}: still", job);
            Map<String, Object> still = new HashMap<>();
            still.put("seq", 1);
            still.put("visualDesc", STILL_DESC);
            still.put("characters", List.of("pip", "mo", "bo"));
            JsonNode img = imageClient.generate(job, List.of(still), "landscape");
            String stillPath = img.path("scenes").path(0).path("imagePath").asText("");
            if (stillPath.isBlank()) throw new IllegalStateException("image-service returned no still");
            // QC the still BEFORE the (paid) Veo step — mirrors IntroRebuildService.
            stillPath = qcStillOrRegen(job, still, stillPath, 2);

            status = "2/3 — Veo giggling-chicks clip (a few minutes)…";
            log.info("Outro rebuild {}: veo clip from {}", job, stillPath);
            Map<String, Object> scene = new HashMap<>();
            scene.put("seq", 1);
            scene.put("sceneType", "outro");
            scene.put("startImagePath", stillPath);
            scene.put("visualDesc", motionDesc());
            scene.put("negativePrompt", IntroRebuildService.IDENTITY_NEG);
            // ~8s of Veo (6 → 8 with the 12s end-screen template): more giggle
            // before OutroBuilder's tpad freeze-hold covers the final ~4s.
            scene.put("durationSeconds", 8);
            if (model != null && !model.isBlank()) scene.put("modelOverride", model.trim());
            // Async submit + poll (drop-in: same args, same result shape) — no
            // minutes-long open HTTP connection for a proxy/idle-timeout to kill.
            JsonNode clips = videoGenClient.generateAsync(job, "landscape", List.of(scene));
            JsonNode c0 = clips.path("clips").path(0);
            if (!"OK".equalsIgnoreCase(c0.path("status").asText()) || c0.path("clipPath").asText("").isBlank()) {
                throw new IllegalStateException("Veo clip not OK: " + c0.path("status").asText()
                        + " " + c0.path("error").asText(""));
            }
            String clip = c0.path("clipPath").asText();
            rememberClip(clip);   // cache for cheap re-composites (no Veo), survives restart

            // Branded farewell voice (same voice as the episodes); best-effort.
            List<String> voiceLines =
                    IntroRebuildService.synthVoiceLines(voiceClient, job, outroLines());

            status = "3/3 — compositing end-screen outro…";
            log.info("Outro rebuild {}: composite {} ({} branded voices)", job, clip, voiceLines.size());
            assemblyClient.buildOutro(clip, voiceLines);

            status = "done — outro.mp4 refreshed at " + LocalTime.now().withNano(0);
            log.info("Outro rebuild {} done", job);
        } catch (Exception e) {
            status = "error — " + e.getMessage();
            log.warn("Outro rebuild failed: {}", e.toString());
        } finally {
            running = false;
        }
    }

    /**
     * Re-run ONLY the overlay / voice / SFX assembly on the last Veo clip — no
     * image, no Veo. Cheap way to iterate on the outro composite (e.g. after an
     * overlay or music change) without paying for a fresh Veo render. Requires a
     * previous full rebuild so a clip exists. Mirrors {@link IntroRebuildService}.
     */
    @Async("pipelineExecutor")
    public void recomposite() {
        if (running) { log.info("Outro busy — ignoring re-composite"); return; }
        if (!hasClip()) {
            status = "error — no cached outro clip yet; do a full rebuild first";
            return;
        }
        running = true;
        try {
            UUID job = UUID.randomUUID();
            String clip = resolveClip();
            status = "re-compositing end-screen overlays + sound (no Veo)…";
            log.info("Outro re-composite from cached clip {}", clip);
            List<String> voiceLines =
                    IntroRebuildService.synthVoiceLines(voiceClient, job, outroLines());
            assemblyClient.buildOutro(clip, voiceLines);
            status = "done — outro.mp4 re-composited at " + LocalTime.now().withNano(0);
        } catch (Exception e) {
            status = "error — " + e.getMessage();
            log.warn("Outro re-composite failed: {}", e.toString());
        } finally {
            running = false;
        }
    }
}
