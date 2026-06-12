package com.youtubeauto.script.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.youtubeauto.script.anthropic.GeneratedScript;
import com.youtubeauto.script.anthropic.GeneratedScript.Line;
import com.youtubeauto.script.anthropic.GeneratedScript.Scene;
import com.youtubeauto.script.bible.EpisodeStructure;
import com.youtubeauto.script.service.ComedyValidator;
import com.youtubeauto.script.service.PacingValidator;
import com.youtubeauto.script.service.StructureValidator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Offline script-eval: pint het deterministische QA-vangnet vast met golden
 * fixtures. Twee structureel geldige golden scripts (geschreven tegen de
 * echte bible-episodeStructure: 31 scènes, ~178s op 180s target, 6 fasen,
 * locatie-rotatie, cast-continuïteit, één stille beat, comedy-contract)
 * moeten door StructureValidator + PacingValidator + ComedyValidator — de
 * volledige offline keten uit ScriptOrchestrator — heen komen; bewust kapotte
 * mutaties van diezelfde golden moeten FALEN met de verwachte violation-tekst.
 *
 * Wie een validator versoepelt (bv. de ±10% duur-drempel terug naar ±20%, of
 * de closing-fase-check weg), ziet hier rood. Wie hem aanscherpt, ziet de
 * golden scripts falen en weet dat élk gegenereerd script strenger behandeld
 * wordt. Geen LLM-calls: de mutaties zijn pure-Java transformaties zodat
 * golden en kapot gegarandeerd alleen in het gemuteerde aspect verschillen.
 *
 * NB: StructureValidator checkte fase-VOLGORDE oorspronkelijk niet, behalve
 * "laatste scène = closing-fase" — een script met de climax-scènes vooraan
 * passeerde zolang aantallen/duren klopten. Die check bestaat inmiddels
 * (niet-dalende fase-index, bible-volgorde); de "climax vooraan"-mutant pint
 * hem vast, naast de "climax weggehaald"-mutant voor de aantallen-check.
 */
class ScriptEvalHarness {

    private static final int TARGET_SECONDS = 180;

    private static final StructureValidator structure = new StructureValidator();
    private static final PacingValidator pacing = new PacingValidator();
    private static final ComedyValidator comedy = new ComedyValidator();

    private static EpisodeStructure es;
    private static GeneratedScript goldenDiscovery;
    private static GeneratedScript goldenDuckling;

    @BeforeAll
    static void setUp() throws Exception {
        es = EvalSupport.loadRealBible().getBible().episodeStructure();
        assertFalse(es.phases().isEmpty(), "bible episodeStructure heeft geen fasen — niets te pinnen");
        goldenDiscovery = load("/eval/scripts/golden-discovery-180.json");
        goldenDuckling = load("/eval/scripts/golden-duckling-180.json");
    }

    private static GeneratedScript load(String resource) throws IOException {
        try (InputStream in = ScriptEvalHarness.class.getResourceAsStream(resource)) {
            if (in == null) throw new IllegalStateException("fixture ontbreekt: classpath:" + resource);
            return new ObjectMapper().readValue(in, GeneratedScript.class);
        }
    }

    // ── golden scripts: de hele offline keten moet groen zijn ───────────────

    @Test
    void goldenDiscoveryPassesAllOfflineGates() {
        assertAllGatesPass(goldenDiscovery, "golden-discovery-180");
    }

    @Test
    void goldenDucklingPassesAllOfflineGates() {
        assertAllGatesPass(goldenDuckling, "golden-duckling-180");
    }

    private static void assertAllGatesPass(GeneratedScript s, String name) {
        List<String> sv = structure.validate(s, es, TARGET_SECONDS);
        assertTrue(sv.isEmpty(), name + ": StructureValidator-violations op golden script "
                + "(validator aangescherpt? fixture dan bewust bijwerken): " + sv);
        PacingValidator.Result pr = pacing.validate(s);
        assertFalse(pr.failed(), name + ": PacingValidator-violations: " + pr.violations());
        ComedyValidator.Result cr = comedy.validate(s);
        assertFalse(cr.failed(), name + ": ComedyValidator-violations: " + cr.violations());
    }

    // ── kapotte mutanten: verwachte violation-strings moeten vuren ──────────

    @Test
    void durationThirtyPercentOverFailsStructure() {
        List<String> v = structure.validate(durationPlus30(), es, TARGET_SECONDS);
        assertTrue(v.stream().anyMatch(x -> x.contains("Total duration")),
                "+30% duur moet de totaalduur-check (±10%) raken, kreeg: " + v);
    }

    @Test
    void singleLocationEverywhereFailsStructure() {
        List<String> v = structure.validate(singleLocation(), es, TARGET_SECONDS);
        assertTrue(v.stream().anyMatch(x -> x.contains("single location")),
                "alles in de barnyard moet de locatie-variatie-check raken, kreeg: " + v);
    }

    @Test
    void missingCloserFailsStructure() {
        List<String> v = structure.validate(noCloser(), es, TARGET_SECONDS);
        assertTrue(v.stream().anyMatch(x -> x.contains("closing phase")),
                "laatste scène buiten de closer-fase moet falen, kreeg: " + v);
        assertTrue(v.stream().anyMatch(x -> x.contains("Phase 'closer'")),
                "lege closer-fase moet de minScenes-check raken, kreeg: " + v);
    }

    @Test
    void missingClimaxFailsStructure() {
        List<String> v = structure.validate(guttedClimax(), es, TARGET_SECONDS);
        assertTrue(v.stream().anyMatch(x -> x.contains("Phase 'climax'")),
                "script zonder climax-fase moet falen, kreeg: " + v);
    }

    @Test
    void climaxFirstFailsStructure() {
        List<String> v = structure.validate(climaxFirst(), es, TARGET_SECONDS);
        assertTrue(v.stream().anyMatch(x -> x.contains("Phase order violated")),
                "climax-scènes vooraan moeten de fase-volgorde-check raken, kreeg: " + v);
    }

    @Test
    void castFlickerFailsStructure() {
        List<String> v = structure.validate(castFlicker(), es, TARGET_SECONDS);
        assertTrue(v.stream().anyMatch(x -> x.contains("flickers")),
                "één-scène-verdwijning van Mo moet de flicker-check raken, kreeg: " + v);
    }

    @Test
    void overstuffedSceneFailsPacing() {
        PacingValidator.Result r = pacing.validate(overstuffedScene());
        assertTrue(r.failed() && r.violations().stream().anyMatch(x -> x.contains("w/s")),
                "30 woorden in 7s moet de words-per-second-check raken, kreeg: " + r.violations());
    }

    @Test
    void removedSilentBeatFailsPacing() {
        PacingValidator.Result r = pacing.validate(noSilentBeat());
        assertTrue(r.failed() && r.violations().stream().anyMatch(x -> x.contains("SILENT VISUAL BEAT")),
                "script zonder stille beat moet falen, kreeg: " + r.violations());
    }

    // ── mutaties (pure functies op de golden fixture) ───────────────────────

    private static GeneratedScript durationPlus30() {
        return mapScenes(goldenDiscovery, sc -> withDuration(sc, (int) Math.round(sc.durationSeconds() * 1.3)));
    }

    private static GeneratedScript singleLocation() {
        return mapScenes(goldenDiscovery, sc -> withLocation(sc, "barnyard"));
    }

    private static GeneratedScript noCloser() {
        int lastSeq = goldenDiscovery.scenes().get(goldenDiscovery.scenes().size() - 1).seq();
        return mapScenes(goldenDiscovery, sc -> sc.seq() == lastSeq ? withPhase(sc, "resolution") : sc);
    }

    private static GeneratedScript guttedClimax() {
        return mapScenes(goldenDiscovery,
                sc -> "climax".equalsIgnoreCase(sc.phase()) ? withPhase(sc, "development") : sc);
    }

    private static GeneratedScript climaxFirst() {
        // Climax-scènes naar voren, seq hernummerd 1..n: aantallen, duren en
        // de closer op het eind blijven allemaal kloppen — alleen de VOLGORDE
        // is kapot. Precies het gat dat de fase-volgorde-check dicht.
        List<Scene> reordered = new ArrayList<>();
        goldenDiscovery.scenes().stream()
                .filter(sc -> "climax".equalsIgnoreCase(sc.phase())).forEach(reordered::add);
        goldenDiscovery.scenes().stream()
                .filter(sc -> !"climax".equalsIgnoreCase(sc.phase())).forEach(reordered::add);
        List<Scene> renumbered = new ArrayList<>();
        for (int i = 0; i < reordered.size(); i++) renumbered.add(withSeq(reordered.get(i), i + 1));
        return new GeneratedScript(goldenDiscovery.title(), goldenDiscovery.hook(),
                goldenDiscovery.cta(), renumbered);
    }

    private static GeneratedScript castFlicker() {
        // Mo zit in s7..s12; hem alleen uit s8 halen = de 1-scène-flicker.
        return mapScenes(goldenDiscovery, sc -> sc.seq() == 8 ? withCharacters(sc, List.of("pip")) : sc);
    }

    private static GeneratedScript overstuffedScene() {
        // 30 woorden in de 7s-scène 3 = 4.3 w/s (max 3.2 voor 3-6 jaar).
        Line wall = new Line("pip", "one two three four five six seven eight nine ten "
                + "eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen twenty "
                + "one two three four five six seven eight nine ten");
        return mapScenes(goldenDiscovery, sc -> sc.seq() == 3 ? withLines(sc, List.of(wall)) : sc);
    }

    private static GeneratedScript noSilentBeat() {
        // Scène 23 is de stille beat; één regel erin = nul stille beats over.
        return mapScenes(goldenDiscovery,
                sc -> sc.lines() != null && sc.lines().isEmpty()
                        ? withLines(sc, List.of(new Line("pip", "Wow."))) : sc);
    }

    // ── record-helpers (Scene heeft geen withers) ───────────────────────────

    private static GeneratedScript mapScenes(GeneratedScript s, UnaryOperator<Scene> f) {
        return new GeneratedScript(s.title(), s.hook(), s.cta(), s.scenes().stream().map(f).toList());
    }

    private static Scene withSeq(Scene sc, int seq) {
        return new Scene(seq, sc.lines(), sc.visualDesc(), sc.characters(), sc.locationId(), sc.phase(),
                sc.timeOfDay(), sc.weather(), sc.goal(), sc.emotion(), sc.motionSpeed(),
                sc.endPose(), sc.motionDesc(), sc.durationSeconds());
    }

    private static Scene withDuration(Scene sc, int dur) { return copy(sc, sc.lines(), sc.characters(), sc.locationId(), sc.phase(), dur); }
    private static Scene withLocation(Scene sc, String loc) { return copy(sc, sc.lines(), sc.characters(), loc, sc.phase(), sc.durationSeconds()); }
    private static Scene withPhase(Scene sc, String phase) { return copy(sc, sc.lines(), sc.characters(), sc.locationId(), phase, sc.durationSeconds()); }
    private static Scene withCharacters(Scene sc, List<String> chars) { return copy(sc, sc.lines(), chars, sc.locationId(), sc.phase(), sc.durationSeconds()); }
    private static Scene withLines(Scene sc, List<Line> lines) { return copy(sc, lines, sc.characters(), sc.locationId(), sc.phase(), sc.durationSeconds()); }

    private static Scene copy(Scene sc, List<Line> lines, List<String> characters,
                              String locationId, String phase, int durationSeconds) {
        return new Scene(sc.seq(), lines, sc.visualDesc(), characters, locationId, phase,
                sc.timeOfDay(), sc.weather(), sc.goal(), sc.emotion(), sc.motionSpeed(),
                sc.endPose(), sc.motionDesc(), durationSeconds);
    }

    // ── rapport ─────────────────────────────────────────────────────────────

    @AfterAll
    static void writeReport() {
        try {
            Map<String, GeneratedScript> cases = new LinkedHashMap<>();
            cases.put("golden-discovery-180 (GOED)", goldenDiscovery);
            cases.put("golden-duckling-180 (GOED)", goldenDuckling);
            cases.put("mutant: duur +30% (KAPOT)", durationPlus30());
            cases.put("mutant: alles in barnyard (KAPOT)", singleLocation());
            cases.put("mutant: geen closer-fase (KAPOT)", noCloser());
            cases.put("mutant: climax weggehaald (KAPOT)", guttedClimax());
            cases.put("mutant: climax vooraan (KAPOT)", climaxFirst());
            cases.put("mutant: cast-flicker Mo (KAPOT)", castFlicker());
            cases.put("mutant: 30 woorden in 7s (KAPOT)", overstuffedScene());
            cases.put("mutant: stille beat weg (KAPOT)", noSilentBeat());

            StringBuilder md = new StringBuilder();
            md.append("Target ").append(TARGET_SECONDS).append("s; gates = StructureValidator + ")
              .append("PacingValidator + ComedyValidator (de offline keten van ScriptOrchestrator).\n\n");
            md.append("| script | structure | pacing | comedy | eerste violation |\n|---|---|---|---|---|\n");
            cases.forEach((name, script) -> {
                List<String> sv = structure.validate(script, es, TARGET_SECONDS);
                PacingValidator.Result pr = pacing.validate(script);
                ComedyValidator.Result cr = comedy.validate(script);
                String first = !sv.isEmpty() ? sv.get(0)
                        : pr.failed() ? pr.violations().get(0)
                        : cr.failed() ? cr.violations().get(0) : "-";
                if (first.length() > 90) first = first.substring(0, 90) + "...";
                md.append(String.format("| %s | %s | %s | %s | %s |%n", name,
                        sv.isEmpty() ? "pass" : "FAIL(" + sv.size() + ")",
                        pr.failed() ? "FAIL(" + pr.violations().size() + ")" : "pass",
                        cr.failed() ? "FAIL(" + cr.violations().size() + ")" : "pass",
                        first.replace("|", "\\|")));
            });
            EvalSupport.reportSection(
                    "Script-eval (golden fixtures door het deterministische QA-vangnet)", md.toString());
        } catch (Exception e) {
            System.err.println("[eval] script-rapport overgeslagen: " + e);
        }
    }
}
