# Flow-clips importeren in de pipeline

Je hebt de scenes in Google Flow / Omni gemaakt en gebruikt ze als de bewegende
beelden — **met de eigen audio van de clip** (Omni doet stem + snavel-sync zelf).
ElevenLabs is volledig verwijderd. Wat de import doet:

- **`POST /api/v1/videos/{id}/import-clips?episode=1`** (orchestrator) — kopieert
  per scène `bible/afleveringen/1/scene-<seq>.mp4` (óf de beschrijvende variant
  `scene-<seq>-<titel>.mp4`, met de scene-goal als slug — de import matcht op het
  `scene-<seq>-` voorvoegsel) naar het clip-slot van de job
  (`/workdir/jobs/<id>/scenes/<seq>/clip.mp4`) en zet de `clipPath`. De montage
  gebruikt jouw clip rechtstreeks en **behoudt de eigen audio van de clip**
  (Omni-stem + ambient); de muziek wordt daar in post overheen gemixt. Er is geen
  losse stem-track meer.
- **`import-flow-clips.bat <JOB-ID> [episode]`** — roept import-clips én reassemble
  in één keer aan.

## Vereisten

1. De pipeline draait (`docker compose up`).
2. Er is een **job voor aflevering 1** met script + scene-images. De clip-import
   vult het *beeld + de audio* in (de clip draagt z'n eigen stem); er is geen
   aparte voice-stap meer. Heb je nog geen job, maak er dan eerst één via het
   dashboard ("New Job", serie *Pip's First Times*, episode 1) en laat 'm door de
   script-/image-stap lopen.
3. De Flow-exports staan in `bible\afleveringen\1\` als `scene-1.mp4` … `scene-29.mp4`.

## Stappen

1. **Bouw de orchestrator opnieuw** (voor het endpoint én de knop) en herstart 'm:

   ```
   docker compose up -d --build orchestrator
   ```

2. Zet je Flow-exports in `bible\afleveringen\1\` als `scene-1.mp4` … `scene-29.mp4`,
   óf met de beschrijvende naam `scene-1-<titel>.mp4` (de titel = de scene-goal-slug
   die het dashboard per scène toont — die kop is meteen de aanbevolen bestandsnaam).

3. **Importeren** — kies één van de twee:

   **A. Via de knop (aanbevolen).** Open het dashboard (`http://localhost:8080`),
   klik op de job van aflevering 1, en druk bij de acties op
   **📥 Flow-clips importeren**. Hij vraagt welke aflevering (standaard 1),
   kopieert je clips, en hermonteert meteen. Ontbreekt er een clip, dan meldt 'ie
   welke scène-nummers nog ontbreken.

   **B. Via het script.** Dubbelklik `import-flow-clips.bat` (vraagt om de
   Job-ID), of draai vanaf de command line:

   ```
   import-flow-clips.bat <JOB-ID> 1
   ```

   De Job-ID staat in de URL van de job-pagina op het dashboard.

4. Volg de voortgang op het dashboard. De master gebruikt nu je Flow-beelden met
   de channel-stemmen, muziek en intro/outro.

## Veo uit + clips-only (ingesteld 2026-06-16)

Twee schakelaars staan nu zo dat alleen je geüploade clips worden gebruikt:

- **`VEO_ENABLED=false`** — de pipeline roept Veo nooit meer aan. De Veo-stap
  slaat door naar de montage en de "alle clips"-knop hermonteert alleen met wat
  er al staat (geen kosten). Terugzetten? `VEO_ENABLED=true` in `.env`/compose.
- **`CLIPS_ONLY=true`** — de montage **weigert** te draaien zolang niet elke
  scène een geüploade clip heeft; er wordt geen Ken Burns-still ingevuld. Mist er
  een clip, dan krijg je een duidelijke foutmelding met de scène-nummers. Upload
  die clips en draai opnieuw.

Beide staan in `docker-compose.yml` onder de `orchestrator`-service.

## Clips laten nummeren door Cowork (flow-in)

Wil je de clips niet zelf hernoemen? Zet je gedownloade Flow-clips in
`bible\afleveringen\<aflevering>\flow-in\` (voor aflevering 1 staat die map al klaar
met uitleg in `flow-in\README.md`). Lever ze sorteerbaar aan (benoem ze `01`, `02`,
`03 …`, of geef de volgorde door). Cowork hernoemt ze dan in de sessie naar
`scene-1.mp4 … scene-N.mp4` één map omhoog, checkt of het aantal klopt met het
script, en trapt daarna de import af. Dezelfde werkwijze geldt voor elke aflevering
(maak `bible\afleveringen\<nummer>\flow-in\` aan).

## Goed om te weten

- **Geluid:** de stem + ambient komen **uit de Omni-clip zelf**; de montage
  behoudt die audio en mixt alleen de muziek eroverheen. ElevenLabs/voice-service
  bestaat niet meer. Wil je andere gesproken woorden? Maak de Flow-clip opnieuw.
- **Lengte:** clips renderen op vaste 10s. Is een clip toch korter, dan speelt de
  montage 'm vooruit + achteruit ("boomerang") zodat het beeld niet bevriest; de
  audio wordt met stilte tot de scèneduur aangevuld.
- **Beeldverhouding:** clips worden naar 1920×1080 (of 1080×1920 voor Shorts)
  geschaald/bijgesneden. Render in Flow in hetzelfde formaat.
- **Ontbrekende scènes:** het import-antwoord toont `missingSeqs` — scènes
  waarvoor geen `scene-<seq>.mp4` is gevonden. Met `CLIPS_ONLY=true` blokkeert
  zo'n ontbrekende clip de montage (duidelijke foutmelding met de nummers) in
  plaats van stilletjes een Ken Burns-still te tonen.
