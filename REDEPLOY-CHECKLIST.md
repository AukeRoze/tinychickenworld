# Redeploy-checklist — openstaande fixes (per 23 jun 2026)

Alle hieronder verzamelde fixes zitten **al in de werkkopie** maar zijn nog niet live:
ze wachten op `mvn test` + rebuild + redeploy. Ze stapelden zich op omdat de sandbox
niet kon bouwen (geen JDK21/Maven). Doel: in **één ronde** alles uitrollen i.p.v. los.

Bron: auto-memory (14–18 jun). Volgorde van uitvoeren staat in de TL;DR.

---

## TL;DR — volgorde

1. [ ] `git add -A && git commit` met een **fatsoenlijke message** (niet weer "Changed code")
2. [ ] `mvn test` vanuit root — golden/lean-snapshots ververst waar ze terecht driften
3. [ ] `docker compose build` van de gewijzigde services (zie lijst hieronder)
4. [ ] `docker compose up -d`
5. [ ] Bible-reload: `POST /api/v1/brand/bible/reload` (via bridge)
6. [ ] Live verificatie per fix (sectie "Na redeploy — verificatie")
7. [ ] Data-acties: rerolls, camera-overrides, cast-unlocks, EP3 staged patches

> **Let op (omgeving):** nieuwe jobs persisteren niet — de DB reset bij elke herstart.
> `e2ec9448` is de enige blijvende (completed) job; verifieer fixes daar tegenaan.
> Een nieuwe Big-Oak script-run blijft falen tot dat DB-probleem opgelost is.

---

## Per service — wat in de werkkopie zit

### orchestrator  (grootste — de meeste fixes)
- [ ] **Intro/outro-overgang renderbaar** (24 jun) — instelbare overgang op de grens intro→scène 1 en laatste scène→outro. **Orchestrator:** Flyway `V28__bumper_transitions.sql` (4 kolommen; V10 was al bezet → V28) + `VideoJob`-velden (`intro/outroTransitionType/Seconds`) + `POST /api/v1/videos/{id}/bumper-transition` + `saveBumperTransition` + doorgegeven aan `assembleAsync` (4 params erbij in `AssemblyServiceClient`; call-site bijgewerkt). **video-assembly-service:** `AssemblyRequest` 4 velden, `Concatenator.concatHeterogeneous`-overload past de gekozen xfade toe op de intro/outro-grens (`bumperTransition`-helper; "cut"=mini-fade, leeg=default-dissolve), `AssemblyService.attachIntroOutro` geeft ze door (alleen als de bumper bestaat). **Test:** `PipelineOrchestratorStateMachineTest` — 6 `assembleAsync`-matchers kregen +4 `any()`. **Frontend:** bumper-`+` op de grenzen, POST + preview-simulatie. ⚠️ raakt **2 services** → `docker compose up -d --build orchestrator video-assembly-service`. ddl-auto=validate, dus de migratie MOET mee.
- [ ] **Restart herhaalt onnodig ASSETS_GENERATING-fix** (24 jun) — `assetsComplete()` eiste nog een `audioPath` per scène, maar de voice-service is weg (audio uit Omni-clip, `audioPath` wordt nooit gezet) → assets golden NOOIT als compleet → `resumePoint`/crash-recovery herhaalde bij elke herstart/Retry de assets-stap. Nu: `assetsComplete` checkt alleen de still. Plus `resumeAfterRestart` voor `ASSETS_GENERATING` slaat de stap over als de stills er al staan en gaat door (Veo→`runVeoStage`, anders→`enterMontageGate`). Geen DB-wijziging.
- [ ] **WebClient "Connection prematurely closed BEFORE response"-fix** (24 jun) — idle-eviction op de Reactor Netty-pool (`maxIdleTime 20s`, `maxLifeTime 5m`, `evictInBackground 30s`, `SO_KEEPALIVE`) zodat stale keep-alive-connecties niet meer worden hergebruikt (de hoofdoorzaak). **Toegepast in 4 services — die moeten ALLE 4 herbouwd:**
  - `orchestrator/config/WebClientConfig` (geldt voor alle orchestrator-clients, connector-niveau, incl. assembly/upload).
  - `script-service/config/WebClientConfig` (Claude).
  - `image-service/config/WebClientConfig` (OpenAI-bean) **én `provider/GeminiImageProvider`** — die bouwde z'n eigen WebClient ZONDER connector, dus had ook géén timeout; nu eviction + 180s responseTimeout. (ReplicateImageProvider = dormant, niet aangeraakt.)
  - `thumbnail-service/config/WebClientConfig` (beide beans).
  - Plus `orchestrator/client/Resilience.paid` retryt nu op deze "before response"-close (`requestNeverProcessed`/`prematureCloseBeforeResponse`) — veilig (er kwam niets terug → geen dubbele billing); timeouts/5xx blijven niet-retried. ⚠️ assembly/upload-clients doen rauwe `.block()` zonder retry — leunen op de preventie + job-level retry. Optioneel later: assembly/upload + ReplicateImageProvider ook nog.
  - **Rebuild-commando:** `docker compose up -d --build orchestrator script-service image-service thumbnail-service`.
- [ ] **Montage als aparte stap + achtergrondmuziek** (23 jun) — nieuwe `JobStatus.MONTAGE_REVIEW_PENDING` (in `isAwaitingReview()`, NIET in `JobRecovery.IN_FLIGHT`); gate-vlag `review.beforeMontage` (env `REVIEW_BEFORE_MONTAGE`, default **true**) in `ReviewProperties`/`ReviewConfigLoader`/`defaults()`; helper `enterMontageGate()` in `PipelineOrchestrator` vangt alle clip→assembly-overgangen (Veo klaar, geen Veo-scènes, Veo-kill-switch, Ken Burns-assets-afronding **én het handmatige approve van IMAGES_/ASSETS_REVIEW_PENDING op de non-Veo-route** — die ging eerst rechtstreeks naar assembly en sloeg de montage over) + pre-selecteert default-muziek; `approve()` montage-case → assembly. Muziek: `GET /api/v1/videos/{id}/music` (library+selectie) + `GET /dashboard/music/{id}.mp3` (preview, `MediaController`), zetten via bestaande `POST /{id}/music`. Frontend `job-page.js`: nieuwe "Montage"-stepperstap (Assembly 3→4), `montagePanel()` met muziek-dropdown+preview, gate-label "🎬 Assembleren". Trim/overgangen zaten al (`montage-editor-ontwerp.md` increment 1+2; sectie C = deze stap). ⚠️ **gate is default aan voor álle jobs** — `e2ec9448`/nieuwe runs pauzeren nu op de montage-stap vóór assembly.
- [ ] **Scène-preview = frame uit de clip** (23 jun) — `job-page.js`: `stillFrame()`/`reelFrame()` tonen bij `hasClip` een `<video>`-poster (media-fragment `#t=0.5`, frame ~0,5s) uit `/dashboard/{id}/scene/{seq}/clip.mp4` i.p.v. de AI-gegenereerde `scene_NN.png`; geen clip → still als vanouds. CSS `dashboard.css`: `.scene-img-frame video` zelfde sizing als `img`. ⚠️ verschijnt pas ná **import-clips** (zet `clipPath`/`hasClip`) — clips alleen in `bible/afleveringen/<ep>/` zetten is niet genoeg. Frontend-only, geen DB/migratie.
- [ ] **UI-aanpassingen jobpagina** (23 jun, frontend-only — `job-page.js` + `job.html` + `dashboard.css`, geen backend/DB):
  - **Stepper-stap "Voice + Images" → "Scenes"** (`PHASES`).
  - **"Video"-stap (Veo) eruit, alles onder "Montage".** Geen Veo in Auke's flow (clips komen uit Google Flow/Omni). `PHASES` = Script · Scenes · Montage · Assembly · … (Video verwijderd); `PHASE_OF` herindexeerd, `VEO_*` mapt naar de Montage-stap. Nieuw inklapblok `step-montage` (`#montage-host`) in `job.html` met **montage-paneel (muziek) + filmrol** samen (`renderMontageSection()`); montage-paneel uit `renderScenes` gehaald. `applyStepFocus`: Montage-sectie opent in VEO_/MONTAGE_-fasen; stepper "Montage"-stap (idx 2) scrollt ernaartoe. Score-bolletje: montage idx 2, QA-board idx 3.
  - **Filmrol = echte filmstrip + grotere, scrubbare frames.** `reelFrame` 220×124 (was 104×59); bij een clip is de miniatuur een `<video>` die je via de in/uit-slider **live scrubt** (`scrubTo`, seek-coalescing) met een tijd-badge die toont waar je staat; step 0,1s. CSS `.film-reel`: zwarte band met twee rijen perforatiegaten (repeating-linear-gradient, `background-attachment:local` → scrollt mee).
  - **Dubbelklik-titel-edit.** In de Metadata-kaart dubbelklik op de titel → inline input (Enter=opslaan met 100-tekenvalidatie, Esc=annuleren) via gedeelde `saveMeta()`; bestaande "✎ Edit"-knop blijft.
  - **5s-flikkerfix.** `loadScenes()` rendert alleen opnieuw bij echte wijziging (`scenesSignature` / `lastScenesSig`) — voorkomt herladen van de clip-`<video>`-miniaturen elke poll en behoudt open trim-/overgang-/muziek-controls.
- [ ] **Cast-recovery gast-fix** (18 jun) — `augmentPresentCast()` in `VeoPromptCompiler`: sprekend of in actietekst genoemd eendje wordt altijd in de cast-lock opgenomen (EP3 sc.19/21/24).
- [ ] **Character-canon** (18 jun) — `channel.yml`: `renderStyle.veoLook` + `dna.veoKey` pip/mo/bo + Mo `antiAccessory` verwijderd. Golden veoKey-snapshots moeten waarschijnlijk ververst.
- [ ] **Accessory- + onomatopee-guard** (17 jun) — `AccessoryGuard.java`, `OnomatopoeiaGuard.java`, `ScriptTool` emit_script-guidance. (AccessoryGuard heeft een identieke kopie in image-service — zie daar.)
- [ ] **Camera-override** (15 jun) — `SceneDto.veoCameraOverride`, 11-arg `compile()`, call-sites in `PipelineOrchestrator`/`VideoController`, `ReviewController` endpoint. Plus prompt-hygiene rondes 2/3 (trailing-punctuatie, pace, headcount close-up).
- [ ] **Cast-lock off-frame relaxatie** (15 jun) — `headcountLockClause(charIds, closeUp, visualDesc)`, close-up uit visualDesc afgeleid, `VeoPromptLinter` + `ScriptCritic` continuity-as.
- [ ] **Relative-size cast-scoped** (17 jun) — `scaleLockClause` + per-personage `dna.veoSizeRank` in `channel.yml` (alleen aanwezige cast genoemd).
- [ ] **Script-review-fixes 17 jun** — multi-speaker lipsync, scènes altijd 10s (`durationSeconds` ge-floored), geen dubbele scènebeschrijving, Pip PURE WHITE (incl. `IntroRebuildService.IDENTITY_LOCK`, `QualityReviewer`), niet-sprekende kuikens bij naam, thumbnail-/image-/verhaal-prompt kopieerbaar, Afl.-kolom in jobs-grid, "GoogleFlow Omni" als default motion-model (sentinel).
- [ ] **Flow native audio** (16 jun) — `veoNativeAudio`-flag + `directorBrief()` format, Engelse gesproken dialoog, muziek uit de prompt (naar post), negative-constraints sidecar, één signatuur-actie/clip, soort-bewuste roster, anti-realisme/continuïteit (4 punten).
- [ ] **Voice-service-removed** (17 jun) — `voiceClient` eruit uit `PipelineOrchestrator`/`Intro`/`OutroRebuildService`, voice-stage no-op, `BibleReloadService` fan-out weg, dialoog-edit-endpoints verwijderd.

### script-service
- [ ] **Treatment-tweetraps** (17 jun) — `TreatmentService` + `POST /api/v1/scripts/treatment`.
- [ ] **emit_script-guidance** (gedeeld met orchestrator) — accessory-ownership, prop-/locatie-/tijd-continuïteit, "geen dubbele beschrijving", SINGLE REVEAL.
- [ ] Character-canon raakt ook de compiler/script-service (golden refresh).

### image-service
- [ ] **Roster species + cast-scope** (17 jun) — `PromptComposer`: `rosterCountSentence`, `scopeDnaText`, `neutraliseCountWords` (trio/three/four), `Character.species/rosterNoun`. (EP3 sc.24/25 trio-paradox.)
- [ ] **AccessoryGuard-kopie** (17 jun) — identiek bijgewerkt aan orchestrator (incl. non-possessieve worn-branch).
- [ ] **Pip pure-white + anatomie** (17 jun) — `wingSafe()`, belichting tijd-agnostisch in `visualStyle`, `ReplicateImageProvider` negatives.

### thumbnail-service
- [ ] **Thumbnail-prompt preview** (17 jun) — `describe()` + `ThumbnailPromptPreview` + `POST /api/v1/thumbnails/preview-prompt`.
- [ ] **Pip pure-white** in QC-javadoc/voorbeelden.

### video-assembly-service + infra
- [ ] **Voice-service-removed** — `SceneClipBuilder.buildFromClip` gebruikt `[0:a]` (clip-audio), `effectiveDur` = scripted duur, `audioPath` optioneel; muziek mixt over clip-audio.
- [ ] **Infra** — `services/voice-service` map weg, uit parent `pom.xml` modules, voice-container + `VOICE_URL` uit `docker-compose.yml`.
- [ ] ⚠️ **Render één aflevering** om de audio te verifiëren — ffmpeg-gedrag vangt de compiler-test niet.

### Al live, GEEN redeploy (data-only, hot-reload)
- ✅ **Locatie-canon** (15 jun) — bible-descriptions opgeschoond, garden = moestuin + zonnebloemen, garden↔pebblePath ondergrond geharmoniseerd. *Restant:* `name`-veld "The Sunflower Garden" nog hernoemen; garden-scènes (2 & 4) opnieuw rerollen — die draaiden vóór de reload.

---

## Tests groen krijgen vóór redeploy

Draai minimaal deze klassen (orchestrator tenzij anders):

- [ ] `VeoPromptCompilerCastRecoveryTest`  (6 cases, incl. "duck under" ≠ duckling)
- [ ] `VeoPromptCompilerLeanTest`  (native-audio, relsize solo/pair/trio, off-frame, single-speaker, dusk, whole-body, count-words)
- [ ] `VeoPromptCompilerCameraOverrideTest`, `VeoPromptCompilerHygieneTest`
- [ ] `AccessoryGuardTest`, `AccessoryGuardParityTest`, `OnomatopoeiaGuardTest`
- [ ] `VeoPromptLinterTest`
- [ ] image-service: `PromptComposerScopeTest`, `PromptComposerAccessoryTest`, bestaande `PromptComposer*Test` (back-compat 6-arg constructor)
- [ ] assembly: `PipelineOrchestratorStateMachineTest`, `RerenderVisualsTest` (voiceClient eruit)
      → montage-stap: test kreeg de montage-hop erbij (default-gates pauzeren nu ook op `MONTAGE_REVIEW_PENDING`); `GATES_OFF` heeft nu **6** booleans (`beforeMontage`).

> Verwachte drift: de `channel.yml` `veoKey`-wijziging (character-canon) kan golden
> veoKey-snapshots laten afwijken. Controleer of de diff klopt vóór je ze ververst.

---

## Na redeploy — verificatie (via bridge)

- [ ] Bible-reload geeft op alle services `true`.
- [ ] `/scenes` van `e2ec9448` levert het nieuwe **director's-brief** format.
- [ ] veoPrompt sc.19/21/24 toont `... AND 1 DUCKLING (TOTAL N)`.
- [ ] veoPrompt Pip-only beats (sc.1-4) noemen **geen** Mo/Bo (relsize cast-scoped).
- [ ] Roster sc.24/25: "Exactly 2 chickens and 1 duckling" — geen 3e kip.
- [ ] Geen "same size"/scale-flicker, geen "trio" bij gereduceerde cast.
- [ ] Render één aflevering → audio komt uit de clip (geen ElevenLabs-restje, geen stille track).
- [ ] **Montage-stap:** een job met clips klaar pauzeert op `MONTAGE_REVIEW_PENDING` (stepper toont "Montage"); montage-paneel toont muziek-dropdown, **▶ Preview** speelt een track af; trim/overgang per scène werkt; "🎬 Assembleren" → assembly draait. Met `REVIEW_BEFORE_MONTAGE=false` loopt 'ie direct door (oude gedrag).
- [ ] **Jobpagina-UI:** stepper toont Script · Scenes · Montage · Assembly (geen "Video"/"Voice + Images"); filmrol + muziek staan in het Montage-blok en de "Montage"-stap scrollt ernaartoe; filmrol oogt als filmstrip met grotere frames; sleep de in/uit-slider van een clip → het frame scrubt live mee met tijd-badge; bij een geïmporteerde clip toont de preview een frame uit het filmpje; dubbelklik op de titel opent de inline-edit; de miniaturen flikkeren niet meer elke 5s.

---

## Na redeploy — data-acties

- [ ] **EP3 staged patches** verplaatsen `bridge/staged/` → `bridge/commands/`:
      `ep3-s05-fix-glasses.json`, `ep3-s08-fix-bonk-dialogue.json`.
- [ ] **Camera-overrides zetten** (`POST /scenes/{seq}/camera-override`):
      sc.2 "35mm lens, medium shot, slow pull-back reveal" · sc.4 "low-angle, 35mm, slow drift" ·
      sc.11 "low-angle, 35mm, slow drift" · sc.12 "extreme close-up, 85mm, very shallow depth".
- [ ] **Cast-unlocks zetten** (`POST /scenes/{seq}/characters`) + reroll:
      sc.8 [pip,bo] · sc.12 [pip] · sc.13 [pip,mo] · sc.19 [pip,mo] · sc.27 [pip,duckling].
      (sc.24 = intentioneel blurred bg, niet aanraken.)
- [ ] **Garden-scènes 2 & 4 rerollen** (moestuin-canon) + bible `name` "The Sunflower Garden" hernoemen.

---

## Losse open punten (niet blokkerend voor deze ronde)

- `VeoPromptLinter` is gebouwd maar **nog niet als pre-Veo gate in de pipeline gedraad** — losse follow-up.
- "Herschrijf zwakke beats"-knop (treatment #4) bewust nog niet gebouwd.
- DB-persistentie: nieuwe jobs overleven een herstart niet — de echte blocker voor nieuwe runs.
