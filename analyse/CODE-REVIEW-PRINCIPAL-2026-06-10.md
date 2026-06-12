# Principal Engineer Deep Code Review — Tiny Chicken World Pipeline

**Datum:** 2026-06-10 · **Reviewer-rol:** Principal Java Engineer / Architect / Security / DevOps
**Scope:** volledige monorepo `youtube-channel` — 8 Spring Boot-services, statisch dashboard, Docker Compose, Flyway, infra-scripts.
**Maatstaf (zoals gevraagd):** enterprise-grade, "Fortune 500, miljoenen transacties/dag". Daarnaast geef ik per onderdeel de eerlijke duiding voor de *werkelijke* context: een single-operator AI-videostudio op één machine. Beide brillen zijn nodig — anders is het rapport streng maar nutteloos.

---

## Samenvattende scores

| Categorie | Score | Eén zin |
|---|---|---|
| Architectuur | **6,5/10** | Heldere stage-decompositie, maar de orchestrator is een god-service en het domeinmodel is stringly-typed JSON |
| Codekwaliteit | **6/10** | Uitstekende commentaarcultuur en naamgeving; god class, `Map<String,Object>`-obsessie, gedupliceerde runners |
| Java best practices | **7/10** | Records, switch-expressies, text blocks; geen sealed types, geen virtual threads waar ze perfect passen |
| Spring Boot | **6/10** | Constructor-injectie en bewuste tx-grenzen; mutaties via GET, self-injection-proxy's, lege global error handling |
| Performance | **6/10** | Prima voor batch-werk; JSON-herparsing per poll, full re-render als enige hersteloperatie (net verzacht) |
| Security | **3/10** | Geen enkele authenticatie, path-traversal-gaten, plaintext OAuth-creds, live keys in `.env` |
| Database | **6,5/10** | Gedisciplineerde Flyway-historie en indexen; TEXT-JSON-blobs, geen optimistic locking |
| DevOps | **4/10** | Nette multi-stage Dockerfiles; geen CI, geen healthchecks, root-containers, geen resource-limits |
| Testing | **3,5/10** | 6 goede unit-tests, maar 0 tests op de drie kritiekste services; golden-test.py is de echte regressiebescherming |
| **Production readiness (internet-facing enterprise)** | **30/100** | Niet deploybaar buiten een vertrouwd netwerk zonder auth + hardening |
| **Production readiness (huidige context: lokale single-operator studio)** | **62/100** | Werkt, herstelt, en is observeerbaar genoeg voor één operator; de risico's zijn bekend en begrensd |

**Geschatte technische schuld:** 5,5/10 (matig-hoog, maar geconcentreerd: ~70% van de schuld zit in één klasse en één ontbrekende laag — tests).

---

## 1. Architectuurreview — 6,5/10

### Wat goed is
- **Decompositie langs pipeline-stages** (script → voice → image → videogen → assembly → thumbnail → upload) is de juiste snijlijn: elke service heeft één duidelijke verantwoordelijkheid, eigen Dockerfile, eigen config. Cohesie binnen services is hoog.
- **Uniforme stack zonder drift**: alle 8 services op Spring Boot 3.3.4 / Java 21, één aggregator-pom. Zeldzaam netjes.
- **Resumable state + review gates**: de pipeline kan op elk gate pauzeren en hervatten zonder upstream-werk te herhalen (assembly_scenes wordt per stage herladen). Dit is een volwassen ontwerpbeslissing die veel geld bespaart.
- **De "bible" als single source of truth** (channel.yml) voor cast/locaties/routing/kosten is een sterk patroon: gedragsverandering zonder rebuild.
- **Provider-adapters in image-service** (OpenAI/Replicate/Gemini) en videogen (Veo/Seedance via `ModelRouter`) zijn schoolvoorbeelden van het ports-and-adapters-idee — uitbreidbaar gebleken (Seedance is er in één dag naast gezet).
- **Dependency-richting klopt**: orchestrator → services, nooit andersom; services kennen elkaar niet.

### Architectuursmells en risico's

**A1 — God-service orchestrator (HIGH).** `PipelineOrchestrator.java` is **2.264 regels** met 19 `@Transactional`-methodes en doet: stage-machine, retry-logica, QC-orkestratie, scene-mutaties, Veo-prompt-aanlevering, kostenbewaking, reroll-coördinatie én persistentie-details. Dit is de plek waar elke nieuwe feature landt (zichtbaar in de groei) en waar elke regressie zal ontstaan. SRP is hier structureel geschonden.
*Aanpak:* extraheer per stage een `*StageService` (ScriptStage, AssetStage, MotionStage, AssemblyStage, PublishStage) met de orchestrator als dunne state-machine erboven. Dat kan incrementeel: één stage per refactorronde, de `self.`-proxy-aanroepen zijn al natuurlijke naden.

**A2 — Stringly-typed domeinkern (HIGH).** Het centrale domeinobject — de scène — bestaat niet als type. Overal reizen `List<Map<String,Object>>` met impliciete keys (`"seq"`, `"clipPath"`, `"visualDesc"`, …) en casts als `((Number) a.get("seq")).intValue()`. De facto is het JSON-schema van assembly_scenes het belangrijkste contract van het systeem, en het staat nergens.
*Aanpak:* één `SceneDto`-record in een gedeelde module (of per service gegenereerd uit een JSON-schema). Dit is de hoogste-ROI-refactor van de hele codebase: het maakt 30+ foutklassen onmogelijk tijdens compileren.

**A3 — Shared `/workdir`-volume als verborgen koppelvlak (MEDIUM).** Services wisselen bestands*paden* uit, geen content. Dat werkt op één host, maar betekent: geen horizontale schaalbaarheid, geen service-isolatie, en path-strings in API-payloads (zie security). Voor de huidige context acceptabel; documenteer het expliciet als bewuste beperking.

**A4 — Synchronous HTTP voor lange operaties (MEDIUM).** Veo-runs van 5-15 min lopen over één blocking HTTP-call (verzacht met een eigen `veoExecutor`, AsyncConfig.java:31 — goed gevonden). Eén netwerkblip = verloren run. Een job-id + poll- of callback-patroon tussen orchestrator en videogen zou de pipeline robuuster maken dan timeouts ooit kunnen.

**A5 — Geen API-contracten.** Geen OpenAPI-specs; de DTO's zijn het contract. Bij 8 services die alleen door de orchestrator worden aangeroepen is dit overleefbaar, maar elke tweede consument (de golden-test doet dit al!) moet nu reverse-engineeren.

### Refactoring-roadmap (architectuur)
1. `SceneDto` invoeren (1-2 dagen, hoogste ROI) → daarna pas al het andere.
2. PipelineOrchestrator splitsen in stage-services (1-2 weken, incrementeel).
3. Videogen asynchroon maken (job-id + status-endpoint; 2-3 dagen).
4. OpenAPI genereren vanaf controllers (springdoc, 1 dag).

---

## 2. Codekwaliteitsreview — 6/10

### Sterk
- **De commentaarcultuur is uitzonderlijk.** Vrijwel elke niet-triviale beslissing heeft een *waarom*-commentaar mét context ("P3b — dedicated pool… would starve the quick stages", "NOT @Transactional — we want repo.save() to commit IMMEDIATELY…"). Dit is zeldzaam en moet zo blijven; het is de helft van de onderhoudbaarheid van dit systeem.
- Naamgeving is consistent en intentioneel (`requireClipOk`, `mergeSceneUpdate`, `COST_CAP_DOWNSHIFT`).
- Records voor DTO's, `@RequiredArgsConstructor` overal — geen field injection aangetroffen.

### Bevindingen

| # | Severity | Locatie | Probleem | Fix |
|---|---|---|---|---|
| C1 | **High** | `orchestrator/.../PipelineOrchestrator.java` (2.264 regels) | God class; methodes van 80-120 regels (`runAssemblyStage`, `regenAndRerollScene`) | Stage-extractie (zie A1); methodes < 40 regels |
| C2 | **High** | overal in orchestrator | Primitive obsession: `Map<String,Object>` + casts voor het kernmodel | `SceneDto` (zie A2) |
| C3 | **Medium** | `voice-service/.../FfmpegRunner.java` én `video-assembly-service/.../ffmpeg/FfmpegRunner.java` | Gedupliceerde proces-runner met subtiel verschillend gedrag (redirectErrorStream true vs false) | Eén gedeelde `ffmpeg-commons`-module of bewust laten + comment |
| C4 | **Medium** | breed (bijv. SceneImageQc.check, MediaController catch-ignore) | `catch (Exception e) → pass/ignore`: fail-open is bij QC een bewuste keuze (goed gedocumenteerd), maar hetzelfde patroon verbergt elders echte bugs (JSON-parsefouten in MediaController vallen stil terug op conventie-pad) | Onderscheid: QC fail-open mág; parse-fouten minimaal `log.warn` |
| C5 | **Medium** | `PipelineOrchestrator`, `ReviewController` | Nederlandstalige foutteksten in de service-laag (`"Clip voor scène X is NIET gegenereerd"`) — UI-taal hoort niet in domeinexcepties | Foutcodes + frontend-vertaling (laag prio in single-user context) |
| C6 | **Low** | `ThumbnailGenerator.punchify(…, int variant)` | Ongebruikte parameter `variant` | Verwijderen |
| C7 | **Low** | div. | Magic numbers grotendeels netjes als constants benoemd (DISSOLVE_INTRO, VOICE_SLOTS); enkele inline (0.40-biasfactor in cropForThumbnail) | Const + comment |

**Thread-safety:** de nieuwe reroll-infrastructuur (per-job lock + in-flight-teller + merge-on-save) is correct opgezet. Resterend risico: een handmatige "Re-assemble" tegelijk met lopende rerolls deelt die lock niet. Klein venster, wel benoemen.

---

## 3. Java best practices — 7/10

- **Goed:** records voor alle DTO's en value-objects (`ModelRoute`, `ClipResult`, `Result.pass()`), switch-expressies (`cropForThumbnail`), text blocks voor prompts/SQL, `Optional` correct als return-type (`findTopBy…`), Streams zonder misbruik.
- **Gemist:**
  - **Virtual threads (Java 21!).** De hele orchestrator is blocking-IO op platform-threadpools van 4 (`AsyncConfig`). Dit is hét textbook-geval voor `Executors.newVirtualThreadPerTaskExecutor()` of `spring.threads.virtual.enabled=true`: alle pool-sizing-zorgen (en de P3b-workaround) verdampen. Aanrader, klein risico, één property.
  - **Sealed interfaces** voor stage-uitkomsten (`ClipResult` is nu status-string `"OK"/"FALLBACK"/"FAILED"` — een `sealed interface ClipOutcome permits Ok, Fallback, Failed` maakt de afhandeling exhaustief checkbaar; de `requireClipOk`-bug van vandaag was dan compile-time gevangen).
  - Pattern matching for switch zou de `instanceof List<?> l`-casts opruimen.

---

## 4. Spring Boot review — 6/10

- **Goed:** `@ConfigurationProperties`-records; bewuste, gedocumenteerde transactiegrenzen (de commit-before-async-comment op PipelineOrchestrator:124 is precies hoe het moet); Resilience4j-retries op 6 services met correcte exception-selectie; Flyway met `ddl-auto: validate`.
- **Anti-patterns:**
  - **GET-endpoints die muteren** — `GET /api/v1/videos/{id}/retry`, `GET …/reassemble`, `GET …/approve` (ReviewController/VideoController). Dit is bewust gedaan voor e-mail-goedkeuringslinks, maar het schendt HTTP-semantiek: prefetchers, linkpreviews (Slack/Outlook!) of een crawler kunnen jobs goedkeuren of hermonteren. *Fix:* e-maillinks naar een bevestigingspagina laten wijzen die POST't, of one-time signed tokens in de link.
  - **Self-injection (`self.runAssemblyStage`)** voor async/tx-proxying werkt, maar is een smell; bij de stage-extractie (A1) verdwijnt dit vanzelf (aanroep gaat dan over een echte bean-grens).
  - **Geen `@ControllerAdvice`/global exception handler** gezien: stacktraces lekken als 500-bodies naar de UI. Eén `ProblemDetail`-handler is een uur werk.
  - Actuator op 4 services in de pom maar nergens geconfigureerd; geen liveness/readiness-probes gekoppeld aan Docker.

---

## 5. Performance review — 6/10

Voor een batch-pipeline (minuten-werk, geen requests/sec) zijn de meeste klassieke zorgen irrelevant. Wat wél telt:

| # | Impact | Bevinding | Verbetering | Verwachte winst |
|---|---|---|---|---|
| P1 | **Medium** | `loadAssemblyScenes` parsed het volledige scene-JSON (kan honderden KB zijn) bij elke dashboard-poll (1s ticker + 5s load in job-page.js) en bij elke stage-stap | Review-payload cachen met ETag of `updatedAt`-conditional | minder GC-churn, snappier UI |
| P2 | **Medium → net verzacht** | Elke scene-reroll triggerde een volledige ffmpeg-re-render (27 scènes + grading + loudnorm, minuten werk) | Vandaag gebouwd: hermontage 1× na laatste in-flight reroll. Volgende stap: alleen-geraakte-scène hersnijden en concat hergebruiken | 10-15 min bespaard per reroll-sessie |
| P3 | **Low** | 24 `.block()`-calls in de orchestrator op WebFlux-clients — reactive stack zonder reactive gebruik | Óf gewoon RestClient (simpeler), óf virtual threads; WebFlux verwijderen scheelt dependency-gewicht | eenvoud |
| P4 | **Low** | Base64-encoderen van volledige PNG's voor vision-QC per scène (geheugenpiek per call) | Downscalen naar ~1024px vóór encode | kleinere/goedkopere API-calls |
| P5 | **Low** | `findTop2000ByOrderByCreatedAtDesc` op qc_finding zonder paginatie in de UI | Paginatie wanneer het ooit traag wordt | n.v.t. nu |

Geen N+1-problemen (nauwelijks relaties), geen lock-contentie (single-writer per job), DB-volume is triviaal.

---

## 6. Security review — 3/10

**Context vooraf:** alles draait op één privé-machine achter een thuisrouter, single-user. Daarbinnen is het risico beperkt. Maar de poorten staan op `0.0.0.0` gemapt — één port-forward of VPN-misconfiguratie en alles hieronder is live. Daarom de strenge score.

| # | Risk | Locatie | Exploit-scenario | Fix |
|---|---|---|---|---|
| S1 | **CRITICAL** | alle services | **Geen enkele authenticatie/autorisatie.** Iedereen op het netwerk kan jobs aanmaken, goedkeuren, verwijderen, en (met geconfigureerde tokens) naar YouTube/TikTok/Facebook publiceren | Minimaal: één gedeelde API-key-filter op de orchestrator + services alleen op het interne Docker-netwerk (geen host-port-mapping behalve 8080); volwaardig: Spring Security + JWT |
| S2 | **CRITICAL** | `.env` (regels 7-85) | Live API-keys (Anthropic, OpenAI, ElevenLabs, Replicate, Gemini, Suno-sessie) in plaintext op schijf; `.gitignore` dekt ze af, maar elke backup/sync/screenshare lekt ze | Keys roteren; naar OS-keystore of Docker secrets; `.env.example` bestaat al — gebruiken |
| S3 | **HIGH** | `YouTubeClientFactory.java:70`, `secrets/yt-creds/StoredCredential` | OAuth-refresh-token onversleuteld op schijf → kanaal-overname bij filesystem-toegang | Volume read-only houden (staat goed), schijfversleuteling, token-scope minimaal houden |
| S4 | **HIGH** | `BrandController.character()` (~:139) | `id` niet gevalideerd → `/api/v1/brand/character/..%2F..%2F…` kan buiten refs/ lezen | Zelfde 3 regels als `audioFile()` ernaast al doet: regex + `normalize()` + `startsWith()` |
| S5 | **HIGH** | `AuditController.renderChecks/keyframes` (assembly), `MultiPlatformController` (upload) | `videoPath` rechtstreeks uit request body → arbitraire bestanden door ffmpeg halen of naar platforms uploaden | Pad valideren tegen `/workdir`-boundary |
| S6 | **HIGH** | `MultiPlatformController.instagram()` | `publicVideoUrl` ongevalideerd → SSRF richting interne services/metadata-endpoints | Whitelist `https://(www.)?youtu(be)?…` |
| S7 | **MEDIUM** | `MediaController.sceneClip()` (:49) | `clipPath` uit opgeslagen JSON zonder boundary-check — alleen exploiteerbaar als de DB al gecompromitteerd is (tweede-orde) | `startsWith(workdir)`-check |
| S8 | **MEDIUM** | GET-mutaties (zie §4) | Link-preview-bots keuren reviews goed | Signed tokens of POST-bevestigingspagina |
| S9 | **LOW** | ffmpeg-aanroepen | **Veilig**: overal `ProcessBuilder` met argument-lijsten, nergens shell-interpolatie — netjes | — |

**Positief:** `GoldenController.sheet()` en `BrandController.audioFile()` doen de pad-validatie wél exact goed — het patroon bestaat al in de codebase, het moet alleen overal toegepast worden. SQL-injectie: niet aanwezig (alles JPA/JPQL met parameters). Secrets in logs: geen expliciete hits.

---

## 7. Cloud review (gevraagd: Azure — feitelijk: GCP + fal.ai) — n.v.t./7

Dit project gebruikt **geen Azure**; de cloud-voetafdruk is Vertex AI (Veo), GCS (output-bucket), fal.ai (Seedance) en ElevenLabs/OpenAI/Anthropic-API's. Beoordeling van wat er wél is:

- **Kostenbeheersing is bovengemiddeld goed:** per-video cost-cap (€45), per-model tarieven in config, conservatieve default-rate voor onbekende modellen (mét unit-test!), cost-cap-downshift naar het goedkoopste bewegende model, en routing in de bible (standard→Lite ≈ €11/video). Dit is beter dan de meeste enterprise-teams het doen.
- **Resilience:** Resilience4j-retries op 429/5xx, quota-retry-pad in videogen, fallback-naar-Ken-Burns zodat een run nooit hard faalt. Wel: fallbacks waren tot vandaag *stil* (zie de reroll-bug van vanavond) — zichtbaarheid is nu toegevoegd, dit patroon ("degradeer luid, niet stil") verdient een doorlichting van álle fallback-paden.
- **Gemist:** geen budget-alerts op GCP-niveau (één misconfiguratie = onbegrensde spend tot de cap per vídeo, niet per maand), geen circuit breaker op de Veo-client (alleen retry), GCS-cleanup gebeurt (deleteQuietly) maar zonder lifecycle-policy als vangnet.

---

## 8. Databasereview — 6,5/10

- **Sterk:** 21 + 10 Flyway-migraties, elk met doelcommentaar; correcte FK's met `ON DELETE CASCADE`; indexen op status, job_id, planned_publish_at, composite (job_id, created_at) voor audit-history; BRIN op created_at (slim voor append-only); `UNIQUE(content_hash) WHERE NOT NULL` voor dedupe; JSONB waar bevraagd wordt (script-service).
- **Zwakke plekken:**
  - **`assembly_scenes` als TEXT** — het meest gemuteerde stuk staat als opaque blob in video_jobs. Elke mutatie = hele blob herschrijven; geen queries mogelijk; geen DB-level integriteit. De migratie-comment erkent dit al ("kan later JSONB"). Doen zodra er íéts in bevraagd moet worden.
  - **Geen `@Version` op VideoJob (MEDIUM).** De async stages + rerolls + handmatige UI-acties muteren dezelfde rij. De per-job-lock van vandaag dekt het reroll-pad, maar optimistic locking is de structurele oplossing: één annotatie + één migratie, en lost-updates worden exceptions in plaats van stille datacorruptie.
  - `findLatestPerVideo` met `MAX(id) GROUP BY` leunt op id-monotonie van UUID's — dat is **niet** gegarandeerd bij random UUID's. Bij klein volume onschuldig, maar `MAX(fetched_at)` is correct. (Kleine, echte bug.)
  - Geen deadlock-risico's gezien (geen multi-row-transacties over meerdere tabellen in tegengestelde volgorde).

---

## 9. DevOps review — 4/10

| Onderdeel | Status | Risico/aanbeveling |
|---|---|---|
| CI/CD | **Afwezig** (geen workflows, alleen `build.bat`) | Hoogste DevOps-prio: één GitHub Actions-workflow: `mvn verify` + docker build op elke push. Zonder CI bestaan de 6 unit-tests effectief niet |
| Dockerfiles | Multi-stage, slanke JRE-runtime, dependency-layer-caching — netjes | Draaien als **root**, geen `USER`; geen `HEALTHCHECK`; geen JVM-flags (`-XX:MaxRAMPercentage=75` minimaal, anders pakt de JVM heap o.b.v. host-RAM) |
| docker-compose | Healthcheck alleen op postgres; `depends_on: service_healthy` dus alleen daar betrouwbaar | Healthchecks (actuator `/health`) op alle services + `restart: unless-stopped`; resource-limits per container (ffmpeg-services kunnen de host opeten) |
| Secrets | `.env` + read-only secret-mounts | Zie S2/S3 |
| Releases | Geen versioning/tags; "rebuild & restart" is de strategie | Voor deze context OK; tag images minimaal met git-sha zodat rollback bestaat |
| Observability | Logs alleen via `docker logs`; geen metrics/tracing | Quick win: `management.endpoints.web.exposure.include=health,metrics` + één Grafana/Prometheus-container is hier overkill — maar een `docker compose logs`-aggregatie-alias en de bestaande step-velden op de job zijn het minimum, en die zijn er |

---

## 10. Testingreview — 3,5/10

**Feiten:** 6 testklassen totaal. image (1), script (1), thumbnail (1), videogen (2), voice (1). **Orchestrator: 0. Assembly: 0. Upload: 0.** Geen jacoco, geen failsafe, geen integratietests, geen testcontainers. Docker-builds skippen tests.

**Wat er is, is goed:** `CostCalculatorTest` en `ModelRouterTest` bewaken precies de duurste regressies (stille lage kostenschattingen, cap-downshift) en `SimHashTest`/`ThumbnailScorerTest` testen echte logica met randgevallen. De Python-harnassen (`golden-test.py`, `run-eval.py`) zijn de feitelijke E2E-regressiebescherming en zijn goed opgezet (baseline + non-zero exit bij regressie).

**Wat ontbreekt, gerangschikt op risico:**
1. **State-machine-tests voor PipelineOrchestrator** — de overgangen (gates, retry, fail, resume) zijn puur handgetest. Eén `@SpringBootTest` met gemockte service-clients die een job door alle stages duwt vangt de hele klasse regressies die nu alleen in productie opvallen (zoals de reroll-zonder-clip van vanavond).
2. **Concatenator/IntroBuilder-tests met echte ffmpeg** (testcontainers of CI met ffmpeg): duur-, fade- en sync-bugs waren dit project de grootste tijdvreters; een test die een 3-scène-video bouwt en duration/silence/black checkt (de render-checks bestaan al als productiecode!) betaalt zich per week terug.
3. **Merge-on-save-concurrencytest**: twee parallelle `regenAndRerollScene`-calls, assert dat beide clipPaths overleven.
4. jacoco aanzetten; streefgetal is hier minder belangrijk dan de trend.

---

## 11. Dependency review

| Dependency | Huidig | Aanbevolen | Risico | Reden |
|---|---|---|---|---|
| Spring Boot | 3.3.4 | 3.3.x latest patch; plan 3.4/3.5 | Laag | 3.3 OSS-support eindigt; patches bevatten CVE-fixes |
| Java | 21 (uniform) | 21 LTS — blijven | — | Virtual threads benutten (zie §3) |
| Resilience4j | 2.2.0 | 2.2.x/2.3 | Laag | Actief onderhouden |
| google-genai | 1.15.0 | pinnen + changelog volgen | Medium | Jong SDK, breaking changes komen voor; zit buiten de GCP-BOM |
| GCP BOM | 26.50.0 | periodiek bumpen | Laag | — |
| google-api-services-youtube | rev20260205 | actueel | Laag | — |
| google-oauth-client-jetty | 1.36.0 | latest | Medium | OAuth-libs hebben CVE-historie; jaarlijks bumpen |
| OpenPDF | **1.3.43** | 2.x-lijn | **Medium** | 1.3.43 is jaren oud; bekende fixes in nieuwere lijnen; alleen gebruikt voor script-PDF — makkelijk te bumpen |
| Lombok | via parent | — | Laag | — |
| jackson-dataformat-yaml | via BOM | — | Laag | — |

Geen snapshots, geen version drift, geen verlaten libraries — **dependency-hygiëne is bovengemiddeld**. Toevoegen: `mvn versions:display-dependency-updates` in CI + Dependabot/Renovate.

---

## 12. Production readiness

| Categorie | Score (enterprise/internet) | Score (huidige context) |
|---|---|---|
| Architectuur | 65 | 75 |
| Codekwaliteit | 60 | 70 |
| Security | **15** | 55 |
| Performance | 65 | 80 |
| Testing | **25** | 40 |
| DevOps | 35 | 55 |
| Maintainability | 55 | 70 |
| **Totaal** | **30/100** | **62/100** |

**Verdict:** als internet-facing enterprise-systeem: *niet productieklaar* — de blokkades zijn S1 (auth) en de testleegte op de orchestrator, al het andere is bijzaak. Als lokale single-operator studio: *productief en verdedigbaar*, mits de poorten dicht blijven, keys geroteerd worden en de drie path-traversal-fixes (elk ~3 regels, patroon bestaat al) doorgevoerd worden.

---

## 13. Geprioriteerd actieplan

### Quick wins (1 dag)
1. **Path-traversal-fixes** op `character()`, `sceneClip()`, `renderChecks/keyframes`, MultiPlatform-paden — kopieer het bestaande `audioFile()`-patroon. *(Security, 2 uur)*
2. **API-keys roteren** + `.env` → Docker secrets of minstens buiten elke sync/backup-scope. *(Security, 1 uur)*
3. **Poort-expositie beperken**: alleen orchestrator 8080 naar de host mappen; de overige 7 services alleen op het Compose-netwerk. Twee derde van het aanvalsoppervlak weg met 10 regels YAML. *(Security, 30 min)*
4. **`@Version` op VideoJob** + migratie V22. *(Datacorruptie-preventie, 1 uur)*
5. **`restart: unless-stopped` + actuator-healthchecks** in compose. *(Reliability, 1 uur)*
6. **`findLatestPerVideo` op `MAX(fetched_at)`** i.p.v. `MAX(id)`. *(Correctheid, 15 min)*
7. **jacoco aan + `mvn verify` lokaal in build.bat.** *(15 min)*

### Korte termijn (1 week)
8. **GitHub Actions CI**: build + tests + docker build per service. Zonder dit blijft alles hieronder theorie.
9. **State-machine-integratietest** voor de orchestrator (gemockte clients, job door alle gates).
10. **Gedeelde API-key-filter** op alle inkomende endpoints (één `OncePerRequestFilter`, header `X-Api-Key`).
11. **GET-mutaties vervangen** door signed-token-links of bevestigingspagina.
12. **Global `@ControllerAdvice`** met ProblemDetail; geen stacktraces naar de UI.

### Middellange termijn (1 maand)
13. **`SceneDto` invoeren** en alle `Map<String,Object>`-paden migreren (de belangrijkste structurele investering).
14. **PipelineOrchestrator → stage-services** splitsen, incrementeel.
15. **Virtual threads** aanzetten en de executor-tuning (P3b-workaround) verwijderen.
16. **ffmpeg-integratietests** (3-scène-mini-render + bestaande render-checks als assertions).
17. **assembly_scenes → JSONB** met een nette migratie.

### Lange termijn (3+ maanden)
18. **Asynchrone videogen** (job-id + poll/callback) — maakt de pipeline herstartbaar midden in een Veo-run.
19. **Event-gedreven stage-overgangen** (outbox-tabel + poller is genoeg; geen Kafka nodig op deze schaal).
20. **Volwaardige authN/Z** zodra er ooit een tweede gebruiker of remote toegang komt.

---

## Top 10 belangrijkste verbeteringen (gerangschikt op business-impact × risico × effort)

1. Path-traversal-fixes + poort-beperking (uren werk, haalt de acute security-angel eruit)
2. CI-pipeline — zonder CI is elke andere verbetering niet geborgd
3. `SceneDto` — typed domeinkern, grootste structurele hefboom
4. Orchestrator-state-machine-test — beschermt het kritiekste onderdeel
5. `@Version` op VideoJob — voorkomt stille lost-updates
6. API-key-filter op alle services
7. PipelineOrchestrator-splitsing in stage-services
8. GET-mutaties elimineren
9. Virtual threads + executor-opruiming
10. ffmpeg-integratietests op de assembly

## Eindoordeel

**Architectuurscore: 6,5/10 · Technische schuld: 5,5/10 · Production readiness: 30/100 (enterprise) / 62/100 (huidige context).**

Dit is een **opmerkelijk coherent systeem** voor zijn ontstaansgeschiedenis: de service-snijlijnen kloppen, de kostenbeheersing is beter dan bij menig professioneel team, fouten degraderen gecontroleerd in plaats van hard, en de commentaarcultuur maakt de codebase leerbaar. De schuld is geconcentreerd en dus aanpakbaar: één god class, één ontbrekend type (`SceneDto`), één ontbrekende laag (tests + CI), en een security-houding die alleen houdbaar is zolang dit systeem nooit een netwerkgrens oversteekt. Wie de quick wins en de korte-termijnlijst uitvoert, heeft een systeem dat ook een kritische buitenstaander serieus neemt; wie daarna §13.13-17 doet, heeft een codebase die jaren meegaat.
