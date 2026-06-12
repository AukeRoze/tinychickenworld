# YouTube Auto Channel — Architectuur

Documenteert de feitelijke staat van de codebase + de geplande uitbreiding met Veo image-to-video. Volg-document voor `README.md` (dat de operationele kant beschrijft).

Lokale infra (Docker Compose) + cloud AI-API's (Claude, OpenAI, ElevenLabs, optioneel Replicate Flux, geplande Veo). Phase 2 routekaart voor eventueel fully-lokale AI.

---

## 1. System Overview

Vaste-cast kinderkanaal ("Pip & Friends", drie kippen in watercolor) waarbij de orchestrator één video per `POST /api/v1/videos { topic }` levert. Zeven Spring Boot microservices op één host, direct REST tussen elkaar, twee Postgres-databases. Spring `@Async` worker per job, geen message queue. Externe AI-calls voor LLM/image/voice/upload. State machine met 6 statussen.

Sterke punten die al in code zitten en behouden moeten blijven bij elke uitbreiding:
- **Bible-driven**: één `bible/channel.yml` bepaalt cast, stijl, locaties, voices, intro/outro, music. Alle services lezen 'm.
- **Anti-dup verdediging**: VariationProfile-rotatie + SHA-256 content hash + 64-bit SimHash (Hamming ≤ 12 = duplicaat) tegen "AI farm" pattern-detection.
- **Visuele variatie**: 6 camera-motion presets (ZOOM_IN/OUT, PAN_LEFT/RIGHT, STATIC, ZOOM_PAN_DIAGONAL) + 4 thumbnail-layouts roteren onafhankelijk.
- **Character consistency knop**: `IMAGE_PROVIDER=openai` (prompt-only ~75%) vs `replicate` (Flux+LoRA ~95%). LoRA-trainingspipeline klaar in `infra/lora-training/`.
- **Shorts first-class**: `format: vertical` → 1080×1920 canvas, alle filters horizontaal/verticaal-bewust, auto-cap 60s.

Wat er ontbreekt en in deze fase wordt toegevoegd: **echte beweging in scènes**. Nu = AI stills met Ken Burns. Veo image-to-video voegt levende beweging toe als optionele upgrade per video of per scène.

---

## 2. Architecture Diagram

```
                POST /api/v1/videos { topic, format, targetSeconds }
                                  │
                                  ▼
   ┌─────────────────────────────────────────────────────────┐
   │  orchestrator :8080            DB: orchestrator         │
   │  PipelineOrchestrator (@Async)  state machine           │
   │  + MetadataGenerator (Claude tool_use → title/desc/tags)│
   └─┬───┬───┬───┬────────────┬────────┬────────┬────────────┘
     │   │   │   │            │        │        │
     ▼   │   │   │            │        │        │
  ┌──────────┐ │   │            │        │        │
  │ script   │ │   │            │        │        │  DB: scripts
  │ :8081    │ │   │            │        │        │  (scripts, simhash idx,
  │ Claude   │ │   │            │        │        │   variation_profile)
  └──────────┘ │   │            │        │        │
     ┌─────────┘   │            │        │        │
     ▼             ▼            ▼        ▼        ▼
  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐
  │ voice    │ │ image    │ │ video-gen│ │ assembly │ │ thumbnail│
  │ :8083    │ │ :8084    │ │ :8087    │ │ :8082    │ │ :8086    │
  │ Eleven   │ │ OpenAI   │ │ Veo MCP  │ │ FFmpeg   │ │ OpenAI   │
  │ Labs     │ │ /Replic. │ │ NEW      │ │ +motion  │ │ +AWT     │
  └──────────┘ └──────────┘ └──────────┘ └──────────┘ └──────────┘
                                  │
                                  ▼
                            ┌──────────┐
                            │ upload   │
                            │ :8085    │
                            │ YouTube  │
                            │ Data API │
                            └──────────┘

  Shared:  bible/channel.yml  (read-only mount in elke service)
           workdir volume     (per-job assets, alle services bind dit)
           secrets/yt-creds/  (OAuth StoredCredential)
```

---

## 3. Services Breakdown (huidige + nieuw)

| Service | Port | DB | Externe API | Status |
|---|---|---|---|---|
| `orchestrator` | 8080 | `orchestrator` | Anthropic (metadata) | ✅ |
| `script-service` | 8081 | `scripts` | Anthropic (tool_use) | ✅ |
| `video-assembly-service` | 8082 | — | — (FFmpeg) | ✅ |
| `voice-service` | 8083 | — | ElevenLabs | ✅ |
| `image-service` | 8084 | — | OpenAI of Replicate | ✅ |
| `youtube-upload-service` | 8085 | — | YouTube Data API v3 | ✅ |
| `thumbnail-service` | 8086 | — | OpenAI + lokale AWT | ✅ |
| **`video-generation-service`** | **8087** | — | **Veo MCP (Google)** | 🆕 |

> **Poorten (cleanup 2026-06-12):** de poorten in deze tabel zijn *interne*
> Docker-netwerkpoorten. Op de host zijn alleen **8080** (orchestrator) en
> **8089** (eenmalige YouTube OAuth-callback, redirect-URI
> `http://127.0.0.1:8089/Callback`) gepubliceerd. Services bereiken elkaar via
> servicenamen (`http://script-service:8081` enz.). Tijdelijk alles op de host
> nodig (debugging, `infra/eval/run-eval.py`, `tools/make-intro.sh`)?
> `docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d`

`video-generation-service` is de enige nieuwe service. Zit tussen `image-service` en `video-assembly-service`. Input: lijst scènes met `startImagePath` (asset uit image-service) + duur + prompt. Output: lijst MP4 clips per scène. Skipt zichzelf als `motionMode=ken_burns` (huidige cheap path blijft default).

---

## 4. Data Model (status quo)

Twee Postgres databases (geen wijziging nodig voor Veo — clips gaan via workdir-volume, niet via DB):

**`orchestrator` DB**
```
video_jobs(id, topic, audience, target_seconds, format,
           status, script_job_id, script_id,
           youtube_video_id, youtube_url, error,
           created_at, updated_at)
-- status: PENDING|SCRIPT_GENERATING|ASSETS_GENERATING|
--         ASSEMBLING|UPLOADING|COMPLETED|FAILED
```

**`scripts` DB**
```
script_jobs(id, topic, audience, target_seconds, status, script_id, error)

scripts(id, topic, full_text jsonb, content_hash CHAR(64) UNIQUE,
        simhash BIGINT, variation_profile jsonb, regen_attempts,
        created_at)

-- Indexes:
--   UNIQUE (content_hash)          -- exact-dup short-circuit
--   bit_count(simhash # ?)         -- Hamming distance scan for near-dup
```

Assets leven uitsluitend op het gedeelde `workdir` volume, niet in de DB. Per-job structuur:
```
workdir/jobs/<jobId>/
  scenes/<seq>/
    image.png                # van image-service
    clip.mp4                 # NIEUW — van video-generation-service (als motionMode=veo)
    voice.mp3                # van voice-service
  final.mp4                  # van video-assembly-service
  thumbnail.png              # van thumbnail-service
```

---

## 5. Pipeline Flow (met Veo geïntegreerd)

Orchestrator state machine, ongewijzigd qua statussen:

1. `PENDING` → submit job → **script-service** roept Claude met `tool_use`, valideert tegen bible (alleen cast-ids, alleen location-ids). VariationProfile geforceerd. SimHash check. Retry tot 2× bij near-dup. → `SCRIPT_GENERATING` → script klaar.
2. → `ASSETS_GENERATING` (parallel via `CompletableFuture.allOf`):
   - **voice-service**: per scène per line → ElevenLabs met character `voiceId` uit bible → concat lines tot één scene-MP3 (FFmpeg).
   - **image-service**: per scène prompt = `visualStyle.description` + alle characters-in-scene descriptions + location description + scene `visualDesc`. Provider via `IMAGE_PROVIDER`.
3. → **NIEUW: video-generation-service** (alleen als `motionMode=veo`):
   - Per scène: roept Veo MCP `generate_video` met `start_image=<workdir>/scenes/<seq>/image.png`, model uit bible-config (default `veo3_1_lite`, hero → `veo3_1` high), duur = `min(sceneDur, 8)`.
   - Output naar `workdir/jobs/<jobId>/scenes/<seq>/clip.mp4`.
   - Veo-calls parallel begrensd op `VEO_MAX_PARALLEL` (default 3) i.v.m. quota + kosten.
   - Fout-pad: fallback naar Ken Burns voor die ene scène (assembly krijgt dan `image.png` i.p.v. `clip.mp4`).
4. → `ASSEMBLING` — **video-assembly-service**:
   - Als `clip.mp4` aanwezig: gebruik die direct (geen Ken Burns).
   - Anders: maak scene clip uit `image.png` met `MotionSelector` (6 presets, adjacent scenes ≠ zelfde).
   - Concat scènes + audio mix + sidechain ducking (music ducks tijdens voice) + optionele intro/outro + optionele subs + H.264 re-encode.
5. **Metadata** (parallel met assembly): Claude `tool_use` → title/description/tags. Voor Shorts: `#Shorts` toegevoegd.
6. → **thumbnail-service**: layout-rotatie uit 4 templates → OpenAI base image met layout-hint → AWT title overlay (DejaVu Sans Bold, headless).
7. → `UPLOADING` — **youtube-upload-service**: YouTube Data API v3 resumable upload, "Made for Kids" flag op kanaalniveau, schedule = `privacyStatus` uit request.
8. → `COMPLETED` met `youtube_url` populated, of `FAILED` met `error`.

Het Veo-pad is een **opt-in upgrade** per video (request param `motionMode`) of per kanaal-default (in `channel.yml`). Default blijft Ken Burns omdat dat 0 extra externe kosten heeft.

---

## 6. MVP-uitbreiding (deze week)

Eén nieuwe service erbij, één bible-uitbreiding, één env-flag. Niets aan bestaande services slopen.

1. **Nieuwe service** `services/video-generation-service` (zie `services/video-generation-service/DESIGN.md` voor details):
   - Spring Boot 3, port 8087, `POST /api/v1/clips/generate`.
   - WebClient naar Veo MCP HTTP endpoint, polling op job status.
   - Bind `workdir` volume.
   - Dockerfile + pom.xml in dezelfde stijl als de andere services.
2. **Compose-uitbreiding**: voeg service toe, env `VEO_MCP_URL`, `VEO_API_KEY`, `VEO_MAX_PARALLEL=3`. Bind `bible:/bible:ro` voor model-routing config.
3. **Orchestrator**: nieuwe `VideoGenerationServiceClient`, optionele stap in `PipelineOrchestrator` na images, vóór assembly. Skipped als `motionMode != veo`.
4. **Assembly**: detecteer `clip.mp4` per scène, gebruik die i.p.v. Ken Burns. Geen breaking change — als `clip.mp4` ontbreekt blijft oude pad werken.
5. **Bible**: `videoGen` sectie met defaultModel, sceneTypeRouting, costCap per video.
6. **.env.example**: `VEO_*` keys + comment.
7. **README**: stuk over `motionMode=veo` opt-in + cost-impact tabel bijwerken.

Acceptance: één testvideo met `motionMode=veo` end-to-end, gemeten Veo-kosten en wallclock per scène, vergelijken met Ken Burns baseline.

---

## 6b. Consistency-enforcement laag (toegevoegd na ep-audit "Pip Found a Wobbly Egg")

Vier afdwingbare maatregelen tegen de geziene AI-video fouten (haperende overgangen, character-drift, verdwijnende characters, te veel characters):

1. **Prompt-locks** — `VeoPromptCompiler.headcountLockClause()` (G7): exact aantal characters + iedereen blijft in beeld, hele clip. `VEO_NEGATIVE` uitgebreid met disappearing/exiting/extra-character termen.
2. **Cast-cap in script** — `ScriptTool` schema `characters.maxItems: 3`; `StructureValidator` check 7: max 2 characters per normale scène, 3 alleen op hook/climax/closer en eerste/laatste scène.
3. **Clip-QC (output-gate)** — `FrameExtractor` (video-gen service) zet `qc_first/mid/last.png` naast elke clip.mp4; `ClipQc` (orchestrator, Claude vision) checkt headcount per frame, verdwijning tussen frames en accessoire-drift. Fail → 1 re-roll (max 2 per job), daarna Ken Burns-fallback op de al gekeurde still. Fail-safe: QC-fouten blokkeren nooit.
4. **Frame-chaining** — opeenvolgende Veo-scènes (zelfde locatie + cast) krijgen een `chainGroup`; video-gen service rendert die sequentieel en start clip N+1 op het geëxtraheerde laatste frame van clip N (pixel-continuïteit i.p.v. alleen de tekstuele G5-hint). Knop: `pipeline.frame-chaining.enabled` (default true).

## 7. Phase 2 — Optioneel, fully-local AI

Niet nu, maar de switch is laag-impact als je later cloud-kosten wil dempen:

- **LLM lokaal**: Ollama (Llama 3.1 8B of Qwen 2.5 14B). `script-service` krijgt provider-flag `LLM_PROVIDER=anthropic|ollama`. Anti-dup blijft hetzelfde. Voor strikte JSON-output: Ollama `format=json` mode of gestructureerde decoder.
- **Image lokaal**: ComfyUI + SDXL/Flux op eigen GPU. `image-service` derde provider naast openai/replicate. Bestaande LoRA-pipeline blijft bruikbaar (LoRA werkt ook lokaal in ComfyUI).
- **TTS lokaal**: Piper (snel, CPU) of XTTS v2 (cloning, GPU). Vervangt ElevenLabs voor non-hero content. ElevenLabs blijft voor karakterstemmen waar kwaliteit telt.
- **Veo blijft cloud**. Geen lokaal alternatief in dit kwaliteitsbereik.
- **Observability**: Grafana + Prometheus + Loki container groep, Spring Boot exposeert `/actuator/prometheus`. Dashboard: kosten/video, throughput, fail rate per stage, Veo quota.
- **Idee-loop**: nieuwe `idea-service` + `analytics-service` + pgvector op `scripts` DB. Top-performers worden few-shot in variation-prompt context.

Niet in scope: Kafka/Service Bus (huidige 1 video / paar minuten throughput vereist het niet), Kubernetes (één host is de eis), Azure/cloud-deploy (lokaal blijft de eis).

---

## 8. Risks & Bottlenecks

| Risico | Impact | Mitigatie |
|---|---|---|
| Veo quota / rate limits | Pipeline stalt op één stage | `VEO_MAX_PARALLEL`, kosten/clip in DB-table loggen, fallback naar Ken Burns per scène |
| Veo-kosten lopen op | €€€ per video | `videoGen.costCapEurPerVideo` in bible, harde check vóór call, default = Lite |
| Veo image-to-video drift (gezichten morphen) | Karakters worden onherkenbaar | Korte clips (4-6s), end_image lock waar mogelijk, hero-only Veo voor MVP-fase |
| Anti-dup faalt na schaal | YouTube algoritme markeert kanaal | SimHash threshold periodiek tunen, embedding-stage toevoegen voor 2e pass |
| LoRA niet getraind = lage character consistency | Visueel rommelig | LoRA training pipeline al klaar, README documenteert switch in 1 env var |
| `workdir` volume vol | Job faalt mid-pipeline | Retention cron: na COMPLETED + 30 dagen → wegmieren behalve `final.mp4` |
| Geen queue = service-restart verliest in-flight jobs | Een job hangt op | `PENDING` jobs bij startup re-trigger via DB-scan (TODO) |
| Anthropic/OpenAI/Eleven outage | Hele pipeline plat | Per-provider fallback configureren (Anthropic ↔ OpenAI, Eleven ↔ Azure Speech). Out of scope MVP |
| YouTube OAuth refresh token verloopt stilletjes | Upload faalt | Health-probe + alert, refresh-token rotatie scriptje |
| COPPA / "Made for Kids" violation | Account ban | Verplicht in upload-call, content-review checklist als pre-publish gate |

Belangrijkste single point of failure verandert door Veo: van "OpenAI image gen + FFmpeg" (~€0.50 per video, 1-2 min) naar "Veo per scène" (€2-5+ per video, 5-15 min wallclock). Daarom blijft Ken Burns de default.

---

## 9. Next Action Plan

Concrete eerste stappen, in volgorde:

1. **`services/video-generation-service/` scaffold** — pom.xml, Dockerfile, Spring Boot main, `application.yml`, `VeoClient` (WebClient + polling), `ClipsController`, properties klasse, design doc. Zie sectie 3 van `services/video-generation-service/DESIGN.md`.
2. **Bible-uitbreiding** — `videoGen` sectie in `bible/channel.yml` met model routing per scene type + cost cap.
3. **Compose update** — voeg service toe, env vars, port 8087, volume bindings, depends_on script-service (niet strikt nodig maar voor opstartvolgorde).
4. **.env.example** — `VEO_MCP_URL`, `VEO_API_KEY`, `VEO_MAX_PARALLEL`, kort comment.
5. **Orchestrator-client** — `VideoGenerationServiceClient.java` in dezelfde stijl als de andere clients (WebClient → POST → blok). `OrchestratorProperties.services.videoGen` URL toevoegen.
6. **Orchestrator-pipeline** — nieuwe stap tussen image-fan-in en assembly. Conditional op `motionMode`. Properties default = `ken_burns`.
7. **Assembly-tweak** — `SceneClipBuilder` checkt `clip.mp4` bestaan → bypass Ken Burns voor die scène. Volledig backward compatible.
8. **README-update** — `motionMode` opt-in, cost-tabel bijwerken (Ken Burns ~€0.50 / Veo ~€3-5), Veo-modellen genoemd.
9. **Smoke test** — één video draaien met `motionMode=veo`, max 4 scènes, alleen `veo3_1_lite`. Vergelijk met Ken Burns baseline op kanaal-bible "Pip & Friends".
10. **Verificatie** — meet kosten + tijd, log naar `video_jobs.metrics` jsonb veld (kleine migratie).

Daarna pas: Phase 2 ideeën (Ollama, Piper, ComfyUI) evalueren op basis van échte cloud-uitgaven.

---

## Appendix A — Bestaande state machine (geen wijziging)

```
PENDING
  → SCRIPT_GENERATING
  → ASSETS_GENERATING       (voice + image, parallel)
  → ASSEMBLING              (FFmpeg; nieuw: detecteert clip.mp4)
  → UPLOADING               (YouTube)
  → COMPLETED               (youtube_url gevuld)
  → FAILED                  (error gevuld)
```

Veo-stap zit binnen `ASSETS_GENERATING` (sub-stap), geen nieuwe status nodig.

## Appendix B — Bible-knoppen na uitbreiding

```yaml
channel: ...
visualStyle: ...
characters: [pip, mo, bo]
locations: [coop, garden, pond, kitchen]
brand: { intro, outro }
music: { tracks: [...] }
imageGen:
  provider: openai | replicate
videoGen:                          # NIEUW
  defaultMode: ken_burns | veo
  veo:
    defaultModel: veo3_1_lite
    heroModel: veo3_1
    heroQuality: high
    fallbackModel: veo3
    maxClipSeconds: 8
    audio: false                   # audio komt van ElevenLabs, niet Veo
    costCapEurPerVideo: 5.00
    routing:
      - sceneType: intro
        model: veo3_1
        quality: high
      - sceneType: hero
        model: veo3_1
        quality: high
      - sceneType: standard
        model: veo3_1_lite
```
