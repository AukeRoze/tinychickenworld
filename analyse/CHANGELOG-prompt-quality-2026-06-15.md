# Prompt-kwaliteit & continuïteit — sessie-changelog + build-checklist (15 juni 2026)

Doel: alle Veo-prompt-defectklassen uit de scène-review structureel oplossen — in de compiler, de `emit_script`-guidance, de bible en een nieuw QC-vangnet. **EP3 (gepubliceerd) is NIET gepatcht**; alles geldt voor toekomstige afleveringen. **Niets is lokaal gecompileerd** (sandbox-VM lag eruit) → draai `mvn test` per service vóór redeploy.

---

## 1. Wat & waarom (defectklasse → oplossing → laag)

| Defect (scène) | Structurele oplossing | Laag |
|---|---|---|
| Cast-lock telt iedereen, ook off-frame leden → geperst/gemorpht (s8) | Off-frame-relaxatie: framed vs off-frame uit visualDesc; telling blijft, off-frame niet geforceerd | compiler |
| Wide/groeps-reveal markeerde leden onterecht off-frame (s22/23) | Guard `framesWholeCast` (wide reveal / "all three") onderdrukt de relaxatie | compiler |
| Intieme close-up propte de rest er toch bij (s24) | Sterke off-frame-taal ("out of frame / melt into blur") bij close-up + subset | compiler |
| Camera-preset botst met de actie: push-in vs pull-back/wide (s22/29), flock-focus op solo (s27), hoek (s15), wide lens op close-up→GoPro (s3), groeps-actie in push-in (s23) | Shot-bewuste preset: hoek/lens/beweging/focus afgeleid uit visualDesc; preset blijft default | compiler |
| Volledige locatie-detail in een macro-Setting → AI zoomt uit (s1/3) | Shot-bewuste Setting: close-up → bokeh-achtergrond i.p.v. volle locatie | compiler |
| `morning`/`afternoon` viel terug op golden-hour (s1-7, 16-20) | `lightPhrase` cases toegevoegd + bible-`timeOfDay`-lijst aangevuld | compiler + bible |
| Telegraph forceerde "ogen wijd/lichaam aanspannen" op een ei/afwezig personage (s1) | Gezichts-cues conditioneel ("whenever a face is in frame") | compiler |
| Truncatie / count-mismatch / licht-tijd / schaal / camera / focus | `VeoPromptLinter` (7 checks) — vóór de Veo-spend | nieuw |
| Statische `Action` op niet-hero Veo-scènes (s3/4/5/6) | `motionDesc`-guidance: vul motion-brief op élke geanimeerde scène | guidance |
| Prop vergeten/geteleporteerd; staat-paradox uncracked→barst (s6-9, s13, s21) | visualDesc-guidance: prop-continuïteit + prop-state (permanent, dynamische wijziging) | guidance |
| Locatie-teleport zonder transitie (s8→9, 15→16, 24→25) | locationId-guidance: aankomst/establishing, geen mid-beat-sprong | guidance |
| Start-frame ≠ eerste frame (pull-back/val vanaf eind-pose) (s1/2/10/11/21) | visualDesc-guidance: visualDesc = compositie op seconde 0 | guidance |
| Anatomie (knieën/duim) + occlusie (stro op hoofd/in snavel) (s14/28/8/4) | visualDesc-guidance: wings/fluffy body, geen mensledematen; geen occlusie van kenmerken | guidance |
| Tijd-tegenstrijdigheid visualDesc vs Setting (s19-21) | timeOfDay-guidance: veld moet matchen met visualDesc | guidance |
| Schaal-paradox "same size" vs Relative-size-lock (s26) | visualDesc-guidance: niet de grootte her-specificeren + linter-check | guidance + linter |
| garden ↔ pebblePath ondergrond transformeert (s4-7) | bible: beide delen donkere-aarde basis (naam "Pebble Path" intact) | bible |
| Hook-preset lockte op "face/eyes" bij prop-hook (s1 ei) | bible: hook-preset subject-agnostisch | bible |
| Continuïteit (prop/locatie/cast/tijd) semantisch | `ScriptCritic` continuity-as (cap continuity≤4 → overall≤65) | critic |
| Auto-Fix kon ongemerkt 8 Veo-clips re-renderen | `app.autofix.max-rerolls: 3` | config |
| Intro/outro-prompt niet kopieerbaar in UI | accessors + `/api/v1/brand/clip-prompts` + Brand-knop | endpoint + UI |
| Intro/outro-prompt verbeteringen | medium-shot still, eyes-open vanaf 4s, negative-space, giggle-merge, tekst/UI-negatives | bible-clips |

---

## 2. Gewijzigde bestanden per service

### orchestrator (`services/orchestrator`)
- `src/main/java/.../service/VeoPromptCompiler.java` — cast-lock off-frame + guards + intieme close-up; shot-bewuste camera (hoek/lens/beweging/focus); shot-bewuste Setting (bokeh); `lightPhrase` morning/lateMorning/afternoon; conditionele telegraph; helpers `describesCloseUp/describesWideReveal/framesWholeCast/describesCameraAngle/isLongLens/isFaceOrFlockFocus/mentionsWord/joinNames`.
- `src/main/java/.../service/VeoPromptLinter.java` — **NIEUW**, 7 invariant/contradictie-checks.
- `src/main/java/.../api/BrandController.java` — `GET /api/v1/brand/clip-prompts` + injectie Intro/OutroRebuildService.
- `src/main/java/.../service/IntroRebuildService.java` — prompt-accessors; STILL medium (waist-up); eyes-open vanaf 4s; tekst-negatives.
- `src/main/java/.../service/OutroRebuildService.java` — accessors; negative-space-wording; giggle+spreek samengevoegd; `OUTRO_NEG`.
- `src/main/resources/application.yml` — `app.autofix.max-rerolls: 3`.
- `src/main/resources/static/ui/brand.html` — "📋 Veo-prompt"-knoppen + textareas.
- `src/main/resources/static/assets/js/brand-page.js` — fetch + copy-wiring.
- `src/test/java/.../service/VeoPromptCompilerLeanTest.java` — nieuwe cases + verrijkte test-bible.
- `src/test/java/.../service/VeoPromptLinterTest.java` — **NIEUW**.

### script-service (`services/script-service`)
- `src/main/java/.../anthropic/ScriptTool.java` — `emit_script`-guidance (visualDesc, locationId, motionDesc, timeOfDay, characters).
- `src/main/java/.../service/ScriptCritic.java` — continuity-as (schema + rubric + record + parse + `renderScript` toont location+cast).

### bible (`bible/channel.yml`) — data-only, hot-reload
- `garden` + `pebblePath` descriptions + `locationSurfaces` geharmoniseerd.
- `cameraBible.hook` subject-agnostisch.
- `timeOfDay`: `lateMorning` + `afternoon` toegevoegd.

---

## 3. Build & test (vóór redeploy)

```bash
# 1) script-service — checkt o.a. ScriptTool-JSON parse + golden gates + ScriptCritic
cd services/script-service && mvn -q clean test

# 2) orchestrator — checkt VeoPromptCompilerLeanTest + VeoPromptLinterTest
cd services/orchestrator && mvn -q clean test
```

Let op bij de golden gates (`ScriptEvalHarness`): die zijn bewust streng. De StructureValidator is NIET aangescherpt (cast/locatie-checks bleven semantisch → critic), dus de golden scripts horen groen te blijven. Als een test rood is, is het waarschijnlijk een transcriptie-/syntaxfout in mijn edits — niet een bewuste regelwijziging.

## 4. Uitrol

- **bible/channel.yml**: geen redeploy — Brand-pagina → "🔄 Reload bible (alle services)" (of `POST /api/v1/brand/bible/reload`).
- **script-service** (.java): rebuild + redeploy.
- **orchestrator** (.java + application.yml + static/): rebuild + redeploy (de `application.yml`-cap en de Brand-knop komen mee).
- Volgorde maakt niet uit; geen DB-migraties.

## 5. Nog open / beslissingen

- **VeoPromptLinter nog niet in de pipeline gedraad** — klaar om als pre-Veo gate in `PipelineOrchestrator` te hangen (truncatie/count hard blokkeren, contradicties als waarschuwing → `QcFinding`).
- **Colour mood is fase-gestuurd**, niet tijd-bewust — kan nog botsen met dusk-scènes (residual; diepere fix = colorScript-paletten tot mood-woorden verzachten of colour mood tijd-bewust maken).
- **"Medium group shot" in een push-in-fase** wordt alleen gedekt als de actie een groepswoord bevat ("all three"); anders `veoCameraOverride`.
- **EP3** blijft live zoals 'ie is; deze fixes raken alleen nieuwe afleveringen.
