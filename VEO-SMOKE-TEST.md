# VEO smoke-test checklist

Doel: bevestigen dat Vertex VEO werkt (auth, GCS, modeltoegang, cliplengte, kosten)
met **één goedkope clip** vóór je een hele video door VEO laat lopen.

Volgorde: **0) build & up → 1) directe 1-clip test → 2) volledige hybrid run**.
Stop bij de eerste die faalt; de fout-tabel onderaan zegt wat het betekent.

---

## 0. Pre-flight (gratis)

- [ ] **Build slaagt** met alles van vandaag: `build.bat` (Java 21). Dit is de enige
      open code-onbekende — als hier iets struikelt, plak de Maven-fout.
- [ ] **Services draaien**: `docker-compose up -d` en `docker-compose ps` toont
      `video-generation-service` als `Up`.
- [ ] **Env staat** (in `.env`): `GCP_PROJECT_ID`, `GCP_REGION`, `GCS_OUTPUT_BUCKET`,
      `GCS_OUTPUT_PREFIX`, `GOOGLE_APPLICATION_CREDENTIALS`. Service-account ligt op
      `secrets/gcp-sa.json` (gemount als `/secrets/gcp-sa.json`).
- [ ] **Gates aan** voor een veilige eerste run:
      `REVIEW_IMAGES=true`, `REVIEW_BEFORE_VEO=true`.
- [ ] **Kostencap** staat in `bible/channel.yml` → `videoGen.veo.costCapEurPerVideo: 7.00`.

Snelle GCS-rechtencheck (optioneel, los van de app), met de service-account:
```bash
# vanaf een host met gcloud + de sa-key
gcloud auth activate-service-account --key-file=secrets/gcp-sa.json
echo test > /tmp/veo-test.txt
gsutil cp /tmp/veo-test.txt gs://<GCS_OUTPUT_BUCKET>/<GCS_OUTPUT_PREFIX>/veo-test.txt   # moet slagen
gsutil rm gs://<GCS_OUTPUT_BUCKET>/<GCS_OUTPUT_PREFIX>/veo-test.txt
```
Slaagt dit niet → de SA mist `storage.objectAdmin` op de bucket (zie fout-tabel).

---

## 1. Directe 1-clip smoke test (goedkoopst — ~1 VEO-clip)

Test alleen de video-generation-service, zonder script/voice/montage. Gebruik een
bestaand plaatje dat de container kan lezen (bijv. `/bible/refs/pip.png`).

`POST http://localhost:8087/api/v1/clips/generate`  (pas host/poort aan als de
poort niet gepubliceerd is; anders: `docker exec -it <videogen> curl ...` intern
naar `http://localhost:8087`).

```bash
curl -s -X POST http://localhost:8087/api/v1/clips/generate \
  -H "Content-Type: application/json" \
  -d '{
    "jobId": "00000000-0000-0000-0000-000000000001",
    "format": "landscape",
    "scenes": [{
      "seq": 1,
      "sceneType": "hero",
      "startImagePath": "/bible/refs/pip.png",
      "visualDesc": "Pip the cream-white chick blinks and tilts her head, gentle slow camera push-in, soft golden-hour light, fireflies drifting. Keep identity, colours and the straw hat perfectly stable.",
      "durationSeconds": 6,
      "negativePrompt": "morphing, flicker, extra wings, duplicate chicken, text"
    }]
  }' | tee /tmp/veo-clip.json
```

Wat je wilt zien:
- [ ] HTTP 200 met een clip die `"status":"OK"` en een `clipPath` (een mp4 op `/workdir/...`) teruggeeft.
- [ ] In de **video-generation-service logs**: een GCS-upload van de still, een
      Vertex VEO-call, en een gedownloade clip. Geen `403`/`PERMISSION_DENIED`.
- [ ] De clip is ~`durationSeconds` lang (`ffprobe` de `clipPath`).

Variaties om door te testen zodra de basis werkt:
- [ ] `durationSeconds`: probeer **4 / 6 / 8**. Werkt 5 of 7 niet → VEO accepteert
      alleen discrete lengtes; dan in de router naar de dichtstbijzijnde snappen.
- [ ] **`endImagePath`** meesturen (een 2e still) om de `lastFrame()`-keten te
      checken. ⚠️ Let op: in `SceneRequest` staat 'chaining currently off' —
      verifieer of de end-frame echt wordt meegegeven of dat dit nog aangezet moet.

---

## 2. Volledige hybrid run (na een geslaagde 1-clip test)

Aanrader: draai éérst dezelfde job in **ken_burns** en keur de stills goed
(`REVIEW_IMAGES=true`), zodat VEO straks al-goedgekeurde beelden animeert.

`POST http://localhost:8080/api/v1/videos`
```bash
curl -s -X POST http://localhost:8080/api/v1/videos \
  -H "Content-Type: application/json" \
  -d '{
    "topic": "Pip finds a shiny pebble",
    "brief": "Pip discovers a glittering pebble by the pond; Mo explains why it sparkles; Bo turns PEBBLE into a silly rhyme. They move from the coop to the pond to the big oak.",
    "lesson": "Looking closely turns a small thing into a big wonder.",
    "mood": "warm, cozy, gentle wonder",
    "targetSeconds": 60,
    "motionMode": "veo",
    "privacyStatus": "private"
  }'
```

Met `REVIEW_BEFORE_VEO=true` **pauzeert** de job vlak vóór de VEO-spend
(status `VEO_REVIEW_PENDING`). Check dan in de logs / dashboard:
- [ ] Alleen **hook + climax**-scènes gaan naar VEO (hero `veo3_1`), de rest blijft Ken Burns.
- [ ] De **cost calculator** blijft onder `costCapEurPerVideo` (€7) — anders valt 'ie
      automatisch terug naar lite/Ken Burns. Log toont de geschatte kosten.
Pas goedkeuren (`POST /api/v1/videos/{id}/approve`) als dit klopt → VEO draait,
clips worden door dezelfde montage gestitcht.

---

## 3. Wat te checken in de logs (per service)

- **video-generation-service**: GCS-upload van de start-still, Vertex VEO-job-id,
  poll tot klaar, clip gedownload. Foutwoorden om op te letten: `PERMISSION_DENIED`,
  `NOT_FOUND` (model), `INVALID_ARGUMENT` (duur/format), `RESOURCE_EXHAUSTED` (quota).
- **orchestrator**: `runVeoStage`, scene-type routing, cost-cap, fallback naar Ken Burns.

---

## 4. Fout → betekenis

| Symptoom in de log | Oorzaak | Actie |
|---|---|---|
| `403 PERMISSION_DENIED` op Vertex | SA mist Vertex-rechten OF project niet allowlisted voor VEO | `roles/aiplatform.user` geven; VEO-toegang aanvragen/inschakelen in het project |
| `NOT_FOUND` op het model (`veo3_1`) | model niet beschikbaar in `GCP_REGION` of geen toegang | andere region proberen / toegang regelen |
| GCS `403` / `AccessDenied` | SA mag niet in de bucket schrijven | `roles/storage.objectAdmin` op de bucket aan de SA |
| `INVALID_ARGUMENT` over duur | VEO wil 4/6/8s | duur naar dichtstbijzijnde toegestane snappen in de router |
| clip identiteit wiebelt / morpht | start-still zwak of prompt te statisch | betere/al-goedgekeurde still + sterkere motion/negative-prompt |
| kosten schieten omhoog | te veel hero-scènes | hybrid bevestigen (alleen hook+climax), cap verlagen |

---

## TL;DR
1. Build + up + env/gates staan.
2. Eén `clips/generate`-call met een bestaande still → bevestigt auth + GCS + model + duur + kosten.
3. Werkt dat → goedgekeurde Ken Burns-job in `motionMode=veo` met `REVIEW_BEFORE_VEO`,
   en keur de VEO-spend pas goed als routing + cap kloppen.
