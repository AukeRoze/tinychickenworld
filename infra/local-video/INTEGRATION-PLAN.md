# Lokale video-provider — integratieplan (voorbereiding)

Doel: Veo als werkpaard vervangen door **lokale image-to-video** op eigen GPU
(gratis stroom = ~€0 marginaal). Model-**agnostisch** via ComfyUI, zodat je
tussen Wan / LTX-2 / Hunyuan wisselt door alleen de workflow te swappen — niet
de pipeline-code.

## Modelkeuze (niet vastpinnen)
- **Wan 2.x** — beste open i2v-kwaliteit/consistentie; 14B wil ~16-24GB VRAM,
  1.3B draait op ~8GB. Eerste keus als je GPU het trekt.
- **LTX-2** — snelst/lichtst (12GB), native audio; iets minder scherp.
- **HunyuanVideo** — hoge kwaliteit, zwaar (24GB+). CogVideoX — degelijk, ouder.
- Beide toppers ondersteunen **LoRA** → cast-LoRA voor consistentie blijft mogelijk.
- Runtime = **ComfyUI** (draait ze allemaal via een workflow-JSON).
- Hardware-richtlijn: 24GB (RTX 3090/4090) voor onbeperkt; 12-16GB = bruikbaar/720p.

## Waar het inplugt (exact)
`ClipGenerationService.generateOne(...)` kiest de provider op de model-id-prefix:
- nu: `route.modelId().startsWith("bytedance/")` → `FalSeedanceClient`, anders → `VertexVeoClient`.
- **nieuw**: `route.modelId().startsWith("local")` → `LocalVideoClient` (zelfde
  in/out-contract: startbeeld + prompt + duur + aspect → schrijf `clip.mp4`).

Contract om te spiegelen (zie `FalSeedanceClient.generateAndDownload`):
```
generateAndDownload(model, prompt, Path startImage, Path endImage,
                    resolution, durationSec, aspect, Path outFile)
```
Geen GCS-rondgang nodig (lokaal); na afloop dezelfde `isValidMp4` + `extractQcFrames`
+ `budget.add(estimate)` als de fal-tak. Cost = €0 (zie rates).

## LocalVideoClient (ComfyUI-HTTP) — ontwerp
Nieuw bestand: `videogen/local/LocalVideoClient.java`. Flow tegen ComfyUI:
1. Laad een **workflow-template** (JSON) voor het gekozen model uit
   `bible/comfy-workflows/<model>.json` (bv. `wan_i2v.json`, `ltx_i2v.json`).
2. Injecteer per scène: het **startbeeld** (upload via ComfyUI `/upload/image`
   of als pad), de **prompt** (visualDesc), **duur/aspect/resolutie** in de
   juiste nodes van de workflow.
3. `POST {COMFY_URL}/prompt` met de workflow → krijg `prompt_id`.
4. Poll `GET {COMFY_URL}/history/{prompt_id}` tot klaar (zelfde poll-stijl als
   `VeoProperties.polling`).
5. Download de output-mp4 via `GET {COMFY_URL}/view?filename=...` → `outFile`.
Model wisselen = andere workflow-JSON; **geen codewijziging**.

## Config (toevoegen)
`video-generation-service/application.yml`:
```yaml
local:
  enabled: ${LOCAL_VIDEO_ENABLED:false}
  base-url: ${COMFY_URL:http://host.docker.internal:8188}   # ComfyUI op de host
  workflow-dir: ${COMFY_WORKFLOW_DIR:/bible/comfy-workflows}
  poll-interval-ms: 3000
```
Let op: vanuit de Docker-container is de host-ComfyUI `host.docker.internal:8188`
(of het host-IP), niet `localhost`.

`veo.rates` (kostencalculator) — lokaal is gratis:
```
"[local-ltx]":  { eur-per-second: 0.0 }
"[local-wan]":  { eur-per-second: 0.0 }
```

`ModelRouter.normaliseModelId` — aliassen: `local`, `local_ltx`, `local_wan`
→ resolve naar `local-ltx` / `local-wan`. Routing in `bible/channel.yml`:
zet `standard` (de bulk) op `local_wan`/`local_ltx`; hou desgewenst Veo op
hero/intro/outro tot lokaal je bevalt (hybride).

## Activatie (zodra de GPU er is)
1. ComfyUI installeren + model (Wan/LTX) + de i2v-workflow testen in de UI.
2. Workflow exporteren als API-JSON → `bible/comfy-workflows/<model>.json`.
3. `.env`: `LOCAL_VIDEO_ENABLED=true`, `COMFY_URL=http://host.docker.internal:8188`.
4. `bible/channel.yml`: routing `standard.model: local_wan` (of volledig lokaal).
5. video-generation-service rebuilden.
6. Eén testjob tot de beforeVeo-gate → controleer de eerste lokale clip.

## Modelkeuze (aanrader, juni 2026)
Kwaliteitsstand open i2v: **Wan 2.2/2.7** = kwaliteitsleider (fotorealisme/subject-
detail; 2.7 heeft first/last-frame). **HunyuanVideo** = hoogste ceiling maar
VRAM-monster (~60-80GB vol → niet op één 5090). **LTX-2.3** = snelst (3× Hunyuan),
4K+audio, i2v + first/last-frame, verticaal-native (Shorts), draait comfortabel.

Voor dit kanaal (gestileerde cartoon, consistentie, volume op één 5090 32GB):
- **Bulk → LTX-2.3** (snel = veel volume, draait makkelijk gequantiseerd/FP4).
- **Hero-shots → Wan 2.2** als je extra finesse wilt (trager, accepteer dat).
- HunyuanVideo overslaan op één 5090.
- Beide (Wan/LTX) steunen **LoRA** → cast-LoRA-consistentieroute blijft.
- ComfyUI-agnostisch: installeer beide, test op je eigen cast/stijl, kies per look.

## Software-scaffold — GESTAGED (14 juni 2026)
Al ingebouwd, dormant tot configuratie:
- `videogen/local/LocalVideoClient.java` — @Component met het FalSeedance-contract
  (`generateAndDownload(...)`); gooit nu een nette IllegalStateException tot ComfyUI
  er is (ClipGeneration vangt → Ken Burns-fallback). Config-keys `local.enabled`,
  `local.base-url`, `local.workflow-dir`, `local.poll-interval-ms`.
- `ClipGenerationService` — dispatch-tak `route.modelId().startsWith("local")` →
  `LocalVideoClient` (spiegelt de Seedance-tak), kosten €0.

Nog te doen bij activatie (zie hierboven): de 5-staps ComfyUI-call in
`LocalVideoClient` afmaken tegen een draaiende instance + workflow-JSON; `local-*`
rates op 0 in `application.yml`; `ModelRouter`-aliassen of bevestigen dat een
onbekende `local_*`-id ongewijzigd doorvalt (dan is geen router-wijziging nodig);
`bible/channel.yml` routing op `local_*`. Een rebuild van video-generation-service
maakt de scaffold actief (verandert niets zolang geen `local*`-model geroutet wordt).

## Open punten (kan ik nu níét blind afmaken)
De exacte node-namen/ids in de workflow-JSON + de ComfyUI-API-payload hangen af
van je geïnstalleerde ComfyUI-versie en nodes. Daarom is dit een plan + scaffold-
contract; de `LocalVideoClient` schrijf ik volledig zodra je ComfyUI draait en we
één werkende workflow hebben (dan injecteer ik beeld/prompt/duur op de juiste nodes).
```
