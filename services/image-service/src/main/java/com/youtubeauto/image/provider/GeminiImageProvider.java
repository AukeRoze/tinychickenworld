package com.youtubeauto.image.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.service.PromptComposer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reference-conditioned image generation via Gemini 2.5 Flash Image
 * ("Nano Banana"). Each scene's characters are supplied as REFERENCE IMAGES
 * (the hero anchors in {@code bible/refs/{id}.png}) so identity + accessories
 * come from real pixels — solving the combined-LoRA collapse where three
 * similar baby chicks averaged into one and lost their hats / glasses / scarves.
 *
 * Flow: POST /v1beta/models/{model}:generateContent with one inline_data part
 * per character anchor + a text part, then read the returned inline image bytes.
 */
@Slf4j
@Component
public class GeminiImageProvider implements ImageProvider {

    private final WebClient client;
    private final BibleLoader bibleLoader;
    private final PromptComposer prompts;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String apiKey;
    private final String model;
    private final String refsDir;
    private final int maxAttempts;

    /** Max reference images to load from a character's multi-angle set (Story G).
     *  Capped so a big folder can't balloon the request payload. */
    private static final int MAX_ANGLES = 3;

    /** Max episode-canon stills attached per scene (Story B) — one per
     *  in-scene character is the normal case; the standard cast is 3. */
    private static final int MAX_EPISODE_ANCHORS = 3;

    /** Max STYLE anchors (original-design references whose composition must be
     *  recreated — scene.styleAnchors, additive). One is the normal case (the
     *  current overlay logo); two is already generous, more would just dilute
     *  the character conditioning. Counted in the MAX_TOTAL_REFS budget. */
    private static final int MAX_STYLE_ANCHORS = 2;

    /** Hard cap on the TOTAL reference images per request. 9 = the legacy
     *  worst case (3 characters × MAX_ANGLES), so without episode/prop refs
     *  nothing changes; with them, extra bible ANGLES are displaced first
     *  (each character always keeps ≥1 anchor) — more refs than this dilutes
     *  Gemini's conditioning and balloons the payload. */
    private static final int MAX_TOTAL_REFS = 9;

    public GeminiImageProvider(
            BibleLoader bibleLoader,
            PromptComposer prompts,
            @Value("${app.gemini.api-key:}") String apiKey,
            @Value("${app.gemini.base-url:https://generativelanguage.googleapis.com}") String baseUrl,
            @Value("${app.gemini.model:gemini-2.5-flash-image}") String model,
            @Value("${app.gemini.refs-dir:/bible/refs}") String refsDir,
            @Value("${app.gemini.max-attempts:4}") int maxAttempts
    ) {
        this.bibleLoader = bibleLoader;
        this.prompts = prompts;
        this.apiKey = apiKey;
        this.model = model;
        this.refsDir = refsDir;
        this.maxAttempts = maxAttempts;
        this.client = WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader("Content-Type", "application/json")
                .codecs(c -> c.defaultCodecs().maxInMemorySize(50 * 1024 * 1024))
                .build();
    }

    @Override
    public String name() { return "gemini"; }

    @Override
    public byte[] generatePng(SceneVisual scene, String format, long seed) {
        return render(scene, format, seed, false, List.of());
    }

    @Override
    public byte[] generateThumbnailPng(SceneVisual scene, String format, long seed) {
        return render(scene, format, seed, true, List.of());
    }

    @Override
    public byte[] generatePng(SceneVisual scene, String format, long seed,
                              List<Path> episodeAnchors) {
        return render(scene, format, seed, false, episodeAnchors);
    }

    @Override
    public byte[] generateThumbnailPng(SceneVisual scene, String format, long seed,
                                       List<Path> episodeAnchors) {
        return render(scene, format, seed, true, episodeAnchors);
    }

    /** Shared render path. {@code thumbnail} selects the CTR close-up prompt
     *  ({@link PromptComposer#composeThumbnail}) over the full scene prompt
     *  ({@link PromptComposer#composeReference}); anchor resolution, aspect
     *  handling and retries are identical so the thumbnail uses the SAME
     *  reference anchors as the film. */
    private byte[] render(SceneVisual scene, String format, long seed, boolean thumbnail,
                          List<Path> episodeAnchors) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("GEMINI_API_KEY not set — cannot use gemini provider");
        }
        var bible = bibleLoader.getBible();

        // Resolve in-scene characters → per-character anchor SETS (each ≤
        // MAX_ANGLES), preserving scene order. Sets are flattened after the
        // total-ref budget below is known, so episode/prop refs can displace
        // EXTRA bible angles without ever dropping a character's hero anchor.
        List<String> charIds = new ArrayList<>();
        List<List<byte[]>> charSets = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        if (scene.characters() != null) {
            for (String charId : scene.characters()) {
                String displayName = bible.character(charId)
                        .map(com.youtubeauto.image.bible.Character::name)
                        .orElse(charId);
                List<byte[]> charAnchors = loadCharacterAnchors(charId);
                if (!charAnchors.isEmpty()) {
                    charIds.add(charId);
                    charSets.add(charAnchors);
                } else {
                    log.warn("gemini: no anchor for character '{}' in {} — relying on text", charId, refsDir);
                    missing.add(displayName);
                }
            }
        }

        // Recurring-prop anchors — appended AFTER the character anchors so they are
        // the trailing reference images. Locks a prop's colour/design across scenes.
        List<String> propNames = new ArrayList<>();
        List<byte[]> propImgs = new ArrayList<>();
        if (scene.propRefs() != null) {
            for (var p : scene.propRefs()) {
                if (p == null || p.imagePath() == null || p.imagePath().isBlank()) continue;
                Path pp = Paths.get(p.imagePath());
                if (Files.isReadable(pp)) {
                    try { propImgs.add(Files.readAllBytes(pp)); propNames.add(p.name()); }
                    catch (Exception e) { log.warn("gemini: failed reading prop ref {} — {}", pp, e.toString()); }
                }
            }
        }

        // ConsistencyState (Story B): stills already rendered for THIS episode,
        // appended as the FINAL reference images. They lock how this episode
        // has drawn the cast, so scene 14's Pip matches scene 1's Pip — not
        // just the bible's Pip. Two sources, explicit one wins:
        //   1. NAMED anchors on the request (scene.episodeAnchors) — the
        //      orchestrator's per-character, QC-approved canon election;
        //   2. the service's generic disk-scan stills (episodeAnchors param) as
        //      the fallback when the request carries none.
        List<String> epNames = new ArrayList<>();   // stays empty for the generic source
        List<Boolean> epSeries = new ArrayList<>(); // parallel to epNames: anchor promoted
                                                    // from a PREVIOUS episode of the series
        List<byte[]> epImgs = new ArrayList<>();
        if (scene.episodeAnchors() != null && !scene.episodeAnchors().isEmpty()) {
            for (var ea : scene.episodeAnchors()) {
                if (ea == null || ea.imagePath() == null || ea.imagePath().isBlank()) continue;
                if (epImgs.size() >= MAX_EPISODE_ANCHORS) break;
                Path pp = Paths.get(ea.imagePath());
                if (!Files.isReadable(pp)) continue;
                try {
                    epImgs.add(Files.readAllBytes(pp));
                    epNames.add(bible.character(ea.characterId())
                            .map(com.youtubeauto.image.bible.Character::name)
                            .orElse(ea.characterId() == null ? "the cast" : ea.characterId()));
                    epSeries.add(ea.fromSeries());
                } catch (Exception e) {
                    log.warn("gemini: failed reading episode anchor {} — {}", pp, e.toString());
                }
            }
        } else if (episodeAnchors != null) {
            for (Path ep : episodeAnchors) {
                if (ep == null || !Files.isReadable(ep)) continue;
                if (epImgs.size() >= MAX_EPISODE_ANCHORS) break;
                try { epImgs.add(Files.readAllBytes(ep)); }
                catch (Exception e) { log.warn("gemini: failed reading episode anchor {} — {}", ep, e.toString()); }
            }
        }
        boolean namedEpisode = !epNames.isEmpty();

        // STYLE anchors (additive — scene.styleAnchors): images of the ORIGINAL
        // design to recreate (e.g. the current overlay logo when regenerating it
        // with a rebranded cast). Appended as the very LAST reference images so
        // the prompt can point at them by number. Null/absent field = no-op.
        List<byte[]> styleImgs = new ArrayList<>();
        if (scene.styleAnchors() != null) {
            for (String sp : scene.styleAnchors()) {
                if (sp == null || sp.isBlank()) continue;
                if (styleImgs.size() >= MAX_STYLE_ANCHORS) break;
                Path pp = Paths.get(sp);
                if (!Files.isReadable(pp)) continue;
                try { styleImgs.add(Files.readAllBytes(pp)); }
                catch (Exception e) { log.warn("gemini: failed reading style anchor {} — {}", pp, e.toString()); }
            }
        }

        // Total-ref budget (MAX_TOTAL_REFS): props + episode + style anchors are
        // kept, and the per-character ANGLE count shrinks to fit — i.e. an extra
        // anchor displaces an extra bible angle, never a character's primary
        // anchor (minimum 1 per character) and never the extra anchors themselves.
        // With the standard 3-character cast and no extra refs this resolves to
        // 3 angles per character — exactly the legacy behaviour.
        int budget = MAX_TOTAL_REFS - propImgs.size() - epImgs.size() - styleImgs.size();
        int perChar = charSets.isEmpty() ? 0
                : Math.max(1, Math.min(MAX_ANGLES, budget / Math.max(1, charSets.size())));

        // Flatten: one orderedIds entry PER image so the prompt's
        // "Reference image N is X" lines stay aligned — a multi-angle
        // set just yields several lines naming the same character.
        List<String> orderedIds = new ArrayList<>();
        List<byte[]> anchors = new ArrayList<>();
        for (int i = 0; i < charSets.size(); i++) {
            List<byte[]> set = charSets.get(i);
            int take = Math.min(perChar, set.size());
            for (int k = 0; k < take; k++) {
                anchors.add(set.get(k));
                orderedIds.add(charIds.get(i));
            }
        }
        // When a character contributed more than one reference image (a multi-angle
        // set), tell the model those are the SAME character from different angles.
        boolean multiAngle = orderedIds.size() > new java.util.HashSet<>(orderedIds).size();
        anchors.addAll(propImgs);
        anchors.addAll(epImgs);
        anchors.addAll(styleImgs);

        int charCount = orderedIds.size();
        int episodeCount = epImgs.size();
        int propStart = charCount + 1;
        int propEnd = charCount + propNames.size();
        int epStart = propEnd + 1;
        int epEnd = propEnd + episodeCount;
        int styleStart = epEnd + 1;

        String prompt = thumbnail
                ? prompts.composeThumbnail(scene, orderedIds, format)
                : prompts.composeReference(scene, orderedIds, format);
        // Characters without an anchor still need a textual identity so they
        // aren't dropped from the scene.
        if (!missing.isEmpty()) {
            prompt = prompt + " Also include " + String.join(", ", missing)
                    + " (match the channel's established design). ";
        }
        if (!propNames.isEmpty()) {
            prompt = prompt + " Reference image(s) " + propStart + "-" + propEnd
                    + " show recurring PROPS (" + String.join(", ", propNames) + "): render each "
                    + "of these props in EXACTLY the colour, material and design shown in its "
                    + "reference image — keep them identical to the reference and consistent "
                    + "across every scene. Only show a prop if the scene text mentions it. ";
        }
        if (namedEpisode) {
            // Source-aware phrasing (series anchors): an anchor promoted from a
            // PREVIOUS episode of the series must not be sold to the model as a
            // still "from earlier in this exact episode" — that's false on the
            // first batch of a new episode. The orchestrator never mixes sources
            // in one request (own episode canon wins outright), so allSeries is
            // effectively "this is a series-seeded first batch".
            boolean allSeries = !epSeries.isEmpty() && epSeries.stream().allMatch(Boolean::booleanValue);
            StringBuilder ep = new StringBuilder();
            ep.append(" Reference image(s) ").append(epStart).append("-").append(epEnd)
              .append(allSeries
                  ? " are APPROVED STILLS FROM THE PREVIOUS EPISODE OF THIS EXACT SERIES. "
                  : " are QC-APPROVED STILLS FROM EARLIER IN THIS EXACT EPISODE. ");
            for (int i = 0; i < epNames.size(); i++) {
                boolean fromSeries = i < epSeries.size() && epSeries.get(i);
                ep.append("Reference image ").append(epStart + i).append(" shows ")
                  .append(epNames.get(i))
                  .append(fromSeries
                      ? " — the SAME individual character in the previous episode of this exact series. "
                      : " — the SAME individual character earlier in this exact episode. ");
            }
            ep.append(allSeries
                    ? "Match each character EXACTLY as in these series stills: identical "
                    + "feather colours and markings, identical accessories and accessory "
                    + "state, identical proportions and relative size — the look this "
                    + "series has already established overrides any other interpretation "
                    + "of the design. Do NOT copy the lighting, background or pose from "
                    + "the series stills: light and stage the character for THIS scene's "
                    + "own time of day, weather and action. "
                    : "Match each character EXACTLY as in these episode stills: identical "
                    + "feather colours and markings, identical accessories and accessory "
                    + "state, identical proportions and relative size — this episode's "
                    + "already-rendered look overrides any other interpretation of the "
                    + "design. Do NOT copy the lighting, background or pose from the episode "
                    + "stills: light and stage the character for THIS scene's own time of "
                    + "day, weather and action. ");
            prompt = prompt + ep;
        } else if (episodeCount > 0) {
            prompt = prompt + " Reference image(s) " + epStart + "-" + epEnd
                    + " are APPROVED STILLS FROM THIS SAME EPISODE, showing the cast as already "
                    + "rendered. Keep every character PIXEL-CONSISTENT with how they appear in "
                    + "those stills — identical feather colours, accessories, eye colour, comb, "
                    + "proportions and relative sizes between characters. The episode stills "
                    + "define this episode's look; do NOT redesign or restyle anything, only the "
                    + "scene, pose and camera change. ";
        }
        // Style anchors are the LAST reference images, so their numbering starts
        // right after the episode block regardless of which extras are present.
        // Aangescherpt (2026-06-12): de zachte "only the characters change"
        // liet het model de OUDE kippen uit het design-beeld kopiëren i.p.v.
        // ze te vervangen door de character-refs. Nu expliciet: design-beeld
        // = ALLEEN layout/bord/letters; de personages erin zijn VEROUDERD.
        for (int i = 0; i < styleImgs.size(); i++) {
            prompt = prompt + " Reference image " + (styleStart + i)
                    + " shows the ORIGINAL design ONLY for its composition, layout, "
                    + "board, lettering and proportions — copy those exactly. WARNING: "
                    + "the characters drawn inside that original design are OUTDATED "
                    + "and must NOT be copied, traced or echoed in any way. Erase them "
                    + "from your mind and REPAINT every character from scratch to match "
                    + "ONLY the character reference images (the current designs): their "
                    + "exact body shapes, sizes, proportions and details win over "
                    + "anything the old design shows. ";
        }
        if (multiAngle) {
            prompt = prompt + " Where several reference images name the SAME character, "
                    + "they are different angles/expressions of that one character — treat them "
                    + "as one identity and keep colours, accessories and proportions identical. ";
        }
        log.debug("gemini scene {} format={} refs={} prompt: {}",
                scene.seq(), format, orderedIds, prompt);

        String aspect = "vertical".equalsIgnoreCase(format) ? "9:16" : "16:9";

        // First attempt with imageConfig.aspectRatio; if the API rejects that
        // field (preview models vary), retry without it.
        byte[] png = call(anchors, prompt, aspect, true);
        if (png != null) return png;
        png = call(anchors, prompt, aspect, false);
        if (png == null) {
            throw new IllegalStateException("Gemini returned no image for scene " + scene.seq());
        }
        return png;
    }

    /**
     * Loads a character's reference anchors (Story G). Prefers a MULTI-ANGLE set
     * at {@code bible/refs/{id}/*.png} (front / 3-4 / side / expressions) so the
     * model locks identity from several views; falls back to the single hero
     * anchor {@code bible/refs/{id}.png} (current behaviour) when no folder
     * exists. Fully additive — with no folder, behaviour is unchanged.
     */
    private List<byte[]> loadCharacterAnchors(String charId) {
        List<byte[]> out = new ArrayList<>();
        Path dir = Paths.get(refsDir, charId);
        if (Files.isDirectory(dir)) {
            try (var stream = Files.list(dir)) {
                List<Path> pngs = stream
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".png"))
                        .sorted()
                        .limit(MAX_ANGLES)
                        .toList();
                for (Path p : pngs) {
                    try { out.add(Files.readAllBytes(p)); }
                    catch (Exception e) { log.warn("gemini: failed reading angle {} — {}", p, e.toString()); }
                }
            } catch (Exception e) {
                log.warn("gemini: failed listing multi-angle dir {} — {}", dir, e.toString());
            }
            if (!out.isEmpty()) {
                log.debug("gemini: {} multi-angle anchors for '{}'", out.size(), charId);
                return out;
            }
        }
        Path single = Paths.get(refsDir, charId + ".png");
        if (Files.isReadable(single)) {
            try { out.add(Files.readAllBytes(single)); }
            catch (Exception e) { log.warn("gemini: failed reading anchor {} — {}", single, e.toString()); }
        }
        return out;
    }

    /** One full retry loop. Returns null only if the aspectRatio field was the
     *  problem (so the caller can retry without it); throws on other failures. */
    private byte[] call(List<byte[]> anchors, String prompt, String aspect, boolean withAspect) {
        ObjectNode body = mapper.createObjectNode();
        ArrayNode contents = body.putArray("contents");
        ObjectNode content = contents.addObject();
        ArrayNode parts = content.putArray("parts");
        for (byte[] img : anchors) {
            ObjectNode p = parts.addObject();
            ObjectNode inline = p.putObject("inline_data");
            inline.put("mime_type", "image/png");
            inline.put("data", Base64.getEncoder().encodeToString(img));
        }
        parts.addObject().put("text", prompt);

        ObjectNode genCfg = body.putObject("generationConfig");
        ArrayNode mods = genCfg.putArray("responseModalities");
        mods.add("IMAGE");
        mods.add("TEXT");
        if (withAspect) {
            genCfg.putObject("imageConfig").put("aspectRatio", aspect);
        }

        String uri = "/v1beta/models/" + model + ":generateContent?key=" + apiKey;

        long delayMs = 3_000;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                JsonNode resp = client.post().uri(uri)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(JsonNode.class)
                        .block(Duration.ofMinutes(2));
                byte[] img = extractImage(resp);
                if (img != null) return img;
                log.warn("gemini: no image part in response (attempt {}/{})", attempt, maxAttempts);
            } catch (WebClientResponseException e) {
                String resp = e.getResponseBodyAsString();
                // aspectRatio / imageConfig unsupported → signal caller to retry without it.
                if (withAspect && e.getStatusCode().value() == 400
                        && (resp.contains("imageConfig") || resp.contains("aspect")
                            || resp.contains("aspectRatio"))) {
                    log.warn("gemini: imageConfig.aspectRatio rejected, will retry without it");
                    return null;
                }
                // Rate limit / transient server errors → backoff + retry.
                int code = e.getStatusCode().value();
                boolean transientErr = code == 429 || code == 503 || code == 500;
                log.error("gemini {} response: {}", code, resp);
                if (!transientErr || attempt == maxAttempts) throw e;
            } catch (Exception e) {
                log.warn("gemini error (attempt {}/{}): {}", attempt, maxAttempts, e.toString());
                if (attempt == maxAttempts) throw new IllegalStateException("Gemini call failed", e);
            }
            try { TimeUnit.MILLISECONDS.sleep(delayMs); }
            catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted during Gemini backoff", ie);
            }
            delayMs = Math.min(delayMs * 2, 30_000);
        }
        throw new IllegalStateException("Gemini exhausted retries");
    }

    /** Pull the first inline image's bytes from a generateContent response. */
    private byte[] extractImage(JsonNode resp) {
        if (resp == null) return null;
        for (JsonNode cand : resp.path("candidates")) {
            for (JsonNode part : cand.path("content").path("parts")) {
                JsonNode inline = part.has("inlineData") ? part.path("inlineData")
                        : part.path("inline_data");
                String data = inline.path("data").asText("");
                if (!data.isBlank()) {
                    try { return Base64.getDecoder().decode(data); }
                    catch (IllegalArgumentException e) {
                        log.warn("gemini: bad base64 in response: {}", e.toString());
                    }
                }
            }
        }
        return null;
    }
}
