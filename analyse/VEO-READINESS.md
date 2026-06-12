# Veo-readiness — alles voorbereiden vóór je geld uitgeeft

Doel: de kwaliteit van een Veo-video zo dicht mogelijk bij "perfect first try" krijgen,
zodat je niet duur zit te proberen. Veo (~€3-5/video) is een **kwaliteits-vermenigvuldiger
op een al goede still**, geen manier om problemen te ontdekken. Elke euro bespaar je door
iets eerst gratis te valideren.

## Hoe het nu technisch werkt (vastgesteld in de code)

- **Image-to-video.** Per scène stuurt de orchestrator de goedgekeurde scène-PNG
  (`startImagePath`) + `visualDesc` + `negativePrompt` + aspect naar Veo. De still is
  ~frame 1; Veo animeert vanaf daar. → De stillkwaliteit ís de clipkwaliteit.
- **Cliplengte.** Uit de routing: `hero` (hook/climax) tot 8s, `standard`/`intro`/`outro`
  tot 6s. De router vraagt `min(scèneduur, route.maxSeconds)` aan → een 4s-scène geeft een
  4s-clip, **geen over-generatie/trim-verspilling**. `hero` gebruikt z'n eigen `maxSeconds=8`
  (NIET geklemd door `maxClipSeconds`; dat geldt alleen voor fallback/cost-cap). Veo-eigen
  audio staat `audio=false` (we mixen zelf).
- **Hybrid routing.** Alleen `hook`+`climax` krijgen Veo (`hero`-model); de rest blijft
  Ken Burns. Scène 1 = `intro`, laatste = `outro`.
- **Stitching = identiek aan Ken Burns.** Veo-clip → `buildFromClip` (her-encode naar
  1920×1080 / blurred-fill, audio vervangen door narratie, **trim naar `durationSeconds`**)
  → `Concatenator` fase-xfades. Geen aparte montagecode.
- **Kostenremmen bestaan al.** `costCapEurPerVideo` (€5), per-scène fallback naar Ken Burns,
  model-routing fast vs hero, preemptieve cost-cap, corrupt-output-check.

## Het grote inzicht over kosten

De router vraagt al `min(scèneduur, maxSeconds)` aan, dus je betaalt nooit voor footage die
je wegtrimt. Huidige duren zijn al goed afgestemd: climax-scènes ~5-7s (mooie Veo-lengte),
hook-scènes ~4s (snappy). De resterende lever is puur **kwaliteit**: Veo komt het best tot
z'n recht in een wat langere, zich ontwikkelende shot — wil je dat, maak hook/climax-scènes
in de `episodeStructure` bewust langer (let op: dat beïnvloedt ook de pacing van het gratis
pad). LET OP: Veo ondersteunt mogelijk alleen **discrete** cliplengtes (bijv. 4/6/8s) — check
dat in de smoke test en snap de aangevraagde duur eventueel naar de dichtstbijzijnde toegestane.

---

## Voorbereidingsplan (gratis/goedkoop eerst, Veo als laatste)

### A. Stills = het zaad (grotendeels klaar)
- [x] Cast-consistentie (Gemini reference-conditioning), correcte accessoires, safe-margin
      framing, blurred-fill. Veo kan een slechte still niet redden — het versterkt 'm.
- [ ] **Keur elke scène-image goed op de gratis run** (per-scène review-gate) vóór je Veo
      laat lopen. Veo draaien op een niet-goedgekeurde still = weggegooid geld.

### B. Scèneduur afstemmen op Veo
- [x] **Router stemt al af** — vraagt `min(scèneduur, maxSeconds)`, dus geen trim-verspilling;
      hero kan z'n volle 8s. Geen `maxClipSeconds`-wijziging nodig.
- [ ] (Optioneel, kwaliteit) hook/climax-scènes bewust langer maken in `episodeStructure`
      zodat Veo meer ruimte heeft voor beweging — afwegen tegen de pacing van het gratis pad.
- [ ] Smoke test: controleer of Veo de aangevraagde duur accepteert (mogelijk alleen 4/6/8s);
      zo nee, snap de duur naar de dichtstbijzijnde toegestane waarde in de router.

### C. Bewegings-prompt (grootste kwaliteits-hefboom voor Veo)
- [ ] `visualDesc` is nu geschreven voor een **statisch** beeld. Voor Veo een
      **motion-prompt** maken: camerabeweging + wat het personage doet + ambient-beweging
      (bijv. "Pip kantelt haar kop en pikt; camera duwt langzaam in; vlinders dwarrelen").
      Optie: een apart `motionDesc`-veld uit het script voor hero-scènes, of een transform.
- [ ] Sterke `negativePrompt` voor Veo: geen morphing, geen extra ledematen/vleugels, geen
      flicker, identiteit/accessoires stabiel, geen tekst.

### D. Continuïteit tussen clips ("plakken")
- [x] Consistente cast + fase-xfades verbergen de meeste naden.
- [ ] Lighting/tijd-van-dag consistent houden binnen een reeks (bible-lighting + shared seed).
- [ ] (Geavanceerd, later) echte continuïteit: de **laatste frame** van clip N als
      `startImagePath` van clip N+1, of match-cut-start-images. Verhoogt naadloosheid maar
      kost wat per-scène stillcontrole.

### E. Kostenremmen verifiëren vóór de eerste euro
- [ ] `costCapEurPerVideo` op een veilige waarde (bijv. €3-5).
- [ ] Hybrid aan: alleen hook+climax naar Veo, rest Ken Burns.
- [ ] `audio=false` bevestigen (we vervangen de clip-audio toch).
- [ ] **Smoke test: één scène door Veo** (1-scène-job) om auth/GCS/format/kosten te checken
      vóór een hele video. Daarna één volledige hybrid-video. Pas opschalen als de look klopt.

### F. Audio eerst (zie ook de audiolaag-gap)
- [ ] Echte stemmen (`VOICE_MODE=elevenlabs`) + muziek vóór/met Veo. Veo-beweging zonder
      audio voelt nog onaf, en toekomstige lip-sync heeft audio nodig. Goedkoper, grotere ROI.

### G. De gated workflow (bespaart het meest)
1. Genereer de **goedkope Ken Burns-master**.
2. **Per-scène image-review** → goede goedkeuren, alleen zwakke her-genereren (goedkoop).
3. **AI Critic + Polish** op de goedkope master → bijschaven tot ~80+.
4. **Pas dán** hybrid Veo aanzetten op de goedgekeurde job → Veo animeert al-goedgekeurde stills.
5. Her-auditen; is één Veo-clip zwak, **alleen die scène** opnieuw rollen (niet de hele video).

---

## Samengevat
- Scènelengte: zet Veo-scènes op de native cliplengte (hero 8s; `maxClipSeconds`=8) en schrijf
  ze vol; houd Ken Burns-scènes snel.
- Plakken: al opgelost — Veo-clips lopen door dezelfde `buildFromClip` + fase-xfade-montage.
- Perfect-first-try: stills goedkeuren → motion-prompt + duur afstemmen → kostenremmen + smoke
  test → Veo pas op een al ~80+ scorende, goedgekeurde master, en re-roll per scène.
