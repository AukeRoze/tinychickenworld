# Draaiboek — sprekende kippen + eerste VEO-run

Volg dit van boven naar beneden. Stop bij de eerste stap die faalt en stuur de
fout/het frame; dan helpen we verder. Detail van fase D staat in
`VEO-SMOKE-TEST.md`.

---

## A. Sprekende kippen aanzetten (ElevenLabs)

1. **API-key**: elevenlabs.io → profiel → **API Keys** → kopieer.
2. **3 stemmen kiezen** in de Voice Library, kopieer per stem de **Voice ID**:
   - **Pip** — hoog, jong, enthousiast (bijv. Brittney / Charlotte / Lily).
   - **Mo** — kalm, warm, laag (bijv. Adam / Patrick).
   - **Bo** — gevarieerd, theatraal, speels (bijv. Domi / Sammy / Mason).
3. Zet in **`.env`**:
   ```
   VOICE_MODE=elevenlabs
   ELEVENLABS_API_KEY=sk_...
   VOICE_ID_PIP=<id>
   VOICE_ID_MO=<id>
   VOICE_ID_BO=<id>
   ```
   (Per-personage `voiceSettings` staan al in de bible. Kosten ≈ €0,05-0,15/video.)

## B. Lelijke placeholder-geluiden weg

- Kip-SFX gaan vanzelf uit in `elevenlabs`-mode.
- Verwijder de placeholder-muziek + ambient voor een schone test:
  ```
  del /Q bible\music\*.mp3 bible\sfx\ambient\*.mp3
  ```
  (Later echte royalty-free tracks in `bible\music\` — zelfde bestandsnamen werken.)

## C. Build + goedkope validatie-run (GEEN VEO-geld)

1. `build.bat`  → slaagt? (anders: plak de Maven-fout)
   - Dit draait ook de nieuwe migraties: script `V7`+`V8`, orchestrator `V12`-`V18`.
2. `docker-compose up -d`  → `docker-compose ps` toont alle services `Up`.
3. Maak een **goedkope Ken Burns-job** (dashboard "+ New Job", of de TEST-knop).
   Laat `motionMode` op **ken_burns**.
4. **Beoordeel** (detailpagina → de fase-stappen zijn klikbaar):
   - **Audio**: práten Pip/Mo/Bo met eigen, passende stemmen?
   - **Beeld**: Mo's gebreide rode sjaal, geen hoed-swap, **geen konijn/eieren**,
     cast-aanwezigheid vloeiend (niet 1→3→1), gecentreerde framing, geen tekst-in-beeld.
   - **Script-fase aanklikken**: staat er voor hook/climax een goede **motionDesc** (start→eind beweging)?
   - **Thumbnail**: grote koppen, CTR-compositie (3 varianten).
   - **AI-Critic / Polish**: mikken op **~80-90**. Onder 90? → knop **"Auto-Fix → 90"**.
5. Iets niet goed? → per scène **💾 Save+Regen** (beeld) / **🔊 Re-voice** (dialoog),
   of een Voice ID wisselen. Pas door als de goedkope master schoon + ~80+ is.

## C½. GCS-bucket klaarzetten (eenmalig — VEO heeft een schrijfplek nodig)

VEO schrijft elke ruwe clip eerst naar een **Google Cloud Storage-bucket**; de
service downloadt 'm daarna lokaal naar `workdir/jobs/<jobId>/scenes/<seq>/clip.mp4`.
Zonder bucket + rechten faalt elke VEO-call. Doe dit vóór fase D.

1. **Bucket aanmaken** (eenmalig, in je VEO-project, dezelfde regio als `GCP_REGION`):
   ```bash
   gcloud auth activate-service-account --key-file=secrets/gcp-sa.json
   gsutil mb -p <GCP_PROJECT_ID> -l us-central1 gs://<jouw-bucket-naam>
   ```
2. **Service-account rechten geven** op die bucket (schrijven + lezen):
   ```bash
   gsutil iam ch serviceAccount:<sa-email>:roles/storage.objectAdmin \
     gs://<jouw-bucket-naam>
   ```
   (`<sa-email>` staat in `secrets/gcp-sa.json` onder `client_email`.)
3. **Env zetten** in `.env`:
   ```
   GCS_OUTPUT_BUCKET=<jouw-bucket-naam>
   GCS_OUTPUT_PREFIX=veo-outputs
   ```
4. **Rechten testen** (moet slagen + weer kunnen verwijderen):
   ```bash
   echo test > /tmp/veo-test.txt
   gsutil cp /tmp/veo-test.txt gs://<jouw-bucket-naam>/veo-outputs/veo-test.txt
   gsutil rm gs://<jouw-bucket-naam>/veo-outputs/veo-test.txt
   ```
   Faalt stap 4 met `AccessDenied` → service-account mist `storage.objectAdmin` (stap 2).

> Let op: VEO-output blijft ook in de bucket staan (kost opslag). Optioneel later een
> lifecycle-rule die `veo-outputs/` na X dagen opruimt — de clips staan dan al lokaal.

## D. VEO 1-clip smoke test (eerste echte VEO-uitgave, ~1 clip)

Bevestigt Vertex-toegang, GCS-schrijfrechten, de `lastFrame()`-SDK-regel en de
toegestane cliplengtes — vóór je een hele video door VEO laat lopen.

```bash
curl -s -X POST http://localhost:8087/api/v1/clips/generate \
  -H "Content-Type: application/json" \
  -d '{ "jobId":"00000000-0000-0000-0000-000000000001", "format":"landscape",
        "scenes":[{ "seq":1, "sceneType":"hero",
          "startImagePath":"/bible/refs/pip.png",
          "visualDesc":"Pip blinks and tilts her head, slow gentle camera push-in, golden-hour light, petals drifting. Keep identity, colours and the straw hat stable.",
          "durationSeconds":6,
          "negativePrompt":"morphing, flicker, extra wings, duplicate chicken, text" }] }'
```
Check: HTTP 200 + `status:OK` + een `clipPath`. In de videogen-logs: GCS-upload +
Vertex-job, geen `403/PERMISSION_DENIED`. `ffprobe` de clip → ~6s.
Daarna varianten: duur **4 / 6 / 8** testen; eventueel `endImagePath` voor de lastFrame-keten.

**Fout → betekenis** (kort; vol in `VEO-SMOKE-TEST.md`):
- `403 PERMISSION_DENIED` Vertex → SA-rechten / VEO-allowlist in het project.
- model `NOT_FOUND` → VEO niet in `GCP_REGION` / geen toegang.
- GCS `AccessDenied` → SA mist `storage.objectAdmin` op de bucket.
- `INVALID_ARGUMENT` op duur → VEO wil 4/6/8s → snap de duur in de router.

## E. Eerste volledige hybrid VEO-video

Pas als C (~80+, schone frames) én D (smoke OK) groen zijn.

1. Zet `REVIEW_BEFORE_VEO=true` (pauzeert vóór de VEO-spend).
2. Nieuwe job met `"motionMode":"veo"` (of een goedgekeurde job opnieuw met VEO).
3. Bij de VEO-review-gate: alleen **hook + climax** naar VEO (hero), rest Ken Burns;
   kosten onder de **€7-cap**. Klopt het → goedkeuren → VEO draait.
4. Zwakke enkele clip? → **🎬 Reroll VEO** op die scène (1 clip = 1 kost), niet de hele stage.

---

## Volgorde in één zin
Stemmen aan + placeholders weg → **build** → goedkope run (~80-90, schone frames) →
**1-clip VEO-smoke** → **hybrid video** (hook+climax), kosten bewaakt.

> Stuur bij elke stap gerust het frame / de score / de log — dan kijken we samen mee.
