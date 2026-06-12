# BACKLOG — Tiny Chicken World

Eén bron van waarheid voor al het openstaande werk, geprioriteerd. Opgeschoond
2026-06-12: alle audits/reviews/concepten staan in `analyse/`, de volledige
[x]-historie van deze backlog in `analyse/BACKLOG-ARCHIEF-2026-06-12.md`.
Afgerond werk wordt hier kort afgevinkt en periodiek naar het archief verplaatst.

Legenda: `[ ]` open · `[~]` gebouwd maar dormant (wacht op asset/validatie) ·
`(user)` = vereist jouw actie · `(data)` = wacht op kijkcijfers · bron-verwijzingen
naar `analyse/...`.

---

## 🔴 P0 — Nu: stack bijwerken, EP3 publiek, eerste echte run

Alles hieronder is de drempel naar "de machine draait en leert".

- [ ] **(user) `build.bat` + volledige stack-herstart.** Alle code t/m 2026-06-12 is ongecompileerd (6+ services geraakt, incl. serie-mythologie, reload-bible, signed tokens, cast-thumbnail, state-machine-test). Daarna: `mvn -pl services/orchestrator test` (nieuwe `PipelineOrchestratorStateMachineTest`) en `docker compose ps` + bridge-script.
- [ ] **(user) OAuth re-consent** (5 min): token in `secrets/yt-creds/` weg → consent-flow opnieuw. Lost twee dingen tegelijk op: auto-captions bij elke upload én **YouTube Analytics 403** (check ook: YouTube Analytics API enabled in GCP + scope `yt-analytics.readonly`). Verifiëren met `POST /api/v1/analytics/poll` → `average_view_percentage` gevuld.
- [ ] **(user) Titel van de live video terugzetten** naar "Pip Found a **Wobbly** Egg!" (eenmalig; de metadata-lock voorkomt herhaling — zie verificatielijst).
- [ ] **(user) Balken-script draaien** (intro/outro zwarte balken; zie `analyse/AUDIT-BOARD-EP3-FINAL-2026-06-11.md`).
- [ ] **(user) EP2 Studio-ronde** (`analyse/AUDIT-EP2-WHOS-IN-THE-PUDDLE-2026-06-10.md`): metadata (#3: #ToddlerLearning, serie-branding, meedoe-regel), end-screen + playlist "Pip's First Times" + weekend-publicatie + pinned comment (#6/#10/#13/#18). In de master verifiëren: intro-snavelsync (#4) en muziektrack-mood (#5).
- [ ] **EP2 re-rolls** (#1/#2, na deploy, ~€4-8): 13 Veo-clips re-rollen (minimaal stille scène ~10 + experimenteer-reeks); stille scène een echt acteer-shot geven ("Pip frozen mid-lean…"). **Combineer met Seedance-A/B** (#14): zelfde still, ander model — de per-scène reroll-knop heeft al een model-dropdown.
- [ ] **(user) PUBLICEREN.** Elke databehoefte (retention-priors, CTR-kalibratie, squint-validatie, arc-weging) start pas met kijkers.
- [ ] **(user) API-keys roteren** (.env bevat live keys, ~1 uur). Zet meteen `REVIEW_TOKEN_SECRET` (nieuw, voor review-confirm-links).
- [ ] **(user) WSL-geheugen bevestigen**: `.wslconfig` met `memory=12-16GB` gezet + `wsl --shutdown`? (De exit-137/OOM bij de transitie-concat kwam hierdoor; code-fallback = chunked concat, zie P3.)
- [x] ~~GCS-bucket + VEO-smoke-test~~ — **was al lang gebeurd** (EP2/EP3 zijn Veo-renders); stale item uit de gearchiveerde draaiboeken, geschrapt 2026-06-12.
- [ ] **(user) `VOICE_MODE=elevenlabs` expliciet in `.env` zetten** — staat nu leeg (werkt vermoedelijk via een host-env-var, maar een verse shell rendert dan stilletjes stemloos; compose-default = silent).

**Verificatielijst eerste run** (code is klaar; de run bewijst het):
- [ ] ⚠ `lastFrame(...)`-SDK-regel in `VertexVeoClient` (methodenaam kan per google-genai-versie verschillen).
- [ ] J/L-cut audiobruggen (drift-veilig gebouwd, nooit op echte render gevalideerd).
- [ ] Assets vuren écht mee: ambient-loops, sting, whoosh, bel, intro/outro.
- [ ] Ondertitel-restyle beoordelen → daarna besluit `burnSubtitles` default aan/uit (soft-captions blijven de upload-route).
- [ ] Metadata-lock houdt stand (geen regen-override na approval — regressie uit `analyse/AUDIT-BOARD-EP3-FINAL`).
- [ ] Loudnorm-keten éénmalig (lossless signaalpad uit de marathonsessie).
- [ ] Pillarbox/blur-fill-check op de master (QUALITY-AUDIT C1).
- [ ] Render-duur vs target (zie ook P2 render-duur-gate).
- [ ] Serie-mythologie zichtbaar in het eerste nieuwe script (tap-tap-tap-hello; gag-dosering max 2).
- [ ] Reload-bible-knop (Brand-pagina) — alle services groen.

## 🟠 P1 — EP4 "Who's in the Egg?" / Duckling (content)

Bron: `analyse/VIRAL-CONCEPT-WHOS-IN-THE-EGG-2026-06-11.md` + `analyse/CONCEPT-EP4-DUCKLINGS-FIRST-DAY-2026-06-11.md`. Duckling-DNA staat al in de bible.

- [ ] **(user) Naam uit de community-poll** in de bible zetten (`characters[duckling].name`).
- [ ] **(user) `VOICE_ID_DUCKLING` kiezen** (ElevenLabs; nu tijdelijk Pip's stem).
- [ ] **Duckling ref-stills genereren + goedkeuren** (`bible/refs/duckling/`, multi-angle zoals Mo).
- [ ] **Job-brief EP4** schrijven (serie "Little Discoveries", mystery-arc, doelwoord "crack", join-in "tok tok").
- [ ] **#16 Song-aflevering** (ep 4-5, Suno-mode) — de open Cocomelon-as.

## 🟠 P1 — Audio- & asset-gaten (code-hooks bestaan, wachten op assets/keys)

- [ ] **(user) Stemmen aan**: `VOICE_MODE=elevenlabs` + key + per-personage voice-IDs (slots klaar in bible). Grootste sprong "diavoorstelling → cartoon".
- [ ] **(user) Muziek**: `bible/music/` uitbreiden naar ~12 tracks (nu 3 → herhaling) óf `SUNO_API_KEY` (~$0,10/track). Ontgrendelt ook: beat-synced cuts, score-volgt-beat, stingers.
- [~] **(user) Foley-clips** in `bible/sfx/foley/` — de mixer is gewired; drop mp3's en hij leeft.
- [~] **(user) Per-character signatuur-SFX** (`bible/sfx/<char>/<emotie>.mp3`).
- [ ] **(user) Muziek-stems** (voor emotie-ducking/score-werk).
- [ ] **(user) Pronunciation dictionary** (ElevenLabs-account): KWAK, tok-tok e.d.
- [~] **(user) FX-assets**: `bible/fx/ambient.mov` (overlay-laag) en `bible/fx/bell.png` (schuddend bel-icoon outro) — beide dormant-tot-asset.

## 🟡 P2 — Kwaliteit (code, geen assets nodig)

Uit `analyse/AUDIT-BOARD-EP3-FINAL`, `analyse/AUDIT-ASSEMBLY-SERVICE`, `analyse/QUALITY-AUDIT-PANEL`, `analyse/PIXAR-QUALITY-AUDIT`:

- [x] **Peak-hold — BLEEK AL GEBOUWD** (marathonsessie; vastgesteld 2026-06-12): climax-beat met `emotion (5/5)` krijgt +2s (board vroeg +1,5s; int-seconden → +2). Zie PipelineOrchestrator script-build-loop. → verifiëren op eerste run.
- [x] **Listening-reactions — BLEEK AL GEBOUWD** (marathonsessie): "The LISTENING character visibly reacts…"-clausule zit in de Veo-staging (PipelineOrchestrator ~regel 2393). → verifiëren op eerste run.
- [x] **ElevenLabs `previous_text`/`next_text` — GEBOUWD (2026-06-12).** Bestond half (client-overload + binnen-scène `neighborText`); nu compleet: `ProsodyContext` (zelfde-speaker-selectie, venster = eigen + aangrenzende scène, cap 300 tekens, 13 unit-tests), flag `ELEVENLABS_CONTEXT_ENABLED` (default aan; uit = body byte-identiek).
- [x] **Render-duur-gate — GEBOUWD (2026-06-12).** In `recordDurationMetrics`: master vs target+intro/outro-marge (>10% → warn + qcInsights "duration-gate" + `withinTarget` in metricsJson) en een gewogen Polish-check "Master duration within 10% of target" in QualityScorer. Zacht (human gate blijft backstop).
- [x] **Climax-camera-presets + auto-establishing — GEBOUWD (2026-06-12).** cameraBible: climax nu 85mm push-in/ondiepe DoF, hook expliciet extreme close-up (beleid-conform). Eerste setup-scène ná de hook krijgt deterministisch "wide establishing shot of {location}" (Veo-framing én image `cameraFraming`). Kanttekening: single-scene-reroll van precies die scène mist de clausule (image-kant dekt het).
- [x] **Stilte-muziekdip — GEBOUWD (2026-06-12).** `musicDipWindow` (stille scène geprefereerd, anders climax-start, cap 6s) → trapezium-envelope op de muziektak (`app.assembly.climax-dip-db`, default -12dB, 0.8s fades). Cosmetisch-veilig: envelope-fout → flat retry, nooit een kapotte render. Open blijft: **stingers** op gasp/reveal (na muziek-assets).
- [ ] **Thumbnail-constraints** aanscherpen (board; max 3 woorden staat al — rest verifiëren).
- [ ] **Eval-harnas voor prompts** — vaste test-briefs + objectieve scoring zodat promptwijzigingen meetbaar worden. *(gepland in sessie 2026-06-12)*
- [ ] **Keyword/SEO**: YouTube-autocomplete als gratis bron → `MetadataGenerator` weegt termen mee in titel/tags. *(gepland in sessie 2026-06-12)*
- [ ] **Pixar Stories** (specs in `analyse/PIXAR-QUALITY-AUDIT.md`): G multi-angle CharacterModel activeren (betaalde generatie), C volledige shot/reverse-dialoogdekking (kost clips).
- [x] **Pixar Story B: Episode-ConsistencyState — GEBOUWD (2026-06-12, batch 5).** De aflevering wordt z'n eigen canon: na de QC-pass kiest de orchestrator per personage de beste goedgekeurde still (minste occlusie → hero-fase → laagste seq), persisteert die als `episode_anchors` (`V27`, jsonb) en stuurt ze bij élk re-roll-pad (QC-reroll, Auto-Fix, ✎ Edit, end-stills) als benoemde extra referenties mee naar Gemini ("the SAME individual character earlier in this exact episode — match identity exactly, light for THIS scene"). Ref-budget gecapt op 9 (episode-anchor verdringt een extra bible-angle, nooit het hero-anchor); prop-anchors her-attachen bij re-rolls; generieke EPISODE CANON-clausule + ConsistencyChecker blijven het vangnet voor props. Fail-safe: geen anchors = exact oud gedrag. 2 pure unit-tests op de selectie-heuristiek. Logica woont in PropAnchorService (static/puur — constructor van PipelineOrchestrator ongewijzigd, test veilig). ~~E2 ScorePlan~~ → **GEBOUWD (2026-06-12)**: fase-gestuurde volume-boog (`app.assembly.score-plan`, default hook:0/setup:-2/dev:-1.5/climax:+1.5/res:-1/closer:-3) als basis-laag onder de bestaande swell+dip (gestapelde volume-filters, flat-retry-vangnet dekt alles; leeg = exact oud gedrag).
- [ ] **Seedance reference-to-video v2** — ná de A/B uit P0; tot 9 refs rechtstreeks de videogen in (grootste resterende consistentiesprong).
- [x] **Auto-Fix uitbreiden naar alle QA-assen — GEBOUWD (2026-06-12).** Per as onder `app.autofix.axis-threshold` (default 70): Thumbnail → 1× regen + squint-preselect (alleen op de afsluitende pass, anders dubbel); Characters/Animation → reroll-selectie verbreed met zwakste hero-scènes (binnen bestaande caps, respecteert scene-locks); Sound → gericht advies via qcInsights (assets = user-domein); Story → bewust alleen insight (herschrijf = re-render, buiten caps). Caps/gates ongewijzigd; constructor onaangetast (test veilig). NB: het `qaAxisFix`-veld uit de oude backlog bleek niet te bestaan — hefbomen hangen aan de echte QA-Board-json.
- [x] **Per-locatie/tijd-van-dag effectkeuze — GEWIRED (2026-06-12).** Resolver in SceneClipBuilder: `bible/fx/weather/{weather}` > `fx/time/{timeOfDay}` > `fx/location/{locationId}` > globale `fx/ambient` (alles dormant-tot-asset, .mov/.webm, 60s-TTL-cache, README in `bible/fx/`). SceneInput kreeg locationId/timeOfDay/weather (orchestrator stuurde ze al mee). Effect↔geluid: locatie-koppeling werkt vanzelf via gedeeld locationId (AmbientMixer zit in voice-service!); **open restje**: weer-ambient voorrang geven op het locatie-bed (voice-service; "de regen die je ziet kan nog als tuin klinken"). Sfeer-overlays (godrays/bokeh) op golden-hour = assets droppen in dezelfde structuur.
- [~] **Silhouet-redesign (board #25, "season-2 design pass") — VOORBEREID + UITROLBAAR VIA UI (2026-06-12).** DNA aangescherpt in de bible: Pip = CIRKEL, Mo = AAMBEELD (breed onderlijf, rechte schouders; "rounder" overal vervangen, stale wimpers uit de description), Bo = VERTICALE STREEP (gestrekt, tuft als spits); scaleAnchors: "similar SIZES, distinct SHAPES". Nieuw op de Cast-pagina: **🎨 Genereer nieuwe referentie** → 3 AI-kandidaten puur uit de nieuwe DNA-tekst (geen oude ref als anchor!), klik = goedkeuren → vervangt `refs/{id}.png` (backup .bak), leegt automatisch de stale multi-angle-map én serie-anchors, en hot-reloadt de bible. **Resterende user-flow (na rebuild): per personage genereren → kiezen → testrender → evt. banner/logo later bijwerken.** Kosten ~€0,15-0,30 per personage per batch.
- (data) **Loops 1/3/4**: retention-priors, CTR-kalibratie, squint-validatie — na publicatie.
- (data) **Story-arc-weging** (`randomStoryArc()` → performance-bias) + **season-2 silhouet-pass**.

## 🟡 P2 — Frontend (uit `analyse/FRONTEND-GAP-REVIEW.md` + `analyse/FRONTEND-REVIEW.md`)

Panelen (backend bestaat, UI ontbreekt):
- [x] **Distribution panel — GEBOUWD (2026-06-12)** op de job-pagina: statuschips YT/FB, push-knoppen (TikTok/IG/FB) op de bestaande proxy-endpoints, community-postideeën + end-screen-recept. Eerlijke "?"-chips voor TikTok/IG (backend persisteert die IDs niet → klein open item hieronder).
- [x] **Cost panel — GEBOUWD (2026-06-12)**: nieuwe read-only `GET /api/v1/videos/{id}/cost` (CostController: estimator-breakdown + werkelijke Veo-kosten uit metricsJson) + 💶-kaart met cap-balk en eerlijke lege staat.
- [x] **Performance-hint zichtbaar — GEBOUWD (2026-06-12)** op het New-job-formulier (uit `GET /api/v1/analytics` veld `hint`).
- [x] **Localization panel — GEBOUWD (2026-06-12)**: per taal status/links/fouten + localize-knop op de bestaande endpoints.
- [x] **TikTok/Instagram-push-status persisteren — GEBOUWD (2026-06-12)**: `V23` + `tiktokPublishId`/`instagramMediaId` op VideoJob, gepersisteerd door de DistributionController-proxy, echte chips in de UI. NB: Instagram-permalink bestaat niet in de push-respons (zou een extra Graph-lookup vergen) — chip linkt bewust nergens heen.
- [x] **Metadata regenerate/edit — GEBOUWD (2026-06-12)**: gevalideerde `POST /api/v1/videos/{id}/metadata` (limieten + status-guard: niet na upload) + `POST .../metadata/regenerate` (MetadataGenerator + MetadataPolicy) + ✎/🔄 in de job-UI met 🔒 bij bevroren jobs. Open restje: de legacy ongeguarde `PATCH /metadata` bestaat nog (pipeline-intern gebruikt) — t.z.t. dichtzetten.
- [x] **Job search + filters + bulk — GEBOUWD (2026-06-12)**: zoekveld, multi-status-pills, formaat-filter (JobSummary kreeg `format`), checkbox-selectie + bulk retry/approve via bestaande POSTs, filterstand in localStorage.

Refactor-schuld:
- [ ] `baseCss()` → `static/assets/css/`; inline scripts → `api.js`; gerichte DOM-updates i.p.v. `location.reload()`; client-side escaping niet-optioneel (XSS); `DashboardController` opsplitsen; `components.css`; theme-bootstrap → `theme.js`; hash-router.

## 🔵 P3 — Architectuur & schaal

- [ ] **SceneDto** — typed scène-record i.p.v. `Map<String,Object>`; hoogste structurele ROI. *(gepland in sessie 2026-06-12)*
- [ ] **@Version op VideoJob** — pas ná een groene `PipelineOrchestratorStateMachineTest`-run (die test is er nu; build eerst).
- [ ] **PipelineOrchestrator verder splitsen** (stap 1, VeoPromptCompiler, is klaar; volgende plakken: state machine / stages / QC).
- [x] **Chunked transitie-concat — AF (2026-06-12).** Bestond deels (proactief, hardcoded 6); nu een volledige ladder: >chunk-size → direct CHUNKED (proactieve OOM-guard, geen gedoemde monolithische poging) → bij falen BARE; ≤chunk-size → volle graph → chunked → bare. `CONCAT_CHUNK_SIZE` (default 8). Chunk-naden hergebruiken de fase-xfades; globale cut-tijdlijn identiek aan de monolithische graph.
- [x] **Poort-mapping beperkt — GEDAAN (2026-06-12).** Alleen 8080 (orchestrator) + 8089 (OAuth-callback, geverifieerd) + 5433 (postgres, host-tooling) nog op de host; 8081-8087/8000 → `expose` (intern). Debug-override: `docker-compose.dev-ports.yml`. Scripts bijgewerkt/gedocumenteerd (run-eval.py, agent-bridge.ps1-hint); MailHog bleek al verwijderd (stale README gefixt). Let op: `tools/make-intro.sh`/`make-outro.sh` hebben de override of `IMG_URL`/`VID_URL` nodig.
- [x] **assembly_scenes → JSONB — GEBOUWD (2026-06-12)**: `V24` (defensief DO-blok tegen corrupte oude rijen + USING-cast) + `@JdbcTypeCode(SqlTypes.JSON)` op het String-veld (zelfde bewezen patroon als script-service). 
- [x] **Asynchrone videogen — GEBOUWD (2026-06-12)**: videogen `POST /clips/generate-async` + `GET /clips/jobs/{id}` (in-memory store, 2-thread pool, 2u expiry) en orchestrator `generateAsync` (poll 10s/timeout 20min, drop-in zelfde JsonNode-shape) op alle 4 pipeline-call-sites. Open restje: Intro/OutroRebuildService staan nog op het synchrone pad (1 regel elk).
- [ ] **virtual threads**; **OpenPDF 1.3.43 → 2.x**; **ffmpeg-integratietests**.
- [ ] **GPU-encode (NVENC)** voor rendersnelheid (analyse/AUDIT-ASSEMBLY #8).
- [ ] **Veo-kostenmetering** tegen GCP-billing + metrics.
- [ ] **Embedding-gebaseerde drift-detectie** (naast vision-QC).
- [ ] **Event-driven queue tussen stages** — bij volumeproductie.
- [x] **Per-serie asset-caching (serie-anchors) — GEBOUWD (2026-06-12, slot).** Story B over afleveringen heen: bij een geslaagde upload promoveert de orchestrator de episode-anchors naar `bible/refs/series/{seriesId}/{charId}.png` (nieuwste goedgekeurde aflevering wint, README met lifecycle erbij); de volgende aflevering seedt z'n éérste generatie-batch daarmee (`source:"series"`-anchors + SERIES CANON-clausule in Gemini). Prioriteit: eigen episode-canon > serie-canon > bible-refs (overgang automatisch zodra de eigen canon post-QC gekozen is). `SERIES_ANCHORS_ENABLED` (default aan), path-traversal-safe ids, pure unit-tests. Personage zonder vorige aflevering (duckling) = stil overslaan; bewuste look-wijziging = serie-map legen.
- [ ] **Re-encode-consolidatie / lossless intermediates** — geparkeerd: winst is rendertijd, niet beeld (CRF-16-tussenstappen dempen al); pas bij schaal.
- [ ] **2.5D parallax**; **echte match-cuts/eyeline-cuts** (pose-detectie); **beat-synced cuts** (na muziek) — world-class, later.
- [ ] **VEO breder + lip-sync** — het enige fundamentele studio-plafond; duur, vereist eerst de audiolaag.
- [ ] **#20 Lokalisatie** — parkeren tot 10 afleveringen (metadata-localisatie is al gebouwd).

---

## Vandaag afgerond (2026-06-12 — verplaatst naar archief bij volgende opruimronde)

- [x] Banner-canon gecheckt: groene sjaal = Bo (canon), geen fout.
- [x] Serie-mythologie: `seriesMythology`-blok (tap-tap-tap-hello + running gags) end-to-end in script-prompt.
- [x] Slimmere auto-Short: sliding-window energie i.p.v. luidste sample.
- [x] Orchestrator state-machine-integratietest (8 scenario's, pure Mockito) — ontgrendelt @Version.
- [x] Reload-bible: `POST /api/v1/bible/reload` op 5 services + orchestrator-fan-out + auto na Cast-edit + Brand-knop. Herstart-caveat vervalt.
- [x] Cast-thumbnail breder: `castPresent`-ground-truth (≥35% van de scènes) → groeps-thumbnail ook zonder namen in de titel; rendert alléén aanwezige castleden.
- [x] GET-mutaties verwijderd (UI was al POST) + `ReviewTokenService`/confirm-flow voor toekomstige mails.
- [x] Intro/outro-preview: bleek al gebouwd (stale backlog-item).
- [x] MD-opruiming: 23 documenten → `analyse/`, backlog-historie → `analyse/BACKLOG-ARCHIEF-2026-06-12.md`, deze geprioriteerde backlog.
- [x] Keyword/SEO: `KeywordSuggester` (YouTube-autocomplete, gratis, best-effort, kindveilige blocklist, `SEO_KEYWORDS_ENABLED`) → weegt mee in `MetadataGenerator`-prompt (titel/tags). + `KeywordSuggesterTest`. Run-time check: is het suggest-endpoint bereikbaar vanuit Docker (log: "YouTube autocomplete: N keyword(s)").
- [x] Eval-harnas: 8 bevroren briefs + golden/mutant-scripts; `PromptEvalHarness` pint 21 prompt-ankers (incl. SERIES MYTHOLOGY) + tokenbudget; `ScriptEvalHarness` pint StructureValidator/PacingValidator/ComedyValidator-gedrag; rapport naar `target/eval-report.md`; draait mee in `mvn test`. Afspraak: elke promptwijziging = harnas draaien + rapport diffen. Bevinding genoteerd: StructureValidator checkt fase-vólgorde niet (alleen closer-laatst) → klein open item.
- [x] Prompt-bugfix (gevonden door het harnas): `SYSTEM_BASE`/`emotionalCurve` gaan niet door `formatted()` → model zag letterlijk "15%%"/"20%%"; nu "%".
- [x] SceneDto: typed scène-klasse (alle bekende keys + extras-round-trip voor onbekende keys, NON_NULL = remove-semantiek) door héél PipelineOrchestrator; HTTP-payloads en persisted JSON byte-compatibel; client-signatures en QC/QA-componenten onaangetast; state-machine-test ongewijzigd compatibel.

**Avondbatch (2026-06-12, tweede ronde — ook ongecompileerd tot build.bat):**
- [x] `StructureValidator` fase-volgorde-check ("Phase order violated: …") + 4 tests + climax-vooraan-mutant in het eval-harnas.
- [x] ElevenLabs prosodie-context, render-duur-gate, climax-camera-presets + auto-establishing, stilte-muziekdip (zie P2).
- [x] Chunked-concat-ladder met proactieve OOM-guard + poort-mapping beperkt (zie P3).
- [x] Frontend: distribution-/cost-/performance-hint-/localization-panelen (zie P2 Frontend), incl. nieuwe `CostController`.
- [x] Auto-Fix over alle QA-assen (zie P2).

**Nachtbatch (2026-06-12, derde ronde — ook ongecompileerd tot build.bat):**
- [x] Dashboard compleet: TikTok/IG-status persistent + metadata-edit/regenerate + zoeken/filters/bulk (zie P2 Frontend).
- [x] Effect-overlay-resolver per weer/tijd/locatie + `bible/fx/README.md` (zie P2).
- [x] ScorePlan E2: fase-gestuurde muziekboog (zie P2 Pixar Stories).
- [x] assembly_scenes → JSONB (`V24`) + asynchrone videogen met poll (zie P3).
- Nieuwe restjes: ~~weer-ambient-voorrang~~, ~~Intro/Outro-rebuild → async-pad~~, ~~legacy `PATCH /metadata` dichtzetten~~, ~~Instagram-permalink-lookup~~ → **alle vier GEBOUWD (batch 4)**, plus frontend-refactor-schuld (XSS-fix golden-page, theme.js sync-in-head, reload→DOM-updates; DashboardController bleek al gepensioneerd) en @Version op VideoJob (`V26` + retryOnConflict-helper op alle korte save-paden + 3 unit-tests) + virtual threads (alle services behalve upload-service: synchronized OAuth-flow pint carriers — gedocumenteerd) + OpenPDF → 2.0.3 (com.lowagie-compatibel; fallback 1.4.2 als 2.0.3 niet bestaat in Maven Central).

**Live-incident gefixt (2026-06-12, eerste run):**
- [x] **Assembly-timeout**: render >20 min (chunked passes + loudnorm + Short) → Netty responseTimeout (20 min, gedeelde builder) kapte de verbinding vóór de bedoelde 45-min call-timeout; job FAILED terwijl ffmpeg doorliep. Tijdelijke fix (50-min connector) is **vervangen door de structurele**: zie batch 5.

**Batch 5 (2026-06-12, volle kracht — ongecompileerd tot volgende build):**
- [x] **Async assemble**: `POST /assemble-async` + `GET /assemble/jobs/{id}` (1-thread store, assembly = bewust één render tegelijk) + orchestrator `assembleAsync` (poll 10s, timeout 60 min, `ASSEMBLY_POLL_*`); 50-min-pleister verwijderd; state-machine-test mee-hernoemd. Zelfde herstart-beperking als videogen (poll-state lokaal, gedocumenteerd).
- [x] **Zelflerend (Stories H/I-plumbing)**: `PhaseRetention` (per-fase retentie-aggregatie over `retentionScenesJson`, ≥3 video's-drempel, 2 pure tests) → voedt de bestaande performance-hint ("viewers drop most during X"); `SeriesStats` + `GET /api/v1/series/{id}/stats`; `GET /api/v1/analytics` kreeg `retentionByPhase`. Geen migratie nodig (query-laag). Bleek al te bestaan: RetentionMapper, PerformanceLoop ÍS de arc-selector (epsilon-greedy), SeriesContinuity = narratief geheugen. Data stroomt zodra OAuth + publicatie er zijn.
- [x] **ffmpeg-integratietests** (assembly, `@EnabledIf` ffmpeg+ffprobe op PATH, fixtures via lavfi): concat-duur/streams, chunked==monolithisch (±0.5s, incl. proactieve-guard-bewijs via passdir), eerlijk kapotte-input-gedrag (ffprobe faalt luid — ladder redt graphs, geen inputs), scorePlan+dip-mix + flat-retry, SceneClipBuilder duur-stretch/cap. Vereist libx264.
- [x] **Pixar Story B** (zie hierboven).
- [x] Episode-anchors-bevinding: image-service had al een QC-blinde disk-scan-ConsistencyState; request-canon wint nu, disk-scan blijft voor de eerste batch.

**Publicatie-verfijningen (2026-06-12, avond — op gebruikerswensen):**
- [x] **Auto-playlist per serie**: `POST /api/v1/distribute/playlist` (lookup op titel → create public → idempotente add, max 2 pagina's dup-check) + orchestrator koppelt na elke geslaagde upload o.b.v. `seriesId` (best-effort, Shorts uitgesloten, serietitel uit bible `series[].name`). UI toont "📃 In playlist".
- [x] **Video-taal**: `snippet.defaultLanguage` + `defaultAudioLanguage` op elke upload (`YOUTUBE_DEFAULT_LANGUAGE`, default `en`) — juiste zoekindex, auto-vertaalde metadata, caption-labels.
- [x] **Outro-fade verlengd** op kijkersfeedback: 1.7 → 2.6s, DUR 8.5 → 9.0 (CTA-leestijd blijft ±3s). Bible `brand.intro/outro.durationSeconds` gecorrigeerd (3/3 was stale → 6/9; voedt de duur-gate-marge).
- [x] **Intro-redesign** op kijkersfeedback: de letter-voor-letter "TINY CHICKEN WORLD"-tekst + het gouden ei + de letter-dings zijn eruit; in plaats daarvan vliegt het **logo** (zelfde de-haloed `bible/logo.png` als de outro) met een ease-out-swoop linksboven in beeld (240px, x=56/y=48 — spiegelt het outro-logo, branding bookend't de video) + één sparkle bij de landing. Stemmen/slots/dissolve-marge ongewijzigd. **Actie: intro én outro éénmalig ♻ Re-composite (gratis, geen Veo) na de rebuild.**
- [ ] **(user) Kanaal telefonisch verifiëren** (youtube.com/verify als Tiny Chicken World) — vereist voor custom thumbnails; daarna thumbnail van qhw-y3paiQM handmatig zetten in Studio.
- [x] **End-screen-outro-template — GEBOUWD (2026-06-12, op gebruikersontwerp).** Vaste 12s-outro rond YouTube's end-screen-band: middenband (~y 280-800) hard vrijgehouden (links=subscribe, midden=playlist, rechts=video; ASCII-schema in de javadoc), eigen rode SUBSCRIBE-box + bel **verwijderd** (YouTube's element vervangt ze), logo klein top-left, één dunne tekstregel onderin ("What adventures will we discover tomorrow?"), Veo-prompt herschreven: drie kuikens lachend in het onderste derde, bovenste twee derde leeg, één farewell-regel (Pip, `OUTRO_LINE` configureerbaar, default "See you in the next adventure!"), rustig muziekbed dormant-tot-asset (`bible/sfx/outro/calm.mp3`, -16dB), tpad-hold + 2.6s fade. **Acties: (1) outro éénmalig VOLLEDIG rebuilden (🎬, Veo-kosten — re-composite hergebruikt de oude framing!), (2) optioneel calm.mp3 droppen, (3) in Studio de end-screen-elementen links/midden/rechts plaatsen met duur ~12s.**

> Alles van vandaag is **ongecompileerd** tot de eerstvolgende `build.bat`.
