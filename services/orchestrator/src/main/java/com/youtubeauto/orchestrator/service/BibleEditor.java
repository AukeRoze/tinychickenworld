package com.youtubeauto.orchestrator.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Safe, comment-preserving editor for the channel bible's character fields, used
 * by the Cast editor in the UI. Only edits SINGLE-LINE scalar fields (name, role,
 * dna.accessory, dna.tic) by replacing the value on the matched line within the
 * target character's block — so all the bible's comments and structure survive.
 *
 * <p>Every write makes a {@code channel.yml.bak} first and then re-parses the
 * result; if the edit produced invalid YAML or dropped the character, the backup
 * is restored and the call fails — so a bad edit can never corrupt the bible.
 *
 * <p>Folded blocks (description/personality) and lists (catchphrases) are NOT
 * edited here (riskier round-trip) — edit those in channel.yml directly for now.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BibleEditor {

    private final OrchestratorProperties props;

    /** 4-space single-line character fields the editor may change. */
    private static final java.util.Set<String> FIELD_KEYS = java.util.Set.of("name", "role");
    /** 6-space single-line dna.* fields the editor may change. antiAccessory
     *  added for the QC→bible suggestion loop (it's the field that hardens
     *  against the most common drift class: accessory swaps). */
    private static final java.util.Set<String> DNA_KEYS = java.util.Set.of("accessory", "tic", "antiAccessory");
    /** 4-space FOLDED-block character fields (multi-line text) the editor may change. */
    private static final java.util.Set<String> BLOCK_KEYS = java.util.Set.of("description", "personality");

    private Path biblePath() { return Paths.get(props.bible().path()); }

    public Path refsDir() {
        Path dir = biblePath().getParent();
        return (dir == null ? Paths.get("refs") : dir.resolve("refs"));
    }

    /**
     * Update a character's editable scalar fields in channel.yml in place.
     * Unknown/blank keys are ignored; fields not present on the character are
     * skipped (no insertion in v1). Returns the list of fields actually changed.
     */
    public synchronized List<String> updateCharacter(String id, Map<String, String> fields) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("character id required");
        Path bible = biblePath();
        if (!Files.exists(bible)) throw new IllegalStateException("channel.yml not found at " + bible);

        List<String> lines = new ArrayList<>(Files.readAllLines(bible, StandardCharsets.UTF_8));
        if (findCharacterStart(lines, id) < 0) {
            throw new IllegalArgumentException("character '" + id + "' not found in bible");
        }

        List<String> changed = new ArrayList<>();
        for (Map.Entry<String, String> e : fields.entrySet()) {
            String key = e.getKey() == null ? "" : e.getKey().trim();
            String val = e.getValue();
            if (val == null) continue;
            // Re-locate per field — folded-block / catchphrase edits change line counts.
            int s = findCharacterStart(lines, id);
            if (s < 0) break;
            int en = findBlockEnd(lines, s);
            boolean did;
            if (FIELD_KEYS.contains(key))        did = setScalar(lines, s, en, "    ", key, val.trim());
            else if (DNA_KEYS.contains(key))     did = setScalar(lines, s, en, "      ", key, val.trim());
            else if (BLOCK_KEYS.contains(key))   did = setFoldedBlock(lines, s, en, "    ", key, val.trim());
            else if (key.equals("catchphrasesOpener")) did = setCatchphraseList(lines, s, en, "opener", splitLines(val));
            else if (key.equals("catchphrasesCloser")) did = setCatchphraseList(lines, s, en, "closer", splitLines(val));
            else did = false;
            if (did) changed.add(key);
        }
        if (changed.isEmpty()) return changed;

        // Back up, write, then validate; restore on any problem so a bad edit
        // can never leave a corrupt bible behind.
        Path bak = bible.resolveSibling("channel.yml.bak");
        Files.copy(bible, bak, StandardCopyOption.REPLACE_EXISTING);
        Files.write(bible, lines, StandardCharsets.UTF_8);
        try {
            JsonNode root = new YAMLMapper().readTree(bible.toFile());
            boolean stillThere = false;
            for (JsonNode c : root.path("characters")) {
                if (id.equalsIgnoreCase(c.path("id").asText(""))) { stillThere = true; break; }
            }
            if (!stillThere) throw new IllegalStateException("character vanished after edit");
        } catch (Exception bad) {
            Files.copy(bak, bible, StandardCopyOption.REPLACE_EXISTING);   // restore
            throw new IllegalStateException("Edit produced invalid YAML — reverted. " + bad.getMessage(), bad);
        }
        log.info("Bible: updated character {} fields {}", id, changed);
        return changed;
    }

    /** Saves an uploaded reference image to refs/{id}.png (backs up any existing). */
    public Path saveReference(String id, byte[] png) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("character id required");
        if (png == null || png.length == 0) throw new IllegalArgumentException("empty image");
        Path dir = refsDir();
        Files.createDirectories(dir);
        Path target = dir.resolve(id + ".png");
        if (Files.exists(target)) {
            Files.copy(target, dir.resolve(id + ".png.bak"), StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(target, png);
        log.info("Bible: saved reference image for {} -> {} ({} bytes)", id, target, png.length);
        return target;
    }

    // ---- helpers --------------------------------------------------------------

    /** Index of the "  - id: <id>" list item line WITHIN the top-level
     *  {@code characters:} section (so a "- id:" in another list like series:
     *  can't match), or -1. */
    private int findCharacterStart(List<String> lines, String id) {
        // Locate the top-level "characters:" key.
        int secStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith("characters:")) { secStart = i; break; }
        }
        if (secStart < 0) return -1;
        for (int i = secStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String t = line.strip();
            // End of the characters: section = next top-level key (col 0, non-comment).
            if (!Character.isWhitespace(line.charAt(0)) && !t.startsWith("#")) break;
            if (t.startsWith("- id:")) {
                String v = unquote(t.substring("- id:".length()).trim());
                if (id.equalsIgnoreCase(v)) return i;
            }
        }
        return -1;
    }

    /** First line after start that begins a new list item or a top-level key. */
    private int findBlockEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String stripped = line.strip();
            if (stripped.startsWith("- id:")) return i;             // next character
            // A top-level key (no leading whitespace, not a comment) ends the list.
            if (!Character.isWhitespace(line.charAt(0)) && !stripped.startsWith("#")) return i;
        }
        return lines.size();
    }

    /** Index within [start,end) of a line "{indent}{key}:" , or -1. */
    private int findKeyLine(List<String> lines, int start, int end, String indent, String key) {
        String prefix = indent + key + ":";
        for (int i = start + 1; i < end; i++) {
            if (lines.get(i).startsWith(prefix)) return i;
        }
        return -1;
    }

    /** Replace a single-line scalar's value. Returns false if the field is absent. */
    private boolean setScalar(List<String> lines, int start, int end, String indent, String key, String value) {
        int idx = findKeyLine(lines, start, end, indent, key);
        if (idx < 0) return false;
        lines.set(idx, indent + key + ": " + yamlQuote(value));
        return true;
    }

    /** Replace a folded-block scalar ("{key}: >" + indented body) with new wrapped
     *  prose. Comment-safe for prose. Returns false if the field is absent. */
    private boolean setFoldedBlock(List<String> lines, int start, int end, String indent, String key, String value) {
        int idx = findKeyLine(lines, start, end, indent, key);
        if (idx < 0) return false;
        int keyIndent = indent.length();
        int bodyEnd = idx + 1;
        while (bodyEnd < end) {
            String l = lines.get(bodyEnd);
            if (l.isBlank()) { bodyEnd++; continue; }
            if (leadingSpaces(l) <= keyIndent) break;   // dedent → block body ended
            bodyEnd++;
        }
        String bodyIndent = " ".repeat(keyIndent + 2);
        List<String> repl = new ArrayList<>();
        repl.add(indent + key + ": >");
        for (String w : wrap(value, 80)) repl.add(bodyIndent + w);
        splice(lines, idx, bodyEnd, repl);
        return true;
    }

    /** Replace the items of a catchphrases sub-list (opener|closer). Returns false
     *  if the catchphrases block or the sub-key is absent. */
    private boolean setCatchphraseList(List<String> lines, int start, int end, String sub, List<String> items) {
        int cp = findKeyLine(lines, start, end, "    ", "catchphrases");
        if (cp < 0) return false;
        int cpEnd = cp + 1;
        while (cpEnd < end) {
            String l = lines.get(cpEnd);
            if (l.isBlank()) { cpEnd++; continue; }
            if (leadingSpaces(l) <= 4) break;            // back to a 4-space key
            cpEnd++;
        }
        int subIdx = -1;
        for (int i = cp + 1; i < cpEnd; i++) {
            if (lines.get(i).startsWith("      " + sub + ":")) { subIdx = i; break; }
        }
        if (subIdx < 0) return false;
        int subEnd = subIdx + 1;
        while (subEnd < cpEnd) {
            String l = lines.get(subEnd);
            if (l.isBlank()) { subEnd++; continue; }
            if (leadingSpaces(l) <= 6) break;            // next 6-space key → end of this list
            subEnd++;
        }
        if (items.isEmpty()) {
            // No phrases → inline empty list, drop any old items.
            lines.set(subIdx, "      " + sub + ": []");
            splice(lines, subIdx + 1, subEnd, new ArrayList<>());
            return true;
        }
        // Keep "      {sub}:" as a key, replace its 8-space "- " item lines.
        if (lines.get(subIdx).trim().endsWith("[]")) lines.set(subIdx, "      " + sub + ":");
        List<String> repl = new ArrayList<>();
        for (String it : items) repl.add("        - " + yamlQuote(it));
        splice(lines, subIdx + 1, subEnd, repl);
        return true;
    }

    /** Replace lines [from, to) of {@code lines} with {@code repl} (in place). */
    private void splice(List<String> lines, int from, int to, List<String> repl) {
        List<String> tail = new ArrayList<>(lines.subList(to, lines.size()));
        while (lines.size() > from) lines.remove(lines.size() - 1);
        lines.addAll(repl);
        lines.addAll(tail);
    }

    private int leadingSpaces(String s) {
        int n = 0; while (n < s.length() && s.charAt(n) == ' ') n++; return n;
    }

    /** Split user textarea input into trimmed non-blank lines (one phrase each). */
    private List<String> splitLines(String s) {
        List<String> out = new ArrayList<>();
        if (s == null) return out;
        for (String line : s.split("\\R")) {
            String t = line.trim();
            if (!t.isEmpty()) out.add(t);
        }
        return out;
    }

    /** Word-wrap prose to ~width columns for a folded YAML block. */
    private List<String> wrap(String text, int width) {
        List<String> out = new ArrayList<>();
        String[] words = text.replaceAll("\\s+", " ").trim().split(" ");
        StringBuilder line = new StringBuilder();
        for (String w : words) {
            if (w.isEmpty()) continue;
            if (line.length() > 0 && line.length() + 1 + w.length() > width) {
                out.add(line.toString()); line.setLength(0);
            }
            if (line.length() > 0) line.append(' ');
            line.append(w);
        }
        if (line.length() > 0) out.add(line.toString());
        if (out.isEmpty()) out.add("");
        return out;
    }

    private String unquote(String s) {
        if (s.length() >= 2 && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** Double-quote a scalar value safely (escape backslash + quote). */
    private String yamlQuote(String v) {
        return "\"" + v.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ===== Series CRUD (top-level "series:" list of {id,name,description}) =====

    /** Update an existing series' name/description (null = leave unchanged). */
    public synchronized List<String> updateSeries(String id, String name, String description) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("series id required");
        List<String> lines = readBibleLines();
        int s = findItemStart(lines, "series", id);
        if (s < 0) throw new IllegalArgumentException("series '" + id + "' not found");
        int en = findItemEnd(lines, s);
        List<String> changed = new ArrayList<>();
        if (name != null && setScalar(lines, s, en, "    ", "name", name.trim())) changed.add("name");
        if (description != null && setScalar(lines, s, en, "    ", "description", description.trim())) changed.add("description");
        if (changed.isEmpty()) return changed;
        commitWithCheck(lines, root -> seriesPresent(root, id));
        log.info("Bible: updated series {} fields {}", id, changed);
        return changed;
    }

    /** Append a new series item to the series: section. Fails if the id exists. */
    public synchronized String addSeries(String id, String name, String description) throws IOException {
        String slug = slug(id);
        if (slug.isBlank()) throw new IllegalArgumentException("a valid series id (letters/numbers/-) is required");
        List<String> lines = readBibleLines();
        if (findItemStart(lines, "series", slug) >= 0) {
            throw new IllegalArgumentException("series id '" + slug + "' already exists");
        }
        int secStart = indexOfTopKey(lines, "series");
        if (secStart < 0) throw new IllegalStateException("no series: section in bible");
        int insertAt = endOfSectionContent(lines, secStart);
        List<String> item = List.of(
                "  - id: " + slug,
                "    name: " + yamlQuote(name == null || name.isBlank() ? slug : name.trim()),
                "    description: " + yamlQuote(description == null ? "" : description.trim()));
        lines.addAll(insertAt, item);
        commitWithCheck(lines, root -> seriesPresent(root, slug));
        log.info("Bible: added series {}", slug);
        return slug;
    }

    /** Remove a series item entirely. */
    public synchronized void deleteSeries(String id) throws IOException {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("series id required");
        List<String> lines = readBibleLines();
        int s = findItemStart(lines, "series", id);
        if (s < 0) throw new IllegalArgumentException("series '" + id + "' not found");
        int en = findItemEnd(lines, s);
        splice(lines, s, en, new ArrayList<>());
        commitWithCheck(lines, root -> !seriesPresent(root, id));
        log.info("Bible: deleted series {}", id);
    }

    private List<String> readBibleLines() throws IOException {
        Path bible = biblePath();
        if (!Files.exists(bible)) throw new IllegalStateException("channel.yml not found at " + bible);
        return new ArrayList<>(Files.readAllLines(bible, StandardCharsets.UTF_8));
    }

    /** Backup → write → re-parse; restore from .bak if invalid or the post-check fails. */
    private void commitWithCheck(List<String> lines, java.util.function.Predicate<JsonNode> ok) throws IOException {
        Path bible = biblePath();
        Path bak = bible.resolveSibling("channel.yml.bak");
        Files.copy(bible, bak, StandardCopyOption.REPLACE_EXISTING);
        Files.write(bible, lines, StandardCharsets.UTF_8);
        try {
            JsonNode root = new YAMLMapper().readTree(bible.toFile());
            if (!ok.test(root)) throw new IllegalStateException("post-condition failed");
        } catch (Exception bad) {
            Files.copy(bak, bible, StandardCopyOption.REPLACE_EXISTING);
            throw new IllegalStateException("Edit produced invalid YAML — reverted. " + bad.getMessage(), bad);
        }
    }

    private boolean seriesPresent(JsonNode root, String id) {
        for (JsonNode n : root.path("series")) {
            if (id.equalsIgnoreCase(n.path("id").asText(""))) return true;
        }
        return false;
    }

    /** Index of the "  - id: <id>" line within the top-level {@code section}: list. */
    private int findItemStart(List<String> lines, String section, String id) {
        int secStart = indexOfTopKey(lines, section);
        if (secStart < 0) return -1;
        for (int i = secStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            String t = line.strip();
            if (!Character.isWhitespace(line.charAt(0)) && !t.startsWith("#")) break;
            if (t.startsWith("- id:")) {
                String v = unquote(t.substring("- id:".length()).trim());
                if (id.equalsIgnoreCase(v)) return i;
            }
        }
        return -1;
    }

    /** Tight end of a list item: stops at a blank line or a dedent (< 4 spaces). */
    private int findItemEnd(List<String> lines, int start) {
        for (int i = start + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) return i;
            if (leadingSpaces(line) < 4) return i;
        }
        return lines.size();
    }

    private int indexOfTopKey(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).startsWith(key + ":")) return i;
        }
        return -1;
    }

    /** Index just after the last indented content line of a top-level section. */
    private int endOfSectionContent(List<String> lines, int secStart) {
        int last = secStart;
        for (int i = secStart + 1; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) continue;
            if (!Character.isWhitespace(line.charAt(0))) break;   // col-0 → out of the section's items
            last = i;
        }
        return last + 1;
    }

    /** Sanitise a free-text id into a safe kebab slug ([a-z0-9-]). */
    private String slug(String s) {
        if (s == null) return "";
        return s.trim().toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+|-+$)", "");
    }
}
