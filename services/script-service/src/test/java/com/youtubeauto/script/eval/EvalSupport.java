package com.youtubeauto.script.eval;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.script.api.dto.GenerateScriptRequest;
import com.youtubeauto.script.bible.BibleLoader;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Gedeelde plumbing voor de offline eval-harnassen ({@link PromptEvalHarness},
 * {@link ScriptEvalHarness}). Geen LLM-calls, geen netwerk, geen Spring-context
 * — alles deterministisch zodat `mvn test` overal hetzelfde antwoord geeft.
 *
 * Bible-pad: de tests gebruiken de ECHTE bible (bible/channel.yml in de
 * repo-root). BibleLoader leest zijn pad normaal uit `app.bible.path`
 * (Spring @Value); buiten een context blijft dat veld null, dus we zetten het
 * hier via ReflectionTestUtils en proberen een paar kandidaat-paden zodat het
 * werkt vanuit zowel `mvn -pl services/script-service test` (CWD = module-dir)
 * als een IDE-run vanuit de repo-root.
 */
final class EvalSupport {

    private EvalSupport() {}

    /** Eén vaste test-brief uit src/test/resources/eval/briefs.yaml. */
    record EvalBrief(String id, String topic, String audience, int targetSeconds,
                     String mood, String arc, String styleHint, String lesson, String brief) {}

    private static final String[] BIBLE_CANDIDATES = {
            "../../bible/channel.yml",    // CWD = services/script-service (mvn -pl)
            "bible/channel.yml",          // CWD = repo-root (IDE)
            "../bible/channel.yml",
            "../../../bible/channel.yml"
    };

    /** Laadt de echte channel-bible via de echte {@link BibleLoader}. */
    static BibleLoader loadRealBible() throws IOException {
        Path path = null;
        for (String candidate : BIBLE_CANDIDATES) {
            Path p = Paths.get(candidate);
            if (Files.exists(p)) { path = p; break; }
        }
        if (path == null) {
            throw new IllegalStateException("bible/channel.yml niet gevonden relatief aan "
                    + Paths.get("").toAbsolutePath()
                    + " — draai de tests vanuit de repo-root of de module-dir");
        }
        BibleLoader loader = new BibleLoader();
        ReflectionTestUtils.setField(loader, "biblePath", path.toString());
        loader.load();
        return loader;
    }

    /** De bevroren briefs-set (classpath:/eval/briefs.yaml). */
    static List<EvalBrief> loadBriefs() throws IOException {
        YAMLMapper yaml = new YAMLMapper();
        yaml.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        try (InputStream in = EvalSupport.class.getResourceAsStream("/eval/briefs.yaml")) {
            if (in == null) throw new IllegalStateException("classpath:/eval/briefs.yaml ontbreekt");
            return yaml.readValue(in, new TypeReference<List<EvalBrief>>() {});
        }
    }

    static GenerateScriptRequest toRequest(EvalBrief b) {
        return new GenerateScriptRequest(b.topic(), b.audience(), b.targetSeconds(),
                null /* numScenes: laat PromptBuilder rekenen */, b.styleHint(), b.brief(),
                b.lesson(), b.mood(), null, null, null, b.arc());
    }

    // ── best-effort markdown-rapport (target/eval-report.md) ────────────────
    // Beide harnassen schrijven hun sectie hierheen; de eerste call in de JVM
    // trunceert het bestand. Schrijffouten worden genegeerd: het rapport is
    // een bijproduct, nooit een reden om de build te breken.

    private static final Path REPORT = Paths.get("target", "eval-report.md");
    private static boolean started = false;

    static synchronized void reportSection(String title, String body) {
        try {
            if (REPORT.getParent() != null) Files.createDirectories(REPORT.getParent());
            if (!started) {
                Files.writeString(REPORT,
                        "# Eval report — " + LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS) + "\n\n"
                        + "Gegenereerd door de *EvalHarness-tests (offline, deterministisch, geen LLM-calls).\n"
                        + "Diff dit bestand voor/na elke promptwijziging.\n\n",
                        StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                started = true;
            }
            Files.writeString(REPORT, "## " + title + "\n\n" + body + "\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) {
            System.err.println("[eval] rapport overgeslagen (best-effort): " + e);
        }
    }
}
