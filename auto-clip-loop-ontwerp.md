# Auto-clip-loop — ontwerpnotitie

Doel: één nacht ongemoeid laten draaien. De loop maakt clips, beoordeelt ze in
drie trappen, past zwakke prompts automatisch aan en herstart, en zet 's ochtends
per scène de top 3 klaar zodat Auke zelf kiest. Geen automatische eindmontage.

**Scope (vastgesteld):**
- **Clips = lokaal model via de bestaande orchestrator** (`reroll-veo`, Veo-backend
  vervangen). Gratis. De enige grens is wandklok-tijd (één nacht) en schijfruimte.
- **Startbeelden = vooraf door Auke goedgekeurd.** De loop genereert of wijzigt
  géén startbeelden. Per scène ligt het goedgekeurde startbeeld vast als input;
  de loop varieert **alleen de clip** die daaruit volgt.
- **Loop-model = "genereren 's nachts, beoordelen 's ochtends" (Model B).**
  's Nachts draait een onbeheerd script dat per scène veel clips maakt en alleen
  de **trap-1 scriptgate** toepast (geen model nodig, echt gratis). 's Ochtends
  doe ik trap 2+3 (vision) op de overlevers, rangschik en kies de top 3. De
  inhoudelijke prompt-bijstelling (op basis van trap 2) gebeurt dus pas in mijn
  ochtendronde, als aanbeveling voor een eventuele volgende nacht — niet live
  's nachts.

Dit is het ontwerp om samen af te tikken (drempels, gewichten, regels). Daarna
bouw ik het runner-script + de scoring-config die hierop leunt.

---

## 1. Aansturing — past op de file-bridge

De loop draait headless als runner-script naast de bridge. Alle pipeline-acties
gaan via `D:\claude\youtube-channel\bridge\commands\<id>.json` → resultaat in
`bridge\results\<id>.json`. Alleen `localhost orchestrator:8080` / `image:8084`,
alleen `/api/v1/*`, sequentieel (poll 2s, timeout 900s). Bestandsnamen met
oplopende prefix (`t…`, `u…`, …) om volgorde te garanderen; een status-commando
ná een lang commando werkt als "klaar"-signaal.

Voorwaarde: `agent-bridge.ps1` moet in een venster bij Auke blijven draaien. Valt
dat venster om, dan stopt de loop vanzelf (geen results meer) — dat is veilig,
geen stille fouten.

Clip maken per scène draait nu op het **lokale videomodel** (Veo eruit). Open
bouwvraag: wordt het lokale model aangeroepen via de bestaande orchestrator
(zelfde `reroll-veo`-pad, andere backend) of via een apart commando dat de runner
direct triggert? Dit bepaalt hoe de runner de bridge gebruikt. **Reroll mag
nooit hermonteren** — alleen de QC-lus herscoort per iteratie.

Startbeeld komt niet uit de loop: de runner leest per scène het goedgekeurde
startbeeld en voert dat als vaste input aan het lokale model.

---

## 2. Beoordeling — getrapt, oplopend in kosten

Een clip moet trap 1 halen voordat hij trap 2 in mag, enz. Zo verspillen we geen
dure analyses aan kapotte clips.

### Trap 1 — technische gate (gratis, script)
Harde checks; zakt een clip → meteen weg.

| Check | Faalt als |
|---|---|
| Morphing / karakterdrift | gezicht-/kleurdrift over frames boven drempel |
| Lengte | korter/langer dan scène-target ± marge |
| Zwarte / bevroren frames | > X aaneengesloten frames identiek of zwart |
| Clip aanwezig | `hasClip` per scène (telt mee tegen de veoComplete-valkuil) |

> Drempels (in te vullen): driftscore-max `__`, lengtemarge `±__s`,
> max-zwart-frames `__`.

### Trap 2 — prompt-adherentie (vision, ik)
Van de overlevers lees ik steekframes en toets aan de scène-bedoeling:
juiste personage(s), locatie, actie, geen artefacten. Score **0–10**.

> Drempel om door te mogen: `≥ __/10`. Rubric per scène-type → §4.

### Trap 3 — hook / openingskracht (vision, ik — GEEN MCP)
Bewust géén `virality_predictor`/`video_analysis` (kost MCP-credits → geschrapt).
In plaats daarvan beoordeel ik zelf de eerste ~1,5s en de algehele
openingskracht op steekframes: sterke opening, leesbare actie, pakkend beeld.
Score **0–10**. Heuristiek, geen getraind viraliteitsmodel — prima als
tiebreaker, gratis (alleen sessie-tokens). Alleen relevant voor hero/opening.

### Eindscore
```
eind = w1·trap2_adherentie + w2·trap3_viraliteit
       (trap 1 is een gate, telt niet mee in de som)
```
> Gewichten (in te vullen): `w1 = __`, `w2 = __` (som = 1).
> Voorstel om mee te starten: w1 0,6 / w2 0,4 — adherentie eerst, hook als
> tiebreaker. Aanpasbaar.

Per scène bewaren we de **top 3** met scores + redenen, niet één.

> Beoordeling is gratis (trap 1 = script, trap 2 + 3 = mijn vision-oordeel,
> geen MCP-credits) én clips zijn lokaal/gratis én startbeelden zijn vooraf
> gemaakt. Daarmee kost een nacht draaien feitelijk niets — alleen tijd,
> stroom en schijfruimte (zie §5).

---

## 3. Prompt-aanpasregels (auto, bij clip onder drempel)

Het startbeeld ligt vast (vooraf goedgekeurd) — dus dat is géén lever meer. De
loop stuurt alleen de **clip-generatie** vanuit dat vaste beeld. Levers:

1. **Zakt op trap 1 (morphing/drift):** plain reroll (lokaal model is gratis,
   dus eerst gewoon opnieuw proberen) en/of model-parameters bijstellen
   (motion/sterkte, seed indien het lokale model seeds respecteert). Startbeeld
   blijft ongemoeid.
2. **Zakt op trap 2 (adherentie):** clip-prompt-tekst bijstellen volgens vaste
   patronen — actie explicieter, camerabeweging concreter, storende elementen
   negatief benoemen. Startbeeld blijft.
3. **Zakt op trap 3 (hook):** alleen relevant voor hero/opening-scènes —
   camerabeweging/energie in de prompt opvoeren; anders niet forceren.
4. **Geen verbetering na N pogingen:** scène markeren als "beste-tot-nu-toe",
   stoppen, door naar volgende scène. Geen oneindig rerollen (tijd is de grens,
   niet geld).

> In te vullen: `N` max pogingen per scène (voorstel 5), en de concrete
> tekst-patronen per faalreden (lijstje dat ik kan uitschrijven).

---

## 4. Adherentie-rubric per scène-type (concept)

Wordt per scène-type een checklist die ik 0–10 score. Te vullen met jouw
scène-typen; voorbeeld-assen:

- **Dialoog/personage:** juiste cast aanwezig, herkenbaar, geen extra figuren,
  lipsync/expressie plausibel.
- **Establishing/locatie:** juiste setting, tijd van dag, sfeer past bij script.
- **Actie:** de bedoelde beweging gebeurt en is leesbaar.
- **Hero/opening:** bovenstaande + sterke eerste 1,5s (hook).

---

## 5. Stop-condities (hard, ingebouwd)

Clips zijn lokaal en gratis → **geen kostenplafond**. De grenzen zijn tijd en
schijf. De loop pauzeert en logt — nooit stilletjes doordraaien — bij:

- **Tijdsbudget op** (de nacht voorbij / einduur bereikt) → afronden, top 3
  bewaren met wat er is.
- **Schijf vol** (bekend risico, clips stapelen snel op) → stop, log, wachten op
  Auke. Overweeg: afgekeurde clips per iteratie opruimen om schijf te sparen.
- **Max-rerolls** per scène (zie §3, `N`) en globaal `__` voor de hele nacht.
- **Bridge / lokaal model stil** (geen output) → loop stopt vanzelf.

---

## 6. 's Ochtends — review (geen auto-montage)

1. Per scène top 3 klaargezet met scores + één regel waarom.
2. Ik lever een overzicht: scène → 3 kandidaten, mijn voorkeur gemarkeerd.
3. Auke kiest per scène → dan pas één expliciete Re-assemble.

---

## Openstaand om af te tikken (de "in te vullen" velden)

**Bouw-essentials (nodig om het script te schrijven):**
1. Runtime op Auke's machine voor de runner: PowerShell (sluit aan op de bridge)
   of Python? + zijn `ffmpeg`/`opencv` beschikbaar voor de trap-1 frame-checks?
2. `reroll-veo`-contract: synchroon of async? Geeft het antwoord het clip-pad
   terug, en waar schrijft de orchestrator de clipbestanden (zodat trap-1 ze leest)?
3. Kan per reroll variatie/prompt mee, of rerollt het de opgeslagen scène-prompt?
4. Generatietijd per clip lokaal (bepaalt volume/nacht).

**Tuning (defaults in de config, later bij te stellen):**
5. Trap 1-drempels: driftscore, lengtemarge, max-zwart-frames.
6. Trap 2-drempel (`≥ /10`) en de rubric-checklists per scène-type (ochtendronde).
7. Eindscore-gewichten `w1`/`w2`.
8. `N` pogingen per scène + globaal nacht-maximum + einduur.
9. Schijf opruimbeleid (afgekeurde clips wel/niet wissen per iteratie).
