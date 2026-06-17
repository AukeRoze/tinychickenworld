package com.youtubeauto.image.service;

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
 * Deterministic ACCESSORY-vs-action contradiction guard for the IMAGE prompt.
 * Pure functions, driven entirely by the bible's per-character accessory data.
 *
 * <p>Sibling of the orchestrator's {@code AccessoryGuard} (separate Maven
 * module, no shared lib — same contract, keep them in sync). The defect it fixes
 * (EP3 review, scene 5): the action text said "<b>Mo</b> … adjusting <b>his
 * glasses</b>" while the same prompt's CHARACTER DNA locks "Mo must NEVER wear
 * glasses" (glasses are <b>Bo's</b>). An image model has no timeline, so it
 * hallucinates a morphing pair of glasses onto Mo or copies Bo's whole look.
 *
 * <p>Because each accessory is owned by exactly one chick ({@code dna.accessory})
 * and forbidden on the others ({@code dna.antiAccessory}), the contradiction is
 * 100% machine-detectable. {@link #sanitize} rewrites the offending accessory to
 * the acting character's own signature accessory; it only acts on a POSSESSIVE
 * accessory phrase whose nearest preceding subject is a character the bible
 * forbids that accessory on, so it never false-positives on a legitimate
 * self-reference ("Bo pushes her glasses up") or a shared accessory ("scarf").
 */
public final class AccessoryGuard {

    private AccessoryGuard() {}

    public record CharModel(String id, String name, String signatureShort,
                            Set<String> ownedCategories, Set<String> forbiddenCategories,
                            String gender) {
        public CharModel(String id, String name, String signatureShort,
                         Set<String> ownedCategories, Set<String> forbiddenCategories) {
            this(id, name, signatureShort, ownedCategories, forbiddenCategories, "");
        }
    }

    /** Infers a character's pronoun gender from text that uses his/her/its
     *  (e.g. the bible's dna.tic). "" when unknown → permissive matching. */
    public static String inferGender(String... texts) {
        int he = 0, she = 0, it = 0;
        for (String t : texts) {
            if (t == null) continue;
            String s = " " + t.toLowerCase(Locale.ROOT) + " ";
            he  += count(s, " his ") + count(s, " himself ") + count(s, " he ");
            she += count(s, " her ") + count(s, " herself ") + count(s, " she ");
            it  += count(s, " its ") + count(s, " itself ");
        }
        if (he == 0 && she == 0 && it == 0) return "";
        if (he >= she && he >= it) return "he";
        if (she >= he && she >= it) return "she";
        return "it";
    }

    private static int count(String hay, String needle) {
        int n = 0, i = 0;
        while ((i = hay.indexOf(needle, i)) >= 0) { n++; i += needle.length() - 1; }
        return n;
    }

    private static String wantGender(String possessive) {
        String p = possessive == null ? "" : possessive.toLowerCase(Locale.ROOT);
        return switch (p) { case "his" -> "he"; case "her" -> "she"; case "its" -> "it"; default -> ""; };
    }

    private static final Map<String, Pattern> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    static {
        CATEGORY_KEYWORDS.put("glasses", Pattern.compile("eye-?glasses|glasses|spectacles|specs", Pattern.CASE_INSENSITIVE));
        CATEGORY_KEYWORDS.put("bandana", Pattern.compile("bandana|kerchief", Pattern.CASE_INSENSITIVE));
        CATEGORY_KEYWORDS.put("hat",     Pattern.compile("\\bhat\\b", Pattern.CASE_INSENSITIVE));
        CATEGORY_KEYWORDS.put("scarf",   Pattern.compile("\\bscarf\\b", Pattern.CASE_INSENSITIVE));
    }

    private static final Pattern POSSESSIVE_ACCESSORY = Pattern.compile(
            "\\b(his|her|its)\\s+((?:[a-z][a-z'-]*\\s+){0,3}?)"
            + "(eye-?glasses|glasses|spectacles|specs|bandana|kerchief|hat|scarf)\\b",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;])\\s+");

    // Worn-state words proving an accessory is ON a body without a possessive
    // ("glasses askew"). EP3 scene-7 defect class: "Mo toppling with glasses
    // askew" — no "his", so the possessive pattern misses it. KEEP IN SYNC with
    // orchestrator AccessoryGuard.
    private static final String WORN_STATE =
            "askew|crooked|cock-?eyed|skewed|tilted|lopsided|slipping|sliding|slid|"
          + "slipped|perched|balanced|fogging|fogged|steamed|bouncing|wobbling|"
          + "flying|knocked|jostled|shaking|dangling";

    // Non-possessive worn-accessory phrase, two TIGHT alternatives (the adjective
    // run must never swallow the subject): (a) prep-anchored with/wearing/in +
    // ≤3 non-possessive adjectives + noun + optional displacement → g1 lead, g2
    // adj, g3 noun, g4 state; (b) displacement-anchored noun + required state →
    // g5 noun, g6 state. KEEP IN SYNC with orchestrator AccessoryGuard.
    private static final String ACC_NOUN =
            "eye-?glasses|glasses|spectacles|specs|bandana|kerchief|hat|scarf";
    private static final Pattern NONPOSSESSIVE_ACCESSORY = Pattern.compile(
            "\\b(?:(with|wearing|in)\\s+((?:(?!his\\b|her\\b|its\\b)[a-z][a-z'-]*\\s+){0,3}?)"
          + "(" + ACC_NOUN + ")\\b(?:\\s+(" + WORN_STATE + "))?"
          + "|(" + ACC_NOUN + ")\\b\\s+(" + WORN_STATE + "))",
            Pattern.CASE_INSENSITIVE);

    public static Set<String> categoriesIn(String text) {
        Set<String> out = new HashSet<>();
        if (text == null || text.isBlank()) return out;
        for (var e : CATEGORY_KEYWORDS.entrySet()) {
            if (e.getValue().matcher(text).find()) out.add(e.getKey());
        }
        return out;
    }

    private static String categoryOf(String keyword) {
        for (var e : CATEGORY_KEYWORDS.entrySet()) {
            if (e.getValue().matcher(keyword).find()) return e.getKey();
        }
        return null;
    }

    public static String sanitize(String desc, List<String> sceneCharIds, List<CharModel> all) {
        if (desc == null || desc.isBlank() || all == null || all.isEmpty()) return desc;
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String sentence : SENTENCE_SPLIT.split(desc, -1)) {
            if (!first) out.append(' ');
            first = false;
            // Pass 1 = possessive; pass 2 = non-possessive worn attribution.
            String s = rewriteSentence(sentence, all);
            s = rewriteWornNonPossessive(s, all);
            out.append(s);
        }
        return out.toString();
    }

    private static String rewriteSentence(String sentence, List<CharModel> all) {
        Matcher m = POSSESSIVE_ACCESSORY.matcher(sentence);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String poss = m.group(1);
            String category = categoryOf(m.group(3));
            CharModel subject = resolveSubject(sentence, poss, all);
            boolean rewrite = isContradiction(subject, category)
                    && subject.signatureShort() != null && !subject.signatureShort().isBlank();
            String repl = rewrite ? poss + " " + subject.signatureShort() : m.group();
            m.appendReplacement(sb, Matcher.quoteReplacement(repl));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Rewrites a non-possessive worn-accessory contradiction ("Mo toppling with
     *  glasses askew") to the subject's own signature accessory, bound to the
     *  NEAREST PRECEDING named character. Only acts when there is worn-evidence
     *  (a wear-preposition or a displacement word). KEEP IN SYNC with orchestrator. */
    private static String rewriteWornNonPossessive(String sentence, List<CharModel> all) {
        Matcher m = NONPOSSESSIVE_ACCESSORY.matcher(sentence);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            boolean prep = m.group(3) != null;
            String lead = prep ? m.group(1) : null;
            String noun = prep ? m.group(3) : m.group(5);
            String state = prep ? m.group(4) : m.group(6);
            String category = categoryOf(noun);
            CharModel subject = resolveNearestPreceding(sentence, m.start(), all);
            if (isContradiction(subject, category)
                    && subject.signatureShort() != null && !subject.signatureShort().isBlank()) {
                String poss = possessiveOf(subject.gender());
                StringBuilder repl = new StringBuilder();
                if (lead != null) repl.append(lead).append(' ');
                if (!poss.isEmpty()) repl.append(poss).append(' ');
                repl.append(subject.signatureShort());
                if (state != null) repl.append(' ').append(state);
                m.appendReplacement(sb, Matcher.quoteReplacement(repl.toString()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Nearest named character starting before {@code pos}; null if none. */
    private static CharModel resolveNearestPreceding(String sentence, int pos, List<CharModel> all) {
        String hay = sentence.toLowerCase(Locale.ROOT);
        CharModel best = null; int bestIdx = -1;
        for (CharModel c : all) {
            int idx = lastNameStartBefore(hay, c, pos);
            if (idx > bestIdx) { bestIdx = idx; best = c; }
        }
        return best;
    }

    private static int lastNameStartBefore(String hay, CharModel c, int pos) {
        int best = -1;
        for (String token : new String[]{c.name(), c.id()}) {
            if (token == null || token.isBlank()) continue;
            Matcher m = Pattern.compile("\\b" + Pattern.quote(token.toLowerCase(Locale.ROOT)) + "\\b").matcher(hay);
            while (m.find()) {
                if (m.start() >= pos) break;
                if (m.start() > best) best = m.start();
            }
        }
        return best;
    }

    private static String possessiveOf(String gender) {
        return switch (gender == null ? "" : gender) {
            case "he" -> "his";
            case "she" -> "her";
            case "it" -> "its";
            default -> "";
        };
    }

    /** R3 gate: subject FORBIDS the category AND does NOT own it. */
    private static boolean isContradiction(CharModel subject, String category) {
        return subject != null && category != null
                && subject.forbiddenCategories().contains(category)
                && !subject.ownedCategories().contains(category);
    }

    /** First name in the sentence whose pronoun-gender matches the possessive;
     *  fallback first name; null if none. */
    private static CharModel resolveSubject(String sentence, String poss, List<CharModel> all) {
        String hay = sentence.toLowerCase(Locale.ROOT);
        String want = wantGender(poss);
        CharModel firstAny = null; int firstAnyIdx = Integer.MAX_VALUE;
        CharModel firstMatch = null; int firstMatchIdx = Integer.MAX_VALUE;
        for (CharModel c : all) {
            int idx = firstIndexOfName(hay, c);
            if (idx < 0) continue;
            if (idx < firstAnyIdx) { firstAnyIdx = idx; firstAny = c; }
            boolean genderOk = want.isEmpty() || want.equals(c.gender());
            if (genderOk && idx < firstMatchIdx) { firstMatchIdx = idx; firstMatch = c; }
        }
        return firstMatch != null ? firstMatch : firstAny;
    }

    private static int firstIndexOfName(String hay, CharModel c) {
        int best = -1;
        for (String token : new String[]{c.name(), c.id()}) {
            if (token == null || token.isBlank()) continue;
            Matcher m = Pattern.compile("\\b" + Pattern.quote(token.toLowerCase(Locale.ROOT)) + "\\b").matcher(hay);
            if (m.find()) { int idx = m.start(); if (best < 0 || idx < best) best = idx; }
        }
        return best;
    }

    public static List<String> findContradictions(String desc, List<String> sceneCharIds, List<CharModel> all) {
        List<String> findings = new ArrayList<>();
        if (desc == null || desc.isBlank() || all == null || all.isEmpty()) return findings;
        for (String sentence : SENTENCE_SPLIT.split(desc, -1)) {
            Matcher m = POSSESSIVE_ACCESSORY.matcher(sentence);
            while (m.find()) {
                String category = categoryOf(m.group(3));
                CharModel subject = resolveSubject(sentence, m.group(1), all);
                if (isContradiction(subject, category)) {
                    findings.add(subject.name() + " described with " + m.group().trim()
                            + " but " + subject.name() + " must never wear " + category + ".");
                }
            }
            Matcher mn = NONPOSSESSIVE_ACCESSORY.matcher(sentence);
            while (mn.find()) {
                String noun = mn.group(3) != null ? mn.group(3) : mn.group(5);
                String category = categoryOf(noun);
                CharModel subject = resolveNearestPreceding(sentence, mn.start(), all);
                if (isContradiction(subject, category)) {
                    findings.add(subject.name() + " described with " + mn.group().trim()
                            + " but " + subject.name() + " must never wear " + category + ".");
                }
            }
        }
        return findings;
    }
}
