# EP3 (egg-job e2ec9448) — directe patch voor scène 5 & 8

Deze twee commando's corrigeren de huidige EP3-run **in de data zelf** (los van
de code-deploy). Ze staan bewust in `bridge/staged/` en NIET in `bridge/commands/`,
zodat de bridge-watcher ze niet automatisch uitvoert terwijl de gecrashte job nog
aan het herstellen is.

## Activeren (wanneer jij wilt)

Verplaats de twee JSON-bestanden naar `bridge/commands/` (de watcher pikt ze dan
op). Bijvoorbeeld in PowerShell:

```powershell
Move-Item bridge\staged\ep3-s05-fix-glasses.json        bridge\commands\
Move-Item bridge\staged\ep3-s08-fix-bonk-dialogue.json  bridge\commands\
```

## Wat ze doen

- **ep3-s05-fix-glasses.json** → `POST /scenes/5/edit`
  Vervangt de visualDesc van scène 5: `adjusting his glasses` →
  `thoughtfully adjusting his thick red knitted scarf`. Regenereert meteen de
  still. Mo raakt nu zijn EIGEN accessoire aan → geen bril-morph meer, geen
  botsing met de `Mo must NEVER wear glasses`-lock.

- **ep3-s08-fix-bonk-dialogue.json** → `POST /scenes/8/edit-dialogue`
  Haalt de gesproken regel `Bo: "Bonk!"` weg. Bo's dialoog eindigt nu op
  `…like a real hen!`, Pip zegt `Bo, wait—`, en Bo valt fysiek om. De "bonk"
  hoort thuis op de SFX/foley-laag (de compiler-guard zet 'm daar automatisch
  zodra de nieuwe code gedeployed is). Werkt ook subtitles + narration bij.

## Let op — controleer het video-id

Beide paden gebruiken job-id `e2ec9448-2e63-45bb-8801-794d5d299723` (de egg-job
uit het reroll-voorbeeld). Klopt dit niet meer? Pas het id in beide bestanden aan.

## Alternatief zonder data-edit

Als je liever de opgeslagen tekst intact houdt: deploy eerst de nieuwe code
(de accessory-guard + SFX-split herstellen scène 5 en 8 automatisch bij het
compileren) en doe daarna alleen een **reroll** van scène 5 (image) en scène 8
(veo). De guard corrigeert dan bij het bouwen van de prompt, met behoud van alle
overige visualDesc-details.
