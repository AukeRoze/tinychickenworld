# Prompt-Audit — Veo/Image Prompt Generator
**Systeem:** `VeoPromptCompiler.directorBrief()` (orchestrator) + `PromptComposer` (image-service)
**Live config:** `veoLeanPrompts: true`, `veoNativeAudio: true`, `singleLocation: true`
**Datum:** 2026-06-17 · **Auditteam:** Java Architect · Prompt Engineer · Veo/Omni Specialist
**Scope:** de automatisch gegenereerde prompt zoals die naar Veo 3.1 / Nano-Banana gaat, inclusief de twee nieuwe guards (AccessoryGuard, OnomatopoeiaGuard).

> Dit is een productie-audit ("miljoenen generaties"-maatstaf). Niets wordt geaccepteerd als "goed genoeg". Bevindingen verwijzen naar echte bestanden/regels.

---

## 0. Auditobject — representatieve gecompileerde prompt (scène 8, na fix)

Gereconstrueerd uit `directorBrief()` op het live lean+native-audio-pad. Cast `[bo, pip]`, fase `development`, de "Bonk!"-regel is door de OnomatopoeiaGuard naar de SFX-laag verplaatst.

```
DIRECTOR'S BRIEF & ENVIRONMENT
- Visual Style: soft 3D Pixar/Illumination cartoon look ...
- Camera Setup: eye-level, 50mm, slow drift, both chicks clearly readable together.
- Shot Type: single, unbroken continuous shot - no cuts, no scene changes.
- Aspect ratio / quality: 16:9 landscape, 1080p, 24fps, fluid animation.
- Setting: the cosy vegetable-garden nest ... golden-hour light. Colour mood: warm ...

CHARACTER ROSTER (STRICT LIMIT: EXACTLY 2 CHICKENS)
Both chickens fully visible from first to last frame. No duplication ...
1. Bo: tan/sandy-brown, slim upright body, round thin-framed eyeglasses + green scarf ...
2. Pip: pure white round ball-of-fluff, straw farmer hat + red bandana ...
Each chick keeps ONLY its own accessory (hat / scarf / glasses) ... never swap.
Relative size: Pip smallest, Mo larger, Bo taller/slimmer ...
Match the start frame exactly ...

CHRONOLOGICAL ACTION & CAMERA MOVEMENT (FULL 10-SECOND CLIP)
- [0:00 - 0:10] Bo plops onto the pale-cream egg in the straw nest, wings out for
  balance; Pip lurches forward, one wing raised. Spoken aloud in clear English,
  lip-synced — Bo and Pip each SPEAK their own line in turn ...: Bo says, "I'll keep
  it warm! Like a real hen!" Pip says, "Bo, wait—". Pace this as ONE calm, continuous
  10-second beat ... Do NOT rush, speed up, loop, or stack extra moves ...

AUDIO (generate natively and perfectly in sync)
- Speech: Bo and Pip EACH speak their own line aloud in clear, child-friendly ENGLISH ...
- Ambient: gentle garden ambience ...
- Sound effects: crisp, natural foley matching the on-screen action and surfaces;
  include the physical impact foley "bonk" as a real collision sound effect at the
  moment of contact — NOT spoken or lip-synced by any character.
- No background music ... No narration. No on-screen text or captions.

ENVIRONMENTAL MOTION & RESTRICTIONS
- Background Motion: minimal and calm ...
- Negative Constraints: No character duplication, no third chicken ... no morphing ...
```

---

## Stap 1 — TECHNICAL REVIEW (Senior Java Architect)

**Sterke punten**
- Volledig deterministische compiler: camera, wereld, licht, identiteit en cast-lock zijn nergens aan Veo's fantasie overgelaten. Reproduceerbaar bij gelijke input.
- Single source of truth = `bible/channel.yml`; image- en Veo-pad delen dezelfde DNA-velden (contract bewaakt door `PromptComposerDnaTest`).
- Gelaagde verdediging: `StructureValidator`/`PacingValidator`/`ComedyValidator` (script-time) → `VeoPromptLinter` (compile-time, vóór betaalde render) → LLM-critic. Nieuwe guards passen in dit patroon.
- Lazy `volatile` caches met expliciete `clearCaches()` na bible-reload.

**Zwakke punten / Risico's**

| # | Bevinding | Bestand | Ernst |
|---|-----------|---------|-------|
| T1 | **(OPGELOST tijdens audit)** `accessoryModelCache` ontbrak in `clearCaches()` → guard bleef stale na bible-edit tot herstart. | `VeoPromptCompiler.java:47` | ~~Hoog~~ → fixed |
| T2 | `AccessoryGuard` is **gedupliceerd** in orchestrator én image-service (aparte Maven-modules) zonder cross-module parity-test. Bij wijziging in één module drift de andere — exact het risico dat `PromptComposerDnaTest` voor DNA al afdekt, maar voor de guard nog niet. | beide `service/AccessoryGuard.java` | Middel |
| T3 | `directorBrief` schrijft **"FULL 10-SECOND CLIP" hardcoded**, maar het script-schema staat `durationSeconds: 2..60` toe en die waarde wordt niet eens aan `directorBrief` doorgegeven. Een 5s-scène krijgt een 10s-instructie → temporele mismatch / Veo rekt of looped. | `VeoPromptCompiler.java:1430` | Middel |
| T4 | `audioBlock()` is **dead code**: `if (nativeAudio) return directorBrief(...)` keert eerder terug, dus de `if (nativeAudio)`-tak die `audioBlock()` aanroept is onbereikbaar. Twee uiteenlopende audio-paden = onderhoudsval. | `VeoPromptCompiler.java:1303, 1843` | Laag |
| T5 | `PromptComposer.accessorySafe()` bouwt de modellen **bij elke aanroep** opnieuw (geen cache, regex per personage). Prima bij 4 personages, onbegrensd als de cast groeit. | `PromptComposer.java` | Laag |
| T6 | `readBible()` parseert YAML van schijf met een **nieuwe `YAMLMapper` per cache-miss**; caches dempen dit, maar na `clearCaches()` onder parallelle compiles ontstaan recompute-stormen (volatile zonder lock). Benign, wel verspilling. | `VeoPromptCompiler.java:36` | Laag |

**Verbeteringen**
1. ✅ T1 gefixt.
2. Maak één gedeelde `prompt-guards` Maven-module (of een parity-test die identieke in/out op beide `AccessoryGuard`-kopieën assert).
3. Geef `durationSeconds` door aan `directorBrief` en interpoleer de tijdvenster-tekst, **of** dwing 10s af in het schema (`minimum=maximum=10`) zodat code en data niet liegen.
4. Verwijder `audioBlock()` of documenteer expliciet als legacy/test-only.

---

## Stap 2 — PROMPT REVIEW (Elite Prompt Engineer)

**Sterke punten**
- Front-loading van de actie (`[0:00-0:10] …` bovenaan de sectie) — modellen wegen vroege tokens zwaarder.
- Token-economie: `visualDesc` mag setting/licht/stijl niet herhalen (compiler print die één keer). Vermindert context-poisoning.
- Harde ENGLISH-lock + "no on-screen text/captions" tegen onomatopee-tekstlekken.
- Expliciete instruction-hierarchy via gelabelde secties.

**Zwakke punten**

- **P1 — Truncation-risico op de identiteit-locks.** De anti-morph/identity-lock en render-look staan **achteraan**. `VeoPromptLinter.SOFT_WORD_BUDGET = 450` waarschuwt al: bij die lengte droppen modellen juist de *trailing* locks. Front-loaded actie is goed, maar de meest kritische consistentie-instructies zijn het kwetsbaarst. → Verplaats de no-morph/anti-swap-lock naar vlak ná de roster (midden), niet het einde.
- **P2 — Latente instructie-conflicten.** "Pace this as ONE calm beat … do NOT stack actions" botst licht met een 2-speaker turn-taking-dialoog (twee spreekbeurten = twee acties) en met "Bo plopt + Pip lurcht". Voor een preschool-beat acceptabel, maar het is een ambiguïteit die Veo kan laten versnellen.
- **P3 — Cross-purpose tussen `ComedyValidator` en `OnomatopoeiaGuard`.** `ComedyValidator` *beloont* sound-words als gesproken dialoog ("≥2 spoken sound beats", incl. "Bonk!"), terwijl de nieuwe `OnomatopoeiaGuard` pure-impact-words bij compile juist uit de spraak haalt. Geen bug (ze draaien op verschillende momenten/objecten), maar conceptueel tegenstrijdig: een schrijver die de comedy-eis met "Bonk!" invult, ziet die beat naar SFX verdwijnen. → Lijn `ComedyValidator.SOUND_WORDS` uit: tel **impact**-onomatopee niet als gesproken comedy-beat (gebruik exclamaties als "Whoosh/Wheee/Boing").

**Confidence score: 7/10** — robuust en goed gelaagd, maar truncation-volgorde (P1) en de 10s/duration-leugen (T3) drukken de betrouwbaarheid.

---

## Stap 3 — VIDEO REVIEW (Veo / Omni Specialist)

- **Character drift — LAAG.** Identiteit komt primair uit de reference-conditioned start-frame (lean) + `veoKey` + expliciete anti-swap-zin. De nieuwe AccessoryGuard haalt de #1-oorzaak van bril/sjaal-morph (actie vs DNA-lock) weg.
- **Object drift — MIDDEL.** Ei-continuïteit en -staat (cracked/uncracked) zijn LLM-authored per scène; guidance dekt het, maar niets *dwingt* het deterministisch af (anders dan locatie/cast). Een schrijver die een verouderde "uncracked"-lock herhaalt, kan nog steeds object-flicker veroorzaken.
- **Scene drift — LAAG.** `singleLocation: true` + `StructureValidator` locken één locatie.
- **Camera drift — LAAG/MIDDEL.** Shot-aware overrides + `VeoPromptLinter` vangen push-in/pull-back- en focus-contradicties. Restrisico: fase-preset-camera vs een `visualDesc` die een ander shot impliceert zonder `veoCameraOverride`.
- **Temporal — MIDDEL.** Eén doorlopende 10s-shot is veilig voor Veo, maar de hardcoded 10s (T3) tegenover variabele `durationSeconds` kan een te-snel-of-te-langzaam ritme geven; over meerdere geassembleerde clips is accessoire-swap *tussen* clips het resterende risico (gemitigeerd, niet uitgesloten).
- **Visual ambiguity — LAAG**, met één gat: zie Red Team R1/R3.

**Aanbevelingen:** maak ei-staat een deterministische, monotone scene-property (zoals cast/locatie) i.p.v. vrije tekst; voeg een per-clip accessoire-checksum toe aan de assembly-QC.

---

## Stap 4 — RED TEAM FINDINGS

| # | Probleem (breekpoging) | Verwachte fout | Impact | Oplossing |
|---|------------------------|----------------|--------|-----------|
| R1 | AccessoryGuard matcht alleen **possessief** `his/her/its + accessoire`. Vormen als "Mo, **glasses** perched on his beak", "the **glasses** slide down Mo's beak", "Mo's **spectacles**" (naam-`'s`, geen his/her/its) ontsnappen. | Bril-vs-DNA-contradictie overleeft → morph op Mo. | Hoog (zelfde defect als scène 5) | Breid patroon uit met naam-`'s` en appositie ("Mo, glasses…"); en/of zet de **primaire** preventie in een nieuwe script-service `AccessoryValidator` die re-prompt (guard = backstop). |
| R2 | "Bonk" niet als losse regel maar **in `visualDesc`/`motionDesc`** ("Bo lands with a loud BONK"). | Onomatopee komt niet in de spraaklaag (goed) maar kan als on-screen tekst lekken; OnomatopoeiaGuard kijkt alleen naar `lines`. | Laag (TAIL verbiedt al on-screen tekst) | Optioneel: dezelfde impact-detectie ook over visualDesc draaien voor de SFX-cue. |
| R3 | **Forbidden-maar-niet-uniek-bezeten** accessoire. "Pip tugs **her scarf**": Pip verbiedt scarf maar bezit 'm niet; scarf is gedeeld (Mo+Bo) → de guard slaat 'm over (vereist `uniqueOwner`). | Pip krijgt een sjaal die ze nooit mag dragen → identity drift. | Middel | Versoepel de poort naar **"subject verbiedt categorie ∧ bezit 'm niet"** → herschrijf naar `signatureShort`. Hangt niet af van een unieke eigenaar. (Concreet patch-voorstel hieronder.) |
| R4 | Possessief-gender genegeerd: "Mo hands the case over; **her glasses** inside" met alleen Mo benoemd → nearest-subject = Mo → onterechte rewrite. | Zeldzame false-positive rewrite. | Laag | Optioneel: match possessief-gender (`his`→man-personage) tegen subject. |
| R5 | Cast-lock `EXACTLY 2` + een `visualDesc` die per ongeluk een derde naam noemt. | Linter `castCount`-check vangt count-mismatch alleen als de roster-tekst zelf afwijkt, niet als de *actie* een extra naam noemt. | Middel | Voeg aan `VeoPromptLinter` een check toe: namen in de actie ⊆ cast. |

**Aanbevolen code-verbetering voor R3 (beide `AccessoryGuard`-kopieën):**
```java
// nu: vereist een unieke eigenaar van de categorie
boolean rewrite = rightfulOwner != null && subject != null
        && !subject.id().equals(rightfulOwner.id())
        && subject.forbiddenCategories().contains(category) && ...;

// beter: hangt niet af van wie het wél bezit
boolean rewrite = subject != null
        && subject.forbiddenCategories().contains(category)
        && !subject.ownedCategories().contains(category)
        && subject.signatureShort() != null && !subject.signatureShort().isBlank();
```
Vangt zowel de bril (Mo) als de gedeelde-sjaal-casus (Pip). `Bo pushes her glasses` blijft ongemoeid (Bo *bezit* glasses).

---

## Stap 5 — PROMPT VARIATIES (A/B/C)

Drie strategieën voor de CHRONOLOGICAL ACTION-sectie van scène 8.

**Variant A — Conservatief (max. stabiliteit)**
> [0:00-0:10] Bo lowers herself slowly onto the straw beside the pale-cream egg and settles; Pip watches, one wing half-raised. Bo says, "I'll keep it warm! Like a real hen!" Pip says, "Bo, wait—". One calm, slow beat; minimal body travel.
- Kwaliteit: hoog/voorspelbaar · Consistentie: zeer hoog · Risico: laag (saai-risico: de "plop"-comedy verdwijnt).

**Variant B — Gebalanceerd (aanbevolen)**
> [0:00-0:10] Bo plops gently onto the straw next to the egg, wings out for a beat of wobble, then steadies; Pip leans in, one wing raised. Bo says, "I'll keep it warm! Like a real hen!" Pip says, "Bo, wait—". A soft "bonk" foley on contact (SFX, not spoken). One continuous beat.
- Kwaliteit: hoog · Consistentie: hoog · Risico: laag-middel (de wobble is één extra micro-actie).

**Variant C — Creatief (max. expressie)**
> [0:00-0:10] Bo flops backward onto the straw with legs in the air, recovers, and proudly puffs up; Pip darts forward mid-sentence. Bo: "I'll keep it warm! Like a real hen!" Pip: "Bo, wait—".
- Kwaliteit: hoog mits het lukt · Consistentie: **middel** (whole-body flop + recover + puff = drie acties → botst met "ONE calm beat"; `describesWholeBodyMotion()` onderdrukt terecht de signature-tic, maar de morph-kans stijgt) · Risico: middel-hoog.

**Oordeel:** Variant **B** is de productiekeuze: behoudt de comedy ("bonk" als foley) zonder de 10s-beat te overladen.

---

## Stap 6 — STRESS TEST REPORT

| Test | Scenario | Verwacht probleem | Kans op falen | Aanbeveling |
|------|----------|-------------------|---------------|-------------|
| 1 | Eén personage door veel scènes | Accessoire-swap *tussen* clips; lichte kleur/scarf-drift | Laag-middel | Per-clip accessoire-QC-checksum bij assembly |
| 2 | Snelle camerabewegingen | Preset-camera vs actie-shot conflict; motion blur/morph | Middel | Forceer `veoCameraOverride` bij niet-default shots; linter push/pull-check is er al |
| 3 | Complexe omgeving | Token-overload >450w → trailing identity-lock truncatie (P1) | Middel | Locks naar het midden; comprimeer boilerplate |
| 4 | Veel objecten tegelijk | Object drift (ei-staat, props) niet deterministisch afgedwongen | Middel | Ei-staat als monotone scene-property |
| 5 | Lange video (>60s) | Geen one-shot model; drift accumuleert over N geassembleerde 10s-clips; 10s-hardcode vs variabele duur (T3) | Middel-hoog | Duration parametriseren; reference-anchor per clip verversen; cross-clip identity-checksum |

---

## Stap 7 — EINDSCORE

| Onderdeel | Score | Toelichting |
|-----------|-------|-------------|
| Technische kwaliteit | **7.5/10** | Sterk gelaagd & deterministisch; T1 gefixt; T2/T3/T4 open |
| Prompt kwaliteit | **7/10** | Goede hiërarchie & token-economie; truncation-volgorde (P1) + comedy/SFX-conflict (P3) |
| Video kwaliteit | **8/10** | Character/scene drift laag dankzij reference-conditioning + guards |
| Consistentie | **7.5/10** | Accessoire-swap grotendeels weg; ei-staat & R1/R3-gaten resteren |
| Schaalbaarheid | **7/10** | Caches + single-source goed; guard-duplicatie (T2) & per-call rebuild (T5) |
| Productiewaardigheid | **7.5/10** | Productieklaar voor de huidige cast; bovenstaande fixes nodig vóór echte "miljoenen"-schaal |

**Eindoordeel:** Solide, bovengemiddeld pijplijnontwerp; **niet "goed genoeg"** zonder R1+R3 (accessoire-dekking), T3 (duration-leugen) en P1 (lock-volgorde). Aanbevolen prioriteit: **R3 → R1 → P1 → T3 → T2**.

---

## Direct doorgevoerd tijdens deze audit
- **T1 opgelost:** `accessoryModelCache` toegevoegd aan `VeoPromptCompiler.clearCaches()` (anders stale guard na elke bible-edit).

## Voorgesteld, nog niet doorgevoerd (wachten op go)
- R3-poortversoepeling in beide `AccessoryGuard`-kopieën (+ test `leavesSharedScarfUntouched` omdraaien naar "rewrite").
- R1 non-possessieve dekking + primaire `AccessoryValidator` in script-service.
- T3 duration doorgeven/afdwingen · P1 lock-volgorde · T2 parity-test/shared module.
```
