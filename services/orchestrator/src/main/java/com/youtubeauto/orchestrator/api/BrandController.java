package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.service.BibleEditor;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serves channel brand assets so the static UI can show them: the logo, the
 * cast (from bible.characters) and each character's reference image. Also lets
 * the Cast editor change a character's scalar fields + upload a new reference
 * image (writes via {@link BibleEditor}; needs the read-WRITE bible mount).
 */
@RestController
@RequestMapping("/api/v1/brand")
@RequiredArgsConstructor
public class BrandController {

    private final OrchestratorProperties props;
    private final BibleEditor bibleEditor;

    private Path bibleDir() {
        Path bible = Paths.get(props.bible().path());          // .../bible/channel.yml
        return bible.getParent();
    }

    @GetMapping(value = "/logo.png", produces = "image/png")
    public ResponseEntity<Resource> logo() {
        Path dir = bibleDir();
        Path logo = (dir == null ? Paths.get("logo.png") : dir.resolve("logo.png"));
        if (!Files.exists(logo)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(logo));
    }

    // ── Character reference stills (the Veo/QC pixel anchor) ───────────────
    // These files now drive BOTH generation (Veo asset references) and the
    // vision-QC ground truth, so the human must be able to SEE and REJECT
    // them. Convention: refs/<id>.png = canonical, refs/<id>/*.png = angles;
    // anything containing "candidate" is ignored by the pipeline.

    private static final java.util.regex.Pattern SAFE_ID =
            java.util.regex.Pattern.compile("[a-z0-9_-]{1,40}");
    private static final java.util.regex.Pattern SAFE_REF_FILE =
            java.util.regex.Pattern.compile("[A-Za-z0-9._-]{1,80}\\.(png|jpg|jpeg)");

    /** Lists a character's reference stills in pipeline priority order. */
    @GetMapping(value = "/cast/{id}/refs", produces = "application/json")
    public ResponseEntity<List<Map<String, Object>>> refs(@PathVariable String id) {
        if (!SAFE_ID.matcher(id).matches()) return ResponseEntity.badRequest().build();
        Path refsDir = bibleDir().resolve("refs");
        List<Map<String, Object>> out = new ArrayList<>();
        // Canonical <id>.png|jpg first (this is what Veo/QC pick first).
        for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
            Path p = refsDir.resolve(id + ext);
            if (Files.isRegularFile(p)) {
                out.add(refEntry(id + ext, "canonical", true));
                break;
            }
        }
        // Angle shots in refs/<id>/ (sorted; candidates excluded by pipeline).
        Path sub = refsDir.resolve(id);
        if (Files.isDirectory(sub)) {
            try (var s = Files.list(sub)) {
                s.filter(p -> SAFE_REF_FILE.matcher(p.getFileName().toString()).matches())
                 .sorted()
                 .forEach(p -> {
                     String name = p.getFileName().toString();
                     boolean candidate = name.toLowerCase().contains("candidate");
                     out.add(refEntry(id + "/" + name,
                             candidate ? "ignored (candidate)" : "angle", !candidate));
                 });
            } catch (Exception ignore) { /* listing is best-effort */ }
        }
        return ResponseEntity.ok(out);
    }

    private static Map<String, Object> refEntry(String file, String kind, boolean active) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("file", file);     // relative to bible/refs, may contain one '/'
        m.put("kind", kind);
        m.put("active", active); // false = pipeline ignores it
        return m;
    }

    /** Serves one reference still for review in the Cast page. */
    @GetMapping("/cast/{id}/refs/{file:.+}")
    public ResponseEntity<Resource> refImage(@PathVariable String id, @PathVariable String file) {
        Path p = safeRefPath(id, file);
        if (p == null || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        String mt = p.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.valueOf(mt))
                .body(new FileSystemResource(p));
    }

    /** REJECT a reference still: the file is renamed to *.rejected (not
     *  deleted — reversible), which removes it from both Veo and QC because
     *  the pipeline only picks .png/.jpg files. */
    @DeleteMapping("/cast/{id}/refs/{file:.+}")
    public ResponseEntity<Map<String, String>> rejectRef(@PathVariable String id,
                                                         @PathVariable String file) {
        Path p = safeRefPath(id, file);
        if (p == null || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        try {
            Path rejected = p.resolveSibling(p.getFileName() + ".rejected");
            Files.move(p, rejected, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            return ResponseEntity.ok(Map.of("result", "REJECTED",
                    "note", "hernoemd naar " + rejected.getFileName()
                            + " — terugzetten = .rejected er weer afhalen"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Resolves id+file to a path STRICTLY inside bible/refs (id-prefixed),
     *  or null when the input smells like traversal. */
    private Path safeRefPath(String id, String file) {
        if (!SAFE_ID.matcher(id).matches()) return null;
        String name = file;
        Path refsDir = bibleDir().resolve("refs").normalize();
        Path p;
        if (name.startsWith(id + "/")) {
            String inner = name.substring(id.length() + 1);
            if (!SAFE_REF_FILE.matcher(inner).matches()) return null;
            p = refsDir.resolve(id).resolve(inner).normalize();
        } else {
            if (!SAFE_REF_FILE.matcher(name).matches()) return null;
            if (!name.startsWith(id + ".")) return null;
            p = refsDir.resolve(name).normalize();
        }
        return p.startsWith(refsDir) ? p : null;
    }

    /** The registered music library (id, mood, file presence) so the Brand
     *  page can preview the channel's sonic identity. Reads the bible live —
     *  a freshly generated/replaced track shows up on refresh. */
    @GetMapping(value = "/music", produces = "application/json")
    public List<Map<String, Object>> music() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            JsonNode root = new YAMLMapper().readTree(Paths.get(props.bible().path()).toFile());
            for (JsonNode t : root.path("music").path("tracks")) {
                String id = t.path("id").asText("");
                if (id.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", id);
                m.put("mood", t.path("mood").asText(""));
                m.put("present", Files.exists(bibleDir().resolve("music").resolve(id + ".mp3")));
                out.add(m);
            }
        } catch (Exception ignore) { /* empty list */ }
        return out;
    }

    /** Streams one music track for in-UI preview. */
    @GetMapping(value = "/music/{id}.mp3", produces = "audio/mpeg")
    public ResponseEntity<Resource> musicFile(@PathVariable String id) {
        return audioFile(bibleDir().resolve("music"), id);
    }

    /** Ambient loops per location (bible/sfx/ambient). */
    @GetMapping(value = "/ambient", produces = "application/json")
    public List<String> ambient() {
        List<String> out = new ArrayList<>();
        Path dir = bibleDir().resolve("sfx").resolve("ambient");
        try (var s = Files.list(dir)) {
            s.filter(p -> p.getFileName().toString().endsWith(".mp3"))
             .sorted()
             .forEach(p -> out.add(p.getFileName().toString().replace(".mp3", "")));
        } catch (Exception ignore) { /* empty */ }
        return out;
    }

    /** Streams one ambient loop for in-UI preview. */
    @GetMapping(value = "/ambient/{id}.mp3", produces = "audio/mpeg")
    public ResponseEntity<Resource> ambientFile(@PathVariable String id) {
        return audioFile(bibleDir().resolve("sfx").resolve("ambient"), id);
    }

    /** Shared safe audio streamer: id sanitised and locked inside {@code dir}. */
    private ResponseEntity<Resource> audioFile(Path dir, String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().build();
        Path f = dir.resolve(id + ".mp3").normalize();
        if (!f.startsWith(dir) || !Files.exists(f)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/mpeg"))
                .body(new FileSystemResource(f));
    }

    /** The channel cast (id, name, role, description, accessory) from the bible. */
    @GetMapping(value = "/cast", produces = "application/json")
    public List<Map<String, Object>> cast() {
        List<Map<String, Object>> out = new ArrayList<>();
        try {
            Path p = Paths.get(props.bible().path());
            if (!Files.exists(p)) return out;
            JsonNode root = new YAMLMapper().readTree(p.toFile());
            for (JsonNode c : root.path("characters")) {
                String cid = c.path("id").asText("");
                if (cid.isBlank()) continue;
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", cid);
                m.put("name", c.path("name").asText(cid));
                m.put("role", c.path("role").asText(""));
                m.put("description", c.path("description").asText("").trim());
                m.put("personality", c.path("personality").asText("").trim());
                m.put("accessory", c.path("dna").path("accessory").asText(""));
                m.put("coreColor", c.path("dna").path("coreColor").asText(""));
                m.put("tic", c.path("dna").path("tic").asText(""));
                m.put("signatureSound", c.path("dna").path("signatureSound").asText(""));
                m.put("catchphrasesOpener", joinList(c.path("catchphrases").path("opener")));
                m.put("catchphrasesCloser", joinList(c.path("catchphrases").path("closer")));
                out.add(m);
            }
        } catch (Exception ignore) { /* return whatever parsed */ }
        return out;
    }

    /** Reference image for a character (bible/refs/{id}.png).
     *  Same sanitise-and-lock pattern as {@link #audioFile} — without it,
     *  {@code id} could traverse outside refs/ (../../...). */
    @GetMapping(value = "/character/{id}.png", produces = "image/png")
    public ResponseEntity<Resource> character(@PathVariable String id) {
        if (!id.matches("[A-Za-z0-9._-]+")) return ResponseEntity.badRequest().build();
        Path dir = bibleDir();
        Path refs = (dir == null ? Paths.get("refs") : dir.resolve("refs")).normalize();
        Path img = refs.resolve(id + ".png").normalize();
        if (!img.startsWith(refs) || !Files.exists(img)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store").contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(img));
    }

    /** Joins a JSON string array into newline-separated text (one item per line). */
    private static String joinList(JsonNode arr) {
        if (arr == null || !arr.isArray()) return "";
        StringBuilder b = new StringBuilder();
        for (JsonNode n : arr) {
            if (b.length() > 0) b.append('\n');
            b.append(n.asText(""));
        }
        return b.toString();
    }

    /** Edit a character's scalar fields (name, role, dna.accessory, dna.tic).
     *  Body: {"name": "...", "role": "...", "accessory": "...", "tic": "..."} —
     *  any subset. Comment-preserving + auto-reverts a bad edit (see BibleEditor). */
    @PostMapping(value = "/cast/{id}", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> updateCharacter(@PathVariable String id,
                                                               @RequestBody Map<String, String> fields) {
        try {
            List<String> changed = bibleEditor.updateCharacter(id, fields == null ? Map.of() : fields);
            return ResponseEntity.ok(Map.of("id", id, "changed", changed, "result", "UPDATED",
                    "note", "Cast page updates now; restart orchestrator + image-service for new renders."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Upload a new reference image for a character → bible/refs/{id}.png. */
    @PostMapping(value = "/cast/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> uploadReference(@PathVariable String id,
                                                              @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no file"));
        }
        if (!id.matches("[A-Za-z0-9._-]+")) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid character id"));
        }
        // Content-Type is client-supplied and trivially spoofable — check the
        // actual PNG magic bytes (\x89PNG) and cap the size instead.
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "file too large (max 10MB)"));
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length < 8 || (bytes[0] & 0xFF) != 0x89
                    || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
                return ResponseEntity.badRequest().body(Map.of("error", "not a valid PNG file"));
            }
            Path saved = bibleEditor.saveReference(id, bytes);
            return ResponseEntity.ok(Map.of("id", id, "result", "UPLOADED", "path", saved.toString(),
                    "note", "Reference updated; restart image-service for new renders to use it."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
