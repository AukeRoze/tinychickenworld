package com.youtubeauto.orchestrator.service;

import com.youtubeauto.orchestrator.domain.QcFinding;
import com.youtubeauto.orchestrator.repository.QcFindingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * Records vision-QC failures and surfaces RECURRING patterns, so we stop
 * re-fixing the same thing every video. If "accessory-swap / mo" keeps topping
 * the list, that's a signal to harden Mo's anchor / the prompt permanently —
 * which then makes future renders start cleaner and Auto-Fix faster.
 *
 * Fail-safe: recording never throws (a logging feature must not break a render).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QcInsights {

    private final QcFindingRepository repo;

    /** Persists each issue from a failed scene QC, bucketed for aggregation. */
    @Transactional
    public void record(UUID jobId, Integer seq, List<String> issues, String source) {
        if (issues == null || issues.isEmpty()) return;
        try {
            for (String issue : issues) {
                if (issue == null || issue.isBlank()) continue;
                repo.save(QcFinding.builder()
                        .videoJobId(jobId)
                        .seq(seq)
                        .category(categorize(issue))
                        .characterHint(characterOf(issue))
                        .issue(issue.length() > 500 ? issue.substring(0, 500) : issue)
                        .source(source)
                        .build());
            }
        } catch (Exception e) {
            log.warn("QC insight record failed (non-fatal): {}", e.getMessage());
        }
    }

    /** Buckets a free-text QC issue into a stable category for aggregation. */
    public static String categorize(String issue) {
        String s = issue.toLowerCase();
        if (containsAny(s, "text", "letter", "word", "bonk", "pow", "boing", "caption",
                "speech bubble", "subtitle", "watermark")) return "rendered-text";
        if (containsAny(s, "finger", "hand", "thumb")) return "hands";
        if (containsAny(s, "duplicate", "twin", "clone", "two chick", "second chick",
                "two identical")) return "duplicate";
        if (containsAny(s, "missing", "without", "no hat", "no scarf", "no glasses",
                "lost", "absent")) return "missing-accessory";
        if (containsAny(s, "wearing", "swap", "wrong accessory", "glasses on",
                "hat on", "bandana on", "another's", "belongs to")) return "accessory-swap";
        if (containsAny(s, "colour", "color", "green", "orange", "red ", "blue",
                "wrong colour", "wrong color")) return "color-drift";
        if (containsAny(s, "cut off", "crop", "edge", "off-center", "off to one side",
                "dead space", "framing", "feet out")) return "framing";
        return "other";
    }

    /** Detects which cast member an issue mentions (pip/mo/bo), else null. */
    public static String characterOf(String issue) {
        String s = issue.toLowerCase();
        // word-ish boundaries so "import" doesn't match "pi" etc.
        if (s.matches(".*\\bpip\\b.*")) return "pip";
        if (s.matches(".*\\bmo\\b.*"))  return "mo";
        if (s.matches(".*\\bbo\\b.*"))  return "bo";
        return null;
    }

    private static boolean containsAny(String s, String... needles) {
        for (String n : needles) if (s.contains(n)) return true;
        return false;
    }

    public record Pattern(String category, String character, long count, String lastExample) {}

    /**
     * Aggregates the most recent findings into recurring patterns
     * (category × character), most frequent first.
     */
    @Transactional(readOnly = true)
    public List<Pattern> patterns() {
        List<QcFinding> recent = repo.findTop2000ByOrderByCreatedAtDesc();
        Map<String, long[]> counts = new HashMap<>();
        Map<String, String> example = new HashMap<>();
        Map<String, String[]> keyParts = new HashMap<>();
        for (QcFinding f : recent) {
            String ch = f.getCharacterHint() == null ? "-" : f.getCharacterHint();
            String key = f.getCategory() + "|" + ch;
            counts.computeIfAbsent(key, k -> new long[1])[0]++;
            keyParts.putIfAbsent(key, new String[]{f.getCategory(), ch});
            example.putIfAbsent(key, f.getIssue()); // most recent (list is desc)
        }
        List<Pattern> out = new ArrayList<>();
        for (var e : counts.entrySet()) {
            String[] kp = keyParts.get(e.getKey());
            out.add(new Pattern(kp[0], kp[1], e.getValue()[0], example.get(e.getKey())));
        }
        out.sort((a, b) -> Long.compare(b.count(), a.count()));
        return out;
    }
}
