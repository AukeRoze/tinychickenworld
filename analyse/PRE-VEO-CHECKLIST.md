# Pre-Veo kwaliteitschecklist — maximale kwaliteit zonder Veo-kosten

Doel: alles uit de goedkope run (Ken Burns + Replicate) halen vóór je naar het dure
Veo (~€3-5/video) overstapt. Alles hieronder is **gratis** of **eenmalig** (asset die
je één keer maakt en daarna in elke video gratis terugkomt).

Kosten-legenda: 🟢 gratis · 🟡 eenmalige asset (daarna gratis) · 🔵 klein bedrag

---

## A. Config-flips (🟢 gratis, doe dit eerst)

- [x] `IMAGE_PROVIDER=replicate` — getrainde cast-LoRA i.p.v. prompt-only OpenAI. Grootste character-consistentie-winst, en goedkoper per beeld (~$0.003-0.01 vs $0.04-0.08).
- [ ] **Ondertitels aan** — `burnSubtitles=true` (staat nu default uit). Gratis, +retention, +toegankelijkheid, +Polish score. Kan per request of als default.
- [ ] **Ken Burns bevestigen** — `VIDEO_MOTION_MODE=ken_burns` en `MOTION_ENABLED=true`, zodat scènes echte camerabeweging hebben en je géén Veo-kosten maakt.
- [ ] **Voice-mode kiezen** — nu `sounds` (gratis, geen ElevenLabs-kosten, taalonafhankelijk). Voor meer "echte" beleving kun je per video `elevenlabs` testen (🔵 ~€0.05-0.15). Begin gratis.
- [ ] **Narrator aan** (`NARRATOR_ENABLED=true`, `NARRATOR_TTS=espeak`) — gratis lokale TTS voor hook/closer-zinnen. Al default; controleer dat het aan staat.

## B. Eenmalige assets (🟡 — grootste gratis kwaliteitssprong)

- [ ] **`bible/fx/ambient.mov` of `.webm`** — loopende particle-overlay (vlinders / vuurvliegjes / bloemblaadjes / bokeh, transparante achtergrond). **Dit is de grootste gratis vervanger voor Veo:** geeft élke still leven zonder per-video kosten. (Code staat klaar, dormant tot je 'm neerzet.)
- [ ] **`bible/intro.mp4` + `bible/outro.mp4`** — gebrande 3s bumpers. Instant herkenning; de outro vervangt automatisch de tekst-eindkaart.
- [ ] **`bible/sting.mp3`** — sonisch logo onder de openingstitel (~1.5s chime). Al gewired.
- [ ] **`bible/sfx/transitions/whoosh.mp3`** — whoosh op scène-overgangen. Verbergt de cut, geeft ritme. (Dormant tot asset.)
- [ ] **`bible/fx/bell.png`** — schuddend belletje naast de SUBSCRIBE-knop. (Dormant tot asset.)
- [ ] **`bible/music/*.mp3`** — 3-5 goede royalty-free tracks; orchestrator kiest er één op mood. Controleer dat ze er staan (Polish score checkt dit).
- [ ] **`bible/sfx/ambient/*.mp3`** — ambient geluid per locatie (coop, pond, garden…). Geeft "echt erbij zijn"-gevoel. (Polish score checkt dit.)
- [ ] **`bible/sfx/<char>/<emotion>-N.mp3`** — character-SFX voor sounds-mode aanvullen (meer variatie = minder herhaling).
- [x] **`bible/logo.png`** — watermerk; al aanwezig.

## C. Content & prompt-kwaliteit (🟢 gratis, al grotendeels gedaan)

- [x] Hook = extreme close-up met sterke emotie (scène 1).
- [x] Character-locks: kleuren + accessoires + babyproporties hard in de prompt.
- [x] Random-idea genereert nu ook een sturende **brief** (hoogste prioriteit voor het script).
- [x] Story-arcs + variation-profiles dwingen variatie af (anti-AI-farm).
- [x] Emotie- en camera-rotatie per scène in de scriptprompt.
- [ ] **Goede briefs schrijven** — hoe concreter de brief (scène-voor-scène beats), hoe beter het script. Gebruik de random-idea als startpunt en scherp 'm aan.

## D. Assembly & thumbnail (🟢 gratis, al gedaan deze sessie)

- [x] Fase-gestuurde overgangen + kortere duur (geen uniforme fade).
- [x] Motion-continuïteit over de cut (geen "bounce").
- [x] Cover-fit — geen zwarte balken meer.
- [x] Warme color grade, geanimeerde titel- + SUBSCRIBE-eindkaart, logo-overlay.
- [x] Bijna-lossless tussen-encodes + finale loudnorm (minder kwaliteitsverlies, juiste loudness).
- [x] Thumbnail: korte punchy tekst + cast groot op de voorgrond, schone achtergrond.
- [ ] **Optioneel:** thumbnail-tekst in HOOFDLETTERS + gekleurde balk achter de titel (CoComelon/Blippi-stijl). Zeg het en ik zet het erin.

## E. Goedkope iteratie-workflow (🟢/🔵 — haal er max uit per run)

- [ ] **Per-scène review-gate aan houden** (`REVIEW_IMAGES=true`) — bekijk de beelden vóór montage; keur goede goed, her-genereer alleen de zwakke (gratis t/m goedkoop per beeld i.p.v. een dure Veo-run weggooien).
- [ ] **AI Critic draaien** op de goedkope master en alleen de gemarkeerde zwakke scènes opnieuw rollen via "regenerate scene" — veel goedkoper dan Veo.
- [ ] **Pas Veo inzetten** als de goedkope versie consistent ~80+ Polish/AI-score haalt, en dan alleen voor **hook + climax** (hybrid-routing) i.p.v. de hele video.

---

### Samengevat: de 3 grootste gratis winsten nu
1. **`bible/fx/ambient.mov`** neerzetten → levende scènes zonder Veo.
2. **intro/outro/sting** neerzetten → instant merk-gevoel.
3. **Ondertitels aan** + per-scène review → goedkoop bijsturen i.p.v. dure re-runs.

Pas als dit alles staat en de scores goed zijn, is Veo (hook+climax) het extra geld waard.
