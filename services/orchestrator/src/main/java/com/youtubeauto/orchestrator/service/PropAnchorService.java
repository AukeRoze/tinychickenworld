package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.client.ImageServiceClient;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Per-episode PROP ANCHORS — locks recurring props (a watering can, a ball, a
 * basket) to one colour/design across scenes, the way the cast anchors lock the
 * chickens. Two steps, both best-effort:
 *   1. Claude reads the scene descriptions and lists the props that appear in
 *      2+ scenes, each with a canonical description + a keyword.
 *   2. We render ONE clean reference image per prop (isolated, neutral bg) via
 *      the image-service, under a throwaway job id so it never collides with the
 *      real scene images.
 * The orchestrator then attaches the matching prop refs to each scene's image
 * request; the Gemini provider conditions on them.
 *
 * Flag-gated (app.props.anchors-enabled, default false) and fully graceful: any
 * failure or the flag off → empty list → scenes fall back to the text-only
 * prop-continuity rules. So this can never break image generation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PropAnchorService {

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final ImageServiceClient imageClient;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${app.props.anchors-enabled:false}")
    private boolean enabled;

    public boolean isEnabled() { return enabled; }

    /** A recurring prop + its rendered reference-anchor path. */
    public record Prop(String name, String keyword, String description, String anchorPath) {}

    private static final String TOOL = "emit_props";
    private static final String SCHEMA = """
            {"type":"object","additionalProperties":false,"required":["props"],
             "properties":{"props":{"type":"array","maxItems":6,"items":{
               "type":"object","additionalProperties":false,
               "required":["name","keyword","description"],
               "properties":{
                 "name":{"type":"string","maxLength":40},
                 "keyword":{"type":"string","maxLength":24,
                   "description":"one lowercase word that appears in the scene text for this prop, e.g. 'watering' or 'kite'"},
                 "description":{"type":"string","maxLength":160,
                   "description":"canonical look: exact colour + material + shape"}
               }}}}}
            """;
    private static final String SYSTEM = """
            You lock visual continuity for a kids cartoon. You are given the
            visual descriptions of every scene in one episode. List the PHYSICAL
            PROPS / OBJECTS that appear in TWO OR MORE scenes (things a viewer
            would notice changing colour: a watering can, a ball, a basket, a
            kite, a decorated egg). For each, give a short lowercase keyword that
            literally occurs in the scene text, and one canonical description
            fixing its exact colour, material and shape. Ignore the chickens
            themselves and generic scenery (grass, sky, hills). If nothing
            recurs, return an empty list. Always emit via emit_props.
            """;

    /**
     * Best-effort: extract recurring props from the scene descriptions and render
     * one anchor each. Returns [] when disabled or on any failure.
     */
    public List<Prop> buildAnchors(UUID jobId, List<String> visualDescs, String imageFormat) {
        if (!enabled || visualDescs == null || visualDescs.size() < 2) return List.of();
        try {
            List<Prop> extracted = extract(visualDescs);
            List<Prop> out = new ArrayList<>();
            for (Prop p : extracted) {
                if (p.keyword() == null || p.keyword().isBlank()) continue;
                try {
                    String path = renderAnchor(p, imageFormat);
                    if (path != null && !path.isBlank()) {
                        out.add(new Prop(p.name(), p.keyword().toLowerCase().trim(), p.description(), path));
                        log.info("Job {} prop anchor '{}' -> {}", jobId, p.name(), path);
                    }
                } catch (Exception e) {
                    log.warn("Job {} prop-anchor render failed for '{}': {}", jobId, p.name(), e.getMessage());
                }
            }
            return out;
        } catch (Exception e) {
            log.warn("Job {} prop extraction failed ({}) — props rely on text only", jobId, e.getMessage());
            return List.of();
        }
    }

    private List<Prop> extract(List<String> visualDescs) {
        StringBuilder scenes = new StringBuilder();
        for (int i = 0; i < visualDescs.size(); i++) {
            scenes.append("Scene ").append(i + 1).append(": ").append(visualDescs.get(i)).append('\n');
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("model", props.anthropic().model());
        body.put("max_tokens", 1200);
        body.put("system", SYSTEM);
        ArrayNode messages = body.putArray("messages");
        messages.addObject().put("role", "user")
                .put("content", "Scene descriptions:\n" + scenes + "\nList the recurring props via emit_props.");
        ArrayNode tools = body.putArray("tools");
        ObjectNode tool = tools.addObject();
        tool.put("name", TOOL);
        tool.put("description", "Emit the recurring props.");
        try { tool.set("input_schema", mapper.readTree(SCHEMA)); }
        catch (Exception e) { throw new IllegalStateException(e); }
        body.putObject("tool_choice").put("type", "tool").put("name", TOOL);

        JsonNode resp = anthropicWebClient.post().uri("/messages").bodyValue(body)
                .retrieve().bodyToMono(JsonNode.class).block();
        List<Prop> out = new ArrayList<>();
        if (resp == null) return out;
        for (JsonNode block : resp.path("content")) {
            if ("tool_use".equals(block.path("type").asText())) {
                for (JsonNode p : block.path("input").path("props")) {
                    out.add(new Prop(p.path("name").asText(), p.path("keyword").asText(),
                            p.path("description").asText(), null));
                }
            }
        }
        return out;
    }

    /**
     * Episode-ConsistencyState (Story B) — pure character-anchor election.
     *
     * <p>Picks, per character, the best ALREADY-RENDERED scene still of THIS
     * episode to act as that character's episode anchor: the exemplar every
     * later re-roll must visually match (the episode becomes its own canon, on
     * top of the bible refs). Selection criteria, in order:
     * <ol>
     *   <li>fewest characters in the scene — least occlusion, the character is
     *       biggest and clearest in frame;</li>
     *   <li>hero phase (hook/climax) preferred on a tie — those beats get the
     *       most generation care;</li>
     *   <li>lowest seq on a remaining tie — deterministic.</li>
     * </ol>
     *
     * <p>Pure and static on purpose: no I/O of its own (file existence comes in
     * as a predicate), so it unit-tests without Spring/Mockito and is immune to
     * this service being mocked. Scenes without an image, or whose image file
     * the predicate rejects (e.g. deleted from disk), never become an anchor —
     * with nothing usable the result is empty and callers keep legacy behaviour.
     *
     * @param scenes      the job's assembly scenes (post vision-QC, so the
     *                    stills are the approved ones)
     * @param imageExists existence check for an image path (injected for
     *                    testability; null-safe — a null predicate accepts all)
     * @return characterId (lowercased) → scene-still path; empty when nothing qualifies
     */
    public static Map<String, String> selectEpisodeAnchors(
            List<com.youtubeauto.orchestrator.domain.SceneDto> scenes,
            java.util.function.Predicate<String> imageExists) {
        Map<String, String> out = new java.util.TreeMap<>();
        if (scenes == null || scenes.isEmpty()) return out;
        record Best(int castSize, boolean hero, int seq, String path) {}
        Map<String, Best> best = new HashMap<>();
        for (com.youtubeauto.orchestrator.domain.SceneDto s : scenes) {
            if (s == null || !s.hasImage()) continue;
            String path = s.getImagePath();
            try {
                if (imageExists != null && !imageExists.test(path)) continue;
            } catch (Exception e) {
                continue; // a failing existence check just disqualifies the still
            }
            List<String> cast = s.charactersOrEmpty();
            if (cast.isEmpty()) continue;
            int castSize = cast.size();
            String phase = s.getPhase() == null ? "" : s.getPhase().toLowerCase();
            boolean hero = phase.equals("hook") || phase.equals("climax");
            int seq = s.getSeq() == null ? Integer.MAX_VALUE : s.getSeq();
            for (String c : cast) {
                if (c == null || c.isBlank()) continue;
                String id = c.trim().toLowerCase();
                Best cur = best.get(id);
                boolean better = cur == null
                        || castSize < cur.castSize()
                        || (castSize == cur.castSize() && hero && !cur.hero())
                        || (castSize == cur.castSize() && hero == cur.hero() && seq < cur.seq());
                if (better) best.put(id, new Best(castSize, hero, seq, path));
            }
        }
        best.forEach((id, b) -> out.put(id, b.path()));
        return out;
    }

    /** Render one isolated prop reference image under a throwaway job id. */
    private String renderAnchor(Prop p, String imageFormat) {
        Map<String, Object> scene = new HashMap<>();
        scene.put("seq", 1);
        scene.put("visualDesc",
                "A single " + p.description() + " — ONE object only, centered on a plain soft "
                + "neutral studio background, no characters, no chickens, no extra objects, clean "
                + "model-sheet / product reference style, soft 3D Pixar cartoon look, even lighting.");
        scene.put("characters", List.of());
        UUID anchorJob = UUID.randomUUID();   // separate id → never overwrites a real scene image
        JsonNode resp = imageClient.generate(anchorJob, List.of(scene), imageFormat);
        return resp.path("scenes").path(0).path("imagePath").asText(null);
    }
}
