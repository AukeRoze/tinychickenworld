# YouTube Auto MVP

Fully automated YouTube content pipeline for a **branded kids channel with
a consistent cast of characters**. Submit a topic, get back a video URL —
every video features the same recurring chickens in the same world, in the
same illustration style.

## Channel identity — the bible

`bible/channel.yml` defines:

- **Three chickens**: Pip (main), Mo (sidekick), Bo (sidekick) — each with
  ultra-specific visual description, personality and an ElevenLabs voice ID.
- **Locked visual style**: soft watercolor storybook. Prepended to every
  image prompt. No more style rotation.
- **Recurring locations**: Cozy Coop, Sunflower Garden, Frog Pond, Coop
  Kitchen. Scripts reference these by id.
- **Brand bumpers**: optional `intro.mp4` / `outro.mp4` concatenated by
  `video-assembly-service` for instant channel recognition.

Edit `bible/channel.yml` to rename characters, swap voice ids, add a fourth
chicken, or change the world. See `bible/README.md` for details.

## Architecture (MVP)

```
       POST /api/v1/videos { topic }
                  |
                  v
   +---------------------------+
   |       orchestrator        |  Postgres: video_jobs
   |  (state machine, polls)   |
   +-------------+-------------+
                 |
   +------+------+------+------+-------+------+------+
   |      |      |      |      |       |      |      |
   v      v      v      v      v       v      v      v
 script  voice  image  thumb  assembly  upload
 (Claude)(EL11) (OpenAI)(OpenAI+AWT)(FFmpeg)(YT API)
   |
 Postgres:
 script_jobs,
 scripts,
 scenes
```

## Services and ports

| Service | Port | Purpose |
|---|---|---|
| orchestrator | 8080 | Owns job state, drives the pipeline |
| script-service | 8081 | Generates a script (structured JSON) from a topic via Claude (Anthropic Messages API, forced tool_use) |
| video-assembly-service | 8082 | Wraps FFmpeg: scene clips → concat → mix → encode |
| voice-service | 8083 | ElevenLabs TTS, one MP3 per scene |
| image-service | 8084 | OpenAI image generation, one PNG per scene |
| youtube-upload-service | 8085 | YouTube Data API v3 resumable upload |
| thumbnail-service | 8086 | Dedicated AI thumbnail + AWT title overlay (4 layout templates) |
| video-generation-service | 8087 | **Opt-in.** Image-to-video via Vertex AI Veo. Replaces Ken Burns per scene when `motionMode=veo`. |

## Motion: Ken Burns (default) vs Veo image-to-video (opt-in)

By default every scene is a still image animated with a rotating Ken Burns motion preset. That's cheap (€0 extra) and fast (seconds per scene). Set `motionMode=veo` on the request to instead route scene generation through `video-generation-service`, which calls **Google Vertex AI Veo** with each scene's start-image and returns a real motion clip.

```bash
curl -X POST http://localhost:8080/api/v1/videos \
  -H 'Content-Type: application/json' \
  -d '{
    "topic": "How rainbows are made",
    "motionMode": "veo",
    "targetSeconds": 60,
    "privacyStatus": "private"
  }'
```

Per-scene fallback: if Veo fails (quota, timeout, corrupt output, cost cap), that scene silently reverts to Ken Burns. One failed scene never fails the job.

Setup (one-time):

1. GCP project with the Vertex AI API enabled.
2. GCS bucket for outputs: `gcloud storage buckets create gs://<bucket> --location=us-central1`. Outputs are auto-downloaded to `workdir`; a 7-day lifecycle rule on the bucket keeps cost negligible.
3. Service account with `roles/aiplatform.user` and `Storage Object Admin` on the bucket. Download a JSON key to `secrets/gcp-sa.json`.
4. Fill `GCP_PROJECT_ID`, `GCP_REGION` (default `us-central1`), `GCS_OUTPUT_BUCKET` in `.env`.

Vertex AI Veo model routing comes from `bible/channel.yml` → `videoGen` section. Defaults:

| scene type | Vertex model | resolution |
|---|---|---|
| intro / outro / hero | `veo-3.1-generate-001` | 1080p |
| standard | `veo-3.1-fast-generate-001` | 720p |
| fallback (after quota) | `veo-3.0-fast-generate-preview` | 720p |

A per-video budget cap (`bible.videoGen.veo.costCapEurPerVideo`, default €5) downshifts to the cheap model once cumulative spend crosses 80% of the cap, and switches remaining scenes to Ken Burns once the cap is hit.

See `services/video-generation-service/DESIGN.md` for the full contract, failure modes, and Vertex AI SDK usage.

## Review gates (human-in-the-loop)

The pipeline pauses at configurable gates so you can inspect output before the next stage runs. Useful for kids content where one bad script shouldn't turn into a paid Veo render. Gates are bible-driven (`bible/channel.yml -> review`) and individually toggleable via env vars:

| Gate | Fires when | What you see | Why useful |
|---|---|---|---|
| `afterScript` | script is generated | `/review/script/{id}` — title, hook, scenes JSON | Cheapest gate; catches bad info before any spend |
| `afterAssets` | voice + images done | `/review/assets/{id}` — paths to workdir files | Catches off-style images or robotic voice |
| `beforeVeo` | only if `motionMode=veo` | same assets page | Skip Veo cost if assets look wrong |
| `beforeUpload` | master MP4 + metadata ready | `/review/master/{id}` — title, description, tags, file paths | Last chance before publishing |

Default for new repos: `afterScript=true`, `beforeUpload=true`, others off. Override per-env in `.env`:

```
REVIEW_AFTER_SCRIPT=true
REVIEW_AFTER_ASSETS=false
REVIEW_BEFORE_VEO=false
REVIEW_BEFORE_UPLOAD=true
REVIEW_EMAIL_TO=you@example.com   # blank = log only, no mail
```

When a gate fires the job status becomes `SCRIPT_REVIEW_PENDING` / `ASSETS_REVIEW_PENDING` / `VEO_REVIEW_PENDING` / `UPLOAD_REVIEW_PENDING`. The orchestrator emails the configured address with a preview link and two action links. Approve or reject:

```bash
# Approve — pipeline resumes from the next stage
curl -X POST http://localhost:8080/api/v1/videos/<jobId>/approve

# Reject — job moves to FAILED with the given reason
curl -X POST 'http://localhost:8080/api/v1/videos/<jobId>/reject?reason=hook-too-flat'
```

GET works on both endpoints too, so the mail buttons are one-click and don't need a form.

Local mail capture: a `mailhog` container runs at `http://localhost:8025` (web UI) and listens for SMTP on `mailhog:1025`. All review mails land there during local development — no real SMTP needed.

Without an `REVIEW_EMAIL_TO`, gates still pause the pipeline; the mail body is just logged at INFO. Useful for "review via the REST API only" setups.

## Vertical Shorts (1080×1920)

YouTube Shorts get a separate algorithmic feed and dramatically boost
discovery for new channels. The pipeline supports them as a first-class
format — same code path, different canvas.

Submit a vertical video by adding `"format": "vertical"`:

```bash
curl -X POST http://localhost:8080/api/v1/videos \
  -H 'Content-Type: application/json' \
  -d '{
    "topic": "How rainbows are made",
    "format": "vertical",
    "targetSeconds": 45,
    "privacyStatus": "private"
  }'
```

What changes when `format=vertical`:

- `targetSeconds` is **auto-capped at 60** (YouTube Shorts rule)
- Scene images are generated 1024×1792 (OpenAI) or 768×1344 (Replicate+LoRA)
- Video canvas is 1080×1920, all FFmpeg filters use vertical dimensions
- Ken Burns motion presets work in vertical (pan-left/right still functional)
- Metadata generator adds `#Shorts` to the title and tags
- Same intro/outro path — if your bumpers are landscape, the concat filter
  pads them with black bars (consider rendering vertical bumpers for clean look)

Default (omitting `format` or `"format": "landscape"`): standard 1920×1080.

**Mix the two.** YouTube algorithm rewards channels that publish both formats
— vertical Shorts pull in new viewers, longer landscape videos build watch
time. A reasonable pattern: 1 landscape per day + 2-3 Shorts per week.

## Image generation: two providers

Scene images can be generated in two modes, controlled by `IMAGE_PROVIDER`
and `imageGen.provider` in `bible/channel.yml`:

| Mode | Backend | Character consistency | Per-image cost | Setup |
|---|---|---|---|---|
| `openai` (default) | OpenAI `gpt-image-1` | ~70-80% (prompt-only) | $0.04-0.08 | None — just an API key |
| `replicate` | Flux base + your trained cast LoRA | 95%+ | $0.003-0.01 | Train a LoRA once (~$5-12) |

For a real branded channel, switch to `replicate` once you've validated the
cast and the channel concept. The training pipeline (one-time, no local GPU
required) is documented in `infra/lora-training/README.md`. tl;dr:

```bash
cd infra/lora-training
./generate-reference-images.sh   # uses your OPENAI_API_KEY
# manually curate ./dataset/<char>/, delete bad images
./caption-images.sh
./zip-dataset.sh
# host dataset.zip publicly (S3, Cloudflare R2, transfer.sh)
./replicate-train.sh https://your.cdn/dataset.zip
# put the resulting model URL + LoRA URL in .env, restart image-service
```

Switching providers is a single env var (`IMAGE_PROVIDER=replicate`) — no
code changes, no migrations. You can A/B test on the same topic.

## Pipeline

The orchestrator runs this sequence per job (async):

1. **Script** — `POST script-service/api/v1/scripts` → polls until `COMPLETED`.
   Script-service reads the bible's cast + locations, forces Claude to emit
   scenes where each `lines` array uses only the bible's character ids and
   each `locationId` is one of the bible's locations.
2. **Voice + images in parallel**:
   - `voice-service/synthesize` — for each scene, sends each line to
     ElevenLabs with the speaker's voice id from the bible, then concatenates
     the per-line MP3s into one scene MP3 (FFmpeg concat demuxer).
   - `image-service/generate` — for each scene, prepends the locked visual
     style + every character-in-scene's full description + the location
     description before the scene's `visualDesc`.
3. **Assemble** — `video-assembly-service/assemble`:
   - per-scene clips with rotating camera motion (zoom/pan/static),
   - concat,
   - optional intro.mp4 + outro.mp4 bumpers,
   - optional background music + sidechain ducking,
   - optional burnt subtitles,
   - final H.264 re-encode tuned for YouTube.
4. **Metadata** — Claude generates YouTube title/description/tags via tool_use.
5. **Thumbnail** — `thumbnail-service/generate` produces a 1280×720 PNG with
   AWT text overlay (white fill, black outline, drop shadow).
6. **Upload** — `youtube-upload-service/upload` resumable upload, returns
   `youtubeVideoId` + `youtubeUrl`.
7. **Persist** — `video_jobs` row flipped to `COMPLETED`.

## Run locally

```bash
cp .env.example .env
# fill in:
#   ANTHROPIC_API_KEY
#   OPENAI_API_KEY (scene images + thumbnails)
#   ELEVENLABS_API_KEY
#   VOICE_ID_PIP / VOICE_ID_MO / VOICE_ID_BO (one per chicken)
#   ELEVENLABS_VOICE_ID (fallback if any of the three is missing)
#
# Drop your YouTube OAuth client_secret.json under ./secrets/
# (Optional) drop bible/intro.mp4 + bible/outro.mp4 for branded bumpers.

docker compose up --build
```

## LLM providers

| Component | Provider | Why |
|---|---|---|
| Script generation (script-service) | Claude (Anthropic Messages API) | Forced `tool_use` gives guaranteed structured output |
| Metadata generation (orchestrator) | Claude (Anthropic Messages API) | Same tool_use technique for title/description/tags |
| Image generation (image-service) | OpenAI `gpt-image-1` | Claude cannot generate images |
| TTS (voice-service) | ElevenLabs | Cheapest+best for kids voice in MVP |

> A Claude.ai Pro/Max subscription does **not** grant API access.
> Create an API key at <https://console.anthropic.com/> — billed separately.

YouTube OAuth requires a one-time browser consent. Run the upload service container interactively the first time, or do the OAuth dance from a local checkout and copy the generated `secrets/yt-creds/StoredCredential` file into the container.

## Submit a video

```bash
curl -X POST http://localhost:8080/api/v1/videos \
  -H 'Content-Type: application/json' \
  -d '{
    "topic": "How rainbows are made",
    "audience": "kids_3_6",
    "targetSeconds": 60,
    "privacyStatus": "private"
  }'

# returns { "id": "<jobId>", ... }
```

Poll status:

```bash
curl http://localhost:8080/api/v1/videos/<jobId>
```

## Visual-uniqueness defences (video + thumbnail)

Beyond the script-level dedup, two production-level features reduce the
"AI farm" visual fingerprint:

### Per-scene camera motion (video-assembly-service)

`SceneClipBuilder` no longer applies the same slow zoom-in to every scene.
A `MotionSelector` picks a `MotionPreset` per scene from:
`ZOOM_IN`, `ZOOM_OUT`, `PAN_LEFT`, `PAN_RIGHT`, `STATIC`, `ZOOM_PAN_DIAGONAL`.
Adjacent scenes get different motions. Disable via `MOTION_ENABLED=false` env
var if you want predictable test output.

### Real thumbnail-service (port 8086)

Replaces the MVP shortcut of "use first scene image as thumbnail". Per request:

1. **Layout picker** rotates between four composition templates
   (`TEXT_LEFT`, `TEXT_BOTTOM`, `TEXT_OUTLINE_CENTER`, `TEXT_TOP_BANNER`).
2. **Base image** generated by OpenAI `gpt-image-1` with a thumbnail-specific
   prompt — close-up, single focal point, eye contact, and a layout-aware
   hint ("leave the left 45% relatively empty", etc.).
3. **Title overlay** drawn by Java AWT (`BufferedImage` + `Graphics2D`):
   white fill / black outline / drop shadow, word-wrapped, font size
   auto-fitted to the available area. Headless mode + bundled DejaVu Sans
   Bold (installed in the Dockerfile).
4. Output is a 1280×720 PNG passed straight to `youtube-upload-service`.

Because the layout *and* the visual *and* the script directives all rotate
independently, the probability that two consecutive videos look identical
drops by roughly an order of magnitude vs. the MVP baseline.

## Anti-duplicate / pattern-detection defence

YouTube actively pattern-detects "AI farm" channels: identical hooks, identical
cadence, identical CTAs across videos. The script-service has a built-in defence:

1. **Forced variation up front.** Before each generation, a random `VariationProfile`
   is picked across four axes (hook style, tone, narrative structure, example
   style). The selected directives are injected into the system prompt and the
   model is required to follow them. Recent profiles are avoided so two videos in
   a row can't share the exact same tuple.

2. **Fingerprinting after generation.** Each generated script's normalised
   narration is hashed two ways:
   - **SHA-256 content hash** (exact-dup short-circuit; unique index in Postgres).
   - **64-bit SimHash signature** (near-dup signature).

3. **Duplicate check before persistence.** The new fingerprint is compared
   against the last `app.dedupe.lookback-window` scripts (default 1000) using
   Postgres' `bit_count(simhash # ?)` for Hamming distance. If the similarity
   exceeds `app.dedupe.similarity-threshold` (default 0.80 = Hamming ≤ 12), the
   script is rejected.

4. **Retry with a fresh profile.** On rejection the orchestrator picks a new
   variation profile and regenerates, up to `app.dedupe.max-retries` (default 2).
   If still duplicate, the job fails with `DUPLICATE:...` — better to skip a
   video than to publish a near-clone.

Every script row stores its `content_hash`, `simhash`, `variation_profile` and
`regen_attempts`, so you can audit how diverse the channel actually is:

```sql
SELECT variation_profile, count(*)
FROM scripts
WHERE created_at > now() - interval '30 days'
GROUP BY 1 ORDER BY 2 DESC;
```

If one tuple dominates, your channel is at risk regardless of what the model
thinks it's doing.

> Note on scope: SimHash is a cheap, lossy first pass. For higher-stakes
> dedupe you'd add a second stage using cosine similarity on text embeddings.
> MVP only does SimHash; that's the trade-off.

## Status state machine

```
PENDING
  -> SCRIPT_GENERATING
  -> ASSETS_GENERATING   (voice + images in parallel)
  -> ASSEMBLING          (FFmpeg)
  -> UPLOADING           (YouTube)
  -> COMPLETED           (youtube_url populated)
  -> FAILED              (error populated)
```

## Cost estimate per video (60 s, ~8 scenes)

| Component | Default (Ken Burns) | `motionMode=veo` |
|---|---|---|
| Claude script + metadata | €0.01 | €0.01 |
| Image generation (8× scenes) | €0.30–€0.60 | €0.30–€0.60 (still needed as Veo start-images) |
| ElevenLabs TTS (~150 words) | €0.05–€0.15 | €0.05–€0.15 |
| Vertex AI Veo (8× 6s clips) | — | €2.50–€5.00 (capped) |
| Local compute (FFmpeg, Postgres) | ~€0 | ~€0 |
| **Total per video** | **€0.40–€0.80** | **€2.85–€5.75** |

YouTube monetisation under COPPA pays low CPMs on kids content. Validate quality and policy compliance before scaling.

## Not in MVP (deferred)

- idea-service (auto-pick topics) — for MVP, you submit topics manually or from a CSV
- thumbnail-service (dedicated AI thumbnail with text overlay) — MVP uses first scene image
- analytics + self-improvement feedback loop
- message queue (Kafka / Azure Service Bus) — MVP uses direct HTTP between services
- Azure deployment manifests (Container Apps / Functions / Bicep)

## Repository layout

```
youtube-channel/
├── docker-compose.yml
├── .env.example
├── infra/
│   └── init-multi-db.sh
└── services/
    ├── orchestrator/
    ├── script-service/
    ├── voice-service/
    ├── image-service/
    ├── thumbnail-service/
    ├── video-assembly-service/     (includes DESIGN.md for the FFmpeg pipeline)
    ├── video-generation-service/   (Vertex AI Veo, opt-in via motionMode=veo)
    └── youtube-upload-service/
```

Each service is an independent Spring Boot 3 app (Java 21).
# tinychickenworld
