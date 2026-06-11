# Rondetafel — Tiny Chicken World pipeline naar studioniveau

Drie experts beoordelen de *feitelijke* codebase (Spring Boot microservices, Veo image-to-video, FFmpeg-assembly, bible-driven). Verwijzingen zijn naar echte bestanden/regels. Geen algemeenheden.

Deelnemers:
- **A — Pixar/Disney animatie- & videospecialist** (visuele consistentie, timing, emotie, lip-sync)
- **V — Veo SDK-specialist** (`com.google.genai`, Vertex AI, prompt-anchoring, kosten, quota)
- **J — Senior Java-architect** (pipeline-robuustheid, async, error handling, tests, onderhoudbaarheid)

---

## 1. Eerste indrukken

### A — Animatie
Het fundament is sterker dan ik bij "AI-kanaal" verwacht: de Veo-prompt-compiler (`PipelineOrchestrator.compileVeoPrompt`, regel 2285) is bijna een mini-shotlist — camera-bible per fase, color-script per fase, Shot-DNA (goal/emotion/motionSpeed), anticipatie-telegraph geschaald op intensiteit, en een volledige DNA-identiteitslock. Dat is regie, niet hopen.

Drie dingen breken het studio-illusie meteen:
1. **Geen echte lip-sync.** De prompt zegt letterlijk *"beak moving while speaking"*, maar de stem komt los van ElevenLabs en Veo-audio staat uit. De snavel beweegt dus willekeurig t.o.v. de fonemen. Voor kindercontent is dat de #1 "het is nep"-tell.
2. **Continuïteit stopt bij de scènegrens.** Elke scène is een losse clip van max 8s, aan elkaar gecrossfade. Er is geen match-cut/eyeline-uitlijning tussen scène N en N+1 (staat ook zo in de backlog). Een Pixar-scène *vloeit*; deze *snijdt*.
3. **Start- én eind-frame komen allebei uit stochastische image-gen.** `lastFrame(endImage)` laat Veo van still A naar still B interpoleren — maar als A en B niet exact hetzelfde personage zijn, interpoleert Veo *tussen twee licht verschillende kippen* = precies de morphing die je probeert te voorkomen.

### V — Veo
Technisch netjes opgezet: directe Vertex-SDK i.p.v. MCP-proxy, bounded parallelism (`Semaphore` + fixed pool in `ClipGenerationService`), cost-budget met pre-emptive cap, quota→fallback-retry, MP4-validatie via ffprobe, en GCS-upload van anchors. Dit is verder dan de meeste hobby-pipelines.

Maar er zitten harde risico's in:
1. **`cfgBuilder.lastFrame(endImage)` is ongeverifieerd** — de code zegt het zelf (comment regel ~58 in `VertexVeoClient`). In `com.google.genai` heet dit afhankelijk van de versie anders, en op verschillende Veo-modellen wordt last-frame/interpolation niet ondersteund. Eén verkeerde versie en élke Veo-scène valt terug op Ken Burns zonder dat iemand het merkt (het is een nette `FALLBACK`).
2. **Model-IDs wijzen naar `-generate-preview`** (`ModelRouter.normaliseModelId`). Preview-modellen vereisen Model Garden-toegang en kunnen 404'en. De code documenteert dit, maar het betekent: zonder expliciete enablement draait *alles* op de GA-fallback `veo-3.0-fast-generate-001`.
3. **Kosten kunnen stil ontsporen.** `CostCalculator` valt bij een onbekende model-id terug op €0,20/s zónder te waarschuwen. Eén alias die niet in `veo.rates` staat → de budget-cap rekent met een gokgetal.

### J — Java/architectuur
Consistente stijl over 8 services, schone bible-driven config, `@Async` per stage met een review-gate-state-machine. Solide skelet. Maar drie structurele zwakheden maken het fragiel bij de eerste tegenslag:

1. **WebClient-calls hebben geen timeout en geen retry.** `ImageServiceClient.generate(...)` doet `.retrieve().bodyToMono(JsonNode.class).block()` — punt. Geen connect-timeout, geen response-timeout, geen `.block(Duration)`. Een trage of vastgelopen image-service laat de orchestrator-thread *oneindig* hangen. Dit geldt voor alle clients.
2. **Geen crash-recovery.** Er is geen `ApplicationReadyEvent`-handler die jobs in `*_GENERATING` oppakt na een herstart (architecture.md noemt dit zelf een TODO). Herstart je mid-job, dan hangt die job voorgoed — de `@Async`-thread is weg, de DB-status blijft staan.
3. **`PipelineOrchestrator` = 2520 regels = god-class.** Alles zit erin: state machine, prompt-compilatie, Veo-orchestratie, QC, asset-merge, color-script. En het is vrijwel **untested**: 3 unit-tests in de hele repo (`SimHashTest`, `ThumbnailScorerTest`, `SfxComposerEmotionTest`), nul voor de orchestrator. De build brak deze week op een lambda-`final`-fout op regel 2250 — een compiler ving dat; geen test had het gevangen.

---

## 2. Discussie — kritische vragen & trade-offs

**J → V:** Je leunt op `FALLBACK` als veiligheidsnet, maar dat is precies waarom een kapotte `lastFrame` onzichtbaar is. Hoe weet je of Veo écht draait of dat élke scène stil naar Ken Burns valt?
**V:** Klopt, dat is een blinde vlek. De `ClipResult` bevat een `reason` ("CORRUPT_OUTPUT", "QUOTA", "RUNTIME:..."), maar niemand aggregeert dat. Je hebt een **Veo-success-rate-metric per job** nodig: als >50% van de scènes `FALLBACK` is, is dat geen graceful degradation, dat is een outage. Nu zie je een groene job met €0,00 Veo-kosten en denkt iedereen dat het goed ging.

**A → V:** Ik wil eind-frames voor gerichte beweging, maar jij zegt dat A→B-interpolatie tussen twee verschillende stills juist morpht. Wat wint?
**V:** Trade-off. `lastFrame` is goud *als* start en eind hetzelfde personage-anchor delen — en dat doen ze hier deels (`generateEndStills` hergebruikt dezelfde anchors). Maar image-gen blijft stochastisch. Veiliger pad: **end-frame alleen op hero/climax-scènes** (waar de regie het waard is) en daar de end-still genereren met *exact dezelfde seed + anchors* als de start, alleen pose verschoven. Op bulk-scènes: start-only, korter (4s), goedkoper. Dat is precies waar je `sceneTypeRouting` voor hebt.

**A → J:** Lip-sync is mijn grootste zichtbare gap. Realistisch in deze architectuur?
**J:** Niet met Veo-image-to-video alleen — Veo krijgt de dialoog-audio niet als sturing. Twee opties: (a) een aparte lip-sync-pass (bv. een talking-head/wav2lip-achtig model gevoed door de ElevenLabs-MP3 per scène) ná Veo, of (b) Veo 3.1's *native* audio+dialoog gebruiken i.p.v. ElevenLabs. (b) breekt je stemconsistentie (Veo kiest elke keer een andere stem). (a) is een nieuwe service en zwaar. Voor nu: **accepteer dat het geen lip-sync is en maak de monden minder prominent** — verklein de "beak moving while speaking"-claim in de prompt naar gesloten/subtiele snavelbewegingen, zodat de mismatch niet opvalt. Goedkope winst, geen nieuwe service.
**A:** Mee eens. Een verkeerd-getimede open snavel is erger dan een rustige snavel.

**V → J:** Je `pipelineExecutor` is core 2 / max 4 / queue 20, en stages roepen elkaar aan via de `@Async`-proxy terwijl ze blokkeren op `.block()`. Bij meerdere parallelle jobs zit je pool zo vol met *wachtende* threads.
**J:** Terecht. Zolang het 1 video per paar minuten is (de huidige eis) gebeurt er niks. Maar het is een latente deadlock-bron: elke blokkerende WebClient-call houdt een pool-thread vast. Zodra je 3-4 jobs tegelijk draait + de Veo-stage (die minuten duurt) zit je vast. Mitigatie is goedkoop: timeouts (zodat threads vrijkomen) + de Veo-stage op een *eigen* executor i.p.v. de gedeelde pool.

**J → A:** Match-cuts/eyeline noem je studio-niveau, maar image-gen lijnt niets uit. Is de ROI er wel, of verbergen de crossfades het al?
**A:** Eerlijk: crossfades + fase-gestuurde overgangen verbergen 80% van de naden al. Echte pose-uitlijning is duur en onbetrouwbaar met stochastische beelden. **Lagere ROI dan lip-sync en kleur-continuïteit.** Ik haal het van de prioriteitenlijst af tot er pose-detectie is.

**Consensus over trade-offs:** kwaliteit-per-euro zit 'm niet in méér Veo, maar in (1) *weten* of Veo draait, (2) Veo gericht inzetten waar regie telt, en (3) de pipeline robuust genoeg maken dat één trage call geen job gijzelt.

---

## 3. Synergie tussen de domeinen

- **Veo-observability is tegelijk een Java- én een regie-feature.** Eén `metrics`-jsonb-veld op `video_jobs` (Java) dat per job de Veo-success-rate + kosten logt, geeft V de outage-detectie én A de data om te zien op welke scène-types Veo visueel loont. (J + V + A)
- **End-frame-discipline koppelt regie aan kosten.** A wil end-frames waar de beat het waard is; V wil kosten beheersen; `sceneTypeRouting` (al aanwezig) is de gedeelde knop: end-frame + 1080p alleen op hero/climax, start-only 720p 4s op bulk. Eén config, drie doelen. (A + V)
- **Prompt-compiler als één bron is al synergie — bewaak het met een test.** `compileVeoPrompt` en `PromptComposer.dnaLine` delen bewust dezelfde DNA-velden (lock-step). Dat contract is nu alleen een comment. Eén unit-test die verifieert dat beide kanten dezelfde DNA-clause produceren maakt de synergie hard. (A + J)
- **Lip-sync-mismatch verzachten is gratis en cross-domein.** Prompt-tekst aanpassen (A) kost niets (J), en haalt de grootste "nep"-tell weg zonder nieuwe service (V hoeft niks te doen). (A + J)

---

## 4. Gezamenlijk verbeterrapport

Geordend naar impact (kwaliteit + robuustheid per eenheid inspanning).

> **Implementatiestatus (bijgewerkt 2026-06-09).** Alle negen prioriteiten zijn doorgevoerd in de code (nog niet gecompileerd/getest op een machine met Maven — draai `mvn -B package` + `mvn test` om groen te bevestigen).
>
> | # | Status | Geïmplementeerd als |
> |---|---|---|
> | P1 | ✅ | `runVeoStage`: aggregeert OK vs FALLBACK/FAILED, logt `fallbackRatio`, `ERROR` bij >50%. (DB-persist van een `metrics`-veld bewust overgeslagen — logging volstaat.) |
> | P2 | ✅ verificatie + retry | `lastFrame(Image)` bevestigd aanwezig in google-genai 1.15.0 (officiële bron). `VertexVeoClient` retryt nu **start-only** als een model het end-frame weigert. *Live betaalde smoke-test blijft jouw stap.* |
> | P3a | ✅ | `WebClientConfig`: connect-timeout 10s + response-timeout **20 min** (i.p.v. de eerder voorgestelde 6 — die zou legitieme Veo-runs van 5–15 min breken). |
> | P3b | ✅ | `AsyncConfig.veoExecutor` (eigen pool) + `runVeoStage` op `@Async("veoExecutor")`. |
> | P4 | ✅ | Aparte `JobRecovery`-component (`ApplicationReadyEvent`) + `PipelineOrchestrator.resumeAfterRestart(...)` + `VideoJobRepository.findByStatusIn(...)`. In-flight set = PENDING, *_GENERATING, ASSEMBLING, UPLOADING. |
> | P5 | ✅ | `compileVeoPrompt`: micro-motion-regel afgezwakt naar grotendeels gesloten snavel, expliciet "do NOT lip-sync". |
> | P6 | ✅ Gemini; Replicate = follow-up | `generateEndStills`: end-frames alleen op hero/climax + identiteits-lock-prompt + `cameraFraming` doorgegeven. Identiteit is via anchors gelockt (Gemini). Seed-lock voor de Replicate-provider vereist een `seed`-veld op de image-DTO → openstaande vervolgstap. |
> | P7 | ✅ | `ModelRouterTest` + `CostCalculatorTest` (routing/cost-cap/alias/conservatieve default). Logica los geverifieerd; draai `mvn test`. |
> | P8 | ✅ | `CostCalculator`: onbekende rate → `WARN` + conservatieve €0,50/s i.p.v. stil €0,20. |

### Top-prioriteit

#### P1 — ✅ GEBOUWD — Veo-success-rate zichtbaar maken (anders is alles blind)
- **Probleem:** Een job kan groen + €0,00 zijn terwijl élke scène stil naar Ken Burns viel (kapotte `lastFrame`, preview-model 404, quota). De `FALLBACK`-reasons in `ClipResult` worden nergens geaggregeerd of gealarmeerd.
- **Oplossing:** Aggregeer per job in de orchestrator na `runVeoStage`: aantal `OK` vs `FALLBACK`/`FAILED` + totale kosten → log + persist in een `video_jobs.metrics` jsonb-veld (kleine migratie). Waarschuw als `fallbackRatio > 0.5`.
  ```java
  // na clips = videoGenClient.generate(...)
  long ok = clips.stream().filter(c -> "OK".equals(c.path("status").asText())).count();
  long total = clips.size();
  double fallbackRatio = total == 0 ? 0 : (total - ok) / (double) total;
  log.warn("Veo job {}: {}/{} OK, fallbackRatio={}, costEur={}",
           jobId, ok, total, fallbackRatio, totalCostEur);
  if (fallbackRatio > 0.5)
      log.error("Veo DEGRADED for job {} — >50% scenes fell back to Ken Burns", jobId);
  ```
- **Expert(s):** V + J
- **Inspanning:** S (½ dag, + mini-migratie)

#### P2 — ✅ GEBOUWD (verificatie + start-only retry; live smoke-test rest) — `lastFrame` SDK-call verifiëren
- **Probleem:** `VertexVeoClient` roept `cfgBuilder.lastFrame(endImage)` aan; de code zelf markeert dit als ongeverifieerd. Verkeerde `com.google.genai`-versie of niet-ondersteund model → exception → alle end-frame-scènes vallen terug.
- **Oplossing:** Bevestig de exacte methodenaam in jullie pinned SDK-versie en draai één gerichte smoke-test (hero-scène met end-frame) met P1-logging aan. Check tegelijk of het gekozen model last-frame ondersteunt.
  ```bash
  # welke versie + bestaat de methode?
  mvn -q dependency:tree -pl services/video-generation-service | grep genai
  javap -cp <genai.jar> com.google.genai.types.GenerateVideosConfig$Builder | grep -i frame
  ```
  Zo niet aanwezig: alias in de bible op `veo3` (GA) zetten of de regel aanpassen naar de juiste setter.
- **Expert(s):** V
- **Inspanning:** S (paar uur incl. één betaalde testrun)

#### P3 — ✅ GEBOUWD (P3a + P3b) — WebClient-timeouts + Veo-stage op eigen executor (robuustheid)
- **Probleem:** Alle orchestrator-clients doen `.block()` zonder timeout → één trage downstream-service hangt een pipeline-thread oneindig. Gecombineerd met de kleine gedeelde `pipelineExecutor` (max 4) en blokkerende stages is dit een latente deadlock bij parallelle jobs.
- **Oplossing (a):** globale timeout-customizer (komt door `WebClient.Builder` in elke client):
  ```java
  @Bean
  org.springframework.boot.web.reactive.function.client.WebClientCustomizer timeouts() {
      reactor.netty.http.client.HttpClient http = reactor.netty.http.client.HttpClient.create()
          .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 10_000)
          .responseTimeout(java.time.Duration.ofMinutes(6)); // image/Veo stages zijn traag
      return b -> b.clientConnector(
          new org.springframework.http.client.reactive.ReactorClientHttpConnector(http));
  }
  ```
  **Oplossing (b):** geef de Veo-stage een eigen `Executor` (los van `pipelineExecutor`) zodat een minutenlange Veo-call de korte stages niet verhongert.
- **Expert(s):** J
- **Inspanning:** S–M (1 dag)

#### P4 — ✅ GEBOUWD — Crash-recovery voor in-flight jobs
- **Probleem:** Geen herstart-herstel. Een herstart mid-job laat de job voorgoed in `*_GENERATING` hangen.
- **Oplossing:** `ApplicationReadyEvent`-listener die jobs in niet-terminale statussen her-routeert via de bestaande status-switch (dezelfde die `*_REVIEW_PENDING` afhandelt, regel ~167).
  ```java
  @Component @RequiredArgsConstructor @Slf4j
  class JobRecovery {
      private final VideoJobRepository repo;
      private final PipelineOrchestrator orch;
      private static final List<JobStatus> IN_FLIGHT = List.of(
          JobStatus.SCRIPT_GENERATING, JobStatus.ASSETS_GENERATING, JobStatus.ASSEMBLING);
      @org.springframework.context.event.EventListener(
          org.springframework.boot.context.event.ApplicationReadyEvent.class)
      void resume() {
          for (VideoJob j : repo.findByStatusIn(IN_FLIGHT)) {
              log.warn("Resuming job {} stuck in {} after restart", j.getId(), j.getStatus());
              orch.resumeFrom(j.getId(), j.getStatus()); // her-trigger de juiste @Async stage
          }
      }
  }
  ```
- **Expert(s):** J
- **Inspanning:** M (1 dag — vereist een idempotente `resumeFrom`)

#### P5 — ✅ GEBOUWD — Lip-sync-mismatch verzachten (grootste zichtbare "nep"-tell, gratis fix)
- **Probleem:** Prompt vraagt *"beak moving while speaking"* maar er is geen foneem-sturing → snavel beweegt willekeurig t.o.v. de stem.
- **Oplossing:** In `compileVeoPrompt` de micro-motion-regel afzwakken: subtiele, grotendeels gesloten snavel met af en toe kleine beweging i.p.v. continu "praten". Een rustige snavel leest beter dan een verkeerd-getimede open snavel.
  ```text
  // was: "...beak moving while speaking..."
  // wordt:
  "...with only small, soft beak movements and natural blinking (do NOT lip-sync
   words — keep the beak mostly closed with subtle motion)..."
  ```
- **Expert(s):** A
- **Inspanning:** XS (10 min)

#### P6 — ✅ GEBOUWD (Gemini; Replicate seed-lock = follow-up) — End-frame-discipline: gericht i.p.v. overal
- **Probleem:** End-frame-interpolatie tussen twee stochastische stills kan juist morphen; het is duur op bulk-scènes met weinig regie-winst.
- **Oplossing:** `lastFrame` alleen op hero/climax (via bestaande `sceneTypeRouting`); end-still daar genereren met **dezelfde seed + anchors** als de start (alleen pose verschoven) zodat A→B dezelfde kip blijft. Bulk-scènes: start-only, 4s, 720p.
- **Expert(s):** A + V
- **Inspanning:** M (1–2 dagen; raakt `generateEndStills` + routing)

#### P7 — ✅ GEBOUWD (ModelRouter + CostCalculator; DNA-contracttest = follow-up) — Tests om de prompt-/kosten-/routing-kern
- **Probleem:** 2520-regelige orchestrator + Veo-kosten/routing zijn vrijwel untested; de build brak deze week op een trivialiteit (lambda-`final`, regel 2250) die geen test ving.
- **Oplossing:** Begin smal en hoog-waarde, geen 100%-jacht:
  - `ModelRouterTest` — cost-cap downshift, alias→Vertex-id, hero→1080p.
  - `CostCalculatorTest` — onbekende model-id moet **luid falen of expliciet markeren**, niet stil €0,20 (zie P8).
  - Contract-test: `compileVeoPrompt` en `PromptComposer.dnaLine` produceren dezelfde DNA-clause (bewaakt de lock-step).
- **Expert(s):** J (+ A voor de DNA-assert)
- **Inspanning:** M (1–2 dagen)

#### P8 — ✅ GEBOUWD — `CostCalculator` luid laten falen bij onbekende rate
- **Probleem:** Onbekende model-id → stil €0,20/s → budget-cap rekent met een gokgetal.
- **Oplossing:** Log een `WARN` (of gooi in test) en kies een *conservatief hoge* default, niet een gemiddelde, zodat de cap eerder beschermt dan te laat.
  ```java
  if (rate == null) {
      log.warn("No Veo rate for model '{}' — using conservative €0.50/s; add it to veo.rates", key);
      perSecond = 0.50; // hoog i.p.v. 0.20: cap beschermt eerder
  }
  ```
- **Expert(s):** V + J
- **Inspanning:** XS (15 min)

---

### Quick wins vs. lange-termijn

**Quick wins (≤1 dag, hoge ROI):** — ✅ allemaal gebouwd
- ✅ P1 Veo-success-rate-logging
- ✅ P2 `lastFrame` verifiëren (verificatie + retry gebouwd; live smoke-test rest)
- ✅ P5 lip-sync-prompt verzachten
- ✅ P8 CostCalculator luid falen
- ✅ P3a WebClient-timeouts

**Mid-term (dagen–weken):** — ✅ allemaal gebouwd
- ✅ P3b Veo-stage eigen executor
- ✅ P4 crash-recovery
- ✅ P6 end-frame-discipline (Gemini; Replicate seed-lock = follow-up)
- ✅ P7 kern-tests (ModelRouter + CostCalculator)

**Lange-termijn / world-class (weken+, deels geblokkeerd):**
- Echte lip-sync-pass (nieuwe service, gevoed door ElevenLabs-MP3) — pas ná de audiolaag.
- Match-cuts/eyeline via pose-detectie — **laagste ROI**, crossfades verbergen de naden al; pas oppakken bij pose-detectie.
- `PipelineOrchestrator` opsplitsen (god-class → stage-objecten) — doe dit *incrementeel terwijl je P7-tests schrijft*, niet als big-bang refactor.

---

### Concrete next steps (volgorde) — bijgewerkt

P1 t/m P8 zijn in de code doorgevoerd. Wat nu rest:

1. **Compileren + testen op een machine met Maven:** `mvn -B package` (orchestrator + video-generation-service) en `mvn test` (de twee nieuwe testklassen). De wijzigingen zijn statisch geverifieerd, niet gecompileerd.
2. **P2 — live smoke-test:** één hero-scène met end-frame draaien op het echte GCP-project en de P1-`fallbackRatio` aflezen. Bevestigt of het gekozen Veo-model `lastFrame` accepteert (zo niet, kickt de nieuwe start-only retry in).
3. **P6 — Replicate seed-lock** (alleen nodig als je op de Replicate-provider draait): een `seed`-veld op de image-`SceneVisual` DTO zodat de end-still dezelfde seed als de start krijgt. Voor de default Gemini-provider niet nodig (anchors locken de identiteit al).
4. **P7 — DNA-contracttest** als follow-up: verifieer dat `compileVeoPrompt` en `PromptComposer.dnaLine` dezelfde DNA-clause produceren (bewaakt de lock-step die nu alleen in comments staat).
5. **God-class:** `PipelineOrchestrator` (2500+ regels) incrementeel opsplitsen in stage-objecten, terwijl je per stage tests toevoegt — geen big-bang refactor.

> Rode draad: de codebase is verder dan z'n testdekking en observability suggereren. De grootste sprong is niet *meer* AI — het is *zien* wat de AI doet (P1/P2) en voorkomen dat één trage call een job gijzelt (P3/P4). Daarna pas de regie-finesse (P6) en het echte studio-plafond (lip-sync).
