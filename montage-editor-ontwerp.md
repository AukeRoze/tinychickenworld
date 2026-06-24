# Montage-editor — ontwerpnotitie

Doel (Auke): in de montage per scène **in/uit-punten kunnen kiezen met een schuifje**
(begin én eind zelf bepalen) en **tussen scènes via een +-icoon een overgang kiezen**
(uitgebreide set). De bouwstenen bestaan al — dit ontwerp ontsluit ze als handmatige
UI-controls en laat ze door de montage toepassen.

> Waarom een notitie eerst: dit raakt **frontend + orchestrator + video-assembly-service**
> en het kernstuk zit in **ffmpeg** (clip-trim + xfade-filtergraph). Dat kan ik niet in
> de sandbox compileren/renderen, dus we bouwen in twee increments die je per stap met
> `mvn test` + één render kunt verifiëren.

Bestaande bouwstenen (geverifieerd):
- `SceneClipBuilder.buildFromClip` rendert een clip naar `durationSeconds` met
  `-t dur -i clip` (leest de eerste `dur` seconden). Een **start-offset** = `-ss` toevoegen.
- `TransitionConfig` leest `assembly.transitions` (per phase: ffmpeg xfade type + seconden);
  `Concatenator` past ze toe. Er is al een ruime `VALID_XFADE`-set (fade, dissolve, wipe*,
  slide*, circleopen/close, zoomin, pixelize, radial, …).
- `AssemblyRequest.SceneInput` draagt al `durationSeconds` (Min 2, Max 120) + `phase`.

---

## A. Inkorten met in/uit-punten (increment 1) — GEBOUWD (werkkopie, ongetest)

> Gebouwd 22 jun: `SceneDto.trimStartSeconds` + `setSceneTrim` + `POST /scenes/{seq}/trim`
> (ReviewController); `SceneSummary.trimStartSeconds/trimEndSeconds` (VideoController);
> `AssemblyRequest.SceneInput.trimStartSeconds` + `SceneClipBuilder.buildFromClip` ffmpeg
> `-ss`; frontend twee in/uit-schuifjes + "Toepassen" in elke scène-rij.
> **Te verifiëren:** `mvn test` (orchestrator + video-assembly-service) + één render —
> trim een scène naar bv. 1–7s, Re-assemble, controleer dat die scène op 1s start en 6s duurt.



**Datamodel — `SceneDto` (orchestrator):**
- `trimStartSeconds` (double, default 0) — waar in de bronclip de scène begint.
- `durationSeconds` blijft de **lengte** van de scène in de film = (uit − in).
- (de UI rekent in/uit → start + lengte; back-compat: afwezig = hele clip 0…10s.)

**Endpoint:** `POST /api/v1/videos/{id}/scenes/{seq}/trim` body `{ "startSec": 1.5, "endSec": 7.0 }`
→ `mergeSceneUpdate`: `setTrimStartSeconds(start)` + `setDurationSeconds(end−start)`.
Validatie: `0 ≤ start < end ≤ clipLen`, min lengte 1s.

**Doorgeven aan assembly:** in de assembly-scene-map (`m.put(...)`) ook
`trimStartSeconds` zetten; `durationSeconds` = de getrimde lengte (de 10s-floor mag
deze door-de-gebruiker-gezette waarde NIET terugzetten — floor alleen toepassen als
de gebruiker niets koos).

**AssemblyRequest.SceneInput:** veld `double trimStartSeconds` (default 0).

**ffmpeg — `SceneClipBuilder.buildFromClip`:** vóór `-i scene.clipPath()` een
`-ss <trimStartSeconds>` zetten als > 0 (accuraat input-seek); `dur` = lengte zoals nu.
De boomerang-fill/`atrim=duration=dur` blijft werken op de lengte.

**Frontend (`renderScenes`):** per scène een **dual-handle range-slider** over 0…cliplengte
(cliplengte = 10s, of de echte clipduur als we die meesturen). Twee handgrepen = in/uit;
toont "in 1,5s · uit 7,0s · lengte 5,5s". Op loslaten → `POST …/trim`. Read-out naast
de bestaande scène-acties.

---

## B. Overgangen via +-icoon (increment 2) — GEBOUWD (werkkopie, ongetest)

> Gebouwd 22 jun: `SceneDto.transitionType/transitionSeconds` + `setSceneTransition` +
> `POST /scenes/{seq}/transition`; `SceneInput.transitionType/transitionSeconds`;
> `AssemblyService` encodeert de override in de `phases`-lijst als `@t:<type>:<sec>`;
> `Concatenator.transitionFor` decodeert dat (cut → mini-fade van 0,04s; onbekend type →
> phase-default) — werkt zo in álle drie de render-paden zonder signatuurwijziging.
> Frontend: **film-rol** (horizontale strook met scène-miniaturen) met tussen elke twee
> scènes een **+-knop** die een picker opent (uitgebreide set + duur). `SceneSummary`
> draagt de gekozen overgang zodat het label de huidige keuze toont.
> **Te verifiëren:** `mvn test` + één render — zet een wipe tussen twee scènes, Re-assemble,
> controleer de overgang (en dat "cut" een harde snit geeft).



**Datamodel — `SceneDto`:** `transitionType` (string, ffmpeg xfade-naam of "cut") +
`transitionSeconds` (double, default uit `TransitionConfig`). Conventie: de overgang
hoort bij de scène waar je **naartoe** gaat (de "+" vóór scène N zet N's `transitionType`).

**Endpoint:** `POST /api/v1/videos/{id}/scenes/{seq}/transition` body
`{ "type": "wipeleft", "seconds": 0.4 }` (type `"cut"` = harde snit, geen xfade).

**AssemblyRequest.SceneInput:** velden `transitionType` + `transitionSeconds` (nullable
→ val terug op de phase-default uit `TransitionConfig`/`Concatenator`).

**`Concatenator`:** per grens de override gebruiken als die gezet is, anders de huidige
phase-gedreven `TransitionConfig.forPhase`. `"cut"` = de xfade overslaan (directe concat).
Dit is de gevoeligste wijziging (filtergraph) → met één render valideren.

**Uitgebreide set in het +-menu (besluit Auke):**
cut · fade (crossfade/dissolve) · fadeblack · fadewhite · wipeleft/right/up/down ·
slideleft/right · circleopen · circleclose · zoomin · pixelize · radial.
(Alle uit `VALID_XFADE`, dus geen filtergraph-verrassingen.)

**Frontend:** tussen elke twee scène-rijen een klein **"+"-knopje**; klik opent een
compact menu met de set hierboven (+ een klein duur-schuifje 0,1–1,5s). Toont de
gekozen overgang als label op de grens. Op kiezen → `POST …/transition`.

---

## C. Montage als aparte pipeline-stap + achtergrondmuziek (increment 3) — GEBOUWD (werkkopie, ongetest)

> Gebouwd 23 jun. Maakt van de montage een **eigen orchestrator-stap met eigen
> status en score-bolletje**, vlak ná de clips en vóór de assemblage — zodat
> knippen/trimmen (A), overgangen (B), volgorde en muziek bewust achter één gate
> gebeuren in plaats van automatisch door te vloeien naar assembly.

**Nieuwe status:** `JobStatus.MONTAGE_REVIEW_PENDING` (tussen `VEO_REVIEW_PENDING`
en `ASSEMBLING`); opgenomen in `isAwaitingReview()`; NIET in `JobRecovery.IN_FLIGHT`
(blijft dus correct gepauzeerd na een herstart).

**Gate-vlag:** `review.beforeMontage` (bible/channel.yml, env `REVIEW_BEFORE_MONTAGE`,
**default true**). Toegevoegd aan `ReviewProperties` + `ReviewConfigLoader` +
`ReviewProperties.defaults()`. Staat de vlag uit → exact het oude gedrag (direct
door naar assembly).

**State-machine (`PipelineOrchestrator`):**
- Nieuwe helper `enterMontageGate(jobId)`: gate aan → `pauseForReview(MONTAGE_REVIEW_PENDING)`;
  gate uit → `runAssemblyStage`. Pre-selecteert één keer een default-muziektrack
  (`autoPickMusic(mood)`) als er nog geen gekozen is, zodat het paneel met een
  zinnige keuze opent.
- Alle vier clip→assembly-overgangen lopen nu via deze helper: einde `runVeoStage`,
  "no scenes to Veo-ify", Veo-kill-switch, en de non-Veo (Ken Burns) assets-afronding.
- `approve()` krijgt `case MONTAGE_REVIEW_PENDING -> runAssemblyStage`.

**Achtergrondmuziek (bouwde voort op bestaande `bible/music` + `POST /{id}/music`):**
- `GET /api/v1/videos/{id}/music` → library uit channel.yml (id, mood, nette naam,
  `previewUrl`, `selected`) + huidige keuze van de job.
- `GET /dashboard/music/{trackId}.mp3` (MediaController) → streamt een track voor
  preview; strikt pad-gevalideerd binnen `/bible/music`.
- Zetten gebeurt via de al bestaande `POST /{id}/music` (→ `backgroundMusicPath`),
  toegepast bij de assemblage.

**Frontend (`job-page.js`):**
- Nieuwe stap **"Montage"** in de stepper (tussen Video en Assembly); `PHASE_OF`,
  `PROGRESS` en de score-bolletje-indexen verschoven (Assembly 3→4; montage-slot op 3
  via `lastReview.montageScore`, verschijnt alleen als die score bestaat).
- `montagePanel()` boven de scènes (alleen bij `MONTAGE_REVIEW_PENDING`):
  muziek-dropdown met **▶ Preview** + Toepassen. Volgorde/trim/overgangen blijven de
  per-scène controls (film-rol + schuifjes + +-menu) eronder.
- De review-gate-balk toont voor deze status het label **"🎬 Assembleren"** + een hint.

**Te verifiëren:** `mvn test` (de state-machine-test kreeg de montage-hop erbij;
`GATES_OFF` heeft nu 6 booleans) + redeploy orchestrator + één run: clips klaar →
job pauzeert op MONTAGE_REVIEW_PENDING → kies muziek (preview), trim/overgang →
"Assembleren" → assembly draait. Zet `REVIEW_BEFORE_MONTAGE=false` om de gate uit te
zetten en het oude directe gedrag te bevestigen.

---

## Volgorde & verificatie

1. **Increment 1 (trim):** SceneDto-velden + `/trim`-endpoint + SceneInput-veld +
   `buildFromClip` `-ss` + frontend dual-slider. Verifieer: trim een scène naar 1,5–7s,
   reassemble, controleer dat de clip op 1,5s start en 5,5s duurt.
2. **Increment 2 (overgangen):** SceneDto-velden + `/transition`-endpoint + SceneInput +
   `Concatenator` per-grens-override + frontend +-menu. Verifieer: zet een wipe tussen
   twee scènes, reassemble, controleer de overgang.

Elke stap: `mvn test` (orchestrator + video-assembly-service) + redeploy + één render.
Back-compat: alle nieuwe velden default leeg → exact het huidige montagegedrag.
