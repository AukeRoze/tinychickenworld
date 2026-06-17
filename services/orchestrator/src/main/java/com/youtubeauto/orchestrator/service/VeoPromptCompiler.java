package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
        signatureSoundCache = null;
        cameraMoveCache = null;
        cameraMoveIntensityCache = null;
        sceneCameraMapCache = null;
        dnaVeoKeyCache = null;
        dnaSizeRankCache = null;
        negativeConstraintsCache = null;
        rosterNounCache = null;
        accessoryModelCache = null;
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

    // ---- Camera-move library (camera-moves.json, sibling of the bible) --------

    /** id -> camera directive text from camera-moves.json. Optional file: when it
     *  is absent the map stays empty and {@link #cameraMoveDirective} returns ""
     *  so the prompt simply keeps the phase-default movement (feature is inert). */
    private volatile Map<String, String> cameraMoveCache;

    /** Loads camera-moves.json from the SAME directory as the bible (so it rides
     *  the same mount/reload path). Each entry maps its lowercased {@code id} to
     *  the {@code camera} directive sentence. Best-effort: any read/parse problem
     *  logs a warning and leaves the library empty rather than failing a render. */
    private Map<String, String> cameraMoves() {
        Map<String, String> c = cameraMoveCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            java.nio.file.Path biblePath = java.nio.file.Paths.get(props.bible().path());
            java.nio.file.Path dir = biblePath.getParent();
            java.nio.file.Path movesPath = (dir == null)
                    ? java.nio.file.Paths.get("camera-moves.json")
                    : dir.resolve("camera-moves.json");
            if (java.nio.file.Files.exists(movesPath)) {
                JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(movesPath.toFile());
                for (JsonNode m : root.path("moves")) {
                    String id = m.path("id").asText("").trim().toLowerCase();
                    String dir2 = m.path("camera").asText("").trim();
                    if (!id.isBlank() && !dir2.isBlank()) out.put(id, dir2);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load camera-moves.json: {}", e.getMessage());
        }
        cameraMoveCache = out;
        return out;
    }

    /** Resolves a camera-move id to its directive sentence, or "" when the id is
     *  blank / unknown (then the phase-default movement is used instead). */
    private String cameraMoveDirective(String moveId) {
        if (moveId == null || moveId.isBlank()) return "";
        return cameraMoves().getOrDefault(moveId.trim().toLowerCase(), "");
    }

    // ---- Negative constraints (negative-constraints.txt, sibling of bible) ----

    /** User-managed negative-constraint points (one per line) from
     *  negative-constraints.txt next to the bible — editable from the Brand
     *  page. When the file is absent the list stays empty and the prompt falls
     *  back to {@link #DEFAULT_NEGATIVE_TAIL}, so behaviour is unchanged until
     *  it is configured. The cast-size anti-duplication clause is NOT part of
     *  this list; it stays programmatic (see {@code directorBrief}). */
    private volatile List<String> negativeConstraintsCache;

    /** Built-in fallback (verbatim the points the prompt always carried) used
     *  when no negative-constraints.txt exists yet. */
    private static final String DEFAULT_NEGATIVE_TAIL =
            "No morphing, no flicker, no accessory swaps, no gaping mouths, no Dutch "
            + "or non-English speech. No on-screen text, numbers, digits, timers, timecodes, "
            + "signs, banners or watermarks. "
            + "Keep every character's identity, colours, proportions and signature "
            + "accessories (straw hat, bandana, scarf, eyeglasses) perfectly stable "
            + "across all frames; Pip's straw hat must never come off.";

    /** Loads negative-constraints.txt from the SAME directory as the bible (so
     *  it rides the same mount / reload path). Each non-blank, non-comment (#)
     *  line is one constraint point. Best-effort: any read problem logs and
     *  leaves the list empty (the prompt then uses the default tail). */
    List<String> negativeConstraints() {
        List<String> c = negativeConstraintsCache;
        if (c != null) return c;
        List<String> out = new java.util.ArrayList<>();
        try {
            java.nio.file.Path dir = java.nio.file.Paths.get(props.bible().path()).getParent();
            java.nio.file.Path f = (dir == null)
                    ? java.nio.file.Paths.get("negative-constraints.txt")
                    : dir.resolve("negative-constraints.txt");
            if (java.nio.file.Files.exists(f)) {
                for (String line : java.nio.file.Files.readAllLines(
                        f, java.nio.charset.StandardCharsets.UTF_8)) {
                    String t = line.trim();
                    if (!t.isEmpty() && !t.startsWith("#")) out.add(t);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load negative-constraints.txt: {}", e.getMessage());
        }
        negativeConstraintsCache = out;
        return out;
    }

    /** Joins the user-managed constraint points into one sentence-ended string,
     *  or the built-in default tail when none are configured. Each point is
     *  terminated with a period so the compiled prompt still ends cleanly. */
    private String negativeConstraintsText() {
        List<String> items = negativeConstraints();
        if (items.isEmpty()) return DEFAULT_NEGATIVE_TAIL;
        StringBuilder b = new StringBuilder();
        for (String it : items) {
            String t = it.trim();
            if (t.isEmpty()) continue;
            if (b.length() > 0) b.append(' ');
            b.append(t);
            char last = t.charAt(t.length() - 1);
            if (last != '.' && last != '!' && last != '?') b.append('.');
        }
        return b.length() == 0 ? DEFAULT_NEGATIVE_TAIL : b.toString();
    }

    // ---- Automatic camera-move selection (whitelist + bible flag) -------------

    /** id -> motion_intensity ("low"/"medium"/"high") from camera-moves.json. */
    private volatile Map<String, String> cameraMoveIntensityCache;

    private Map<String, String> cameraMoveIntensities() {
        Map<String, String> c = cameraMoveIntensityCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            java.nio.file.Path biblePath = java.nio.file.Paths.get(props.bible().path());
            java.nio.file.Path dir = biblePath.getParent();
            java.nio.file.Path movesPath = (dir == null)
                    ? java.nio.file.Paths.get("camera-moves.json")
                    : dir.resolve("camera-moves.json");
            if (java.nio.file.Files.exists(movesPath)) {
                JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(movesPath.toFile());
                for (JsonNode m : root.path("moves")) {
                    String id = m.path("id").asText("").trim().toLowerCase();
                    String mi = m.path("motion_intensity").asText("medium").trim().toLowerCase();
                    if (!id.isBlank()) out.put(id, mi);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load camera-move intensities: {}", e.getMessage());
        }
        cameraMoveIntensityCache = out;
        return out;
    }

    /** intent -> ordered candidate move ids (primary first, then alternatives).
     *  The 'bold' tier is deliberately NOT included: those are high-impact moves
     *  reserved for manual use, kept out of the automatic (whitelisted) rotation. */
    private volatile Map<String, List<String>> sceneCameraMapCache;

    private Map<String, List<String>> sceneCameraMap() {
        Map<String, List<String>> c = sceneCameraMapCache;
        if (c != null) return c;
        Map<String, List<String>> out = new HashMap<>();
        try {
            java.nio.file.Path biblePath = java.nio.file.Paths.get(props.bible().path());
            java.nio.file.Path dir = biblePath.getParent();
            java.nio.file.Path mapPath = (dir == null)
                    ? java.nio.file.Paths.get("scene-camera-mapping.json")
                    : dir.resolve("scene-camera-mapping.json");
            if (java.nio.file.Files.exists(mapPath)) {
                JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(mapPath.toFile());
                for (JsonNode it : root.path("intents")) {
                    String intent = it.path("intent").asText("").trim().toLowerCase();
                    if (intent.isBlank()) continue;
                    List<String> ids = new ArrayList<>();
                    String primary = it.path("primary").asText("").trim().toLowerCase();
                    if (!primary.isBlank()) ids.add(primary);
                    for (JsonNode alt : it.path("alternatives")) {
                        String a = alt.asText("").trim().toLowerCase();
                        if (!a.isBlank() && !ids.contains(a)) ids.add(a);
                    }
                    if (!ids.isEmpty()) out.put(intent, ids);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load scene-camera-mapping.json: {}", e.getMessage());
        }
        sceneCameraMapCache = out;
        return out;
    }

    /** Story-phase → camera-mapping intent. Only the phases that render as
     *  "standard" scenes are mapped; hero (hook/climax) and outro (closer) keep
     *  their deliberate cinematic framing and are never auto-assigned a move. */
    private static final Map<String, String> PHASE_TO_INTENT = Map.of(
            "setup", "establishing",
            "development", "dialogue-confrontation",
            "resolution", "ending-resolution");

    private static final String DEFAULT_INTENT = "journey-movement";

    /** Reads the {@code cameraMovesAuto} flag from the bible. Absent → false, so
     *  automatic moves are OFF by default and the live prompt output is unchanged
     *  until the flag is explicitly enabled (and a bible reload picks it up). */
    private boolean cameraMovesAutoEnabled() {
        try {
            return readBible().path("cameraMovesAuto").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Deterministically picks a frame-chaining-SAFE camera-move id for a standard
     * scene, or {@code null} when automatic moves are disabled / no safe candidate
     * exists. The candidate list comes from scene-camera-mapping.json (phase →
     * intent → primary + alternatives), filtered to low/medium {@code
     * motion_intensity} (high-intensity moves are excluded to avoid fighting the
     * locked start frame / morphing), then rotated by {@code seq} so consecutive
     * beats of the same phase vary instead of reading as one fixed preset.
     */
    public String autoCameraMove(String phase, int seq) {
        if (!cameraMovesAutoEnabled()) return null;
        String intent = PHASE_TO_INTENT.getOrDefault(
                phase == null ? "" : phase.toLowerCase(), DEFAULT_INTENT);
        List<String> candidates = sceneCameraMap().get(intent);
        if (candidates == null || candidates.isEmpty()) return null;
        Map<String, String> directives = cameraMoves();
        Map<String, String> intensities = cameraMoveIntensities();
        List<String> safe = new ArrayList<>();
        for (String id : candidates) {
            if (!directives.containsKey(id)) continue; // unknown move id
            String mi = intensities.getOrDefault(id, "medium");
            if (mi.equals("low") || mi.equals("medium")) safe.add(id);
        }
        if (safe.isEmpty()) return null;
        return safe.get(Math.floorMod(seq, safe.size()));
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
        // Each variant already INCLUDES "natural" so the opening line can append
        // only ", child-friendly motion." without doubling the word (the old
        // ", natural, child-friendly" suffix produced "gentle, natural, natural").
        return switch (m) {
            case "quick", "fast"     -> "lively but smooth, natural";
            case "natural", "medium" -> "gentle, natural";
            default                  -> "slow, gentle, natural"; // slow / unspecified
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
            // The bible offers a `morning` timeOfDay but this switch had no case,
            // so morning scenes silently fell back to golden-hour (which models
            // read as evening) — wrong for early scenes meant to feel like fresh
            // morning before a dusk ending. (scene 1-7 defect)
            case "morning", "earlymorning", "early-morning"
                                    -> "soft warm early-morning light, fresh, with dew and long crisp morning shadows";
            case "latemorning", "late-morning"
                                    -> "soft warm late-morning sunlight, gentle shadows";
            case "afternoon"        -> "soft warm afternoon light, long gentle golden shadows, filtered and cozy";
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

    /** Cache of character id -> COMPACT identity line from bible dna.veoKey. This
     *  is the "lean prompt" path: instead of dumping every verbose DNA field
     *  (silhouette/build/weight/feathers — 300+ words per character, repeated
     *  every scene), the bible gives one short line with only the consistency-
     *  critical cues (colour, shape, accessory, eyes, size rank). Empty per
     *  character when no veoKey is set → that character falls back to verbose. */
    private volatile Map<String, String> dnaVeoKeyCache;

    private Map<String, String> characterVeoKeyClauses() {
        Map<String, String> cached = dnaVeoKeyCache;
        if (cached != null) return cached;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                if (id.isBlank()) continue;
                String name = ch.path("name").asText(id);
                String key = ch.path("dna").path("veoKey").asText("").trim();
                if (!key.isBlank()) {
                    out.put(id, name + ": " + key + (key.endsWith(".") ? "" : "."));
                }
            }
        } catch (Exception e) {
            log.warn("Could not load character veoKey lines: {}", e.getMessage());
        }
        dnaVeoKeyCache = out;
        return out;
    }

    /** Master switch for the lean-prompt fixes (compact identity via dna.veoKey,
     *  relaxed cast lock, single scale rule). Absent → false, so the prompt
     *  output is unchanged until the bible explicitly enables it. */
    private boolean veoLeanPrompts() {
        try {
            return readBible().path("veoLeanPrompts").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** One concise relative-size rule for the whole flock (bible.veoScaleRule),
     *  used in lean mode instead of the per-character anchors. Empty when unset. */
    private String veoScaleRule() {
        try {
            return readBible().path("veoScaleRule").asText("").trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Cache of character id -> COMPACT relative-size phrase (bible dna.veoSizeRank),
     *  e.g. "Pip is the smallest". This lets {@link #scaleLockClause} assemble the
     *  "Relative size:" sentence from ONLY the characters present in the scene,
     *  instead of emitting the whole-flock veoScaleRule verbatim in every shot —
     *  which named absent characters (e.g. "Mo is slightly larger" in a Pip-only
     *  beat), inviting Veo to load a chicken that should not be in the cast. Empty
     *  per character when no veoSizeRank is set. */
    private volatile Map<String, String> dnaSizeRankCache;

    private Map<String, String> characterSizeRanks() {
        Map<String, String> cached = dnaSizeRankCache;
        if (cached != null) return cached;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                String rank = ch.path("dna").path("veoSizeRank").asText("").trim();
                if (!id.isBlank() && !rank.isBlank()) {
                    // strip a trailing period so the caller controls punctuation/joins
                    out.put(id, rank.endsWith(".") ? rank.substring(0, rank.length() - 1).trim() : rank);
                }
            }
        } catch (Exception e) {
            log.warn("Could not load character size ranks from bible: {}", e.getMessage());
        }
        dnaSizeRankCache = out;
        return out;
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
        // LEAN: a concise relative-size rule instead of the overlapping per-character
        // anchors — and it no longer claims "all the SAME size", which contradicted
        // the distinct builds (Pip smallest, Mo larger, Bo taller/slimmer).
        //
        // CAST-SCOPED: build the sentence from ONLY the characters actually in this
        // shot, mirroring how the "Key cues" identity line is filtered. The old code
        // emitted the whole-flock veoScaleRule verbatim in every scene, so a Pip-only
        // beat still read "Mo is slightly larger, Bo is slightly taller…" — naming
        // absent chicks and tempting Veo to render them. Per-character phrases come
        // from bible dna.veoSizeRank; if none are configured we fall back to the
        // legacy single veoScaleRule (output unchanged until the bible opts in).
        if (veoLeanPrompts()) {
            Map<String, String> ranks = characterSizeRanks();
            List<String> parts = new ArrayList<>();
            for (String id : charIds) {
                String r = ranks.get(id == null ? "" : id.toLowerCase());
                if (r != null && !r.isBlank() && !parts.contains(r)) parts.add(r);
            }
            // A relative-size comparison only means something with 2+ present chars;
            // a single subject has nothing to be "relative" to (its proportions are
            // already pinned by the anti-morph lines), so we omit the line entirely.
            if (parts.size() >= 2) {
                return "Relative size: " + String.join(", ", parts)
                        + ". Their proportions never change between or within shots. ";
            }
            if (parts.size() == 1) {
                // Exactly one ranked character present → no relative-size line.
                return "";
            }
            // No per-character ranks configured → legacy whole-flock rule.
            String rule = veoScaleRule();
            if (!rule.isBlank()) {
                return "Relative size: " + rule + (rule.endsWith(".") ? " " : ". ");
            }
            // else: fall through to the per-anchor scale lock below.
        }
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
    private String headcountLockClause(List<String> charIds, boolean closeUp, String visualDesc) {
        if (charIds == null || charIds.isEmpty()) return "";
        int n = charIds.size();
        Map<String, String> dna = characterDnaClauses();
        String descLc = visualDesc == null ? "" : visualDesc.toLowerCase();
        // Use the bible names where we have them so the lock names WHO stays — and,
        // in parallel, split the cast into who the ACTION actually frames vs who is
        // only present for continuity. A scene's `characters` array is its cast for
        // cast-CONTINUITY (so a sidekick doesn't pop out for one beat), which is NOT
        // the same as who is framed in this shot: inserts, participation beats and
        // over-the-shoulder shots legitimately leave a present cast member off-frame
        // (e.g. golden "close-up on the pond surface, Mo's reflection" with Pip
        // present but unframed). Naming an off-frame member in the EXACTLY-N count
        // used to make Veo cram a motionless, unscripted body into the shot — or
        // morph it into a framed one. We keep the count for the anti-newcomer /
        // anti-swap guarantee but tell Veo NOT to force the off-frame members in.
        StringBuilder names = new StringBuilder();
        List<String> framed = new ArrayList<>();
        List<String> offFrame = new ArrayList<>();
        int known = 0;
        for (String id : charIds) {
            String clause = dna.get(id == null ? "" : id.toLowerCase());
            if (clause == null || clause.isBlank()) continue;
            // dna clause starts with "<Name> ..." — take the name token (strip any
            // trailing punctuation like the "Pip:" colon used in the key-cues line).
            String name = clause.split("\\s+", 2)[0].replaceAll("[^\\p{L}]", "");
            if (names.length() > 0) names.append(known == n - 1 ? " and " : ", ");
            names.append(name);
            known++;
            // Framed = named (by id or display name) in the action text. A blank
            // visualDesc carries no framing signal, so treat everyone as framed
            // (no relaxation) — identical to the previous behaviour.
            boolean isFramed = descLc.isBlank()
                    || mentionsWord(descLc, id) || mentionsWord(descLc, name.toLowerCase());
            (isFramed ? framed : offFrame).add(name);
        }
        String who = names.length() > 0 ? " (" + names + ")" : "";
        // LEAN: lock the CAST SET (exact count, no newcomer, no swap) but DON'T
        // demand everyone be "fully visible from first to last frame" — that
        // contradicted intentional partial framing (e.g. "Pip peeks up from the
        // bottom edge"). Characters may move within or partly out of frame.
        if (veoLeanPrompts()) {
            String castLock = "Cast lock: this shot has EXACTLY " + n + (n == 1 ? " character" : " characters")
                    + who + " — these and no others. No extra character, chicken, animal or "
                    + "silhouette appears or wanders into the background, and none of them is "
                    + "replaced or swapped out. They may move within the frame (and partly in or "
                    + "out of it as the action calls for), but the cast stays this exact set. ";
            // Tight shots: don't force the whole cast into frame. Off-frame members
            // are fine, and the shot must not widen just to fit them.
            if (closeUp && n > 1) {
                castLock += "This is a tight shot: only what the framing shows (the framed "
                        + "character, face or object — e.g. the wings and the egg) needs to be in "
                        + "view. ";
                if (!framed.isEmpty() && !offFrame.isEmpty() && !framesWholeCast(descLc)) {
                    // Intimate close-up that names a small framed subset (e.g. Pip +
                    // the duckling): keep the others OUT — "EXACTLY N" alone made Veo
                    // cram them into a corner and ruin the intimacy (scene-24).
                    String fr = joinNames(framed);
                    String off = joinNames(offFrame);
                    boolean one = offFrame.size() == 1;
                    castLock += "Frame " + fr + " tightly; " + off + " "
                            + (one ? "is" : "are") + " NOT in this shot — keep "
                            + (one ? "it" : "them") + " fully out of frame, or melt "
                            + (one ? "it" : "them") + " into soft, unreadable background blur, never "
                            + "posed or readable in a corner. ";
                } else {
                    castLock += "The rest of the cast may be entirely out of frame, or sit softly "
                            + "out-of-focus in the background if the action places them there. ";
                }
                castLock += "The shot must NOT widen or pull back just to fit everyone in. ";
            } else if (n > 1 && !framed.isEmpty() && !offFrame.isEmpty()
                    && !framesWholeCast(descLc)) {
                // The action stages only SOME of the cast. Pin presence WITHOUT
                // forcing the rest in — the fix for the "pressed-in extra" / morph.
                // GUARD: a wide reveal or a collective group reference ("all three",
                // "the trio") frames the WHOLE cast, so we must NOT mark the
                // unnamed members off-frame there — that wrongly told Veo to wipe
                // characters out of a group reveal (the scene-22/23 regression).
                String fr = joinNames(framed);
                String off = joinNames(offFrame);
                boolean one = offFrame.size() == 1;
                castLock += "Of these, the action frames " + fr + "; " + off + " "
                        + (one ? "is" : "are") + " part of the scene but NOT framed in this shot — "
                        + "keep " + (one ? "that character" : "them") + " out of frame rather than "
                        + "posing " + (one ? "it" : "them") + " motionless in the background, do NOT "
                        + "widen the shot to fit everyone, and never merge or morph "
                        + (one ? "it" : "them") + " into a framed character. ";
            }
            return castLock;
        }
        return "Headcount lock: EXACTLY " + n + (n == 1 ? " character" : " characters")
                + who + " in the frame for the ENTIRE shot. "
                + (n == 1 ? "It stays" : "All " + n + " stay") + " fully visible from the "
                + "first frame to the last — nobody exits the frame, shrinks away, fades "
                + "out or disappears, and NO new character, chicken, animal or silhouette "
                + "enters the frame or appears in the background. ";
    }

    /** True when the action text itself frames a tight shot (close-up / extreme
     *  close-up / macro / insert), so the cast lock can relax even when the phase
     *  camera preset resolves to a wider default. */
    private static boolean describesCloseUp(String visualDesc) {
        if (visualDesc == null || visualDesc.isBlank()) return false;
        String d = visualDesc.toLowerCase();
        return d.contains("close-up") || d.contains("close up")
                || d.contains("extreme close") || d.contains("macro shot")
                || d.contains("insert shot");
    }

    /** True when the action frames a wide / pull-back / reveal shot, so the cast
     *  preset's push-in movement and tight lens can yield to a widening move. The
     *  markers are shot-language ("wide shot", "pull back", "reveal the full"),
     *  never bare "wide" — so "wide-eyed Pip" or "wings flung wide" do NOT trip it. */
    private static boolean describesWideReveal(String visualDesc) {
        if (visualDesc == null || visualDesc.isBlank()) return false;
        String d = visualDesc.toLowerCase();
        return d.contains("wide shot") || d.contains("wide reveal")
                || d.contains("wide establishing") || d.contains("establishing shot")
                || d.contains("pull back") || d.contains("pull-back") || d.contains("pulls back")
                || d.contains("reveal the full") || d.contains("zoom out");
    }

    /** True when the action frames the WHOLE cast — a wide reveal, or a collective
     *  group reference ("all three", "the trio", "together") — so the off-frame
     *  relaxation must be suppressed (everyone is in shot, even if not each named).
     *  {@code descLc} is the already-lower-cased visualDesc. */
    private static boolean framesWholeCast(String descLc) {
        if (descLc == null || descLc.isBlank()) return false;
        if (describesWideReveal(descLc)) return true;
        return descLc.contains("all three") || descLc.contains("all four")
                || descLc.contains("the trio") || descLc.contains("the three chicks")
                || descLc.contains("the group") || descLc.contains("the whole group")
                || descLc.contains("the flock") || descLc.contains("everyone")
                || descLc.contains("all of them") || descLc.contains("together");
    }

    /** True when the action already stages a big WHOLE-BODY or group motion
     *  (tumble, fall, roll, sprawl, jump, leap, legs in the air…). In those beats
     *  the cast is fully committed physically, so forcing an extra fine signature
     *  gesture on top (Pip leaning in to touch her hat while sprawled on her back)
     *  contradicts the pose and morphs limbs — so the per-clip signature tic is
     *  suppressed and the body motion is left to carry the beat. */
    private static boolean describesWholeBodyMotion(String visualDesc) {
        if (visualDesc == null || visualDesc.isBlank()) return false;
        String d = visualDesc.toLowerCase();
        return d.contains("tumble") || d.contains("tumbl") || d.contains("fall")
                || d.contains("falls") || d.contains("fell") || d.contains("roll")
                || d.contains("sprawl") || d.contains("backwards") || d.contains("backward")
                || d.contains("legs in the air") || d.contains("on their back")
                || d.contains("on her back") || d.contains("on his back")
                || d.contains("jump") || d.contains("leap") || d.contains("topple")
                || d.contains("flop") || d.contains("dive") || d.contains("spin");
    }

    /** True for a dark / low-sun time of day, where a "daylight" colour mood
     *  contradicts the scene light and flickers (the scene-20 dusk-vs-daylight
     *  defect). */
    private static boolean isDarkTime(String timeOfDay) {
        String t = timeOfDay == null ? "" : timeOfDay.trim().toLowerCase();
        return t.equals("dusk") || t.equals("sunset") || t.equals("twilight")
                || t.equals("night") || t.equals("evening");
    }

    /** True when a colour-mood phrase asserts daytime light (so it must not be
     *  emitted at a dark time of day). "golden-hour" is the everywhere-default and
     *  is deliberately NOT treated as a daylight clash. */
    private static boolean mentionsDaylight(String colour) {
        if (colour == null || colour.isBlank()) return false;
        String c = colour.toLowerCase();
        return c.contains("daylight") || c.contains("midday") || c.contains("noon")
                || c.contains("sunny") || c.contains("bright day") || c.contains("daytime");
    }

    /** A camera angle stated in the action, mapped to a clean Camera-line angle,
     *  or null when the action states none (keep the preset). Only explicit angle
     *  terms — not bare "looking up/down", which are usually character gaze. */
    private static String describesCameraAngle(String visualDesc) {
        if (visualDesc == null) return null;
        String d = visualDesc.toLowerCase();
        if (d.contains("low angle") || d.contains("low-angle")
                || d.contains("worm's-eye") || d.contains("worms-eye"))
            // Stylized so a hatted character's face/eyes stay readable — a literal
            // low angle hides the eyes under the straw-hat brim (scene-4).
            return "stylized low angle, looking up at the subject while keeping its face "
                    + "and eyes clearly visible and unblocked (a hat brim never covers the eyes)";
        if (d.contains("high angle") || d.contains("high-angle") || d.contains("top-down")
                || d.contains("overhead shot") || d.contains("bird's-eye") || d.contains("birds-eye"))
            return "high angle, looking down at the subject";
        return null;
    }

    private static boolean isLongLens(String lens) {
        String l = lens == null ? "" : lens.toLowerCase();
        return l.contains("85mm") || l.contains("long") || l.contains("portrait");
    }

    private static boolean isFaceOrFlockFocus(String focus) {
        String f = focus == null ? "" : focus.toLowerCase();
        return f.contains("face") || f.contains("eyes") || f.contains("flock");
    }

    /** Whole-word, case-insensitive membership test — so a short character id
     *  like "mo" matches "Mo" / "Mo's" but not "moment" or "smooth". {@code hay}
     *  is expected already lower-cased; blank tokens never match. */
    private static boolean mentionsWord(String hay, String token) {
        if (hay == null || hay.isBlank() || token == null || token.isBlank()) return false;
        return java.util.regex.Pattern
                .compile("\\b" + java.util.regex.Pattern.quote(token.toLowerCase()) + "\\b")
                .matcher(hay).find();
    }

    /** Joins names as a readable list: "A", "A and B", "A, B and C". */
    private static String joinNames(List<String> ns) {
        if (ns == null || ns.isEmpty()) return "";
        if (ns.size() == 1) return ns.get(0);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < ns.size(); i++) {
            if (i > 0) b.append(i == ns.size() - 1 ? " and " : ", ");
            b.append(ns.get(i));
        }
        return b.toString();
    }

    /** Combined DNA identity clause for the characters present in a scene. */
    private String dnaIdentityClause(List<String> charIds) {
        if (charIds == null || charIds.isEmpty()) return "";
        boolean lean = veoLeanPrompts();
        Map<String, String> verbose = characterDnaClauses();
        // Lean mode: prefer the compact dna.veoKey line per character; fall back
        // to the verbose clause only for characters that have no veoKey yet.
        Map<String, String> compact = lean ? characterVeoKeyClauses() : java.util.Map.of();
        StringBuilder b = new StringBuilder();
        for (String id : charIds) {
            String key = id == null ? "" : id.toLowerCase();
            String clause = lean && compact.containsKey(key) ? compact.get(key) : verbose.get(key);
            if (clause != null && !clause.isBlank()) b.append(clause).append(' ');
        }
        if (b.length() == 0) return "";
        // In lean mode the start frame (a reference-conditioned still) is the
        // PRIMARY identity source, so the text just says "match it" instead of
        // re-describing the character — a reference beats hundreds of words.
        String clause = lean
                ? "Character identity — match the start frame EXACTLY (same design, colours and "
                  + "accessories), do not redesign anyone. Key cues: " + b
                : "Character identity (keep EXACT across every frame): " + b;
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

    // ---- Flow / Veo 3.1 NATIVE audio (wordless vocalisations + music) ---------

    /** Master switch for Flow / Veo 3.1 NATIVE audio. When ON, the compiled prompt
     *  drops the "voice is a separate ElevenLabs track / do NOT lip-sync" framing
     *  and instead directs Veo to generate the character vocalisations, ambient
     *  soundscape and music itself (in sync). Absent → false, so the prompt output
     *  is byte-for-byte unchanged until the bible explicitly enables it
     *  ({@code veoNativeAudio: true}). */
    private boolean veoNativeAudio() {
        try {
            return readBible().path("veoNativeAudio").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    /** Cache of character id -> "<Name> with <dna.signatureSound>" (e.g.
     *  "Pip with a bright rising curious chirp"). The channel is wordless
     *  (Pingu / Shaun-the-Sheep), so this signature chick sound — NOT human
     *  speech — is what each character "says" in Flow's native audio. */
    private volatile Map<String, String> signatureSoundCache;

    private Map<String, String> characterSignatureSounds() {
        Map<String, String> c = signatureSoundCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                String name = ch.path("name").asText(id);
                String snd = ch.path("dna").path("signatureSound").asText("").trim();
                if (!id.isBlank() && !snd.isBlank()) out.put(id, name + " with " + snd);
            }
        } catch (Exception e) {
            log.warn("Could not load character signature sounds: {}", e.getMessage());
        }
        signatureSoundCache = out;
        return out;
    }

    /** Buckets free-form episode mood text into the 3 music categories — mirrors
     *  the orchestrator's {@code autoPickMusic} bucketing so the Flow score
     *  direction matches the mood the pipeline picks tracks for. */
    private static String moodBucket(String moodText) {
        if (moodText == null) return "calm";
        String m = moodText.toLowerCase();
        if (m.contains("energetic") || m.contains("adventure") || m.contains("excited")
                || m.contains("playful") || m.contains("chaotic") || m.contains("silly")) return "energetic";
        if (m.contains("thoughtful") || m.contains("curious") || m.contains("wonder")
                || m.contains("discovery") || m.contains("mystery")) return "thoughtful";
        return "calm";   // default / cozy / quiet / bedtime / warm
    }

    /** A Flow music-direction phrase for the episode mood. Deliberately free of
     *  any time-of-day word so it never trips the linter's light/time check. */
    private static String musicDirection(String moodText) {
        return switch (moodBucket(moodText)) {
            case "energetic" -> "a joyful, upbeat orchestral children's score — plucky pizzicato strings, "
                    + "a bouncy ukulele, bright glockenspiel and light hand percussion, warm and playful";
            case "thoughtful" -> "a gentle, curious orchestral children's score — soft music-box glockenspiel, "
                    + "light woodwinds and warm strings, inquisitive and tender";
            default -> "a soft, soothing orchestral children's score — warm strings, mellow piano and "
                    + "a gentle music box, peaceful and cozy";
        };
    }

    /** Location-driven ambient soundscape, keyword-matched on the location id,
     *  plus a weather sound when set. Kept time-of-day free on purpose (linter). */
    private String ambientSound(String locationId, String weather) {
        String id = locationId == null ? "" : locationId.toLowerCase();
        String base;
        if (id.contains("pond") || id.contains("water") || id.contains("stream") || id.contains("puddle"))
            base = "gentle lapping water, soft reed rustle and faint distant birdsong";
        else if (id.contains("coop") || id.contains("barn") || id.contains("nest") || id.contains("hen"))
            base = "cosy soft straw rustle, faint wooden creaks and a muffled, far-off cluck";
        else if (id.contains("forest") || id.contains("wood") || id.contains("tree") || id.contains("oak"))
            base = "soft leaf rustle, a gentle wind through the branches and faint distant birdsong";
        else if (id.contains("garden") || id.contains("meadow") || id.contains("field")
                || id.contains("hill") || id.contains("grass"))
            base = "a soft breeze through grass, gentle buzzing insects and cheerful faint birdsong";
        else
            base = "soft natural outdoor ambience — a light breeze, rustling grass and gentle faint birdsong";
        String w = weather == null ? "" : weather.toLowerCase();
        if (w.contains("rain")) return base + ", soft pattering rain";
        if (w.contains("wind") || w.contains("breez")) return base + ", a soft gusting wind";
        if (w.contains("snow")) return base + ", a soft muffled hush";
        return base;
    }

    /** Wordless character-vocalisation direction for native audio: each present
     *  character "speaks" only in its signature chick sound, carrying the beat
     *  emotion — no human words, so the channel stays language-neutral. */
    private String vocalisationClause(List<String> charIds, String emotion) {
        Map<String, String> sounds = characterSignatureSounds();
        List<String> parts = new ArrayList<>();
        if (charIds != null) {
            for (String id : charIds) {
                String s = sounds.get(id == null ? "" : id.toLowerCase());
                if (s != null && !s.isBlank()) parts.add(s);
            }
        }
        String who = parts.isEmpty() ? "the chick" : joinNames(parts);
        String em = emotion == null ? "" : stripIntensity(emotion).trim();
        String feeling = em.isBlank() ? "" : " that clearly carry " + em;
        return "wordless, expressive chick vocalisations — " + who
                + " — small chirps, peeps and trills" + feeling
                + ", in sync with each beak (NO human words, NO discernible language, no sung lyrics)";
    }

    /** Cache of character id -> display name (bible characters[].name). */
    private volatile Map<String, String> characterNameCache;

    private Map<String, String> characterNames() {
        Map<String, String> c = characterNameCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                String name = ch.path("name").asText("").trim();
                if (!id.isBlank() && !name.isBlank()) out.put(id, name);
            }
        } catch (Exception e) {
            log.warn("Could not load character names: {}", e.getMessage());
        }
        characterNameCache = out;
        return out;
    }

    // ---- Accessory-vs-action guard (bible-driven) -----------------------------

    /** Cache of the per-character accessory facts the {@link AccessoryGuard} needs
     *  (owned categories from dna.accessory, forbidden from dna.antiAccessory, the
     *  rewrite target from dna.signatureAccessoryShort). Built once from the bible. */
    private volatile List<AccessoryGuard.CharModel> accessoryModelCache;

    private List<AccessoryGuard.CharModel> accessoryModels() {
        List<AccessoryGuard.CharModel> cached = accessoryModelCache;
        if (cached != null) return cached;
        List<AccessoryGuard.CharModel> out = new ArrayList<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").trim().toLowerCase();
                if (id.isBlank()) continue;
                String name = ch.path("name").asText(id).trim();
                JsonNode d = ch.path("dna");
                String owned = d.path("accessory").asText("");
                String forbidden = d.path("antiAccessory").asText("");
                String shortAcc = d.path("signatureAccessoryShort").asText("").trim();
                // Pronoun-gender inferred from the bible's tic/sound text (uses
                // his/her/its) so the guard binds a possessive to the right chick.
                String gender = AccessoryGuard.inferGender(
                        d.path("tic").asText(""), d.path("signatureSound").asText(""),
                        ch.path("description").asText(""));
                out.add(new AccessoryGuard.CharModel(id, name, shortAcc,
                        AccessoryGuard.categoriesIn(owned),
                        AccessoryGuard.categoriesIn(forbidden), gender));
            }
        } catch (Exception e) {
            log.warn("Could not load accessory models from bible: {}", e.getMessage());
        }
        accessoryModelCache = out;
        return out;
    }

    // ---- Species-aware roster (chickens vs the duckling) ----------------------

    /** Cache of character id -> roster noun (the word used in the CHARACTER
     *  ROSTER count). Reads characters[].rosterNoun, else characters[].species,
     *  else "chicken". So the duckling reads as "duckling" and is never lumped
     *  in with the chickens — the "EXACTLY 4 CHICKENS" bug where the AI tried to
     *  invent a 4th chicken instead of keeping 3 chickens + 1 duckling. */
    private volatile Map<String, String> rosterNounCache;

    private Map<String, String> rosterNouns() {
        Map<String, String> c = rosterNounCache;
        if (c != null) return c;
        Map<String, String> out = new HashMap<>();
        try {
            for (JsonNode ch : readBible().path("characters")) {
                String id = ch.path("id").asText("").toLowerCase();
                if (id.isBlank()) continue;
                String noun = ch.path("rosterNoun").asText("").trim();
                if (noun.isBlank()) noun = ch.path("species").asText("chicken").trim();
                out.put(id, noun.isBlank() ? "chicken" : noun.toLowerCase());
            }
        } catch (Exception e) {
            log.warn("Could not load character roster nouns: {}", e.getMessage());
        }
        rosterNounCache = out;
        return out;
    }

    /** Groups a cast by roster noun, preserving first-appearance order. */
    private java.util.LinkedHashMap<String, Integer> rosterNounCounts(List<String> ids) {
        Map<String, String> nouns = rosterNouns();
        java.util.LinkedHashMap<String, Integer> counts = new java.util.LinkedHashMap<>();
        for (String id : ids) {
            String noun = nouns.getOrDefault(id == null ? "" : id.toLowerCase(), "chicken");
            counts.merge(noun, 1, Integer::sum);
        }
        return counts;
    }

    private static String plural(String noun, int n) {
        return n == 1 ? noun : noun + "s";
    }

    /** "EXACTLY 1 CHICKEN" | "EXACTLY 2 CHICKENS" | "EXACTLY 3 CHICKENS AND 1
     *  DUCKLING (TOTAL 4 CHARACTERS)". A single-species cast keeps the legacy
     *  wording (golden/lean tests stay byte-identical); a mixed cast spells out
     *  each species AND a TOTAL the linter can read as the headcount. */
    private String rosterLimit(List<String> ids) {
        java.util.LinkedHashMap<String, Integer> counts = rosterNounCounts(ids);
        int total = ids.size();
        StringBuilder b = new StringBuilder("EXACTLY ");
        if (counts.size() <= 1) {
            String noun = counts.isEmpty() ? "chicken" : counts.keySet().iterator().next();
            return b.append(total).append(' ').append(plural(noun, total).toUpperCase()).toString();
        }
        int i = 0, ns = counts.size();
        for (var e : counts.entrySet()) {
            if (i > 0) b.append(i == ns - 1 ? " AND " : ", ");
            b.append(e.getValue()).append(' ').append(plural(e.getKey(), e.getValue()).toUpperCase());
            i++;
        }
        return b.append(" (TOTAL ").append(total).append(" CHARACTERS)").toString();
    }

    /** "Exactly 1 chicken stays" | "Exactly 3 chickens and 1 duckling stay". */
    private String rosterSubject(List<String> ids) {
        java.util.LinkedHashMap<String, Integer> counts = rosterNounCounts(ids);
        StringBuilder b = new StringBuilder("Exactly ");
        int i = 0, ns = counts.size();
        for (var e : counts.entrySet()) {
            if (i > 0) b.append(i == ns - 1 ? " and " : ", ");
            b.append(e.getValue()).append(' ').append(plural(e.getKey(), e.getValue()));
            i++;
        }
        return b.append(ids.size() == 1 ? " stays" : " stay").toString();
    }

    /** Species-aware anti-duplication clause: "no extra chickens anywhere in the
     *  scene (including the background, edges, or bokeh), no fourth chicken" —
     *  one clause per species, joined by "; ". For an all-chicken cast this is
     *  byte-identical to the legacy single-species wording. No leading capital /
     *  trailing period: the caller punctuates. */
    private String antiExtraClause(List<String> ids) {
        java.util.LinkedHashMap<String, Integer> counts = rosterNounCounts(ids);
        StringBuilder b = new StringBuilder();
        int i = 0;
        for (var e : counts.entrySet()) {
            if (i++ > 0) b.append("; ");
            b.append("no extra ").append(plural(e.getKey(), 2))
             .append(" anywhere in the scene (including the background, edges, or bokeh), no ")
             .append(ordinalNext(e.getValue())).append(' ').append(e.getKey());
        }
        return b.toString();
    }

    /** ONE signature gesture per clip. To stop the "everybody performs their tic
     *  at once" overload that morphs limbs in short scenes, only the lead chick
     *  (the first in the cast that has a tic) performs its signature gesture; the
     *  rest hold calm idle motion and must NOT perform their own gesture at the
     *  same time. Empty when no cast member has a tic (nothing to restrict). */
    private String oneSignatureMotion(List<String> ids) {
        if (ids == null || ids.isEmpty()) return "";
        Map<String, String> tics = characterTicClauses();
        Map<String, String> names = characterNames();
        String lead = null;
        for (String id : ids) {
            String key = id == null ? "" : id.toLowerCase();
            if (tics.containsKey(key)) { lead = key; break; }
        }
        if (lead == null) return "";   // no tics in this cast -> no restriction needed
        StringBuilder b = new StringBuilder("Signature character motion (ONE per clip): ")
                .append(tics.get(lead)).append(". ");
        List<String> otherNames = new ArrayList<>();
        for (String id : ids) {
            String key = id == null ? "" : id.toLowerCase();
            if (key.equals(lead)) continue;
            String nm = names.get(key);
            if (nm != null && !nm.isBlank()) otherNames.add(nm);
        }
        if (!otherNames.isEmpty()) {
            b.append("Only ").append(names.getOrDefault(lead, "the lead chick"))
             .append(" performs a signature gesture this clip; ");
            if (otherNames.size() == 1) {
                // Singular subject → singular verb + pronouns (scene-18 grammar fix:
                // "Mo keep calm" was wrong; it also implied more chicks than present).
                b.append(otherNames.get(0))
                 .append(" keeps calm, natural body motion — it still blinks, breathes, reacts and, when it has a line, SPEAKS it with its beak moving normally — but must NOT perform its ");
            } else {
                b.append(joinNames(otherNames))
                 .append(" keep calm, natural body motion — they still blink, breathe, react and, when they have a line, SPEAK it with their beak moving normally — but must NOT perform their ");
            }
            b.append("own signature gesture at the same time - no simultaneous hat-tip, scarf-tug ")
             .append("or glasses-push. ");
        }
        return b.toString();
    }

    /** Builds the spoken-dialogue direction from the scene's lines — each line as
     *  {@code <Name> says, "<text>"} in order (the Veo 3.1 dialogue format).
     *  ENGLISH is enforced by the audio block + the script-generation prompt.
     *  Empty when the scene has no spoken lines (a silent/action beat → the audio
     *  block falls back to wordless chick vocalisations). */
    private String dialogueClause(List<Map<String, Object>> lines) {
        if (lines == null || lines.isEmpty()) return "";
        Map<String, String> names = characterNames();
        StringBuilder b = new StringBuilder();
        for (Map<String, Object> ln : lines) {
            if (ln == null) continue;
            Object tx = ln.get("text");
            if (tx == null) continue;
            String text = tx.toString().trim();
            if (text.isBlank()) continue;
            Object sp = ln.get("speaker");
            String speaker = sp == null ? "" : sp.toString().toLowerCase();
            String name = names.getOrDefault(speaker, "the chick");
            if (b.length() > 0) b.append(' ');
            b.append(name).append(" says, \"").append(text).append('"');
        }
        return b.toString();
    }

    /** The DISTINCT display names of every character that actually has a spoken
     *  line in this scene, in first-appearance order. Used so the beak / lip-sync
     *  direction names the real speakers and tells the model they take TURNS —
     *  instead of the old singular "only the talking chick's beak moves while the
     *  others keep their beaks closed", which silenced a second speaker (e.g. Bo,
     *  who had a line but was lumped in with "the others" and told to stay shut). */
    private List<String> speakingNames(List<Map<String, Object>> lines) {
        List<String> out = new ArrayList<>();
        if (lines == null) return out;
        Map<String, String> names = characterNames();
        for (Map<String, Object> ln : lines) {
            if (ln == null) continue;
            Object tx = ln.get("text");
            if (tx == null || tx.toString().trim().isBlank()) continue;
            Object sp = ln.get("speaker");
            String speaker = sp == null ? "" : sp.toString().toLowerCase();
            String name = names.getOrDefault(speaker, "the chick");
            if (!out.contains(name)) out.add(name);
        }
        return out;
    }

    /** The DISTINCT display names of the chicks present in the scene that do NOT
     *  have a spoken line — i.e. the scene roster minus the speakers. Used to NAME
     *  the silent chicks instead of the old generic plural "the non-speaking
     *  chicks", which (EP3 scene-18 defect) made the model hunt for a third chick
     *  that wasn't in a 2-chick roster. Order = roster order. */
    private List<String> nonSpeakingNames(List<String> ids, List<String> speakerNames) {
        List<String> out = new ArrayList<>();
        if (ids == null) return out;
        Map<String, String> names = characterNames();
        java.util.Set<String> speakers = speakerNames == null
                ? java.util.Set.of() : new java.util.HashSet<>(speakerNames);
        for (String id : ids) {
            String nm = names.get(id == null ? "" : id.toLowerCase());
            if (nm != null && !nm.isBlank() && !speakers.contains(nm) && !out.contains(nm)) out.add(nm);
        }
        return out;
    }

    /** Grammatically-correct "keep(s) ... beak(s) closed" clause for the silent
     *  chicks, NAMED so it matches the exact scene roster count: e.g.
     *  "Mo keeps its beak closed" (one) or "Mo and Bo keep their beaks closed"
     *  (two+). Empty when every present chick has a line (nothing to silence). */
    private String nonSpeakingBeakClause(List<String> ids, List<String> speakerNames) {
        List<String> non = nonSpeakingNames(ids, speakerNames);
        if (non.isEmpty()) return "";
        return non.size() == 1
                ? non.get(0) + " keeps its beak closed"
                : joinNames(non) + " keep their beaks closed";
    }

    /** The full Veo 3.1 native-audio direction block (Google's Dialogue / Ambient
     *  / SFX labelling). When the scene has spoken lines the characters SPEAK them
     *  aloud in clear ENGLISH with lip-sync; with no lines they fall back to
     *  wordless chick sounds. MUSIC is never requested here — the episode's single
     *  track is laid under the whole video in post for consistency. Ends on a full
     *  stop so the prompt still passes the linter's truncation check. */
    private String audioBlock(List<String> charIds, String emotion,
                              String locationId, String weather, String dialogue) {
        String voice;
        if (dialogue != null && !dialogue.isBlank()) {
            voice = "the characters SPEAK their lines out loud in clear, natural, child-friendly "
                  + "ENGLISH with accurate lip-sync — their beaks move to match the words — "
                  + dialogue + ". Every spoken word is ENGLISH, never Dutch or any other language";
        } else {
            voice = vocalisationClause(charIds, emotion);
        }
        return "Audio (generate natively and perfectly in sync): " + voice + ". "
                + "Ambient noise: " + ambientSound(locationId, weather) + ". "
                + "Sound effects: crisp, natural foley matching the on-screen action and surfaces. "
                + "All speech is in ENGLISH only, never Dutch. No background music (the music track "
                + "is added later in post for consistency), no narration, and no on-screen text, captions, "
                + "numbers, digits, timers or timecodes.";
    }

    /** Fixed clip length, in seconds. SINGLE SOURCE for the clip-length wording in
     *  the compiled prompt. MUST match the length the assembly/videogen actually
     *  renders (VideoController floors scene durations to this same value:
     *  {@code Math.max(10, durationSeconds)}). The script schema still allows a
     *  per-scene durationSeconds, but every clip is rendered at this fixed length,
     *  so the prompt states THIS — change it here and the prose follows, no stray
     *  "10"s drifting out of sync (T3). */
    public static final int CLIP_SECONDS = 10;

    /** CLIP_SECONDS spelled out in words ("ten") for the prompt prose. We deliberately
     *  do NOT put the bare digit or a timecode (e.g. "0:10") in the prompt: a video
     *  model can burn a stray number/timer into the frame (the same way "BONK" leaked
     *  as on-screen text). The duration is a directing instruction, not screen content
     *  (N1). Falls back to the digit only for an unmapped custom length. */
    private static String clipSecondsWord() {
        return switch (CLIP_SECONDS) {
            case 5 -> "five"; case 6 -> "six"; case 7 -> "seven"; case 8 -> "eight";
            case 9 -> "nine"; case 10 -> "ten"; case 11 -> "eleven"; case 12 -> "twelve";
            case 15 -> "fifteen"; case 20 -> "twenty";
            default -> String.valueOf(CLIP_SECONDS);
        };
    }

    /** Builds the structured "director's brief" prompt format Auke standardised on:
     *  labelled sections — DIRECTOR'S BRIEF & ENVIRONMENT, CHARACTER ROSTER,
     *  CHRONOLOGICAL ACTION & CAMERA MOVEMENT, AUDIO, ENVIRONMENTAL MOTION &
     *  RESTRICTIONS — with spoken English dialogue + lip-sync. Produced when
     *  veoNativeAudio is on; reuses the same bible-driven content as the legacy
     *  paragraph (camera, location/light, character DNA, cast lock, dialogue,
     *  ambient/SFX), just reorganised. */
    private String directorBrief(String base, String descLc, String phase, List<String> charIds,
                                 String locationId, String timeOfDay, String weather,
                                 String goal, String emotion, String motionSpeed,
                                 String[] cam, String camOverride, String moveDirective,
                                 boolean closeUp, String dialogue, List<String> speakerNames,
                                 String impactSfx) {
        StringBuilder p = new StringBuilder();
        String look = veoLook();
        int speakerCount = speakerNames == null ? 0 : speakerNames.size();

        // 1) DIRECTOR'S BRIEF & ENVIRONMENT
        p.append("DIRECTOR'S BRIEF & ENVIRONMENT\n");
        p.append("- Visual Style: ").append(look);
        if (!look.endsWith(".")) p.append('.');
        p.append('\n');
        // Camera — same shot-aware resolution as the legacy path.
        String cameraStr;
        if (!camOverride.isBlank()) {
            cameraStr = camOverride.replaceAll("[\\s.]+$", "");
        } else {
            String angle = cam[0];
            String va = describesCameraAngle(base);
            if (va != null) angle = va;
            String lens = cam[1];
            String movement = cam.length > 2 ? cam[2] : "";
            String focus = (cam.length > 3 && cam[3] != null) ? cam[3] : "";
            if (describesWideReveal(base)) {
                movement = "slow, steady pull-back that gradually widens the frame to reveal the full scene";
                if (isLongLens(lens)) lens = "35mm wide";
                if (isFaceOrFlockFocus(focus)) focus = "the whole scene readable, the main subject still clear";
            } else if (framesWholeCast(descLc)) {
                if (isLongLens(lens)) lens = "35mm wide";
                if (isFaceOrFlockFocus(focus)) focus = "all framed characters clearly readable together";
            } else if (closeUp) {
                String ll = lens.toLowerCase();
                if (ll.contains("35mm") || ll.contains("24mm") || ll.contains("wide")) lens = "50mm portrait";
                if (isFaceOrFlockFocus(focus) && focus.toLowerCase().contains("flock")) focus = "the framed subject sharp, the rest soft";
            }
            StringBuilder c = new StringBuilder(angle).append(", ").append(lens);
            if (moveDirective.isBlank() && !movement.isBlank()) c.append(", ").append(movement);
            if (!focus.isBlank()) c.append(", ").append(focus);
            if (cam.length > 4 && cam[4] != null && !cam[4].isBlank()) c.append(", ").append(cam[4]);
            cameraStr = c.toString();
        }
        p.append("- Camera Setup: ").append(cameraStr);
        if (!moveDirective.isBlank()) p.append("; ").append(moveDirective.replaceAll("[\\s.]+$", ""));
        p.append(".\n");
        p.append("- Shot Type: single, unbroken continuous shot - no cuts, no scene changes.\n");
        p.append("- Aspect ratio / quality: 16:9 landscape, 1080p, 24fps, fluid animation.\n");
        String loc = locationId == null ? "" : locations().getOrDefault(locationId.toLowerCase(), "");
        p.append("- Setting: ");
        if (closeUp) {
            p.append("the subject in sharp focus, the background melted into soft, unreadable creamy bokeh, ");
        } else if (!loc.isBlank()) {
            loc = loc.replaceAll("[\\s.,;]+$", "");
            if (!loc.isBlank()) p.append(loc).append(", ");
        }
        p.append(lightPhrase(timeOfDay));
        String wx = weatherPhrase(weather);
        if (!wx.isBlank()) p.append(", ").append(wx);
        String colour = colorScriptPhrase(phase);
        // Don't assert a daytime colour mood at a dark time of day — that fought
        // the dusk/night Setting light and flickered (the scene-20 "dusk sky +
        // warm natural daylight" defect). Lock a consistent warm-dusk mood instead.
        if (isDarkTime(timeOfDay) && (colour.isBlank() || mentionsDaylight(colour))) {
            colour = "rich saturated warm dusk light with deep purple-amber tones";
        }
        if (!colour.isBlank()) p.append(". Colour mood: ").append(colour);
        p.append(".\n\n");

        // 2) CHARACTER ROSTER
        List<String> ids = new ArrayList<>();
        if (charIds != null) for (String id : charIds) if (id != null && !id.isBlank()) ids.add(id);
        int n = ids.size();
        boolean mixedCast = rosterNounCounts(ids).size() > 1;
        p.append("CHARACTER ROSTER (STRICT LIMIT: ").append(rosterLimit(ids)).append(")\n");
        p.append(rosterSubject(ids))
         .append(" fully visible from the first to the last frame. No character duplication, ")
         .append(antiExtraClause(ids)).append("; nobody is replaced or swapped.\n");
        Map<String, String> roster = veoLeanPrompts() ? characterVeoKeyClauses() : characterDnaClauses();
        int idx = 1;
        for (String id : ids) {
            String line = roster.get(id.toLowerCase());
            line = (line == null || line.isBlank()) ? id : line.trim();
            p.append(idx++).append(". ").append(line);
            if (!line.endsWith(".")) p.append('.');
            p.append('\n');
        }
        if (n > 1) {
            p.append(mixedCast
                    ? "Each character keeps ONLY its own accessory (hat / scarf / glasses) and its own body colour and species - never move an accessory onto another character, and never turn the duckling into a chicken or vice versa.\n"
                    : "Each chick keeps ONLY its own accessory (hat / scarf / glasses) and its own body colour - never move an accessory onto another chicken.\n");
            String sr = veoScaleRule();
            if (!sr.isBlank()) p.append("Relative size: ").append(sr).append(sr.endsWith(".") ? "\n" : ".\n");
        }
        p.append("Match the start frame exactly (same design, colours and accessories); do not redesign anyone.\n");
        // P1 — the core consistency lock sits HIGH (right after the roster), not
        // only in the trailing Restrictions section: on long prompts the model
        // truncates the tail first, dropping exactly the no-morph/anti-swap lock.
        // One short sentence here guarantees it survives even if the end is cut.
        p.append("HARD IDENTITY LOCK: absolutely no morphing, no flicker, no melting; "
                + "each chick keeps its OWN body colour and its OWN accessory for every frame, "
                + "and accessories are NEVER swapped, shared or added between characters.\n\n");

        // 3) CHRONOLOGICAL ACTION & CAMERA MOVEMENT
        // FIXED 10-SECOND CLIPS (Auke 2026-06-17): every clip runs a full 10s. The
        // old 4-5s beats crammed too much motion into too little time, so Veo/Flow
        // rushed and morphed (liquid limbs). State the 10s window explicitly and
        // direct ONE calm action paced across the whole duration.
        p.append("CHRONOLOGICAL ACTION & CAMERA MOVEMENT (FULL ").append(clipSecondsWord().toUpperCase(java.util.Locale.ROOT)).append("-SECOND CLIP)\n");
        p.append("- Across the whole clip, from the first to the last frame: ");
        if (!base.isBlank()) { p.append(base); if (!base.endsWith(".")) p.append('.'); p.append(' '); }
        if (goal != null && !goal.isBlank()) p.append("Beat goal: ").append(goal.trim()).append(". ");
        String em = emotion == null ? "" : stripIntensity(emotion).trim();
        if (!em.isBlank()) p.append("Performance: clearly convey ").append(em).append(". ");
        if (!dialogue.isBlank()) {
            if (speakerCount >= 2) {
                // MULTI-SPEAKER FIX (Auke 2026-06-17): several chicks have lines in
                // this clip. The old text said "only the talking chick's beak moves"
                // (singular) and lumped every other chick — including a real second
                // speaker like Bo — into "keep their beaks closed", so that speaker
                // never opened its beak. Name the speakers and direct turn-taking.
                p.append("Spoken aloud in clear English, lip-synced — ")
                 .append(joinNames(speakerNames))
                 .append(" each SPEAK their own line in turn, every speaking chick's beak moving accurately to its own words: ")
                 .append(dialogue).append(". ");
            } else {
                p.append("Spoken aloud in clear English, lip-synced (only the speaking chick's beak moves): ")
                 .append(dialogue).append(". ");
            }
        }
        p.append("Small lifelike motion - blinking, soft breathing, slight head and wing movement");
        if (!dialogue.isBlank()) {
            if (speakerCount >= 2) {
                p.append("; each speaking chick lip-syncs its OWN English line in turn, "
                       + "and a chick keeps its beak closed only while it is NOT its turn to speak "
                       + "(every chick that has a line DOES open its beak and talk on that line)");
            } else {
                p.append("; the speaking chick's beak lip-syncs its English words accurately");
                // NAME the silent chick(s) so the wording matches the scene roster
                // EXACTLY (EP3 scene-18: the generic plural "the non-speaking chicks"
                // made the model hunt for a third chick that wasn't in a 2-chick
                // roster). Empty in a solo scene → nothing appended, so we never
                // reference absent chickens.
                String nonSpeaking = nonSpeakingBeakClause(ids, speakerNames);
                if (!nonSpeaking.isBlank()) p.append(" while ").append(nonSpeaking);
            }
        } else {
            p.append("; only small soft beak movements (natural chick sounds, no human-word lip-sync)");
        }
        p.append(". ");
        // Anti-rush pacing (Auke 2026-06-17): the clip is a FULL 10 seconds, so the
        // model must NOT cram or speed up the action. Spread one primary beat calmly
        // across all 10s — rushing is what melts/morphs the bodies.
        p.append("Pace this as ONE calm, continuous ").append(clipSecondsWord()).append("-second beat: a single primary action "
               + "performed slowly and smoothly with natural little pauses, comfortably filling "
               + "the full ").append(clipSecondsWord()).append(" seconds. Do NOT rush, speed up, loop, or stack extra moves to fill "
               + "the time, and never cram several actions together — unhurried motion keeps the "
               + "bodies stable and prevents morphing. ");
        // Suppress the per-clip signature gesture on big whole-body / group beats
        // (tumbling, falling backwards, legs in the air) — adding a fine "leans in
        // and touches her hat" on top of a sprawl contradicts the pose and morphs
        // limbs (scene-23). The body motion already carries the beat.
        String tics = describesWholeBodyMotion(base) ? "" : oneSignatureMotion(ids);
        if (!tics.isBlank()) p.append(tics);
        String surfaceFx = surfacePhrase(locationId, (goal == null ? "" : goal) + " " + base);
        if (!surfaceFx.isBlank()) p.append(surfaceFx);
        p.append("\n\n");

        // 4) AUDIO
        p.append("AUDIO (generate natively and perfectly in sync)\n");
        if (!dialogue.isBlank()) {
            if (speakerCount >= 2) {
                p.append("- Speech: ").append(joinNames(speakerNames))
                 .append(" EACH speak their own line aloud in clear, natural, child-friendly ENGLISH with accurate lip-sync — they take turns, and every chick that has a line opens its beak and talks on that line; a chick keeps its beak closed only while another is speaking. Every spoken word is ENGLISH, never Dutch or any other language. No singing.\n");
            } else {
                // Single speaker: NAME the silent chick(s) so the count matches the
                // roster (scene-18 fix); fall back to the solo phrasing when nobody
                // else is present (never reference absent chickens).
                String nonSpeaking = nonSpeakingBeakClause(ids, speakerNames);
                if (!nonSpeaking.isBlank()) {
                    p.append("- Speech: the speaking chick SPEAKS in clear, natural, child-friendly ENGLISH with accurate lip-sync; its beak moves as it speaks while ")
                     .append(nonSpeaking)
                     .append(". Every spoken word is ENGLISH, never Dutch or any other language. No singing.\n");
                } else {
                    p.append("- Speech: the chick SPEAKS in clear, natural, child-friendly ENGLISH with accurate lip-sync; its beak moves only as it speaks. Every spoken word is ENGLISH, never Dutch or any other language. No singing.\n");
                }
            }
        } else {
            p.append("- Voice: ").append(vocalisationClause(charIds, emotion)).append(".\n");
        }
        p.append("- Ambient: ").append(ambientSound(locationId, weather)).append(".\n");
        p.append("- Sound effects: crisp, natural foley matching the on-screen action and surfaces");
        if (impactSfx != null && !impactSfx.isBlank()) p.append("; ").append(impactSfx);
        p.append(".\n");
        p.append("- No background music (the music track is added later in post for consistency). No narration. "
               + "No on-screen text or captions, and NO on-screen numbers, digits, timers, timecodes or countdowns "
               + "(any timing above is a directing note only — never render it in frame).\n\n");

        // 5) ENVIRONMENTAL MOTION & RESTRICTIONS
        p.append("ENVIRONMENTAL MOTION & RESTRICTIONS\n");
        p.append("- Background Motion: minimal and calm - a soft breeze, a few drifting petals or fireflies, gently swaying grass.\n");
        p.append("- Negative Constraints: No character duplication, ")
         .append(antiExtraClause(ids)).append(". ")
         .append(negativeConstraintsText());
        return p.toString();
    }

    /** Ordinal word for the count just ABOVE the cast size, for the anti-extra
     *  rule ("no fourth chicken" when the cast is exactly 3). */
    private static String ordinalNext(int n) {
        return switch (n + 1) {
            case 2 -> "second";
            case 3 -> "third";
            case 4 -> "fourth";
            case 5 -> "fifth";
            default -> "extra";
        };
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
        return compile(visualDesc, phase, charIds, locationId, timeOfDay, weather,
                goal, emotion, motionSpeed, null);
    }

    /**
     * Same as the 9-arg {@link #compile}, but with an optional {@code cameraMove}
     * id from camera-moves.json. When the id resolves to a known move, its
     * directive REPLACES the phase-default movement (the rest of the Camera line —
     * angle, lens, focus, depth — is kept); when it is blank/unknown the output is
     * byte-for-byte identical to the 9-arg path, so existing scenes are untouched.
     */
    public String compile(String visualDesc, String phase, List<String> charIds,
                          String locationId, String timeOfDay, String weather,
                          String goal, String emotion, String motionSpeed,
                          String cameraMove) {
        return compile(visualDesc, phase, charIds, locationId, timeOfDay, weather,
                goal, emotion, motionSpeed, cameraMove, null);
    }

    /**
     * Same as the 10-arg {@link #compile}, but with an optional per-scene
     * {@code veoCameraOverride}. When non-blank it REPLACES the entire
     * phase-default Camera line (angle, lens, movement, focus, depth) with the
     * override text verbatim — and the {@code cameraMove} directive is skipped so
     * the override is the single source of camera truth. When blank/null the
     * output is byte-for-byte identical to the 10-arg path, so existing scenes
     * are untouched. Used to fix scenes whose visualDesc shot type contradicts
     * the phase preset (e.g. a "Medium shot" beat tagged as the close-up hook).
     */
    public String compile(String visualDesc, String phase, List<String> charIds,
                          String locationId, String timeOfDay, String weather,
                          String goal, String emotion, String motionSpeed,
                          String cameraMove, String veoCameraOverride) {
        return compile(visualDesc, phase, charIds, locationId, timeOfDay, weather,
                goal, emotion, motionSpeed, cameraMove, veoCameraOverride, null);
    }

    /**
     * Same as the 11-arg {@link #compile}, plus an optional {@code musicMood} (the
     * episode mood string the orchestrator uses to pick a music track). When
     * {@code veoNativeAudio} is enabled in the bible, this drives a native
     * Flow / Veo 3.1 audio block (wordless character vocalisations + ambient +
     * mood-matched music) and the legacy "separate voice track / no lip-sync"
     * framing is dropped. When the flag is OFF the output is byte-for-byte
     * identical to the 11-arg path ({@code musicMood} is ignored), so existing
     * scenes and the golden/lean tests are untouched.
     */
    public String compile(String visualDesc, String phase, List<String> charIds,
                          String locationId, String timeOfDay, String weather,
                          String goal, String emotion, String motionSpeed,
                          String cameraMove, String veoCameraOverride, String musicMood) {
        return compile(visualDesc, phase, charIds, locationId, timeOfDay, weather,
                goal, emotion, motionSpeed, cameraMove, veoCameraOverride, musicMood, null);
    }

    /**
     * Same as the 12-arg {@link #compile}, plus the scene's spoken {@code lines}
     * (each a {@code {speaker, text}} map). When {@code veoNativeAudio} is on AND
     * the scene has lines, the characters SPEAK them aloud in clear ENGLISH with
     * lip-sync; with no lines they fall back to wordless chick sounds. {@code
     * musicMood} is now unused (music is laid in post). Flag OFF → byte-identical
     * legacy output, so the golden/lean tests are untouched.
     */
    public String compile(String visualDesc, String phase, List<String> charIds,
                          String locationId, String timeOfDay, String weather,
                          String goal, String emotion, String motionSpeed,
                          String cameraMove, String veoCameraOverride, String musicMood,
                          List<Map<String, Object>> lines) {
        boolean nativeAudio = veoNativeAudio();
        // Impact-onomatopoeia coded as a spoken line (Bo "Bonk!" while plopping
        // onto the egg) is a foley sound, not speech — route it to the SFX layer
        // so the chick doesn't lip-sync a sound effect (EP3 review, scene 8). The
        // physical-impact cues are surfaced for the AUDIO block; the spoken
        // dialogue keeps only the real lines.
        List<Map<String, Object>> spokenLines = OnomatopoeiaGuard.spokenLines(lines);
        String impactSfx = OnomatopoeiaGuard.impactSfxCue(lines);
        String dialogue = dialogueClause(spokenLines);
        List<String> speakerNames = speakingNames(spokenLines);
        String base = visualDesc == null ? "" : visualDesc.trim();
        // Accessory-vs-action guard: rewrite an action that hands a uniquely-owned
        // accessory to the wrong chick (Mo "adjusting his glasses" → his own
        // "thick red knitted scarf"), so the action stops fighting the identity
        // lock the roster/DNA injects below (EP3 review, scene 5).
        if (!base.isBlank()) {
            List<AccessoryGuard.CharModel> models = accessoryModels();
            List<String> accFindings = AccessoryGuard.findContradictions(base, charIds, models);
            if (!accFindings.isEmpty()) {
                log.warn("Accessory-action contradiction auto-corrected in scene action: {}", accFindings);
                base = AccessoryGuard.sanitize(base, charIds, models);
            }
        }
        String descLc = base.toLowerCase();
        String[] cam = cameraSpec(phase);
        String camOverride = veoCameraOverride == null ? "" : veoCameraOverride.trim();
        String moveDirective = cameraMoveDirective(cameraMove);
        // Is this a tight close-up? Derived from the resolved camera (override or
        // phase angle/lens) using the controlled camera vocabulary — so the cast
        // lock can allow off-frame cast members instead of cramming everyone in.
        // ALSO honour a close-up/insert/macro stated in the action itself: the
        // phase camera preset (e.g. 50mm medium) can lag a visualDesc that frames
        // a tight detail ("Extreme close-up of the wings and the egg"), which used
        // to leave the cast lock demanding whole bodies in a shot that only shows
        // a detail — Veo then distorts the cramped-in bodies.
        boolean closeUp = (!camOverride.isBlank() ? camOverride : cam[0] + " " + cam[1])
                .toLowerCase().contains("close-up")
                || describesCloseUp(base);
        // Standardised output (Auke): when native audio is on, emit the structured
        // "director's brief" format — labelled sections (Director's Brief & Environment,
        // Character Roster, Chronological Action, Audio, Restrictions) with spoken
        // English + lip-sync — instead of the legacy flowing paragraph. The legacy
        // paragraph below stays byte-identical when the flag is off (golden tests).
        if (nativeAudio) {
            return directorBrief(base, descLc, phase, charIds, locationId, timeOfDay,
                    weather, goal, emotion, motionSpeed, cam, camOverride, moveDirective,
                    closeUp, dialogue, speakerNames, impactSfx);
        }
        StringBuilder p = new StringBuilder();
        p.append("Animate from the start frame with ").append(pacePhrase(motionSpeed))
         .append(", child-friendly motion. ");
        // FRONT-LOAD THE ACTION (assembly-audit #7): video models weigh early
        // tokens heaviest, and the action — what the scene IS — used to sit
        // halfway down a 200-word prompt behind camera/world/colour clauses.
        // The locks below still follow; they protect, the action directs.
        if (!base.isBlank()) p.append("Action: ").append(base)
                .append(base.endsWith(".") ? " " : ". ");
        // Camera. A per-scene override (scene.veoCameraOverride) wins outright:
        // it REPLACES the whole phase Camera line AND suppresses the cameraMove
        // directive, so a scene whose shot type contradicts the phase preset can
        // state its own framing with no competing instruction.
        if (!camOverride.isBlank()) {
            p.append("Camera: ").append(camOverride);
            if (!camOverride.endsWith(".")) p.append('.');
            p.append(' ');
        } else {
            // Camera (Camera-Bible): angle, lens, movement, focus, depth-of-field —
            // so VEO never guesses what's sharp or how soft the background falls off.
            // When the scene picked an explicit camera move (camera-moves.json), that
            // move drives the motion, so the phase-default movement (cam[2]) is left
            // out of the list to avoid two contradicting movement instructions.
            // Shot-aware preset: the phase camera is a DEFAULT, not a straitjacket.
            // When the action clearly states a shot that contradicts the preset —
            // a wide reveal / pull-back against the climax/closer push-in, or a
            // solo close-up against the resolution "flock" focus — adapt the
            // offending lens/movement/focus so the Camera line stops fighting the
            // action (the scene-22/27/29 defects). With no such signal these are
            // the preset verbatim, so every other scene is byte-for-byte unchanged.
            // Angle: honour a camera angle stated in the action (e.g. "low angle
            // from just behind the egg") over the preset's eye-level — otherwise a
            // low-angle start frame fights an eye-level Camera line (scene-15).
            String angle = cam[0];
            String va = describesCameraAngle(base);
            if (va != null) angle = va;
            String lens = cam[1];
            String movement = cam.length > 2 ? cam[2] : "";
            String focus = (cam.length > 3 && cam[3] != null) ? cam[3] : "";
            if (describesWideReveal(base)) {
                movement = "slow, steady pull-back that gradually widens the frame to reveal the full scene";
                if (isLongLens(lens)) lens = "35mm wide";
                if (isFaceOrFlockFocus(focus)) focus = "the whole scene readable, the main subject still clear";
            } else if (framesWholeCast(descLc)) {
                // A group action ("all three", "the trio") must not get a tight
                // face push-in — widen a long lens and read the whole group so the
                // outer characters don't morph out of the lens (scene-23 tumble).
                if (isLongLens(lens)) lens = "35mm wide";
                if (isFaceOrFlockFocus(focus)) focus = "all framed characters clearly readable together";
            } else if (closeUp) {
                // A wide preset lens (35mm/24mm) on a close-up distorts the face
                // (GoPro/fisheye bulge) and over-sharpens the background — a close-up
                // wants a ~50mm portrait lens (scene-3).
                String ll = lens.toLowerCase();
                if (ll.contains("35mm") || ll.contains("24mm") || ll.contains("wide"))
                    lens = "50mm portrait";
                if (isFaceOrFlockFocus(focus) && focus.toLowerCase().contains("flock"))
                    focus = "the framed subject sharp, the rest soft";
            }
            p.append("Camera: ").append(angle).append(", ").append(lens);
            if (moveDirective.isBlank() && !movement.isBlank()) p.append(", ").append(movement);
            if (!focus.isBlank()) p.append(", ").append(focus);
            if (cam.length > 4 && cam[4] != null && !cam[4].isBlank()) p.append(", ").append(cam[4]);
            p.append(". ");
            // Explicit camera move (camera-moves.json) — overrides the phase-default
            // movement with a deliberate cinematic move chosen for this beat.
            if (!moveDirective.isBlank()) {
                p.append("Camera move: ").append(moveDirective);
                if (!moveDirective.endsWith(".")) p.append('.');
                p.append(' ');
            }
        }
        // World context (so the animation keeps the still's setting + light)
        String loc = locationId == null ? "" : locations().getOrDefault(locationId.toLowerCase(), "");
        p.append("Setting: ");
        if (closeUp) {
            // Shot-aware setting: a close-up / macro background is all soft bokeh, so
            // dumping the full wide-shot location detail (wheelbarrow, fence, distant
            // hills) over-stuffs the prompt and pushes Veo to widen out to fit it all
            // (the scene-1 macro defect). The START STILL already carries the location
            // identity; here we just keep the foreground sharp and the rest unreadable.
            p.append("the subject in sharp focus, the background melted into soft, "
                   + "unreadable creamy bokeh, ");
        } else if (!loc.isBlank()) {
            // Strip trailing sentence punctuation so a description that ends in a
            // period doesn't produce "...world., warm light." — the location reads
            // as one clause that flows into the light phrase. Generic: works for
            // every location no matter how the bible text happens to be punctuated.
            loc = loc.replaceAll("[\\s.,;]+$", "");
            if (!loc.isBlank()) p.append(loc).append(", ");
        }
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
                 // Body-specific cues (eyes widening, body tensing) are gated on a
                 // character actually being framed at that beat — on an object-subject
                 // shot (a wobbling egg) or a beat where the character only rises into
                 // frame afterwards, forcing "eyes widening" produced nonsense (scene-1).
                 .append(" — a held breath and a micro-stillness, and (whenever a "
                        + "character's face is already in the frame) widening eyes and a "
                        + "sharp little intake — then let the beat land. ");
            }
        }
        // G2 — ground physics, only on contact beats (dig/hop/splash/…).
        String surfaceFx = surfacePhrase(locationId,
                (goal == null ? "" : goal) + " " + base);
        if (!surfaceFx.isBlank()) p.append(surfaceFx);
        // Impact onomatopoeia lifted out of the spoken dialogue (e.g. Bo's "Bonk!")
        // belongs in the foley layer, not lip-synced — surface it here in the legacy
        // path too (the native-audio path handles it in its AUDIO block).
        if (impactSfx != null && !impactSfx.isBlank()) p.append("Sound effects: ").append(impactSfx).append(". ");
        // Full character DNA identity — the SAME canonical fields the image
        // provider locks (PromptComposer.dnaLine), so nothing the still nailed
        // (colour, accessory, silhouette, feathers, build, weight, eyes) drifts
        // once Veo starts moving the frame. One source: bible characters[].dna.
        String identity = dnaIdentityClause(charIds);
        if (!identity.isBlank()) p.append(identity);
        // G7 — headcount + presence lock: exact cast count, everyone stays in
        // frame, nobody new enters. Mirrors PromptComposer's "exactly N chicks"
        // still-side lock so the guarantee survives the animation step.
        String headcount = headcountLockClause(charIds, closeUp, base);
        if (!headcount.isBlank()) p.append(headcount);
        // G1 — scale lock: keep body size consistent relative to the world so a
        // chick never changes size between shots (one sentence for the flock).
        String scale = scaleLockClause(charIds);
        if (!scale.isBlank()) p.append(scale);
        // (Scene action moved to the TOP of the prompt — audit #7 front-load.)
        // Lifelike micro-motion + ambient.
        // P5 — beak / lip-sync handling depends on where the audio comes from:
        //  • native-audio (Flow / Veo 3.1) ON → Veo generates the chick sounds
        //    itself, so the beak should open and move naturally IN TIME with those
        //    vocalisations (a closed beak would now look wrong against the audio);
        //  • OFF (legacy) → the voice is a separate ElevenLabs track, so a beak
        //    "moving while speaking" lands mis-timed against the words; a calm,
        //    mostly-closed beak reads better than a wrongly-timed open one.
        if (nativeAudio && !dialogue.isBlank()) {
            // Spoken dialogue: the speaking character's beak must lip-sync the
            // actual English words accurately (this is what makes the speech read).
            p.append("The characters move with small lifelike motion — blinking, soft breathing, "
                    + "slight head and wing movement; the character who is talking opens and moves "
                    + "its beak to LIP-SYNC its spoken English words accurately and naturally, while "
                    + "the others react and listen — with soft ambient life (drifting petals or "
                    + "fireflies, gently swaying grass). ");
        } else if (nativeAudio) {
            p.append("The characters move with small lifelike motion — blinking, soft breathing, "
                    + "slight head and wing movement, their beaks opening and moving naturally in "
                    + "time with their own chirps and peeps (natural chick-sound motion, not "
                    + "human-word lip-sync) — with soft ambient life (drifting petals or fireflies, "
                    + "gently swaying grass). ");
        } else {
            p.append("The characters move with small lifelike motion — blinking, soft breathing, "
                    + "slight head and wing movement, with only small soft beak movements (do NOT "
                    + "lip-sync words — keep the beak mostly closed with subtle motion) — with soft "
                    + "ambient life (drifting petals or fireflies, gently swaying grass). ");
        }
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
        // Flow / Veo 3.1 NATIVE audio — wordless character vocalisations, a
        // location-driven ambient soundscape and a mood-matched music score, all
        // generated by Veo itself and synced to the picture. Appended only when
        // veoNativeAudio is enabled; ends on a full stop (linter truncation check).
        if (nativeAudio) {
            p.append(' ').append(audioBlock(charIds, emotion, locationId, weather, dialogue));
        }
        return p.toString();
    }
}
