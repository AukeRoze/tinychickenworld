# Props-canon — ontwerpnotitie

Doel: hero-objecten (het ei, en straks elke prop die een hoofdrol speelt) dezelfde
**vaste look + behaviour door de hele aflevering** geven als de personages al hebben.
Nu hebben kippen een `dna`-blok dat de compiler in elke scène injecteert; objecten
hebben dat niet — die leunen op losse LLM-hints + het startbeeld → drift-risico.

Dit ontwerp leunt volledig op patronen die er al zijn: bible-DNA → compiler-injectie
→ linter. Geen nieuwe architectuur. Het is een code-wijziging in **orchestrator** +
**image-service** die mee kan in de redeploy-ronde. Eerst samen aftikken (de
"in te vullen"-velden onderaan), daarna bouw ik het.

Gerelateerd: [[character-canon-consistency]], [[veo-castlock-offframe-fix]]
(de bestaande zachte prop-continuity-guidance die dit hard maakt),
[[veo-castrecovery-guest-fix]] (`augmentPresentCast` = exact het detectie-patroon),
[[locatie-canon]].

---

## 1. Het gat (huidige stand, geverifieerd)

- **Personages = harde canon.** `bible/channel.yml` → `characters[].dna` (veoKey,
  scaleAnchor, tic, signatureSound, antiAccessory, …). De compiler prikt dat in
  élke scène waar de kip voorkomt, met anti-swap/anti-morph-locks.
- **Objecten = geen canon.** Er is **geen `props:`-sectie** in de bible. Het ei
  zit als hardcoded voorbeeld in de compiler ("the wings and the egg") en verder
  alleen als zachte instructie in `emit_script` ("track het ei, verplaats mee,
  prop-staat is eenrichting, geen ghost-copies"). Dat is een hint aan het
  script-model, geen vaste definitie. Hoe het ei eruitziet drijft dus per scène.

---

## 2. Schema — nieuw `props:`-blok in bible/channel.yml

Spiegelt het `characters[].dna`-patroon (korte, ondubbelzinnige regels, geen
afsluitende punt; de compiler voegt samen en interpunctueert). Voorbeeld voor het ei:

```yaml
props:
  - id: egg
    name: "The Wobbly Egg"
    role: hero                       # hero | recurring | minor — bepaalt of de canon HARD wordt geïnjecteerd
    aliases: ["egg", "the egg", "wobbly egg"]   # detectie-woorden (woordgrens \bword\b, net als augmentPresentCast)

    # Visuele canon — het veoKey-equivalent. Noemt GEEN scène-specifieke staat.
    veoKey: "a smooth matte cream-white egg, slightly larger than Pip's head, faint pale-brown speckles, rounded oval, never glossy"

    # Vaste schaal (zoals scaleAnchor bij characters)
    scaleAnchor: "about the size of Pip's head — never shrinks or grows between shots"

    # Anti-drift lock (zoals antiAccessory): wat het object NOOIT mag worden
    antiDrift: "never glossy or plastic, never pure white, never patterned, never a second egg"

    # Basis-geluid (canon door alle staten)
    signatureSound: "a soft hollow wooden tok-tok when tapped"

    # Toestand-machine — ÉÉNRICHTING. Volgorde van de lijst = toegestane progressie.
    # Per state een eigen `look` ÉN eigen `behaviour` (besluit #5).
    states:
      - id: intact
        look: "whole, unblemished cream shell"
        behaviour: "rests heavy and still; a gentle touch makes it give ONE slow heavy wobble then settle — never bounces, never rolls off on its own"
      - id: hairline
        look: "a single faint hairline crack, shell still fully closed"
        behaviour: "same heavy stillness; a faint creak when touched; the hairline crack never widens by itself"
      - id: cracked
        look: "a jagged crack with a small chip missing, shell still mostly together"
        behaviour: "settles with the chipped side down, tiny uneven wobbles, a soft rattle from within; never re-seals"
      - id: hatched
        look: "broken open into two empty halves"
        behaviour: "the two halves lie still where they fell, light as paper; permanent — never re-forms, never moves on its own"
```

> **Aliases (besluit #3):** bewust strak — `["egg", "the egg", "wobbly egg", "the wobbly egg"]`,
> altijd met woordgrens `\b…\b`. **`shell` NIET** als alias (false positives:
> "seashell", "shell of the …") — een ei dat alleen via "shell" wordt genoemd
> telt niet als aanwezig. Eventueel later uitbreiden als blijkt dat scripts het
> ei consequent anders noemen.

**Back-compat:** `props:` afwezig/leeg → geen injectie → byte-identiek oud gedrag
(golden tests veilig), net als `veoNativeAudio`/`veoSizeRank` hun fallback hebben.

---

## 3. Per scène — welke staat? → BESLUIT: A

Nieuw optioneel scène-veld **`propStates`**, bv. `{ egg: cracked }`, gezet door de
script-LLM via `emit_script`. Default = de staat van de vorige scène (eenmaal gezet
blijft 'ie staan tot een latere scène 'm verhoogt). Expliciet, te valideren, geen
giswerk uit vrije tekst. Geen afleiden-fallback in de eerste versie.

---

## 4. Injectie in de prompt (orchestrator `VeoPromptCompiler`)

1. **`presentProps(visualText, lines)`** — detecteert aanwezige props via `aliases`
   met woordgrens (hergebruik de `\bword\b`-aanpak uit `augmentPresentCast`, incl.
   de "duck under ≠ duckling"-les).
2. Voor elk aanwezig **hero**-prop: nieuwe **`KEY OBJECT`-sectie** in de
   `directorBrief`, naast de `CHARACTER ROSTER`:
   ```
   KEY OBJECT — THE WOBBLY EGG (exactly one, never duplicated):
   <veoKey>. <scaleAnchor>. Current state: <state.look>. <behaviour>. <antiDrift>.
   ```
3. **Anti-duplicatie** analoog aan de roster: "exactly one egg, no second egg
   anywhere (background/edges/bokeh)".
4. **recurring/minor** props: alleen een korte één-regel-mention of overslaan
   (niet de volle harde injectie).

Zelfde injectie in **image-service** `PromptComposer.composeReference` zodat het
startbeeld dezelfde canon krijgt. **Thumbnail NIET** (besluit #4) — `composeThumbnail`
blijft ongemoeid.

---

## 5. Bewaking (linter + critic)

- **`VeoPromptLinter`** (deterministisch): als een hero-prop in de actietekst staat,
  MOET de `KEY OBJECT`-sectie aanwezig zijn (zoals de Cast-lock-check nu).
- **Toestand-monotonie** (cross-scene, op de scène-lijst — niet per-prompt): de
  state-index van een prop mag over de seq-volgorde nooit dalen (ei gaat nooit van
  `cracked` terug naar `intact`). Hangen aan de bestaande continuity-as van
  `ScriptCritic` of een aparte kleine validator. Past op de "SINGLE REVEAL"-regel
  die er al is.

---

## 6. Migratie

- Het ei uit de **hardcoded compiler-voorbeelden** halen en naar `props:` verplaatsen
  (single source of truth).
- Bestaande zachte `emit_script` prop-continuity-guidance laten staan als vangnet,
  maar de harde lock wordt nu leidend.

---

## Besloten (22 jun 2026) — build-ready

1. **Staat-bron:** A — expliciet scène-veld `propStates`, default = vorige staat. ✓
2. **Scope:** alleen `egg` (`role: hero`). Andere props later. ✓
3. **Aliases:** `["egg", "the egg", "wobbly egg", "the wobbly egg"]`, woordgrens; geen `shell`. ✓
4. **Image-kant:** alleen `composeReference`; thumbnail ongemoeid. ✓
5. **Behaviour:** per-state eigen `behaviour` (+ basis `signatureSound`). ✓
6. **Linter-monotonie:** strikt monotoon, geen flashback-uitzonderingen (geen flashbacks in de serie). ✓

## Build-omvang (volgende stap)

Werkkopie-wijzigingen, gaan mee in de bestaande redeploy-ronde (kan hier niet
gecompileerd/gecommit worden — sandbox heeft geen JDK21/Maven en geen `.git`-schrijf):

- `bible/channel.yml` — nieuw `props:`-blok met de egg-definitie hierboven.
- orchestrator `VeoPromptCompiler` — `presentProps()` + `KEY OBJECT`-injectie in
  `directorBrief` + anti-duplicatie; `SceneDto.propStates` doorgeven.
- orchestrator `ScriptTool` (`emit_script`) — guidance + schema voor `propStates`.
- image-service `PromptComposer.composeReference` — zelfde injectie.
- `VeoPromptLinter` — KEY-OBJECT-aanwezigheidscheck + cross-scene monotonie.
- Tests — `VeoPromptCompiler*Test` (present/absent → byte-identiek), linter-monotonie.
