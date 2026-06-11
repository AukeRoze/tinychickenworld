package com.youtubeauto.script.service;

import com.youtubeauto.script.anthropic.GeneratedScript;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Deterministic comedy gate. The brand promises "at least TWO real laugh
 * beats", a CALLBACK and three per-character running gags — but until now
 * only the LLM critic judged that, holistically and fallibly. This validator
 * checks the mechanical comedy contract in pure Java, so a gag-less script is
 * re-prompted with exact fixes instead of shipped on a 7/10 vibe.
 *
 * Honest about its limits: only mechanically checkable rules are VIOLATIONS
 * (drive a re-prompt); fuzzy ones (is Bo's wordplay funny?) are WARNINGS —
 * logged, never blocking, the LLM critic stays the judge of funny.
 */
@Slf4j
@Component
public class ComedyValidator {

    /** Sound-effect vocabulary the brand voice asks for ("kids quote these
     *  for weeks"). Lowercase, matched on whole dialogue tokens. */
    private static final Set<String> SOUND_WORDS = Set.of(
            "whoosh", "plop", "bonk", "bzzz", "boing", "pop", "splash", "crack",
            "ding", "wheee", "whee", "pffft", "bok", "bok-bok", "peep", "crunch",
            "squish", "knock", "boom", "zoom", "swoosh", "thump", "tweet",
            "drip", "splat", "pitter-patter", "tap-tap", "achoo", "hic");

    /** Stretched-letter onomatopoeia ("Wooooow", "Bzzzz") also counts. */
    private static final Pattern STRETCHED = Pattern.compile(".*([a-z])\\1{2,}.*");

    private static final Set<String> STOPWORDS = Set.of(
            "about", "after", "again", "along", "always", "around", "because",
            "before", "comes", "could", "every", "first", "friend", "friends",
            "going", "happy", "little", "look", "looks", "maybe", "morning",
            "never", "other", "right", "their", "there", "these", "thing",
            "things", "think", "today", "together", "tomorrow", "until",
            "watch", "where", "which", "while", "world", "would", "wonderful");

    private static final Set<String> CAST = Set.of("pip", "mo", "bo");

    public record Result(List<String> violations, List<String> warnings) {
        public boolean failed() { return !violations.isEmpty(); }
    }

    public Result validate(GeneratedScript script) {
        List<String> violations = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<GeneratedScript.Scene> scenes = script == null ? List.of() : script.scenes();
        if (scenes == null || scenes.isEmpty()) return new Result(violations, warnings);

        // ---- 1. Sound-effect beats: at least TWO across the dialogue. ----
        int soundBeats = 0;
        for (GeneratedScript.Scene s : scenes) {
            for (GeneratedScript.Line l : lines(s)) {
                for (String tok : tokens(l.text())) {
                    if (SOUND_WORDS.contains(tok) || STRETCHED.matcher(tok).matches()) soundBeats++;
                }
            }
        }
        if (soundBeats < 2) {
            violations.add(String.format(
                    "Only %d sound-effect beat(s) in dialogue — the brand voice requires at least TWO "
                    + "(e.g. \"Whoosh!\", \"Plop!\", \"Bonk!\" spoken as dialogue)", soundBeats));
        }

        // ---- 2. Mo's running gag: exactly one calm everyday comparison. ----
        if (speaks(scenes, "mo")) {
            long moComparisons = lineStream(scenes, "mo")
                    .filter(t -> t.matches("(?i).*\\b(a bit like|just like|like when|it's like)\\b.*"))
                    .count();
            if (moComparisons == 0) {
                violations.add("Mo never lands his running gag — he must give exactly ONE calm "
                        + "everyday comparison (\"It's a bit like when...\"), late in the script");
            } else if (moComparisons > 1) {
                warnings.add("Mo gives " + moComparisons + " comparisons — the gag works best exactly once");
            }
        }

        // ---- 3. Callback: a distinctive early word must return late. ----
        int n = scenes.size();
        Set<String> earlyRare = new HashSet<>();
        for (int i = 0; i < Math.max(1, n / 3); i++) {
            for (GeneratedScript.Line l : lines(scenes.get(i))) {
                for (String tok : tokens(l.text())) {
                    if (isRare(tok)) earlyRare.add(tok);
                }
            }
        }
        boolean payoff = false;
        for (GeneratedScript.Scene s : scenes) {
            if (!inPayoffZone(s, scenes)) continue;
            for (GeneratedScript.Line l : lines(s)) {
                for (String tok : tokens(l.text())) {
                    if (earlyRare.contains(tok)) { payoff = true; break; }
                }
            }
        }
        if (!payoff) {
            violations.add("No CALLBACK found: plant a DISTINCTIVE gag word in the first third "
                    + "(a made-up compound like \"SKY-PEBBLE\" or a silly sound) and have a character "
                    + "speak the same word again in the climax or closer");
        }

        // ---- 4. Soft heuristics (never block — the critic judges funny). ----
        if (speaks(scenes, "bo") && lineStream(scenes, "bo")
                .noneMatch(t -> t.matches(".*\\b\\w+[-–]\\w+.*") || t.contains("?!"))) {
            warnings.add("Bo may be missing her wordplay/mishear gag (no hyphenated rhyme or '?!' found)");
        }
        if (speaks(scenes, "pip") && lineStream(scenes, "pip")
                .noneMatch(t -> t.matches("(?i).*\\b(oh! oh|oops|i mean|silly me|wait)\\b.*")
                        || t.matches(".*\\b\\w+[-–]\\w+.*"))) {
            warnings.add("Pip may be missing her tiny-mistake / made-up-name gag");
        }

        return new Result(violations, warnings);
    }

    // ---------- helpers ----------

    private static List<GeneratedScript.Line> lines(GeneratedScript.Scene s) {
        return s.lines() == null ? List.of() : s.lines();
    }

    private static boolean speaks(List<GeneratedScript.Scene> scenes, String speaker) {
        return scenes.stream().flatMap(s -> lines(s).stream())
                .anyMatch(l -> speaker.equalsIgnoreCase(l.speaker()));
    }

    private static java.util.stream.Stream<String> lineStream(
            List<GeneratedScript.Scene> scenes, String speaker) {
        return scenes.stream().flatMap(s -> lines(s).stream())
                .filter(l -> speaker.equalsIgnoreCase(l.speaker()))
                .map(l -> l.text() == null ? "" : l.text());
    }

    private static List<String> tokens(String text) {
        if (text == null || text.isBlank()) return List.of();
        List<String> out = new ArrayList<>();
        for (String raw : text.split("\\s+")) {
            String t = raw.toLowerCase(Locale.ROOT).replaceAll("^[^a-z]+|[^a-z-]+$", "");
            if (!t.isBlank()) out.add(t);
        }
        return out;
    }

    /** A callback candidate must be DISTINCTIVE — a made-up hyphen compound
     *  ("sky-pebble", "bang-rise"), a sound word or a stretched exclamation.
     *  Ordinary nouns recur naturally and would make the check meaningless. */
    private static boolean isRare(String tok) {
        if (STOPWORDS.contains(tok) || CAST.contains(tok)) return false;
        return (tok.contains("-") && tok.length() >= 5)
                || SOUND_WORDS.contains(tok)
                || STRETCHED.matcher(tok).matches();
    }

    /** Climax / resolution / closer when phases are present; last third otherwise. */
    private static boolean inPayoffZone(GeneratedScript.Scene s, List<GeneratedScript.Scene> all) {
        String p = s.phase();
        if (p != null && !p.isBlank()) {
            return p.equals("climax") || p.equals("resolution") || p.equals("closer");
        }
        return all.indexOf(s) >= (all.size() * 2) / 3;
    }

}
