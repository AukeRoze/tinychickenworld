# Video Generation Service — Design

Standalone Spring Boot 3 service. Wikkelt Google **Vertex AI Veo** (image-to-video) achter een lokaal REST endpoint. Voor elke scène: neem de start-image van `image-service` + de scene visualDesc + duur → vraag Veo een clip te renderen → schrijf MP4 naar de gedeelde `workdir`.

Optioneel pad. Default pipeline blijft Ken Burns (gratis, snel). Veo is opt-in per video (`motionMode=veo`) of per kanaal-default (in `bible/channel.yml`).

Port: **8087**. Geen eigen database — alle state via `workdir` volume + responses.

---

## 1. API Contract

Eén POST endpoint, in de stijl van de andere services (`image-service`, `voice-service`):

### `POST /api/v1/clips/generate`

**Request**
```json
{
  "jobId": "uuid",
  "format": "landscape",
  "scenes": [
    {
      "seq": 1,
      "sceneType": "intro",
      "startImagePath": "/workdir/jobs/<jobId>/scenes/1/image.png",
      "endImagePath": "/workdir/jobs/<jobId>/scenes/2/image.png",
      "visualDesc": "Pip and Mo walking through the sunflower garden, soft watercolor",
      "durationSeconds": 6,
      "negativePrompt": "blurry, distorted faces, extra limbs"
    }
  ]
}
```

**Response**
```json
{
  "jobId": "uuid",
  "clips": [
    {
      "seq": 1,
      "status": "OK",
      "clipPath": "/workdir/jobs/<jobId>/scenes/1/clip.mp4",
      "model": "veo-3.1-generate-001",
      "resolution": "720p",
      "durationSeconds": 6,
      "wallclockMs": 47000,
      "costEur": 0.42,
      "error": null
    },
    {
      "seq": 2,
      "status": "FALLBACK",
      "clipPath": null,
      "model": "veo-3.1-fast-generate-001",
      "error": "QUOTA_EXCEEDED"
    }
  ],
  "totalCostEur": 0.42,
  "costCapReached": false
}
```

**Contract regels**
- `status=OK`: `clip.mp4` ligt op pad, assembly mag direct gebruiken.
- `status=FALLBACK`: Veo faalde, geen clip geschreven. Assembly valt terug op Ken Burns voor die scène (gebruikt `image.png` van diezelfde scène). Geen fail van de totale job.
- `status=FAILED`: harde fout, scène kan niet geassembleerd worden. Orchestrator beslist of de hele job faalt.
- Bij `costCapReached=true` worden volgende scènes overgeslagen als FALLBACK.

Synchroon request van orchestrator → service blokkeert tot alle scènes klaar zijn (intern wel parallel). De orchestrator leunt al op `@Async` op job-niveau.

---

## 2. Vertex AI Veo Integratie

We praten **direct** met Vertex AI via de officiële `com.google.genai` Java SDK. Geen MCP-proxy.

### Authenticatie

Application Default Credentials (ADC) via een GCP service account:
- Service account met rol `Vertex AI User` (`roles/aiplatform.user`) en `Storage Object Admin` op de output bucket.
- JSON key gemount op `/secrets/gcp-sa.json`.
- `GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcp-sa.json` env in compose.
- `GCP_PROJECT_ID` en `GCP_REGION` (default `us-central1`).

### SDK pattern

```java
try (Client client = Client.builder()
        .location(region)              // "us-central1"
        .vertexAI(true)
        .build()) {

    GenerateVideosOperation op = client.models.generateVideos(
        modelId,                       // "veo-3.1-generate-001" of "veo-3.1-fast-generate-001"
        prompt,                        // scene visualDesc, gesaneerd
        Image.builder()
            .gcsUri(uploadedImageGcsUri)   // gs://<bucket>/refs/<jobId>/<seq>.png
            .mimeType("image/png")
            .build(),
        GenerateVideosConfig.builder()
            .aspectRatio(aspect)            // "16:9" of "9:16"
            .durationSeconds(durationSec)   // 4/6/8
            .resolution(resolution)         // "720p" of "1080p"
            .negativePrompt(neg)            // optioneel
            .outputGcsUri(outputGcsPrefix)  // gs://<bucket>/out/<jobId>/<seq>/
            .build()
    );

    while (!op.done().orElse(false)) {
        Thread.sleep(pollDelayMs);
        op = client.operations.getVideosOperation(op, GetOperationConfig.builder().build());
    }

    String videoGcsUri = op.response()
        .flatMap(GenerateVideosResponse::generatedVideos)
        .flatMap(v -> v.stream().findFirst())
        .flatMap(GeneratedVideo::video)
        .flatMap(Video::uri)
        .orElseThrow();

    // Download from GCS to local workdir
    gcsDownloader.download(videoGcsUri, workdir.resolve("clip.mp4"));
}
```

### Output via GCS

Vertex AI Veo schrijft de gerenderde MP4 naar een GCS bucket. Wij downloaden 'm vervolgens naar `workdir/jobs/<jobId>/scenes/<seq>/clip.mp4` zodat de assembly-service hem lokaal pakt zoals de andere assets.

Reden voor GCS i.p.v. inline bytes: de Java SDK ondersteunt het primair via GCS, en grote outputs (>10MB voor 8s 1080p) verwerken zonder geheugen-druk werkt beter via streaming download.

**Eenmalige setup** (handmatig, niet door de service):
```bash
gcloud storage buckets create gs://<your-bucket> --location=us-central1
gcloud storage buckets update gs://<your-bucket> --lifecycle-file=lifecycle.json  # auto-delete na 7 dagen
```

Lifecycle rule kost-bespaarder: outputs hebben we lokaal nodig, dus na download mag GCS leeg. 7-daagse auto-delete is veilig.

---

## 3. Model Routing

Routing is bible-driven. Service laadt `bible/channel.yml` bij startup en cachet de `videoGen` sectie.

```java
public record ModelRoute(
    String modelId,        // "veo-3.1-generate-001" of "veo-3.1-fast-generate-001"
    String resolution,     // "720p" of "1080p"
    int maxDurationSec
) {}

ModelRoute pick(SceneType type, boolean costCapNearby) { ... }
```

Default mapping (override-baar in bible):

| sceneType | model | resolution | max dur |
|---|---|---|---|
| `intro` | `veo-3.1-generate-001` | `1080p` | 6 |
| `outro` | `veo-3.1-generate-001` | `1080p` | 6 |
| `hero` | `veo-3.1-generate-001` | `1080p` | 8 |
| `standard` | `veo-3.1-fast-generate-001` | `720p` | 6 |

Bij `costCapNearby` (cumulatieve kosten > 80% cap): forceer alle volgende calls naar `veo-3.1-fast-generate-001` @ 720p. Bij quota-error (HTTP 429): één retry naar fallback model uit `videoGen.veo.fallbackModel` (default `veo-3.0-fast-generate-preview`).

Scene-type wordt geleverd door de orchestrator. MVP-regel:
- `seq == 1` → `intro`
- `seq == lastSeq` → `outro`
- Rest → `standard`
- `hero` voorlopig niet gebruikt — komt later op basis van een `isHero` flag uit script-service.

---

## 4. Cost Tracking

Statische rate-card in `application.yml`. Cijfers zijn schattingen — pas aan na eerste echte facturatie van GCP.

```yaml
veo:
  rates:
    "veo-3.1-fast-generate-001":  { eur_per_second: 0.10 }
    "veo-3.1-generate-001":       { eur_per_second: 0.40 }
    "veo-3.0-fast-generate-preview": { eur_per_second: 0.08 }
```

Budget cap komt uit `bible.videoGen.veo.costCapEurPerVideo` (default 5.00). Cap-check:
- **Vóór** elke call: als (nog te genereren scènes × default rate × duration) > resterend budget → kleinere model voor de rest.
- **Na** elke call: cumulatief totaal updaten. Als > cap → `costCapReached=true`, resterende scènes worden `FALLBACK`.

Kosten worden in de response teruggegeven. Orchestrator persisteert ze in `video_jobs.metrics` jsonb veld (kleine migratie, zie task 9).

---

## 5. Parallelism

Veo-calls zijn duur en traag (30s - 5min per clip). Parallellisme begrenzen:

```java
Semaphore veoSlots = new Semaphore(VEO_MAX_PARALLEL); // default 3
```

Per scène: claim slot → upload start-image naar GCS → submit job → poll → download → release. Bij quota error: respecteer Veo's `Retry-After` (waar beschikbaar) voor de hele service.

---

## 6. Failure Modes en Mitigaties

| Fout | Detectie | Aanpak |
|---|---|---|
| Vertex AI HTTP 429 (quota) | SDK exception code | Globale pauze, één retry op fallback model. Daarna FALLBACK. |
| Veo operation FAILED status | `op.error()` of `op.done()` zonder response | Eén retry op fallback model. Daarna FALLBACK. |
| Veo operation TIMEOUT | Polling-loop > `VEO_MAX_WAIT_SEC` | Cancel operation, FALLBACK. |
| Cost cap bereikt | Pre-check | Resterende → FALLBACK met reason `COST_CAP`. |
| GCS upload van start-image faalt | IOException | Eén retry. Daarna FAILED voor die scène. |
| GCS download van MP4 faalt | IOException of `ffprobe` validatie | Eén retry. Daarna FALLBACK. |
| Lege/corrupte MP4 | `ffprobe -v error` exit ≠ 0 | FALLBACK. |
| Service account credentials missen | `GoogleAuthException` bij startup | Spring fails-fast, geen request wordt geaccepteerd. |
| `GCS_BUCKET` env niet gezet | Startup check | Spring fails-fast. |

Belangrijk: **één gefaalde scène ≠ gefaalde video**. Ken Burns kan altijd inspringen via `video-assembly-service` bypass.

---

## 7. Configuratie

`application.yml`:
```yaml
server:
  port: 8087

gcp:
  project-id: ${GCP_PROJECT_ID}
  region: ${GCP_REGION:us-central1}
  credentials-path: ${GOOGLE_APPLICATION_CREDENTIALS:/secrets/gcp-sa.json}
  output-bucket: ${GCS_OUTPUT_BUCKET}
  output-prefix: ${GCS_OUTPUT_PREFIX:veo-outputs}

veo:
  polling:
    initial-delay-ms: 5000
    max-delay-ms: 15000
    max-wait-seconds: ${VEO_MAX_WAIT_SEC:600}
  parallelism:
    max-parallel: ${VEO_MAX_PARALLEL:3}
  rates:
    "veo-3.1-fast-generate-001":     { eur-per-second: 0.10 }
    "veo-3.1-generate-001":          { eur-per-second: 0.40 }
    "veo-3.0-fast-generate-preview": { eur-per-second: 0.08 }

bible:
  path: ${BIBLE_PATH:/bible/channel.yml}

workdir:
  root: ${WORK_ROOT:/workdir}
```

`.env` toevoegingen:
```
GCP_PROJECT_ID=my-project-123
GCP_REGION=us-central1
GOOGLE_APPLICATION_CREDENTIALS=/secrets/gcp-sa.json
GCS_OUTPUT_BUCKET=my-veo-outputs
VEO_MAX_PARALLEL=3
VEO_MAX_WAIT_SEC=600
```

Plus: `secrets/gcp-sa.json` lokaal (gitignored), gemount in compose.

---

## 8. Folder layout

```
services/video-generation-service/
├── Dockerfile
├── pom.xml
├── DESIGN.md
└── src/main/
    ├── java/com/youtubeauto/videogen/
    │   ├── VideoGenApplication.java
    │   ├── api/
    │   │   ├── ClipsController.java
    │   │   └── dto/
    │   │       ├── GenerateClipsRequest.java
    │   │       ├── GenerateClipsResponse.java
    │   │       ├── SceneRequest.java
    │   │       └── ClipResult.java
    │   ├── bible/
    │   │   ├── BibleLoader.java
    │   │   └── VideoGenConfig.java
    │   ├── config/
    │   │   ├── GcpProperties.java
    │   │   ├── VeoProperties.java
    │   │   ├── WorkdirProperties.java
    │   │   └── GenAiClientConfig.java
    │   ├── routing/
    │   │   ├── ModelRoute.java
    │   │   ├── ModelRouter.java
    │   │   └── SceneType.java
    │   ├── cost/
    │   │   ├── CostBudget.java
    │   │   └── CostCalculator.java
    │   ├── gcs/
    │   │   ├── GcsClient.java
    │   │   └── GcsUri.java
    │   ├── veo/
    │   │   ├── VertexVeoClient.java
    │   │   └── VeoException.java
    │   └── service/
    │       └── ClipGenerationService.java
    └── resources/
        └── application.yml
```
