package com.youtubeauto.orchestrator.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Pixar-audit Story F — a DETERMINISTIC consistency check over the assembled
 * script (no LLM, no pixels, no cost). It complements the vision-based
 * {@link com.youtubeauto.orchestrator.service.SceneImageQc} (which judges single
 * stills) by catching CROSS-SCENE continuity bugs that only show up when you read
 * the whole episode at once — most importantly the "a named prop changes colour
 * between scenes" bug the channel actually hit (a watering can that's green in one
 * shot and grey in the next).
 *
 * Philosophy: this scorer is deliberately CONSERVATIVE. It emits warnings and
 * nudges the Characters axis down a little; it never hard-blocks publishing
 * (false positives would silently stall the pipeline). Treat its notes as a
 * "continuity supervisor's" list to eyeball.
 *
 * Checks:
 *  1. Cast sanity        — scene.characters that aren't real bible character ids.
 *  2. Prop-colour drift  — the SAME prop noun described with two different colours
 *                          across scenes (accessories + body/world nouns excluded,
 *                          because a red scarf vs a green scarf is two characters,
 *                          not a continuity error).
 *  3. Accessory reinforce — a present character whose signature accessory keyword
 *                          never appears in its scene text (a soft prompt nudge).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConsistencyChecker {

    private final OrchestratorProperties props;
    private final YAMLMapper yaml = new YAMLMapper();

    public record Result(int score, List<String> notes) {}

    // Colour vocabulary for the prop-drift scan.
    private static final Set<String> COLOURS = Set.of(
            "red", "orange", "yellow", "green", "blue", "purple", "pink", "brown",
            "tan", "grey", "gray", "white", "black", "golden", "gold", "silver",
            "cream", "beige", "teal", "turquoise", "lilac", "violet", "scarlet");

    // Nouns that legitimately come in different colours per character or per
    // instance — excluded so we don't false-flag them as continuity errors.
    private static final Set<String> EXCLUDED_NOUNS = Set.of(
            // accessories (differ per chick on purpose)
            "scarf", "bandana", "kerchief", "hat", "glasses", "eyeglasses", "comb",
            // body / feathers
            "down", "feather", "feathers", "chick", "chicks", "chicken", "chickens",
            "beak", "legs", "leg", "eyes", "eye", "wing", "wings", "body", "tuft",
            // nature / world (naturally multi-coloured, not a prop)
            "flower", "flowers", "petal", "petals", "butterfly", "butterflies",
            "light", "glow", "sky", "hills", "hill", "grass", "leaves", "leaf",
            "sun", "cloud", "clouds", "water", "field", "fields", "background");

    // Word tokenizer — lower-case letter runs only (so "blue-grey" → blue, grey
    // and punctuation is dropped). A colour immediately followed (skipping any
    // further colour words of a compound) by a non-excluded noun is a prop mention.
    private static final Pattern WORD = Pattern.compile("[a-z]+");

    private volatile Map<String, String[]> bibleCharsCache; // id -> [accessoryKeyword(s)]

    /**
     * @param scenes assembled scenes (each carries characters, visualDesc,
     *               motionDesc, narration).
     */
    public Result evaluate(List<Map<String, Object>> scenes) {
        if (scenes == null || scenes.isEmpty()) return new Result(70, List.of("No scenes to check"));
        Map<String, String[]> chars = bibleChars();
        List<String> notes = new ArrayList<>();
        int penalty = 0;

        // 1. Cast sanity --------------------------------------------------------
        for (Map<String, Object> s : scenes) {
            for (String id : charIds(s)) {
                if (!chars.containsKey(id)) {
                    notes.add("Scene " + seq(s) + ": unknown character id '" + id + "'");
                    penalty += 4;
                }
            }
        }

        // 2. Prop-colour drift across scenes -----------------------------------
        // noun -> (colour -> first scene seq it was seen in)
        Map<String, Map<String, Integer>> propColours = new HashMap<>();
        for (Map<String, Object> s : scenes) {
            List<String> ws = tokens(sceneText(s));
            for (int i = 0; i < ws.size() - 1; i++) {
                String w = ws.get(i);
                // Start a colour run only at its FIRST colour word, so a compound
                // colour ("blue-grey wagon") records once, not once per component.
                if (!COLOURS.contains(w)) continue;
                if (i > 0 && COLOURS.contains(ws.get(i - 1))) continue;
                int j = i + 1;
                while (j < ws.size() && COLOURS.contains(ws.get(j))) j++;
                if (j >= ws.size()) continue;
                String noun = ws.get(j);
                if (noun.length() < 3 || EXCLUDED_NOUNS.contains(noun)) continue;
                propColours.computeIfAbsent(noun, k -> new LinkedHashMap<>())
                        .putIfAbsent(normaliseColour(w), seq(s));
            }
        }
        for (Map.Entry<String, Map<String, Integer>> e : propColours.entrySet()) {
            if (e.getValue().size() >= 2) {
                String noun = e.getKey();
                String detail = e.getValue().entrySet().stream()
                        .map(c -> c.getKey() + " (scene " + c.getValue() + ")")
                        .reduce((a, b) -> a + ", " + b).orElse("");
                notes.add("Prop colour drift — '" + noun + "' described as " + detail
                        + ". Lock one colour for continuity.");
                penalty += 8;
            }
        }

        // 3. Accessory reinforcement (soft nudge) ------------------------------
        int missingReinforce = 0;
        for (Map<String, Object> s : scenes) {
            String text = sceneText(s).toLowerCase();
            for (String id : charIds(s)) {
                String[] kw = chars.get(id);
                if (kw == null || kw.length == 0) continue;
                boolean any = false;
                for (String k : kw) if (!k.isBlank() && text.contains(k)) { any = true; break; }
                if (!any) missingReinforce++;
            }
        }
        if (missingReinforce > 0) {
            notes.add(missingReinforce + " character-appearance(s) don't name the signature "
                    + "accessory in the scene text (prompt could drift; not fatal).");
            penalty += Math.min(12, missingReinforce); // capped soft nudge
        }

        int score = Math.max(0, Math.min(100, 100 - penalty));
        if (notes.isEmpty()) notes.add("No cross-scene continuity issues detected");
        return new Result(score, notes);
    }

    private String normaliseColour(String c) {
        return switch (c) {
            case "gray" -> "grey";
            case "gold" -> "golden";
            default -> c;
        };
    }

    /** id -> array of accessory keywords (from bible dna.accessory), lower-case. */
    private Map<String, String[]> bibleChars() {
        Map<String, String[]> cached = bibleCharsCache;
        if (cached != null) return cached;
        Map<String, String[]> out = new HashMap<>();
        try {
            Path p = Paths.get(props.bible().path());
            if (Files.exists(p)) {
                JsonNode root = yaml.readTree(p.toFile());
                for (JsonNode ch : root.path("characters")) {
                    String id = ch.path("id").asText("").toLowerCase();
                    if (id.isBlank()) continue;
                    String acc = ch.path("dna").path("accessory").asText("").toLowerCase();
                    // keyword tokens that signal the accessory is present in text
                    List<String> kw = new ArrayList<>();
                    for (String key : new String[]{"hat", "bandana", "scarf", "glasses",
                            "eyeglasses", "kerchief"}) {
                        if (acc.contains(key)) kw.add(key);
                    }
                    out.put(id, kw.toArray(new String[0]));
                }
            }
        } catch (Exception e) {
            log.warn("ConsistencyChecker could not load bible characters: {}", e.getMessage());
        }
        bibleCharsCache = out;
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> charIds(Map<String, Object> s) {
        Object c = s.get("characters");
        if (c instanceof List<?> l) {
            List<String> out = new ArrayList<>();
            for (Object o : l) if (o != null) out.add(o.toString().toLowerCase());
            return out;
        }
        return List.of();
    }

    private static String sceneText(Map<String, Object> s) {
        return str(s.get("visualDesc")) + " " + str(s.get("motionDesc")) + " " + str(s.get("narration"));
    }

    /** Lower-case letter-run tokens (drops punctuation; splits hyphens). */
    private static List<String> tokens(String text) {
        List<String> out = new ArrayList<>();
        var m = WORD.matcher(text.toLowerCase());
        while (m.find()) out.add(m.group());
        return out;
    }

    private static int seq(Map<String, Object> s) {
        Object v = s.get("seq");
        return v instanceof Number n ? n.intValue() : 0;
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
}
