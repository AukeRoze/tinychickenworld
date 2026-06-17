# flow-in — landingsmap voor Flow-clips (aflevering 1)

Zet hier je **gedownloade Google Flow-clips** neer. Cowork (Claude) nummert ze
in de sessie en zet ze klaar als de bewegende beelden van deze aflevering.

## Hoe aanleveren

1. Download je clips uit Flow en sleep ze in deze map (`bible/afleveringen/1/flow-in/`).
2. Zorg dat ze **in scène-volgorde sorteerbaar** zijn. Het makkelijkst:
   benoem ze `01`, `02`, `03 …` (of `01-hook`, `02-setup`, …). Laat je de
   Flow-namen staan, zeg er dan even bij of de bestandsnaam-volgorde = scène-volgorde.
3. Kan de volgorde niet uit de namen, geef Cowork dan de mapping
   (bijv. "die met de zonsopgang = scène 3").

## Wat Cowork dan doet

- Hernoemt/kopieert de clips naar `bible/afleveringen/1/scene-1.mp4 … scene-N.mp4`
  (één map omhoog), in de juiste volgorde.
- Controleert of het aantal clips klopt met het aantal scènes in het script.
- Trapt daarna de import af (`import-flow-clips.bat <JOB-ID> 1`) zodat de montage
  jouw beelden gebruikt met de ElevenLabs-stemmen eroverheen.

> Voor een andere aflevering: maak `bible/afleveringen/<nummer>/flow-in/` aan en
> lever daar aan. Dezelfde werkwijze.
