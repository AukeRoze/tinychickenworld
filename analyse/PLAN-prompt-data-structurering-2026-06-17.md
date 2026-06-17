# Migratieplan — prompt-data structureren (proza → velden)

**Doel:** de consistency-guards stoppen met het scrubben van vrije bible-proza met
regex, en in plaats daarvan prompts assembleren uit **gestructureerde, cast-neutrale
DNA-velden**. Dit haalt de broosheid (één herformulering in `channel.yml` en een
scrub mist) en de duplicatie (zelfde regex-logica in image-service én orchestrator)
weg. Hoogste structurele ROI van de prompt-laag (zie BACKLOG P3).

Status: PLAN. Niets hiervan is gebouwd. Incrementeel + volledig back-compat: elke
stap laat de output byte-identiek tot de bible het nieuwe veld invult ("opt-in"),
exact het patroon waarmee `dna.veoSizeRank` al is ingevoerd.

---

## 1. Waarom (de huidige pijn)

De per-personage DNA in `bible/channel.yml` mengt drie soorten informatie in één
proza-string per veld (`silhouette`, `build`, `scaleAnchor`, `veoKey`):

1. **Eigen identiteit** — "blue-grey ANVIL-shaped body, tall red comb" (zuiver, prima).
2. **Vergelijkingen die ándere personages noemen** — "noticeably smaller and finer
   than Mo", "(where Pip is all curves … and Bo is a vertical line)".
3. **Flock-tellingen** — "the smallest of the trio", "the tiniest of the four".

(2) en (3) zijn de lekken: zodra de scène-cast gereduceerd is, noemt een aanwezig
personage een **afwezig** personage (weight-bleed → extra body), of impliceert een
count-woord meer personages dan in de roster staan. De huidige oplossing zijn
regex-guards die dit achteraf uit de proza scrubben:

- `PromptComposer.scopeDnaText` (image) — parenthese-/clause-drop op afwezige namen.
- `PromptComposer.neutraliseCountWords` + `VeoPromptCompiler.neutraliseCountWords` — trio/three/four → flock.
- `VeoPromptCompiler.scaleLockClause` — bouwt "Relative size" uit `veoSizeRank` (al gestructureerd, goede richting) met legacy `veoScaleRule`-fallback.

Problemen: (a) regex op natuurlijke taal is fragiel; (b) de logica staat dubbel in
twee Maven-modules, in sync gehouden met parity-tests; (c) elke nieuwe DNA-zin kan
een nieuw lek introduceren dat pas in een render zichtbaar wordt.

---

## 2. Doelmodel — DNA als data, prompt als template

Splits elk vergelijkend/tellend stuk uit de proza in **atomaire, cast-neutrale
velden** per personage. De prompt-compilers assembleren dan een zin uit alleen de
**aanwezige** cast — de guard wordt een datafilter, geen taalkundige scrubber.

### 2.1 Nieuwe/uitgebreide bible-velden (`characters[].dna`)

| Veld | Type | Voorbeeld (Pip) | Vervangt in proza |
|---|---|---|---|
| `silhouetteShape` | string, cast-neutraal | "a tiny, perfectly round ball-of-fluff; a small circle under an oversized round head" | het zelf-deel van `silhouette` (zonder "of the trio") |
| `bodyBuild` | string, cast-neutraal | "petite and fine, oversized head, baby proportions" | het zelf-deel van `build` (zonder "than Mo", "(where …)") |
| `sizeRank` | enum-achtig int + woord | `rank: 1`, `word: "the smallest"` | `veoSizeRank` (al aanwezig — promoveren tot dé bron) |
| `relativeNote` | **VERWIJDEREN** | — | alle "than X"/"(where X …)"-stukken |

`species` + `rosterNoun` bestaan al (sinds de scène-24/25-fix) en blijven de bron
voor de telling.

> Kern: **geen enkel DNA-veld noemt nog een ander personage bij naam.** Vergelijkingen
> worden afgeleid uit `sizeRank` bij het assembleren, alleen over aanwezige cast.

### 2.2 Afgeleide zinnen (compiler bouwt, niet de bible)

- **Roster-telling** (bestaat al, image-kant): tel `displayNoun()` over de aanwezige
  cast → "Exactly 2 chickens and 1 duckling total (3 maximum)…".
- **Relatieve grootte**: sorteer aanwezige cast op `sizeRank` → "Pip is the smallest,
  the duckling is the tiny baby" (≥2 aanwezig; anders weglaten). Dit is precies wat
  `scaleLockClause` nu al doet voor VEO — uitbreiden naar de image-kant en de legacy
  `veoScaleRule`-fallback schrappen.
- **Silhouet/build** in de prompt = puur `silhouetteShape`/`bodyBuild` (cast-neutraal)
  → geen scrub meer nodig, geen count-woorden meer aanwezig.

---

## 3. Migratiestappen (incrementeel, elke stap los te deployen)

**Stap 0 — meetlat.** Leg de huidige gecompileerde prompt voor 3 ijk-scènes vast
(solo Pip, Pip+Mo+Duck, volle 3-cast) als golden files. Elke stap diff't hiertegen:
ongewijzigd tot de bible opt-in't, daarna alleen het bedoelde verschil.

**Stap 1 — velden toevoegen (data, geen gedrag).** Voeg `silhouetteShape`,
`bodyBuild` toe aan `Character.Dna` (image) + de orchestrator-bible-reader, met
back-compat (leeg = val terug op de oude `silhouette`/`build`). Promoveer
`veoSizeRank` tot `sizeRank` (int + woord). Geen prompt-wijziging zolang de velden
leeg zijn.

**Stap 2 — bible invullen voor de 4 personages.** Schrijf de cast-neutrale
`silhouetteShape`/`bodyBuild` en zet `sizeRank` per personage. Laat de oude
`silhouette`/`build` voorlopig staan (worden genegeerd zodra de nieuwe gevuld zijn).

**Stap 3 — compilers laten kiezen.** `dnaLine` (image) en `characterDnaClauses`/
`characterVeoKeyClauses` (VEO) gebruiken `silhouetteShape`/`bodyBuild` als die er
zijn, anders de oude velden. Relatieve-grootte-zin overal uit `sizeRank` over
aanwezige cast. **Nu worden de regex-guards no-ops** (er staat geen afwezige naam /
count-woord meer in de bron) — maar ze blijven als vangnet staan.

**Stap 4 — guards degraderen tot assert/vangnet.** `scopeDnaText`/`neutraliseCountWords`
blijven draaien maar zouden niets meer mogen wijzigen; voeg een dev-/test-assert toe
die faalt als ze tóch iets veranderen (= iemand heeft per ongeluk weer een naam/teller
in een DNA-veld gezet). Zo bewaak je de nieuwe discipline zonder de regex als
productie-afhankelijkheid te houden.

**Stap 5 — opruimen.** Verwijder de oude `silhouette`/`build`-proza-vergelijkingen
en de legacy `veoScaleRule`-fallback. De guards mogen blijven als goedkope
defense-in-depth, of weg als de assert lang groen blijft.

**Stap 6 — deduplicatie (optioneel, los besluit).** Met de logica nu triviaal
(sorteer + join) is de duplicatie tussen modules klein. Als je 'm tóch wil weghalen:
één service genereert de geschoonde per-personage DNA en levert die via een veld in
de scene-payload, zodat de ander 'm alleen consumeert. Botst met de bewuste
geen-shared-lib-keuze — daarom apart en optioneel.

---

## 4. Risico's & mitigatie

| Risico | Mitigatie |
|---|---|
| Cast-neutrale herschrijving verliest een nuance ("petite vs plump") | Golden-file diff per ijk-scène in stap 0; handmatige review van de 4 nieuwe veldwaarden |
| `sizeRank`-zin leest houterig met 2 aanwezigen | Houd de woord-vorm (`"the smallest"`) naast de rank; compiler kiest woord, niet de rank |
| Stap 3 verandert ongewild de volle-cast-output | Golden file "volle 3-cast" moet identiek blijven (vergelijking dan accuraat → "trio" mag) |
| Twee modules raken uit sync tijdens migratie | Parity-test eerst uitbreiden met de nieuwe velden, dan pas migreren |
| Bible-editor zet later weer een naam in een veld | De assert uit stap 4 vangt het in de test/CI |

---

## 5. Volgorde & inschatting

1. Stap 0+1 (golden files + velden + back-compat) — klein, geen risico.
2. Stap 2+3 (bible invullen + compilers kiezen) — de echte verschuiving; per personage
   uitrolbaar, golden-file-bewaakt.
3. Stap 4 (guards → assert) — klein.
4. Stap 5 (opruimen) — klein, ná een paar groene runs.
5. Stap 6 (dedup) — apart te besluiten.

Begin met **Mo** als pilot (zijn `build`/`veoKey` hebben de meeste afwezig-naam- en
count-lekken), valideer op de 3 ijk-scènes, rol dan Pip/Bo/Duckling uit.

> Net als de rest van de stack: niets hiervan compileert tot `build.bat`. Voeg de
> golden-file-diff toe aan het bestaande eval-harnas (`infra/eval`) zodat elke
> prompt-/bible-wijziging meetbaar blijft.
