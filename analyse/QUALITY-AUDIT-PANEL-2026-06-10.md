# Quality Audit — Expertpanel "Tiny Chicken World"
**Datum:** 2026-06-10 · **Scope:** volledige pipeline (code, bible, prompts, echte outputs)
**Methode:** 5 parallelle deep-dives (architectuur, story/characters, VEO, audio/editing, QA/YouTube) + visuele inspectie van echte frames, refs, thumbnail en eindvideo, gevolgd door verificatie van alle claims tegen de code.

> Belangrijk: drie claims uit eerdere interne reviews bleken **verouderd** en zijn gecorrigeerd:
> 1. `VOICE_MODE=elevenlabs` staat AAN (het .env-commentaar "silent mode" is stale).
> 2. `bible/refs/mo.png` toont al de canonieke gebreide sjaal — de BACKLOG-entry is achterhaald.
> 3. Character-SFX bestaan wél (pip/mo/bo: 20 files elk, words: 17, intro: 6). Wat WEL leeg is: `bible/sfx/ambient/`, `common/`, `narrator/`.

---

## Eindscores (onderbouwing in §1–§11)

| Metric | Score |
|---|---|
| **Overall Quality Score** | **64 / 100** |
| **Pixar Readiness Score** | **42 / 100** |
| **YouTube Success Probability** | **55 / 100** |

De stills zijn verrassend goed (premium look, warm licht, correcte DoF). Wat het geheel omlaag trekt is niet de beeldkwaliteit per still, maar: **drift tussen scènes, twee zichtbare render-bugs in de eindvideo, een thumbnail-strategie die niet bij 3–6-jarigen past, en het ontbreken van deterministische QA-gates** die deze fouten hadden moeten vangen.

---

## 1. Story Quality — 7,5/10

**Wat sterk is.** Het promptsysteem (`PromptBuilder.java`, ~7000 woorden SYSTEM_BASE) is professioneel: beat-sheet met 6 fases en seconden-budget, 5 story-arcs, expliciete comedy-mechanieken (callback, rule of three, anticipation), emotion-rotation, match cuts, prop-continuity, 11-punts checklist. De ScriptCritic (8 assen, comedy + emotionalImpact als primary gates) plus StructureValidator met herprompt-loop is een echt redactiesysteem. De pilot-brief ("Pip's First Sunrise") volgt het discovery-arc-sjabloon vrijwel perfect.

**Wat zwak is.**
- **De eindvideo is 138,6s i.p.v. 180s (−23%)** — buiten de eigen ±20%-tolerantie, en niets in de pipeline heeft dit tegengehouden. De duurvalidatie zit op script-niveau (geschatte seconden), niet op de gerenderde video.
- De Critic beoordeelt re-hook-cadans en callbacks **holistisch**, niet deterministisch. Een script zonder echte callback kan met 7/10 passeren. (`ScriptCritic.java:46`)
- **Geen serie-geheugen**: elke aflevering is geïsoleerd. Pip groeit niet, refereert nooit aan eerdere ontdekkingen. Geen terugkeer-haak voor vaste kijkers.
- Arc-selectie is letterlijk `randomStoryArc()` (`ChannelBible.java:32`) — het systeem leert niets van wat presteert.

**Concrete verbeteringen:** render-time duration gate; deterministische ComedyValidator (callback aanwezig? ≥2 silly sounds? per-karakter running gag afgevinkt?); `series_state`-tabel + injectie "vorige aflevering leerde Pip X".

---

## 2. Character Quality

| Karakter | Score | Onderbouwing |
|---|---|---|
| **Pip** | **8/10** | Sterkste DNA-lock (cream-white, strohoed, rode bandana, anti-accessory-lijst). In alle geïnspecteerde frames stabiel herkenbaar. Catchphrases hard afgedwongen in prompt. Risico: stateless generatie → micro-drift in hoedhoek/dons. |
| **Mo** | **6,5/10** | Ref-image is inmiddels correct (gebreide sjaal ✓). Maar **geobserveerde drift in echte frames**: oogkleur blauwgrijs i.p.v. canoniek "deep gentle brown" (frame_00), en wimpers/vervrouwelijkte ogen terwijl Mo "hij" is (frame_04). Persoonlijkheidslock (één kalme vergelijking, slow blink) is goed. |
| **Bo** | **7/10** | Bril + groene sjaal consistent aanwezig ✓. Maar: **rode kam ontbreekt** in meerdere frames (tuft i.p.v. kam), en formaat drift — canon zegt "slightly smaller, slimmer", in frame_07 is Bo even groot en ronder dan Pip. Wordplay-gag alleen soft gevalideerd. |

**Root cause van alle drift:** elke still is een onafhankelijke Gemini-call met alleen tekst-DNA + één anchor-image; Veo krijgt **helemaal geen** reference image, alleen tekst. Drift in de still composteert door in de clip.

**Aanbevolen systemen:** (1) `ConsistencyState` per job — goedgekeurde scene-1-anchors als extra refs in elke volgende call + vision-QC met re-roll bij drift boven drempel; (2) oogkleur/kam/silhouet opnemen in de bestaande SceneImageQc-checklist als harde criteria; (3) Veo image-conditioning gebruiken zodra het modelpad dit toelaat.

---

## 3. Visual Quality — 6,5/10

**Sterk:** de stills halen een hoog niveau — warm golden-hour licht, correcte shallow DoF op hook-shots, leesbare silhouetten, Pixar-achtige proporties. De Camera Bible (per fase: lens, hoek, beweging) werkt zichtbaar door in de output. frame_01 (Pip rennend op keienpad, EXTREME CLOSE) is precies wat het hook-sjabloon voorschrijft.

**Zwak:**
1. **KRITIEK — zwarte pillarbox-balken in de eindvideo.** Geverifieerd op pixelniveau: frame_00/01 van run `00f0a2e0` hebben echte zwarte randen links/rechts in het 1920×1080-eindbestand. De blur-fill (`blurfill_check.png` bestaat nota bene als testartefact) faalt voor een deel van de scènes. Dit is het verschil tussen "premium" en "amateur" in één oogopslag.
2. **Monotone kleurtemperatuur**: alle 4 geïnspecteerde frames zijn golden-hour/schemering. Het bible kent timeOfDay-variatie, maar de praktijk levert elke scène dezelfde oranje-roze gloed → visuele vermoeidheid en zwakker dag/nacht-verhaalgevoel.
3. Karakterschaal varieert per scène (zie §2 Bo) — geen scale-reference in de prompt verankerd aan pixels.

---

## 4. VEO Prompt Engineering — 7,5/10

De `VeoPromptCompiler` (13-staps deterministische opbouw, ~1200–1800 tokens, negative prompt met 40+ failure modes) is bovengemiddeld goed. Hero-naar-hero clip-chaining (end-still als startframe) bestaat.

**Gaten, gerangschikt:**
1. **Geen visuele referentie naar Veo** — alle character-constraints zijn tekst. Dit is de #1 bron van drift.
2. Surface-physics vuurt alleen op ~20 magic words ("dig", "splash"); "walk"/"stand" missen → zwevende voeten mogelijk.
3. Scale anchor is talig ("~50× her height from Big Oak"), niet pixel-gegrond.
4. Multi-karakter negative prompt mist overlap-clausules (vermenging van accessoires bij 3 chicks in beeld — zie Bo's kam-verlies in groepsshots).
5. Continuïteit alleen hero→hero; elke Veo→Ken Burns-cut is een zichtbare kwaliteitsval.
6. Kritieke instructies staan soms diep begraven in de prompt — herordening op token-prioriteit is gratis winst.

**Betere architectuur (schets):** laag 1 stijl-lock (vast) → laag 2 character-block per aanwezig karakter mét ref-image → laag 3 scène (locatie/weer/licht uit bible-ID's, niet vrije tekst) → laag 4 motion (werkwoord + endPose + pacing, emotie-geschaald) → laag 5 camera (fase-preset + scène-override) → laag 6 negatives (basis + multi-char + per-karakter anti-accessories van de ándere karakters).

---

## 5. Audio Quality — 7/10

**Geverifieerd sterk:** ElevenLabs staat aan met per-karakter voice-ID's en goed gekozen settings (Pip stability 0.45 expressief, Mo 0.65 kalm, Bo 0.35 theatraal). Sidechain-ducking en two-pass loudnorm zijn professioneel geïmplementeerd. **Gemeten: −14,3 LUFS integrated — exact op YouTube-norm.** Character-SFX-bibliotheek bestaat (60 emotie-clips).

**Zwak:**
- **3 muziektracks totaal** (één per mood). Na 5 afleveringen klinkt de serie als een loop. Richting: 12+ tracks, 2–3 per mood, plus per-fase varianten.
- `bible/sfx/ambient/` en `common/` zijn **leeg** — de ambient-mixing-code is dormant. Geen vogels bij de vijver, geen wind op de heuvel: het soundscape-verschil tussen "video" en "wereld".
- Emotion-tag-dichtheid op dialooglijnen is ~40–50%, niet 100% — vlakkere voice-delivery dan de settings toelaten.
- LRA 4,2 LU is vrij plat gecomprimeerd; voor climax-momenten mag er meer dynamiek doorheen.
- Transitions-map: 1 file. Geen whoosh-variatie.

---

## 6. Editing Quality — 6,5/10

Fase-gedreven transities (hard op hook, dissolve op climax, fade op closer), J/L-cuts, climax-muziekswell, title/end-cards en ingebrande subs (`subs.srt` ✓) zijn allemaal aanwezig in de assembly-code — dat is meer dan de meeste AI-kanalen hebben.

**Maar:** (1) de −23% duurafwijking toont dat scène-timing vs. voice-duur niet sluit; (2) J/L-cut-sync is nooit gemeten (geen A/V-drift-test); (3) de muziekswell rekent met geplande scèneduur — als voice de scène oprekt valt de swell naast de beat; (4) Veo→Ken Burns-overgangen zijn ritmebrekers. Top-kanalen (Bluey-clips, Ms Rachel) cutten op audio-beats en houden elke 7–10s een visuele verandering vast — dat laatste haalt dit systeem op papier, maar niemand meet het op de gerenderde video.

---

## 7. Children's Content Quality — 7,5/10

Doelgroep 3–6 klopt met de uitvoering: zinnen ≤8 woorden, één emotie per scène, mistake-driven humor, geen moraliseren ("never explain the magic away" is een uitstekende persona-regel). Educatieve waarde is licht maar echt (één concept per aflevering, herhaald in kindertaal). Emotioneel veilig.

**Verbeterpunten:** (1) **participatie-momenten ontbreken** — Ms Rachel-stijl pauzes ("Kun jij de zon zien? ... Ja!") zijn dé retentie- en leer-versneller voor deze leeftijd en kosten alleen een promptregel + 1,5s stilte in de edit; (2) herhaalstructuur (rule of three) zit in de comedy maar wordt niet ingezet voor het leerdoel zelf; (3) geschatte werkelijke kijkleeftijd van de huidige output: 3–5 — voor 6-jarigen is de plot te dun.

---

## 8. YouTube Performance Potential — 5,5/10

- **Thumbnail (geïnspecteerd):** "MO'S ORANGE POND!" — 3 regels tekst over ~50% van het beeld, mét zwarte balk aan de linkerrand (de pillarbox-bug zit dus ook in de thumbnail-bron). **De doelgroep kan niet lezen.** Kids-thumbnails winnen op grote expressieve gezichten + één visueel raadsel (de oranje vijver zelf!), niet op tekst. De lachende gezichten zijn goed; de compositie verspilt ze.
- **Titel:** ≤60 chars, topic-specifiek — prima basis, geen keyword-onderzoek.
- **CTR-loop:** 3 thumbnail-varianten worden gegenereerd maar **nooit gescoord** tegen werkelijke CTR. Analytics-API-koppeling bestaat backend maar is onzichtbaar.
- **Hook:** scene-1-sjabloon (extreme close-up + vraag/geluid) is precies goed voor browse-traffic.
- **Verwachting bij huidige staat:** CTR matig (tekst-thumbnails), watch time per view behoorlijk (pacing klopt), rewatch laag (geen serie-geheugen, 3 muziektracks, zelfde golden-hour-look). Compliance: `madeForKids=true` hardcoded + synthetic-media-disclosure ✓.

---

## 9. AI Pipeline Architecture — 6,5/10

Solide fundament voor een single-host indie-pipeline: bible-driven config, idempotente stages met crash-recovery, per-scène Veo→Ken Burns-fallback, cost-cap-concept, SimHash-dedup.

**Geverifieerde productie-gaten:**
1. **Naakte `.block()` zonder timeout** in `ScriptServiceClient`, `ImageServiceClient`, `VoiceServiceClient`, `ThumbnailServiceClient` én alle 8 calls in `UploadServiceClient` — één hangende downstream = job hangt eeuwig. Alleen Assembly en VideoGen hebben timeouts.
2. Retries alleen op `ElevenLabsClient`/`AnthropicClient`; service-naar-service-calls falen hard op transients. Geen circuit breakers: één Anthropic-storing = 100% pipeline-stop.
3. **Veo-cost-cap is een schatting**, niet gemeterd tegen werkelijke GCP-billing — budgetoverschrijding is mogelijk en onzichtbaar.
4. Metadata + thumbnail worden bij elke re-assembly opnieuw gegenereerd (dubbele API-kosten, geen cache-check).
5. Secrets in platte `.env`, statische GCP-service-account-key, geen rotatie. YouTube-OAuth-token wordt alleen reactief ververst.
6. Geen Micrometer/Prometheus — kosten, faalratio's en quota zijn niet observeerbaar.
7. Schaal: gedeelde workdir-volume bindt alles aan één host; script-polling elke 2s; Veo-parallelisme hardcoded op 3.

---

## 10. Quality Assurance System — huidig 5/10, ontwerp hieronder

**Bestaat al:** StructureValidator (deterministisch), ScriptCritic (LLM), per-scène SceneImageQc met auto-reroll, 8-keyframe master-audit, 14-punts polish-score, ConsistencyChecker voor prop-kleuren (vers, ongetest).

**Ontbreekt — en precies déze gates hadden de twee gevonden bugs gevangen:**

| Gate | Type | Vangt | Implementatie |
|---|---|---|---|
| **Edge/black-bar-detectie** | ffmpeg/PIL, deterministisch | de pillarbox-bug | rand-pixelscan op 8 keyframes; >2% zwarte kolommen = FAIL |
| **Render-duur-gate** | ffprobe | de −23%-video | `ffprobe duration` vs. target ±10% = blocker |
| Loudness-gate | ffmpeg ebur128 | mix-uitschieters | I ∉ [−15,−13] LUFS = FAIL (nu toevallig goed) |
| Stilte/black-frame-detect | ffmpeg silencedetect | dode lucht, kapotte clips | >1,5s stilte buiten beoogde pauzes = WARN |
| **Character-drift vision-QC** | LLM-vision per keyframe | Mo's oogkleur, Bo's kam | checklist per karakter (oogkleur, kam, accessoire, relatieve grootte) tegen locked anchor; score <0,8 = re-roll |
| A/V-sync-meting | waveform vs. subs timing | J/L-cut-drift | woordgrens vs. SRT-offset >120ms = WARN |
| ComedyValidator | deterministisch op script-JSON | gag-loze scripts | callback set-up/pay-off aanwezig, ≥2 sound-effects, 3 running gags afgevinkt |

**Meetbare kwaliteitsmetrics per video:** duration-delta %, LUFS, edge-black %, character-consistency-score (0–1, vision), emotion-tag-dichtheid %, re-hook-interval p95, kosten/€ per minuut output.

---

## 11. Competitive Benchmarking

| Benchmark | Wat zij hebben | Gap hier | Afstand |
|---|---|---|---|
| **Pixar/Disney/DreamWorks** | rigs, continuity supervisors, lip-sync, 4 jaar per film | stateless 2D-stills + motion; geen lip-sync (toollimiet) | niet de relevante lat voor 3-min YouTube; "Pixar-look per still" is wél deels gehaald |
| **Bluey** | personages die groeien, emotionele gelaagdheid voor ouders óók | geen serie-geheugen, ouders haken niet mee | groot, deels dichtbaar via SeriesState + dual-layer jokes |
| **Cocomelon** | beat-perfecte muziek-sync, eindeloze herhaalbaarheid | 3 tracks, geen beat-cutting, geen songs | middelgroot; muziekbibliotheek is quick win |
| **Ms Rachel** | directe participatie, pauzes, taalherhaling | nul participatie-momenten | klein gat, goedkoop te dichten |
| **Top AI-kanalen** | volume + thumbnails geoptimaliseerd op CTR-data | betere story-discipline dan zij; slechtere thumbnail/analytics-loop | dit kanaal wint op inhoud, verliest op packaging |

**Eerlijke plaatsbepaling:** ver boven het gemiddelde AI-kanaal in systeemontwerp; onder professioneel studio-niveau door drift, render-bugs en ontbrekende feedbackloops. Geschat: 60–70% van "premium YouTube kids"-niveau, 40% van Pixar-shorts-niveau.

---

## 12. Geprioriteerde Roadmap

### KRITIEK (schaadt elke video die nu uitgaat)
| # | Actie | Impact | Moeite | Winst | Voorbeeld |
|---|---|---|---|---|---|
| C1 | **Fix blur-fill/pillarbox-bug** + edge-black-QA-gate | 9 | 2 | elke video direct premium-ogend | scale/crop-filterketen in assembly corrigeren voor afwijkende bronresoluties; PIL-randscan in master-audit |
| C2 | **Render-duur-gate** (ffprobe vs. target ±10%) | 7 | 2 | geen 138s-video's meer op een 180s-format | blocker in assembly-stage vóór upload |
| C3 | **Timeouts + retries op álle WebClient-calls** | 8 | 2 | geen eeuwig hangende jobs | `.timeout(5min).retryWhen(backoff(3, 2s))` patroon uitrollen over 5 clients |
| C4 | **Thumbnail: tekst → max 3 woorden, gezicht-gedreven** | 8 | 3 | CTR is de poort naar álle andere kwaliteit | de oranje vijver ZELF groot in beeld + 1 verbaasd gezicht; tekst hooguit "ORANJE?!" |

### HIGH IMPACT
| # | Actie | Impact | Moeite | Winst |
|---|---|---|---|---|
| H1 | Character-drift vision-QC per keyframe (oogkleur, kam, accessoires, schaal) met auto-re-roll | 9 | 6 | consistentie 6,5→8; de kern van "echt kanaal"-gevoel |
| H2 | ConsistencyState: scene-1-anchors als extra refs in elke image-call | 9 | 7 | micro-drift structureel omlaag |
| H3 | Muziek 3→12 tracks + ambient-loops per locatie (mappen bestaan al, zijn leeg) | 7 | 3 | herkijkbaarheid + immersie |
| H4 | Emotion-tags naar 100% dekking op dialooglijnen | 7 | 3 | voice-acting van vlak naar levend, settings staan al goed |
| H5 | Veo-kostenmetering tegen werkelijke GCP-billing + Micrometer-metrics | 7 | 4 | budgetzekerheid bij opschalen |

### MEDIUM
| # | Actie | Impact | Moeite | Winst |
|---|---|---|---|---|
| M1 | ComedyValidator (deterministisch) | 7 | 4 | geen gag-loze scripts meer door de gate |
| M2 | SeriesState: cross-episode geheugen + teasers | 7 | 5 | terugkeer-kijkers; arc over afleveringen |
| M3 | Participatie-beats in promptsjabloon (vraag + 1,5s pauze, 2×/video) | 6 | 2 | retentie + leereffect (Ms Rachel-mechaniek) |
| M4 | timeOfDay-variatie afdwingen (max 60% golden hour per video) | 5 | 2 | visuele variatie |
| M5 | Performance-gewogen arc-selectie zodra analytics lopen | 6 | 3 | compounding leereffect |
| M6 | Metadata/thumbnail-cache bij re-assembly | 4 | 2 | API-kosten ↓ |

### NICE TO HAVE
secrets naar Secret Manager (5/3) · proactieve YouTube-token-refresh (4/2) · circuit breakers + fallback-providers (6/5) · beat-synced cutting (6/6) · character-leitmotifs (5/5) · scale-reference-image in Veo-prompt (7/5, wachten op model-support) · multi-platform-distributie zichtbaar maken in UI (5/2).

---

## Top 10 Acties (grootste kwaliteitssprong, in volgorde)

1. **Pillarbox-bug fixen + edge-QA-gate** — elke huidige video heeft zichtbare zwarte balken.
2. **Duur-gate**: nooit meer een video 23% onder format-target.
3. **Thumbnails gezicht-gedreven maken** — doelgroep kan niet lezen; dit is de grootste CTR-hefboom.
4. **Vision-QC op character-drift** (Mo's ogen, Bo's kam zijn nú al mis) met auto-re-roll.
5. **Timeouts/retries op alle service-clients** — betrouwbaarheid vóór opschalen.
6. **Muziek- en ambient-bibliotheek vullen** — de code wacht al op de bestanden.
7. **Emotion-tags naar 100%** — goedkoopste audiokwaliteitswinst die er is.
8. **ComedyValidator** — de merkbelofte ("2 echte lachmomenten") hard maken.
9. **ConsistencyState/anchor-locking** — de structurele oplossing waar 4 ook van profiteert.
10. **SeriesState + participatie-beats** — van losse video's naar een kanaal waar kinderen op terugkomen.

*Geschatte doorlooptijd acties 1–7: ±2 weken. Daarna staat er een systeem dat niet alleen mooie stills maakt, maar consistent afleverbare kwaliteit bewaakt.*
