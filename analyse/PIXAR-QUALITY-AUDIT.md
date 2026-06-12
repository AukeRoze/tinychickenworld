# 🎬 VEO Pixar Quality Review Board — Audit Tiny Chicken World

> **▶ Voortgang (nacht 2026-06-09→10):** Stories **A, F, D, E1** en een deelversie van **C** + top-20 #12 zijn inmiddels **gebouwd** (code-compleet, ongecompileerd → `build.bat`). Zie **`docs/PIXAR-AUDIT-NIGHT-PROGRESS.md`** voor de ochtend-checklist en de specs van wat nog open staat (C-full, E2, B, G, H, I).

**Scope:** volledige geautomatiseerde pijplijn (script → image → Veo → assembly → audio → QA → upload).
**Methode:** code-grounded audit. Elk bevinding verwijst naar echte velden/bestanden in de repo, niet naar aannames.
**Datum:** 2026-06-09.

> **Belangrijke nuance vooraf (en correctie op een eerdere snelle scan).**
> Dit systeem is architectonisch al **ver boven** een typische "AI-farm"-pijplijn. Voordat we schieten, eerst eerlijk wat er **al** staat — want de meeste "ontbrekende" dingen uit de opdracht zíjn er al, en de echte gaten liggen subtieler.
>
> Al geïmplementeerd en geverifieerd in `PipelineOrchestrator.compileVeoPrompt()` (regels 2185-2258) + `bible/channel.yml`:
> - **Camera-Bible per fase** (hoek, lens, beweging, focus, depth-of-field) — `cameraBible:` regels 244-251.
> - **Volledige character-DNA-injectie in Veo** (coreColor, silhouette, accessory, feathers, build, weight, eyeColor, antiAccessory) — `dnaIdentityClause()` 1978, identiek aan de image-kant (`PromptComposer.dnaLine`).
> - **Cross-character anti-swap-lock** ("never move a hat/glasses/scarf onto another chicken") — 1991-1995.
> - **Scale-lock** (Mo > Pip > Bo, subtiel) — `scaleLockClause()` 1963.
> - **Signature tics** per personage — `ticClause()` 1880.
> - **Ground-physics** alleen op contact-beats (dig/hop/splash) — `surfacePhrase()` 2088.
> - **Ease-in/out & anticipatie-telegraph** op hero-beats — `easeClause()` 2115, G6 in `compileVeoPrompt`.
> - **End-pose-interpolatie** (start- én eind-frame aan Veo) — `generateEndStills()` 1740.
> - **Inter-clip continuïteit** (hero volgt op hero) — 1820-1828.
> - **Expliciete verhaal-arcs + fase-structuur met beat-eisen** — `storyArcs:` 335, `episodeStructure:` 253.
> - **8-assige QA-board met safety-floor** — `QaBoard.java`, publish-gate 80.
>
> De score start dus **niet** laag. De resterende afstand tot Pixar zit in een handvol diepere systemen die hieronder eerlijk worden blootgelegd.

---

## 1. Diepgaand technisch gesprek — VEO-specialist × Java-architect

**VEO-specialist:** Laat ik beginnen met het compliment dat pijn doet: jullie prompt-compiler is beter dan 95% van wat ik in productie zie. Het probleem is niet meer "Veo raadt de camera" of "Veo wisselt de hoed". Dat is opgelost in tekst. Het probleem is dat jullie identiteit en performance volledig op **taal** leunen, en taal heeft een plafond. Veo krijgt per shot één startframe plus een paragraaf DNA. Binnen een clip van 8 seconden interpoleert het model vrij — en tekst kan "exact dezelfde snavel" zeggen, maar het model heeft geen *identiteits-embedding* die het daaraan houdt. Pixar heeft een character-rig; jullie hebben een goed geschreven verzoek.

**Java-architect:** Eens, en dat is precies waar de datastructuur tekortschiet. We hebben één canonieke DNA-bron, mooi — maar we hebben geen **character-model-artifact**: geen turnaround-sheet, geen multi-angle referentieset, geen vastgelegde identiteits-vector. Elke still wordt *onafhankelijk* gegenereerd door Gemini, geconditioneerd op één anchor-PNG. Twee scènes van dezelfde Pip zijn twee aparte trekkingen. Daarom zie je micro-drift tussen scènes die geen enkele tekstregel vangt.

**VEO-specialist:** En het ergste: dat stapelt. Still A drijft 3% af, Veo animeert vanaf A en drijft nog eens 4%, de volgende scène trekt een verse still met 3% andere drift. Een kind merkt het misschien niet bewust, maar het brein registreert "niet helemaal dezelfde kip" — en dát is het verschil tussen een merk-personage en een AI-kip.

**Java-architect:** De oplossing is een **ConsistencyState** — een stateful object dat per episode de gekozen identiteits-anchors, de accessoire-posities en de prop-toestand vasthoudt en dwingend doorgeeft. Nu is continuïteit *per prompt*; het moet *per episode-state* worden. We hebben al `PropAnchorService`; dat moet promoveren van "genereer een prop-plaatje" naar "houd de wereld-staat bij".

**VEO-specialist:** Tweede groot gat: **stem zonder acteren**. Ik zag het in `ElevenLabsClient.synthesize()` — het gebruikt één globale `stability`/`similarity_boost` uit de config. Maar de bible heeft prachtige per-personage `voiceSettings` (Pip 0.45 los/expressief, Mo 0.65 kalm) én elke `Line` heeft een `emotion`-veld. Niets daarvan bereikt ElevenLabs. Pip klinkt dus exact zo opgewonden als Mo kalm is — namelijk: identiek. Pixar laat een acteur dezelfde regel tien keer anders inspreken; jullie hebben de regie-aanwijzingen geschreven en ze vervolgens in een la gelegd.

**Java-architect:** Dat is een zuivere bedradings-bug op data-niveau, geen modelbeperking. `Line.emotion` en `character.voiceSettings` bestaan; ze worden alleen niet doorgegeven naar de synthese-call. Dat is een **S**-fix met een **+10%** audio-impact.

**VEO-specialist:** Derde: **cinematografie is sjabloon, geen découpage**. De Camera-Bible is goed, maar elke "hook" krijgt dezelfde push-in, elke "climax" dezelfde pull-back. Binnen een dialoogscène is er geen shot/reverse-shot, geen eyeline-match, geen 180°-as. Twee kippen die praten worden in één statisch medium shot gezet. Pixar knipt tussen blikrichtingen; daar ontstaat de helft van de emotie. Nu leest het als "elke scène hetzelfde camerapreset" — de AI-handtekening die je juist wilt vermijden.

**Java-architect:** Dat vraagt een **shotPlan** per scène in plaats van een fase→preset-lookup. Een klein model-veld: `shotSize` (wide/medium/close/extreme-close), `cameraMove`, en bij dialoog een `coverage`-flag die shot/reverse genereert. We hebben de fase al; we moeten *binnen* de fase variëren en bij meerdere sprekers de as bewaken.

**VEO-specialist:** Vier: **geen kleur-script en geen materiaal-definitie**. Lighting is nu `timeOfDay → frase` (golden-hour default). Mooi consistent, maar Pixar ontwerpt een *emotionele kleurprogressie* over de hele film — koel in de twijfel, warm in de overwinning. En materiaal: hoe vangt dons licht, hoeveel subsurface-scattering, hoe glanst de snavel. Nu staat er alleen "Soft 3D Pixar look" als tekst. Dat is een wens, geen specificatie.

**Java-architect:** Een `colorScript`-laag per fase in de bible (palet + key-light-richting + contrast), plus een `materials`-blok per personage (dons, snavel, ogen, accessoire-materiaal). Beide injecteren in zowel image- als Veo-prompt. Deterministisch, goedkoop, en het tilt de hele "render-uitstraling" omhoog.

**VEO-specialist:** Vijf: **muziek staat los van het verhaal**. Suno krijgt een mood-prompt en levert een track; die wordt eronder gelegd. Geen leitmotief per personage, geen stinger op de climax-beat, geen score die meebuigt met de emotie-curve. Pixar's score *is* de emotie. Bij jullie is het behang.

**Java-architect:** En zes — meta: **de QA-board meet animatie niet echt**. De "Animation"-as is een *proxy*: framing-score van de vision-audit plus "heeft deze hero-scène een motionDesc, ja/nee". Dat zegt niets over of de beweging *goed* is. "Characters" is puur vision-oordeel (character_drift). Er is geen deterministische accessoire/kleur-verificatie. Een drift die de vision-LLM mist, glipt door de poort. We hebben een **deterministische consistentie-checker** nodig naast het LLM-oordeel.

**VEO-specialist:** Tot slot het echte plafond dat eerlijk benoemd moet worden: **Veo doet geen lip-sync**. "Snavel beweegt tijdens het spreken" is generiek; het is niet gekoppeld aan fonemen. Voor preschool-content is dat acceptabel (Pingu praat ook niet articulair), maar het betekent dat "Pixar lip-sync" met deze tool-keuze simpelweg niet haalbaar is. Dat moeten we niet wegpoetsen — het is een bewuste grens, geen bug.

**Java-architect:** Akkoord. Dan is de eerlijke conclusie: geen enkel gat is "het systeem deugt niet". Het zijn zes-zeven gerichte upgrades bovenop een sterke basis. Laten we ze per probleem uitschrijven.

---

## 2. Probleemanalyse (Probleem · Oorzaak · Gevolg · Pixar-standaard · Java-oplossing · VEO-oplossing · Prioriteit · Winst)

### P1 — Identiteit leunt volledig op tekst + één startframe (geen identiteits-lock door de beweging)
- **Probleem:** binnen en tussen Veo-clips drijft het personage subtiel af (snavel, oog, donsstructuur).
- **Oorzaak:** Veo krijgt per shot één startframe + DNA-tekst; er is geen identiteits-embedding/referentieset die het model door de 8s vasthoudt. `buildVeoScenes()` geeft `startImagePath` + (soms) `endImagePath`, verder tekst.
- **Gevolg:** kijker registreert onbewust "niet helemaal dezelfde kip"; merk-herkenning verzwakt.
- **Pixar-standaard:** één gerigde character-asset; identiteit is wiskundig vast, niet beschreven.
- **Java-oplossing:** `CharacterModel`-artifact per personage: een **multi-angle referentieset** (front/3-4/side/expressies) i.p.v. één anchor-PNG; vast doorgeven aan zowel still- als Veo-generatie. `ConsistencyState` per job die de gekozen anchors locked houdt.
- **VEO-oplossing:** waar Veo het ondersteunt: meerdere reference-images meegeven (niet alleen frame-1); altijd start+eind-frame voor hero-shots (al deels gebouwd — uitbreiden naar álle multi-second shots).
- **Prioriteit:** Critical · **Winst: +10%** (character consistency)

### P2 — Stills worden onafhankelijk gegenereerd → cross-scène micro-drift
- **Probleem:** elke scène-still is een verse Gemini-trekking; opeenvolgende scènes tonen lichte variatie.
- **Oorzaak:** geen gedeelde identiteits-conditionering buiten de losse anchor; geen "lock op de eerste geaccepteerde still van deze episode".
- **Gevolg:** flikkerende identiteit over de montage; de "AI-kip"-perceptie.
- **Pixar-standaard:** model-sheet is de waarheid voor élke frame.
- **Java-oplossing:** episode-`ConsistencyState` kiest bij scène 1 de definitieve anchors en **hergebruikt exact dezelfde anchorset** voor alle scènes; optioneel "seed-lock" per personage.
- **VEO-oplossing:** n.v.t. (image-laag), maar betere stills = betere Veo-startframes.
- **Prioriteit:** Critical · **Winst: +10%** (character consistency)

### P3 — Stem zonder acteren (per-personage voiceSettings + Line.emotion niet bedraad)
- **Probleem:** alle personages en alle regels klinken met dezelfde expressiviteit.
- **Oorzaak:** `ElevenLabsClient.synthesize(text, voiceId)` gebruikt globale `props.elevenlabs().stability()/similarityBoost()`; negeert `character.voiceSettings` (bible) én `Line.emotion`.
- **Gevolg:** vlakke voice-acting; emotionele scènes landen niet; personages zijn auditief niet te onderscheiden behalve in toonhoogte.
- **Pixar-standaard:** elke regel geregisseerd; intensiteit varieert per beat.
- **Java-oplossing:** `synthesize()` uitbreiden met per-personage settings (uit bible) + per-regel emotie→settings-mapping (bv. `excited`→stability−0.1/style+0.15; `sad`→stability+0.1/style−0.1). `Line.emotion` doorgeven tot in de TTS-call.
- **VEO-oplossing:** n.v.t.
- **Prioriteit:** High · **Winst: +10%** (audio)

### P4 — Cinematografie is fase-sjabloon, geen découpage (geen shot/reverse, geen eyelines, geen 180°)
- **Probleem:** herhalende camera per fase; dialoog in statische mediums; geen blikrichting-montage.
- **Oorzaak:** `cameraSpec(phase)` is een 1-op-1 lookup; geen variatie binnen fase, geen meersprekers-dekking.
- **Gevolg:** monotone beeldtaal = AI-handtekening; dialoog mist emotionele snit.
- **Pixar-standaard:** doordachte découpage; shot/reverse op blik; as-consistentie.
- **Java-oplossing:** `ShotPlan`-veld per scène (`shotSize`, `cameraMove`, `coverage`); bij ≥2 sprekers automatisch shot/reverse genereren met vaste 180°-as; varieer shotgrootte binnen een fase (establish→medium→close).
- **VEO-oplossing:** prompt per sub-shot expliciet de shotgrootte + blikrichting ("Pip kijkt camera-links naar Mo").
- **Prioriteit:** High · **Winst: +5%** (cinematografie/Pixar-uitstraling)

### P5 — Geen kleur-script en geen materiaal-specificatie
- **Probleem:** licht is golden-hour-default; "Pixar look" is een tekstwens; geen emotionele kleurboog.
- **Oorzaak:** `lightPhrase(timeOfDay)` mapt alleen tijd→licht; geen `colorScript`/`materials` in de bible.
- **Gevolg:** vlakke, inconsistente "render-uitstraling"; geen visuele emotie-ondersteuning.
- **Pixar-standaard:** kleur-script per sequentie; materiaal-definities (subsurface dons, snavel-spec).
- **Java-oplossing:** `colorScript:` per fase (palet, key-light, contrast, mood) + `materials:` per personage in `bible/channel.yml`; injecteren in image- én Veo-prompt naast de DNA-regel.
- **VEO-oplossing:** voeg materiaal+palet-zin toe aan `compileVeoPrompt` ("soft subsurface scattering in the down, matte beak, warm rim-light from camera-left").
- **Prioriteit:** High · **Winst: +5%** (visual quality)

### P6 — Continuïteit is een tekst-smeekbede, geen stateful model
- **Probleem:** props/accessoires/posities wisselen soms (kleur gieter, links/rechts).
- **Oorzaak:** anti-swap en prop-kleur staan in de prompt, maar er is geen toestand die per scène afdwingt "de gieter is groen, half vol, links".
- **Gevolg:** continuïteitsfouten die de illusie breken.
- **Pixar-standaard:** continuity-supervisor + scene-graph.
- **Java-oplossing:** `ContinuityState` (scene-graph) per episode: prop-register {naam, kleur, materiaal, positie, toestand}, persoon-posities; valideer elke nieuwe still ertegen.
- **VEO-oplossing:** geef de relevante prop-state-zin expliciet mee per shot.
- **Prioriteit:** High · **Winst: +5%** (consistency)

### P7 — QA meet animatie/consistentie niet hard (proxy + alleen vision)
- **Probleem:** zwakke beweging of subtiele drift haalt de publish-gate.
- **Oorzaak:** "Animation"-as = framing + "heeft motionDesc"-proxy (`heroMotionCoverage`); "Characters" = alleen `character_drift` vision-oordeel; geen deterministische check.
- **Gevolg:** inconsistente output passeert; QA geeft vals vertrouwen.
- **Pixar-standaard:** meervoudige objectieve checks + supervisor-review.
- **Java-oplossing:** deterministische `ConsistencyChecker` (kleur-histogram per personage-regio, accessoire-detectie via vision met *gestructureerde* checklist i.p.v. vrije score); voeg toe als aparte as + harde block bij accessoire-mismatch.
- **VEO-oplossing:** n.v.t. (meet-laag).
- **Prioriteit:** Medium · **Winst: +5%** (consistency)

### P8 — Muziek/score staat los van de emotie-curve
- **Probleem:** track ligt eronder; geen stingers op beats, geen leitmotief, geen score-naar-arc.
- **Oorzaak:** Suno krijgt mood-prompt; geen koppeling aan fase-emoties of climax-timing.
- **Gevolg:** emotie wordt niet muzikaal versterkt; minder "filmisch".
- **Pixar-standaard:** score als dramaturgisch instrument; thema's per personage.
- **Java-oplossing:** `scorePlan` afgeleid van de emotie-curve (per fase intensiteit); stinger-cues op hook/climax-beats; per-personage leitmotief-tag in de Suno-prompt; ducking koppelen aan emotie-piek.
- **VEO-oplossing:** n.v.t.
- **Prioriteit:** Medium · **Winst: +5%** (audio)

### P9 — Geen emotie-curve-model over de episode (losse woorden per beat)
- **Probleem:** emotie is één vrij woord per scène; geen boog/intensiteit over de hele aflevering.
- **Oorzaak:** `emotion` (+ soms `(5/5)`) per scène; geen episode-emotie-tijdlijn.
- **Gevolg:** vlakke dramaturgie; climax voelt niet als piek omdat er geen dal aan voorafging.
- **Pixar-standaard:** emotie-tijdlijn is het skelet van de film.
- **Java-oplossing:** `emotionCurve` op script-niveau (per fase: valentie+intensiteit); valideer monotone opbouw naar climax; voed kleur-script én score-plan.
- **VEO-oplossing:** intensiteit moduleert de anticipatie-telegraph-sterkte.
- **Prioriteit:** Medium · **Winst: +5%** (storytelling)

### P10 — Geen lip-sync / viseme-koppeling (tool-grens)
- **Probleem:** snavelbeweging is generiek, niet foneem-gestuurd.
- **Oorzaak:** Veo ondersteunt geen viseme-conditionering; TTS en beeld zijn ontkoppeld.
- **Gevolg:** geen articulaire lip-sync (voor preschool acceptabel, maar het blokkeert "Pixar-dialoog").
- **Pixar-standaard:** precieze viseme-animatie.
- **Java-oplossing:** bewust accepteren; mitigatie: houd spreek-shots kort, dek met cutaways naar de luisteraar (sluit aan op P4-coverage).
- **VEO-oplossing:** prompt "gentle beak movement synced to a talking rhythm", niet meer dan dat verwachten.
- **Prioriteit:** Low (accepteren) · **Winst: +1%**

### P11 — Geen cross-episode character-memory / serie-arc
- **Probleem:** elke aflevering staat los; `lifeStage` is handmatig; geen groei/herinnering.
- **Oorzaak:** geen serie-state-DB; bewuste MVP-keuze.
- **Gevolg:** geen meeslepende serie-binding (minder terugkeer-kijkers).
- **Pixar-standaard:** personages onthouden en groeien.
- **Java-oplossing:** `SeriesState`-tabel (vorige lessen, terugkerende props/locaties, relatie-ontwikkeling) als context in de scriptprompt.
- **VEO-oplossing:** n.v.t.
- **Prioriteit:** Low · **Winst: +5%** (retentie op kanaalniveau)

### P12 — Hook/titel/thumbnail niet gesloten-lus met retentie-analytics
- **Probleem:** hook-structuur is afgedwongen, maar niet geoptimaliseerd op echte kijkdata.
- **Oorzaak:** analytics-feedbackloop (project-doel 9-10) nog niet dicht.
- **Gevolg:** virale potentie blijft giswerk i.p.v. geleerd.
- **Pixar/YouTube-standaard:** A/B + retentie-curve stuurt de volgende hook.
- **Java-oplossing:** `RetentionFeedback` die YouTube-analytics terugkoppelt naar hook/titel/thumbnail-keuze (self-learning).
- **VEO-oplossing:** n.v.t.
- **Prioriteit:** Medium · **Winst: +10%** (viral) — maar pas zinvol ná publicatie-volume.

---

## 3. Agile Stories (kant-en-klaar)

### Story A — Stem laten acteren (per-personage + per-regel emotie) — ✅ GEBOUWD (2026-06-09)
> Geïmplementeerd: nieuw `VoiceSettings`-record (voice-service) met per-personage basis uit `bible.voiceSettings` + per-regel `emotion`-modulatie (`withEmotion`, geclamped). `ElevenLabsClient.synthesize` schrijft nu stability/similarity/**style**/**use_speaker_boost**. Orchestrator geeft de scène-emotie mee aan elke voice-regel. Numeriek geverifieerd (karakter blijft behouden onder emotie). Resteert: build + één goedkope render om te horen.
- **Business Value:** grootste audio-sprong voor de kleinste inspanning; personages worden auditief onderscheidbaar en scènes landen emotioneel.
- **Technische beschrijving:** geef `character.voiceSettings` (bible) en `Line.emotion` door tot in `ElevenLabsClient.synthesize()`. Map emotie→delta op stability/style.
- **Acceptatiecriteria:**
  - `synthesize()` accepteert per-call `stability/similarity/style/use_speaker_boost`.
  - Pip/Mo/Bo gebruiken hun bible-`voiceSettings`; verschillen meetbaar in de output.
  - `Line.emotion` (excited/sad/calm/scared…) verschuift de settings volgens een gedocumenteerde tabel.
  - Terugval op globale defaults als een veld ontbreekt (geen regressie).
- **Implementatievoorstel:** `VoiceSettings`-record per regel; `BibleLoader.voiceSettingsFor(speaker)`; emotie-tabel in `VoiceProperties`.
- **Complexiteit:** **S** · **Kwaliteitswinst: +10% audio** · **Risico indien niet:** vlakke voice-acting blijft het zwakste zintuig.

### Story B — Episode-ConsistencyState (anchor-lock + prop/positie-register)
- **Business Value:** stopt cross-scène identiteits- en prop-drift; kern van "merk-personage" i.p.v. "AI-kip".
- **Technische beschrijving:** stateful object per job dat de definitieve character-anchors en een prop/positie-register vasthoudt en aan elke still/Veo-call dwingend meegeeft; valideer nieuwe stills ertegen.
- **Acceptatiecriteria:**
  - Alle scènes van één job gebruiken dezelfde character-anchorset.
  - Prop-register {naam, kleur, materiaal, positie, toestand} blijft consistent over scènes.
  - Afwijking → automatische re-roll (haakt in op bestaande Auto-Fix).
- **Implementatievoorstel:** promoveer `PropAnchorService` naar `ConsistencyState`; sla op in de job; injecteer in `buildVeoScenes` + image-request.
- **Complexiteit:** **L** · **Kwaliteitswinst: +10% consistency** · **Risico:** drift blijft de hoofdoorzaak van niet-Pixar-uitstraling.

### Story C — ShotPlan & dialoog-dekking (shot/reverse + 180°)
- **Business Value:** breekt de monotone camera; geeft dialoog filmische snit.
- **Technische beschrijving:** voeg `shotSize`/`cameraMove`/`coverage` toe per scène; genereer bij ≥2 sprekers shot/reverse met vaste as; varieer shotgrootte binnen een fase.
- **Acceptatiecriteria:**
  - Geen twee opeenvolgende scènes met identieke camera-preset binnen dezelfde fase.
  - Dialoogscènes leveren ≥2 shots met consistente blikrichting.
  - 180°-as wordt niet overschreden (links blijft links).
- **Implementatievoorstel:** uitbreiden script-`ScriptScene` + `compileVeoPrompt`; eyeline-zin per sub-shot.
- **Complexiteit:** **L** · **Kwaliteitswinst: +5% cinematografie** · **Risico:** beeldtaal blijft "AI-preset".

### Story D — Color-script & materiaal-laag
- **Business Value:** tilt de hele visuele uitstraling en geeft emotie kleur-ondersteuning.
- **Technische beschrijving:** `colorScript:` per fase + `materials:` per personage in de bible; injecteren in image- en Veo-prompt.
- **Acceptatiecriteria:**
  - Elke fase heeft palet + key-light + contrast; zichtbaar verschil koel→warm richting climax.
  - Materiaal-zin (dons/snavel/ogen) staat in elke character-prompt.
  - Geen regressie in identiteit (materiaal vult DNA aan, vervangt niet).
- **Implementatievoorstel:** bible-uitbreiding + twee injectiepunten (`PromptComposer`, `compileVeoPrompt`).
- **Complexiteit:** **M** · **Kwaliteitswinst: +5% visual** · **Risico:** "render-uitstraling" blijft vlak/wisselend.

### Story E — EmotionCurve + ScorePlan (verhaal stuurt muziek)
- **Business Value:** maakt de aflevering filmisch; climax voelt als piek.
- **Technische beschrijving:** script-niveau `emotionCurve` (valentie+intensiteit per fase) → voedt color-script én een `scorePlan` (stingers op beats, leitmotief-tags, ducking op piek).
- **Acceptatiecriteria:**
  - Emotie-curve bouwt monotoon op naar climax (gevalideerd).
  - Suno-prompt bevat per-personage leitmotief-tag; stinger-cue op hook+climax.
  - Ducking-diepte volgt de emotie-intensiteit.
- **Implementatievoorstel:** veld op script-DTO; afgeleide objecten in orchestrator + assembly.
- **Complexiteit:** **M** · **Kwaliteitswinst: +5% storytelling/audio** · **Risico:** muziek blijft behang.

### Story F — Deterministische ConsistencyChecker in QA
- **Business Value:** sluit het gat waardoor drift de publish-gate haalt.
- **Technische beschrijving:** naast vision-oordeel een gestructureerde accessoire/kleur-check per personage-regio; harde block bij accessoire-mismatch.
- **Acceptatiecriteria:**
  - QA rapporteert per personage: kleur OK, accessoire aanwezig+correct, geen swap.
  - Accessoire-swap → publish geblokkeerd ongeacht totaalscore.
  - "Animation"-as krijgt een echte motion-component (niet alleen "heeft motionDesc").
- **Implementatievoorstel:** nieuwe scorer in `review/`; toevoegen aan `QaBoard.evaluate`.
- **Complexiteit:** **M** · **Kwaliteitswinst: +5% consistency** · **Risico:** QA geeft vals vertrouwen.

### Story G — Multi-angle CharacterModel (referentieset i.p.v. één anchor)
- **Business Value:** verhoogt het identiteits-plafond door de hele pijplijn.
- **Technische beschrijving:** genereer/cureer eenmalig per personage een referentieset (front/3-4/side/expressies); gebruik als conditionering voor stills en als extra Veo-references waar ondersteund.
- **Acceptatiecriteria:**
  - Per personage ≥4 goedgekeurde angles in `bible/refs/`.
  - Image-service conditioneert op de set, niet op één frame.
  - Meetbare daling in `character_drift` over een testbatch.
- **Implementatievoorstel:** uitbreiden `GeminiImageProvider` anchor-laden naar een set; eenmalige bouw-tool.
- **Complexiteit:** **L** · **Kwaliteitswinst: +10% consistency** · **Risico:** tekst-only identiteit houdt het plafond laag.

### Story H — SeriesState (cross-episode memory)
- **Business Value:** serie-binding en terugkeer-kijkers.
- **Technische beschrijving:** persisteer vorige lessen/props/relaties; voed als context in de scriptprompt.
- **Acceptatiecriteria:** nieuwe afleveringen refereren correct aan eerdere; geen tegenspraak met serie-canon.
- **Complexiteit:** **M** · **Kwaliteitswinst: +5% retentie** · **Risico:** kanaal blijft "losse clips".

### Story I — RetentionFeedback (self-learning hook/thumbnail)
- **Business Value:** sluit de virale leerlus.
- **Technische beschrijving:** YouTube-analytics → hook/titel/thumbnail-keuze van de volgende afleveringen.
- **Acceptatiecriteria:** retentie-curve + CTR per video opgeslagen; meetbare invloed op volgende briefs.
- **Complexiteit:** **L** · **Kwaliteitswinst: +10% viral (na volume)** · **Risico:** virale groei blijft giswerk.

---

## 4. Eindrapport — Top 20 verbeteringen

Gesorteerd op gecombineerde impact (videokwaliteit × consistency × Pixar-uitstraling) gedeeld door implementatiekosten.

| # | Verbetering | Story/Probleem | Prioriteit | Kosten | Winst |
|---|---|---|---|---|---|
| 1 | Stem laten acteren (per-personage + per-regel emotie naar ElevenLabs) | A / P3 | Critical | S | +10% |
| 2 | Episode-ConsistencyState (anchor-lock + prop/positie-register) | B / P1,P2,P6 | Critical | L | +10% |
| 3 | Multi-angle CharacterModel (referentieset) | G / P1 | Critical | L | +10% |
| 4 | Color-script + materiaal-laag in bible → image+Veo | D / P5 | High | M | +5% |
| 5 | ShotPlan + dialoog-dekking (shot/reverse, 180°) | C / P4 | High | L | +5% |
| 6 | EmotionCurve op script-niveau | E / P9 | Medium | S | +5% |
| 7 | ScorePlan (stingers/leitmotief/emotie-ducking) | E / P8 | Medium | M | +5% |
| 8 | Deterministische ConsistencyChecker in QA | F / P7 | Medium | M | +5% |
| 9 | Echte motion-component in QA "Animation"-as | F / P7 | Medium | S | +3% |
| 10 | Start+eind-frame voor álle multi-second shots (niet alleen hero) | P1 | High | S | +3% |
| 11 | Shotgrootte-variatie binnen elke fase | C / P4 | High | S | +3% |
| 12 | Per-regel emotie moduleert anticipatie-telegraph-sterkte | P9 | Low | XS | +2% |
| 13 | Materiaal/rim-light-zin in `compileVeoPrompt` | D / P5 | High | XS | +3% |
| 14 | Prop-state-zin expliciet per shot uit ConsistencyState | B / P6 | High | S | +3% |
| 15 | Cutaway-dekking naar luisteraar bij spreek-shots (lip-sync-mitigatie) | P10 / P4 | Low | S | +2% |
| 16 | SeriesState (cross-episode memory) | H / P11 | Low | M | +5% (retentie) |
| 17 | RetentionFeedback self-learning loop | I / P12 | Medium | L | +10% (viral, na volume) |
| 18 | Beat-synced cuts op de muziekmaat | P8 (backlog) | Low | M | +3% |
| 19 | Seed-lock per personage in image-generatie | B / P2 | Medium | S | +3% |
| 20 | Eval-harness uitbreiden met image-consistentie-as | F / P7 | Low | M | +2% |

---

## 5. Huidige geschatte scores

Eerlijke inschatting op basis van de geverifieerde code. De basis is sterk; de gaten zijn diep maar weinige.

| As | Score | Korte motivatie |
|---|---|---|
| **Character Consistency** | **68/100** | Volledige DNA-lock, anti-swap, scale-lock, anchors, Auto-Fix — maar tekst-gebaseerde lock + onafhankelijke stills + alleen vision-QC laten residuele drift toe. |
| **Storytelling** | **72/100** | Expliciete arcs, fase-structuur met beat-eisen, story-critic + structure-validator. Mist emotie-curve-model en serie-geheugen. |
| **Animation** | **58/100** | Ease/anticipatie/tics/ground-physics/end-pose zijn sterk geprompt — maar geen lip-sync, en QA meet motion alleen via proxy. |
| **Cinematography** | **64/100** | Camera-Bible per fase met lens/DoF is goed; verliest punten op sjabloon-herhaling, geen shot/reverse, geen eyeline-beheer. |
| **Audio** | **55/100** | Branded TTS-stemmen, Suno, ambient, ducking aanwezig — maar vlakke delivery (globale settings) en score los van het verhaal. |
| **Visual Quality** | **60/100** | Consistente golden-hour + "Pixar look"-tekst; mist kleur-script en materiaal-specificatie. |
| **Pixar Readiness (totaal)** | **62/100** | Sterke, doordachte basis; ~28 punten zitten in 6-7 gerichte systemen. |

*Viral YouTube-gereedheid (apart): ~**65/100** — hook-structuur + thumbnail/titel-discipline zijn er; de self-learning retentielus ontbreekt nog.*

---

## 6. Stappenplan naar 90+/100 Pixar Readiness

Volgorde = maximale winst per euro/uur, gratis (niet-Veo) eerst zodat je goedkoop kunt testen.

**Sprint 1 — "Acteren & meten" (goedkoop, geen Veo-kosten) → richt ~62 → ~72**
1. **Story A** — stem laten acteren (S). Direct +10% audio; test met de eval-harness + één goedkope render.
2. **Story F** — deterministische ConsistencyChecker + echte motion-as in QA (M). Je kunt drift nu *meten* vóór je betaalt.
3. **Story E (emotie-curve-deel)** — `emotionCurve` op script-niveau (S). Stuurt later kleur + score; nu al toetsbaar in tekst.

**Sprint 2 — "Identiteit vastnagelen" (de kern) → ~72 → ~82**
4. **Story B** — episode-ConsistencyState (L): één anchorset + prop/positie-register voor de hele job.
5. **Story G** — multi-angle CharacterModel (L): referentieset i.p.v. één anchor; meet de drift-daling in QA (uit sprint 1).
6. **Top-20 #10 + #19** — start+eind-frame voor alle multi-second shots + seed-lock (S).

**Sprint 3 — "Filmische uitstraling" → ~82 → ~90**
7. **Story D** — color-script + materiaal-laag (M): koel→warm-boog + dons/snavel-spec.
8. **Story C** — ShotPlan + dialoog-dekking (L): shot/reverse, 180°, shotgrootte-variatie.
9. **Story E (score-deel)** — ScorePlan: stingers, leitmotief, emotie-ducking (M).

**Sprint 4 — "Serie & viraal" (kanaalniveau, na publicatie-volume) → 90 → 92+ en groei**
10. **Story H** — SeriesState (cross-episode memory).
11. **Story I** — RetentionFeedback self-learning loop.

**Meet-discipline (doorlopend):** draai na elke prompt/wijziging de eval-harness (`infra/eval/run-eval.py`) voor de tekst-assen, en één goedkope Ken-Burns-render + de nieuwe ConsistencyChecker vóór elke betaalde Veo-render. Zo koop je elke +% objectief af i.p.v. op één render te hopen.

> **Eerlijke grens:** met Veo als generator is articulaire lip-sync (P10) niet haalbaar; 92-95 "Disney-dialoog" vergt een andere animatie-tool of een rig-gebaseerde laag. Voor preschool-content op YouTube is dat geen blokker — 90+ Pixar-*uitstraling* is met bovenstaand plan realistisch.
