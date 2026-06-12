# Concept EP 4 — "Duckling's First Day!" (werktitel)

**Volgt op:** ep 3 "🐣 Pip Found a Wobbly Egg!" (eendje geboren, naam-vraag uitstaand, "See you tomorrow, friends!")
**Serie-architectuur:** dit is de payoff-aflevering van de naam-CTA — terugkerende kijkers MOETEN binnen 15 seconden horen dat hun roep "gehoord" is.
**Format-opties:** standaard aflevering (A) of de eerste song-aflevering (B). Advies: A nu, B als ep 5 — twee nieuwe dingen tegelijk (nieuw castlid én eerste song) maakt leren uit analytics onmogelijk.

---

## De drie verplichte elementen (uit de audits)

1. **Naam-callback (seriescontinuïteit).** Open met de naamonthulling: Pip "luistert" (wing aan oor, zelfde pose als de ep 3-closer — visuele rijm!) en "hoort" de naam. De gekozen naam komt uit de Community-poll; tot die binnen is, is de placeholder **"Kwakkie"** (werkt in NL én EN, en het eendje "zegt" hem bijna zelf). Daarna: naam invullen in `bible/channel.yml` → `characters[duckling].name` vóór de job start.
2. **De KWAK-les (educatief feitje).** Eén expliciete, herhaalbare regel: *"Chickens say TOK. Ducks say KWAK!"* — minimaal 3× terug laten komen (ontdekking → meedoen-beat → recap). Dit is het feitje dat een kind aan tafel navertelt.
3. **Made for Kids-proof CTA.** Geen comments-belofte. Meedoe-beat: *"Can YOU kwak? Kwak kwak!"* + bevestiging ("I heard you! The loudest kwak ever!").

## Verhaalskelet (suggestie, ~26-29 scènes)

- **Hook:** de naamonthulling + het eendje doet iets onverwachts: hij zegt "TOK?" — een eendje dat kip probeert te zijn. Omkering van ep 3 (chicks die KWAK ontdekten).
- **Setup:** eendje kopieert alles een halve tel te laat (bible-tic!): Pip hupt → hij tuimelt; Bo's bril-duw → hij heeft geen bril, valt om. Mild, liefdevol.
- **Development:** de chicks proberen het eendje "kip-dingen" te leren (scharrelen, tok-tok, stofbad) — mislukt komisch. Dal: het eendje wordt stil — *hij kan niks "goed"*. Mo: "You don't have to be a good chicken. You're a GREAT duck."
- **Climax:** de vijver. Het eendje ziet water — en doet het enige dat geen enkele kip kan: **zwemmen**. De chicks staan paf aan de kant (drie chicks op een rij = toegestane trio-scène).
- **Resolutie:** rollen omgedraaid — het eendje "leert" de chicks pootje-baden (één teen, gilletjes). Les-recap: iedereen kan iets anders, en dat is precies goed. KWAK-les nog 1× als grap (Bo: "TOK!" — eendje: "KWAK!" — high-five).
- **Closer:** zonsondergang aan de vijver, "See you tomorrow, friends!"

## Productie-notities

- **Cast-regels:** duckling staat nu in de bible — de cast-cap (max 2 per normale scène, 3 op hook/climax/closer) geldt óók voor hem. Veel two-shots eendje+één chick = de consistentie-sweet-spot.
- **Nieuwe locatie:** `pond` bestaat in de bible — eerste aflevering die hem als hoofdlocatie gebruikt (visuele variatie + zwem-payoff).
- **Veo-aandachtspunt:** zwemmende personages zijn een nieuw bewegingstype; geef de climax-scènes een `motionDesc` en `endPose`, en reken op 1-2 clip-QC re-rolls (webbed feet ≠ chicken toes is de drift om op te letten — staat al in de duckling-antiAccessory).
- **Muziek:** `tiny_mystery` was ep 3; kies hier een speelse/zonnige track; climax-swell op het zwem-moment.
- **Metadata:** titel-opties: "🐥 Duckling's First Day!" / "🐥 Kwakkie Can't Cluck!" / "What Can a Duckling Do?". De metadata-policy dwingt serie-regel + hashtags nu automatisch af.

## Optie B — als song-aflevering (geparkeerd voor ep 5)

"**The Tok Tok Kwak Song**": het tok-tok-ritmespel van ep 3 + de KWAK als punchline is letterlijk al een call-and-response-song. Structuur: couplet per chick (tok-tok in eigen tempo) → eendje antwoordt steeds KWAK → meezing-refrein. Suno-pad bestaat al (`pipeline.suno-instrumental` + `songs/generate`); vergt vooral een lyrics-pass en de karaoke-mix. De grootste onbespeelde as uit de studio-benchmark (Cocomelon 55%).

---

**Vóór de job start:** ① naam uit de poll in de bible zetten, ② duckling-ref-stills genereren en goedkeuren via de Cast-pagina (zoals bij Pip/Mo/Bo), ③ `VOICE_ID_DUCKLING` kiezen in ElevenLabs (piepklein, zacht, hoog).
