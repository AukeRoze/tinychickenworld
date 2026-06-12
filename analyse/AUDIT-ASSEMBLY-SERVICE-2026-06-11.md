# Principal Audit — Video Assembly Service & renderketen (2026-06-11)

**Scope:** `video-assembly-service` als kern, plus elke plek stroomop- en stroomafwaarts die de uiteindelijke beeld-/geluids-/verhaalkwaliteit bepaalt (prompt-compilatie, voice, thumbnail, upload). Gebaseerd op volledige code-inspectie én de productie-logs van ep 3 — niet op aannames.
**Peildatum:** inclusief de wijzigingen van vandaag (chunked concat, clip-QC, frame-chaining, headcount-locks, metadata-policy, Shorts-fix, duurmetrics).

---

## EXECUTIVE SUMMARY

**Overall score: 7/10 — maturity: Advanced** (was 5,5 vóór de wijzigingen van vandaag).

Dit is géén beginnerssysteem. Bible-driven identity, deterministische validators, review-gates, QC-insights met patroonherkenning, een performance-feedbackloop, cost-caps met downshift, crash-recovery en een tweede videoprovider achter één contract — dat is architectuur die de meeste AI-contentkanalen nooit bereiken. De zwaktes zitten niet in wat er ontbreekt, maar in wat er **te vaak** gebeurt: de pijplijn hercomprimeert hetzelfde beeld 4-6× en hetzelfde audiospoor 6-8×, normaliseert loudness op drie plekken, en gooit daarmee stilletjes kwaliteit weg die Veo wél geleverd heeft.

**De drie grootste kwaliteits-bottlenecks:**

1. **Generatieverlies-cascade (beeld én audio).** Veo-clip → scene-encode (**crf 20, veryfast — de zwakste schakel staat vooraan**) → concat (crf 16) → branded (crf 16) → final (crf 19). Audio: ElevenLabs-MP3 → MP3-concat → AAC → AAC → AAC → AAC → AAC → AAC. Elke hop kost detail; samen kosten ze het "premium" gevoel dat het verschil maakt tussen "AI-video" en "animatie".
2. **Drievoudige loudness-normalisatie.** `loudnorm` draait in de scene-concat, nóg eens in de intro/outro-koppeling en nóg eens (two-pass) in de FinalEncoder. Drie keer dynamiekcompressie op hetzelfde materiaal = pomp-effecten en vlakgedrukte stem-dynamiek — uitgerekend bij kindercontent waar stem-expressie de retentie draagt.
3. **Ondertitel-granulariteit.** Eén cue per scéne, op hele seconden. Een 5s-scène met drie dialoogregels toont alles tegelijk. De per-regel MP3-duren bestaan al in de voice-service — de data om dit perfect te doen wordt nu weggegooid.

---

## TOP 20 VERBETERINGEN

### 1. Elimineer de encode-cascade: lossless intermediates
**Huidig:** 4-6 lossy H.264-generaties per pixel; de eerste (scene-encode) is nota bene de slechtste (crf 20, veryfast).
**Root cause:** elke pipeline-stap is als losse ffmpeg-run gegroeid; mp4-tussenbestanden dwingen hercompressie.
**Impact:** zacht/wasachtig eindbeeld, banding in gradients (golden hour!), detailverlies in dons-textuur — precies de assets waar Veo-geld aan is uitgegeven.
**Oplossing:** intermediates naar near-lossless: scene-encode `crf 16→12` of `-qp 0 ultrafast` (x264 lossless, ~3-4× groter, schijf is goedkoop t.o.v. Veo); audio in intermediates als PCM (`pcm_s16le` in `.mov`/`.mkv`); pas in FinalEncoder één keer naar AAC/crf 19. Waar mogelijk stappen samenvoegen (withmusic + sting is één amix).
**Complexiteit 4 · Kwaliteit 9 · Retentie 4 · Videokwaliteit 9 · CRITICAL**

### 2. Loudnorm exact één keer
**Huidig:** 3× loudnorm (concat, branded, final two-pass).
**Root cause:** elke stap wil "veilig" afleveren.
**Impact:** dynamiek-pompen, vermoeiend geluid, stem-emotie afgevlakt.
**Oplossing:** intermediates alleen `alimiter` (peak-bescherming); uitsluitend FinalEncoder normaliseert (two-pass staat er al). Eén regel verwijderen op twee plekken.
**Complexiteit 2 · Kwaliteit 8 · Retentie 5 · Videokwaliteit n.v.t. (audio 9) · CRITICAL**

### 3. Per-regel ondertitel-timing uit de voice-pipeline
**Huidig:** SubtitleBurner: één cue per scène, hele seconden.
**Root cause:** SRT wordt uit scène-narration gebouwd; de per-regel duren (line-MP3's) worden na concat weggegooid.
**Impact:** captions lopen vóór/achter op de stem; Shorts (muted-first!) lijden het hardst.
**Oplossing:** voice-service retourneert per regel `{speaker, text, startMs, durMs}` (bekend uit de line-files); SubtitleBurner schrijft cue per regel met ms-precisie. Optioneel later: ElevenLabs `with_timestamps` voor woordniveau.
**Complexiteit 4 · Kwaliteit 7 · Retentie 7 · Videokwaliteit 3 · CRITICAL**

### 4. ElevenLabs prosodie-continuïteit (`previous_text` / `next_text`)
**Huidig:** elke regel wordt geïsoleerd gesynthetiseerd; de API-velden voor context ontbreken in de client.
**Root cause:** per-line synth zonder request-stitching.
**Impact:** intonatie "reset" per regel — hoorbaar als de licht robotische cadans die auto-detectie en ouders triggert.
**Oplossing:** geef per request `previous_text`/`next_text` (de buurregels van dezelfde speaker/scène) mee; voeg een **pronunciation dictionary** toe voor kanaalwoorden (KWAK, tok-tok, Peep, garden-pebble) zodat uitspraak vastligt over 100+ afleveringen.
**Complexiteit 3 · Kwaliteit 8 · Retentie 6 · Videokwaliteit n.v.t. (voice 9) · HIGH**

### 5. Character-referentiebeelden in de videogeneratie zelf
**Huidig:** consistentie steunt op startbeeld + tekst-DNA + negative prompt. Sterk, maar tekst blijft kansrekening — vandaar de resterende drift.
**Root cause:** Veo 3.1's reference-images ("ingredients") en Seedance's multi-reference worden niet benut.
**Impact:** de laatste 5-10% drift (hoed-incident, stro-scène) die tekst-locks niet dichtkrijgen.
**Oplossing:** geef per generatie 2-3 goedgekeurde ref-stills per aanwezig character mee (de Cast-pagina beheert ze al!). Eén veld in `VertexVeoClient`/`FalSeedanceClient` + bible-pad.
**Complexiteit 5 · Kwaliteit 9 · Retentie 6 · Videokwaliteit 9 · CRITICAL**

### 6. Embedding-gebaseerde drift-detectie naast vision-QC
**Huidig:** Clip-QC (vandaag) oordeelt via Claude vision — goed maar duur/episodisch.
**Oplossing:** bereken per QC-frame een beeld-embedding en vergelijk cosine-similarity tegen de canonieke ref-stills per character; drempel = deterministische drift-score, gratis per frame, trendbaar over seizoenen ("Pip-similarity per aflevering" als dashboard-lijn). Vision-QC blijft de scheidsrechter, embeddings worden de rookmelder.
**Complexiteit 6 · Kwaliteit 7 · Retentie 4 · Videokwaliteit 7 · HIGH**

### 7. Veo-prompt: front-load actie, ablatie-test de clausules
**Huidig:** de gecompileerde prompt is ~200+ woorden; actie staat ná camera/wereld/kleur; tien locks concurreren om aandacht.
**Root cause:** elke fix voegde een clausule toe; nooit gemeten welke werken.
**Impact:** prompt-dilutie — modellen wegen vroege tokens zwaarder; de actie (waar de scène om draait) staat halverwege.
**Oplossing:** herorden: actie eerst, dan identity-locks, dan stijl; A/B vijf clausule-varianten over 10 testclips (zelfde seed waar mogelijk) en schrap wat niet meetbaar helpt. De compiler is al één testbare klasse — perfect voor een harness.
**Complexiteit 5 · Kwaliteit 7 · Retentie 4 · Videokwaliteit 8 · HIGH**

### 8. GPU-encode (NVENC) voor intermediates
**Huidig:** alle passes x264 op CPU; de chunked aanpak voegt passes toe; 180s wallclock voor één branded-encode in de ep-3-log.
**Oplossing:** `h264_nvenc -preset p5 -cq 16` voor intermediates (final mag x264 medium blijven voor maximale kwaliteit/bitrate-efficiëntie). 5-10× sneller per pass; maakt verbetering #1 (meer/zwaardere passes) gratis.
**Complexiteit 4 (GPU in container) · Kwaliteit 2 · Retentie 0 · Videokwaliteit 2 · snelheid 9 · HIGH**

### 9. Scene-audio: scripted-duur los van voice-duur
**Huidig:** scène = `-t scripted` + `apad/atrim`; te lange voice wordt afgekapt of de planning rekt stroomopwaarts (de ×1,28-drift, nu gemeten).
**Oplossing:** voice-service rapporteert echte regel-duren vóór de beeldfase; orchestrator herverdeelt scène-duren (cap per fase) vóór Veo draait — dan klopt beeldritme mét stem in plaats van erachteraan. De duurgate van vandaag levert de meetdata al.
**Complexiteit 6 · Kwaliteit 6 · Retentie 7 · Videokwaliteit 5 · HIGH**

### 10. Retentie-loop sluiten: drop-points → scène-priors
**Huidig:** AnalyticsPoller haalt per-scène retentie op; ArcSelector weegt arcs; maar scène-duur/fase-priors leren nog niet automatisch mee.
**Oplossing:** map drop-spikes naar fase+scènetype; voed als prior in de episode-structure (bijv. "development-scènes >6s verliezen 2× zoveel kijkers") en in de transition-keuze. Alles ligt er al (retentionScenesJson, EpisodeStructure) — het is een join + gewichtjes.
**Complexiteit 6 · Kwaliteit 5 · Retentie 9 · Videokwaliteit 2 · HIGH**

### 11. Thumbnail: squint-test-scorer + contrast-pass
**Huidig:** layout-rotatie + performance-loop + cast-stills (sterk fundament); geen automatische CTR-proxy; ep-2-audit klaagde al over contrast.
**Oplossing:** (a) vaste na-bewerking: +10-15% lokaal contrast op het gezicht, saturatie-boost, vignet; (b) vision-scorecard per variant: "op 120px breed — zie je in 1s een gezicht + één raadsel?" (zelfde patroon als SceneImageQc); laagste score wordt nooit default.
**Complexiteit 4 · Kwaliteit 6 · Retentie 3 (CTR 8) · Videokwaliteit n.v.t. · HIGH**

### 12. Model-routing uitbreiden tot benchmark-gedreven matrix
**Huidig:** routing per sceneType (hero→Veo 3.1, standard→lite, Seedance als tweede provider) — goed. Maar de routing is opinie, geen meting.
**Oplossing:** maandelijkse benchmark-job: zelfde 5 stills + prompts door Veo 3.1/lite, Seedance, (adapter-stub voor Kling/Hailuo/Luma) → clip-QC-score + kosten per sceneType in een tabel; routing-yaml wordt door data onderbouwd. Aanbevolen startverdeling op basis van publieke sterktes: **hero/emotie → Veo 3.1** (beste karakter-act), **fysieke comedy/beweging → Seedance** (beweging/fysica), **stills-achtige establishing → lite**.
**Complexiteit 5 · Kwaliteit 6 · Retentie 3 · Videokwaliteit 7 · MEDIUM**

### 13. Frame-chaining uitbreiden met kleur-match
**Huidig (sinds vandaag):** laatste frame → startbeeld. Maar Veo shift kleurtemperatuur per generatie licht; geketende clips kunnen stapelen.
**Oplossing:** na elke chain-link een `colorbalance`-match van clip N+1 op het histogram van clip N's laatste frame (ffmpeg `colormap`/3D-LUT of simpele midtone-match) in de chunk-pass.
**Complexiteit 6 · Kwaliteit 5 · Retentie 3 · Videokwaliteit 6 · MEDIUM**

### 14. Muziek-stems i.p.v. één track + sidechain
**Huidig:** één mp3, -12dB, sidechain-duck, gaussian climax-swell — drie keer slimmer dan gemiddeld. Volgende stap:
**Oplossing:** per track een 2-stem-variant (percussie / pad): percussie wegfilteren tijdens dialoog i.p.v. alles ducken, climax-swell op beide. Hoorbaar "gemixt" i.p.v. "geduckt".
**Complexiteit 5 · Kwaliteit 5 · Retentie 4 · Videokwaliteit n.v.t. · MEDIUM**

### 15. Prometheus-metrics + per-stage tracing
**Huidig:** actuator aanwezig, geen registry; alles leeft in logs; kosten nu wel op de job (vandaag).
**Oplossing:** micrometer-prometheus in alle services; counters/timers per stage (veo_clip_seconds, ffmpeg_pass_seconds, qc_fail_total per categorie); Grafana-dashboard "kosten & kwaliteit per aflevering". De QcInsights-categorieën zijn al perfecte labels.
**Complexiteit 4 · Kwaliteit 2 · Retentie 0 · betrouwbaarheid 8 · MEDIUM**

### 16. ffmpeg-concurrency-governor
**Huidig:** scene-encodes parallel op thread-pool; OOM van ep 3 bewees dat de host de som niet altijd aankan; chunking verlaagt het risico maar bounded is het niet.
**Oplossing:** één semafoor over álle ffmpeg-spawns in de service (configurabel, default = cores/4), plus `-threads`-cap per proces en container-memory-limits in compose. Eén governor-klasse om FfmpegRunner heen.
**Complexiteit 3 · Kwaliteit 1 · Retentie 0 · betrouwbaarheid 8 · MEDIUM**

### 17. Render-regressietest met golden frames
**Huidig:** geen geautomatiseerde bewaking dat een refactor (zoals die van vandaag) het beeld niet verandert.
**Oplossing:** CI-job: 4 mini-clips door de volledige assembly; SSIM tegen golden output > 0,98 = pass. Vangt elke toekomstige filtergraph-regressie in minuten i.p.v. in een audit.
**Complexiteit 5 · Kwaliteit 4 · Retentie 0 · betrouwbaarheid 8 · MEDIUM**

### 18. Verhaal: micro-conflict + dual-audience-laag structureel
**Huidig:** validators borgen structuur/dal/stilte-beat; ep 3 bewees de formule. Wat ontbreekt structureel: één klein menings­verschil tussen vrienden (Bluey-motor) en één ouder-knipoog per aflevering.
**Oplossing:** twee promptregels + ComedyValidator-check ("bevat het script één vriendschaps-frictie die vriendelijk wordt opgelost?").
**Complexiteit 2 · Kwaliteit 6 · Retentie 6 · Videokwaliteit 0 · MEDIUM**

### 19. Workdir → object storage + queue (schaalpad)
**Huidig:** gedeeld volume + single-host + geen broker — gedocumenteerde keuze, prima tot ~10 video's/dag.
**Oplossing (pas bij schaal):** assets naar S3/GCS met presigned URLs, stages via een queue (SQS/Rabbit), assembly horizontaal. De service-grenzen zijn er al op gebouwd; dit is infra, geen redesign.
**Complexiteit 8 · Kwaliteit 0 · Retentie 0 · schaal 9 · LOW (nu) / CRITICAL (bij >10/dag)**

### 20. Seizoens-bible met versionering
**Huidig:** één channel.yml, hot-edited (Cast-editor schrijft erin) — geen historie; "welke bible rendere ep 12?" is onbeantwoordbaar.
**Oplossing:** bible-snapshot (hash + kopie) per job opslaan; diff-weergave in de UI. Essentieel voor 100+ afleveringen consistentie-forensiek.
**Complexiteit 3 · Kwaliteit 4 · Retentie 1 · Videokwaliteit 3 · MEDIUM**

---

## QUICK WINS (≤1 dag)

1. **#2 loudnorm-dedup** — 2 regels verwijderen, hoorbaar resultaat.
2. **Scene-encode crf 20→16 + preset veryfast→faster** — één constante; de slechtste schakel weg.
3. **#4 ElevenLabs `previous_text`/`next_text`** — twee velden in de request-body.
4. **#16 ffmpeg-semafoor** — één wrapper-klasse.
5. **#18 micro-conflict-promptregel** — twee zinnen in PromptBuilder.
6. **Pronunciation-dictionary voor KWAK/tok-tok** — vóór ep 4 belangrijk (nieuwe castlid-woorden).
7. **Thumbnail contrast-pass** — één ffmpeg/AWT-stap in de thumbnail-service.

## HIGH-ROI (meeste kwaliteit per uur)

| # | Wat | Uren | Winst |
|---|---|---|---|
| 2 | Loudnorm één keer | 1 | hele audio-mix ademt weer |
| 4 | ElevenLabs-continuïteit | 3 | grootste stap richting "echte" stemmen |
| 3 | Per-regel captions | 6 | sync + Shorts-kwaliteit + toegankelijkheid |
| 5 | Ref-images in videogeneratie | 8 | de resterende drift-staart |
| 1 | Lossless intermediates | 8 | structureel premium beeld |
| 11 | Thumbnail-scorer | 6 | CTR is de goedkoopste groei |

## LANGE TERMIJN — naar een wereldklasse-platform (1000+ afleveringen)

**Fase 1 (nu-3 mnd): kwaliteit dichttimmeren.** Quick wins + #1/#3/#5. Doel: elke aflevering visueel/auditief niet te onderscheiden van een handmatige edit. De enforcement-laag van vandaag (locks, clip-QC, chaining) is het fundament; ref-image-conditioning maakt hem af.

**Fase 2 (3-6 mnd): de lerende studio.** Sluit alle loops die half bestaan: retentie → scène-priors (#10), QC-patterns → automatische bible-PR-voorstellen, thumbnail-CTR → layout-gewichten (bestaat), benchmark-matrix → model-routing (#12). Het systeem wordt elke aflevering meetbaar beter zonder menselijke analyse — de mens beoordeelt alleen nog afwijkingen (de gates zijn daar al voor gebouwd).

**Fase 3 (6-12 mnd): industrialisatie.** Object storage + queue (#19), GPU-encode-pool (#8), bible-versionering per seizoen (#20), multi-kanaal: dezelfde pijplijn met een tweede bible (ander IP) bewijst dat het een platform is en geen kanaal. Karakter-consistentie over seizoenen wordt geborgd door de combinatie canonieke ref-sets + embedding-trendbewaking (#6) + bible-snapshots — drie onafhankelijke ankers, zodat geen enkele modelwissel (Veo 4, Seedance 3…) het IP kan laten driften.

**De eerlijke conclusie:** de verhaal- en systeemkant is verder dan die van menig gefinancierde studio; de fysieke signaalketen (encodes, loudness, caption-timing, prosodie) is het achtergebleven kwart. Dat kwart is goedkoop te fixen — vrijwel alles in de Quick Wins/High-ROI-lijst is dagen werk, geen maanden — en het is precies het kwart dat kijkers onbewust voelen als het verschil tussen "AI-filmpje" en "mijn kind z'n lievelingsserie".
