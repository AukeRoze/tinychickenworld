package com.youtubeauto.script.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.script.anthropic.GeneratedScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Deterministic ACCESSORY-OWNERSHIP gate at the SCRIPT source (R1). The
 * orchestrator's compile-time AccessoryGuard auto-rewrites the common possessive
 * form ("Mo … his glasses") in both the image and Veo prompt, but it is a
 * backstop: it only fixes the rendered prompt, only the possessive phrasing, and
 * silently. This validator is the PRIMARY prevention — it re-prompts the LLM to
 * author the scene correctly, and it also catches the NAME-possessive form the
 * guard misses ("Mo's spectacles", "Bo's hat").
 *
 * <p>Bible-driven (reads {@code characters[].dna} directly, like the orchestrator
 * guard): a character may only interact with an accessory it OWNS; an accessory it
 * FORBIDS and does not own is a contradiction with the DNA never-wear lock and
 * morphs the character. Never blocks the final attempt — the guard + human gate
 * remain the backstop. Mirrors the orchestrator AccessoryGuard category/gender
 * logic; keep them in sync (see GUARD-PARITY note).
 */
@Slf4j
@Component
public class AccessoryValidator {

    @Value("${app.bible.path:./bible/channel.yml}")
    private String biblePath;

    private final YAMLMapper yaml = new YAMLMapper();

    // Package-private so the parity test can build models in-memory (GUARD-PARITY).
    record Cm(String id, String name, String signatureShort,
              Set<String> owned, Set<String> forbidden, String gender) {}

    public record Result(List<String> violations) {
        public boolean failed() { return !violations.isEmpty(); }
    }

    // Canonical accessory categories — keep identical to the orchestrator guard.
    private static final Map<String, Pattern> CATEGORY = new LinkedHashMap<>();
    static {
        CATEGORY.put("glasses", Pattern.compile("eye-?glasses|glasses|spectacles|specs", Pattern.CASE_INSENSITIVE));
        CATEGORY.put("bandana", Pattern.compile("bandana|kerchief", Pattern.CASE_INSENSITIVE));
        CATEGORY.put("hat",     Pattern.compile("\\bhat\\b", Pattern.CASE_INSENSITIVE));
        CATEGORY.put("scarf",   Pattern.compile("\\bscarf\\b", Pattern.CASE_INSENSITIVE));
    }
    private static final String ACC_ALT = "eye-?glasses|glasses|spectacles|specs|bandana|kerchief|hat|scarf";
    private static final Pattern POSSESSIVE = Pattern.compile(
            "\\b(his|her|its)\\s+((?:[a-z][a-z'-]*\\s+){0,3}?)(" + ACC_ALT + ")\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;])\\s+");

    public Result validate(GeneratedScript script) {
        List<String> v = new ArrayList<>();
        List<GeneratedScript.Scene> scenes = script == null ? List.of() : script.scenes();
        List<Cm> models = models();
        if (scenes == null || scenes.isEmpty() || models.isEmpty()) return new Result(v);

        for (GeneratedScript.Scene s : scenes) {
            for (String msg : contradictionsInText(s.visualDesc(), models)) {
                v.add("scene " + s.seq() + ": " + msg);
            }
        }
        return new Result(v);
    }

    /**
     * PURE detection over one visualDesc, given the cast models — no I/O, no
     * Spring, so the parity test ({@code AccessoryValidatorParityTest}) can lock
     * it to the SAME canonical cases as the orchestrator/image AccessoryGuard.
     * Catches both "his/her/its &lt;accessory&gt;" (gender-aware subject) and
     * "&lt;Name&gt;'s &lt;accessory&gt;" (the form the compile-time guard misses).
     */
    static List<String> contradictionsInText(String desc, List<Cm> models) {
        List<String> out = new ArrayList<>();
        if (desc == null || desc.isBlank() || models == null || models.isEmpty()) return out;

        Set<String> tokens = new HashSet<>();
        for (Cm c : models) { if (!c.name().isBlank()) tokens.add(c.name()); if (!c.id().isBlank()) tokens.add(c.id()); }
        String nameAlt = tokens.stream().map(t -> Pattern.quote(t.toLowerCase(Locale.ROOT)))
                .reduce((a, b) -> a + "|" + b).orElse("");
        Pattern namePoss = nameAlt.isBlank() ? null : Pattern.compile(
                "\\b(" + nameAlt + ")(?:'s|’s)\\s+((?:[a-z][a-z'-]*\\s+){0,3}?)(" + ACC_ALT + ")\\b",
                Pattern.CASE_INSENSITIVE);

        for (String sentence : SENTENCE_SPLIT.split(desc, -1)) {
            Matcher pm = POSSESSIVE.matcher(sentence);
            while (pm.find()) {
                String cat = categoryOf(pm.group(3));
                Cm subj = resolveSubject(sentence, pm.group(1), models);
                if (isContradiction(subj, cat)) out.add(message(subj, pm.group().trim(), cat));
            }
            if (namePoss != null) {
                Matcher nm = namePoss.matcher(sentence);
                while (nm.find()) {
                    String cat = categoryOf(nm.group(3));
                    Cm subj = byToken(nm.group(1), models);
                    if (isContradiction(subj, cat)) out.add(message(subj, nm.group().trim(), cat));
                }
            }
        }
        return out;
    }

    private static String message(Cm subj, String phrase, String cat) {
        String fix = subj.signatureShort().isBlank() ? "its own signature accessory" : subj.signatureShort();
        return String.format("\"%s\" gives %s a %s, but %s must NEVER wear %s (it is another "
                + "chick's signature accessory) — the prompt also hard-locks that, so the model morphs %s. "
                + "Have %s interact with %s's own %s instead, or give a neutral beat (a head tilt).",
                phrase, subj.name(), cat, subj.name(), cat, subj.name(), subj.name(), subj.name(), fix);
    }

    private static boolean isContradiction(Cm subj, String cat) {
        return subj != null && cat != null && subj.forbidden().contains(cat) && !subj.owned().contains(cat);
    }

    private static Cm resolveSubject(String sentence, String poss, List<Cm> all) {
        String hay = sentence.toLowerCase(Locale.ROOT);
        String want = switch (poss == null ? "" : poss.toLowerCase(Locale.ROOT)) {
            case "his" -> "he"; case "her" -> "she"; case "its" -> "it"; default -> ""; };
        Cm firstAny = null; int firstAnyIdx = Integer.MAX_VALUE;
        Cm firstMatch = null; int firstMatchIdx = Integer.MAX_VALUE;
        for (Cm c : all) {
            int idx = firstIndexOfName(hay, c);
            if (idx < 0) continue;
            if (idx < firstAnyIdx) { firstAnyIdx = idx; firstAny = c; }
            if ((want.isEmpty() || want.equals(c.gender())) && idx < firstMatchIdx) { firstMatchIdx = idx; firstMatch = c; }
        }
        return firstMatch != null ? firstMatch : firstAny;
    }

    private static int firstIndexOfName(String hay, Cm c) {
        int best = -1;
        for (String token : new String[]{c.name(), c.id()}) {
            if (token == null || token.isBlank()) continue;
            Matcher m = Pattern.compile("\\b" + Pattern.quote(token.toLowerCase(Locale.ROOT)) + "\\b").matcher(hay);
            if (m.find()) { int idx = m.start(); if (best < 0 || idx < best) best = idx; }
        }
        return best;
    }

    private static Cm byToken(String token, List<Cm> all) {
        String t = token == null ? "" : token.toLowerCase(Locale.ROOT);
        for (Cm c : all) {
            if (t.equalsIgnoreCase(c.name()) || t.equalsIgnoreCase(c.id())) return c;
        }
        return null;
    }

    private static String categoryOf(String keyword) {
        for (var e : CATEGORY.entrySet()) if (e.getValue().matcher(keyword).find()) return e.getKey();
        return null;
    }

    private static Set<String> categoriesIn(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;
        for (var e : CATEGORY.entrySet()) if (e.getValue().matcher(text).find()) out.add(e.getKey());
        return out;
    }

    private static String inferGender(String... texts) {
        int he = 0, she = 0, it = 0;
        for (String t : texts) {
            if (t == null) continue;
            String s = " " + t.toLowerCase(Locale.ROOT) + " ";
            he += occ(s, " his ") + occ(s, " himself ") + occ(s, " he ");
            she += occ(s, " her ") + occ(s, " herself ") + occ(s, " she ");
            it += occ(s, " its ") + occ(s, " itself ");
        }
        if (he == 0 && she == 0 && it == 0) return "";
        if (he >= she && he >= it) return "he";
        if (she >= he && she >= it) return "she";
        return "it";
    }

    private static int occ(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length() - 1; }
        return n;
    }

    /** Reads accessory facts fresh from the bible each call (no cache) so a hot
     *  bible reload (/api/v1/bible/reload) is reflected immediately — the
     *  validator runs only a few times per job, not on a hot path, so re-parsing
     *  a small YAML is negligible and avoids the stale-cache class of bug. */
    private List<Cm> models() {
        List<Cm> out = new ArrayList<>();
        try {
            Path p = Paths.get(biblePath);
            if (Files.exists(p)) {
                JsonNode root = yaml.readTree(p.toFile());
                for (JsonNode ch : root.path("characters")) {
                    String id = ch.path("id").asText("").trim().toLowerCase();
                    if (id.isBlank()) continue;
                    JsonNode d = ch.path("dna");
                    out.add(new Cm(id, ch.path("name").asText(id).trim(),
                            d.path("signatureAccessoryShort").asText("").trim(),
                            categoriesIn(d.path("accessory").asText("")),
                            categoriesIn(d.path("antiAccessory").asText("")),
                            inferGender(d.path("tic").asText(""), d.path("signatureSound").asText(""),
                                    ch.path("description").asText(""))));
                }
            }
        } catch (Exception e) {
            log.warn("AccessoryValidator could not load bible accessory data: {}", e.getMessage());
        }
        return out;
    }
}
