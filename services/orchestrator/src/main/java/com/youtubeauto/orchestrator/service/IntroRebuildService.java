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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-click intro rebuild: chains the running services to (re)make the branded
 * intro without any manual step —
 *   1. image-service → a character-consistent start still (anchors pip/mo/bo),
 *   2. video-gen      → a Veo clip animating it (chicks pop up, wave, hello),
 *   3. assembly       → composite the title overlay + SFX → bible/intro.mp4.
 * Runs async (Veo takes minutes); the dashboard polls {@link #status()}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntroRebuildService {

    /** Per-character identity lock — appended to intro/outro Veo prompts because
     *  these clips bypass the normal compileVeoPrompt DNA injection, so without
     *  this Veo swaps the chicks' colours + accessories (hat/glasses/scarf). */
    static final String IDENTITY_LOCK =
            "IDENTITY LOCK — keep each chicken EXACTLY as described and NEVER swap their "
            + "accessories: Pip is a CREAM-WHITE chick wearing a STRAW HAT and a RED neck "
            + "bandana (no glasses). Mo is a BLUE-GREY chick wearing a RED knitted neck scarf "
            + "(no hat, no glasses). Bo is a TAN/sandy chick wearing ROUND thin-framed GLASSES "
            + "and a GREEN neck scarf (no hat). Keep their body colours and accessories stable "
            + "the whole clip; do not move the hat, glasses or scarves between them, and add no "
            + "extra accessories. Keep each chick's PLUMP, ROUNDED baby-chick body and size "
            + "CONSTANT the entire clip — never thin out, slim down, stretch, elongate or change "
            + "a chicken's body volume or size at any moment.";

    static final String IDENTITY_NEG =
            "swapped accessories, glasses on the wrong chicken, hat on the wrong chicken, "
            + "wrong colours, extra hats, extra glasses, duplicate chicken, morphing, "
            + "identity drift, hat flying off, hat falling off, hat lifting off the head, "
            + "characters swapping places, characters changing position, characters crossing "
            + "over each other, lip-sync, lip syncing, mouthing words, talking mouth, "
            + "wide open beak, gaping beak, exaggerated mouth movement, flapping beak, "
            + "thin chicken, skinny chicken, slim chicken, elongated body, stretched body, "
            + "lanky chicken, slimmed-down chicken, deformed proportions, body morphing, "
            + "changing body size, "
            + "text, watermark";

    /** Shared channel-banner world so intro + outro match the brand banner. */
    static final String BANNER_WORLD =
            "a warm golden-hour farm world that matches the channel banner: soft rolling "
            + "green hills, a friendly RED BARN and a WINDMILL in the background, a winding "
            + "little stream, scattered sunflowers and daisies, and a big warm low SUN glowing "
            + "softly in the sky";

    private static final String STILL_DESC =
            "A calm, bright " + BANNER_WORLD + ". The three little chickens stand together in the "
            + "lower centre, visible from the chest up, smiling warmly at the camera, with calm "
            + "open meadow and sky around and above them so a title can be placed over the scene "
            + "later. Calm, centred, uncluttered composition. No signboard, no sign, no banner "
            + "and NO text anywhere.";

    private static final String MOTION_DESC =
            "Open on all THREE little cartoon chickens ALREADY standing together in the lower "
            + "centre of the sunny meadow, fully visible from the very first frame. NONE of them "
            + "appears, pops up, rises, walks in, hatches or fades in — all three are present and "
            + "in frame the ENTIRE time. They greet the viewer ONE BY ONE in a BRISK, warm "
            + "rhythm — three quick greeting turns packed into the FIRST FOUR SECONDS, each turn "
            + "about ONE second, immediately one after another with NO long pauses between them "
            + "(the dubbed voices follow this exact timing): seconds 0.5-1.6 Pip gives a little "
            + "wing-wave and tips her straw hat (her beak clearly opens and closes during HER "
            + "second only); seconds 1.7-2.8 Mo gives a small nod and head tilt (HIS beak moves "
            + "during his second only); seconds 2.9-4.0 Bo nudges his round glasses up with a "
            + "wing-tip and gives a cheerful little bob (HIS beak moves during his second only). "
            + "From second 4 onward all three SETTLE, sit still together in the lower centre and "
            + "smile warmly at the camera with beaks CLOSED, leaving calm open space above them. "
            // The assembly HOLDS the final Veo frame while the title appears, so the
            // clip must END on an open-eyed pose — a closed/mid-blink last frame
            // freezes shut eyes under the title and looks bad.
            + "END the clip with ALL THREE chickens' EYES WIDE OPEN, bright and looking warmly "
            + "at the camera, smiling — the FINAL frame must have every chicken's eyes FULLY "
            + "OPEN, never closed, never mid-blink, never half-closed. NO signboard, "
            + "no sign, no banner and NO text anywhere in the scene. STABILITY: Pip's straw hat "
            + "stays FIRMLY on her head the entire time — it never flies off, lifts or falls. "
            + "Each chicken STAYS in the exact same spot it starts in (same left/middle/right "
            + "position as the start image) and NEVER swaps places, slides across or crosses past "
            + "another. Calm, gentle, unhurried and warm "
            + "— NOT busy, NOT high-energy, NO laughing. Only a soft breeze, a couple of "
            + "drifting petals and 1-2 butterflies far in the background. "
            // P5 (intro) — dub-friendly mouth motion: the names are dubbed in later as a
            // separate ElevenLabs track, so don't articulate the exact words. But the beak
            // must still CLEARLY move on the chicken whose turn it is, or the audio reads as
            // unsynced (sound with a closed beak). Visible open-close on the speaker, closed
            // on the others.
            + "BEAK / MOUTH: the names are dubbed in separately, so do NOT lip-sync or mouth the "
            + "exact words and do NOT hold a wide gaping beak. BUT on its OWN greeting turn the "
            + "speaking chicken MUST clearly open and close its beak a few times — a simple, "
            + "visible 'talking' motion so it's obvious which chicken is speaking — while the "
            + "other two keep their beaks CLOSED and still. Gentle rounded open-close, never a "
            + "wide gape, never word-shaped phonemes. " + IDENTITY_LOCK;

    /** The chickens' self-introduction lines — MUST match the words in
     *  {@link #MOTION_DESC} so the spoken ElevenLabs voices line up with what the
     *  Veo clip shows them saying. Order = Pip, Mo, Bo. */
    static final List<Map<String, String>> INTRO_LINES = List.of(
            Map.of("speaker", "pip", "text", "Hello! I'm Pip!",  "emotion", "excited"),
            Map.of("speaker", "mo",  "text", "Hi, I'm Mo.",      "emotion", "warm"),
            Map.of("speaker", "bo",  "text", "And I'm Bo!",      "emotion", "happy"));

    private final ImageServiceClient imageClient;
    private final VideoGenerationServiceClient videoGenClient;
    private final AssemblyServiceClient assemblyClient;
    private final VoiceServiceClient voiceClient;
    private final SceneImageQc sceneImageQc;
    private final com.youtubeauto.orchestrator.config.OrchestratorProperties props;

    /** Cast DNA lines for the brand-still QC — same vocabulary the normal
     *  scene QC uses (accessory, anti-accessory, eye colour, silhouette/comb).
     *  Static so OutroRebuildService reuses it, like synthVoiceLines. */
    static List<String> brandCastLines(String biblePath) {
        List<String> out = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode bible =
                    new com.fasterxml.jackson.dataformat.yaml.YAMLMapper()
                            .readTree(java.nio.file.Paths.get(biblePath).toFile());
            for (com.fasterxml.jackson.databind.JsonNode ch : bible.path("characters")) {
                com.fasterxml.jackson.databind.JsonNode dna = ch.path("dna");
                StringBuilder b = new StringBuilder(ch.path("name").asText(ch.path("id").asText("")))
                        .append(": ").append(dna.path("coreColor").asText("")).append(" chick wearing ")
                        .append(dna.path("accessory").asText(""));
                if (!dna.path("antiAccessory").asText("").isBlank())
                    b.append(" — must NOT wear ").append(dna.path("antiAccessory").asText(""));
                if (!dna.path("eyeColor").asText("").isBlank())
                    b.append(" | eye colour: ").append(dna.path("eyeColor").asText(""));
                if (!dna.path("silhouette").asText("").isBlank())
                    b.append(" | silhouette: ").append(dna.path("silhouette").asText(""));
                out.add(b.toString());
            }
        } catch (Exception ignore) { /* empty = QC passes, legacy behaviour */ }
        return out;
    }

    /** Vision-QC on a freshly generated brand still BEFORE Veo money is spent —
     *  the normal pipeline QC's every scene still, but the intro/outro rebuild
     *  path skipped it, which let a comb-less Mo slip into the brand intro.
     *  Regenerates up to {@code maxRegens} times; last attempt proceeds anyway
     *  (the human previews the result on the Brand page). */
    String qcStillOrRegen(UUID job, Map<String, Object> still, String firstPath, int maxRegens) {
        String path = firstPath;
        List<String> expected = brandCastLines(props.bible().path());
        for (int attempt = 0; attempt <= maxRegens; attempt++) {
            SceneImageQc.Result r = sceneImageQc.check(java.nio.file.Paths.get(path), expected);
            if (r.ok()) return path;
            log.warn("Brand still QC FAIL (attempt {}/{}): {}", attempt + 1, maxRegens + 1, r.issues());
            if (attempt == maxRegens) break;
            try {
                com.fasterxml.jackson.databind.JsonNode img =
                        imageClient.generate(job, List.of(still), "landscape");
                String np = img.path("scenes").path(0).path("imagePath").asText("");
                if (np.isBlank()) break;
                path = np;
            } catch (Exception e) {
                log.warn("Brand still regen failed: {}", e.getMessage());
                break;
            }
        }
        return path;
    }

    private volatile String status = "idle";
    private volatile boolean running = false;
    /** Path of the last good Veo chickens clip — lets us re-run ONLY the title
     *  assembly (cheap, no Veo) to iterate on the intro composite. Mirrored to
     *  {@link #CLIP_MARKER} so a free re-composite survives an orchestrator
     *  restart (the outro already did this; the intro losing its cache forced
     *  a paid Veo rebuild for what should be a free recomposite). */
    private volatile String lastClipPath;

    /** Disk marker in the shared /workdir volume — persists across restarts. */
    private static final java.nio.file.Path CLIP_MARKER =
            java.nio.file.Paths.get("/workdir/.last-intro-clip");

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

    private void rememberClip(String clip) {
        this.lastClipPath = clip;
        try { java.nio.file.Files.writeString(CLIP_MARKER, clip); }
        catch (Exception e) { log.warn("Could not persist intro clip marker: {}", e.toString()); }
    }

    public boolean hasClip() {
        String p = resolveClip();
        return p != null && java.nio.file.Files.exists(java.nio.file.Paths.get(p));
    }

    @Async("pipelineExecutor")
    public void rebuild() { rebuild(null); }

    /** @param model optional Veo model override from the UI (veo3_1_lite /
     *  veo3_1_fast / veo3_1); null/blank = bible routing for sceneType intro. */
    @Async("pipelineExecutor")
    public void rebuild(String model) {
        if (running) { log.info("Intro rebuild already running — ignoring"); return; }
        running = true;
        try {
            UUID job = UUID.randomUUID();

            status = "1/3 — generating start still…";
            log.info("Intro rebuild {}: still", job);
            Map<String, Object> still = new HashMap<>();
            still.put("seq", 1);
            still.put("visualDesc", STILL_DESC);
            still.put("characters", List.of("pip", "mo", "bo"));
            JsonNode img = imageClient.generate(job, List.of(still), "landscape");
            String stillPath = img.path("scenes").path(0).path("imagePath").asText("");
            if (stillPath.isBlank()) throw new IllegalStateException("image-service returned no still");
            // QC the still BEFORE the (paid) Veo step — comb, scarfs, eye colour.
            stillPath = qcStillOrRegen(job, still, stillPath, 2);

            status = "2/3 — Veo chickens clip (a few minutes)…";
            log.info("Intro rebuild {}: veo clip from {}", job, stillPath);
            Map<String, Object> scene = new HashMap<>();
            scene.put("seq", 1);
            scene.put("sceneType", "intro");
            scene.put("startImagePath", stillPath);
            scene.put("visualDesc", MOTION_DESC);
            scene.put("negativePrompt", IDENTITY_NEG);
            scene.put("durationSeconds", 8);
            if (model != null && !model.isBlank()) scene.put("modelOverride", model.trim());
            JsonNode clips = videoGenClient.generate(job, "landscape", List.of(scene));
            JsonNode c0 = clips.path("clips").path(0);
            if (!"OK".equalsIgnoreCase(c0.path("status").asText()) || c0.path("clipPath").asText("").isBlank()) {
                throw new IllegalStateException("Veo clip not OK: " + c0.path("status").asText()
                        + " " + c0.path("error").asText(""));
            }
            String clip = c0.path("clipPath").asText();
            rememberClip(clip);   // remember (memory + disk) for cheap re-composites

            // Synthesize the self-intros in the SAME voices used in the episodes,
            // so the chicks speak with branded ElevenLabs voices instead of Veo's
            // off-brand synthetic audio. Best-effort: empty list = keep Veo audio.
            List<String> voiceLines = synthVoiceLines(voiceClient, job, INTRO_LINES);

            status = "3/3 — compositing title + sound…";
            log.info("Intro rebuild {}: composite {} ({} branded voices)", job, clip, voiceLines.size());
            assemblyClient.buildIntro(clip, voiceLines);

            status = "done — intro.mp4 refreshed at " + LocalTime.now().withNano(0);
            log.info("Intro rebuild {} done", job);
        } catch (Exception e) {
            status = "error — " + e.getMessage();
            log.warn("Intro rebuild failed: {}", e.toString());
        } finally {
            running = false;
        }
    }

    /**
     * Re-run ONLY the title + SFX assembly on the last Veo clip — no image,
     * no Veo. Cheap way to iterate on the intro composite (e.g. after a title
     * styling change) without paying for a fresh Veo render. Requires a previous
     * full rebuild so a clip exists.
     */
    @Async("pipelineExecutor")
    public void recomposite() {
        if (running) { log.info("Intro busy — ignoring re-composite"); return; }
        if (!hasClip()) {
            status = "error — no cached intro clip yet; do a full rebuild first";
            return;
        }
        running = true;
        try {
            UUID job = UUID.randomUUID();
            status = "re-compositing title + sound (no Veo)…";
            String clip = resolveClip();
            log.info("Intro re-composite from cached clip {}", clip);
            List<String> voiceLines = synthVoiceLines(voiceClient, job, INTRO_LINES);
            assemblyClient.buildIntro(clip, voiceLines);
            status = "done — intro.mp4 re-composited at " + LocalTime.now().withNano(0);
        } catch (Exception e) {
            status = "error — " + e.getMessage();
            log.warn("Intro re-composite failed: {}", e.toString());
        } finally {
            running = false;
        }
    }

    /**
     * Synthesize a short ordered set of one-line utterances via the voice-service
     * (one scene per line, so each comes back as its own MP3) and return the
     * audio paths IN THE SAME ORDER as {@code lines}. Shared by intro + outro.
     * Best-effort: any failure returns an empty list so the caller falls back to
     * the clip's own audio rather than breaking the rebuild.
     *
     * @param lines ordered [{speaker, text}] maps; speaker = character id (pip/mo/bo).
     */
    static List<String> synthVoiceLines(VoiceServiceClient voiceClient, UUID job,
                                        List<Map<String, String>> lines) {
        try {
            List<Map<String, Object>> scenes = new ArrayList<>();
            for (int i = 0; i < lines.size(); i++) {
                scenes.add(Map.of("seq", i + 1, "lines", List.of(lines.get(i))));
            }
            JsonNode resp = voiceClient.synthesize(job, scenes);
            Map<Integer, String> bySeq = new HashMap<>();
            for (JsonNode s : resp.path("scenes")) {
                String p = s.path("audioPath").asText("");
                if (!p.isBlank()) bySeq.put(s.path("seq").asInt(), p);
            }
            List<String> ordered = new ArrayList<>();
            for (int i = 1; i <= lines.size(); i++) {
                String p = bySeq.get(i);
                if (p != null) ordered.add(p);
            }
            return ordered;
        } catch (Exception e) {
            log.warn("intro/outro voice synth failed ({}) — falling back to clip audio", e.toString());
            return List.of();
        }
    }
}
