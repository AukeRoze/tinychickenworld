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
 * TRANSPARANTE intro/outro-overlay-asset en blijft hier overal vanaf):
 * <ul>
 *   <li>candidates/{logo|banner}-N.png — pending kandidaten;</li>
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
    /** Kandidaat-bestandsnamen: {kind}-N.png, niets anders. */
    private static final java.util.regex.Pattern SAFE_CANDIDATE_FILE =
            java.util.regex.Pattern.compile("(logo|banner)-[0-9]{1,2}\\.png");
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

    /** Genereert 1-4 kandidaten voor het kanaal-logo of de kanaal-banner.
     *  Body: {"kind": "logo"|"banner", "characters": ["pip","mo"], "count": 3}.
     *  De geselecteerde ids gaan als scene.characters mee → de provider ankert
     *  op ál hun goedgekeurde refs. Best-effort per kandidaat. */
    @PostMapping(value = "/generate", produces = "application/json")
    public ResponseEntity<Map<String, Object>> generate(@RequestBody Map<String, Object> body) {
        String kind = String.valueOf(body == null ? "" : body.getOrDefault("kind", "")).trim();
        if (!"logo".equals(kind) && !"banner".equals(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind moet 'logo' of 'banner' zijn"));
        }
        List<String> ids = new ArrayList<>();
        Object raw = body.get("characters");
        if (raw instanceof List<?> l) {
            for (Object o : l) {
                String id = String.valueOf(o).trim();
                if (!id.isEmpty() && !ids.contains(id)) ids.add(id);
            }
        }
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error",
                    "characters: selecteer minstens één personage"));
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

        String prompt = "logo".equals(kind) ? logoPrompt(cast) : bannerPrompt(cast);
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
                scene.put("cameraFraming", "logo".equals(kind)
                        ? "tight group close-up, heads and shoulders, perfectly centered"
                        : "very wide panoramic establishing shot, characters small and centered");
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
                 .forEach(n -> out.add(Map.of("file", n,
                         "kind", n.startsWith("logo-") ? "logo" : "banner")));
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

    /** Weigert pending kandidaten (🗑). Optioneel ?kind=logo|banner om één
     *  soort te wissen; zonder kind gaat alles weg. */
    @DeleteMapping("/candidates")
    public ResponseEntity<Map<String, Object>> discardCandidates(
            @RequestParam(value = "kind", required = false) String kind) {
        if (kind != null && !"logo".equals(kind) && !"banner".equals(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind moet 'logo' of 'banner' zijn"));
        }
        Path candDir = candidatesDir();
        int removed = kind == null
                ? deleteKindCandidates(candDir, "logo") + deleteKindCandidates(candDir, "banner")
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
     *  referentie; de oude gaat eerst naar youtube_banner.previous.jpg.</li></ul>
     *  Geen bible-reload: geen service leest deze bestanden bij opstart. */
    @PostMapping(value = "/approve", consumes = "application/json")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody Map<String, String> body) {
        String kind = body == null ? null : body.get("kind");
        if (!"logo".equals(kind) && !"banner".equals(kind)) {
            return ResponseEntity.badRequest().body(Map.of("error", "kind moet 'logo' of 'banner' zijn"));
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
