# Flow clip generator (Playwright)

Genereert TinyChickenWorld-clips in Google Flow **automatisch**, aangestuurd vanuit
je eigen ingelogde Chrome. Je draait dit **zelf, lokaal** — dus het kost **geen
Claude-credits**.

## Belangrijk over credits (lees dit even)

- **Geen Claude-credits:** klopt — je draait het script zelf, Claude is niet betrokken.
- **Wel Flow-credits:** elke Omni-clip kost ~15 Flow-credits. Dat rekent Google
  **server-side** af, of jij klikt, of een script klikt. Geen enkele automatisering
  maakt generaties gratis. Je Flow-credits **refreshen wel dagelijks**, dus je
  betaalt niets extra uit je portemonnee — maar je zit aan je dagelijkse pool vast
  (dus niet écht "oneindig" binnen één dag).

De winst van dit script: **geen handwerk en geen Claude-gebruik.**

## Wat het doet

Per scène (uit een JSON-bestand):
1. cast vastzetten via `@`-mentions (de **Character**-referenties Pip/Mo/Bo),
2. een korte **actie-prompt** typen,
3. op **genereren** klikken.

Flow rendert de clips parallel in de wachtrij. (Korte actie-prompt + @-referenties
werken veel beter dan de volledige lange Veo-prompt — die gaf een statische
groepsshot.)

## Eenmalige setup

1. **Node.js 18+** en Playwright installeren (in deze map):
   ```
   npm init -y
   npm i playwright
   ```
   (We koppelen aan je bestaande Chrome, dus `npx playwright install` is niet nodig.)

2. **Chrome starten met remote-debugging** (sluit Chrome eerst helemaal af). Op Windows:
   ```
   "C:\Program Files\Google\Chrome\Application\chrome.exe" --remote-debugging-port=9222 --user-data-dir="%LOCALAPPDATA%\Google\Chrome\User Data"
   ```
   Door je eigen `user-data-dir` te gebruiken ben je meteen ingelogd bij Google/Flow.
   (Liever een apart profiel? Laat `--user-data-dir` weg of wijs een lege map aan en
   log één keer in.)

3. In díé Chrome: open je **Tiny Chicken World**-project en zet het generatiepaneel
   één keer goed: **Video · Ingredients · Omni Flash · 10s · 1x**. Die instelling
   blijft staan, dus het script hoeft 'm niet aan te raken.

## Draaien

```
node generate-clips.mjs ep4-puddle-jumpers.json
```

Wil je dat het script eerst naar een specifiek project navigeert:
```
set FLOW_PROJECT=https://labs.google/fx/tools/flow/project/<jouw-project-id>
node generate-clips.mjs ep4-puddle-jumpers.json
```

## Andere afleveringen

Maak gewoon een nieuw JSON-bestand met dezelfde vorm en draai het:
```json
[
  { "name": "scene-1", "cast": ["Pip","Mo","Bo"], "prompt": "korte actie-prompt..." },
  ...
]
```
### Scènes automatisch uit een aflevering halen (geen handwerk, geen credits)

Met `build-scenes.mjs` haal je de korte prompts + cast rechtstreeks uit een
orchestrator-job (de aflevering moet wel een script hebben — gegenereerd of
geïmporteerd). Het bouwt per scène een korte actie-prompt uit **Omgeving +
visualDesc + dialoog**, en leidt de cast af uit de Veo-roster.

```
node build-scenes.mjs <jobId> ep5.json
node generate-clips.mjs ep5.json
```

Het `jobId` vind je in de orchestrator-UI in de job-URL
(`.../ui/job.html?id=<jobId>`). Wijkt de orchestrator-poort af? Zet `ORCH`:
```
set ORCH=http://localhost:8080
```
Dit kost niets — het leest alleen de API. Zo maak je voor elke nieuwe aflevering
in één commando de scenes-JSON.

## Alles in één commando

```
node run.mjs <jobId>
```
Doet build-scenes + generate-clips achter elkaar voor die aflevering.

## Eén scène opnieuw

Alleen scène 4 (her)genereren:
```
node generate-clips.mjs ep4.json 4
```
Of via de agent: `POST http://localhost:9223/generate/<jobId>/4`.

## Personage-hints (bv. Bo's lange nek)

De @-referenties brengen niet altijd elk kenmerk over (Bo's lange rechtopstaande
nek dreef vaak weg naar een rond bolletje). Daarom voegt `build-scenes.mjs` een
korte hint toe zodra een personage in de scène zit — zie de `HINTS`-map bovenin.
Standaard zit Bo's nek erin; voeg gerust meer toe (bv. voor Pip of Mo). De
meegeleverde `ep4-puddle-jumpers.json` heeft de Bo-hint al per scène.

## Vanuit de frontend aanroepen (lokale agent)

Een browserpagina mag zelf geen Node/Chrome starten, dus draai het kleine
agent-servertje en laat de frontend dáárheen fetchen:

```
node server.mjs            # luistert op http://localhost:9223
```

In de orchestrator-UI (of een bookmarklet) roep je het zo aan:
```js
fetch(`http://localhost:9223/generate/${jobId}`, { method: 'POST' });
```
De agent draait dan build-scenes + generate-clips voor dat jobId. Status checken:
`GET http://localhost:9223/status`. Alleen lokaal bereikbaar; CORS staat alleen
`http://localhost:8080` toe (override met `FLOW_AGENT_ORIGIN`).

**Knop op de jobpagina (toegevoegd):** in `job-page.js` staat nu een
**"▶ Genereer in Flow"**-actie bij de andere job-acties; die doet bovenstaande
fetch naar de agent. Voorwaarden om 'm te gebruiken: `node server.mjs` draait,
Chrome staat aan met remote-debugging en is ingelogd in Flow. De knop verschijnt
na een **redeploy/refresh van de orchestrator** (harde refresh, Ctrl+F5).

## Als een stap faalt (selectors)

Flow is een React-app; soms verschuift de UI en breekt een selector. Het script
logt welke scène/stap faalde. Pak dan de juiste selector met:
```
npx playwright codegen https://labs.google/fx/tools/flow
```
en pas 'm aan in `generate-clips.mjs` bovenin (`SEL`), of zet de genereer-knop via:
```
set FLOW_SUBMIT=<jouw-selector-voor-de-genereer-knop>
```
Een gefaalde **setup**-stap kost geen credits — Flow rekent pas af als een
generatie écht afgevuurd wordt.

## Knoppen / env-variabelen

| Variabele      | Default                  | Wat                                            |
|----------------|--------------------------|------------------------------------------------|
| `FLOW_CDP`     | `http://localhost:9222`  | CDP-adres van je Chrome                         |
| `FLOW_PROJECT` | (leeg)                   | optioneel project-URL om eerst te openen        |
| `FLOW_PACING`  | `6000`                   | ms wachten tussen scènes                        |
| `FLOW_SUBMIT`  | (auto)                   | selector voor de genereer-knop, als auto faalt  |
