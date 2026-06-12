package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.client.ImageServiceClient;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import com.youtubeauto.orchestrator.service.BibleEditor;
import com.youtubeauto.orchestrator.service.BibleReloadService;
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
 * image (writes via {@link BibleEditor}; needs the read-WRITE bible mount),
 * and AI-generate reference CANDIDATES from the current bible DNA
 * (generate-ref / ref/approve — see the candidates section below), plus extra
 * multi-angle views conditioned on the approved primary ref
 * (generate-angle / angle/approve — see the angles section below).
 */
@RestController
@RequestMapping("/api/v1/brand")
@RequiredArgsConstructor
public class BrandController {

    private final OrchestratorProperties props;
    private final BibleEditor bibleEditor;
    private final BibleReloadService bibleReloadService;
    private final ImageServiceClient imageClient;

    /**
     * Hot-reload van de bible over de hele stack (orchestrator-caches +
     * script-/voice-/image-/thumbnail-/videogen-service). Voor handmatige
     * channel.yml-edits; Cast-edits hieronder triggeren dit al automatisch.
     */
    @PostMapping("/bible/reload")
    public Map<String, Object> reloadBible() {
        return bibleReloadService.reloadAll();
    }

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

    // ── Scene-transition styling (bible assembly.transitions, hot-reloaded) ─

    private static final List<String> VALID_XFADE_TYPES = List.of(
            "fade", "fadeblack", "fadewhite", "fadegrays", "dissolve", "distance",
            "wipeleft", "wiperight", "wipeup", "wipedown",
            "slideleft", "slideright", "slideup", "slidedown",
            "smoothleft", "smoothright", "smoothup", "smoothdown",
            "circlecrop", "rectcrop", "circleopen", "circleclose",
            "horzopen", "horzclose", "vertopen", "vertclose",
            "diagtl", "diagtr", "diagbl", "diagbr",
            "hlslice", "hrslice", "vuslice", "vdslice",
            "pixelize", "radial", "hblur", "zoomin", "squeezev", "squeezeh",
            "coverleft", "coverright", "coverup", "coverdown",
            "revealleft", "revealright", "revealup", "revealdown");

    /** Current per-phase transition config + the valid type menu (for the
     *  Brand-page editor). The assembly hot-reloads the bible ≤1 min, so an
     *  edit here lands on the very next re-assemble — no rebuild. */
    @GetMapping(value = "/transitions", produces = "application/json")
    public Map<String, Object> transitions() {
        Map<String, Object> phases = new LinkedHashMap<>();
        try {
            JsonNode t = new YAMLMapper().readTree(Paths.get(props.bible().path()).toFile())
                    .path("assembly").path("transitions");
            t.fields().forEachRemaining(e -> phases.put(e.getKey(), Map.of(
                    "type", e.getValue().path("type").asText(""),
                    "seconds", e.getValue().path("seconds").asDouble(0))));
        } catch (Exception ignore) { /* empty = section absent */ }
        return Map.of("phases", phases, "validTypes", VALID_XFADE_TYPES);
    }

    /** Update one phase's transition. Body: {phase, type, seconds}. Line-edit
     *  with backup + YAML re-parse; restores on any problem. */
    @PostMapping("/transitions")
    public ResponseEntity<?> setTransition(@RequestBody Map<String, Object> body) {
        String phase = String.valueOf(body.getOrDefault("phase", "")).trim().toLowerCase();
        String type = String.valueOf(body.getOrDefault("type", "")).trim().toLowerCase();
        double seconds;
        try { seconds = Double.parseDouble(String.valueOf(body.get("seconds"))); }
        catch (Exception e) { return ResponseEntity.badRequest().body(Map.of("error", "seconds moet een getal zijn")); }
        if (!phase.matches("[a-z]{2,20}")) return ResponseEntity.badRequest().body(Map.of("error", "ongeldige fase"));
        if (!VALID_XFADE_TYPES.contains(type)) return ResponseEntity.badRequest().body(Map.of("error", "ongeldig xfade-type"));
        seconds = Math.max(0.05, Math.min(1.5, seconds));
        try {
            Path bible = Paths.get(props.bible().path());
            List<String> lines = new ArrayList<>(Files.readAllLines(bible));
            int asm = -1, trans = -1, target = -1;
            for (int i = 0; i < lines.size(); i++) {
                String l = lines.get(i);
                if (asm < 0 && l.startsWith("assembly:")) { asm = i; continue; }
                if (asm >= 0 && trans < 0 && l.startsWith("  transitions:")) { trans = i; continue; }
                if (trans >= 0) {
                    if (!l.isBlank() && !Character.isWhitespace(l.charAt(0))) break; // next top-level key
                    if (l.stripLeading().startsWith(phase + ":")) { target = i; break; }
                }
            }
            if (asm < 0 || trans < 0) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "assembly.transitions-sectie niet gevonden in channel.yml"));
            }
            String newLine = String.format(java.util.Locale.ROOT,
                    "    %-12s { type: %s, seconds: %.2f }", phase + ":", type, seconds);
            if (target >= 0) lines.set(target, newLine);
            else lines.add(trans + 1, newLine);
            // Backup → write → validate → restore on failure (BibleEditor pattern).
            Path bak = bible.resolveSibling("channel.yml.bak");
            Files.copy(bible, bak, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            Files.write(bible, lines);
            try {
                JsonNode check = new YAMLMapper().readTree(bible.toFile());
                String got = check.path("assembly").path("transitions").path(phase).path("type").asText("");
                if (!type.equals(got)) throw new IllegalStateException("edit niet teruggelezen");
            } catch (Exception bad) {
                Files.copy(bak, bible, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                return ResponseEntity.internalServerError().body(Map.of("error",
                        "edit produceerde ongeldige YAML — teruggedraaid: " + bad.getMessage()));
            }
            return ResponseEntity.ok(Map.of("result", "SAVED", "phase", phase,
                    "type", type, "seconds", seconds,
                    "note", "actief binnen ±1 min (hot-reload) — re-assemble om te zien"));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
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

    /** Serves one reference still for review in the Cast page. The filename
     *  travels as ?file= QUERY param: angle refs contain a '/' (pip/01-front.png)
     *  and Tomcat rejects %2F in URL paths by default — the reason the refs
     *  strip showed nothing. */
    @GetMapping("/cast/{id}/ref")
    public ResponseEntity<Resource> refImage(@PathVariable String id,
                                             @RequestParam("file") String file) {
        Path p = safeRefPath(id, file);
        if (p == null || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        String mt = p.toString().toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        return ResponseEntity.ok().contentType(MediaType.valueOf(mt))
                .body(new FileSystemResource(p));
    }

    /** REJECT a reference still: the file is renamed to *.rejected (not
     *  deleted — reversible), which removes it from both Veo and QC because
     *  the pipeline only picks .png/.jpg files. */
    @DeleteMapping("/cast/{id}/ref")
    public ResponseEntity<Map<String, String>> rejectRef(@PathVariable String id,
                                                         @RequestParam("file") String file) {
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
     *  or null when the input smells like traversal. Accepts three shapes:
     *  {@code <id>.png} (canonical), {@code <id>/<file>} (angle shot) and
     *  {@code <id>/candidates/<file>} (pending AI-generated candidate). */
    private Path safeRefPath(String id, String file) {
        if (!SAFE_ID.matcher(id).matches()) return null;
        String name = file;
        Path refsDir = bibleDir().resolve("refs").normalize();
        Path p;
        if (name.startsWith(id + "/candidates/")) {
            String inner = name.substring((id + "/candidates/").length());
            if (!SAFE_REF_FILE.matcher(inner).matches()) return null;
            p = refsDir.resolve(id).resolve("candidates").resolve(inner).normalize();
        } else if (name.startsWith(id + "/")) {
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

    // ── AI-generated reference candidates (Cast page "🎨 Genereer referentie") ─
    //
    // Schone-generatie-route: de scène-payload noemt GEEN characters, dus de
    // image-service hecht NUL bestaande referentie-anchors aan (Gemini laadt
    // anchors per character-id in scene.characters — een lege lijst = tekst-
    // only, describe-achtig gedrag). De volledige character-DNA reist als
    // TEKST mee in visualDesc, zodat een aangescherpte dna.silhouette/build de
    // kandidaat bepaalt en het OUDE referentiebeeld het oude silhouet niet
    // terug kan trekken. Elke kandidaat krijgt een eigen synthetisch jobId →
    // een eigen seed (image-service: sharedSeed = |jobId.hashCode()|) en een
    // eigen /workdir/{uuid}/images-scratchmap, die hierna wordt opgeruimd.
    //
    // Kandidaten landen in bible/refs/{id}/candidates/ — een SUBmap, dus
    // onzichtbaar voor alle pipeline-scans (die lijsten niet-recursief
    // *.png in refs/ en refs/{id}/), en de naam bevat "candidate" als
    // dubbele beveiliging (CharacterRefStills/CharacterRefs filteren daarop).

    /** Max kandidaten per batch — elke kandidaat is een betaalde image-call
     *  (orde ~€0,05-0,10). */
    private static final int MAX_REF_CANDIDATES = 4;

    /** Genereert 1-4 AI-referentiekandidaten voor een character vanuit de
     *  ACTUELE bible-DNA (neutrale studio-referentiesheet, geen oude anchors).
     *  Body: {"count": 3} (optioneel, default 3). Best-effort per kandidaat:
     *  een mislukte variant blokkeert de rest niet. */
    @PostMapping(value = "/cast/{id}/generate-ref", produces = "application/json")
    public ResponseEntity<Map<String, Object>> generateReference(
            @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        if (!SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid character id"));
        }
        JsonNode ch = findCharacter(id);
        if (ch == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "character '" + id + "' niet gevonden in de bible"));
        }
        int count = 3;
        try { count = Integer.parseInt(String.valueOf(body == null ? null : body.get("count"))); }
        catch (Exception ignore) { /* default */ }
        count = Math.max(1, Math.min(MAX_REF_CANDIDATES, count));

        String prompt = refSheetPrompt(ch);
        Path candDir = bibleDir().resolve("refs").resolve(id).resolve("candidates");
        try {
            Files.createDirectories(candDir);
            deleteRegularFiles(candDir);   // vorige, nooit-goedgekeurde batch opruimen
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error",
                    "kan kandidaten-map niet aanmaken: " + e.getMessage()));
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            java.util.UUID tempJob = java.util.UUID.randomUUID();
            try {
                Map<String, Object> scene = new LinkedHashMap<>();
                scene.put("seq", 1);
                scene.put("visualDesc", prompt);
                // Lege characters-lijst = de schone route: geen anchors, de
                // DNA-tekst in visualDesc bepaalt de vorm.
                scene.put("characters", List.of());
                // "studio" is bewust géén bible-timeOfDay-id, zodat er geen
                // goldenHour-lichtclausule over de neutrale setup heen komt.
                scene.put("timeOfDay", "studio");
                scene.put("cameraFraming",
                        "full body shot, the whole character visible from head to feet, centered");
                JsonNode resp = imageClient.generate(tempJob, List.of(scene), "landscape");
                Path src = null;
                for (JsonNode s : resp.path("scenes")) {
                    String p = s.path("imagePath").asText("");
                    if (!p.isBlank()) { src = Paths.get(p); break; }
                }
                if (src == null || !Files.isReadable(src)) {
                    // Pad uit de respons is image-service-lokaal; de gedeelde
                    // /workdir-mount (zelfde volume) is de fallback.
                    Path alt = Paths.get("/workdir", tempJob.toString(), "images", "scene_01.png");
                    if (Files.isReadable(alt)) src = alt;
                }
                if (src == null || !Files.isReadable(src)) {
                    throw new IllegalStateException(
                            "gegenereerd bestand niet leesbaar via de gedeelde workdir");
                }
                Path dst = candDir.resolve("candidate-" + i + ".png");
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                candidates.add(Map.of("file", id + "/candidates/" + dst.getFileName()));
            } catch (Exception e) {
                errors.add("kandidaat " + i + ": " + e.getMessage());
            } finally {
                deleteTempWorkdir(tempJob);   // scratch van de synthetische job
            }
        }
        if (candidates.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "geen enkele kandidaat gegenereerd");
            err.put("details", errors);
            return ResponseEntity.internalServerError().body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("result", "GENERATED");
        out.put("candidates", candidates);
        if (!errors.isEmpty()) out.put("errors", errors);
        out.put("note", "Kandidaten staan in bible/refs/" + id + "/candidates/ en worden door "
                + "de pipeline genegeerd tot je er één goedkeurt via POST .../ref/approve.");
        return ResponseEntity.ok(out);
    }

    /** Pending AI-kandidaten voor een character (refs/{id}/candidates/*.png) —
     *  zo kan de Cast-pagina een eerdere batch na een refresh terugtonen. */
    @GetMapping(value = "/cast/{id}/ref/candidates", produces = "application/json")
    public ResponseEntity<List<Map<String, Object>>> refCandidates(@PathVariable String id) {
        if (!SAFE_ID.matcher(id).matches()) return ResponseEntity.badRequest().build();
        List<Map<String, Object>> out = new ArrayList<>();
        Path candDir = bibleDir().resolve("refs").resolve(id).resolve("candidates");
        if (Files.isDirectory(candDir)) {
            try (var s = Files.list(candDir)) {
                s.filter(p -> SAFE_REF_FILE.matcher(p.getFileName().toString()).matches())
                 .sorted()
                 .forEach(p -> out.add(Map.of("file", id + "/candidates/" + p.getFileName())));
            } catch (Exception ignore) { /* lijst is best-effort */ }
        }
        return ResponseEntity.ok(out);
    }

    /** Weigert ALLE pending kandidaten van een character (🗑 Weiger alles). */
    @DeleteMapping("/cast/{id}/ref/candidates")
    public ResponseEntity<Map<String, Object>> discardCandidates(@PathVariable String id) {
        if (!SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid character id"));
        }
        Path candDir = bibleDir().resolve("refs").resolve(id).resolve("candidates");
        int removed = deleteRegularFiles(candDir);
        try { Files.deleteIfExists(candDir); } catch (Exception ignore) { /* niet leeg of weg */ }
        return ResponseEntity.ok(Map.of("id", id, "result", "DISCARDED", "removed", removed));
    }

    /** Promoveert één kandidaat tot canonieke referentie. Body: {"file":
     *  "{id}/candidates/candidate-1.png"}. Schrijft refs/{id}.png via
     *  {@link BibleEditor#saveReference} (oude referentie → refs/{id}.png.bak),
     *  ruimt daarna op wat na een redesign stale is: de overige kandidaten, de
     *  multi-angle map refs/{id}/ (oude hoeken) en refs/series/*&#47;{id}.png
     *  (serie-anchors dragen de oude look), en hot-reloadt de bible stack-breed. */
    @PostMapping(value = "/cast/{id}/ref/approve", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> approveRef(@PathVariable String id,
                                                          @RequestBody Map<String, String> body) {
        if (!SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid character id"));
        }
        String file = body == null ? null : body.get("file");
        Path candDir = bibleDir().resolve("refs").resolve(id).resolve("candidates").normalize();
        Path cand = file == null ? null : safeRefPath(id, file);
        if (cand == null || !cand.startsWith(candDir) || !Files.isRegularFile(cand)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "file moet een bestaande kandidaat zijn (" + id + "/candidates/...)"));
        }
        try {
            byte[] png = Files.readAllBytes(cand);
            // Zelfde promotie-pad als de upload-flow: refs/{id}.png + backup .bak.
            Path saved = bibleEditor.saveReference(id, png);

            // Opruimen — alles hieronder is na een redesign stale:
            int candidatesRemoved = deleteRegularFiles(candDir);          // incl. de gekozen kandidaat
            try { Files.deleteIfExists(candDir); } catch (Exception ignore) { }
            Path angleDir = bibleDir().resolve("refs").resolve(id);
            int anglesRemoved = deleteRegularFiles(angleDir);             // oude multi-angle hoeken
            try { Files.deleteIfExists(angleDir); } catch (Exception ignore) { }
            int seriesRemoved = deleteSeriesAnchors(id);                  // serie-anchors = oude look

            Map<String, Object> reload = bibleReloadService.reloadAll();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("result", "APPROVED");
            out.put("path", saved.toString());
            out.put("backup", saved + ".bak (vorige referentie — terugzetten = hernoemen)");
            out.put("cleaned", Map.of(
                    "candidates", candidatesRemoved,
                    "staleAngles", anglesRemoved,
                    "seriesAnchors", seriesRemoved));
            out.put("bibleReload", reload);
            out.put("note", "Nieuwe referentie actief (bible hot-reloaded). Opgeruimd: "
                    + candidatesRemoved + " kandida(a)t(en), " + anglesRemoved
                    + " stale multi-angle ref(s) uit refs/" + id + "/ en " + seriesRemoved
                    + " serie-anchor(s) uit refs/series/*/" + id + ".png — die droegen "
                    + "allemaal nog de oude look.");
            return ResponseEntity.ok(out);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Multi-angle hoeken, geconditioneerd op de primaire ref (Story G) ────
    //
    // Verschil met generate-ref hierboven: de scène-payload noemt het character
    // WÉL (characters:[id]), zodat GeminiImageProvider.loadCharacterAnchors de
    // primaire ref refs/{id}.png als BEELD-anchor meestuurt — de hoek wordt zo
    // een rotatie van dat exacte individu i.p.v. een nieuw tekst-only ontwerp.
    // Direct na ref/approve is refs/{id}/ leeg of afwezig (alleen eventueel de
    // candidates/-submap, die de niet-recursieve *.png-scan van de provider
    // nooit raakt), dus de anchor-set is dan precies de primaire ref. Zodra een
    // eerste hoek is goedgekeurd anchort een vólgende hoek-generatie op de
    // pngs in refs/{id}/ (provider-gedrag: map-met-pngs wint van de single
    // ref); die hoeken zijn zelf op de primaire ref geconditioneerd, dus de
    // identiteitsketen blijft intact.
    //
    // Hoeken-whitelist: "front" bestaat bewust niet (de primaire ref ÍS het
    // vooraanzicht) en driekwart-VÓÓR bieden we bewust niet aan — die hoek gaf
    // historisch wimper-drift bij Mo (de ogen kregen er lashes bij die daarna
    // als canon doorsijpelden in episode-anchors).
    private static final Map<String, String> ANGLE_VIEWS = Map.of(
            "side", "side profile facing left",
            "back34", "three-quarter view from behind");

    /** Harde cap op het aantal hoek-refs in refs/{id}/ — gespiegeld aan
     *  GeminiImageProvider.MAX_ANGLES (de provider leest er toch maar 3,
     *  gesorteerd; meer toestaan zou alleen verwarren welke 3 meegaan). */
    private static final int MAX_PROVIDER_ANGLES = 3;

    /** Genereert 1-4 kandidaten voor één extra hoek van een character,
     *  geconditioneerd op de primaire ref (zie het blok-comment hierboven).
     *  Body: {"angle": "side"|"back34", "count": 3}. Kandidaten landen als
     *  refs/{id}/candidates/candidate-{angle}-{i}.png — dezelfde map en
     *  "candidate"-naamconventie als generate-ref, dus het bestaande
     *  serve-/GET-/DELETE-candidates-pad lift gewoon mee. */
    @PostMapping(value = "/cast/{id}/generate-angle", produces = "application/json")
    public ResponseEntity<Map<String, Object>> generateAngle(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        if (!SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid character id"));
        }
        String angle = String.valueOf(body == null ? "" : body.getOrDefault("angle", "")).trim();
        if (!ANGLE_VIEWS.containsKey(angle)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "angle moet 'side' of 'back34' zijn (front = de primaire ref zelf; "
                            + "driekwart-vóór bieden we bewust niet aan)"));
        }
        JsonNode ch = findCharacter(id);
        if (ch == null) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "character '" + id + "' niet gevonden in de bible"));
        }
        // Zonder primaire ref is er niets om op te conditioneren — de provider
        // zou tekst-only terugvallen en dat is precies wat we hier NIET willen.
        // (.png expliciet: de single-anchor-fallback van de provider leest
        // alleen refs/{id}.png, geen .jpg.)
        if (!Files.isRegularFile(bibleDir().resolve("refs").resolve(id + ".png"))
                && activeAngleFiles(id).isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "geen primaire referentie (refs/" + id + ".png) — genereer en keur eerst "
                            + "een 🎨-referentie goed, dan pas hoeken"));
        }
        int count = 3;
        try { count = Integer.parseInt(String.valueOf(body.get("count"))); }
        catch (Exception ignore) { /* default */ }
        count = Math.max(1, Math.min(MAX_REF_CANDIDATES, count));

        String prompt = angleSheetPrompt(ch, angle);
        Path candDir = bibleDir().resolve("refs").resolve(id).resolve("candidates");
        try {
            Files.createDirectories(candDir);
            // Alleen de vorige batch van DEZE hoek opruimen — pending primaire
            // of andere-hoek-kandidaten blijven staan.
            deleteAngleCandidates(candDir, angle);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error",
                    "kan kandidaten-map niet aanmaken: " + e.getMessage()));
        }

        List<Map<String, Object>> candidates = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            java.util.UUID tempJob = java.util.UUID.randomUUID();
            try {
                Map<String, Object> scene = new LinkedHashMap<>();
                scene.put("seq", 1);
                scene.put("visualDesc", prompt);
                // HET verschil met generate-ref: characters:[id] → de provider
                // laadt refs/{id}.png (of reeds goedgekeurde hoeken) als anchor.
                scene.put("characters", List.of(id));
                scene.put("timeOfDay", "studio");
                scene.put("cameraFraming",
                        "full body shot, the whole character visible from head to feet, centered");
                JsonNode resp = imageClient.generate(tempJob, List.of(scene), "landscape");
                Path src = null;
                for (JsonNode s : resp.path("scenes")) {
                    String p = s.path("imagePath").asText("");
                    if (!p.isBlank()) { src = Paths.get(p); break; }
                }
                if (src == null || !Files.isReadable(src)) {
                    Path alt = Paths.get("/workdir", tempJob.toString(), "images", "scene_01.png");
                    if (Files.isReadable(alt)) src = alt;
                }
                if (src == null || !Files.isReadable(src)) {
                    throw new IllegalStateException(
                            "gegenereerd bestand niet leesbaar via de gedeelde workdir");
                }
                Path dst = candDir.resolve("candidate-" + angle + "-" + i + ".png");
                Files.copy(src, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                candidates.add(Map.of("file", id + "/candidates/" + dst.getFileName(),
                        "angle", angle));
            } catch (Exception e) {
                errors.add("kandidaat " + i + ": " + e.getMessage());
            } finally {
                deleteTempWorkdir(tempJob);
            }
        }
        if (candidates.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "geen enkele hoek-kandidaat gegenereerd");
            err.put("details", errors);
            return ResponseEntity.internalServerError().body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", id);
        out.put("angle", angle);
        out.put("result", "GENERATED");
        out.put("candidates", candidates);
        if (!errors.isEmpty()) out.put("errors", errors);
        out.put("note", "Hoek-kandidaten staan in bible/refs/" + id + "/candidates/ en worden "
                + "door de pipeline genegeerd tot je er één goedkeurt via POST .../angle/approve.");
        return ResponseEntity.ok(out);
    }

    /** Promoveert één hoek-kandidaat naar refs/{id}/{angle}.png. Body:
     *  {"file": "{id}/candidates/candidate-side-1.png", "angle": "side"}.
     *  ADDITIEF: de primaire ref blijft staan, er wordt geen map geleegd en
     *  geen serie-anchor weggegooid — alleen de kandidaten van deze hoek
     *  worden opgeruimd. Cap: het resultaat mag max {@link #MAX_PROVIDER_ANGLES}
     *  hoek-refs opleveren (de provider leest er toch maar 3). */
    @PostMapping(value = "/cast/{id}/angle/approve", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> approveAngle(@PathVariable String id,
                                                            @RequestBody Map<String, String> body) {
        if (!SAFE_ID.matcher(id).matches()) {
            return ResponseEntity.badRequest().body(Map.of("error", "invalid character id"));
        }
        String angle = body == null ? null : body.get("angle");
        if (angle == null || !ANGLE_VIEWS.containsKey(angle)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "angle moet 'side' of 'back34' zijn"));
        }
        String file = body.get("file");
        Path candDir = bibleDir().resolve("refs").resolve(id).resolve("candidates").normalize();
        Path cand = file == null ? null : safeRefPath(id, file);
        if (cand == null || !cand.startsWith(candDir) || !Files.isRegularFile(cand)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "file moet een bestaande kandidaat zijn (" + id + "/candidates/...)"));
        }
        if (!cand.getFileName().toString().startsWith("candidate-" + angle + "-")) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "file hoort niet bij angle '" + angle + "' — verwacht candidate-" + angle + "-*.png"));
        }
        List<String> existing = activeAngleFiles(id);
        boolean replaces = existing.contains(angle + ".png");
        int resulting = existing.size() + (replaces ? 0 : 1);
        if (resulting > MAX_PROVIDER_ANGLES) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "refs/" + id + "/ zou dan " + resulting + " hoeken bevatten; de provider "
                            + "gebruikt er maximaal " + MAX_PROVIDER_ANGLES + " — verwijder eerst "
                            + "een hoek via de bestaande 🗑 in de refs-lijst "
                            + "(DELETE /cast/" + id + "/ref?file=...)"));
        }
        try {
            Path angleDir = bibleDir().resolve("refs").resolve(id);
            Files.createDirectories(angleDir);
            Path dst = angleDir.resolve(angle + ".png");
            Files.copy(cand, dst, java.nio.file.StandardCopyOption.REPLACE_EXISTING);

            int cleaned = deleteAngleCandidates(candDir, angle);   // incl. de gekozen kandidaat
            try { Files.deleteIfExists(candDir); } catch (Exception ignore) { /* niet leeg */ }

            Map<String, Object> reload = bibleReloadService.reloadAll();
            List<String> angles = activeAngleFiles(id);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("id", id);
            out.put("angle", angle);
            out.put("result", "APPROVED");
            out.put("path", dst.toString());
            out.put("angles", angles);
            out.put("angleCount", angles.size());
            out.put("cleanedCandidates", cleaned);
            out.put("bibleReload", reload);
            out.put("note", "Hoek '" + angle + "' actief (bible hot-reloaded). " + id + " heeft nu "
                    + angles.size() + " hoek-ref(s) in refs/" + id + "/ naast de primaire ref"
                    + (replaces ? " (bestaande " + angle + ".png vervangen)" : "")
                    + " — additief: primaire ref, overige hoeken en serie-anchors zijn ongemoeid.");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Actieve hoek-refs DIRECT in refs/{id}/ (gesorteerd): .png/.jpg-bestanden,
     *  kandidaten ("candidate" in de naam) en .rejected uitgesloten — dezelfde
     *  selectie die de pipeline (en de cap-check) hanteert. */
    private List<String> activeAngleFiles(String id) {
        List<String> out = new ArrayList<>();
        Path dir = bibleDir().resolve("refs").resolve(id);
        if (!Files.isDirectory(dir)) return out;
        try (var s = Files.list(dir)) {
            s.filter(Files::isRegularFile)
             .map(p -> p.getFileName().toString())
             .filter(n -> SAFE_REF_FILE.matcher(n).matches())
             .filter(n -> !n.toLowerCase().contains("candidate"))
             .sorted()
             .forEach(out::add);
        } catch (Exception ignore) { /* best-effort */ }
        return out;
    }

    /** Verwijdert alleen de kandidaten van één hoek (candidate-{angle}-*). */
    private int deleteAngleCandidates(Path candDir, String angle) {
        int n = 0;
        if (!Files.isDirectory(candDir)) return 0;
        try (var s = Files.list(candDir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                if (p.getFileName().toString().startsWith("candidate-" + angle + "-")) {
                    try { if (Files.deleteIfExists(p)) n++; } catch (Exception ignore) { }
                }
            }
        } catch (Exception ignore) { /* best-effort */ }
        return n;
    }

    /** Het character-node uit channel.yml (live gelezen), of null. */
    private JsonNode findCharacter(String id) {
        try {
            JsonNode root = new YAMLMapper().readTree(Paths.get(props.bible().path()).toFile());
            for (JsonNode c : root.path("characters")) {
                if (id.equalsIgnoreCase(c.path("id").asText(""))) return c;
            }
        } catch (Exception ignore) { /* null = niet gevonden */ }
        return null;
    }

    /** Neutrale studio-referentiesheet-prompt. Het character wordt puur in
     *  TEKST beschreven (description + dna.*), zodat een geredesignde
     *  silhouette/build in de bible het beeld bepaalt. */
    private String refSheetPrompt(JsonNode ch) {
        String name = ch.path("name").asText(ch.path("id").asText(""));
        StringBuilder b = new StringBuilder();
        b.append("Character reference sheet: ").append(name)
         .append(" standing in a relaxed neutral three-quarter pose, full body visible from ")
         .append("head to feet, on a plain soft warm cream background, even soft lighting, ")
         .append("no scene, no other characters, no props beyond the signature accessories. ")
         .append("Exactly ONE character in the whole image — no second character, no twin, ")
         .append("no clone, no reflection. ");
        appendCharacterDna(b, ch);
        return b.toString().trim();
    }

    /** Turnaround-prompt voor één extra hoek, GECONDITIONEERD op de primaire
     *  ref: de scène-payload noemt het character (characters:[id]), dus de
     *  image-service stuurt refs/{id}.png als beeld-anchor mee en deze tekst
     *  instrueert een rotatie van dat exacte individu — geen nieuw ontwerp. */
    private String angleSheetPrompt(JsonNode ch, String angle) {
        StringBuilder b = new StringBuilder();
        b.append("Character turnaround sheet: the EXACT SAME individual as the reference ")
         .append("image, now seen in full ").append(ANGLE_VIEWS.get(angle))
         .append(", full body head to feet, same relaxed neutral standing pose, plain soft ")
         .append("warm cream background, even soft lighting, identical colours, accessories ")
         .append("and proportions — this is the same character rotated, not a new design. ")
         .append("Exactly ONE character in the whole image — no second character, no twin, ")
         .append("no clone, no reflection. ");
        appendCharacterDna(b, ch);
        return b.toString().trim();
    }

    /** Gedeelde DNA-staart van de referentie-/turnaround-prompts: lifeStage,
     *  description en de dna.*-velden — veld-voor-veld gespiegeld aan
     *  PromptComposer.dnaLine in de image-service (minus weight: dat is een
     *  Veo-motion-cue, geen still-eigenschap). Door refSheetPrompt en
     *  angleSheetPrompt hier te laten delen kan de DNA-opsomming nooit
     *  uiteenlopen tussen de primaire ref en de hoeken. */
    private void appendCharacterDna(StringBuilder b, JsonNode ch) {
        String name = ch.path("name").asText(ch.path("id").asText(""));
        JsonNode d = ch.path("dna");
        String life = ch.path("lifeStage").asText("").trim();
        if (!life.isBlank()) b.append(name).append(" is ").append(life).append(". ");
        String desc = ch.path("description").asText("").trim();
        if (!desc.isBlank()) b.append("APPEARANCE — ").append(desc.replaceAll("\\s+", " ")).append(' ');
        b.append("CHARACTER DNA (every detail must be clearly visible and correct): ");
        appendDna(b, "Core colour", d.path("coreColor").asText(""));
        String acc = d.path("accessory").asText("").trim();
        if (!acc.isBlank()) {
            b.append("ALWAYS wears ").append(acc)
             .append(" — clearly visible, never dropped or swapped. ");
        }
        appendDna(b, "Silhouette", d.path("silhouette").asText(""));
        appendDna(b, "Feathers", d.path("feathers").asText(""));
        appendDna(b, "Build", d.path("build").asText(""));
        appendDna(b, "Eyes", d.path("eyeColor").asText(""));
        String anti = d.path("antiAccessory").asText("").trim();
        if (!anti.isBlank()) b.append(name).append(" must NEVER wear ").append(anti).append(". ");
    }

    private void appendDna(StringBuilder b, String label, String value) {
        String v = value == null ? "" : value.trim();
        if (!v.isEmpty()) b.append(label).append(": ").append(v).append(". ");
    }

    /** Best-effort: verwijdert de gewone bestanden DIRECT in {@code dir}
     *  (niet recursief — submappen zoals candidates/ blijven staan). */
    private int deleteRegularFiles(Path dir) {
        int n = 0;
        if (!Files.isDirectory(dir)) return 0;
        try (var s = Files.list(dir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                try { if (Files.deleteIfExists(p)) n++; } catch (Exception ignore) { }
            }
        } catch (Exception ignore) { /* best-effort */ }
        return n;
    }

    /** Verwijdert refs/series/*&#47;{id}.png|jpg — de serie-anchors van dit
     *  character dragen na een redesign nog de OUDE look. */
    private int deleteSeriesAnchors(String id) {
        int n = 0;
        Path seriesRoot = bibleDir().resolve("refs").resolve("series");
        if (!Files.isDirectory(seriesRoot)) return 0;
        try (var dirs = Files.list(seriesRoot)) {
            for (Path d : dirs.filter(Files::isDirectory).toList()) {
                for (String ext : new String[]{".png", ".jpg", ".jpeg"}) {
                    try { if (Files.deleteIfExists(d.resolve(id + ext))) n++; }
                    catch (Exception ignore) { }
                }
            }
        } catch (Exception ignore) { /* best-effort */ }
        return n;
    }

    /** Best-effort: ruimt de /workdir/{uuid}-scratch van één kandidaat-
     *  generatie op. Het uuid is synthetisch (geen echte job), dus niets
     *  anders schrijft of leest daar. */
    private void deleteTempWorkdir(java.util.UUID tempJob) {
        try {
            Path dir = Paths.get("/workdir", tempJob.toString());
            if (!Files.isDirectory(dir)) return;
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignore) { }
                });
            }
        } catch (Exception ignore) { /* scratch-opruiming is best-effort */ }
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
            // Hot-reload de bible-caches stack-breed — de edit stuurt nieuwe
            // renders direct, geen herstart meer nodig.
            Map<String, Object> reload = bibleReloadService.reloadAll();
            return ResponseEntity.ok(Map.of("id", id, "changed", changed, "result", "UPDATED",
                    "bibleReload", reload,
                    "note", "Bible hot-reloaded across services; new renders use this edit immediately."));
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
            // Hot-reload zodat de image-service de nieuwe referentie meteen pakt.
            Map<String, Object> reload = bibleReloadService.reloadAll();
            return ResponseEntity.ok(Map.of("id", id, "result", "UPLOADED", "path", saved.toString(),
                    "bibleReload", reload,
                    "note", "Reference updated and bible hot-reloaded; new renders use it immediately."));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }
}
