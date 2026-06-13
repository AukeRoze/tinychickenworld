package com.youtubeauto.image.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.bible.ImageGenConfig;
import com.youtubeauto.image.service.PromptComposer;
import io.github.resilience4j.retry.annotation.Retry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Calls a Replicate Flux model with the trained cast LoRA. Trigger words in
 * the prompt activate the LoRA's character weights; consistency is near-perfect
 * once the LoRA is properly trained.
 *
 * Flow: POST /v1/predictions → poll GET /v1/predictions/{id} until status
 * is "succeeded" or "failed" → download PNG bytes from output URL.
 */
@Slf4j
@Component
public class ReplicateImageProvider implements ImageProvider {

    private static final String API = "https://api.replicate.com/v1";

    private final WebClient client;
    private final BibleLoader bibleLoader;
    private final PromptComposer prompts;
    private final ObjectMapper mapper = new ObjectMapper();

    public ReplicateImageProvider(
            BibleLoader bibleLoader,
            PromptComposer prompts,
            @Value("${app.replicate.api-token:}") String apiToken,
            @Value("${app.replicate.poll-interval-ms:2500}") long pollInterval,
            @Value("${app.replicate.max-poll-attempts:120}") int maxPollAttempts
    ) {
        this.bibleLoader = bibleLoader;
        this.prompts = prompts;
        this.pollIntervalMs = pollInterval;
        this.maxPollAttempts = maxPollAttempts;
        this.client = WebClient.builder()
                .baseUrl(API)
                .defaultHeader("Authorization", "Token " + apiToken)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    private final long pollIntervalMs;
    private final int maxPollAttempts;

    @Override
    public String name() { return "replicate"; }

    @Override
    @Retry(name = "replicate")
    public byte[] generatePng(SceneVisual scene, String format, long seed) {
        ImageGenConfig.Replicate cfg = bibleLoader.getBible().imageGen().replicate();
        String prompt = PromptComposer.withCorrection(prompts.composeTrigger(scene), scene);
        log.debug("replicate scene {} format={} prompt: {}", scene.seq(), format, prompt);

        String version = extractVersion(cfg.model());

        // Flux likes dimensions divisible by 16. Vertical shorts use 768x1344.
        boolean vertical = "vertical".equalsIgnoreCase(format);
        int w = vertical ? 768  : cfg.width();
        int h = vertical ? 1344 : cfg.height();

        ObjectNode body = mapper.createObjectNode();
        if (version != null) body.put("version", version);

        ObjectNode input = body.putObject("input");
        input.put("prompt", prompt);
        // Push Flux away from generic chicken and aggressively suppress
        // duplicates + accessory swaps. Repetition matters — Flux weights
        // negative terms by frequency. Put the most common failures multiple
        // times.
        input.put("negative_prompt",
                // Anti-duplicate (REPEAT — this is the biggest fail mode):
                "duplicate chickens, two identical chickens, multiple identical chickens, "
                + "two pip_chicken, two mo_chicken, two bo_chicken, "
                + "twin chickens, twin pip, twin mo, twin bo, "
                + "cloned chicken, repeated chicken, mirrored chicken, "
                + "same chicken appearing twice, copy of same chicken, "
                + "extra chickens, crowd of chickens, flock, group of identical birds, "
                // Accessory swaps (banner-matched cast — Pip cream-white + straw
                // hat + red bandana, Mo blue-grey + red scarf, Bo tan + round
                // glasses + green scarf):
                + "pip_chicken without straw hat, pip_chicken without hat, "
                + "pip_chicken wearing glasses, pip_chicken with blue-grey feathers, "
                + "mo_chicken without scarf, mo_chicken wearing a hat, "
                + "mo_chicken wearing glasses, mo_chicken with cream feathers, "
                + "bo_chicken without glasses, bo_chicken without green scarf, "
                + "bo_chicken wearing a hat, bo_chicken wearing a bandana, "
                + "bo_chicken with thick glasses, bo_chicken with black-framed glasses, "
                + "pip_chicken wearing a scarf, mo_chicken wearing a bandana, "
                + "swapped accessories, character wearing another character's accessory, "
                // Style-A artefacts to suppress (push toward Pixar 3D, away from
                // watercolor / felt / stop-motion / pastel storybook):
                + "flat watercolor style, watercolor texture, watercolor wash, "
                + "soft brush strokes, paper texture, painted illustration, "
                + "storybook illustration, picture-book style, "
                + "felt fabric texture, felted wool, knitted texture, "
                + "stop motion, claymation, miniature diorama, toy photograph, "
                + "flat diffuse lighting, washed out colors, pale pastel palette, "
                + "muted earth tones only, beige overall tone, "
                + "small eyes, beady eyes, simple dot eyes, plain eyes, dull eyes, "
                + "lifeless eyes, eyes without highlights, "
                + "sparse background, empty backdrop, plain empty space, "
                + "characters too small in frame, background-dominant composition, "
                + "tiny silhouettes far away, "
                // Phantom clothing — Mo's "white chest feathers" was reading as
                // a white shirt. Pip+Bo do wear scarves but NEVER shirts or coats.
                + "wearing shirt, wearing t-shirt, wearing white shirt, "
                + "wearing clothing, wearing clothes, wearing coat, wearing jacket, "
                + "wearing apron, dressed in clothing, "
                + "mo_chicken wearing shirt, mo_chicken wearing clothing, "
                + "chicken in clothing, anthropomorphic clothed chicken, "
                + "white t-shirt, white shirt covering body, "
                // Stronger anti-duplicate (Flux still slips two chickens into "
                // "wide 16:9 frames for solo scenes):
                + "two chickens, three chickens, four chickens, multiple birds, "
                + "second chicken visible, another chicken behind, partial chicken, "
                + "reflection of chicken, shadow of another chick, "
                // Generic style failures:
                + "generic chicken, realistic photo, photograph, photorealism, "
                // Style break the critic flagged — photo-real fur + DOF/bokeh:
                + "photorealistic fur, realistic fur texture, individual fur strands, "
                + "hyper-detailed fur, shallow depth of field, bokeh background, "
                + "macro photography, "
                // Anatomy — cat-ear / mammal-ear tufts the critic flagged:
                + "cat ears, rabbit ears, mammal ears, pointed ear tufts, animal ears, "
                // Anatomy — human hands/fingers (critic flagged Pip with 4 fingers):
                + "human hands, hands, fingers, four fingers, thumbs, fingered hands, "
                + "humanlike hands, holding with fingers, "
                // Framing — cropped heads / eyes off-screen the critic flagged:
                + "cropped head, head cut off, top of head cut off, face cut off, "
                + "eye out of frame, subject too small, "
                + "different breed, wrong colour, missing accessories, "
                // Body-proportion drift — keep the plump baby-chick shape, never thin.
                + "thin chicken, skinny chicken, slim chicken, elongated body, "
                + "stretched body, lanky chicken, adult hen, deformed proportions, "
                + "deformed beak, deformed eyes, extra limbs, extra wings, "
                + "blurry, distorted, ugly, low quality, watermark, text, "
                // Comic sound-effect words leaking in from onomatopoeia dialogue:
                + "comic sound effect text, onomatopoeia text, BONK, POW, BOING, "
                + "WHOOSH, PLOP, speech bubble, caption, letters, words, numbers, "
                + "subtitle, signature, "
                // Focal balance — character shoved aside / props dominating:
                + "subject in the far corner, character off to one side, large empty "
                + "dead space, background prop dominating, oversized cart, oversized fence"
                // Per-scene character exclusion: forces LoRA toward the right
                // trigger embedding when one character is dominating. Builds
                // "NOT [trigger], NOT [distinctive features]" for every cast
                // member NOT in this scene.
                + buildAntiCastNegative(scene));
        input.put("width", w);
        input.put("height", h);
        input.put("num_inference_steps", cfg.numInferenceSteps());
        input.put("guidance_scale", cfg.guidanceScale());
        input.put("output_format", "png");
        input.put("output_quality", 95);

        // Auto-detect model type:
        //   lucataco/flux-dev-multi-lora        → "hub" mode, attach LoRA via hf_loras + lora_scales arrays
        //   ${user}/tiny-chicken-world-cast-v2  → "trained model" mode, LoRA is baked in, use single lora_scale
        // Trained models reject hf_loras/lora_scales with 422 because they
        // don't accept those input fields.
        boolean multiLoraHub = cfg.model() != null && cfg.model().toLowerCase().contains("multi-lora");
        if (multiLoraHub) {
            input.putArray("hf_loras").add(cfg.castLoraUrl());
            input.putArray("lora_scales").add(cfg.castLoraScale());
        } else {
            // Trained model — LoRA is part of the model already. Single
            // lora_scale knob adjusts how strongly it expresses.
            input.put("lora_scale", cfg.castLoraScale());
        }
        // Per-scene seed: derive from per-video seed + scene.seq() so each
        // scene has its OWN composition while style/palette stays consistent
        // (consistency now comes from visualStyle + LoRA, not seed-reuse).
        // Using same seed across scenes was producing near-identical poses.
        long sceneSeed = seed * 1_000_003L + scene.seq();
        input.put("seed", (int) (Math.abs(sceneSeed) % Integer.MAX_VALUE));

        JsonNode created = post("/predictions", body);
        String id = created.path("id").asText();
        if (id.isEmpty()) {
            throw new IllegalStateException("Replicate response missing id: " + created);
        }

        JsonNode done = poll(id);
        JsonNode out = done.path("output");
        String url = out.isArray() && out.size() > 0 ? out.get(0).asText() : out.asText();
        if (url == null || url.isBlank()) {
            throw new IllegalStateException("Replicate succeeded but output missing: " + done);
        }
        log.debug("replicate prediction {} -> {}", id, url);
        return downloadBinary(url);
    }

    private JsonNode post(String path, JsonNode body) {
        // Retry on 429 (rate limit) with exponential backoff. Replicate gives
        // tight per-account limits on prediction creation, especially for new
        // PAYG accounts. 5 attempts spaced at 5/10/20/40s usually clears it.
        int attempt = 0;
        long delayMs = 5_000;
        while (true) {
            try {
                return client.post().uri(path)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block();
            } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
                // 4xx (excluding 429) and 5xx other than 429: log the response
                // body BEFORE rethrowing so we can see Replicate's diagnostic
                // JSON ("detail: ..."). Without this the exception just says
                // "422 Unprocessable Entity" with no clue what was wrong.
                if (!(e instanceof org.springframework.web.reactive.function.client.WebClientResponseException.TooManyRequests)) {
                    log.error("Replicate {} response: body={}\nrequest_body={}",
                            e.getStatusCode(), e.getResponseBodyAsString(), body);
                    throw e;
                }
                attempt++;
                if (attempt >= 5) {
                    log.warn("Replicate 429 after {} retries, giving up", attempt);
                    throw e;
                }
                log.warn("Replicate 429, backing off {} ms (attempt {}/5)", delayMs, attempt);
                try { TimeUnit.MILLISECONDS.sleep(delayMs); }
                catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted during 429 backoff", ie);
                }
                delayMs = Math.min(delayMs * 2, 60_000);
            }
        }
    }

    private JsonNode poll(String id) {
        for (int i = 0; i < maxPollAttempts; i++) {
            JsonNode r = client.get().uri("/predictions/{id}", id)
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (r == null) throw new IllegalStateException("Empty poll response");
            String status = r.path("status").asText();
            if ("succeeded".equals(status)) return r;
            if ("failed".equals(status) || "canceled".equals(status)) {
                throw new IllegalStateException("Replicate prediction " + id + " " + status
                        + ": " + r.path("error").asText());
            }
            try { TimeUnit.MILLISECONDS.sleep(pollIntervalMs); }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while polling Replicate", e);
            }
        }
        throw new IllegalStateException("Replicate prediction " + id + " timed out");
    }

    private byte[] downloadBinary(String url) {
        // Default WebClient buffer is 256 KB — too small for multi-MB PNGs.
        // Bump to 50 MB so any reasonable Flux output downloads cleanly.
        WebClient downloader = WebClient.builder()
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
        return downloader.get().uri(url)
                .retrieve()
                .bodyToMono(byte[].class)
                .block(Duration.ofMinutes(2));
    }

    /**
     * Builds character-exclusion fragment for the negative prompt. For every
     * cast member not in this scene we add ", trigger_word, distinctive
     * features" so the LoRA is forced away from those embeddings. Particularly
     * effective when one character (Mo) dominates and other triggers are
     * being ignored.
     */
    private String buildAntiCastNegative(SceneVisual scene) {
        if (scene.characters() == null || scene.characters().isEmpty()) return "";
        var bible = bibleLoader.getBible();
        if (bible.characters() == null || bible.characters().isEmpty()) return "";
        java.util.Set<String> inScene = new java.util.HashSet<>(scene.characters());
        StringBuilder sb = new StringBuilder();
        for (var ch : bible.characters()) {
            if (inScene.contains(ch.id())) continue;
            // Trigger word — primary force-away signal.
            if (ch.triggerWord() != null && !ch.triggerWord().isBlank()) {
                sb.append(", ").append(ch.triggerWord());
            }
            // Distinctive features now come from the bible character DNA (single
            // source of truth) instead of a hardcoded switch — push the model
            // away from the ABSENT character's core colour + accessory so it
            // doesn't leak into the present cast.
            var dna = ch.dna();
            if (dna != null) {
                if (dna.hasCoreColor()) {
                    sb.append(", ").append(dna.coreColor()).append(" fluffy chick");
                }
                if (dna.hasAccessory()) {
                    sb.append(", chick wearing ").append(dna.accessory());
                }
            }
        }
        return sb.toString();
    }

    /** Accept either "owner/name:version" or "owner/name" (latest). */
    private String extractVersion(String model) {
        if (model == null) return null;
        int colon = model.indexOf(':');
        return colon < 0 ? null : model.substring(colon + 1);
    }
}
