package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministic Veo shot-prompt compiler. Extracted verbatim from
 * {@code PipelineOrchestrator} (god-class split, step 1) — behaviour and output
 * are identical; this only moves the cohesive "scene + bible → Veo prompt line"
 * cluster (Camera-Bible, locations, surfaces, colour-script, render-look,
 * pacing/ease, character DNA / scale / tic clauses) into one focused, testable
 * place.
 *
 * <p>The orchestrator keeps its own {@code readBible()} (still used by other
 * paths such as {@code dnaAccessoryLines}); this compiler holds its own copy +
 * its own caches so the two are decoupled. The image provider
 * ({@code PromptComposer.dnaLine}) and this compiler must inject the SAME
 * canonical character DNA — when a DNA field is added to the bible, add it in
 * BOTH places.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class VeoPromptCompiler {

    private final OrchestratorProperties props;

    private JsonNode readBible() throws java.io.IOException {
        java.nio.file.Path p = java.nio.file.Paths.get(props.bible().path());
        return new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(p.toFile());
    }

    /**
     * Leegt ALLE lazy bible-caches zodat de eerstvolgende prompt-compilatie
     * de verse channel.yml leest. Aangeroepen door BibleReloadService na een
     * bible-edit (Cast-pagina of handmatig). Nieuwe cache toevoegen? Hier ook
     * nullen — anders blijft dat veld stale tot een herstart.
     */
    public void clearCaches() {
        cameraBibleCache = null;
        locationCache = null;
        surfaceCache = null;
        ticClauseCache = null;
        dnaIdentityCache = null;
        dnaScaleCache = null;
        colorScriptCache = null;
        veoLookCache = null;
    }

    // ---- Camera-Bible + world -------------------------------------------------

    private volatile Map<String, String[]> cameraBibleCache;   // phase -> [angle, lens, movement, focus, depthOfField]
    private volatile Map<String, String> locationCache;        // id -> description

    private static final String[] CAMERA_DEFAULT = {
            "eye-level", "50mm normal", "subtle slow camera move",
            "lock focus on the main character", "medium depth, gently soft background"
    };

    private Map<String, String[]> cameraBible() {
        Map<String, String[]> c = cameraBibleCache;
        if (c != null) return c;
        Map<String, String[]> out = new HashMap<>();
        try {
            JsonNode node = readBible().path("cameraBible");
            node.fields().forEachRemaining(e -> {
                JsonNode v = e.getValue();
                out.put(e.getKey().toLowerCase(), new String[]{
                        v.path("angle").asText(CAMERA_DEFAULT[0]),
                        v.path("lens").asText(CAMERA_DEFAULT[1]),
                        v.path("movement").asText(CAMERA_DEFAULT[2]),
                        v.path("focus").asText(CAMERA_DEFAULT[3]),
                        v.path("depthOfField").asText(CAMERA_DEFAULT[4])
                });
            });
        } catch (Exception e) {
            log.warn("Could not load cameraBible: {}", e.getMessage());
        }
        if (!out.containsKey("default")) {
            out.put("default", CAMERA_DEFAULT.clone());
        }
        cameraBibleCache = out;
        return out;
    }

    private String[] cameraSpec(String phase) {
        Map<String, String[]> cb = cameraBible();
        return cb.getOrDefault(phase == null ? "" : phase.toLowerCase(), cb.get("default"));
    }

    private Map<String, String> locations() {
        Map<String, String> c = locationCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode l : readBible().path("locations")) {
                String id = l.path("id").asText("").toLowerCase();
                String desc = l.path("description").asText("").trim();
                if (!id.isBlank()) out.put(id, desc);
            }
        } catch (Exception e) {
            log.warn("Could not load locations: {}", e.getMessage());
        }
        locationCache = out;
        return out;
    }

    private volatile Map<String, String> surfaceCache;       // location id -> surface

    /** Contact/impact verbs that warrant a ground-physics cue. Plain walking or
     *  standing is intentionally excluded so calm shots stay clean. */
    private static final String[] CONTACT_VERBS = {
            "dig", "scratch", "splash", "dive", "hop", "jump", "stomp", "kick",
            "slip", "roll", "climb", "peck", "land", "bounce", "paddle", "wade",
            "scuttle", "scamper", "tumble", "skid", "burrow", "pounce"
    };

    private Map<String, String> surfaces() {
        Map<String, String> c = surfaceCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            JsonNode node = readBible().path("locationSurfaces");
            node.fields().forEachRemaining(e ->
                    out.put(e.getKey().toLowerCase(), e.getValue().asText("").trim()));
        } catch (Exception e) {
            log.warn("Could not load locationSurfaces: {}", e.getMessage());
        }
        surfaceCache = out;
        return out;
    }

    /** G2 — ground-physics cue, emitted ONLY when the beat involves contact
     *  (dig/hop/splash/…), so VEO stops guessing how the surface reacts. Empty
     *  for calm/talking beats or when the location has no surface defined. */
    private String surfacePhrase(String locationId, String beatText) {
        if (locationId == null || locationId.isBlank() || beatText == null) return "";
        String surf = surfaces().get(locationId.toLowerCase());
        if (surf == null || surf.isBlank()) return "";
        String t = beatText.toLowerCase();
        boolean contact = false;
        for (String v : CONTACT_VERBS) {
            if (t.contains(v)) { contact = true; break; }
        }
        if (!contact) return "";
        return "Ground physics: the surface is " + surf + "; the characters' contact with it "
                + "reacts believably — squish, scatter, splash, dust or give as appropriate. ";
    }

    /** Maps Shot-DNA motionSpeed to a Veo pacing phrase. */
    private String pacePhrase(String motionSpeed) {
        String m = motionSpeed == null ? "" : motionSpeed.trim().toLowerCase();
        return switch (m) {
            case "quick", "fast"   -> "lively but smooth";
            case "natural", "medium" -> "gentle, natural";
            default                 -> "slow, gentle"; // slow / unspecified
        };
    }

    /** G4 — momentum/ease cue. Real character animation anticipates the main
     *  action (a tiny dip/wind-up), then settles with weight follow-through,
     *  instead of drifting at a robotic constant velocity. Modulated by pace. */
    private String easeClause(String motionSpeed) {
        String m = motionSpeed == null ? "" : motionSpeed.trim().toLowerCase();
        String ease = switch (m) {
            case "quick", "fast"     -> "a quick little wind-up before the main action and a snappy settle after";
            case "natural", "medium" -> "a small anticipation before the main action and a soft settle after";
            default                   -> "a gentle anticipation dip before the main action and a slow, soft settle after";
        };
        return "Motion eases in and out with " + ease + "; weight follows through "
                + "naturally — nothing moves at a robotic constant speed. ";
    }

    /** Hero phases (hook/climax) are the peak-emotion beats where an anticipation
     *  telegraph pays off most; we gate G6 on them so calm beats stay clean. */
    private boolean isHeroPhase(String phase) {
        String p = phase == null ? "" : phase.trim().toLowerCase();
        return p.equals("hook") || p.equals("climax");
    }

    /** Strips a trailing intensity marker from a Shot-DNA emotion so it reads as
     *  a plain feeling word, e.g. "wonder (5/5)" -> "wonder". */
    private String stripIntensity(String emotion) {
        if (emotion == null) return "";
        int paren = emotion.indexOf('(');
        return (paren >= 0 ? emotion.substring(0, paren) : emotion).trim();
    }

    /** Parses a Shot-DNA emotion intensity marker "(n/5)" → 1..5 (default 3 when
     *  unmarked). Drives how big the hero-beat anticipation telegraph reads. */
    private int emotionIntensity(String emotion) {
        if (emotion == null) return 3;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("\\(\\s*(\\d)\\s*/\\s*5\\s*\\)").matcher(emotion);
        if (m.find()) {
            try { return Math.max(1, Math.min(5, Integer.parseInt(m.group(1)))); }
            catch (NumberFormatException ignore) { /* default */ }
        }
        return 3;
    }

    /** Maps a bible timeOfDay id to a Veo lighting phrase (golden-hour default). */
    private String lightPhrase(String timeOfDay) {
        String t = timeOfDay == null ? "" : timeOfDay.trim().toLowerCase();
        return switch (t) {
            case "midday", "noon"   -> "bright, soft midday sunlight";
            case "dusk", "sunset"   -> "soft warm dusk light, long shadows";
            case "night", "evening" -> "calm moonlit night, soft cool light";
            case "dawn", "sunrise"  -> "gentle pink dawn light";
            default                  -> "warm golden-hour light"; // signature
        };
    }

    /** Maps a bible weather id to a Veo phrase. Blank = nothing extra. */
    private String weatherPhrase(String weather) {
        String w = weather == null ? "" : weather.trim().toLowerCase();
        return switch (w) {
            case "lightrain", "rain" -> "light rain with soft wet sparkle";
            case "breezy", "windy"   -> "a gentle breeze moving the grass";
            case "snow"              -> "soft falling snow";
            case "overcast", "cloudy"-> "soft overcast light";
            default                   -> ""; // clear / unspecified
        };
    }

    // ---- Signature tics (character DNA motion) --------------------------------

    private volatile Map<String, String> ticClauseCache;

    private Map<String, String> characterTicClauses() {
        Map<String, String> cached = ticClauseCache;
        if (cached != null) return cached;
        Map<String, String> out = new HashMap<>();
        try {
            java.nio.file.Path p = java.nio.file.Paths.get(props.bible().path());
            if (java.nio.file.Files.exists(p)) {
                JsonNode root = new com.fasterxml.jackson.dataformat.yaml.YAMLMapper().readTree(p.toFile());
                for (JsonNode ch : root.path("characters")) {
                    String id = ch.path("id").asText("").toLowerCase();
                    String name = ch.path("name").asText(id);
                    String tic = ch.path("dna").path("tic").asText("").trim();
                    if (!id.isBlank() && !tic.isBlank()) out.put(id, name + " " + tic);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load character tics from bible: {}", e.getMessage());
        }
        ticClauseCache = out;
        return out;
    }

    /** Builds the "signature character motion" sentence for the characters in a scene. */
    private String ticClause(List<String> charIds) {
        if (charIds == null || charIds.isEmpty()) return "";
        Map<String, String> tics = characterTicClauses();
        StringBuilder b = new StringBuilder();
        for (String id : charIds) {
            String clause = tics.get(id == null ? "" : id.toLowerCase());
            if (clause != null && !clause.isBlank()) b.append(clause).append(". ");
        }
        return b.length() == 0 ? "" : "Signature character motion: " + b;
    }

    // ---- Unified character-DNA clause (image + Veo share one source) ---------

    /** Cache of character id -> full DNA identity sentence from bible dna.* */
    private volatile Map<String, String> dnaIdentityCache;

    private Map<String, String> characterDnaClauses() {
        Map<String, String> cached = dnaIdentityCache;
        if (cached != null) return cached;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                if (id.isBlank()) continue;
                String name = ch.path("name").asText(id);
                JsonNode d = ch.path("dna");
                StringBuilder b = new StringBuilder(name).append(" is ");
                String color = d.path("coreColor").asText("").trim();
                b.append(color.isBlank() ? "a chick" : "a " + color + " chick");
                String acc = d.path("accessory").asText("").trim();
                if (!acc.isBlank()) b.append(", ALWAYS wearing ").append(acc)
                        .append(" (clearly visible, never dropped or swapped)");
                appendDnaDetail(b, "silhouette", d.path("silhouette").asText("").trim());
                appendDnaDetail(b, "feathers", d.path("feathers").asText("").trim());
                appendDnaDetail(b, "build", d.path("build").asText("").trim());
                appendDnaDetail(b, "weight", d.path("weight").asText("").trim());
                appendDnaDetail(b, "eyes", d.path("eyeColor").asText("").trim());
                b.append('.');
                String anti = d.path("antiAccessory").asText("").trim();
                if (!anti.isBlank()) b.append(' ').append(name)
                        .append(" must NEVER wear ").append(anti).append('.');
                out.put(id, b.toString());
            }
        } catch (Exception e) {
            log.warn("Could not load character DNA from bible: {}", e.getMessage());
        }
        dnaIdentityCache = out;
        return out;
    }

    private void appendDnaDetail(StringBuilder b, String label, String value) {
        if (value != null && !value.isBlank()) b.append("; ").append(label).append(": ").append(value);
    }

    /** Cache of character id -> scaleAnchor (dna.scaleAnchor). */
    private volatile Map<String, String> dnaScaleCache;

    private Map<String, String> characterScaleAnchors() {
        Map<String, String> cached = dnaScaleCache;
        if (cached != null) return cached;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                String sa = ch.path("dna").path("scaleAnchor").asText("").trim();
                if (!id.isBlank() && !sa.isBlank()) out.put(id, sa);
            }
        } catch (Exception e) {
            log.warn("Could not load scale anchors from bible: {}", e.getMessage());
        }
        dnaScaleCache = out;
        return out;
    }

    /** G1 — one scale-lock sentence for the flock. All chicks are the SAME small
     *  size, so we emit the first present character's anchor as the size
     *  reference and forbid per-shot resizing. Empty when no anchor is set. */
    private String scaleLockClause(List<String> charIds) {
        if (charIds == null || charIds.isEmpty()) return "";
        Map<String, String> anchors = characterScaleAnchors();
        for (String id : charIds) {
            String a = anchors.get(id == null ? "" : id.toLowerCase());
            if (a != null && !a.isBlank()) {
                return "Scale lock (keep body size consistent with the world): the chicks are "
                        + a + ". All chicks present are the SAME small size AND the same plump, "
                        + "rounded baby-chick body shape — never resize, slim down, stretch, "
                        + "elongate or thin out a character between shots or within a shot. ";
            }
        }
        return "";
    }

    /** G7 — headcount + presence lock. The image side (PromptComposer) pins the
     *  cast count for the STILL ("exactly N chicks"), but once Veo animates the
     *  frame nothing stopped it from letting a chick wander out of frame, fade
     *  away, or letting an extra chicken wander in (the two failure modes the
     *  channel audit hit: "characters verdwijnen ineens" / "soms te veel
     *  characters"). This clause re-pins the count AND the presence for the
     *  full duration of the clip. */
    private String headcountLockClause(List<String> charIds) {
        if (charIds == null || charIds.isEmpty()) return "";
        int n = charIds.size();
        Map<String, String> dna = characterDnaClauses();
        // Use the bible names where we have them so the lock names WHO stays.
        StringBuilder names = new StringBuilder();
        int known = 0;
        for (String id : charIds) {
            String clause = dna.get(id == null ? "" : id.toLowerCase());
            if (clause == null || clause.isBlank()) continue;
            // dna clause starts with "<Name> is ..." — take the name token.
            String name = clause.split("\\s+", 2)[0];
            if (names.length() > 0) names.append(known == n - 1 ? " and " : ", ");
            names.append(name);
            known++;
        }
        String who = names.length() > 0 ? " (" + names + ")" : "";
        return "Headcount lock: EXACTLY " + n + (n == 1 ? " character" : " characters")
                + who + " in the frame for the ENTIRE shot. "
                + (n == 1 ? "It stays" : "All " + n + " stay") + " fully visible from the "
                + "first frame to the last — nobody exits the frame, shrinks away, fades "
                + "out or disappears, and NO new character, chicken, animal or silhouette "
                + "enters the frame or appears in the background. ";
    }

    /** Combined DNA identity clause for the characters present in a scene. */
    private String dnaIdentityClause(List<String> charIds) {
        if (charIds == null || charIds.isEmpty()) return "";
        Map<String, String> dna = characterDnaClauses();
        StringBuilder b = new StringBuilder();
        for (String id : charIds) {
            String clause = dna.get(id == null ? "" : id.toLowerCase());
            if (clause != null && !clause.isBlank()) b.append(clause).append(' ');
        }
        if (b.length() == 0) return "";
        String clause = "Character identity (keep EXACT across every frame): " + b;
        // Cross-character anti-swap lock — with 2+ chicks in frame Veo tends to
        // move the hat/glasses/scarf onto the wrong one (proven in the intro
        // tests). This explicit "never swap between them" line fixes it.
        if (charIds.size() > 1) {
            clause += "NEVER swap accessories between the chickens — each keeps ONLY its own "
                    + "hat/glasses/scarf and its own body colour; do not move a hat, glasses or "
                    + "scarf onto another chicken, and never give one chicken two accessories. ";
        }
        return clause;
    }

    // ---- Story D: render-style + colour-script (bible-driven) ----------------

    private volatile Map<String, String> colorScriptCache;   // phase -> colour mood
    private volatile String veoLookCache;                    // concise render-look sentence

    /** Per-phase emotional colour mood from bible.colorScript (Pixar colour-script).
     *  Empty when the bible has no colorScript section. */
    private String colorScriptPhrase(String phase) {
        Map<String, String> cs = colorScriptCache;
        if (cs == null) {
            final Map<String, String> built = new HashMap<>();
            try {
                JsonNode node = readBible().path("colorScript");
                node.fields().forEachRemaining(e -> built.put(e.getKey().toLowerCase(), e.getValue().asText("")));
            } catch (Exception e) {
                log.warn("Could not load colorScript: {}", e.getMessage());
            }
            cs = built;
            colorScriptCache = cs;
        }
        String key = phase == null ? "" : phase.toLowerCase();
        return cs.getOrDefault(key, cs.getOrDefault("default", ""));
    }

    /** Concise Veo render-look from bible.renderStyle.veoLook, so the animated
     *  clip shares the stills' materials/lighting. Falls back to the built-in
     *  Pixar-look string when the bible field is absent. */
    private String veoLook() {
        String v = veoLookCache;
        if (v == null) {
            String fallback = "Soft 3D Pixar / Illumination cartoon look.";
            try {
                String fromBible = readBible().path("renderStyle").path("veoLook").asText("").trim();
                v = fromBible.isBlank() ? fallback : fromBible.replaceAll("\\s+", " ");
            } catch (Exception e) {
                v = fallback;
            }
            veoLookCache = v;
        }
        return v;
    }

    // ---- Public entry point ---------------------------------------------------

    /**
     * Compiles the full Veo prompt for one shot: camera (Camera-Bible by phase)
     * + world (bible location + golden-hour light) + the scene action + lifelike
     * micro-motion + per-character signature tics + a hard identity-stability
     * lock. Deterministic — no important field is left to VEO's imagination.
     */
    public String compile(String visualDesc, String phase, List<String> charIds,
                          String locationId, String timeOfDay, String weather,
                          String goal, String emotion, String motionSpeed) {
        String base = visualDesc == null ? "" : visualDesc.trim();
        String[] cam = cameraSpec(phase);
        StringBuilder p = new StringBuilder();
        p.append("Animate from the start frame with ").append(pacePhrase(motionSpeed))
         .append(", natural, child-friendly motion. ");
        // FRONT-LOAD THE ACTION (assembly-audit #7): video models weigh early
        // tokens heaviest, and the action — what the scene IS — used to sit
        // halfway down a 200-word prompt behind camera/world/colour clauses.
        // The locks below still follow; they protect, the action directs.
        if (!base.isBlank()) p.append("Action: ").append(base)
                .append(base.endsWith(".") ? " " : ". ");
        // Camera (Camera-Bible): angle, lens, movement, focus, depth-of-field —
        // so VEO never guesses what's sharp or how soft the background falls off.
        p.append("Camera: ").append(cam[0]).append(", ").append(cam[1])
         .append(", ").append(cam[2]);
        if (cam.length > 3 && cam[3] != null && !cam[3].isBlank()) p.append(", ").append(cam[3]);
        if (cam.length > 4 && cam[4] != null && !cam[4].isBlank()) p.append(", ").append(cam[4]);
        p.append(". ");
        // World context (so the animation keeps the still's setting + light)
        String loc = locationId == null ? "" : locations().getOrDefault(locationId.toLowerCase(), "");
        p.append("Setting: ");
        if (!loc.isBlank()) p.append(loc).append(", ");
        p.append(lightPhrase(timeOfDay));
        String wx = weatherPhrase(weather);
        if (!wx.isBlank()) p.append(", ").append(wx);
        p.append(". ");
        // Story D — emotional colour-script for this phase (Pixar-style), so the
        // palette supports the beat's feeling instead of being uniformly bright.
        String colour = colorScriptPhrase(phase);
        if (!colour.isBlank()) p.append("Colour mood: ").append(colour).append(". ");
        // Shot-DNA: beat goal + emotion drive the performance.
        if (goal != null && !goal.isBlank()) p.append("Beat goal: ").append(goal.trim()).append(". ");
        if (emotion != null && !emotion.isBlank()) {
            p.append("Performance: clearly convey ").append(emotion.trim()).append(". ");
            // G6 — anticipation telegraph on hero beats: animate the feeling a beat
            // BEFORE the action lands so the emotion reads, then let the beat hit.
            if (isHeroPhase(phase)) {
                // Story top-20 #12 — scale the anticipation telegraph by the
                // emotion's intensity marker "(n/5)": a gentle 1/5 beat gets a
                // small flicker, a 5/5 beat gets a big, clear anticipatory beat.
                int intensity = emotionIntensity(emotion);
                String size = intensity >= 4 ? "a big, clear"
                            : intensity <= 2 ? "a small, subtle"
                            : "a quick";
                p.append("Telegraph the moment: a beat before the main action, show ").append(size)
                 .append(" anticipatory flash of ").append(stripIntensity(emotion))
                 .append(" — eyes widening, a sharp little intake, body tensing — then let "
                        + "the beat land. ");
            }
        }
        // G2 — ground physics, only on contact beats (dig/hop/splash/…).
        String surfaceFx = surfacePhrase(locationId,
                (goal == null ? "" : goal) + " " + base);
        if (!surfaceFx.isBlank()) p.append(surfaceFx);
        // Full character DNA identity — the SAME canonical fields the image
        // provider locks (PromptComposer.dnaLine), so nothing the still nailed
        // (colour, accessory, silhouette, feathers, build, weight, eyes) drifts
        // once Veo starts moving the frame. One source: bible characters[].dna.
        String identity = dnaIdentityClause(charIds);
        if (!identity.isBlank()) p.append(identity);
        // G7 — headcount + presence lock: exact cast count, everyone stays in
        // frame, nobody new enters. Mirrors PromptComposer's "exactly N chicks"
        // still-side lock so the guarantee survives the animation step.
        String headcount = headcountLockClause(charIds);
        if (!headcount.isBlank()) p.append(headcount);
        // G1 — scale lock: keep body size consistent relative to the world so a
        // chick never changes size between shots (one sentence for the flock).
        String scale = scaleLockClause(charIds);
        if (!scale.isBlank()) p.append(scale);
        // (Scene action moved to the TOP of the prompt — audit #7 front-load.)
        // Lifelike micro-motion + ambient.
        // P5 — NO fake lip-sync: Veo gets no phoneme guidance (the voice is a
        // separate ElevenLabs track), so a beak "moving while speaking" lands
        // mis-timed against the words. A calm, mostly-closed beak reads better
        // than a wrongly-timed open one.
        p.append("The characters move with small lifelike motion — blinking, soft breathing, "
                + "slight head and wing movement, with only small soft beak movements (do NOT "
                + "lip-sync words — keep the beak mostly closed with subtle motion) — with soft "
                + "ambient life (drifting petals or fireflies, gently swaying grass). ");
        // G4 — momentum/ease so the action feels animated, not robotic.
        p.append(easeClause(motionSpeed));
        // Signature tics (character DNA motion)
        String tics = ticClause(charIds);
        if (!tics.isBlank()) p.append(tics);
        // Identity-stability lock + style
        p.append("Keep every character's identity, colours, proportions and signature "
                + "accessories (straw hat, bandana, scarf, eyeglasses) perfectly stable across "
                + "all frames — absolutely no morphing, no flicker, accessories never change. "
                + "Smooth, steady camera. ");
        // Story D — shared render-look (bible.renderStyle.veoLook) so the animated
        // clip keeps the same materials/lighting as the still it animates.
        p.append(veoLook());
        return p.toString();
    }
}
