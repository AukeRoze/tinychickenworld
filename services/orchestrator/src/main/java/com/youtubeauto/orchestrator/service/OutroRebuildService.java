package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.client.AssemblyServiceClient;
import com.youtubeauto.orchestrator.client.ImageServiceClient;
import com.youtubeauto.orchestrator.client.VideoGenerationServiceClient;
import com.youtubeauto.orchestrator.client.VoiceServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-click OUTRO rebuild: chains the running services to (re)make the branded
 * outro without any manual step —
 *   1. image-service → a character-consistent end still (anchors pip/mo/bo),
 *   2. video-gen      → a Veo clip animating it (chicks wave goodbye one by one),
 *   3. assembly       → composite the SUBSCRIBE CTA + logo + sparkle → bible/outro.mp4.
 * Runs async (Veo takes minutes); the dashboard polls {@link #status()}.
 * Mirrors {@link IntroRebuildService}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutroRebuildService {

    private static final String STILL_DESC =
            "The SAME " + IntroRebuildService.BANNER_WORLD + ", but the warm sun now sits a "
            + "little LOWER and gently DIMMED for a soft, cosy end-of-day golden glow. The "
            + "three little chickens stand close together, centred, facing the camera and "
            + "smiling warmly, each lifting one wing to wave goodbye. Keep the LOWER THIRD of "
            + "the frame as open grass with nothing in it (room for a caption). Wholesome, "
            + "cosy, centred composition.";

    private static final String MOTION_DESC =
            "The three little cartoon chickens wave goodbye to the viewer ONE BY ONE, each saying a "
            + "cheerful farewell as they wave, then all three wave together at the end. "
            + "FIRST Pip waves her wing energetically, tips her straw hat and says brightly: "
            + "'Bye bye!'. THEN Mo gives a calm, gentle wave and a warm slow blink and says: "
            + "'See you soon!'. THEN Bo waves both wings in a gentle wobble, nudges his round "
            + "glasses and says warmly: 'Byeee!'. Finally all three wave together and beam at "
            + "the camera. A soft breeze sways the grass and flowers, a few petals drift and "
            + "2-3 butterflies flutter past, in warm DIMMED end-of-day light. Keep it warm, "
            + "calm and gentle — NOT over-the-top, NO laughing or giggling. Keep the lower "
            + "third clear. "
            // P5 (outro) — dub-friendly mouth motion: the farewells are dubbed in later as
            // a separate ElevenLabs track, so don't articulate the exact words. But the beak
            // must still CLEARLY move on the chicken whose turn it is, or the audio reads as
            // unsynced. Visible open-close on the speaker, closed on the others.
            + "BEAK / MOUTH: the farewells are dubbed in separately, so do NOT lip-sync or "
            + "mouth the exact words and do NOT hold a wide gaping beak. BUT on its OWN turn "
            + "the speaking chicken MUST clearly open and close its beak a few times — a "
            + "simple, visible 'talking' motion so it's obvious which chicken is speaking — "
            + "while the other two keep their beaks CLOSED and still. Gentle rounded "
            + "open-close, never a wide gape, never word-shaped phonemes. "
            + IntroRebuildService.IDENTITY_LOCK;

    /** The chickens' farewell lines — MUST match the words in {@link #MOTION_DESC}
     *  so the spoken voices line up with the wave. Order = Pip, Mo, Bo. */
    static final List<Map<String, String>> OUTRO_LINES = List.of(
            Map.of("speaker", "pip", "text", "Bye bye!",     "emotion", "happy"),
            Map.of("speaker", "mo",  "text", "See you soon!", "emotion", "warm"),
            Map.of("speaker", "bo",  "text", "Byeee!",        "emotion", "happy"));

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

            status = "2/3 — Veo chickens-waving clip (a few minutes)…";
            log.info("Outro rebuild {}: veo clip from {}", job, stillPath);
            Map<String, Object> scene = new HashMap<>();
            scene.put("seq", 1);
            scene.put("sceneType", "outro");
            scene.put("startImagePath", stillPath);
            scene.put("visualDesc", MOTION_DESC);
            scene.put("negativePrompt", IntroRebuildService.IDENTITY_NEG);
            scene.put("durationSeconds", 6);
            if (model != null && !model.isBlank()) scene.put("modelOverride", model.trim());
            JsonNode clips = videoGenClient.generate(job, "landscape", List.of(scene));
            JsonNode c0 = clips.path("clips").path(0);
            if (!"OK".equalsIgnoreCase(c0.path("status").asText()) || c0.path("clipPath").asText("").isBlank()) {
                throw new IllegalStateException("Veo clip not OK: " + c0.path("status").asText()
                        + " " + c0.path("error").asText(""));
            }
            String clip = c0.path("clipPath").asText();
            rememberClip(clip);   // cache for cheap re-composites (no Veo), survives restart

            // Branded farewell voices (same voices as the episodes); best-effort.
            List<String> voiceLines =
                    IntroRebuildService.synthVoiceLines(voiceClient, job, OUTRO_LINES);

            status = "3/3 — compositing SUBSCRIBE call-to-action…";
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
     * Re-run ONLY the CTA / credits / SFX assembly on the last Veo clip — no
     * image, no Veo. Cheap way to iterate on the outro composite (e.g. after a
     * credits-overlay change) without paying for a fresh Veo render. Requires a
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
            status = "re-compositing CTA + credits + sound (no Veo)…";
            log.info("Outro re-composite from cached clip {}", clip);
            List<String> voiceLines =
                    IntroRebuildService.synthVoiceLines(voiceClient, job, OUTRO_LINES);
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
