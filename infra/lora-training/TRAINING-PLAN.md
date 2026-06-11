# Cast LoRA — de grote heraanpak (kwaliteit echt omhoog)

## Waarom de huidige LoRA generieke kuikens geeft (root cause, bevestigd)

De training is op elke as fout:

1. **Beelden:** `dataset-archive-watercolor/` = generieke **witte aquarel-kippen**, zonder
   de onderscheidende kleuren/accessoires van de cast.
2. **Captions:** koppelen de triggerwoorden aan de **verkeerde ontwerpen**:
   - `pip_chicken` → "red-orange chicken, golden-yellow feathers, **blue** scarf, watercolor"
     (moet: **blauw-grijze** kip, **oranje** sjaal, Pixar 3D)
   - `bo_chicken` → "white chicken with black spots and a **pink bow**"
     (moet: **tan** kip met **ronde witte bril**)
3. **Stijl:** alles is "soft watercolor storybook" — het kanaal is nu **Pixar/Illumination 3D**.

→ De LoRA heeft letterlijk de verkeerde cast in de verkeerde stijl geleerd. Inference-prompts
kunnen dit niet redden. **Garbage in, garbage out.** De enige echte fix is opnieuw trainen op
een correcte dataset.

## Principe: de LoRA is zo goed als zijn referentieset

De grootste hefboom is niet de training-parameters, maar de **kwaliteit en consistentie van de
referentiebeelden**. Een drift-vrije, on-model set van 12-20 perfecte beelden per personage
verslaat 30 rommelige. Daarom staat de dataset centraal in dit plan.

## Anker: je hebt de canonieke cast al — het logo

`bible/logo.png` toont Pip (blauw-grijs), Mo (strohoed) en Bo correct in Pixar 3D. Dat is je
**model sheet / ground truth**. We genereren de referentieset *geconditioneerd op dat ontwerp*,
niet vrij uit tekst (tekst-only is exact de drift-bron).

---

## Stappenplan

### Fase 1 — Canonieke ontwerpen vastleggen
- Crop de drie personages uit `logo.png` → `refs/pip_anchor.png`, `mo_anchor.png`, `bo_anchor.png`.
- Dit zijn de ijkbeelden waar alle referenties op moeten lijken.

### Fase 2 — Consistente referentieset bouwen (de crux)
Tekst-only generatie drift; gebruik **reference-conditioned** generatie zodat elk beeld on-model
blijft en alleen pose/hoek/expressie varieert. Beste→snelste opties:
1. **Image-to-image / IP-Adapter / "consistent character"** geseed op het anker (Flux Redux/IP-Adapter
   of een consistent-character model op Replicate, of gpt-image-1 met het anker als referentiebeeld).
2. **Bootstrap (dreambooth-loop):** genereer een batch → keihard cureren (alleen perfect on-model
   houden, ~80% weggooien) → train v1 → genereer schonere refs mét v1 → cureren → train v2. Elke
   ronde wordt strakker.
3. **Meedogenloos cureren** sowieso: 12-20 perfecte beelden/personage. Kwaliteit > kwantiteit.

Variatie in de set (per personage): front / 3-4 / zij, close-up + full-body, paar expressies,
wisselende eenvoudige achtergronden (zodat de LoRA het personage leert, niet de achtergrond).
Accessoires in **élk** beeld correct. Geen twee personages in één single-character ref.

### Fase 3 — Correcte captions (bible-accuraat, Pixar 3D)
Vaste identiteitszin per personage + variërend deel. Gebruik deze templates:

Banner-true mapping: **Pip = cream-white + straw hat**, **Mo = blue-grey + red scarf**, **Bo = tan + round glasses + green scarf**.

- **Pip:** `pip_chicken, a fluffy newly-hatched baby chick with soft cream-white down feathers, a bright red pointy comb, extra-large round shiny brown eyes, a tiny straw farmer hat, a red bandana around the neck, small orange beak, glossy 3D Pixar Illumination cartoon style, <pose/angle/expression>`
- **Mo:** `mo_chicken, a round fluffy baby chick with soft blue-grey down feathers, a bright red pointy comb, a red knitted scarf around the neck, extra-large shiny brown eyes, small orange beak, glossy 3D Pixar Illumination cartoon style, <pose/angle/expression>`
- **Bo:** `bo_chicken, a fluffy baby chick with tan and sandy-brown down feathers, a small feather tuft on top, round thin-framed eyeglasses on the beak, a green scarf around the neck, extra-large shiny eyes, small orange beak, glossy 3D Pixar Illumination cartoon style, <pose/angle/expression>`

Houd het identiteitsdeel **identiek** over alle captions van een personage, varieer alleen het
laatste stuk → de trigger bindt sterk aan de juiste features.

### Fase 4 — Trainen: per-personage LoRA's (aanbevolen)
Eén gedeelde LoRA gaf "alle drie zien er identiek uit". Train daarom **drie aparte LoRA's**
(pip / mo / bo) zodat elke identiteit schoon wordt geleerd, en combineer ze bij inference met
multi-LoRA (de bible ondersteunt `lucataco/flux-dev-multi-lora` met meerdere `hf_loras`).
- Params (startpunt): rank 16-32, ~1500-2500 stappen/personage, LR ~1e-4, 1024px, Pixar-base.
- Trade-off: scènes met meerdere personages activeren meerdere LoRA's tegelijk (ondersteund).

### Fase 5 — Validatie-loop (dit maakt het verschil)
Na trainen: genereer een **validatie-grid** per personage × {front, zij, 3-4, close-up, full-body,
samen met een ander personage}. Leg naast de anker-sheet. Drift een personage → meer/betere refs
voor dát personage, opnieuw trainen. Herhaal tot het grid consistent on-model is. Niet doorgaan
naar productie vóór het grid klopt.

### Fase 6 — Inhaken + verifiëren
- Nieuwe LoRA-URL('s) in `bible/channel.yml` (`imageGen.replicate`), `castLoraScale` ~1.0.
- Eén testvideo tot de **per-scène image-review gate** → controleer Pip/Mo/Bo (kleur + accessoires).

---

## Onderscheidbaarheid (bonus-kwaliteit)
Pip (blauw-grijs), Mo (crème), Bo (tan) liggen kleur-technisch dicht bij elkaar. De accessoires
(sjaal/hoed/bril) doen het onderscheid. Overweeg de lichaamskleuren iets **sterker te
contrasteren** zodat kinderen ze ook op klein formaat/silhouet uit elkaar houden.

## Kosten/effort per iteratie
- Referentie-generatie: ~$3-5 · Training (3 LoRA's): ~$6-24 · Validatie: ~$1.
- Reken op 2-3 iteraties tot het echt staat. Eénmalig; daarna is inference sub-1¢/beeld.

## Wat ik kan bouwen/uitvoeren
1. De `generate-reference-images` herschrijven naar **reference-conditioned** generatie (anker = logo-crops).
2. De caption-generator herschrijven naar de **correcte bible-accurate** templates hierboven + Pixar-stijl.
3. De `dataset-archive-watercolor` archiveren en een schone `dataset/` opzetten.
4. De train-config naar **per-personage LoRA's** zetten.
5. Het validatie-grid-script schrijven.

Zeg welke stappen ik mag uitvoeren (sommige kosten Replicate/OpenAI-credits) — dan begin ik bij
fase 1-3 (de dataset), want dáár zit 80% van de kwaliteitswinst.
