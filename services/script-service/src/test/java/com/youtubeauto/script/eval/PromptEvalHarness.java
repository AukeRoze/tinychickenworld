package com.youtubeauto.script.eval;

import com.youtubeauto.script.bible.BibleCharacter;
import com.youtubeauto.script.bible.BibleLoader;
import com.youtubeauto.script.bible.BibleLocation;
import com.youtubeauto.script.bible.ChannelBible;
import com.youtubeauto.script.bible.SeriesMythology;
import com.youtubeauto.script.bible.StoryArc;
import com.youtubeauto.script.dedupe.VariationProfile;
import com.youtubeauto.script.eval.EvalSupport.EvalBrief;
import com.youtubeauto.script.service.PromptBuilder;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline prompt-eval: bouwt voor elke bevroren brief (eval/briefs.yaml) de
 * volledige system+user-prompt via de ECHTE {@link PromptBuilder} met de ECHTE
 * bible, en pint deterministisch vast wat een promptwijziging NOOIT stuk mag
 * maken:
 *
 *   1. alle verplichte secties + kernregels aanwezig (EPISODE STRUCTURE,
 *      SERIES MYTHOLOGY incl. opening-ritual, STORY ARC, CAST, LOCATIONS,
 *      VARIATION DIRECTIVES, STRICT RULES-kern: CTA REALITY CHECK,
 *      TIC DOSING, MICRO-CONFLICT, PARENT WINK, INSERT SHOTS);
 *   2. geen onopgeloste placeholders ("%s", "%d", kale "null", lege
 *      fallback-secties);
 *   3. promptlengte binnen budget (tokens ~= chars/4) + groei zichtbaar in
 *      het rapport per sectie;
 *   4. elke bible-character (naam + id) staat in de CAST-sectie;
 *   5. de gekozen arc is exact de brief-arc en alle arc-beats uit de bible
 *      staan letterlijk in de prompt.
 *
 * Geen LLM-call, geen netwerk: PromptBuilder is een pure functie van
 * (request, profile, bible). Wie de prompt aanpast, draait dit harnas en
 * dift target/eval-report.md — zie infra/eval/README.md.
 */
class PromptEvalHarness {

    /** Hard budget: ~chars/4. Huidige prompt zit ruim onder; dit vangt een
     *  sluipende verdubbeling (elke sectie "nog één regeltje erbij"). */
    private static final int MAX_PROMPT_TOKENS = 12_000;

    /** Vast variatieprofiel zodat de VARIATION DIRECTIVES deterministisch zijn. */
    private static final VariationProfile PROFILE = new VariationProfile(
            VariationProfile.Hook.QUESTION,
            VariationProfile.Tone.CURIOUS,
            VariationProfile.Structure.LINEAR,
            VariationProfile.ExampleStyle.REAL_WORLD);

    /** Letterlijke ankers uit PromptBuilder/bible-rendering. Verdwijnt er één,
     *  dan is een verplichte sectie of kernregel uit de prompt gevallen. */
    private static final List<String> REQUIRED_MARKERS = List.of(
            "=== WHO YOU ARE (writer persona) ===",                  // bible persona header
            "=== BRAND VOICE",
            "=== QUALITY CHECKLIST",
            "=== SERIES MYTHOLOGY (makes episodes feel like one show)",   // vaste basistekst
            "=== SERIES MYTHOLOGY (recognisable rituals",                 // bible-gedreven sectie
            "OPENING RITUAL (mandatory, every episode):",
            "RUNNING GAGS",
            "=== EPISODE STRUCTURE (HARD CONSTRAINTS",
            "PHASE 1 — HOOK",
            "=== STORY ARC (FOLLOW THESE BEATS IN ORDER) ===",
            "=== EMOTIONAL CURVE",
            "=== BEAT-SHEET TIMING",
            "=== STRICT RULES ===",
            "CTA REALITY CHECK",
            "VERBAL TIC DOSING",
            "MICRO-CONFLICT",
            "INSERT SHOTS",
            "PARENT WINK",
            "=== CAST (use these character ids only) ===",
            "=== LOCATIONS (use these locationIds only) ===",
            "=== VARIATION DIRECTIVES (apply but stay in cast) ===");

    private static final Pattern HEADER = Pattern.compile("(?m)^=== (.+?) ===\\s*$");
    private static final Pattern BARE_NULL = Pattern.compile("\\bnull\\b");

    private static ChannelBible bible;
    private static PromptBuilder builder;
    private static List<EvalBrief> briefs;

    @BeforeAll
    static void setUp() throws Exception {
        BibleLoader loader = EvalSupport.loadRealBible();
        bible = loader.getBible();
        builder = new PromptBuilder(loader);
        briefs = EvalSupport.loadBriefs();
        assertTrue(briefs.size() >= 6, "briefs.yaml moet minstens 6 briefs bevatten, vond " + briefs.size());
    }

    /** Eén gebouwde prompt per brief. */
    private record Built(EvalBrief brief, String system, String user, String arcId) {}

    private static Built build(EvalBrief b) {
        PromptBuilder.BuiltPrompt bp = builder.build(EvalSupport.toRequest(b), PROFILE);
        return new Built(b, bp.systemPrompt(), bp.messages().get(0).content(), bp.arcId());
    }

    // ── 1. verplichte secties ───────────────────────────────────────────────

    @Test
    void requiredSectionsPresentForEveryBrief() {
        List<String> failures = new ArrayList<>();
        for (EvalBrief b : briefs) {
            Built p = build(b);
            for (String marker : REQUIRED_MARKERS) {
                if (!p.system().contains(marker)) {
                    failures.add(b.id() + ": sectie/regel ontbreekt in system-prompt: '" + marker + "'");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── 2. geen onopgeloste placeholders / lege fallbacks ──────────────────

    @Test
    void noUnresolvedPlaceholdersOrEmptyFallbacks() {
        List<String> failures = new ArrayList<>();
        for (EvalBrief b : briefs) {
            Built p = build(b);
            String all = p.system() + "\n" + p.user();
            if (all.contains("%s")) failures.add(b.id() + ": onopgeloste '%s' in prompt");
            if (all.contains("%d")) failures.add(b.id() + ": onopgeloste '%d' in prompt");
            if (BARE_NULL.matcher(all).find()) failures.add(b.id() + ": kale 'null' in prompt");
            if (all.contains("(no locations defined)")) {
                failures.add(b.id() + ": locations-fallback actief — bible-locaties niet geladen");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── 3. promptlengte-budget ──────────────────────────────────────────────

    @Test
    void promptStaysWithinTokenBudget() {
        List<String> failures = new ArrayList<>();
        for (EvalBrief b : briefs) {
            Built p = build(b);
            int approxTokens = (p.system().length() + p.user().length()) / 4;
            if (approxTokens > MAX_PROMPT_TOKENS) {
                failures.add(String.format("%s: ~%d tokens > budget %d (system %d chars, user %d chars) "
                                + "— snoei een sectie of verhoog bewust het budget",
                        b.id(), approxTokens, MAX_PROMPT_TOKENS, p.system().length(), p.user().length()));
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── 4. cast-consistentie ────────────────────────────────────────────────

    @Test
    void everyBibleCharacterAppearsInCastSection() {
        Built p = build(briefs.get(0));   // cast-sectie is brief-onafhankelijk
        List<String> failures = new ArrayList<>();
        for (BibleCharacter c : bible.characters()) {
            if (!p.system().contains("(id: " + c.id() + ",")) {
                failures.add("character-id '" + c.id() + "' ontbreekt in CAST-sectie");
            }
            if (!p.system().contains(c.name())) {
                failures.add("character-naam '" + c.name() + "' ontbreekt in CAST-sectie");
            }
        }
        String mainId = bible.mainCharacter().map(BibleCharacter::id).orElse("");
        assertFalse(mainId.isBlank(), "bible heeft geen main character (role: main)");
        if (!p.system().contains("Main character (host of every episode): " + mainId)) {
            failures.add("main-character-regel ontbreekt of wijst niet naar '" + mainId + "'");
        }
        for (BibleLocation l : bible.locations()) {
            if (!p.system().contains("- " + l.id() + ": " + l.name())) {
                failures.add("locatie '" + l.id() + "' ontbreekt in LOCATIONS-sectie");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── 5. arc-beats kloppen per brief met de bible ─────────────────────────

    @Test
    void chosenArcMatchesBriefAndBibleBeats() {
        List<String> failures = new ArrayList<>();
        for (EvalBrief b : briefs) {
            Optional<StoryArc> arcOpt = bible.storyArc(b.arc());
            if (arcOpt.isEmpty()) {
                failures.add(b.id() + ": brief-arc '" + b.arc() + "' bestaat niet in bible storyArcs");
                continue;
            }
            Built p = build(b);
            StoryArc arc = arcOpt.get();
            if (!arc.id().equalsIgnoreCase(p.arcId())) {
                failures.add(b.id() + ": PromptBuilder koos arc '" + p.arcId()
                        + "' i.p.v. de gevraagde '" + arc.id() + "' — preferredArc-pad kapot?");
            }
            if (!p.system().contains("Arc: " + arc.label())) {
                failures.add(b.id() + ": arc-label '" + arc.label() + "' ontbreekt in STORY ARC-sectie");
            }
            for (String beat : arc.beats()) {
                if (!p.system().contains(beat)) {
                    failures.add(b.id() + ": arc-beat ontbreekt letterlijk in prompt: '" + beat + "'");
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── 6. series mythology: ritueel + gags uit de bible komen door ─────────

    @Test
    void seriesMythologyCarriesRitualAndGags() {
        SeriesMythology m = bible.seriesMythology();
        assertFalse(m.isEmpty(), "bible heeft geen seriesMythology-blok — sectie kan niet gepind worden");
        Built p = build(briefs.get(0));
        List<String> failures = new ArrayList<>();
        if (!p.system().contains(m.openingRitual().trim())) {
            failures.add("openingRitual-tekst uit de bible ontbreekt in de prompt");
        }
        m.runningGags().forEach((id, gag) -> {
            boolean knownCharacter = bible.characters().stream()
                    .anyMatch(c -> c.id().equalsIgnoreCase(id));
            if (knownCharacter && !p.system().contains(gag.trim())) {
                failures.add("running gag van '" + id + "' ontbreekt in de prompt");
            }
        });
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── 7. user-prompt draagt de brief-velden ───────────────────────────────

    @Test
    void userPromptCarriesBriefFields() {
        List<String> failures = new ArrayList<>();
        for (EvalBrief b : briefs) {
            Built p = build(b);
            if (!p.user().contains("Topic: " + b.topic())) failures.add(b.id() + ": topic ontbreekt in user-prompt");
            if (!p.user().contains("Target length: " + b.targetSeconds() + " seconds")) {
                failures.add(b.id() + ": targetSeconds ontbreekt in user-prompt");
            }
            if (b.mood() != null && !b.mood().isBlank() && !p.user().contains("Mood: ")) {
                failures.add(b.id() + ": mood ontbreekt in user-prompt");
            }
            if (b.styleHint() != null && !b.styleHint().isBlank() && !p.user().contains("Style hint: ")) {
                failures.add(b.id() + ": styleHint ontbreekt in user-prompt");
            }
            if (b.brief() != null && !b.brief().isBlank() && !p.user().contains("CREATIVE BRIEF")) {
                failures.add(b.id() + ": creative brief-sectie ontbreekt in user-prompt");
            }
            if (b.lesson() != null && !b.lesson().isBlank() && !p.user().contains("Lesson the viewer")) {
                failures.add(b.id() + ": lesson ontbreekt in user-prompt");
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }

    // ── rapport: promptlengte per brief + sectie-groottes ───────────────────

    @AfterAll
    static void writeReport() {
        try {
            StringBuilder md = new StringBuilder();
            md.append("Budget: ").append(MAX_PROMPT_TOKENS).append(" tokens (~chars/4). ")
              .append("Vast variatieprofiel: ").append(PROFILE.tag()).append("\n\n");
            md.append("| brief | arc | target s | system chars | user chars | ~tokens | budget | secties |\n");
            md.append("|---|---|---:|---:|---:|---:|---|---|\n");
            for (EvalBrief b : briefs) {
                Built p = build(b);
                int tokens = (p.system().length() + p.user().length()) / 4;
                long missing = REQUIRED_MARKERS.stream().filter(mk -> !p.system().contains(mk)).count();
                md.append(String.format("| %s | %s | %d | %d | %d | %d | %s | %s |%n",
                        b.id(), p.arcId(), b.targetSeconds(),
                        p.system().length(), p.user().length(), tokens,
                        tokens <= MAX_PROMPT_TOKENS ? "ok" : "OVER",
                        missing == 0 ? "alle aanwezig" : missing + " ONTBREKEN"));
            }

            md.append("\nSectie-groottes (system-prompt van brief '")
              .append(briefs.get(0).id()).append("' — secties zijn vrijwel brief-onafhankelijk):\n\n");
            md.append("| sectie | chars |\n|---|---:|\n");
            sectionSizes(build(briefs.get(0)).system()).forEach((name, size) ->
                    md.append("| ").append(name.length() > 56 ? name.substring(0, 56) + "..." : name)
                      .append(" | ").append(size).append(" |\n"));

            EvalSupport.reportSection("Prompt-eval (echte PromptBuilder + echte bible)", md.toString());
        } catch (Exception e) {
            System.err.println("[eval] prompt-rapport overgeslagen: " + e);
        }
    }

    /** Splitst de system-prompt op '=== ... ==='-koppen en meet elke sectie in chars. */
    private static Map<String, Integer> sectionSizes(String system) {
        Map<String, Integer> out = new LinkedHashMap<>();
        Matcher m = HEADER.matcher(system);
        String name = "(preamble vóór eerste header)";
        int start = 0;
        while (m.find()) {
            int size = m.start() - start;
            if (size > 0) putUnique(out, name, size);
            name = m.group(1);
            start = m.start();
        }
        putUnique(out, name, system.length() - start);
        return out;
    }

    private static void putUnique(Map<String, Integer> map, String name, int size) {
        String key = name;
        int i = 2;
        while (map.containsKey(key)) key = name + " #" + i++;
        map.put(key, size);
    }
}
