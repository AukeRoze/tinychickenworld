package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Automated per-scene image QC. Before montage, a single scene still is shown to
 * Claude vision with the characters it SHOULD contain (name + colour + signature
 * accessory from the bible DNA). Claude returns a pass/fail + concrete issues so
 * the orchestrator can auto-reroll weak images instead of relying on a human to
 * spot them — and so we never spend Veo money on a broken still.
 *
 * Fail-safe: any error (missing image, API hiccup) returns a PASS so QC never
 * blocks the pipeline; the human review gate remains the backstop.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SceneImageQc {

    private static final String TOOL = "emit_scene_qc";
    private static final String SCHEMA = """
            {
              "type":"object","additionalProperties":false,
              "required":["ok","issues"],
              "properties":{
                "ok":{"type":"boolean","description":"true if the image is good enough to use as-is"},
                "issues":{"type":"array","maxItems":6,"items":{"type":"string","maxLength":160}}
              }
            }
            """;

    private static final String SYSTEM = """
            You are a strict QC checker for a kids cartoon's individual scene
            images (ages 3-6). You may first be shown OFFICIAL REFERENCE images —
            the approved canonical look of each character; judge the scene image
            AGAINST those references, not against your own idea of the
            characters. Then you are shown ONE scene image plus the list of
            characters it should contain with their signature accessories.

            Set ok=false ONLY for clear, fixable problems:
              - a listed character is MISSING its signature accessory
                (straw hat / bandana / scarf / round eyeglasses),
              - a character WEARS AN ACCESSORY IT MUST NOT WEAR (the "must NOT
                wear" list) — e.g. a character that belongs to someone else got
                that accessory (glasses on the wrong chick, a hat on the wrong
                chick, a bandana on the wrong chick). This accessory-SWAP is a
                hard fail; kids notice it.
              - a listed character is ENTIRELY ABSENT from the image (one of the
                named characters that should appear is simply not there),
              - a character has the WRONG colour vs the description,
              - CHARACTER DRIFT vs the listed DNA (these subtle drifts break the
                "same character every episode" illusion and ARE hard fails):
                  * wrong EYE COLOUR — the iris hue clearly differs from the
                    listed eye colour (e.g. blue/grey irises when the DNA says
                    warm brown). Catch-light highlights are fine; hue swaps not.
                  * the SILHOUETTE head detail is missing or replaced (e.g. the
                    listed comb/hat/tuft on top of the head is absent or swapped
                    for a different head shape),
                  * RELATIVE SIZE drift — a character is rendered clearly bigger
                    or smaller than its listed size note relative to the other
                    chicks in frame (e.g. a "slightly smaller, slimmer" chick
                    towering over the others),
                  * added FEMININE EYELASHES on a character whose DNA does not
                    list eyelashes, or missing them where the DNA lists them.
                When unsure on a drift call, lean towards ok=true — only flag
                drift a child would notice when watching two scenes back-to-back.
              - a chick has HUMAN-LIKE HANDS or FINGERS (thumbs, four/five
                fingers, a palm) anywhere instead of stubby feathered wing-tips —
                chicks have WINGS, never hands. This anatomy break is a hard fail;
                kids notice it instantly.
              - the main subject is CUT OFF at a frame edge (feet or head out of
                frame) or jammed hard against the edge,
              - the SAME character is duplicated (two identical chicks),
              - the number of chickens clearly does not match the listed cast,
              - there is ANY rendered text in the image — letters, words, numbers,
                a comic sound-effect word (BONK, POW, BOING…), a speech bubble,
                a caption or a watermark. Kids content must be clean of stray text.

              - the image CONTRADICTS the listed scene weather/time of day —
                e.g. bright sunshine and dry ground when the scene lists rain,
                or broad daylight when the scene lists night. A gentle "sun
                shower" mix is fine; a hard contradiction is not.

            Set ok=true if the image is usable. Do NOT nitpick art style, pose or
            background. List concrete issues. Always emit via emit_scene_qc.
            """;

    private final WebClient anthropicWebClient;
    private final OrchestratorProperties props;
    private final CharacterRefStills refStills;
    private final ObjectMapper mapper = new ObjectMapper();

    public record Result(boolean ok, List<String> issues) {
        public static Result pass() { return new Result(true, List.of()); }
    }

    /**
     * @param image         path to the scene PNG
     * @param expectedChars lines like "Pip: cream-white chick wearing a straw
     *                      farmer hat and a red neck bandana"
     */
    /** Back-compat: QC without reference images. */
    public Result check(Path image, List<String> expectedChars) {
        return check(image, expectedChars, List.of());
    }

    /** @param charIds cast ids — their approved reference stills (bible/refs)
     *  are shown FIRST so drift is judged against canonical pixels. */
    public Result check(Path image, List<String> expectedChars, List<String> charIds) {
        try {
            if (image == null || !Files.exists(image)) return Result.pass();
            byte[] bytes = Files.readAllBytes(image);

            ObjectNode body = mapper.createObjectNode();
            body.put("model", props.anthropic().model());
            body.put("max_tokens", 500);
            body.put("system", SYSTEM);

            ArrayNode messages = body.putArray("messages");
            ObjectNode um = messages.addObject();
            um.put("role", "user");
            ArrayNode content = um.putArray("content");
            // Ground truth first: the approved canonical look per character.
            for (CharacterRefStills.RefStill ref : refStills.resolve(charIds)) {
                try {
                    content.addObject().put("type", "text").put("text",
                            "OFFICIAL REFERENCE — the approved canonical look of '"
                            + ref.characterId() + "':");
                    ObjectNode rimg = content.addObject();
                    rimg.put("type", "image");
                    ObjectNode rsrc = rimg.putObject("source");
                    rsrc.put("type", "base64");
                    rsrc.put("media_type", "image/png");
                    rsrc.put("data", Base64.getEncoder()
                            .encodeToString(Files.readAllBytes(ref.path())));
                } catch (Exception refErr) {
                    log.debug("Scene QC ref {} unreadable ({}) — skipping",
                            ref.path(), refErr.getMessage());
                }
            }
            ObjectNode img = content.addObject();
            img.put("type", "image");
            ObjectNode srcNode = img.putObject("source");
            srcNode.put("type", "base64");
            srcNode.put("media_type", "image/png");
            srcNode.put("data", Base64.getEncoder().encodeToString(bytes));
            content.addObject().put("type", "text").put("text",
                    "Characters that should appear in this single image (with their"
                    + " canonical DNA — eye colour, silhouette and size notes):\n"
                    + String.join("\n", expectedChars)
                    + "\n\nCheck accessories, colours, EYE COLOUR, silhouette head"
                    + " detail, relative sizes, framing and duplicates. emit_scene_qc.");

            ArrayNode tools = body.putArray("tools");
            ObjectNode tool = tools.addObject();
            tool.put("name", TOOL);
            tool.put("description", "Emit the scene QC verdict.");
            tool.set("input_schema", mapper.readTree(SCHEMA));
            ObjectNode tc = body.putObject("tool_choice");
            tc.put("type", "tool");
            tc.put("name", TOOL);

            JsonNode resp = anthropicWebClient.post()
                    .uri("/messages")
                    .bodyValue(body)
                    .retrieve().bodyToMono(JsonNode.class).block();
            if (resp == null) return Result.pass();
            for (JsonNode b : resp.path("content")) {
                if ("tool_use".equals(b.path("type").asText())) {
                    JsonNode in = b.path("input");
                    List<String> issues = new ArrayList<>();
                    in.path("issues").forEach(i -> issues.add(i.asText()));
                    return new Result(in.path("ok").asBoolean(true), issues);
                }
            }
            return Result.pass();
        } catch (Exception e) {
            log.warn("Scene QC failed (treating as pass): {}", e.getMessage());
            return Result.pass();
        }
    }
}
