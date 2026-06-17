# Prompt-Audit v2 — Veo/Image Prompt Generator (delta t.o.v. v1)
**Systeem:** `VeoPromptCompiler.directorBrief()` + `PromptComposer` + script-service validators
**Live config:** `veoLeanPrompts: true`, `veoNativeAudio: true`, `singleLocation: true`, vaste 10s-clips
**Datum:** 2026-06-17 (2e ronde) · **Team:** Java Architect · Prompt Engineer · Veo/Omni Specialist

> Tweede pass na een ronde fixes. Eerst de delta (wat is dicht), dan een verse jacht op NIEUWE/resterende risico's met "miljoenen generaties"-maatstaf.

## Δ Sinds v1 gesloten (geverifieerd)
R3 (gate forbids∧¬owns) · R1 (`AccessoryValidator` + naam-bezittelijk "Mo's spectacles") · P1 (identity-lock hoog in de prompt) · P3 (impact-onomatopee telt niet als gesproken comedy-beat) · T1 (accessory-cache in `clearCaches`) · T2 (parity-tests, nu **3-weg**) · T3 (`CLIP_SECONDS`-constante) · #1 scène-21 (gender-aware subject-resolutie) · #2 scène-20 (`TransformationValidator`).

## Tijdens deze audit direct gefixt
- **N8 (regressie, T1-klasse):** de nieuwe `AccessoryValidator` had een eigen `volatile` cache die de bible-hotreload (`/api/v1/bible/reload`) niet leegde → stale tot herstart. **Cache verwijderd** (validator draait enkele keren per job, geen hot path).
- **Parity-gat:** `AccessoryValidator` was een **derde** kopie buiten het T2-contract. Detectie is nu een pure `contradictionsInText(...)` met een eigen `AccessoryValidatorParityTest` op dezelfde canonieke cases.

---

## Stap 1 — TECHNICAL REVIEW (Java Architect)
**Sterk:** gelaagde verdediging (script-validators → compile-linter/guards → critic → human gate); bible als single source; pure, unit-testbare guards; 3-weg parity-contract; reload-veilig.

**Zwak / Risico**
| # | Bevinding | Plek | Ernst |
|---|-----------|------|-------|
| N2 | `CLIP_SECONDS=10` (compiler) en `Math.max(10, …)` (VideoController) zijn **niet compile-gekoppeld** — een comment zegt "must match", maar ze kunnen alsnog uiteenlopen. | VeoPromptCompiler / VideoController:313 | Middel |
| N3 | Accessoire-categorieën staan nu in **DRIE** bestanden (2 guards + validator). Nieuw type = 3 edits; de parity-tests gebruiken vaste in-memory categorieën, dus "categorie vergeten in één `CATEGORY`-map" wordt **niet** gevangen. | 3× | Middel |
| N4 | `inferGender` leunt op pronouns in `dna.tic`. Duckling-tic heeft er geen → gender "" → permissief. Een tic herschrijven zonder "his/her" degradeert de gender-binding **stilzwijgend**; geen test bewaakt de inferentie. | guards + validator | Middel |
| T5/T6 (open) | per-call modelbouw in image; volatile-recompute-races. | — | Laag |

**Verbeteringen:** N2 → laat VideoController dezelfde constante importeren (één bron). N3+N4 → de twee al voorgestelde genericiteit-stappen (categorieën + `pronoun` uit de bible; één gedeelde guard-module i.p.v. 3 kopieën).

## Stap 2 — PROMPT REVIEW (Prompt Engineer)
**Sterk:** front-loaded actie; identity-lock nu zowel vroeg (P1) als laat; token-economie; expliciete ENGLISH- en no-text-locks; impact-foley uit de spraaklaag.

**Zwak**
- **N5:** P1 voegt ~30 woorden hoog in de prompt toe → langere prompt, dichter bij de 450-woord-truncatiegrens die P1 juist bestrijdt. Netto positief (lock staat vroeg), maar houd de lengte in de gaten; comprimeer de gedeelde boilerplate.
- **N7:** `ComedyValidator` skipt een *pure* impact-regel ("Bonk!"), maar een impact-woord **ingebed** in een langere regel ("the egg goes plop") telt nog steeds als gesproken sound-beat via `SOUND_WORDS`. Kleine inconsistentie met de SFX-split.

**Confidence: 8/10** (was 7).

## Stap 3 — VIDEO REVIEW (Veo/Omni)
- **Character drift — LAAG.** Reference-conditioned start-frame + `veoKey` + anti-swap + accessory-guard (nu incl. scène-21 gender-binding).
- **Object drift — MIDDEL.** Ei-staat blijft vrije tekst (niet deterministisch monotone property). `TransformationValidator` dekt nu wél de eend-onthulling.
- **Scene/Camera — LAAG.** singleLocation + shot-aware overrides + linter-contradictiechecks.
- **Temporal — LAAG/MIDDEL.** Vaste 10s consistent; zie N1 (cijferlek) en N2.

## Stap 4 — RED TEAM FINDINGS
| # | Probleem | Verwachte fout | Impact | Oplossing |
|---|----------|----------------|--------|-----------|
| **N1** | De prompt bevat kale cijfers/tijdcode: `[0:00 - 0:10]` en "10-SECOND". De restricties verbieden "on-screen text/captions" maar **niet expliciet numbers/timecodes**. | Veo brandt een timer/getal in beeld (zoals "BONK" eerder als tekst lekte). | Middel-hoog | (1) restrictie uitbreiden met "no on-screen numbers, timers or timecodes"; (2) de kale `[0:00-0:10]` vervangen door woorden ("over the full ten-second clip"). **Fix klaar, wacht op go.** |
| N3b | Iemand voegt "bowtie" toe aan één `CATEGORY`-map maar niet de andere twee. | Inconsistente dekking image vs video vs script. | Middel | Categorieën uit de bible (single source). |
| N6 | `TransformationValidator` vergelijkt cast alleen met de **vorige** scène; een personage dat 2 scènes wegblijft en terugkeert + een "reveal"-werkwoord → false flag. Reveal-werkwoorden zijn breed. | Onnodige re-prompt (kosten), zelden gemist defect. | Laag | Vergelijk met "alle eerder geziene cast", niet alleen vorige scène. |
| N4b | Tic zonder pronoun → gender "" → "its X" bindt mogelijk aan het verkeerde personage. | Zeldzame mis-rewrite. | Laag | Expliciet `pronoun`-veld in de bible. |

## Stap 5 — PROMPT VARIATIES (A/B/C) — duur/cijfer-regel
- **A (conservatief):** geen tijdcode in de prompt; "Across the whole clip, beginning to end, …". Kwaliteit hoog · consistentie zeer hoog · risico laag (geen cijferlek), maar iets minder expliciete timing.
- **B (gebalanceerd, aanbevolen):** behoud "FULL 10-SECOND CLIP" als label, vervang de kale `[0:00 - 0:10]` door "from the first to the last frame", en voeg "no on-screen numbers/timecodes" aan de restricties toe. Hoog · hoog · laag.
- **C (creatief):** houd de tijdcode `[0:00–0:10]` voor maximale temporele sturing, maar zet er een expliciete "(timing note only — never render any numbers on screen)" achter. Hoog · middel · middel (vertrouwt erop dat het model de meta-instructie respecteert).

## Stap 6 — STRESS TEST
| Test | Verwacht probleem | Kans | Aanbeveling |
|------|-------------------|------|-------------|
| 1 lang karakter / veel scènes | accessoire-swap tússen clips | laag-middel | per-clip accessoire-checksum bij assembly |
| 2 snelle camera | preset vs actie-shot | middel | forceer `veoCameraOverride`; linter aanwezig |
| 3 complexe omgeving | token-overload >450w → trailing truncatie (N5) | middel | boilerplate comprimeren |
| 4 veel objecten | ei-staat/props niet deterministisch | middel | ei-staat als monotone property |
| 5 >60s (N×10s) | drift accumuleert; **cijferlek** (N1) zichtbaarder bij veel clips | middel-hoog | N1 fixen; reference-anchor per clip; identity-checksum |

## Stap 7 — EINDSCORE (v2)
| Onderdeel | v1 | v2 |
|-----------|----|----|
| Technische kwaliteit | 7.5 | **8** |
| Prompt kwaliteit | 7 | **7.5** |
| Video kwaliteit | 8 | **8.5** |
| Consistentie | 7.5 | **8.5** |
| Schaalbaarheid | 7 | **7** (triple-duplicatie N3 drukt) |
| Productiewaardigheid | 7.5 | **8** |

**Eindoordeel:** duidelijke vooruitgang; de accessoire-/transformatie-/timing-defectklassen zijn nu gedekt. Hoogste resterende prioriteit: **N1 (cijferlek)** → **N2 (CLIP_SECONDS koppelen)** → **N3/N4 (categorieën + pronoun uit de bible, guard naar één module)**. Pas dan is "miljoenen generaties" verdedigbaar zonder de duplicatie- en cijferlek-risico's.
