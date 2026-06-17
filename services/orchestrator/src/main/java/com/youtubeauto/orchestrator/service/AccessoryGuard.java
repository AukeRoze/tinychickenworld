package com.youtubeauto.orchestrator.service;

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
 * Deterministic ACCESSORY-vs-action contradiction guard. Pure functions (no
 * Spring, no I/O), driven entirely by the bible's per-character accessory data,
 * so it is trivially unit-testable AND can be called inline both in the image
 * {@code PromptComposer} and the {@link VeoPromptCompiler} right before a prompt
 * is built.
 *
 * <p>The defect this fixes (EP3 review, scene 5): the LLM-authored action text
 * said "<b>Mo</b> waddles in, adjusting <b>his glasses</b> with a wingtip" while
 * the very same prompt's CHARACTER DNA / ROSTER locks "Mo must NEVER wear any
 * eyeglasses or glasses" (glasses are <b>Bo's</b> signature accessory). An image
 * model has no timeline: it is told to draw Mo without glasses AND to have him
 * touch glasses, so it hallucinates a morphing pair of glasses onto Mo or copies
 * Bo's whole look onto him. A video model rushes the same contradiction.
 *
 * <p>Because each accessory is OWNED by exactly one chick in the bible
 * ({@code dna.accessory}) and FORBIDDEN on the others ({@code dna.antiAccessory}),
 * the contradiction is 100% machine-detectable: an action that attributes a
 * uniquely-owned accessory to a character the bible forbids it on is always a
 * defect. {@link #sanitize} rewrites the offending accessory to the acting
 * character's OWN signature accessory (Mo's "his glasses" → "his thick red
 * knitted scarf"); {@link #findContradictions} reports without rewriting (for the
 * critic / a re-prompt / logging).
 *
 * <p>Two phrasings are caught. (1) A POSSESSIVE accessory phrase
 * ("his/her/its &lt;accessory&gt;") bound to its grammatical subject. (2) A
 * NON-POSSESSIVE worn attribution ("Mo toppling <b>with glasses askew</b>",
 * "glasses sliding") bound to the nearest preceding named subject — this is the
 * EP3 scene-7 defect class the possessive pattern alone missed (no "his", so a
 * video model morphed Bo's glasses onto Mo). The non-possessive branch only fires
 * when there is worn-evidence (a wear-preposition <i>with/wearing/in</i> or a
 * displacement word <i>askew/sliding/…</i>), so a bare object mention ("the spare
 * glasses on the table") is never touched.
 *
 * <p>Conservative by design — a shared accessory (a "scarf", worn by both Mo and
 * Bo) is never rewritten, and a legitimate self-reference ("Bo pushes her glasses
 * up") is left untouched, so a finding is never a false positive.
 */
public final class AccessoryGuard {

    private AccessoryGuard() {}

    /** The bible facts the guard needs for one character. {@code ownedCategories}
     *  comes from {@code dna.accessory}, {@code forbiddenCategories} from
     *  {@code dna.antiAccessory}, {@code signatureShort} from
     *  {@code dna.signatureAccessoryShort} (the rewrite target), {@code gender}
     *  ("he"/"she"/"it"/"") from the character's pronoun usage — used to bind a
     *  possessive ("his"/"her"/"its") to the right character. */
    public record CharModel(String id, String name, String signatureShort,
                            Set<String> ownedCategories, Set<String> forbiddenCategories,
                            String gender) {
        /** Back-compat 5-arg form (no gender) → permissive gender matching. */
        public CharModel(String id, String name, String signatureShort,
                         Set<String> ownedCategories, Set<String> forbiddenCategories) {
            this(id, name, signatureShort, ownedCategories, forbiddenCategories, "");
        }
    }

    /** Infers a character's pronoun gender from any text that uses "his"/"her"/
     *  "its" (e.g. the bible's {@code dna.tic}). Majority wins; "" when unknown
     *  (→ permissive matching). Single source = the bible, no extra field. */
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

    // Canonical accessory categories → the keywords that name them in free text.
    // Ordered most-specific-first so "eyeglasses" is classed as glasses, not hat.
    private static final Map<String, Pattern> CATEGORY_KEYWORDS = new LinkedHashMap<>();
    static {
        CATEGORY_KEYWORDS.put("glasses", Pattern.compile("eye-?glasses|glasses|spectacles|specs", Pattern.CASE_INSENSITIVE));
        CATEGORY_KEYWORDS.put("bandana", Pattern.compile("bandana|kerchief", Pattern.CASE_INSENSITIVE));
        CATEGORY_KEYWORDS.put("hat",     Pattern.compile("\\bhat\\b", Pattern.CASE_INSENSITIVE));
        CATEGORY_KEYWORDS.put("scarf",   Pattern.compile("\\bscarf\\b", Pattern.CASE_INSENSITIVE));
    }

    // A possessive accessory phrase: "his/her/its" + up to 3 adjective words +
    // an accessory noun. The accessory noun group is matched against the category
    // keywords afterwards (so the alternation stays one source of truth).
    private static final Pattern POSSESSIVE_ACCESSORY = Pattern.compile(
            "\\b(his|her|its)\\s+((?:[a-z][a-z'-]*\\s+){0,3}?)"
            + "(eye-?glasses|glasses|spectacles|specs|bandana|kerchief|hat|scarf)\\b",
            Pattern.CASE_INSENSITIVE);

    // Sentence boundaries (kept simple: a colon like "Over-the-shoulder past Pip:"
    // must NOT split, so the subject "Mo" stays in the same unit as "his glasses").
    private static final Pattern SENTENCE_SPLIT = Pattern.compile("(?<=[.!?;])\\s+");

    // Displacement / wearing-state words that prove an accessory is ON a body
    // even without a possessive ("glasses askew", "hat slipping"). This is the
    // EP3 scene-7 defect class: "Mo toppling with glasses askew" — no "his", so
    // the possessive pattern above misses it and the video model morphs Bo's
    // glasses onto Mo.
    private static final String WORN_STATE =
            "askew|crooked|cock-?eyed|skewed|tilted|lopsided|slipping|sliding|slid|"
          + "slipped|perched|balanced|fogging|fogged|steamed|bouncing|wobbling|"
          + "flying|knocked|jostled|shaking|dangling";

    // Non-possessive worn-accessory phrase, in two TIGHT alternatives so the
    // adjective run can never swallow the acting subject (the bug that bound
    // "Mo toppling with glasses askew" to Pip):
    //   (a) preposition-anchored: with/wearing/in + ≤3 non-possessive adjectives +
    //       accessory noun + OPTIONAL displacement word
    //         → g1 lead, g2 adjectives, g3 noun, g4 state
    //   (b) displacement-anchored: accessory noun + REQUIRED displacement word (no
    //       preceding words consumed)
    //         → g5 noun, g6 state
    // Either branch guarantees worn-evidence, so a bare object mention ("the spare
    // glasses on the table") matches neither and is left untouched.
    private static final String ACC_NOUN =
            "eye-?glasses|glasses|spectacles|specs|bandana|kerchief|hat|scarf";
    private static final Pattern NONPOSSESSIVE_ACCESSORY = Pattern.compile(
            "\\b(?:(with|wearing|in)\\s+((?:(?!his\\b|her\\b|its\\b)[a-z][a-z'-]*\\s+){0,3}?)"
          + "(" + ACC_NOUN + ")\\b(?:\\s+(" + WORN_STATE + "))?"
          + "|(" + ACC_NOUN + ")\\b\\s+(" + WORN_STATE + "))",
            Pattern.CASE_INSENSITIVE);

    /** @return the set of accessory categories named anywhere in {@code text}
     *  (used to read a character's owned/forbidden categories from the bible
     *  free-text fields). */
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

    /**
     * Rewrites every accessory-vs-action contradiction in {@code desc} to the
     * acting character's OWN signature accessory. Returns {@code desc} unchanged
     * when there is nothing to fix, so it is safe to call on every scene.
     */
    public static String sanitize(String desc, List<String> sceneCharIds, List<CharModel> all) {
        if (desc == null || desc.isBlank() || all == null || all.isEmpty()) return desc;
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String sentence : SENTENCE_SPLIT.split(desc, -1)) {
            if (!first) out.append(' ');
            first = false;
            // Pass 1: possessive ("his glasses"). Pass 2: non-possessive worn
            // attribution ("with glasses askew", "glasses sliding"). Order matters
            // — once pass 1 rewrites a forbidden possessive to an OWNED accessory,
            // pass 2 sees an owned accessory and leaves it alone (no double rewrite).
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
            if (isContradiction(subject, category)
                    && subject.signatureShort() != null && !subject.signatureShort().isBlank()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(poss + " " + subject.signatureShort()));
            } else {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Rewrites a non-possessive worn-accessory contradiction ("Mo toppling with
     *  glasses askew") to the acting character's own signature accessory, binding
     *  the accessory to the NEAREST PRECEDING named character (there is no
     *  possessive pronoun to give a gender signal). Only acts when there is
     *  worn-evidence (a wear-preposition or a displacement word), so a plain
     *  mention of an accessory object is never touched. */
    private static String rewriteWornNonPossessive(String sentence, List<CharModel> all) {
        Matcher m = NONPOSSESSIVE_ACCESSORY.matcher(sentence);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            // Branch (a) preposition-anchored populates g1/g3/g4; branch (b)
            // displacement-anchored populates g5/g6 (no leading preposition).
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

    /** Binds a non-possessive accessory to the LAST named character that starts
     *  before {@code pos} in the sentence — the nearest preceding subject (so in
     *  "Pip …, Mo toppling with glasses askew, Bo …" the glasses bind to Mo, not
     *  Pip or Bo). Returns null when no name precedes, in which case the caller
     *  leaves the text untouched. */
    private static CharModel resolveNearestPreceding(String sentence, int pos, List<CharModel> all) {
        String hay = sentence.toLowerCase(Locale.ROOT);
        CharModel best = null;
        int bestIdx = -1;
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

    /** R3 gate: a contradiction is when the acting character FORBIDS the
     *  accessory category AND does NOT own it — independent of who else owns it
     *  (so a shared accessory like "scarf" worn by the wrong chick is caught too). */
    private static boolean isContradiction(CharModel subject, String category) {
        return subject != null && category != null
                && subject.forbiddenCategories().contains(category)
                && !subject.ownedCategories().contains(category);
    }

    /** Binds a possessive ("his/her/its") to a character: the FIRST name in the
     *  sentence whose pronoun-gender matches the possessive (so "Mo … his glasses"
     *  resolves to Mo even when "Pip" appears earlier, and "Bo … her hat" resolves
     *  to Bo even when "Pip" sits right next to "hat"). Falls back to the first
     *  named character, then null. This grammatical-subject heuristic is far more
     *  reliable than nearest-name for the real defect phrasings. */
    private static CharModel resolveSubject(String sentence, String poss, List<CharModel> all) {
        String hay = sentence.toLowerCase(Locale.ROOT);
        String want = wantGender(poss);
        CharModel firstAny = null;
        int firstAnyIdx = Integer.MAX_VALUE;
        CharModel firstMatch = null;
        int firstMatchIdx = Integer.MAX_VALUE;
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
            if (m.find()) {
                int idx = m.start();
                if (best < 0 || idx < best) best = idx;
            }
        }
        return best;
    }

    /**
     * Reports accessory-vs-action contradictions WITHOUT rewriting — for the LLM
     * critic, a re-prompt, or logging. Empty list = the text is clean.
     */
    public static List<String> findContradictions(String desc, List<String> sceneCharIds, List<CharModel> all) {
        List<String> findings = new ArrayList<>();
        if (desc == null || desc.isBlank() || all == null || all.isEmpty()) return findings;
        for (String sentence : SENTENCE_SPLIT.split(desc, -1)) {
            Matcher m = POSSESSIVE_ACCESSORY.matcher(sentence);
            while (m.find()) {
                String category = categoryOf(m.group(3));
                CharModel subject = resolveSubject(sentence, m.group(1), all);
                if (isContradiction(subject, category)) {
                    String fix = subject.signatureShort() == null || subject.signatureShort().isBlank()
                            ? "its own signature accessory" : subject.signatureShort();
                    findings.add(subject.name() + " is described with " + m.group().trim()
                            + " but " + subject.name() + " must never wear " + category
                            + " — use " + subject.name() + "'s own " + fix + " instead.");
                }
            }
            Matcher mn = NONPOSSESSIVE_ACCESSORY.matcher(sentence);
            while (mn.find()) {
                String noun = mn.group(3) != null ? mn.group(3) : mn.group(5);
                String category = categoryOf(noun);
                CharModel subject = resolveNearestPreceding(sentence, mn.start(), all);
                if (isContradiction(subject, category)) {
                    String fix = subject.signatureShort() == null || subject.signatureShort().isBlank()
                            ? "its own signature accessory" : subject.signatureShort();
                    findings.add(subject.name() + " is described with " + mn.group().trim()
                            + " but " + subject.name() + " must never wear " + category
                            + " — use " + subject.name() + "'s own " + fix + " instead.");
                }
            }
        }
        return findings;
    }
}
