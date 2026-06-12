package com.youtubeauto.orchestrator.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.youtubeauto.orchestrator.client.ImageServiceClient;
import com.youtubeauto.orchestrator.client.UploadServiceClient;
import com.youtubeauto.orchestrator.config.OrchestratorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Branding-studio — genereert het KANAAL-logo (avatar) en de KANAAL-banner uit
 * de cast-referenties, met selecteerbare hoofdpersonages, en uploadt de banner
 * rechtstreeks naar YouTube.
 *
 * Eigen controller naast {@link BrandController} (die zit al rond de 1000
 * regels); de patronen — kandidaten-flow via {@link ImageServiceClient},
 * SAFE-regex + normalize/startsWith-padveiligheid, best-effort per kandidaat —
 * zijn daar 1-op-1 vandaan. ANDERS dan de 🎨 generate-ref-route stuurt de
 * scène-payload hier WÉL {@code characters:[geselecteerde ids]} mee (zoals
 * generate-angle): GeminiImageProvider laadt dan per id de bible-refs als
 * beeld-anchors, zodat logo en banner het exacte, goedgekeurde cast-design
 * dragen. De provider capt het totaal op ~9 referenties en krimpt het aantal
 * hoeken per character vanzelf bij 3+ geselecteerde personages — bestaand
 * gedrag, geen extra zorg hier.
 *
 * Bestanden (bible/branding/, een NIEUWE map — bible/logo.png is het
 * TRANSPARANTE intro/outro-overlay-asset; de avatar/banner-flow blijft daar
 * overal vanaf, alleen de expliciete upload-route {@code POST /logo-overlay}
 * en de overlay-approve ({@code kind:"overlay"} — "zelfde bord, nieuwe cast",
 * op magenta gegenereerd en bij approve gechroma-keyed) vervangen het, mét
 * backup en alpha-validatie):
 * <ul>
 *   <li>candidates/{logo|banner|overlay}-N.png — pending kandidaten;</li>
 *   <li>avatar.png — goedgekeurd logo, 800×800 (YouTube-avatar-formaat). De
 *       Data API heeft GÉÉN endpoint voor de profielfoto — eenmalig handmatig
 *       uploaden via studio.youtube.com → Aanpassing → Branding;</li>
 *   <li>banner.png — goedgekeurde banner, 2560×1440. Approve vervangt ook
 *       bible/youtube_banner.jpg (de cast-canon-referentie voor o.a. de
 *       infra-scripts make-anchors.py/analyze-banner.py; oude eerst →
 *       youtube_banner.previous.jpg).</li>
 * </ul>
 *
 * Geen bible-reload nodig: geen enkele service leest youtube_banner.jpg of
 * bible/branding/ bij opstart (geverifieerd — alleen infra-scripts raken de
 * banner aan), dus approve hoeft niets te hot-reloaden.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/brand/branding")
@RequiredArgsConstructor
public class BrandingStudioController {

    // YouTube-maten (bron: YouTube Studio "channel art" specs):
    //   banner-upload: 2560×1440 aanbevolen (minimum 2048×1152, max 6MB);
    //   safe-area:     1546×423, GECENTREERD — alleen die strook is op élk
    //                  apparaat zichtbaar (tv toont alles, mobiel ~1546×423);
    //   avatar:        800×800, wordt overal CIRKELVORMIG bijgesneden.
    private static final int AVATAR_SIZE    = 800;
    private static final int BANNER_WIDTH   = 2560;
    private static final int BANNER_HEIGHT  = 1440;

    private static final java.util.regex.Pattern SAFE_ID =
            java.util.regex.Pattern.compile("[a-z0-9_-]{1,40}");
    /** Kandidaat-bestandsnamen: {kind}-N.png, niets anders. "overlay" =
     *  hergenereer-kandidaten voor bible/logo.png (zelfde bord, nieuwe cast) —
     *  op magenta gegenereerd, bij approve gechroma-keyed naar transparant. */
    private static final java.util.regex.Pattern SAFE_CANDIDATE_FILE =
            java.util.regex.Pattern.compile("(logo|banner|overlay)-[0-9]{1,2}\\.png");
    /** Geldige kinds voor generate/approve/discard. */
    private static final List<String> KINDS = List.of("logo", "banner", "overlay");
    /** Goedgekeurde assets die de UI mag opvragen (download-link / preview). */
    private static final List<String> APPROVED_FILES = List.of("avatar.png", "banner.png");

    /** Max kandidaten per batch — elke kandidaat is een betaalde image-call
     *  (orde ~€0,05-0,10), zelfde cap als BrandController. */
    private static final int MAX_CANDIDATES = 4;

    private final OrchestratorProperties props;
    private final ImageServiceClient imageClient;
    private final UploadServiceClient uploadClient;

    private Path bibleDir() {
        Path bible = Paths.get(props.bible().path());          // .../bible/channel.yml
        return bible.getParent();
    }

    private Path brandingDir()   { return bibleDir().resolve("branding"); }
    private Path candidatesDir() { return brandingDir().resolve("candidates"); }

    // ── Genereren ───────────────────────────────────────────────────────────

    /** Genereert 1-4 kandidaten voor het kanaal-logo, de kanaal-banner of het
     *  OVERLAY-logo (bible/logo.png — zelfde bord/ontwerp, nieuwe cast).
     *  Body: {"kind": "logo"|"banner"|"overlay", "characters": ["pip","mo"],
     *  "count": 3}. De geselecteerde ids gaan als scene.characters mee → de
     *  provider ankert op ál hun goedgekeurde refs. Bij "overlay" reist het
     *  HUIDIGE logo bovendien als styleAnchor mee: de provider krijgt het als
     *  extra referentie met de instructie het ontwerp exact te herhalen en
     *  alleen de personages te vervangen. Best-effort per kandidaat. */
    @PostMapping(value = "/generate", produces = "application/json")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        String kind = String.valueOf(body == null ? "" : body.getOrDefault("kind", "")).trim();
        if (!KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "kind moet 'logo', 'banner' of 'overlay' zijn"));
        }
        List<String> ids = new ArrayList<>();
        Object raw = body.get("characters");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                String id = String.valueOf(o).trim();
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        }
        if (ids.isEmpty() && "overlay".equals(kind)) {
            // Overlay-regeneratie zonder expliciete selectie: de hoofdcast
            // (main + sidekicks) — dezelfde default als de checkbox-rij in de UI.
            ids = defaultMainCastIds();
        }
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "characters: selecteer minstens één personage"));
        }
        // Overlay kent twee routes (UI-vinkje "gebruik oude logo als design-ref"):
        //  - useDesignRef=true (default): het bestaande logo reist als styleAnchor
        //    mee — exact hetzelfde bord, maar het model kan de oude personages
        //    soms terug-kopiëren ondanks de REPAINT-instructie.
        //  - useDesignRef=false (PLAN B): géén design-beeld; het bord wordt
        //    tekstueel beschreven (overlayFreePrompt). Iets ander bord mogelijk,
        //    maar gegarandeerd de NIEUWE cast — er is geen oud beeld om van te
        //    spieken.
        // logo-source.png (origineel vóór de uitsnijding/de-halo) heeft geen
        // weggesneden randen en is de betere design-ref; fallback = logo.png.
        boolean useDesignRef = !Boolean.FALSE.equals(body.get("useDesignRef"));
        Path styleRef = null;
        if ("overlay".equals(kind) && useDesignRef) {
            styleRef = bibleDir().resolve("logo-source.png");
            if (!Files.isRegularFile(styleRef)) styleRef = bibleDir().resolve("logo.png");
            if (!Files.isRegularFile(styleRef)) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "geen bestaand logo gevonden (bible/logo-source.png noch bible/logo.png) "
                                + "— kies 'vrij genereren' (zonder design-referentie)"));
            }
        }
        // Valideer elk id tegen de bible-cast (live gelezen, zoals BrandController).
        List<JsonNode> cast = new ArrayList<>();
        for (String id : ids) {
            if (!SAFE_ID.matcher(id).matches()) {
                return ResponseEntity.badRequest().body(Map.of("error", "ongeldig character-id: " + id));
            }
            JsonNode ch = findCharacter(id);
            if (ch == null) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "character '" + id + "' niet gevonden in de bible"));
            }
            cast.add(ch);
        }
        int count = 3;
        try { count = Integer.parseInt(String.valueOf(body.get("count"))); }
        catch (Exception ignore) { /* default */ }
        count = Math.max(1, Math.min(MAX_CANDIDATES, count));

        String prompt = switch (kind) {
            case "logo"   -> logoPrompt(cast);
            case "banner" -> bannerPrompt(cast);
            // "overlay" — KINDS-gevalideerd; plan B = vrij, zonder design-beeld.
            default       -> useDesignRef ? overlayPrompt(cast) : overlayFreePrompt(cast);
        };
        Path candDir = candidatesDir();
        try {
            Files.createDirectories(candDir);
            deleteKindCandidates(candDir, kind);   // vorige batch van DIT kind opruimen
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
                // HET anker: alle geselecteerde ids → de provider laadt per id
                // refs/{id}.png (of de multi-angle set refs/{id}/*.png) en
                // krimpt de hoeken-per-character zodra het totaal de ref-cap
                // (~9) zou overschrijden — bestaand provider-gedrag.
                scene.put("characters", ids);
                // "studio" is bewust géén bible-timeOfDay-id: geen golden-hour-
                // lichtclausule óver het logo heen; de banner-prompt benoemt
                // golden hour zelf, op de plek waar hij het hebben wil.
                scene.put("timeOfDay", "studio");
                scene.put("cameraFraming", switch (kind) {
                    case "logo"   -> "tight group close-up, heads and shoulders, perfectly centered";
                    case "banner" -> "very wide panoramic establishing shot, characters small and centered";
                    default       -> "flat frontal logo composition, the complete artwork centered "
                                   + "with generous margins on all sides";
                });
                if (styleRef != null) {
                    // Additief veld op de scene-payload (zie GenerateImageRequest.
                    // SceneVisual.styleAnchors in de image-service): het huidige
                    // logo-ontwerp als design-anchor — max 2, hier altijd 1.
                    scene.put("styleAnchors", List.of(styleRef.toString()));
                }
                // format kent alleen landscape|vertical (request-DTO-validatie);
                // het logo wordt dus 16:9 gegenereerd en bij approve naar een
                // gecentreerd vierkant gecropt — de prompt vraagt om royale
                // marges, dus de center-crop is veilig.
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
                Path dst = candDir.resolve(kind + "-" + i + ".png");
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                candidates.add(Map.of("file", dst.getFileName().toString(), "kind", kind));
            } catch (Exception e) {
                errors.add("kandidaat " + i + ": " + e.getMessage());
            } finally {
                deleteTempWorkdir(tempJob);
            }
        }
        if (candidates.isEmpty()) {
            Map<String, Object> err = new LinkedHashMap<>();
            err.put("error", "geen enkele kandidaat gegenereerd");
            err.put("details", errors);
            return ResponseEntity.internalServerError().body(err);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("kind", kind);
        out.put("characters", ids);
        out.put("result", "GENERATED");
        out.put("candidates", candidates);
        if (!errors.isEmpty()) out.put("errors", errors);
        out.put("note", "Kandidaten staan in bible/branding/candidates/ — goedkeuren via "
                + "POST .../branding/approve.");
        return ResponseEntity.ok(out);
    }

    // ── Kandidaten: lijst / serve / weiger ──────────────────────────────────

    /** Pending kandidaten + welke goedgekeurde assets al bestaan (zodat de UI
     *  na een refresh de download-/upload-knoppen kan terugtonen). */
    @GetMapping(value = "/candidates", produces = "application/json")
    public Map<String, Object> candidates() {
        List<Map<String, Object>> out = new ArrayList<>();
        Path candDir = candidatesDir();
        if (Files.isDirectory(candDir)) {
            try (var s = Files.list(candDir)) {
                s.map(p -> p.getFileName().toString())
                 .filter(n -> SAFE_CANDIDATE_FILE.matcher(n).matches())
                 .sorted()
                 // kind = het prefix vóór de streep (logo|banner|overlay) —
                 // gegarandeerd aanwezig door de SAFE_CANDIDATE_FILE-match.
                 .forEach(n -> out.add(Map.of("file", n,
                         "kind", n.substring(0, n.indexOf('-')))));
            } catch (Exception ignore) { /* lijst is best-effort */ }
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("candidates", out);
        resp.put("avatarPresent", Files.isRegularFile(brandingDir().resolve("avatar.png")));
        resp.put("bannerPresent", Files.isRegularFile(brandingDir().resolve("banner.png")));
        return resp;
    }

    /** Serveert één branding-bestand: een kandidaat ({kind}-N.png) of een
     *  goedgekeurd asset (avatar.png / banner.png — de ⬇ Download-link).
     *  Zelfde path-safety-stijl als BrandController.safeRefPath: strikte
     *  naam-whitelist + normalize/startsWith-slot binnen bible/branding. */
    @GetMapping("/file")
    public ResponseEntity<Resource> file(@RequestParam("name") String name) {
        Path p = safeBrandingPath(name);
        if (p == null || !Files.isRegularFile(p)) return ResponseEntity.notFound().build();
        return ResponseEntity.ok().header("Cache-Control", "no-store")
                .contentType(MediaType.IMAGE_PNG)
                .body(new FileSystemResource(p));
    }

    /** Weigert pending kandidaten (🗑). Optioneel ?kind=logo|banner|overlay om
     *  één soort te wissen; zonder kind gaat alles weg. */
    @DeleteMapping("/candidates")
    public ResponseEntity<Map<String, Object>> discardCandidates(
            @RequestParam(value = "kind", required = false) String kind) {
        if (kind != null && !KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "kind moet 'logo', 'banner' of 'overlay' zijn"));
        }
        Path candDir = candidatesDir();
        int removed = kind == null
                ? KINDS.stream().mapToInt(k -> deleteKindCandidates(candDir, k)).sum()
                : deleteKindCandidates(candDir, kind);
        try { Files.deleteIfExists(candDir); } catch (Exception ignore) { /* niet leeg of weg */ }
        return ResponseEntity.ok(Map.of("result", "DISCARDED", "removed", removed));
    }

    // ── Approve: schalen/croppen naar de echte YouTube-maten ────────────────

    /** Promoveert één kandidaat. Body: {"file": "logo-1.png", "kind": "logo"}.
     *  <ul><li>logo → cover-crop naar het gecentreerde vierkant, geschaald naar
     *  800×800, als bible/branding/avatar.png. bible/logo.png (het transparante
     *  intro/outro-overlay) wordt NIET aangeraakt.</li>
     *  <li>banner → cover-crop naar 2560×1440 als bible/branding/banner.png én
     *  als JPEG (quality ~0.9) naar bible/youtube_banner.jpg — de cast-canon-
     *  referentie; de oude gaat eerst naar youtube_banner.previous.jpg.</li>
     *  <li>overlay → chroma-key: de magenta achtergrond eruit (flood-fill vanaf
     *  de randen + rand-feather met kleur-decontaminatie, zie
     *  {@link #chromaKeyMagenta}), gevalideerd met dezelfde
     *  {@link #scanAlpha}-check als de upload-route, daarna backup →
     *  logo.previous.png en vervang bible/logo.png — exact dezelfde staart als
     *  de upload-flow ({@link #writeOverlayLogo}).</li></ul>
     *  Geen bible-reload: geen service leest deze bestanden bij opstart (het
     *  overlay-logo wordt pas bij een intro/outro-re-composite gelezen). */
    @PostMapping(value = "/approve", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody Map<String, String> body) {
        String kind = body == null ? null : body.get("kind");
        if (kind == null || !KINDS.contains(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "kind moet 'logo', 'banner' of 'overlay' zijn"));
        }
        String file = body.get("file");
        Path candDir = candidatesDir().normalize();
        Path cand = file == null ? null : safeBrandingPath(file);
        if (cand == null || !cand.startsWith(candDir) || !Files.isRegularFile(cand)
                || !cand.getFileName().toString().startsWith(kind + "-")) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "file moet een bestaande " + kind + "-kandidaat zijn (" + kind + "-N.png)"));
        }
        try {
            java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(cand.toFile());
            if (src == null) {
                return ResponseEntity.internalServerError().body(Map.of("error",
                        "kandidaat is geen leesbaar beeld: " + file));
            }
            Files.createDirectories(brandingDir());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("kind", kind);
            out.put("result", "APPROVED");

            if ("logo".equals(kind)) {
                java.awt.image.BufferedImage avatar =
                        coverCrop(src, AVATAR_SIZE, AVATAR_SIZE, false);
                Path dst = brandingDir().resolve("avatar.png");
                javax.imageio.ImageIO.write(avatar, "png", dst.toFile());
                out.put("path", dst.toString());
                out.put("note", "Avatar 800×800 opgeslagen. YouTube heeft GÉÉN API voor de "
                        + "profielfoto — download het bestand en upload het eenmalig handmatig "
                        + "via studio.youtube.com → Aanpassing → Branding. bible/logo.png "
                        + "(het transparante intro/outro-overlay) is niet aangeraakt.");
            } else if ("overlay".equals(kind)) {
                // Magenta-kandidaat → transparant overlay-logo. De kandidaat is
                // bewust op effen #FF00FF gegenereerd (zie overlayPrompt) zodat
                // de achtergrond hier deterministisch te scheiden is.
                java.awt.image.BufferedImage keyed = chromaKeyMagenta(src);
                // Dezelfde validatie als de handmatige upload: een overlay is
                // grotendeels transparant, dus ≥2% doorzichtig hoort nu vanzelf
                // te kloppen. Zo niet, dan was de achtergrond niet (effen)
                // magenta en is de key mislukt — geen half werk opslaan.
                AlphaStats stats = scanAlpha(keyed);
                if (stats.transparentFraction() < MIN_TRANSPARENT_FRACTION) {
                    return ResponseEntity.badRequest().body(Map.of("error",
                            "chroma-key kon de achtergrond niet scheiden — kies een "
                                    + "andere kandidaat"));
                }
                java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(keyed, "png", buf);
                Path backup = writeOverlayLogo(buf.toByteArray());
                out.put("path", bibleDir().resolve("logo.png").toString());
                if (backup != null) out.put("backup", backup.toString());
                out.put("transparentPct", Math.round(stats.transparentFraction() * 100));
                out.put("note", "Overlay-logo vervangen (achtergrond automatisch verwijderd"
                        + (backup != null ? "; oude → logo.previous.png" : "") + "). "
                        + "Het zit pas in de bumpers na een ♻ Re-composite van intro én "
                        + "outro (gratis, geen Veo).");
            } else {
                java.awt.image.BufferedImage banner =
                        coverCrop(src, BANNER_WIDTH, BANNER_HEIGHT, true);
                Path dst = brandingDir().resolve("banner.png");
                javax.imageio.ImageIO.write(banner, "png", dst.toFile());

                // youtube_banner.jpg is óók de cast-canon-referentie (infra-
                // scripts): oude versie bewaren, dan vervangen als JPEG ~0.9.
                Path canon = bibleDir().resolve("youtube_banner.jpg");
                boolean hadPrevious = Files.isRegularFile(canon);
                if (hadPrevious) {
                    Files.copy(canon, canon.resolveSibling("youtube_banner.previous.jpg"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                writeJpeg(banner, canon, 0.9f);

                out.put("path", dst.toString());
                out.put("canonPath", canon.toString());
                if (hadPrevious) {
                    out.put("backup", canon.resolveSibling("youtube_banner.previous.jpg").toString());
                }
                out.put("note", "Banner 2560×1440 opgeslagen (safe-area 1546×423 gecentreerd) en "
                        + "bible/youtube_banner.jpg vervangen"
                        + (hadPrevious ? " (oude → youtube_banner.previous.jpg)" : "")
                        + ". Live zetten: POST .../branding/upload-banner.");
            }
            int cleaned = deleteKindCandidates(candDir, kind);
            try { Files.deleteIfExists(candDir); } catch (Exception ignore) { /* niet leeg */ }
            out.put("cleanedCandidates", cleaned);
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Banner live zetten op YouTube (proxy naar de upload-service) ────────

    /** Uploadt de goedgekeurde banner naar YouTube en zet hem live
     *  (channelBanners.insert + channels.update — zie ChannelBannerService in
     *  de upload-service). De upload-service mount de bible niet en accepteert
     *  alleen /workdir-paden, dus de banner wordt eerst naar de gedeelde
     *  workdir gekopieerd. */
    @PostMapping(value = "/upload-banner", produces = "application/json")
    public ResponseEntity<Map<String, Object>> uploadBanner() {
        Path canon = bibleDir().resolve("youtube_banner.jpg");
        if (!Files.isRegularFile(canon)) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "geen goedgekeurde banner (bible/youtube_banner.jpg ontbreekt) — "
                            + "genereer en keur eerst een banner goed"));
        }
        try {
            Path staged = Paths.get("/workdir", "branding", "youtube_banner.jpg");
            Files.createDirectories(staged.getParent());
            Files.copy(canon, staged, StandardCopyOption.REPLACE_EXISTING);

            JsonNode resp = uploadClient.uploadChannelBanner(staged.toString());
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", "UPLOADED");
            out.put("bannerUrl", resp == null ? "" : resp.path("bannerUrl").asText(""));
            out.put("channelId", resp == null ? "" : resp.path("channelId").asText(""));
            out.put("note", "Banner staat live op het kanaal. YouTube cachet channel-art "
                    + "soms even — hard refresh op de kanaalpagina.");
            return ResponseEntity.ok(out);
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException e) {
            // Upload-service stuurt {"error": ...} met 400/502 — geef de echte
            // reden door i.p.v. een kale statuscode (zelfde stijl als api.js).
            String reason = e.getResponseBodyAsString();
            log.warn("channel banner upload failed: {} {}", e.getStatusCode(), reason);
            return ResponseEntity.status(502).body(Map.of("error",
                    "upload-service: " + (reason == null || reason.isBlank()
                            ? e.getStatusCode().toString() : reason)));
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    // ── Overlay-logo (bible/logo.png — het transparante intro/outro-asset) ──

    /** Minimaal aandeel pixels met alpha &lt; 255 om de upload als transparante
     *  overlay te accepteren. Een overlay-logo is in de praktijk grotendeels
     *  transparant (de hele achtergrond), dus 2% is al heel ruimhartig — maar
     *  het vangt het klassieke geval af: een PNG mét alpha-kanaal dat overal
     *  255 is (= effectief een rechthoekige plaat over de video). */
    private static final double MIN_TRANSPARENT_FRACTION = 0.02;
    /** Halo-note vanaf dit aandeel licht/crème binnen de half-transparante
     *  randpixels (zie {@link #scanAlpha}). Bewust ruim — bij twijfel alleen
     *  de note, nooit weigeren. */
    private static final double HALO_LIGHT_SHARE = 0.40;
    /** Onder dit absolute aantal half-transparante pixels is de halo-breuk
     *  statistische ruis (een handvol AA-pixels) — dan geen note. */
    private static final long HALO_MIN_SEMI_PIXELS = 500;

    /** Vervangt bible/logo.png — het TRANSPARANTE overlay-logo dat IntroBuilder
     *  (logo-fly-in linksboven) en OutroBuilder (top-left) bij de intro/outro-
     *  (re)build in de bumpers compositen. Dit is de ENIGE route die dat
     *  bestand schrijft; de avatar/banner-flow hierboven blijft er bewust vanaf.
     *  <ul>
     *    <li>validatie: PNG magic-bytes + ≤10MB (zelfde checks als
     *        BrandController.uploadReference) én een échte alpha-check — een
     *        alpha-KANAAL alleen is niet genoeg, zie comments;</li>
     *    <li>soft-warning (nooit een block) bij een crème-halo-indicatie —
     *        het bekende probleem van dit logo, zie {@link #scanAlpha};</li>
     *    <li>oude versie → bible/logo.previous.png (bestaande backup wordt
     *        overschreven), daarna de rauwe upload-bytes naar bible/logo.png
     *        (geen her-encode — de PNG blijft byte-voor-byte wat de operator
     *        exporteerde).</li>
     *  </ul>
     *  Het nieuwe logo zit pas in de bumpers na een ♻ Re-composite van intro
     *  én outro (gratis, geen Veo) — de respons-note zegt dat er ook bij. */
    @PostMapping(value = "/logo-overlay", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
                 produces = "application/json")
    public ResponseEntity<Map<String, Object>> replaceOverlayLogo(
            @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "no file"));
        }
        if (file.getSize() > 10 * 1024 * 1024) {
            return ResponseEntity.badRequest().body(Map.of("error", "file too large (max 10MB)"));
        }
        try {
            byte[] bytes = file.getBytes();
            // Content-Type is client-supplied en triviaal te spoofen — check de
            // echte PNG magic-bytes (\x89PNG), net als BrandController.uploadReference.
            if (bytes.length < 8 || (bytes[0] & 0xFF) != 0x89
                    || bytes[1] != 'P' || bytes[2] != 'N' || bytes[3] != 'G') {
                return ResponseEntity.badRequest().body(Map.of("error", "not a valid PNG file"));
            }
            java.awt.image.BufferedImage img =
                    javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "PNG kon niet gedecodeerd worden — bestand corrupt?"));
            }
            // Alpha-check in twee stappen: (1) is er überhaupt een alpha-kanaal,
            // en (2) is er ook ÉCHT transparantie? Een PNG kán een alpha-kanaal
            // hebben dat overal 255 is — dan komt er alsnog een dekkende
            // rechthoek over de video te liggen.
            String opaqueMsg = "dit logo komt óver de video — zonder transparantie "
                    + "krijg je een rechthoekig vlak in beeld. Exporteer als PNG met "
                    + "transparante achtergrond en upload opnieuw.";
            if (!img.getColorModel().hasAlpha()) {
                return ResponseEntity.badRequest().body(Map.of("error",
                        "PNG heeft geen alpha-kanaal: " + opaqueMsg));
            }
            AlphaStats stats = scanAlpha(img);
            if (stats.transparentFraction() < MIN_TRANSPARENT_FRACTION) {
                return ResponseEntity.badRequest().body(Map.of("error", String.format(
                        "PNG heeft wel een alpha-kanaal maar is vrijwel volledig dekkend "
                                + "(%.1f%% van de pixels doorzichtig): %s",
                        stats.transparentFraction() * 100, opaqueMsg)));
            }
            String warning = null;
            if (stats.semiCount() >= HALO_MIN_SEMI_PIXELS
                    && stats.lightShareOfSemi() > HALO_LIGHT_SHARE) {
                warning = String.format(
                        "mogelijk een crème-halo: %.0f%% van de half-transparante randpixels "
                                + "is licht/crème — dat gaf eerder een lichte gloed rond het "
                                + "logo over de video. Check de preview op de geblokte "
                                + "achtergrond; het logo is gewoon vervangen (geen blokkade).",
                        stats.lightShareOfSemi() * 100);
            }

            Path backup = writeOverlayLogo(bytes);
            boolean hadPrevious = backup != null;

            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", "REPLACED");
            out.put("path", bibleDir().resolve("logo.png").toString());
            if (hadPrevious) out.put("backup", backup.toString());
            out.put("transparentPct", Math.round(stats.transparentFraction() * 100));
            if (warning != null) out.put("warning", warning);
            out.put("note", "Overlay-logo vervangen"
                    + (hadPrevious ? " (oude → logo.previous.png)" : "")
                    + ". Het zit pas in de bumpers na een ♻ Re-composite van intro én "
                    + "outro (gratis, geen Veo).");
            return ResponseEntity.ok(out);
        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", e.getMessage()));
        }
    }

    /** Alpha-statistiek van de upload: aandeel doorzichtige pixels (alpha &lt;
     *  255), het aantal half-transparante pixels en welk deel dáárvan licht is.
     *
     *  Crème-halo-heuristiek (bewust pragmatisch — levert hooguit een NOTE op,
     *  nooit een weigering): dit logo had eerder half-transparante, lichte
     *  crème randpixels — het residu van matting tegen een lichte achtergrond
     *  bij het uitsnijden. Over de video gecomposit geeft dat een licht gloed-
     *  randje rond het silhouet. De half-transparante pixels (20 &lt; alpha &lt;
     *  235) zijn per definitie de anti-alias-rand van het silhouet — precies
     *  waar een halo leeft. We samplen ze in het hele beeld in plaats van
     *  alleen een band langs de bounding-box-rand: een rond silhouet raakt die
     *  band maar op vier punten, terwijl de halo de hele omtrek volgt. Hoort
     *  zo'n randpixel naar de logokleuren toe te mengen maar is hij licht/crème
     *  (R, G én B ≥ 190), dan telt hij als halo-verdacht; een hoog aandeel
     *  daarvan triggert de note. */
    private static AlphaStats scanAlpha(java.awt.image.BufferedImage img) {
        int w = img.getWidth(), h = img.getHeight();
        long total = (long) w * h;
        long transparent = 0, semi = 0, semiLight = 0;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int argb = img.getRGB(x, y);   // altijd ARGB, ook bij palette-PNG's
                int a = argb >>> 24;
                if (a < 255) transparent++;
                if (a > 20 && a < 235) {
                    semi++;
                    int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                    if (r >= 190 && g >= 190 && b >= 190) semiLight++;
                }
            }
        }
        return new AlphaStats(
                total == 0 ? 0 : transparent / (double) total,
                semi == 0 ? 0 : semiLight / (double) semi,
                semi);
    }

    /** Resultaat van {@link #scanAlpha}. */
    private record AlphaStats(double transparentFraction, double lightShareOfSemi,
                              long semiCount) { }

    /** Backup + vervang bible/logo.png — de GEDEELDE staart van de upload-route
     *  ({@link #replaceOverlayLogo}) en de overlay-approve: oude versie →
     *  logo.previous.png (een bestaande backup wordt overschreven), daarna de
     *  PNG-bytes naar bible/logo.png. Retourneert het backup-pad, of null als
     *  er nog geen logo bestond. */
    private Path writeOverlayLogo(byte[] pngBytes) throws Exception {
        Path logo = bibleDir().resolve("logo.png");
        Path backup = logo.resolveSibling("logo.previous.png");
        boolean hadPrevious = Files.isRegularFile(logo);
        if (hadPrevious) {
            Files.copy(logo, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        Files.write(logo, pngBytes);
        return hadPrevious ? backup : null;
    }

    // ── Chroma-key (overlay-approve): magenta achtergrond → transparant ─────

    /** Max RGB-afstand (Euclidisch; theoretisch maximum ≈441) tot puur magenta
     *  #FF00FF om een pixel tijdens de flood-fill als ACHTERGROND te zien.
     *  90 is ruim genoeg voor compressieruis en de lichte tint-drift die
     *  image-modellen in een "effen" vlak leggen, maar ver onder de afstand
     *  van élke realistische logokleur — crème, hout, geel en groen zitten
     *  allemaal ≥ ~250 van magenta vandaan. */
    private static final int CHROMA_BG_TOLERANCE = 90;
    /** Bredere band voor de rand-feather: een pixel die aan verwijderde
     *  achtergrond grenst en binnen deze afstand van magenta ligt, is deels met
     *  de achtergrond vermengd (anti-aliasing) en krijgt alpha + kleur-
     *  decontaminatie naar rato van die bijmenging. */
    private static final int CHROMA_FEATHER_BAND = 220;

    /** Kwadratische RGB-afstand tot puur magenta (255, 0, 255). */
    private static int distSqToMagenta(int argb) {
        int dr = 255 - ((argb >> 16) & 0xFF);
        int dg = (argb >> 8) & 0xFF;
        int db = 255 - (argb & 0xFF);
        return dr * dr + dg * dg + db * db;
    }

    /** Markeert idx als achtergrond en zet hem op de stack, mits nog niet
     *  bezocht én dicht genoeg bij magenta ({@link #CHROMA_BG_TOLERANCE}). */
    private static void floodPush(java.util.ArrayDeque<Integer> stack, boolean[] bg,
                                  int[] px, int idx) {
        if (bg[idx]) return;
        if (distSqToMagenta(px[idx]) > CHROMA_BG_TOLERANCE * CHROMA_BG_TOLERANCE) return;
        bg[idx] = true;
        stack.push(idx);
    }

    /** Chroma-key voor de overlay-approve: maakt de effen magenta achtergrond
     *  van een kandidaat transparant. Drie stappen:
     *  <ol>
     *    <li>flood-fill vanaf alle vier de randen over bijna-magenta pixels
     *        → alpha 0. Vanaf de randen en niet beeldbreed, zodat magenta-
     *        achtige kleuren BINNEN het artwork (roze kam, paars accent) nooit
     *        weggeslagen worden;</li>
     *    <li>rand-feather: niet-achtergrond-pixels die aan verwijderde
     *        achtergrond grenzen én meetbaar magenta bijgemengd hebben (de
     *        anti-alias-rand) krijgen alpha naar rato van de bijmenging;</li>
     *    <li>kleur-decontaminatie op diezelfde randpixels: de magenta-component
     *        wordt richting de gemiddelde SCHONE (niet-magenta) buurkleur
     *        gemengd — de de-halo-stap. Zonder deze stap blijft er een roze
     *        gloedrand om het silhouet staan zodra het logo over video
     *        gecomposit wordt — exact het crème-halo-probleem dat het oude,
     *        tegen een lichte achtergrond uitgesneden logo had.</li>
     *  </ol> */
    private static java.awt.image.BufferedImage chromaKeyMagenta(
            java.awt.image.BufferedImage src) {
        final int w = src.getWidth(), h = src.getHeight();
        // getRGB levert altijd ARGB, ook bij palette-/grayscale-PNG's.
        final int[] px = src.getRGB(0, 0, w, h, null, 0, w);
        final boolean[] bg = new boolean[w * h];

        // (a) flood-fill: seeds op alle vier de randen, 4-connectiviteit.
        java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
        for (int x = 0; x < w; x++) {
            floodPush(stack, bg, px, x);                    // bovenrand
            floodPush(stack, bg, px, (h - 1) * w + x);      // onderrand
        }
        for (int y = 0; y < h; y++) {
            floodPush(stack, bg, px, y * w);                // linkerrand
            floodPush(stack, bg, px, y * w + (w - 1));      // rechterrand
        }
        while (!stack.isEmpty()) {
            int i = stack.pop();
            int x = i % w, y = i / w;
            if (x > 0)     floodPush(stack, bg, px, i - 1);
            if (x < w - 1) floodPush(stack, bg, px, i + 1);
            if (y > 0)     floodPush(stack, bg, px, i - w);
            if (y < h - 1) floodPush(stack, bg, px, i + w);
        }

        // (b) + (c): feather + decontaminatie, alleen op pixels die aan de
        // verwijderde achtergrond grenzen — het binnenwerk blijft onaangeroerd.
        final int bandSq = CHROMA_FEATHER_BAND * CHROMA_FEATHER_BAND;
        final int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int i = y * w + x;
                if (bg[i]) { out[i] = 0; continue; }        // achtergrond → alpha 0
                int argb = px[i];
                boolean touchesBg = (x > 0 && bg[i - 1]) || (x < w - 1 && bg[i + 1])
                        || (y > 0 && bg[i - w]) || (y < h - 1 && bg[i + w]);
                int dSq = distSqToMagenta(argb);
                if (!touchesBg || dSq >= bandSq) {
                    out[i] = 0xFF000000 | (argb & 0xFFFFFF); // binnenwerk: vol dekkend
                    continue;
                }
                // t = aandeel magenta-bijmenging in deze randpixel: 1 bij puur
                // magenta, 0 aan de buitenkant van de feather-band.
                double t = 1.0 - Math.sqrt(dSq) / CHROMA_FEATHER_BAND;
                int a = (int) Math.round(255 * (1.0 - t));
                // Decontaminatie: gemiddelde van de schone 8-buren (geen
                // achtergrond, buiten de magenta-band) = waar deze rand naartoe
                // hoort te mengen; de pixel schuift daar naar rato van t heen.
                long sr = 0, sg = 0, sb = 0;
                int n = 0;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        if (dx == 0 && dy == 0) continue;
                        int nx = x + dx, ny = y + dy;
                        if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue;
                        int j = ny * w + nx;
                        if (bg[j] || distSqToMagenta(px[j]) < bandSq) continue;
                        sr += (px[j] >> 16) & 0xFF;
                        sg += (px[j] >> 8) & 0xFF;
                        sb += px[j] & 0xFF;
                        n++;
                    }
                }
                int r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                if (n > 0) {
                    r = (int) Math.round(r * (1.0 - t) + (sr / (double) n) * t);
                    g = (int) Math.round(g * (1.0 - t) + (sg / (double) n) * t);
                    b = (int) Math.round(b * (1.0 - t) + (sb / (double) n) * t);
                }
                // Zonder schone buur blijft de eigen kleur staan — de verlaagde
                // alpha drukt de zichtbare magenta-bijdrage dan al fors.
                out[i] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(
                w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, w, h, out, 0, w);
        return img;
    }

    // ── Prompts ─────────────────────────────────────────────────────────────

    /** Avatar-prompt. De refs van de geselecteerde cast reizen als BEELD-
     *  anchors mee (scene.characters); deze tekst regelt compositie + de
     *  cirkel-crop-marges, en de compacte DNA-kern per personage houdt de
     *  provider bij kleur/accessoire/silhouet — zelfde velden als de
     *  generate-ref-DNA, maar bewust kort: het beeld-anchor doet het echte werk. */
    private String logoPrompt(List<JsonNode> cast) {
        StringBuilder b = new StringBuilder();
        b.append("Square channel avatar: ONLY the selected characters together, heads and ")
         .append("shoulders close-up, huddled cheek-to-cheek, joyful, looking at camera, ")
         .append("centered composition with generous margins on all sides (the image will be ")
         .append("CIRCLE-CROPPED — nothing important near the corners or edges), simple warm ")
         .append("sunny background, bold and readable at tiny sizes, NO text anywhere. ");
        appendCastDna(b, cast);
        return b.toString().trim();
    }

    /** Banner-prompt. Echte YouTube-maten ter referentie: upload 2560×1440
     *  (minimum 2048×1152), maar alléén de GECENTREERDE safe-area van 1546×423
     *  is op elk apparaat zichtbaar — tv toont het volledige doek, mobiel
     *  vrijwel alleen de strook. Vandaar: cast in het smalle centrum, de
     *  buitenste delen puur als decor. */
    private String bannerPrompt(List<JsonNode> cast) {
        StringBuilder b = new StringBuilder();
        b.append("YouTube channel banner, wide 16:9 panoramic farm scene at golden hour: ")
         .append("the selected characters together in the CENTER of frame, ALL characters ")
         .append("and any key content within the narrow central safe strip (the outer ")
         .append("thirds left/right and top/bottom edges get cropped on TV and mobile — ")
         .append("keep them as simple scenery), warm inviting, NO text anywhere. ");
        appendCastDna(b, cast);
        return b.toString().trim();
    }

    /** Overlay-logo-prompt ("zelfde bord, nieuwe cast"). Het oude logo reist
     *  als styleAnchor (beeld-referentie) mee — deze tekst pint vast dat ALLEEN
     *  de personages veranderen, en dwingt de effen magenta achtergrond af die
     *  de approve-chroma-key ({@link #chromaKeyMagenta}) er deterministisch uit
     *  haalt. Magenta omdat geen enkele kanaal-kleur (crème, hout, geel, groen)
     *  er ook maar in de buurt komt — anders dan groen/blauw screen-kleuren,
     *  die met gras of lucht zouden botsen. */
    private String overlayPrompt(List<JsonNode> cast) {
        StringBuilder b = new StringBuilder();
        b.append("the channel's logo artwork, EXACTLY the same wooden sign/board design, ")
         .append("lettering and composition as the reference design, but every character ")
         .append("FULLY REPLACED — repainted from scratch — to match the character ")
         .append("reference images (the current, updated designs). The characters in the ")
         .append("old design are outdated and must not be copied: new body shapes, new ")
         .append("sizes, new proportions, exactly as the character references show them. ")
         .append("The artwork is isolated on a perfectly flat, uniform, pure magenta ")
         .append("(#FF00FF) background with NOTHING else — no scenery, no shadows on the ")
         .append("background, no vignette. ");
        appendCastDna(b, cast);
        return b.toString().trim();
    }

    /** PLAN B (vrij genereren, zonder design-beeld): het bord wordt tekstueel
     *  beschreven i.p.v. als referentie meegestuurd. Voordeel: het model heeft
     *  géén oud beeld om de verouderde personages uit te kopiëren — de cast-refs
     *  zijn de enige beeldbron, dus de nieuwe kippen zijn gegarandeerd. Nadeel:
     *  het bord zelf kan licht afwijken van het origineel. Merk-kleuren komen
     *  uit het oude intro-titelontwerp (TINY/CHICKEN goud-geel 0xF0B010,
     *  WORLD blauw 0x3E72C8). */
    private String overlayFreePrompt(List<JsonNode> cast) {
        StringBuilder b = new StringBuilder();
        b.append("the channel's logo artwork: a rustic, warm wooden farm sign board ")
         .append("(weathered planks, slightly rounded corners, a friendly hand-painted ")
         .append("look) with the channel name 'TINY CHICKEN WORLD' painted on it in ")
         .append("cheerful rounded letters — 'TINY' and 'CHICKEN' in warm golden ")
         .append("yellow (#F0B010), 'WORLD' in sky blue (#3E72C8) — and the selected ")
         .append("characters sitting on top of and peeking around the board, joyful ")
         .append("and looking at the camera, drawn EXACTLY as the character reference ")
         .append("images show them (their body shapes, sizes and proportions win over ")
         .append("everything else). A tiny golden egg as a small accent near the board. ")
         .append("The artwork is isolated on a perfectly flat, uniform, pure magenta ")
         .append("(#FF00FF) background with NOTHING else — no scenery, no shadows on ")
         .append("the background, no vignette. ");
        appendCastDna(b, cast);
        return b.toString().trim();
    }

    /** Compacte DNA-kern per geselecteerd personage: kleur + accessoire +
     *  silhouet. Eigen variant van BrandController.appendCharacterDna (die is
     *  privé en bewust uitgebreider; hier doen de beeld-anchors het zware
     *  werk, de tekst pint alleen de drie verwisselbaarste kenmerken vast). */
    private void appendCastDna(StringBuilder b, List<JsonNode> cast) {
        b.append("Exactly ").append(cast.size()).append(" character")
         .append(cast.size() == 1 ? "" : "s").append(" in the image, the EXACT SAME ")
         .append("individuals as the reference images — no extra characters, no duplicates. ");
        for (JsonNode ch : cast) {
            String name = ch.path("name").asText(ch.path("id").asText(""));
            JsonNode d = ch.path("dna");
            b.append(name).append(":");
            String core = d.path("coreColor").asText("").trim();
            if (!core.isEmpty()) b.append(" core colour ").append(core).append(';');
            String acc = d.path("accessory").asText("").trim();
            if (!acc.isEmpty()) b.append(" always wears ").append(acc).append(';');
            String sil = d.path("silhouette").asText("").trim();
            if (!sil.isEmpty()) b.append(" silhouette: ").append(sil).append(';');
            if (b.charAt(b.length() - 1) == ';') b.setLength(b.length() - 1);
            b.append(". ");
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Het character-node uit channel.yml (live gelezen), of null —
     *  zelfde leesroute als BrandController. */
    private JsonNode findCharacter(String id) {
        try {
            JsonNode root = new YAMLMapper().readTree(Paths.get(props.bible().path()).toFile());
            for (JsonNode c : root.path("characters")) {
                if (id.equalsIgnoreCase(c.path("id").asText(""))) return c;
            }
        } catch (Exception ignore) { /* null = niet gevonden */ }
        return null;
    }

    /** Ids van de hoofdcast (role main|sidekick) uit channel.yml — de default
     *  voor overlay-regeneratie zonder expliciete selectie; spiegelt de
     *  checkbox-default in brand-page.js (main + sidekicks aan, baby uit). */
    private List<String> defaultMainCastIds() {
        List<String> ids = new ArrayList<>();
        try {
            JsonNode root = new YAMLMapper().readTree(Paths.get(props.bible().path()).toFile());
            for (JsonNode c : root.path("characters")) {
                String id = c.path("id").asText("");
                String role = c.path("role").asText("");
                if (!id.isEmpty() && ("main".equals(role) || "sidekick".equals(role))) {
                    ids.add(id);
                }
            }
        } catch (Exception ignore) { /* leeg → generate() geeft 400 */ }
        return ids;
    }

    /** Resolves een UI-bestandsnaam STRIKT binnen bible/branding, of null:
     *  kandidaten ({kind}-N.png) onder candidates/, goedgekeurde assets
     *  (avatar.png / banner.png) direct in branding/. */
    private Path safeBrandingPath(String name) {
        if (name == null) return null;
        Path root = brandingDir().normalize();
        Path p;
        if (SAFE_CANDIDATE_FILE.matcher(name).matches()) {
            p = root.resolve("candidates").resolve(name).normalize();
        } else if (APPROVED_FILES.contains(name)) {
            p = root.resolve(name).normalize();
        } else {
            return null;
        }
        return p.startsWith(root) ? p : null;
    }

    /** Schaal + cover-crop naar exact w×h: schalen tot het doel volledig
     *  gevuld is (Graphics2D, bilineair) en het overschot symmetrisch
     *  afsnijden — voor het logo is dat de gecentreerde vierkant-crop uit het
     *  16:9-kanvas, voor de banner vrijwel pure schaling (zelfde ratio).
     *  {@code opaque} = JPEG-bestemming: alpha plat op wit. */
    private static java.awt.image.BufferedImage coverCrop(
            java.awt.image.BufferedImage src, int w, int h, boolean opaque) {
        java.awt.image.BufferedImage out = new java.awt.image.BufferedImage(w, h,
                opaque ? java.awt.image.BufferedImage.TYPE_INT_RGB
                       : java.awt.image.BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g = out.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
                    java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(java.awt.RenderingHints.KEY_RENDERING,
                    java.awt.RenderingHints.VALUE_RENDER_QUALITY);
            if (opaque) {
                g.setColor(java.awt.Color.WHITE);
                g.fillRect(0, 0, w, h);
            }
            double scale = Math.max(w / (double) src.getWidth(), h / (double) src.getHeight());
            int sw = (int) Math.round(src.getWidth() * scale);
            int sh = (int) Math.round(src.getHeight() * scale);
            g.drawImage(src, (w - sw) / 2, (h - sh) / 2, sw, sh, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    /** JPEG-schrijver met expliciete quality (ImageIO.write kent geen
     *  quality-parameter) — zelfde writer-recept als de thumbnail-compressie
     *  in de upload-service. Verwacht een RGB-beeld (coverCrop opaque=true). */
    private static void writeJpeg(java.awt.image.BufferedImage rgb, Path dst, float quality)
            throws Exception {
        javax.imageio.ImageWriter writer =
                javax.imageio.ImageIO.getImageWritersByFormatName("jpeg").next();
        javax.imageio.ImageWriteParam p = writer.getDefaultWriteParam();
        p.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
        p.setCompressionQuality(quality);
        try (javax.imageio.stream.ImageOutputStream ios =
                     javax.imageio.ImageIO.createImageOutputStream(dst.toFile())) {
            writer.setOutput(ios);
            writer.write(null, new javax.imageio.IIOImage(rgb, null, null), p);
        } finally {
            writer.dispose();
        }
    }

    /** Verwijdert alleen de kandidaten van één kind ({kind}-*.png). */
    private int deleteKindCandidates(Path candDir, String kind) {
        int n = 0;
        if (!Files.isDirectory(candDir)) return 0;
        try (var s = Files.list(candDir)) {
            for (Path p : s.filter(Files::isRegularFile).toList()) {
                String name = p.getFileName().toString();
                if (SAFE_CANDIDATE_FILE.matcher(name).matches() && name.startsWith(kind + "-")) {
                    try { if (Files.deleteIfExists(p)) n++; } catch (Exception ignore) { }
                }
            }
        } catch (Exception ignore) { /* best-effort */ }
        return n;
    }

    /** Best-effort: ruimt de /workdir/{uuid}-scratch van één kandidaat-
     *  generatie op (synthetisch jobId — niets anders leest/schrijft daar). */
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
}
