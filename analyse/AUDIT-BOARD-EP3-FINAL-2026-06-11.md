# Elite Review Board — "🐣 Pip Found an Egg!" (kbU7V0EAgKY, master v2)

**Board:** Story (Docter-school), Story Art, Character Design, Character Performance, DoP, Layout, Animatie ×2, Lighting, Render, Voice, Sound, Compositie, Retentie, Thumbnail, Viral, AI-Architecten.
**Basis:** frame-analyse van de identieke Veo-clips (master v1), volledige script-data (29 scènes), assembly-logs van v1 én v2, captions/metadata van de live pagina, en — uniek voor dit board — de volledige pipeline-broncode. Het board weet dus niet alleen wát er mis is, maar wélke regel code het veroorzaakt.
**Context:** tussen v1 en v2 is een groot deel van de eerdere bevindingen al geïmplementeerd (transities, grade, titelkaart, lossless audio, per-regel captions, ref-anchoring, clip-QC). Dit rapport beoordeelt v2 en richt zich op wat **overblijft**.

---

## TEAM 1 — VERHAAL & EMOTIE

### Story Director — **8,5/10**
**Werkt:** complete arc (vondst→zorg→geduld→dal→payoff), rule-of-three met telbare cracks, de stilte-beat, "Mama?"/"Big sister." als echt Pixar-moment, thema verdiend gerecapt, seriële haak.
**Werkt niet:** (1) geen frictie tussen de vrienden — alles verloopt in harmonie; (2) de "waar is de eendenmoeder"-vraag wordt genegeerd (ouders denken hem wél); (3) na "Big sister." wordt te snel doorgesneden — het beste moment krijgt geen lucht.
**Waarom een probleem:** harmonie zonder frictie vlakt herbekijkwaarde af; onbeantwoorde logica knaagt bij de mee-kijkende ouder; een onderbroken piek-emotie halveert zijn impact.
**Disney-fix:** één micro-meningsverschil in akte 2; één regel die de mama-vraag parkeert ("We'll help it find its family — tomorrow!"); 1,5s extra stilte op de piek.
**Software-feature:** micro-conflict-promptregel ✅ (vandaag gebouwd, geldt vanaf ep 4) · ComedyValidator-check op aanwezigheid ervan (open) · "peak-hold": scène-duur +1,5s automatisch op de hoogste emotie-intensiteit (`emotion (5/5)`) — triviaal in de orchestrator.

### Story Artist — **7,5/10**
**Werkt:** shot-variatie per fase (de rotatie-regel), eyeline/180°-staging in two-shots, dag→nacht-boog die het geduld-thema visueel vertelt.
**Werkt niet:** de cracks (het plotmechaniek!) zijn klein in beeld — een kind moet ze kunnen TELLEN; er is geen insert-shot-taal (extreme close-up van alleen het ei bij elke crack).
**Disney-fix:** elke crack krijgt een eigen insert: ECU ei, crack centraal, 1,5s, identiek gekadreerd — herhaling als visueel refrein.
**Software-feature:** "insert-shot" als scènetype in het script-schema (visualDesc-template: ECU, object centraal, geen characters) — de cast-cap staat solo-shots al toe; één prompt-regel + voorbeelden.

## TEAM 2 — KARAKTERS

### Character Designer — **7/10** (Disney 6 · Bluey 7 · Cocomelon 8)
**Werkt:** silhouet-onderscheid via accessoires (hoed/sjaal/bril) is functioneel; het eendje is merchandise-goud (kleinste, geelste, bill).
**Werkt niet:** de drie kippen verschillen in VORM te weinig (zelfde bouw, alleen kleur+accessoire); op 10% zoom zijn Pip en Bo verwisselbaar zonder accessoire. Merchandise-test: een knuffel zonder hoed is geen herkenbare Pip.
**Disney-fix:** vorm-taal per character (Pip ronder, Mo hoekiger/zwaarder onderlijf, Bo smaller+verticaal tuft-silhouet) — silhouet herkenbaar in zwartvlak.
**Software-feature:** dit is een bible/ref-beslissing, geen code: nieuwe ref-sets met aangescherpte `silhouette`-DNA. De ref-anchoring van vandaag maakt zo'n redesign nu wél veilig uitrolbaar (één ref-set wisselen = hele pipeline volgt). Plan als "season 2 design pass".

### Character Performance Director — **7/10**
**Werkt:** tic-systeem (compiler injecteert signatuur-beweging), anticipation-telegraph op hero-beats, emotie-intensiteit (n/5) stuurt de uithaal.
**Werkt niet:** tussen de beats acteren characters "idle" (knipperen/ademen maar geen gedachten); reactieshots ontbreken — wie luistert, doet niets.
**Disney-fix:** "thinking poses" — de luisteraar reageert vóór hij spreekt.
**Software-feature:** prompt-clausule "the listening character VISIBLY reacts (head tilt, lean-in, eyes following) before any reply" in de compiler — 15 minuten werk, geldt elke scène met 2 cast.

## TEAM 3 — CINEMATOGRAFIE

### Director of Photography — **7,5/10**
**Werkt:** Camera-Bible per fase (hoek/lens/beweging/DoF — zeldzaam in AI-pipelines), golden-hour-signatuur, climax-framing met licht uit het ei.
**Werkt niet:** vrijwel alles is ooghoogte/lichte low-angle; geen extreme keuzes op piekmomenten (top-shot op de nest-cirkel, worm's eye bij het uitkomen).
**Pixar-vergelijk:** Pixar wisselt per sequence bewust lens-taal; hier is de taal consistent maar smal.
**Software-feature:** Camera-Bible uitbreiden met 2 "exotische" presets die ALLEEN op climax/stilte-beat mogen — bible-edit, geen code.

### Layout Artist — **8/10**
**Werkt:** 180°-regel afgedwongen in prompt, stabiele links/rechts-posities, locatie-logica (moestuin→coop→porch→Big Oak) klopt ruimtelijk.
**Werkt niet:** establishing-shots ontbreken bij locatiewissels — het kind moet zelf concluderen "we zijn nu bij de boom".
**Software-feature:** auto-establishing: bij locatiewissel krijgt de eerste scène een "wide establishing" hint in de compiler (locatie-wissel is al detecteerbaar in de scènelijst). Klein.

## TEAM 4 — ANIMATIE

### Supervising Animator — **7/10**
**Werkt:** ease-clausules (anticipation/settle), ground-physics op contact-beats, geen lipsync-val (bewuste keuze: snavel dicht).
**Werkt niet:** per-clip kwaliteit wisselt (Veo-loterij); secondary motion (dons dat nabeweegt, hoedrand die veert) is er soms wel, soms niet; de bevroren staarten bij stem-overloop (NU GEFIXT in code, zichtbaar vanaf de volgende generatie-run — v2 heeft ze nog, want zelfde clips).
**Software-feature:** frozen-tail-guard ✅ vandaag · clip-QC re-roll ✅ · resterend: secondary-motion-clausule expliciet maken ("downy feathers and hat brim show subtle follow-through") — prompt-regel.

### Animation Director — **7,5/10**
**Werkt niet:** scène 8 (stro/coop) en de drukke trio-shots blijven de zwakste clips (drift, kloon-risico) — de bekende Veo-zwakte bij drukke texturen + 3 cast.
**Software-feature:** ✅ structureel gedekt sinds vandaag: cast-cap (max 2 normaal), headcount-locks, ref-anchoring, clip-QC. De stro-scène verdient een handmatige 🎬 re-roll onder het nieuwe regime — dat is meteen de A/B-test van de hele enforcement-laag.

## TEAM 5 — LIGHTING & RENDERING

### Lighting Director — **7,5/10**
**Werkt:** colorScript per fase (Pixar-techniek!), licht-boog ochtend→schemer→vuurvliegjes, het climax-licht uit het ei als verhaalelement.
**Werkt niet:** binnen een fase is licht vlak — geen bewuste key/fill-verhouding, gezichten missen soms een catch-light-accent op emotiepieken.
**Software-feature:** colorScript uitbreiden met `lighting:` per fase ("soft key from the egg's glow, faces半 lit") — bible-edit; compiler leest het al patroon-gewijs mee te nemen (één regel code).

### Render Supervisor — **8/10** (was 6,5 vóór de cascade-fix)
**Werkt (v2!):** crf-16-eerste-encode, lossless audio-keten, één loudnorm, één delivery-encode — de generatieverlies-cascade is gedicht; dit is nu een professionele signaalketen.
**Resterend:** Veo-bron is de bodem (1080p, AI-textuur); 4K-upscale pas zinnig bij bewezen kanaal.
**Software-feature:** ✅ gedaan; lange termijn NVENC/4K-upscale (backlog, GPU).

## TEAM 6 — AUDIO

### Voice Director — **7/10**
**Werkt:** per-character voices, emotie-modulatie per regel, SFX-woorden niet uitgesproken maar als geluid.
**Werkt niet (v2 hoort dit nog):** per-regel intonatie-reset (de context-velden zijn vandaag gebouwd maar gelden pas bij nieuwe voice-runs); "Bonk" te zacht (idem gefixt).
**Software-feature:** ✅ previous/next_text + SFX-punch · open: pronunciation-dictionary (KWAK/tok-tok — account-actie, backlog) en per-episode voice-seed-consistentie checken.

### Sound Designer — **7,5/10**
**Werkt:** ambient per locatie, whoosh per cut (v2!), onomatopee-systeem, ducking.
**Werkt niet:** de wereld is stil buiten dialoog+ambient: geen voetstapjes, geen stro-geritsel, geen ei-tik-foley los van de stem.
**Disney-fix:** foley-laag per contact-beat.
**Software-feature:** de CONTACT_VERBS-detectie bestáát al (ground-physics) — koppel er een foley-mix aan: zelfde trigger, clip uit `sfx/foley/<verb>.mp3` op scène-audio. Mooi afgebakende feature (~halve dag).

### Composer — **8/10**
**Werkt (v2):** mood-gekozen track, climax-swell op het juiste moment, sting, audio-uitadem.
**Werkt niet:** één track per aflevering = geen thematische ontwikkeling; het dal (scène 19-20) verdient verstilling (muziek bijna weg) — nu dimt hij alleen.
**Software-feature:** "silence on the silent beat": de stilte-scène krijgt music-volume ×0,3 (de scène is al gemarkeerd — lines==[]); één filterregel in de music-mix. · Lange termijn: stems (audit #14).

## TEAM 7 — YOUTUBE & RETENTIE

### Audience Retention Specialist — **7,5/10**
**Verwachte curve:** sterke start (ei op sec 7 ✓), risicodip 0:45-1:10 (nest bouwen = laag-conflict middenstuk), her-grip op tok-tok-participatie (1:10-1:25), stilte-beat is double-edged (patroonbreker óf wegklik bij 3-jarigen), climax houdt vast, resolutie netjes. Schatting AVD: 65-75% bij doelgroep — goed.
**Risico:** het middenstuk mist één verrassing; Bo's Bonk is er, maar zit vroeg.
**Software-feature:** dit is exact backlog Loop 1 (retentie→priors) — eerst publiceren, daarna meet het systeem dit zelf.

### Thumbnail Director — **7/10** (niet extern bekeken — private; oordeel op systeem)
**Werkt:** cast-stills als basis (consistentie!), layout-rotatie, squint-scorer die variant 2 met 78 koos (de scores 38/42 voor de rest tonen dat de generator wisselvallig is — precies waarom de scorer bestaat).
**Werkt niet:** 2 van 3 varianten scoren <45 — de generator verspilt 66% van de kosten aan kansloze varianten.
**Software-feature:** voer de squint-criteria als NEGATIEVE constraints in de thumbnail-prompt (gezicht ≥40% hoogte, één object-raadsel, geen drukte) zodat álle varianten boven 60 starten. Klein promptwerk.

### Viral Content Strategist — **6,5/10**
**Deelbaar:** "Mama?"/"Big sister." is het deelmoment (ouder→ouder, "kijk hoe lief"); tok-tok is het meedoe-moment (kind tikt terug = ouder filmt kind = UGC-potentieel!).
**Niet deelbaar:** er is geen 15s-fragment dat zonder context werkt behalve de hatch; de Short fixt dit (✅ + captions + hook-tekst sinds vandaag).
**Waarom deelt iemand NIET:** privé-status (!), en de titel v2 verloor zijn beste woord: "**Wobbly**" — "Pip Found an Egg!" is vlakker dan "Pip Found a Wobbly Egg!".
**Software-feature → SYSTEEMFOUT GEVONDEN:** de re-assembly regenereerde de metadata en degradeerde een eerder goedgekeurde titel. **Approved metadata moet vastliggen**: eenmaal door de gate = title/description bevroren; regen alleen op expliciet verzoek. (Top-prioriteit hieronder.)

## BONUS — AI SOFTWARE ARCHITECTEN (systeemproblemen)

| Probleem | Oorzaak (code-niveau) | Oplossing | Impact | Moeite | Prio |
|---|---|---|---|---|---|
| Goedgekeurde titel verslechterde bij re-assembly | runAssemblyStage roept metadata.generate altijd opnieuw | Metadata-lock: skip regen als metadataTitle al bestaat (tenzij ?regen=true) | 8 | 2 | **KRITIEK** |
| Captions wéér auto-ASR op v2 | OAuth-token mist force-ssl (re-consent open) | Backlog #24 uitvoeren (5 min handwerk) | 7 | 1 | **KRITIEK** |
| Stilte-beat = gok bij 3-jarigen | geen leeftijds-A/B-data | Loop 1 (retentie) na publicatie | 6 | 5 | HOOG |
| Veo-clip-loterij resterend (~10%) | model-variantie | ✅ ref-anchoring+clip-QC vandaag; meet fail-rate per 10 clips als KPI | 8 | gedaan | HOOG |
| Foley-gat | geen foley-laag | CONTACT_VERBS→foley-mix (zie Sound) | 6 | 4 | HOOG |
| Thumbnail-varianten wisselvallig | prompt zonder squint-constraints | constraints in thumbnail-prompt | 6 | 2 | HOOG |
| Muziek niet verstild op stilte-beat | vlakke music-mix | volume-dip op lines==[] scène | 5 | 2 | GEMIDDELD |
| Eén lens-taal | Camera-Bible smal | 2 climax-presets toevoegen | 5 | 1 | GEMIDDELD |
| Geen establishing bij locatiewissel | compiler kent wissel niet | auto-establishing-hint | 5 | 2 | GEMIDDELD |
| Vorm-silhouetten te gelijk | bible-design | season-2 design pass via ref-sets | 7 | 7 | GEMIDDELD (gepland) |

---

## TOP 25 (gerangschikt op impact; ✅ = vandaag al geïmplementeerd, telt als afgevinkt bewijs dat het systeem leert)

1. **Metadata-lock na approval** (KRITIEK — verse regressie, zie Viral)
2. **OAuth re-consent** → captions structureel (KRITIEK, 5 min)
3. Publiceren — zonder kijkers geen lerende studio (KRITIEK, beslissing)
4. ✅ Ref-anchoring generatie+QC 5. ✅ Clip-QC+re-roll 6. ✅ Encode/audio-keten 7. ✅ Per-regel captions 8. ✅ Frozen-tail-guard 9. ✅ Transities/grade/titelkaart 10. ✅ Short (captions+hook) 11. ✅ Squint-scorer 12. ✅ Micro-conflict+ouder-knipoog 13. ✅ Prompt front-load
14. Peak-hold (+1,5s op 5/5-emotie) 15. Crack-inserts als shot-taal 16. Listening-reactions-clausule 17. Foley-laag op contact-verbs 18. Stilte-beat muziekdip 19. Thumbnail-prompt-constraints 20. Climax-camera-presets 21. Auto-establishing 22. Pronunciation-dictionary 23. Loop 1 retentie-priors 24. Loop 4 benchmark-matrix 25. Season-2 silhouet-redesign

## ROADMAP

**Deze week:** #1 metadata-lock, #2 OAuth, #3 publiceer ep 1-3 (na ep 1 v2-productie), stro-scène re-roll als enforcement-A/B.
**Deze maand:** 14-19 (allemaal klein), ep 4 onder vol nieuw regime, Loop 1 aanzetten zodra data binnenkomt.
**Drie maanden:** 20-24, eerste song-aflevering, benchmark-gedreven routing.
**Lange termijn R&D:** silhouet-redesign met ref-migratie, stems-muziek, 4K/NVENC, multi-kanaal-bewijs.

---

## "Als Pixar dit in 2026 zou produceren — de 10 belangrijkste veranderingen"

1. **De stilte zou langer durven duren** — vier volle seconden niets, en dán de crack.
2. **Elke crack een eigen insert-shot**, identiek gekadreerd: herhaling als ritueel.
3. **De luisteraar acteert altijd** — Mo's gezicht vertelt Pip's regel mee.
4. **Eén frictiebeat** tussen Bo's ongeduld en Mo's kalmte — geduld is pas een les als ongeduld bestaat.
5. **"Big sister." krijgt vijf seconden** en de muziek valt er volledig stil.
6. **Foley draagt de wereld** — stro ritselt, het ei tikt los van de stem, pootjes op hout.
7. **Het licht acteert mee** — het ei-licht groeit per crack; de wereld dimt eromheen.
8. **De mama-vraag wordt gehonoreerd** in één regel — respect voor de slimste kijker in de kamer.
9. **Silhouet-test op alles** — elke kip herkenbaar als zwart vlak, hoed of geen hoed.
10. **De titel was nooit verzwakt** — bij Pixar is "Wobbly" heilig verklaard in de eerste pitch; niets dat is goedgekeurd wordt ooit stilletjes herschreven door een machine.

**Eindoordeel board:** v2 is productioneel een ander niveau dan 24 uur geleden — de signaalketen is professioneel, de bewaking is systemisch. Wat overblijft is regie-verfijning (punten 14-21, samen dagen werk) en twee kritieke procesgaten die vanavond zichtbaar werden: de metadata-regressie en de captions. Fix die twee, produceer ep 1 v2, en publiceer. Het systeem is er klaar voor; de wereld nog niet, want hij staat op private.
