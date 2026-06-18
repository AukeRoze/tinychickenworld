package com.youtubeauto.image.service;

import com.youtubeauto.image.api.dto.GenerateImageRequest.SceneVisual;
import com.youtubeauto.image.bible.BibleLoader;
import com.youtubeauto.image.bible.ChannelBible;
import com.youtubeauto.image.bible.Character;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
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

    /** Append a short, high-priority CORRECTION clause to a finished prompt when
     *  the scene carries a non-blank {@code correctionHint} (additive — null/blank
     *  → returns the prompt unchanged = current blind-re-roll behaviour). Kept at
     *  the END so terminal-token-weighting providers prioritise it. */
    public static String withCorrection(String prompt, SceneVisual scene) {
        if (scene == null || scene.correctionHint() == null
                || scene.correctionHint().isBlank()) {
            return prompt;
        }
        return prompt + " IMPORTANT CORRECTION — the previous render of this scene was "
                + "wrong; fix specifically: " + scene.correctionHint().trim()
                + ". Keep everything else as described.";
    }

    /** Neutralise anatomically-impossible HUMAN-HAND gestures that sometimes leak
     *  into the script's visualDesc (e.g. "a thumbs-up wing", "claps her hands",
     *  "high-five"). A chick has feathered WING-TIPS — no thumbs, fingers or
     *  hands — so these contradict the prompt's own anatomy rule (the TAIL) and
     *  push the model into a malformed hand-wing hybrid. Rewrite each into the
     *  wing equivalent BEFORE it reaches the prompt, so the whole prompt is
     *  internally consistent. Case-insensitive, word-bounded, ordered most-
     *  specific first; text without such a gesture is returned unchanged. */
    static String wingSafe(String desc) {
        if (desc == null || desc.isBlank()) return desc;
        String d = desc;
        // thumbs-up family first (incl. the literal "thumbs-up wing")
        d = d.replaceAll("(?i)\\b(?:a |an |two |both )?thumbs?[-\\s]?up(?:\\s+wing)?(?:\\s+gesture)?\\b",
                         "a cheerful raised wingtip");
        d = d.replaceAll("(?i)\\bhigh[-\\s]?fives?\\b", "a wing-bump");
        d = d.replaceAll("(?i)\\bclapping\\s+(?:her|his|their|its)?\\s*hands\\b", "clapping its wings");
        d = d.replaceAll("(?i)\\bclaps?\\s+(?:her|his|their|its)?\\s*hands\\b", "claps its wings");
        // leftover bare anatomy words
        d = d.replaceAll("(?i)\\bfingertips?\\b", "wingtips");
        d = d.replaceAll("(?i)\\bfingers?\\b", "wingtips");
        d = d.replaceAll("(?i)\\bthumbs\\b", "wingtips");
        d = d.replaceAll("(?i)\\bthumb\\b", "wingtip");
        d = d.replaceAll("(?i)\\bhands\\b", "wings");
        d = d.replaceAll("(?i)\\bhand\\b", "wing");
        return d;
    }

    /** Corrects an accessory-vs-action contradiction in the scene action BEFORE
     *  it is inserted into SCENE/SUBJECT — so an action that hands a uniquely-owned
     *  accessory to the wrong chick (Mo "adjusting his glasses" → his own "thick
     *  red knitted scarf") stops fighting the CHARACTER DNA never-wear lock in the
     *  same prompt (EP3 review, scene 5). Bible-driven; a clean action is returned
     *  unchanged. */
    private String accessorySafe(SceneVisual scene) {
        String desc = scene == null ? null : scene.visualDesc();
        if (desc == null || desc.isBlank()) return desc;
        ChannelBible bible = bibleLoader.getBible();
        if (bible == null || bible.characters() == null) return desc;
        List<AccessoryGuard.CharModel> models = new java.util.ArrayList<>();
        for (Character ch : bible.characters()) {
            var dna = ch.dna();
            String owned = dna == null ? "" : dna.accessory();
            String forbidden = dna == null ? "" : dna.antiAccessory();
            String shortAcc = dna == null ? "" : dna.signatureAccessoryShort();
            String gender = AccessoryGuard.inferGender(
                    dna == null ? "" : dna.tic(),
                    dna == null ? "" : dna.signatureSound(),
                    ch.description());
            models.add(new AccessoryGuard.CharModel(ch.id(), ch.name(), shortAcc,
                    AccessoryGuard.categoriesIn(owned),
                    AccessoryGuard.categoriesIn(forbidden), gender));
        }
        return AccessoryGuard.sanitize(desc, scene.characters(), models);
    }

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

        sb.append("SCENE — ").append(wingSafe(accessorySafe(scene)).trim()).append(TAIL);
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

        sb.append(wingSafe(accessorySafe(scene)).trim()).append(TAIL);
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
            // Max ÉÉN mannerism/tic per scène (gebruikersfeedback 2026-06-14): alleen
            // het EERSTE (focale) personage toont zijn tic; identiteitsvelden blijven
            // voor élk personage.
            java.util.List<String> absentNames = absentCastNames(orderedIds);
            StringBuilder dnaBlock = new StringBuilder();
            for (int i = 0; i < orderedIds.size(); i++) {
                String line = dnaLine(orderedIds.get(i), i == 0, absentNames);
                if (!line.isBlank()) dnaBlock.append(line).append(' ');
            }
            if (dnaBlock.length() > 0) {
                sb.append("CHARACTER DNA (each must be clearly visible and correct on the "
                        + "right character): ").append(dnaBlock);
            }

            sb.append(rosterCountSentence(orderedIds));
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
        sb.append("SCENE — ").append(wingSafe(accessorySafe(scene)).trim()).append(' ');
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

            // Max ÉÉN tic per scène (zie composeReference) — alleen het eerste
            // personage toont zijn mannerism; identiteit blijft voor iedereen.
            java.util.List<String> absentNames = absentCastNames(orderedIds);
            StringBuilder dnaBlock = new StringBuilder();
            for (int i = 0; i < orderedIds.size(); i++) {
                String line = dnaLine(orderedIds.get(i), i == 0, absentNames);
                if (!line.isBlank()) dnaBlock.append(line).append(' ');
            }
            if (dnaBlock.length() > 0) {
                sb.append("CHARACTER DNA (each must be clearly visible and correct on the "
                        + "right character): ").append(dnaBlock);
            }

            sb.append(rosterCountSentence(orderedIds));
        }

        if (!bible.visualStyle().isBlank()) sb.append(bible.visualStyle()).append(' ');
        sb.append("SUBJECT — ").append(wingSafe(scene.visualDesc()).trim()).append(' ');
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
    private String dnaLine(String id, boolean includeTic, java.util.Collection<String> absentNames) {
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
        // Prefer the cast-neutral structured field; fall back to the legacy proza.
        // scopeDnaText stays as a safety net — on a migrated (clean) field it finds
        // nothing to scrub, which is exactly the point of the migration.
        String silhouetteText = dna.hasSilhouetteShape() ? dna.silhouetteShape() : dna.silhouette();
        if (silhouetteText != null && !silhouetteText.isBlank()) {
            String s = scopeDnaText(silhouetteText, absentNames);
            if (!s.isBlank()) b.append("Silhouette: ").append(s).append(". ");
        }
        // Extended identity details — feather texture, body build and eye colour
        // keep the character on-model across DIFFERENT shots without an identical
        // reference still. Weight is a motion cue, so it's reserved for the Veo
        // compiler, not the still image.
        if (dna.hasFeathers()) {
            b.append("Feathers: ").append(dna.feathers()).append(". ");
        }
        String buildText = dna.hasBodyBuild() ? dna.bodyBuild() : dna.build();
        if (buildText != null && !buildText.isBlank()) {
            String s = scopeDnaText(buildText, absentNames);
            if (!s.isBlank()) b.append("Build: ").append(s).append(". ");
        }
        if (dna.hasEyeColor()) {
            b.append("Eyes: ").append(dna.eyeColor()).append(". ");
        }
        // Signature mannerism (the DNA tic) as a POSE hint so the character's
        // quirk reads even in a still — Bo mid glasses-push, Pip tipping her hat,
        // Mo a slow thoughtful look. Only when it fits the scene's action.
        if (dna.hasTic() && includeTic) {
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

    // ---- species-aware roster count + absent-cast scoping ----------------------

    /**
     * Species-aware roster count so a mixed cast is counted correctly: e.g.
     * "Exactly 2 chickens and 1 duckling total (3 characters maximum in frame),
     * each matching its own referenced species and design perfectly, never
     * duplicated." The old "Exactly 3 chicks total" lumped the duckling in with
     * the chickens, so Midjourney/Nano-Banana turned the duckling into a third
     * chicken (the EP3 scene-24/25 paradox). Counts use each character's bible
     * {@code rosterNoun}/{@code species} (default "chicken").
     */
    private String rosterCountSentence(java.util.List<String> orderedIds) {
        ChannelBible bible = bibleLoader.getBible();
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String id : orderedIds) {
            String noun = bible.character(id).map(Character::displayNoun).orElse("chicken");
            counts.merge(noun, 1, Integer::sum);
        }
        int total = orderedIds.size();
        if (total == 1) {
            String noun = counts.keySet().iterator().next();
            return "Exactly ONE " + noun + " in the whole image — no second " + noun
                    + ", no twin, no clone, no reflection. ";
        }
        java.util.List<String> parts = new java.util.ArrayList<>();
        for (var e : counts.entrySet()) {
            parts.add(e.getValue() + " " + (e.getValue() == 1 ? e.getKey() : e.getKey() + "s"));
        }
        return String.format("Exactly %s total (%d characters maximum in frame), each "
                + "matching its own referenced species and design perfectly, never "
                + "duplicated. ", joinAnd(parts), total);
    }

    private static String joinAnd(java.util.List<String> parts) {
        if (parts.isEmpty()) return "";
        if (parts.size() == 1) return parts.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) b.append(i == parts.size() - 1 ? " and " : ", ");
            b.append(parts.get(i));
        }
        return b.toString();
    }

    /** Display names of every bible character NOT present in {@code orderedIds} —
     *  the cast members whose comparative mentions must be scrubbed from the DNA so
     *  an absent character (e.g. Bo in a Pip+Mo+duckling scene) is never named. */
    private java.util.List<String> absentCastNames(java.util.List<String> orderedIds) {
        java.util.Set<String> present = new java.util.HashSet<>();
        for (String id : orderedIds) if (id != null) present.add(id.toLowerCase());
        java.util.List<String> absent = new java.util.ArrayList<>();
        for (Character c : bibleLoader.getBible().characters()) {
            if (c.id() != null && !present.contains(c.id().toLowerCase())
                    && c.name() != null && !c.name().isBlank()) {
                absent.add(c.name());
            }
        }
        return absent;
    }

    /**
     * Strips comparative fragments that NAME an absent cast member from a DNA
     * string, so a per-character DNA note never drags an off-screen character into
     * the frame (EP3 scene-24/25: the duckling's "about two-thirds of Bo's height"
     * and Pip's "(… and Bo is a vertical line)" made the model render Bo). Two
     * passes: (1) drop any parenthetical that mentions an absent name; (2) drop any
     * comma/semicolon/dash-delimited clause that mentions an absent name. A
     * character's OWN self-reference and any present cast member are left intact.
     */
    static String scopeDnaText(String text, java.util.Collection<String> absentNames) {
        if (text == null || text.isBlank() || absentNames == null || absentNames.isEmpty()) return text;
        // The cast is reduced (some bible character is absent), so a count word like
        // "the trio"/"the three"/"the four" mis-states how many are in frame and
        // weight-bleeds an extra body in. Neutralise it to the count-free "the
        // flock". (When the full cast IS present, absentNames is empty and we never
        // get here, so an accurate "trio" is left alone.)
        String out = neutraliseCountWords(text);
        // Only do the heavier clause surgery on fields that actually NAME an absent
        // character; everything else keeps its original punctuation (no churn).
        boolean mentionsAny = false;
        for (String name : absentNames) {
            if (name != null && !name.isBlank()
                    && java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b")
                            .matcher(out).find()) { mentionsAny = true; break; }
        }
        if (!mentionsAny) return out;
        for (String name : absentNames) {
            if (name == null || name.isBlank()) continue;
            String nb = "\\b" + java.util.regex.Pattern.quote(name) + "\\b";
            // (1) parentheticals mentioning the absent name → removed wholesale.
            out = out.replaceAll("\\s*\\([^()]*" + nb + "[^()]*\\)", "");
        }
        // (2) clause-level drop for remaining mentions.
        String[] clauses = out.split("\\s*(?:,|;|—|–|\\s-\\s)\\s*");
        java.util.List<String> kept = new java.util.ArrayList<>();
        for (String cl : clauses) {
            String c = cl.trim();
            if (c.isEmpty()) continue;
            boolean mentionsAbsent = false;
            for (String name : absentNames) {
                if (name == null || name.isBlank()) continue;
                if (java.util.regex.Pattern.compile("\\b" + java.util.regex.Pattern.quote(name) + "\\b")
                        .matcher(c).find()) { mentionsAbsent = true; break; }
            }
            if (!mentionsAbsent) kept.add(c);
        }
        String joined = String.join(", ", kept);
        // Tidy any punctuation/space artefacts left by the removals.
        return joined.replaceAll("\\s{2,}", " ")
                     .replaceAll("\\s+([,.;])", "$1")
                     .replaceAll("(?:,\\s*){2,}", ", ")
                     .replaceAll("^[,;\\s]+", "")
                     .replaceAll("[,;\\s]+$", "")
                     .trim();
    }

    /** Replaces flock-size count words ("the trio"/"the three"/"the four") with the
     *  count-free "the flock", so a reduced-cast scene's DNA never implies more
     *  characters are present than the roster allows (weight-bleeding an extra
     *  body into the frame). Only invoked when the cast is reduced.
     *
     *  <p>GUARD-PARITY: the orchestrator's {@code VeoPromptCompiler.neutraliseCountWords}
     *  is a hand copy of this. The canonical cases are pinned IDENTICALLY in
     *  {@code PromptComposerScopeTest.neutralisesFlockCountWordsForReducedCast} and
     *  {@code VeoPromptCompilerLeanTest.neutralisesFlockCountWordsForReducedCast};
     *  if the two copies drift, one module's parity test fails the build. (Same
     *  convention as AccessoryGuard — no shared lib, separate Maven modules.)
     *  Package-private for the parity test. */
    static String neutraliseCountWords(String text) {
        if (text == null || text.isBlank()) return text;
        return text.replaceAll("(?i)\\btrio\\b", "flock")
                   .replaceAll("(?i)\\bthe three\\b", "the flock")
                   .replaceAll("(?i)\\bthe four\\b", "the flock");
    }
}
