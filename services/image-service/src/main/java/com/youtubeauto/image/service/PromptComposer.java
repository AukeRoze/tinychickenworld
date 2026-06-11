package com.youtubeauto.image.service;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.bible.ChannelBible;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.stream.Collectors;

/**
 * Two prompt modes:
 *
 *  describe — Verbose. Locked style + location + every character's full
 *             physical description prepended. Used when no LoRA is trained
 *             (provider = openai).
 *
 *  trigger  — Lean. Locked style + location + character TRIGGER WORDS that
 *             activate the trained LoRA's character weights. Used when
 *             provider = replicate. Far better consistency.
 */
@Component
@RequiredArgsConstructor
public class PromptComposer {

    private static final String TAIL =
            " Soft 3D Pixar / Illumination cartoon look — NOT photo-real, no realistic "
            + "fur texture, no depth-of-field blur, no cat-ear tufts. Keep both eyes and "
            + "the full head within the frame. "
            // Anatomy: the critic flagged Pip with 4-fingered human hands. Chicks
            // use wing-tips, never hands/fingers.
            + "The chicks are CHICKENS with little feathered WING-TIPS — NEVER human "
            + "hands, NEVER fingers, NEVER thumbs; arms are soft wings. "
            // Safe-area / composition: keep the subject off the edges and centered
            + "so the editor's cover-fit + slow zoom never crops anything important. "
            + "COMPOSITION: place the main subject CENTERED in the frame, away from all "
            + "four edges, with clear headroom above the head and breathing space below; "
            + "keep the WHOLE character — head, eyes, beak, accessories AND the legs and "
            + "feet — well inside the central 90% safe area, with a margin of empty space "
            + "on every side; do NOT crop the feet or the top of the head, do NOT jam the "
            + "subject against an edge or let any part run off the frame. The shot will get "
            + "a slow zoom-in afterward, so leave extra margin — frame a touch wider than "
            + "feels necessary. "
            // Focal balance: the character is the subject, not the scenery. The
            // critic flagged Pip shoved into a side third with a cart in the dead
            // space — keep the character centered and prominent.
            + "FOCUS: the main character is the clear focal point — centered and "
            + "filling a good portion of the frame, eyes near the upper-middle; do "
            + "NOT shove the character into a left or right third, do NOT leave a "
            + "large empty/dead area, and never let a background prop (cart, wheel, "
            + "fence, tree) dominate the composition or crowd the character aside. "
            // Prop consistency: props were drifting colour between scenes (a green
            // watering can turning grey). Honour the stated colour/material exactly.
            + "PROP COLOURS: render every named object or prop in EXACTLY the colour "
            + "and material stated in the scene text (a 'green metal watering can' is "
            + "green metal); do not recolour, restyle or reimagine props, and keep any "
            + "recurring object identical in colour and design across shots. "
            // Hard anti-text: comic sound-effect words (BONK, POW) and speech
            // bubbles kept leaking in because the dialogue uses onomatopoeia.
            + "ABSOLUTELY NO rendered text anywhere in the image: no letters, no "
            + "words, no numbers, no comic-style sound-effect text (such as BONK, "
            + "POW, BOING, WHOOSH, PLOP), no speech bubbles, no captions, no "
            + "subtitles, no watermarks, no logos, no signatures.";

    // Thumbnail composition tail — replaces the full-body scene TAIL for
    // thumbnails. Same 3D look + anti-text lock, but a CTR close-up: the
    // character fills the frame with a big expressive face instead of being
    // kept whole and centered with margins. The identity still comes from the
    // attached reference anchors, so the thumbnail chick IS the film chick.
    private static final String THUMBNAIL_TAIL =
            " THUMBNAIL COMPOSITION (designed for maximum click-through, NOT a wide "
            + "scene): push the character(s) RIGHT UP CLOSE to the camera so the "
            + "face / faces fill 60-80% of the frame. Oversized eyes wide open with "
            + "big round pupils and bright shine highlights, beak open mid-gasp, "
            + "SHARP focus on the eyes and face, strong emotional reaction. Keep a "
            + "CLEAN, simple, softly-blurred background with minimal distractions so "
            + "the character pops; big bold shapes, vivid saturated colours, "
            + "high-contrast cinematic lighting with a strong rim light. Leave the "
            + "top third OR bottom third relatively empty for a title overlay. Must "
            + "read clearly at small phone size. Soft 3D Pixar / Illumination "
            + "cartoon look — NOT photo-real, NOT painterly, NOT 2D flat or "
            + "storybook. The chicks are CHICKENS with feathered WING-TIPS — never "
            + "human hands, fingers or thumbs. Each chick keeps newly-hatched "
            + "baby-chick proportions with an oversized head — never a generic or "
            + "adult chicken. ABSOLUTELY NO rendered text, letters, words, numbers, "
            + "sound-effect text, speech bubbles, captions, watermarks or logos.";

    private final BibleLoader bibleLoader;

    public String composeDescribe(SceneVisual scene) {
        ChannelBible bible = bibleLoader.getBible();
        StringBuilder sb = new StringBuilder();

        if (!bible.visualStyle().isBlank()) sb.append(bible.visualStyle()).append(' ');

        if (scene.locationId() != null && !scene.locationId().isBlank()) {
            bible.location(scene.locationId()).ifPresent(loc ->
                    sb.append("SETTING — ").append(loc.name()).append(": ")
                      .append(loc.description()).append(' '));
        }

        if (scene.characters() != null) {
            for (String charId : scene.characters()) {
                bible.character(charId).ifPresent(ch -> {
                    sb.append("CHARACTER — ").append(ch.name()).append(": ");
                    // lifeStage carries the baby-chick proportions ("newly
                    // hatched chick, oversized head…"). Without it gpt-image-1
                    // drifts to adult-hen proportions.
                    if (ch.lifeStage() != null && !ch.lifeStage().isBlank()) {
                        sb.append(ch.lifeStage()).append(", ");
                    }
                    sb.append(ch.description()).append(' ');
                });
            }
            // Hard consistency reminder — gpt-image-1 takes no negative prompt,
            // so the accessory/proportion lock has to be stated positively and
            // emphatically right before the scene action.
            if (!scene.characters().isEmpty()) {
                sb.append("CONSISTENCY RULE — Each character above MUST keep the exact ")
                  .append("signature accessories and colours from their description ")
                  .append("(never drop, swap, recolour or hide the scarf, straw hat, ")
                  .append("bandana or eyeglasses), and MUST keep newly-hatched ")
                  .append("baby-chick proportions with an oversized head and small ")
                  .append("body — never draw them as adult hens. ");
            }
        }

        sb.append("SCENE — ").append(scene.visualDesc().trim()).append(TAIL);
        return sb.toString();
    }

    public String composeTrigger(SceneVisual scene) {
        ChannelBible bible = bibleLoader.getBible();
        StringBuilder sb = new StringBuilder();

        // Trigger words FIRST. Flux weights tokens by position — the first
        // ~75 tokens dominate the attention. Leading with triggers gives
        // the LoRA's character weights the strongest possible activation,
        // restoring character lock when castLoraScale is below ~1.0.
        if (scene.characters() != null && !scene.characters().isEmpty()) {
            for (String charId : scene.characters()) {
                bible.character(charId).ifPresent(ch -> {
                    if (!ch.triggerWord().isBlank()) {
                        sb.append(ch.triggerWord()).append(", ");
                    }
                });
            }
        }

        if (!bible.visualStyle().isBlank()) sb.append(bible.visualStyle()).append(' ');

        // World context wrapper — sets the felt sense of place before the
        // specific location. Brief but evocative.
        if (bible.worldOverview() != null && !bible.worldOverview().isBlank()) {
            sb.append("WORLD: ").append(bible.worldOverview()).append(' ');
        }

        if (scene.locationId() != null && !scene.locationId().isBlank()) {
            bible.location(scene.locationId()).ifPresent(loc ->
                    sb.append("In ").append(loc.name()).append(", ")
                      .append(loc.description()).append(". "));
        }

        // Time-of-day mood (set by script-service per scene, defaults to
        // goldenHour — the channel's signature).
        String tod = scene.timeOfDay() != null && !scene.timeOfDay().isBlank()
                ? scene.timeOfDay() : "goldenHour";
        bible.timeOfDay(tod).ifPresent(t ->
                sb.append("LIGHTING — ").append(t.description()).append(' '));

        // Weather overlay if specified (rarely per-scene; usually per-video).
        if (scene.weather() != null && !scene.weather().isBlank()) {
            bible.weather(scene.weather()).ifPresent(w ->
                    sb.append("WEATHER — ").append(w.description()).append(' '));
        }

        // Camera framing — explicit instruction per scene phase. Rotates
        // through wide/medium/closeup/over-shoulder/low-angle to avoid
        // the visual monotony that kills retention.
        if (scene.cameraFraming() != null && !scene.cameraFraming().isBlank()) {
            sb.append("CAMERA — ").append(scene.cameraFraming()).append(' ');
        }

        // Hard count guard: aggressive language because Flux ignores soft counts.
        // "Alone in the frame" for solo scenes is the single strongest anti-dup
        // signal that actually works empirically. Repeated, capitalized, with
        // explicit "1" digit (Flux notices numerals more than spelled-out words).
        if (scene.characters() != null && !scene.characters().isEmpty()) {
            int n = scene.characters().size();
            if (n == 1) {
                sb.append("SOLO COMPOSITION: exactly 1 chicken in the ENTIRE frame. ")
                  .append("Only 1 chick. Just 1 chick. A single chick alone. ")
                  .append("ABSOLUTELY NO second chick anywhere in the image — no ")
                  .append("twin, no double, no clone, no copy, no reflection of ")
                  .append("another chick, no shadow of another chick, no silhouette ")
                  .append("of another chick in the background. ")
                  .append("Total chicken count in this image: 1. ");
            } else {
                sb.append(String.format(
                    "GROUP COMPOSITION: exactly %d chickens total in the frame, ", n));
                sb.append("each chicken is unique and DIFFERENT from the others, ");
                sb.append("no two chickens look the same, no duplicates of any character, ");
            }
        }

        // Per character: trigger word + "exactly one of him/her" + description.
        // The explicit "exactly one of" per character is what stops Flux from
        // doubling up the same trigger word.
        if (scene.characters() != null && !scene.characters().isEmpty()) {
            for (String charId : scene.characters()) {
                bible.character(charId).ifPresent(ch -> {
                    if (!ch.triggerWord().isBlank()) {
                        sb.append("Exactly one ").append(ch.triggerWord())
                          .append(" (only one of this character, never duplicated). ");
                    }
                    StringBuilder desc = new StringBuilder();
                    if (!ch.lifeStage().isBlank()) desc.append(ch.lifeStage()).append(", ");
                    if (!ch.description().isBlank()) desc.append(ch.description().trim());
                    if (desc.length() > 0) {
                        sb.append("(").append(ch.name()).append(" is ")
                          .append(desc.toString().trim()).append(") ");
                    }
                });
            }
        }

        sb.append(scene.visualDesc().trim()).append(TAIL);
        return sb.toString();
    }

    /**
     * reference — for the Gemini provider. The character identities come from
     * attached reference images (the hero anchors), NOT from text. The prompt
     * binds each attached image to a character by ordinal, hard-locks the
     * accessories, then layers the usual scene/world/lighting/camera context.
     *
     * @param scene      scene visual data
     * @param orderedIds character ids in the SAME order the provider attaches
     *                   the reference images
     * @param format     "landscape" or "vertical" — drives the aspect hint
     */
    public String composeReference(SceneVisual scene, java.util.List<String> orderedIds,
                                   String format) {
        ChannelBible bible = bibleLoader.getBible();
        StringBuilder sb = new StringBuilder();

        // 1) Reference binding FIRST — this is the whole point of this mode.
        if (orderedIds != null && !orderedIds.isEmpty()) {
            sb.append("Use the attached reference image(s) as the EXACT, fixed character "
                    + "design. ");
            for (int i = 0; i < orderedIds.size(); i++) {
                String id = orderedIds.get(i);
                String nm = bible.character(id).map(c -> c.name()).orElse(id);
                sb.append("Reference image ").append(i + 1).append(" is ")
                  .append(nm).append(". ");
            }
            sb.append("Reproduce each referenced character's exact feather colours, body "
                    + "shape, oversized head, extra-large shiny eyes and ALL signature "
                    + "accessories (straw hat, bandana, scarf, eyeglasses) precisely as "
                    + "shown in its reference. Never add an accessory a character does not "
                    + "have, never remove one it does have, and never swap accessories "
                    + "between characters. ");

            // Per-character DNA (bible characters[].dna) — the canonical iconic
            // identity. Forces the small, high-frequency details the model drops
            // (Pip's hat, Bo's glasses) AND the recognisable silhouette into
            // every frame. Single source of truth: edit the bible, not code.
            StringBuilder dnaBlock = new StringBuilder();
            for (String id : orderedIds) {
                String line = dnaLine(id);
                if (!line.isBlank()) dnaBlock.append(line).append(' ');
            }
            if (dnaBlock.length() > 0) {
                sb.append("CHARACTER DNA (each must be clearly visible and correct on the "
                        + "right character): ").append(dnaBlock);
            }

            int n = orderedIds.size();
            if (n == 1) {
                sb.append("Exactly ONE chick in the whole image — no second chick, no twin, "
                        + "no clone, no reflection. ");
            } else {
                sb.append(String.format("Exactly %d chicks total, one per referenced "
                        + "character, each clearly DIFFERENT and recognisable, never "
                        + "duplicated. ", n));
            }
        }

        // 2) Style + world context.
        if (!bible.visualStyle().isBlank()) sb.append(bible.visualStyle()).append(' ');
        if (bible.worldOverview() != null && !bible.worldOverview().isBlank()) {
            sb.append("WORLD: ").append(bible.worldOverview()).append(' ');
        }
        if (scene.locationId() != null && !scene.locationId().isBlank()) {
            bible.location(scene.locationId()).ifPresent(loc ->
                    sb.append("In ").append(loc.name()).append(", ")
                      .append(loc.description()).append(". "));
        }
        String tod = scene.timeOfDay() != null && !scene.timeOfDay().isBlank()
                ? scene.timeOfDay() : "goldenHour";
        bible.timeOfDay(tod).ifPresent(t ->
                sb.append("LIGHTING — ").append(t.description()).append(' '));
        if (scene.weather() != null && !scene.weather().isBlank()) {
            bible.weather(scene.weather()).ifPresent(w ->
                    sb.append("WEATHER — ").append(w.description()).append(' '));
        }
        if (scene.cameraFraming() != null && !scene.cameraFraming().isBlank()) {
            sb.append("CAMERA — ").append(scene.cameraFraming()).append(' ');
        }

        // 3) Scene action + aspect + style tail.
        sb.append("SCENE — ").append(scene.visualDesc().trim()).append(' ');
        boolean vertical = "vertical".equalsIgnoreCase(format);
        sb.append(vertical
                ? "Vertical 9:16 full-bleed composition. "
                : "Wide 16:9 cinematic full-bleed composition. ");
        sb.append(TAIL);
        return sb.toString();
    }

    /**
     * thumbnail — same reference-anchor identity lock as {@link #composeReference}
     * but with a CTR close-up composition ({@link #THUMBNAIL_TAIL}) instead of the
     * full-body scene TAIL. The chicks are therefore the EXACT same characters as
     * the film (identity comes from the attached anchors) while filling the frame
     * with a big expressive face. {@code scene.visualDesc()} carries the
     * topic / hook / per-variant mood supplied by the thumbnail-service.
     */
    public String composeThumbnail(SceneVisual scene, java.util.List<String> orderedIds,
                                   String format) {
        ChannelBible bible = bibleLoader.getBible();
        StringBuilder sb = new StringBuilder();

        // Identity lock from the attached reference anchors (same as scenes).
        if (orderedIds != null && !orderedIds.isEmpty()) {
            sb.append("Use the attached reference image(s) as the EXACT, fixed character "
                    + "design. ");
            for (int i = 0; i < orderedIds.size(); i++) {
                String id = orderedIds.get(i);
                String nm = bible.character(id).map(c -> c.name()).orElse(id);
                sb.append("Reference image ").append(i + 1).append(" is ")
                  .append(nm).append(". ");
            }
            sb.append("Reproduce each referenced character's exact feather colours, body "
                    + "shape, oversized head, extra-large shiny eyes and ALL signature "
                    + "accessories (straw hat, bandana, scarf, eyeglasses) precisely as "
                    + "shown in its reference. Never add an accessory a character does not "
                    + "have, never remove one it does have, and never swap accessories "
                    + "between characters. ");

            StringBuilder dnaBlock = new StringBuilder();
            for (String id : orderedIds) {
                String line = dnaLine(id);
                if (!line.isBlank()) dnaBlock.append(line).append(' ');
            }
            if (dnaBlock.length() > 0) {
                sb.append("CHARACTER DNA (each must be clearly visible and correct on the "
                        + "right character): ").append(dnaBlock);
            }

            int n = orderedIds.size();
            if (n == 1) {
                sb.append("Exactly ONE chick in the whole image — no second chick, no twin, "
                        + "no clone, no reflection. ");
            } else {
                sb.append(String.format("Exactly %d chicks total, one per referenced "
                        + "character, each clearly DIFFERENT and recognisable, never "
                        + "duplicated. ", n));
            }
        }

        if (!bible.visualStyle().isBlank()) sb.append(bible.visualStyle()).append(' ');
        sb.append("SUBJECT — ").append(scene.visualDesc().trim()).append(' ');
        boolean vertical = "vertical".equalsIgnoreCase(format);
        sb.append(vertical
                ? "Vertical 9:16 full-bleed composition. "
                : "Wide 16:9 full-bleed composition. ");
        sb.append(THUMBNAIL_TAIL);
        return sb.toString();
    }

    /**
     * Builds the bible-driven DNA clause for one character: name + core colour,
     * its mandatory accessory, recognisable silhouette, and the extended identity
     * details (feathers, build, eyes). Returns "" if the character or its DNA is
     * unknown. Single source of truth = bible characters[].dna.
     *
     * CONTRACT: the orchestrator's Veo compiler (PipelineOrchestrator
     * #characterDnaClauses) locks the SAME DNA fields so the still and its Veo
     * animation never disagree. When a DNA field is added to the bible, inject it
     * in BOTH places. ({@code weight} is a motion cue → Veo-only, omitted here.)
     */
    private String dnaLine(String id) {
        var chOpt = bibleLoader.getBible().character(id);
        if (chOpt.isEmpty()) return "";
        var ch = chOpt.get();
        var dna = ch.dna();
        if (dna == null) return "";
        StringBuilder b = new StringBuilder();
        b.append(ch.name());
        if (dna.hasCoreColor()) b.append(" (").append(dna.coreColor()).append(')');
        b.append(": ");
        if (dna.hasAccessory()) {
            b.append("ALWAYS wears ").append(dna.accessory())
             .append(" — clearly visible, never dropped or swapped. ");
        }
        if (dna.hasSilhouette()) {
            b.append("Silhouette: ").append(dna.silhouette()).append(". ");
        }
        // Extended identity details — feather texture, body build and eye colour
        // keep the character on-model across DIFFERENT shots without an identical
        // reference still. Weight is a motion cue, so it's reserved for the Veo
        // compiler, not the still image.
        if (dna.hasFeathers()) {
            b.append("Feathers: ").append(dna.feathers()).append(". ");
        }
        if (dna.hasBuild()) {
            b.append("Build: ").append(dna.build()).append(". ");
        }
        if (dna.hasEyeColor()) {
            b.append("Eyes: ").append(dna.eyeColor()).append(". ");
        }
        // Signature mannerism (the DNA tic) as a POSE hint so the character's
        // quirk reads even in a still — Bo mid glasses-push, Pip tipping her hat,
        // Mo a slow thoughtful look. Only when it fits the scene's action.
        if (dna.hasTic()) {
            b.append(ch.name()).append("'s signature mannerism — when it fits the "
                    + "scene, show ").append(ch.name()).append(" mid-gesture: ")
             .append(dna.tic()).append(". ");
        }
        // Hard anti-swap lock — the exact accessories this character must NEVER
        // wear (so Pip never inherits Bo's glasses, Mo never a bandana, etc.).
        if (dna.hasAntiAccessory()) {
            b.append(ch.name()).append(" must NEVER wear ").append(dna.antiAccessory()).append(". ");
        }
        return b.toString().trim();
    }
}
