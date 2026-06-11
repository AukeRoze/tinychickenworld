# Backlog — Tiny Chicken World pipeline

Status van vervolgpunten. `[x]` = gedaan, `[~]` = ingebouwd maar dormant/flag-gated (wacht op asset of validatie), `[ ]` = open.

## ⭐ EP2-audit (73/100, "Who's in the Puddle?") → werkitems

**Vóór de video publiek gaat (handwerk, ~1 avond):**
- [ ] **#1 CRITICAL — 13 Veo-clips re-rollen** (na rates-deploy!): minimaal de stille scène (~10) en de experimenteer-reeks; "🎬 Maak clip"-knop per scène, hermonteert vanzelf. ~€4-8.
- [ ] **#2 CRITICAL — stille scène een echt acteer-shot geven**: ✎ Edit met "Pip frozen mid-lean over the puddle, beak inches from the water, eyes huge, one wing half-raised, breath held" → regen still → Maak clip.
- [ ] **#3 — metadata in YouTube Studio**: #BedtimeStories → #ToddlerLearning; beschrijving + serie-branding ("Episode 2 of Pip's First Times") + meedoe-regel ("Stamp along — splish-splash!").
- [ ] **#4 — intro-snavelsync verifiëren** in deze master (eerste 6s); zo niet: intro-rebuild + reassemble.
- [ ] **#5 — muziektrack checken** (thoughtful/calm?) — zo nodig wisselen via de 🎵-kiezer + reassemble.
- [ ] **#6/#10/#13/#18 — Studio-ronde**: end-screen (abonneer + ep-1-slot), playlist "Pip's First Times", publicatie weekend-ochtend plannen via 📅-gate, pinned comment ("Did YOU stamp along?").

**In software opgepakt (2026-06-10, scoort door in álle volgende afleveringen):**
- [x] **Cost-cap → Lite-downshift** i.p.v. Ken Burns-fallback: bij budgetdruk eerst Veo Lite (€0,30/scène, beweegt) proberen; alleen als zelfs dát niet past → still. De stilstaand/bewegend-wissel (grootste AI-tell uit de audit) kan zo vrijwel niet meer ontstaan. `ModelRouter.cheapest()` + downshift-branch in `ClipGenerationService`.
- [x] **#7 Uitadem-beat** — POLISH RULES in de schrijfprompt: één rustige 3-4s-scène ná de climax, nooit eindigen op een piek.
- [x] **#9 Brugterm** — volwassen leswoord (mirror/reflection/...) 2-3× naast het doelwoord.
- [x] **#11 Tic-dosering** — signatuur-tics max 1× per aflevering ("Hmm." 2× was er net één te veel).
- [x] **#17 Ouder-knipoog** — exact één regel per aflevering die over kinderhoofden heen landt.
- [x] **#15 Licht-variatie** — lichtboog per verhaal (grijsblauw mysterie → warm bij de oplossing) i.p.v. eeuwige golden hour.

**Open (gepland, niet blokkerend):**
- [x] **#8 Thumbnail-contrast — GEBOUWD** (2026-06-10). `ThumbnailGenerator`: `punchify()` nu op ÉLKE base (anchor-/AI-gegenereerde bases sloegen 'm over; alleen het loadProvidedBase-pad had 'm). Vergt rebuild thumbnail-service.
- [x] **#12 Duurdiscipline — GEBOUWD** (2026-06-10). `StructureValidator.TOTAL_DRIFT_MAX` 0.20 → 0.10: voice-stretching in assembly legt nog ~10% bovenop de script-duren, dus +20% script werd ~+30% master (EP2: 90s → 124s). Vergt rebuild script-service.
- [x] **Bonus: weer-continuïteit in vision-QC — GEBOUWD** (2026-06-10). EP2 had zonneschijn in een regen-beat. `PipelineOrchestrator.autoQcImages` geeft nu `weather`/`timeOfDay` van de scène mee aan `SceneImageQc`; harde tegenspraak (zon+droog bij regen, daglicht bij nacht) = fail → auto-reroll. Vergt rebuild orchestrator.
- [ ] **#14 Seedance-A/B** combineren met de re-rolls van #1 (zelfde still, ander model — gratis vergelijk). **Klaar voor gebruik**: de per-scène reroll-knop heeft al een model-dropdown.
- [ ] **#16 Song-aflevering** (ep 4-5, Suno-mode) — de open Cocomelon-as.
- [ ] **#20 Lokalisatie** — parkeren tot 10 afleveringen.

## ⭐ Code-review 2026-06-10 (Principal-audit, zie CODE-REVIEW-PRINCIPAL-2026-06-10.md) → werkitems

**In de nacht van 10→11 juni zelfstandig gebouwd (details: NACHTRAPPORT-2026-06-11.md):**
- [x] Foutzichtbaarheid: `GlobalExceptionHandler` (ProblemDetail) + api.js toont de échte serverfout — lost de kale "500" bij Maak clip op.
- [x] Path-traversal dicht: `character()`, `sceneClip()`, Audit `videoPath`, Intro/Outro `clipPath`/`voiceLines` (SafePaths), MultiPlatform-paden → alles gelockt op /workdir + /bible.
- [x] SSRF dicht: Instagram `publicVideoUrl` → alleen publieke https-URL's.
- [x] Upload-hardening: PNG magic-bytes + 10MB-cap + id-regex op cast-image-upload.
- [x] Analytics-bug: `findLatestPerVideo` op `MAX(fetchedAt)` i.p.v. `MAX(id)` (random UUID's zijn niet monotoon — gaf willekeurige snapshots).
- [x] Compose: `restart: unless-stopped` + healthchecks (bash /dev/tcp) op alle services.
- [x] Dockerfiles: `-Xmx` heap-cap op alle 8 JVM's.
- [x] jacoco-coverage in de aggregator-pom + GitHub Actions CI (`.github/workflows/ci.yml`, `mvn -B verify`).
- [x] Tests: `StructureValidatorTest` (±10%-duurgate!) + `PacingValidatorTest` (6 cases) — script-service 1→3 testklassen.
- [x] `ApiKeyFilter` (default UIT; zet `APP_API_KEY` zodra de poort ooit naar buiten gaat).
- [x] Bridge voor autonome aansturing: `infra/bridge/agent-bridge.ps1` (Claude ↔ localhost:8080 via bestandscommando's).
- [x] Cleanup: ongebruikte `variant`-param uit `punchify()`.

**Open (gerangschikt; uit het reviewrapport §13):**
- [ ] **API-keys roteren** (.env bevat live keys — gebruikersactie, 1 uur).
- [ ] **SceneDto** — typed scène-record i.p.v. `Map<String,Object>` overal; hoogste structurele ROI.
- [ ] **Orchestrator-state-machine-integratietest** (gemockte service-clients, job door alle gates).
- [ ] **PipelineOrchestrator → stage-services** splitsen (incrementeel; 2.264 regels nu).
- [ ] **GET-mutaties vervangen** (approve/retry/reassemble via GET = link-preview-risico) → signed tokens in reviewmails.
- [ ] **@Version op VideoJob** — bewust niet 's nachts gedaan: optimistic-locking-conflicten zonder integratietest = risico op pipeline-breuk; eerst de state-machine-test.
- [ ] **Poort-mapping beperken** tot 8080 (+8089 OAuth, 8025 mail) — bewust niet 's nachts: infra-scripts praten nog rechtstreeks met 8081 e.a.
- [ ] **assembly_scenes → JSONB**; virtual threads aanzetten; OpenPDF 1.3.43 → 2.x; ffmpeg-integratietests; asynchrone videogen (job-id + poll).

## ⭐ Bovenaan — nu actief

- [ ] **Seedance reference-to-video (v2 van de fal-integratie).** v1 (gebouwd, 2026-06-10) gebruikt `bytedance/seedance-2.0/image-to-video` — zelfde contract als Veo (start+eindframe). Het `reference-to-video`-endpoint accepteert daarnaast tot 9 referentie-afbeeldingen: de multi-angle character-refs (`bible/refs/{id}/`) zouden dan rechtstreeks de videogeneratie in gaan — potentieel de grootste consistentiesprong die er nog is. Vergt: apart endpoint + eigen prompt-dialect (@-tagging van refs, zie magichour-gids), `characters` per scène doorgeven (zit al in assembly-scenes), refs uploaden naar fal storage (cachen per run). Eerst v1 A/B'en tegen Veo via de golden set; als Seedance wint → dit bouwen.

- [ ] **YouTube Analytics API geeft 403 Forbidden** (`GET youtubeanalytics.googleapis.com/v2/reports`). De AnalyticsPoller degradeert netjes (views-gebaseerde weging blijft werken), maar retention-per-scène, kijkpercentage-weging en de retention-kaarten op de jobpagina blijven leeg tot dit is opgelost. Twee waarschijnlijke oorzaken, in volgorde van checken:
  1. **YouTube Analytics API niet enabled** in het GCP-project waar de OAuth-client bij hoort → Google Cloud Console → APIs & Services → Library → "YouTube Analytics API" → Enable.
  2. **OAuth-token mist de scope** `yt-analytics.readonly` (token dateert van vóór die scope) → re-OAuth: token-bestand in `secrets/yt-creds/` verwijderen en de upload-service de consent-flow opnieuw laten doorlopen.
  Daarna verifiëren: `POST /api/v1/analytics/poll` (orchestrator) en checken of `average_view_percentage` in `video_analytics` gevuld raakt.

- [x] **Per-locatie ambient-geluid — GEDAAN (via ElevenLabs SFX, niet Suno).** Alle 9 loops staan in `bible/sfx/ambient/` (generate-ambient.py, 2026-06-10) en zijn afspeelbaar op de Brand-pagina. ~~Per-locatie ambient-geluid via Suno.~~ Enige asset-gat uit de render-audit: de `ambientByLocation`-mapping in de bible is compleet en de `AmbientMixer` is gewired, maar de map `bible/sfx/ambient/` ontbreekt — dus de geluidsbedjes draaien nog niet mee (AmbientMixer slaat per scène stil over; Polish-QA scoort dit laag). **TODO (later, met Suno):** 9 zachte sfeer-loops maken en neerzetten in `bible\sfx\ambient\`: `coop.mp3, kitchen.mp3, porch.mp3, garden.mp3, pond.mp3, willowGrove.mp3, oak.mp3, hills.mp3, night.mp3` (coop = houtgekraak/bries, pond = water/kikkers, garden = bijen/vogels, night = krekels/uil…). Géén rebuild nodig — zodra de bestanden er staan, draaien ze automatisch mee. Mengniveau staat al op `ambientMixDb: -16`.

## Dashboard / UI

- [x] **Cast bewerkbaar vanuit de UI (v1) — GEBOUWD.** Cast-pagina heeft per character **✎ Bewerken**: naam/rol/`dna.accessory`/`dna.tic` aanpassen + een **referentieplaatje uploaden** (`bible/refs/{id}.png`). Schrijft via nieuwe `BibleEditor` (comment-behoudende in-place YAML-edit, maakt `channel.yml.bak` + valideert en revert bij ongeldige uitkomst) en `BrandController` (`POST /api/v1/brand/cast/{id}`, `POST /cast/{id}/image`). Orchestrator-bible-mount nu read-write; multipart-limiet op 15MB. **Geen training nodig** want `IMAGE_PROVIDER=gemini` (reference-conditioned). Caveat: image-/script-/voice-service cachen de bible bij opstart → na een edit **orchestrator + image-service herstarten** voor nieuwe renders (Cast-pagina zelf toont direct). **Uitgebreid:** ook `description`, `personality` (gevouwen blokken) en `catchphrases` (opener/closer-lijsten) zijn nu bewerkbaar (comment-behoudende blok/lijst-editing in `BibleEditor`, zelfde backup+validate+revert-net). De Cast-pagina toont nu de eigenaardigheden: accessoire, tic, signatuurgeluid, **persoonlijkheid**, catchphrases en uiterlijk. `personality`+`catchphrases` voeden de script-prompt (PromptBuilder), `tic` de Veo-prompt — dus edits sturen echt de output. **Open vervolg:** een "reload bible"-endpoint i.p.v. restart; optioneel een referentieplaatje AI-genereren i.p.v. uploaden. Training-vanuit-UI (Replicate LoRA) alleen nodig als je naar `replicate` switcht — groot apart traject, niet aanbevolen.

- [ ] **Intro/outro preview in het dashboard.** Er is nu geen manier om `bible/intro.mp4` / `bible/outro.mp4` in de app te bekijken — alleen de statusregel na een rebuild. Toevoegen: een orchestrator-endpoint dat beide bestanden serveert (mirror van de master-player `/dashboard/{id}/master.mp4`) + een `<video>`-spelertje naast de "🎬 Rebuild intro" / "👋 Rebuild outro"-knoppen, zodat je ze meteen na een rebuild kunt afspelen. *Op verzoek geparkeerd: eerst VEO werkend krijgen.*
- [ ] **Auto-Fix uitbreiden naar alle QA-assen (niet alleen script).** De Auto-Fix→90-loop tilt nu alleen de AI-Critic/script-kant op (Story/Humor/Emotion/Retention). Uitbreiden zodat 'ie ook de niet-script-assen automatisch aanpakt: **Sound** (echte muziek/ambient/sting plaatsen of waarschuwen), **Thumbnail** (beste variant kiezen / regenereren), **Characters/Animation** (zwakke scène-beelden re-rollen). De QA Board toont sinds deze sessie al per zakkende as de juiste hefboom (`qaAxisFix`) — dit zou die hefbomen automatiseren i.p.v. handmatig. *Op verzoek geparkeerd.*

## Frame-feedback ronde 2 (deze sessie)

- [x] **Logo de-halo** — crème halo (partial-alpha randpixels ≈233,222,205) rond de kippen weggewerkt (alpha-erosie + randkleur-decontaminatie). Nieuwe `bible/logo.png` (origineel geback-upt).
- [x] **A2 — verboden-accessoire-locks.** Bible `dna.antiAccessory` per personage (Pip≠bril/sjaal, Mo≠bandana/bril/hoed, Bo-bril=dun rond, nooit hoed/rode sjaal). Geïnjecteerd in `PromptComposer.dnaLine`, Replicate-negatives, Veo-`characterDnaClauses` en QC-expected. Lost Pip-met-bril, Mo-bandana, Bo-bril-stijl op.
- [x] **A3 — vision-QC cross-accessoire + ontbrekend personage.** `SceneImageQc` faalt nu ook op een accessoire-swap (personage draagt andermans accessoire) én op een volledig ontbrekend gebrieft personage → auto-reroll. `dnaAccessoryLines` draagt de "must NOT wear"-info aan.
- [x] **B — stijl-detail omlaag.** `visualStyle` aangescherpt naar zacht low-detail storybook + harde anti-fotoreal ("NEVER photorealistic/hyper-detailed", geen kasseien/plassen/tractor-realisme). Replicate had de anti-fotoreal-negatives al.
- [x] **C — paaseieren niet meer always-on.** Eieren uit `visualStyle` én de barnyard-locatie gehaald; nieuw per-video `recurringMotif`-veld (`CreateVideoRequest`→`VideoJob`/`V14`→image-`visualDesc` via `withMotif`) zodat een motief alleen verschijnt als jij het kiest.
- [x] **D — titelkaart safe-zone.** `Concatenator.appendTitleCard`: tekst naar onderin-derde (h*0.72/0.83) + scrim-box; valt nooit meer over Pip's hoed/gezicht, weg vóór scène 2.
- [x] **E — warme, lichte outro.** Nieuwe `bible/outro.mp4` (5s): warme golden-hour achtergrond + schoon logo + rode SUBSCRIBE + "tap the bell"-CTA, i.p.v. de bijna-zwarte eindkaart. Lokaal via ffmpeg gebouwd (geen AI-generatie); origineel geback-upt naar /tmp.
- [x] **A1 — Mo-anchor (bandana → gebreide sjaal) — OPGELOST.** `bible/refs/mo.png` toont inmiddels de canonieke rode gebreide sjaal (geverifieerd 2026-06-10); plus multi-angle set in `bible/refs/mo/` (front + zijaanzicht; driekwart bewust weggelaten — Gemini bleef wimpers toevoegen). Rest-drift wordt per scène gevangen door de drift-QC.
- [ ] **Banner-canon checken** — in de banner lijkt Mo een groene sjaal te dragen (canon = rode gebreide sjaal); banner kan zelf fout zijn. Te verifiëren/corrigeren.

## Shot-DNA / Camera-Bible / World-velden / Prompt Compiler (VEO-gok minimaliseren)

Uit de VEO-expert review: slechte VEO-output ontstaat doordat VEO moet gokken. Camera,
emotie, tijd/weer, beweging en eind-pose zijn nu geen first-class data — het zijn gehoopte
prozaregels in `visualDesc`. Maak ze gestructureerd en compileer ze deterministisch.

- [x] **Camera-Bible + Prompt Compiler (VEO-pad)** — GEBOUWD. `cameraBible` in de bible (fase → hoek/lens/beweging) + `compileVeoPrompt` in de orchestrator die elke VEO-prompt deterministisch samenstelt: camera (Camera-Bible per fase) + wereld (bible-locatiebeschrijving + golden-hour) + scène-actie + lifelike micro-motion + character-DNA-tics + harde identiteits-stabiliteit. `locationId` wordt nu door de assembly meegedragen. `veoMotionPrompt` verwijderd.
- [~] **World-velden first-class.** `timeOfDay` + `weather` GEBOUWD als echte velden end-to-end: `ScriptTool`-schema, `GeneratedScript.Scene`, `domain.ScriptScene`, `V5__scene_world.sql`, `ScriptResponse.Scene`, `ScriptOrchestrator` (persist+get), `PromptBuilder` (LLM-instructie met rotatie), en in de orchestrator doorgezet naar image-service (`timeOfDay`/`weather` op de image-scene) én de VEO-compiler (`lightPhrase`/`weatherPhrase`). Resteert: `cameraAngle`/`lens`/`movement`/`emotion`/`goal` als per-scène velden (nu camera via Camera-Bible per fase; emotie/goal nog in proza).
- [~] **Shot-DNA-model.** `goal`, `emotion(+intensiteit)`, `motionSpeed`, `endPose` GEBOUWD end-to-end (schema, `GeneratedScript.Scene`, `ScriptScene`, `V6__scene_shot_dna.sql`, `ScriptResponse`, persist/get, `PromptBuilder`-instructie). `compileVeoPrompt` verwerkt nu `goal` (beat goal), `emotion` (performance) en `motionSpeed` (pace). `endPose` wordt opgeslagen + meegedragen; gebruik ervan = de endPose-keten hieronder. Resteert optioneel: `weight`, `focusSubject`, `ambient` als aparte velden.
- [x] **Eind-pose (`endImagePath`) aangezet** voor hero-shots — GEBOUWD. Orchestrator `generateEndStills` maakt een 2e still uit de Shot-DNA `endPose` (best-effort, zelfde anchors), `endImagePath` loopt via `buildVeoScenes` → `SceneRequest` → `ClipGenerationService` (GCS-upload via `uploadEndImage`) → `VertexVeoClient` (`cfgBuilder.lastFrame(endImage)`). VEO interpoleert nu start→eind i.p.v. wiebelen. ⚠️ De SDK-regel `lastFrame(...)` één keer verifiëren bij de eerste smoke test (methodenaam kan per google-genai-versie verschillen).
- [x] **Character-DNA uitbreiden** met verentextuur / bouw / gewicht / oogkleur voor cross-shot consistentie zonder identieke still. GEBOUWD: bible `dna`-blokken kregen `feathers`/`build`/`weight`/`eyeColor` (pip/mo/bo); `Character.Dna` + `BibleLoader.parseDna` uitgebreid; `PromptComposer.dnaLine` injecteert nu veren/bouw/ogen (weight = motion-cue → Veo-only).
- [x] **Prompt Compiler unificeren** over beeld (`composeReference`) én VEO zodat nooit een veld vergeten wordt (één bron: bibles + Shot-DNA). GEBOUWD: de Veo-compiler injecteerde voorheen alleen de `tic`; nu bouwt `PipelineOrchestrator.characterDnaClauses`/`dnaIdentityClause` de **volledige** DNA-identiteit (kleur+accessoire+silhouet+veren+bouw+gewicht+ogen) uit dezelfde bible-velden als `dnaLine`. Lock-step-contract in de comments aan beide kanten.

## Geautomatiseerde vision-QC vóór de review-gate — GEBOUWD

- [x] Nieuwe `SceneImageQc`-service: stuurt elk scène-beeld vóór de montage naar Claude-vision met de verwachte personages + DNA-accessoires; krijgt `{ok, issues}`. Faalt veilig (error/missing → pass, blokkeert nooit).
- [x] `PipelineOrchestrator.autoQcImages` draait dit ná `mergeAssets`, vóór de gate/Veo: zwakke beelden (ontbrekende accessoire, afgesneden onderwerp, dubbele kip, verkeerde kleur) worden automatisch her-gerold via `regenScene`, begrensd door `app.qc.max-rerolls-per-scene` (default 1) en `app.qc.max-total-rerolls` (default 4); `app.qc.enabled` (default true). Aanvult de handmatige gate (die blijft de backstop).
- [x] **Bugfix:** `QualityReviewer` SYSTEM-prompt had nog de oude omgewisselde cast-mapping (Pip blauwgrijs+oranje…) → gecorrigeerd naar banner-true, anders keurde de AI-critic juist-correcte kippen af.
- [x] **Bugfix (gevonden via QC-log op job 1e99185b):** de assembly-maps droegen geen `characters` → de QC liep BLIND ("No character list provided", kon kleur/accessoires niet checken) én de Veo-tic-injectie + per-personage-compiler kregen lege cast. `characters` nu toegevoegd aan de assembly-map in de script-build-loop. QC verifieert nu kleur/accessoires en rolt true drift (bijv. scène-1 blauwgrijze "Pip") opnieuw.

## Review-actieplan (Pixar Director + Engineering review)

Concreet plan uit de dubbele review. Drie tiers. Cross-refs naar bestaande secties waar het item al stond.

### Quick wins (≤1 dag)
- [ ] **Stemmen aan.** `VOICE_MODE=elevenlabs` + `ELEVENLABS_API_KEY` + per-personage stem-IDs in `bible/channel.yml` (slots staan al klaar: Pip≈Brittney/Charlotte, Mo≈Adam/Glinda, Bo≈Domi/Sammy). Grootste sprong van "diavoorstelling" naar "cartoon". *User-actie key; daarna verifiëren.*
- [ ] **Muziek** — zie pre-VEO quick win #1 (`bible/music/` of `SUNO_API_KEY`).
- [x] **Title card → golden-hour platteland** — KLAAR (zie Frame-feedback #1: nieuwe `bible/intro.mp4` met golden-hour backdrop).
- [x] **Locatie-/tijd-van-dag-rotatie afdwingen** in de scriptprompt — KLAAR. `PromptBuilder` heeft een HARD RULE (≥3 verschillende `locationId`s, nooit >2 opeenvolgend dezelfde) + `timeOfDay`-rotatie; `StructureValidator` dwingt het af met herprompt.
- [ ] **Per-personage signatuur-geluid** in de `sounds`-laag (`bible/sfx/<char>/`) — taal-agnostische herkenning; vult ook de stem-loze momenten.

### Mid-term (weken)
- [~] **Character-DNA-systeem.** Eén canonieke DNA per personage = kernkleur + silhouet + accessoire + tic + signatuur-geluid, in de bible (`characters[].dna`) als single source of truth.
  - [x] **Bible**: `dna`-blokken voor pip/mo/bo toegevoegd.
  - [x] **Image-pad**: `Character.Dna` + `BibleLoader.parseDna`; `PromptComposer.composeReference` injecteert nu DNA (accessoire-lock + silhouet + kernkleur) bible-gedreven i.p.v. hardcoded switch; `ReplicateImageProvider` anti-cast-negatives ook bible-gedreven.
  - [x] **Veo-motion** (`veoMotionPrompt` + `ticClause`, orchestrator): per aanwezig personage wordt de `tic` uit de bible nu in de Veo-bewegingsprompt geïnjecteerd ("Signature character motion: Bo pushes his glasses up…"). Bible wordt in de orchestrator gelezen + gecachet.
  - [x] **Thumbnail** (`thumbnail-service` `BibleLoader.dnaClause`): DNA (accessoire + silhouet) wordt nu aan elke cast-/main-character-omschrijving geplakt → thumbnail houdt de iconische identiteit (geldt voor de OpenAI-fallback).
  - [x] **Thumbnail matcht nu de film:** de thumbnail-service gebruikt echte **cast scène-stills als basis** (`GenerateThumbnailRequest.baseImagePaths` → `loadProvidedBase`), gekozen door de orchestrator (`pickCastStills`, meeste personages eerst). OpenAI-generatie is alleen nog fallback. Lost "thumbnails lijken niet op de characters" op, want het is letterlijk een filmframe.
  - [ ] **Voice/sfx**: `signatureSound` per personage koppelen aan de sounds-laag; `catchphrases` (al in bible) sterker in de scriptprompt verankeren.
- [x] **Beat-sheet-validator + herprompt-lus** (= Script & verhaal #1) — GEBOUWD, inclusief locatie-variatie-afdwinging. Resteert optioneel: een goedkope tekst-"script-criticus" (kwalitatieve scoring van boog/humor) bovenop de structurele checks.
- [ ] **Geautomatiseerde QC vóór de review-gate.** Vision-check per scène op (a) accessoire-aanwezigheid per aanwezig personage en (b) onderwerp binnen safe-frame → automatische her-rol van zwakke beelden i.p.v. mens-afhankelijke gate. Verlaagt de handmatige last bij schaal. Bouwt voort op `qualityReviewer` + `regenerateSceneImage` + retry/resume.
- [ ] **Eval-harnas voor prompts.** Meet promptwijzigingen objectief (vaste test-briefs → score) i.p.v. blind aanpassen. Maakt de reuzenprompts veilig itereerbaar.
- [ ] **Serie-mythologie.** Terugkerend openingsritueel + één running gag per personage in de bible/scriptprompt — maakt afleveringen memorabel en serie-herkenbaar.

### World-class
- [ ] **Echte character-animatie via Veo breder + lip-sync.** Veo verder dan hook/climax, en een lip-sync-pass (apart talking-character-model gestuurd door de dialoog-audio). Het enige fundamentele studio-plafond (zie ook "Grootste resterende gap"). Vereist dat de audiolaag er eerst is.
- [ ] **Score volgt de beat + beat-synced cuts** — zie Pacing & overgangen + muziek-sectie.
- [ ] **Per-serie asset-caching.** Locaties/props/anchors hergebruiken over afleveringen van dezelfde serie → drukt Gemini/Veo-kosten bij schaal.
- [ ] **Event-driven queue tussen stages** (i.p.v. orchestrator-polling) voor horizontale schaal naar honderden video's.

## Volgende — pre-VEO quick wins (geprioriteerd)

Doel: de goedkope Ken Burns-master consistent op ~80+ (AI Critic / Polish) krijgen
vóór we VEO-geld uitgeven. Vastgesteld na beoordeling van job `39adce35`.

1. [ ] **Achtergrondmuziek — grootste gat.** `bible/music/` bestaat niet, dus video's zijn vrijwel muziekloos (alleen de sting). Voor kindercontent dé kwaliteitsfactor. Route A: 3–5 royalty-free tracks in `bible/music/`. Route B: `SUNO_API_KEY` invullen → eigen track per video (~$0,10). *Tracks/Suno-key = user-actie; daarna selectie wiren/verifiëren.*
2. [~] **Mooie ondertitels, dán aan.** Herstijl = KLAAR: `SubtitleBurner.burn` gebruikt nu klein (fontsize 22), dunne near-black outline + zachte shadow, géén scrim-box, `MarginV=60` onderin, bottom-center, woord-wrap op 38 tekens. Resteert alleen de bewuste keuze om `burnSubtitles` default AAN te zetten — nu uit omdat soft-captions worden geüpload (inbranden is onomkeerbaar + extra encode-pass). Valideren op een echte render vóór default-on.
3. [x] **Locatie-/tijd-van-dag-variatie per scène.** KLAAR. `PromptBuilder` dwingt af: ≥3 verschillende `locationId`s, nooit >2 opeenvolgend dezelfde, en `timeOfDay`-rotatie (goldenHour/midday/dusk/night); `StructureValidator` rejectet + herpromptt bij te weinig locatie-variatie. Breekt de 'AI-farm sameness'.
4. [ ] **Verifiëren dat nieuwe assets vuren.** ambient-overlay, intro/outro, sting, whoosh, bel waren 'dormant tot asset'; assets bestaan nu. Checken dat ze écht meedraaien op een render en de flags/paden kloppen.
5. [ ] **Per-locatie ambient-sfx.** `bible/sfx/ambient/` ontbreekt (coop/pond/garden…). 'Erbij zijn'-gevoel; Polish score checkt dit. *Assets = user-actie.*
6. [ ] **Scène-duplicaat-restje.** In `39adce35` scène 3 leek twee witte kippen (bijna-duplicaat Pip). De 'exactly N, never duplicated'-guard staat er; monitoren of het zich herhaalt, evt. compositie-hint toevoegen.

Klaar deze sessie (gerelateerd): Gemini reference-conditioned image-provider (lost cast-collapse op), verplichte-accessoire-lock per personage (hoed/bril), transparant logo (`bible/logo.png`, origineel `logo-source.png`), series-dropdown in New Job.

## Frame-feedback (AI Critic op job `39adce35`)

Bevestigd aan de hand van de echte frames. Twee schone code/asset-fixes (hoge
zekerheid), één feature, één alleen-verbeterbaar (beeldgen is stochastisch).

1. [x] **Title card op vlak perzik-oranje → countryside golden-hour.** GEDAAN — nieuwe `bible/intro.mp4`: transparant logo over een zacht-geblurde golden-hour boerderij-backdrop (hergebruikte establishing shot), fade in/out, 1920×1080/3s, stille audiotrack. Origineel bewaard als `bible/intro-flat.mp4`. Mounted asset, geen rebuild.
2. [x] **Titeltekst over het gezicht (safe-zone).** KLAAR (= Frame-feedback ronde 2 #D). `Concatenator.appendTitleCard` zet de titel nu in de onderin-derde (`h*0.72`/`0.83`, twee gebalanceerde regels rond `h*0.70`) met scrim-box per regel, adaptieve fontgrootte (geen overflow meer) en fade-out vóór ~3.6s — weg vóór scène 2.
3. [x] **Terugkerend scenisch motief consistent (versierde eieren).** GEBOUWD (per-aflevering): `CreateVideoRequest.recurringMotif` → `VideoJob.recurringMotif` → `PipelineOrchestrator.withMotif` plakt het aan élke beeld-prompt (initiële gen, edit én clone). UI-veld "Recurring motif" staat in het New-job-formulier. Leeg = no-op.
4. [x] **Kip rechts / voeten afgesneden (compositie).** GEBOUWD. Safe-margin-hint zit in `PromptComposer.TAIL` ("WHOLE character … binnen central 90% safe area", "do NOT crop feet/head", "frame a touch wider for the zoom") op alle modi (describe/trigger/reference). Cover-fit-overscan teruggebracht: `MotionPreset` Ken Burns-zoom 1.12 → 1.08 (pans/diagonaal navenant), ~8% i.p.v. ~12% bijsnijden. *Alleen verbeterbaar, geen garantie; per-scène review-gate blijft vangnet.*

## Metadata & hoofdstukken

Beoordeling: titel/omschrijving/tags zijn goed (LLM via `emit_metadata`-schema:
kids-SEO-sjabloon, Shorts-`#Shorts`-handling, lengtelimieten). Hoofdstukken bestaan
(`enrichWithChapters`, per-fase, in de omschrijving).

- [ ] **Keyword-/SEO-onderzoek per video (hoger ranken in YouTube/Google-zoek).** Vóór metadata-generatie per aflevering keyword-research draaien en de winnende termen in titel/omschrijving/tags verwerken. Aanpak: (a) een keyword-bron koppelen — YouTube Data API `search`/autocomplete-suggesties, of een externe tool (bv. een SEO-MCP/连ector), om voor het topic de best-rankende long-tail zoektermen + zoekvolume te vinden; (b) `MetadataGenerator` die termen laten meewegen (prompt + tags) i.p.v. alleen het sjabloon; (c) optioneel de gekozen keywords tonen/bewerken in de review-gate. **Beslissen:** welke databron (gratis YouTube-autocomplete vs. betaalde SEO-API), en of het per video of per serie draait. Levert hogere vindbaarheid/CTR; vereist een externe data-koppeling (mogelijk een MCP-connector).
- [x] **Hoofdstuk ≥10s-regel afdwingen** — korte fases (hook/closer) worden samengevoegd tot blokken ≥10s, anders negeert YouTube *alle* hoofdstukken. (deze sessie)
- [x] **Hoofdstukken overslaan op Shorts** + pas tonen bij ≥3 geldige blokken, eerste op 00:00. (deze sessie)
- [x] **Rijkere, topic-specifieke hoofdstuktitels via de LLM** i.p.v. vaste labels ("The big moment" elke video). GEBOUWD: nieuwe `MetadataGenerator.chapterTitles` (forced-tool `emit_chapters`) maakt per fase een korte, topic-specifieke kid-titel; `PipelineOrchestrator.generatePhaseTitles` voedt 'm met een snippet per fase en `enrichWithChapters` gebruikt de titels (fallback = `prettifyPhase`). ≥10s-merge en first-chapter-op-00:00 ongewijzigd.
- [x] **Titel-lengte echt afdwingen** — prompt vraagt ≤60 tekens maar schema staat 100 toe. GEBOUWD: `MetadataGenerator.enforceTitleLength` capt hard op 60 op een woordgrens (geen ellipsis), behoudt een trailing `#Shorts`; toegepast op zowel `generate` als `localize`.
- [x] **Metadata-localisatie** — `VideoLocalization` bestaat (multi-taal) maar `MetadataGenerator` was alleen Engels. GEBOUWD: `MetadataGenerator.localize` vertaalt titel/omschrijving/tags (brand + Pip/Mo/Bo + `#TinyChickenWorld`/`#Shorts` blijven staan); `VideoLocalization` kreeg `localized_title/description/tags` (`V13`); `LocalizationController.localize` vult ze best-effort naast de script-vertaling.

## Pacing & overgangen

Beoordeling: **cuts zijn schoon en goed geregisseerd** — fase-gestuurde overgangen
(snappy near-cut op hook/dev, smoothleft de climax in, dissolve/fadeblack op
resolution/closer), xfade geklemd op halve clipduur, audio-acrossfade in sync,
anti-bounce motion, whoosh op cut-centra, `concatBare`-fallback. Cut-posities uit
echte geprobete clipduren → timing klopt met de render.

- [x] **Scèneduren-op-beats afdwingen** — GEBOUWD via de `StructureValidator` (zie Script & verhaal #1): fase-duren (±40%), totale duur (±20%) en fase-scènebudgetten worden nu afgedwongen met herprompt.
- [ ] **Echte match-cuts / eyeline-cuts.** De prompt vraagt erom in `visualDesc`, maar de assembly lijnt niets uit — het zijn crossfades. Continuïteit is zo goed als de beelden toevallig aansluiten. Echte match-cuts vereisen object-/pose-uitlijning tussen opeenvolgende beelden (zwaar; later).
- [ ] **Beat-synced cuts** — zie ook "Kwaliteit — overgangen / montage". Cuts op de muziekmaat leggen i.p.v. op scriptduur; vereist beat-detectie van de gekozen track.
- [x] **J/L-cut audiobruggen aanzetten.** GEBOUWD, drift-veilig. `Concatenator.JL_CUT_LEAD_SEC` = 0.12. Cumulatieve drift opgelost: elke clip-audio krijgt een stille staart (`apad=pad_dur=lead`), zodat de langere audio-crossfade die stilte opeet i.p.v. echte audio → totale audio loopt slechts één constante lead langer dan video (één trailing pad), NIET per-cut oplopend. Bare-fallback ongemoeid. ⚠️ Nog valideren op een echte render (kan hier niet compileren/testen).
- [~] **Held-frame fill op Veo-scènes — GEBOUWD** (deze sessie). `SceneClipBuilder.buildFromClip` houdt het slotframe vast (tpad) zodat een Veo-clip korter dan de scriptduur (8s-cap) tot volle lengte vult i.p.v. vroeg af te kappen; `-shortest` op de stem cap't op `dur`. Resteert optioneel: een trage push-in op het vastgehouden deel zodat het niet bevroren oogt (zwaarder: zoompan op video).
- [x] **Pixar-audit Story F — deterministische ConsistencyChecker + echte motion-as — GEBOUWD** (nacht 2026-06-09). Nieuw `review/ConsistencyChecker.java`: vangt cross-scène **prop-kleur-drift** (de "gieter wisselt van kleur"-bug), cast-sanity, accessoire-versterking — conservatief (waarschuwingen + lichte score-invloed, géén harde block). QA Characters-as blendt vision-drift 0.6 + deterministisch 0.4; QA Animation-as is nu echte motion-richness (motion-werkwoord + end-pose + pacing) i.p.v. "heeft motionDesc ja/nee". Sandbox-geverifieerd op false positives. → `QaBoard.java`.
- [x] **Pixar-audit Story D — color-script + render-look naar Veo — GEBOUWD** (nacht 2026-06-09). Bible: `renderStyle.veoLook` + `colorScript` per fase (koel/contrast in hook → rijk/warm in climax). `compileVeoPrompt` injecteert nu kleur-mood per fase + gedeelde render-look (voorheen kreeg Veo alleen een korte hardcoded look terwijl de rijke visualStyle alleen naar de stills ging). Image-kant houdt visualStyle; phase→image is veilige vervolgstap.
- [x] **Pixar-audit Story C (deel) — shotgrootte-variatie + eyeline-staging — GEBOUWD** (nacht 2026-06-09). `buildVeoScenes` rotateert shotgrootte per seq (wide→medium→close) voor bulk-scènes; bij ≥2 kippen nu ook expliciete eyeline/180°-staging (naar elkaar kijken, stabiele links/rechts). Geen extra clips/kosten. Volledige shot/reverse-dialoogdekking (meer clips) = spec.
- [x] **Pixar-audit Story E2 (deel) — emotie-boog in muziek — GEBOUWD** (nacht 2026-06-09). `SongController` voegt na het mood-bucketen een vaste emotionele boog toe aan elke Suno-instrumental-prompt (opbouw → triomfantelijk hoogtepunt → warme afronding); bucket-keyword-vrij dus verandert de bucket niet. Tekst-only. Stingers + emotie-ducking = spec.
- [x] **Pixar-audit Story G (plumbing) — multi-angle ref-set — GEBOUWD** (nacht 2026-06-09). `GeminiImageProvider.loadCharacterAnchors`: gebruikt `bible/refs/<id>/*.png` (max 3 hoeken) als die map bestaat, anders de single `{id}.png` (huidig gedrag). Prompt meldt dat meerdere images dezelfde kip vanuit andere hoeken zijn. Additief; activeren = de ref-set genereren (betaald + goedkeuring).
- [x] **Pixar-audit Story E1 — emotie-opbouw-check — GEBOUWD** (nacht 2026-06-09). `QaBoard.emotionBuildNote`: arousal per scène, valideert climax = piek; informatief (geen score-impact).
- [x] **Pixar-audit #12 — anticipatie-telegraph schaalt met emotie-intensiteit `(n/5)` — GEBOUWD** (nacht 2026-06-09). `compileVeoPrompt`.
- [x] **Stem laten acteren — per-personage voiceSettings + per-regel emotie — GEBOUWD** (2026-06-09, Pixar-audit Story A). `ElevenLabsClient.synthesize` schreef één globale stability/similarity; nu een nieuw `VoiceSettings`-record (stability/similarity/style/use_speaker_boost) dat (1) per personage uit `bible.characters[].voiceSettings` komt (Pip los/expressief, Mo kalm) en (2) per regel wordt gemoduleerd door `Line.emotion` via `withEmotion()` (arousal/valentie-buckets, geclamped 0-1). Orchestrator geeft nu de scène-`emotion` (Shot-DNA) mee aan elke voice-regel (beide bouw-plekken) + intro/outro-regels kregen een emotie. Numeriek geverifieerd: karakter blijft behouden onder emotie (excited Mo > excited Pip qua stabiliteit). Verwachte audio-winst +10%. Code-compleet, ongecompileerd → build.bat.
- [x] **Branded ElevenLabs-stemmen op intro & outro — GEBOUWD** (2026-06-09). De kippen stellen zich voor / zwaaien gedag met DEZELFDE stemmen als in de afleveringen i.p.v. Veo's eigen synthetische audio. `Intro/OutroRebuildService` synthen de vaste regels via voice-service (`synthVoiceLines`, 1 scène per regel → 1 MP3 elk); paden gaan via `buildIntro/buildOutro(clip, voiceLines)` naar `Intro/OutroBuilder`, die Veo-audio droppen en de regels op gespreide offsets over de intro-/zwaai-beat mixen. Alle audiotakken `aresample=48000` + output `-ar 48000` (amix-rate-mismatch voorkomen; in sandbox getest met 44,1/22/48 kHz). Best-effort terugval op clip-audio bij synth-fout. Regels moeten woordelijk matchen met MOTION_DESC. Code-compleet, ongecompileerd → build.bat is de poort.
- [x] **Afgekapte gesproken zinnen — OPGELOST** (deze sessie). `SceneClipBuilder.effectiveDur()` probet de echte stem-lengte (ffprobe) en rekt de scèneduur op zodat die **nooit korter is dan de voice-over** (+0.4s staart), begrensd op `scripted+3s` tegen uitschieters; valt veilig terug op de scriptduur als proben faalt. Geldt voor zowel Ken-Burns- (`build`) als Veo-scènes (`buildFromClip`). Hiermee wordt een dialoogregel langer dan de scriptduur niet meer mid-zin door `atrim` afgesneden.

## Script & verhaal — handhaving (nu gespecificeerd, niet afgedwongen)

Beoordeling: de scriptprompt dekt boog / leeftijdsgeschikt / re-hooks / bevredigend
einde zeer grondig (storyArcs met beats, episodeStructure-fases, rehookEverySeconds,
emotie-rotatie, age-band-regels, goede/slechte voorbeelden). Maar de kwaliteit leunt
vrijwel volledig op "het model volgt de prompt" — er is bijna geen vangnet.

- `ScriptGenerator.validate()` checkt alleen duur-afwijking (>25% = **alleen log-warning, geen afkeuring**) en scène-volgorde. De prompt dreigt met "the validator will reject and re-prompt" maar die validator bestaat niet.
- Enige echte afkeur-/herprompt-lus is de **dedupe** (SimHash) → bewaakt variatie, niet verhaalkwaliteit.
- **AI Critic** (`QualityReviewer`) draait ná de render op beeldframes, scoort alleen character_drift/audio/framing/branding — niet boog/re-hooks/einde.

Open punten (volgorde van waarde):

1. [x] **Echte beat-sheet-validator + herprompt-lus** — GEBOUWD. Nieuwe `StructureValidator` checkt tegen de bible-`episodeStructure`: geldige `phase` per scène, min/max scènes per fase, fase-duur (±40%), totale duur (±20%), **laatste scène = closing-fase** (bevredigend einde), scène-volgorde, én **locatie-variatie** (≥2 locaties bij ≥6 scènes, max 4 opeenvolgend dezelfde). `ScriptOrchestrator` herpromptt bij overtredingen met de exacte fouten als feedback (`PromptBuilder` feedback-blok), tot `maxRetries`; op de laatste poging gaat-ie door (human gate + AI critic als backstop). Dekt ook het pacing- en wereld-variatie-gat.
2. [x] **Goedkope tekst-"script-criticus"-pass** vóór de render. Scoort boog / re-hook-cadans / bevredigend einde / leeftijdstaal en triggert max één gerichte herschrijf. Veel goedkoper dan een zwak verhaal pas ná een volledige render ontdekken. GEBOUWD: nieuwe `ScriptCritic` (forced-tool `emit_critique`, draait op het goedkope script-model = Haiku) → `Critique{overall,arc,rehook,ending,ageLanguage,issues,directives}`. `ScriptOrchestrator` draait 'm ná de structuur-gate; bij `overall < min-score` één gerichte herprompt via een eigen critic-feedback-kanaal (`PromptBuilder`/`ScriptGenerator`), begrensd door `app.critic.max-rewrites` (apart van de dedupe-retries). Faalt veilig (pass). Config: `app.critic.{enabled,min-score,max-rewrites}`.
3. [x] **Structuurscore in Polish meewegen** zodat zwakke boog/timing zichtbaar is vóór upload. GEBOUWD: deterministische `structureScore` (uit `StructureValidator`-violations) + `criticScore` worden op `Script` opgeslagen (`V7__script_scores.sql`) en geëxposeerd in `ScriptResponse`. De orchestrator kopieert ze naar `VideoJob` (`V12`), en `QualityScorer` (Polish) heeft nu twee gewogen checks: "Story structure validated" (≥80) en "Story-critic passed" (≥70).
4. [ ] **Story-arc-selectie performance-wegen** i.p.v. puur random (`randomStoryArc()`), zodra er analytics zijn welke arcs beter presteren.

## Kwaliteit — beeld & beweging

- [~] **Ambient-effecten overlay-laag.** Loopende clip met transparante achtergrond (vlinders / vuurvliegjes / blaadjes / bokeh) wordt over elke Ken Burns-scène gecomposit. Ingebouwd in `SceneClipBuilder`; **dormant tot** je een loop neerzet op `bible/fx/ambient.mov` of `.webm`. Nu één globale laag — per-locatie/tijd-van-dag keuze is nog open (zie hieronder).
- [ ] **Per-locatie/tijd-van-dag effectkeuze.** `locationId` + `timeOfDay` doorzetten naar de assembly en het overlay-effect daarop kiezen (nacht → vuurvliegjes + sterren, tuin → vlinders + bijen, na-regen → glinstering, winderig → blaadjes).
- [ ] **Sfeer-overlays** (godrays, lensflare, drijvende bokeh) in screen-blend op golden-hour scènes.
- [ ] **2.5D parallax** — voorgrond/achtergrond apart laten bewegen voor nepdiepte. Bewerkelijk; later.
- [x] **Veo voor hero/climax-scènes** — `phase`→sceneType routing: hook/climax krijgen nu het `hero`-model i.p.v. standaard lite. (hybrid-routing bestond al.)

## Kwaliteit — overgangen / montage

- [~] **Echte J/L-cut** (audio knipt vóór/na het beeld). Ingebouwd als flag `JL_CUT_LEAD_SEC` in `Concatenator`, **default 0 = uit** (audio en beeld perfect in sync). >0 geeft een J/L-feel maar bouwt kleine A/V-lead op over lange video's → eerst valideren op een testrender. Volledig correcte versie = audio losweken naar absoluut geplaatste `adelay`/`amix`.
- [~] **Whoosh op overgangen.** Ingebouwd in `Concatenator`; **dormant tot** je `bible/sfx/transitions/whoosh.mp3` (of `bible/sfx/whoosh.mp3`) neerzet.
- [ ] **Effect ↔ geluid koppelen.** Ambient (`ambientByLocation`) laten samenvallen met het zichtbare effect (bijengezoem bij bijen, krekels bij vuurvliegjes).
- [x] **Fase-gestuurde overgangen + kortere duur** (Concatenator).
- [x] **Motion-continuïteit over de cut** (geen tegenbeweging-bounce).

## Kwaliteit — muziek & geluid

- [x] **Suno-koppeling.** `SUNO_API_KEY` doorgezet (`.env`, compose, assembly-config). Leeg = royalty-free tracks; vul de key in → originele muziek/Song Mode activeert (~$0,10/track). Geeft elke video een eigen track i.p.v. 3 herhaalde.
- [x] **Slimmer ducken.** Diepere, snellere sidechain (ratio 8, attack 20ms, threshold −30dB) → heldere dialoog; muziek ademt voller terug in de stiltes.
- [ ] **Score die de beat-sheet volgt.** Muziek per `phase` laten meebewegen (rustig setup → swell op climax → warm resolution), evt. twee cues crossfaden rond de climax. Vereist phase-timings in de `AudioMixer`.
- [ ] **Muzikale accenten (stingers)** op gasp/reveal/grap — koppelen aan transitie-/fasegegevens.
- [ ] **Rijkere foley-laag** (voetstapjes, vleugelklap, "boing", plop) gekoppeld aan acties in `visualDesc`.
- [ ] **Beat-synced cuts** — scènes op de maat knippen.

## Assembly / montage

- [x] **Robuuste fallback** — concat hervalt op een bare montage (zonder cosmetische overlays) als de volledige filtergraph faalt, zodat een overlay-bug nooit de hele render sloopt.
- [x] **Minder generatieverlies** — tussen-encodes op bijna-lossless (crf 16); FinalEncoder doet de delivery-compressie.
- [x] **Finale loudnorm** in FinalEncoder (na muziek-mix), zodat de geleverde loudness echt −14 LUFS is.
- [x] **Config-toggles** — color grade / titelkaart / eindkaart instelbaar via `app.assembly.*` (env).
- [x] **Echte two-pass loudnorm** — KLAAR. `FinalEncoder.loudnormFilter` draait eerst een meetpass (`loudnorm=…:print_format=json`, `-f null -`), parset `input_i/tp/lra/thresh` + `target_offset` uit het JSON-rapport (via `runFfmpegCaptured`, die stderr meekoppelt) en voedt ze terug met `linear=true`; valt veilig terug op single-pass bij een onvolledige/mislukte meting. Wordt als laatste audiopass aangeroepen door `AssemblyService`. ⚠️ Kanttekening: de capture-helper heeft een vaste 2-min timeout — bij erg lange video's kan de meetpass die overschrijden en valt-ie (veilig) terug op single-pass; voor Shorts/korte video's ruim voldoende.
- [ ] **Re-encode-consolidatie** — het aantal volledige H.264-passes verder terugbrengen (concat → intro/outro → subs → final) door meer in één filtergraph te doen.
- [x] **Geanimeerde eindkaart** — rode SUBSCRIBE-knop met slide-up + bounce + "for more + tap the bell". Outro.mp4 neemt het automatisch over als dat bestand bestaat; optioneel schuddend bel-icoon via `bible/fx/bell.png` (dormant tot asset).
- [ ] **Slimmere auto-Short** — het meest energieke segment kiezen i.p.v. een vaste hook+climax-crop.

## Pipeline / orchestratie

- [x] **AI Critic "Auto-Fix"-knop.** GEBOUWD. Knop op de QA-kaart (job-detail) → `POST /api/v1/videos/{id}/autofix?iterations=1`. Backend bestond al volledig (`startAutoFix`/`runAutoFixPass`: re-rollt beeld-fixbare zwakke scènes via per-scène vision-QC + Critic-findings, hermonteert, her-auditeert, caps op `default-target:90`/`max-iterations`/`max-rerolls`, pauzeert altijd op de review-gate, nooit auto-upload). Toegevoegd: `iterations`-param (UI vraagt 1 = "1 ronde, dan review", keuze gebruiker) + de UI-knop met bevestiging/kosten-waarschuwing. *Oorspronkelijke open beslissing (agressiviteit) → gekozen: 1 ronde + review.*

- [ ] **Cast-thumbnail breder triggeren** — ook op topics die meerdere personages impliceren zonder ze bij naam te noemen.

- [x] **God-class `PipelineOrchestrator` opsplitsen — stap 1 GEBOUWD.** Veo-prompt-compiler uit de orchestrator getrokken naar een nieuwe `VeoPromptCompiler`-component (`@Service`, zelfde package). Verplaatst: `compile` (was `compileVeoPrompt`) + cameraBible/cameraSpec, locations, surfaces/surfacePhrase, pace/ease, light/weather/colorScript/veoLook, isHeroPhase(String)/strip/emotionIntensity, én de DNA-clausules (`dnaIdentityClause`/`scaleLockClause`/`ticClause` + hun caches) — die werden alléén door de compiler gebruikt. **Bleef in de orchestrator** (elders gebruikt): `readBible`, `dnaAccessoryLines`, `isHeroPhase(Map)`, `VEO_NEGATIVE`, `withMotif`. Aanroep vervangen door `veoPromptCompiler.compile(...)`; compiler kreeg een eigen `readBible()`. Pure refactor, identieke output, ~300 regels kleiner. Statisch geverifieerd (geen losse refs, schone naden); **moet nog gecompileerd/getest worden in de rebuild.** Vervolgplakken (state machine / stages / QC scheiden) blijven open.

## Bugs / opgehelderd

- [x] **Hergebruikt slot-frame (scène 12=18).** Was een job van vóór de per-scène-seed fix (`seed*1_000_003 + seq`); huidige code geeft elke scène een eigen seed, dus opgelost. Geen actie nodig.

## Grootste resterende gap vs. een studio als Pixar

Geregisseerde, consistente **character-animatie met lip-sync** (personages die met intentie bewegen en in elke shot exact on-model blijven). Zit niet in een still-plus-beweging-aanpak; de enige fundamentele "echte studio"-grens. Rest (verhaal, muziek, render, character-locks) is incrementeel te dichten.

## Gereed in deze sessie (overig)

- Vier build-blokkerende compileerfouten (webflux ×2, double→int cast, `bible()`-property).
- `IMAGE_PROVIDER=replicate` + character-lock fixes (lifeStage/accessoires; correcte anti-cast negatives).
- Hook = extreme close-up (scène 1) i.p.v. wide establishing shot.
- Thumbnail: emoji-tofu gestript, donkere scrim achter titel, stijl-/accessoire-lock, cast-variant.
- `phase`-veld door script-service heen (schema + V4-migratie + response) en door naar de assembly (`SceneInput`).

## Geparkeerd — lage prioriteit / slechte ROI

- [ ] **Re-encode-consolidatie** — minder volledige H.264-passes door meer in één ffmpeg-filtergraph te doen (concat → intro/outro → muziek/sting → subs → final). **Beoordeling: niet nu doen.** Kwaliteitswinst is **klein en nauwelijks zichtbaar** (generation loss is al gedempt met bijna-lossless CRF 16-tussen-encodes); de echte winst is **rendersnelheid/kosten**, niet beeld. Werk is **substantieel + fragiel** (de filtergraph heeft al een bare-fallback omdat-ie kwetsbaar is; meer samenvoegen = nóg kwetsbaarder) en hier niet testbaar. Pas oppakken bij optimaliseren op **rendertijd bij schaal** (honderden video's) of als er zichtbare verzachting optreedt. Veilige mini-variant die al bestaat: de subs-pass valt nu weg zolang ondertitels uit staan (soft-captions i.p.v. inbranden).

- [ ] **Beat-synced cuts (beat-detectie)** — cuts op de muziekmaat leggen i.p.v. op `durationSeconds`: beats van de gekozen track detecteren (librosa/aubio) en de cut-/overgangsposities naar de dichtstbijzijnde beat snappen → ritmische, professioneel-aanvoelende montage. **Middelmatig werk, redelijke ROI — maar geblokkeerd op muziek** (die er nog niet is). Oppakken zodra `bible/music/` of Suno staat.

- [ ] **Echte match-cuts / eyeline-cuts (pose-detectie)** — het einde van scène N en het begin van scène N+1 echt laten matchen (pose/object-positie uitlijnen, of eyeline: opent op wat het personage aankijkt). Vereist pose/object-detectie + frame-uitlijning tussen elk opeenvolgend paar. **Veruit het zwaarst en minst betrouwbaar** (beeldgen is stochastisch, beelden lijnen niet vanzelf uit); fase-gestuurde overgangen + crossfades verbergen de meeste naden nu al. **World-class, veel later.**

### Fase 2 / world-class (architectuur/zwaar) — twee gaan over zichtbare kwaliteit, twee over schaal

- [ ] **VEO breder + lip-sync** — VEO (echte beweging) op meer/alle scènes i.p.v. alleen hook+climax, plus een lip-sync-pass (snavels in sync met de dialoog-audio). **Grootste zichtbare sprong** = de enige echte "studio-plafond"-doorbraak: personages bewegen met intentie en praten on-model. **Duur** (VEO per scène) en **vereist dat de audiolaag er eerst is** + een lip-sync-model. Zwaar + kostbaar.
- [ ] **2.5D parallax** — elke still in voorgrond/achtergrond-lagen splitsen en op verschillende snelheden bewegen → nep-diepte zónder VEO. **Bescheiden zichtbare polish**, maar **bewerkelijk** (diepte-schatting + achtergrond inpainten per beeld).
- [ ] **Per-serie asset-caching** — locaties/props/anchors hergebruiken over afleveringen van dezelfde serie. **Géén zichtbare kwaliteit per video**, maar drukt Gemini/VEO-kosten fors + betere cross-episode consistentie. **Waarde groeit met volume.**
- [ ] **Event-driven queue tussen stages** (Kafka/Service Bus i.p.v. orchestrator-polling) — ontkoppelt de stages; workers pakken events op. **Géén kwaliteit, puur schaalbaarheid + robuustheid** (honderden video's parallel, horizontaal schalen, nette crash/retry). Alleen zinvol bij volumeproductie.

### Audio + zelflerend (geblokkeerd op assets / data — code-hooks bestaan grotendeels)

- [ ] **Stemmen aan** (`VOICE_MODE=elevenlabs` + key + per-personage stem-IDs) — echte TTS zodat Pip/Mo/Bo práten. **Grootste sprong van "diavoorstelling" naar "cartoon".** Alternatief: bewust `sounds`-mode (gratis, taal-onafhankelijk, Pingu/Shaun-stijl). **Geblokkeerd op jouw key of die keuze.** → dit eerst van de audiolaag.
- [ ] **Per-locatie ambient-SFX** (`bible/sfx/ambient/coop.mp3`, `pond.mp3`…) — zacht geluidsbed per plek (coop kraakt, vijver/kikkers, tuin zoemt). Geeft een **"er echt zijn"-gevoel**; Polish-score checkt dit. **Geblokkeerd op de geluidsbestanden.**
- [ ] **Per-character signatuur-SFX** (`bible/sfx/<char>/<emotie>.mp3`) — kipgeluiden per personage voor sounds-mode + signatuur-cues. **Taal-onafhankelijke herkenning** (Pip's piep, Bo's giechel), vult stem-loze momenten, meer variatie = minder herhaling. **Geblokkeerd op de assets.**
- [ ] **Story-arc-weging** — i.p.v. `randomStoryArc()` de keuze biasen richting arcs/moods/lessen die in de **analytics** beter presteren → **zelflerende lus**, compounding waarde. Plumbing (`VideoAnalytics`/`AnalyticsPoller`/`InsightsAggregator`) bestaat deels; mist per-arc tracking + weging. **Geblokkeerd tot je gepubliceerde video's met kijkdata hebt.**
