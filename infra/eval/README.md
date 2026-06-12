# Offline eval-harnas (JUnit — €0, geen service nodig)

De goedkoopste en snelste trap van de eval-ladder: een **deterministische
JUnit-laag** in script-service die zonder LLM-call, zonder netwerk en zonder
draaiende stack pint wat een prompt- of validatorwijziging nooit stuk mag
maken. Draai dit ALTIJD eerst; de LLM-eval hieronder (`run-eval.py`) en de
golden-test zijn de duurdere trappen erboven.

```bash
mvn -pl services/script-service test -Dtest=*EvalHarness*   # alleen het harnas
mvn -pl services/script-service test                        # draait het ook mee
# rapport: services/script-service/target/eval-report.md
```

## Wat het pint

**`PromptEvalHarness`** bouwt voor 8 bevroren briefs
(`src/test/resources/eval/briefs.yaml` — mystery, first_time, song-achtig,
kort 60s, lang 240s, met/zonder Duckling) de volledige system+user-prompt via
de **echte** `PromptBuilder` + de **echte** `bible/channel.yml`, en assert:

- alle verplichte secties aanwezig (EPISODE STRUCTURE, SERIES MYTHOLOGY incl.
  opening-ritual + running gags, STORY ARC, CAST, LOCATIONS, VARIATION
  DIRECTIVES, STRICT RULES-kern: CTA REALITY CHECK / TIC DOSING /
  MICRO-CONFLICT / PARENT WINK / INSERT SHOTS);
- geen onopgeloste placeholders (`%s`, `%d`, kale `null`, lege fallbacks);
- promptlengte onder budget (~12.000 tokens, chars/4) + sectie-groottes in
  het rapport zodat sluipende groei zichtbaar wordt;
- elke bible-character (id + naam) staat in de CAST-sectie, elke locatie in
  LOCATIONS;
- per brief: de gekozen arc == de gevraagde arc en alle arc-beats uit de
  bible staan letterlijk in de prompt.

**`ScriptEvalHarness`** haalt twee golden scripts
(`src/test/resources/eval/scripts/*.json`, structureel geldig tegen de echte
episodeStructure: 31 scènes, ~178s, locatie-rotatie, cast-continuïteit, één
stille beat, comedy-contract) door de volledige offline QA-keten van de
orchestrator — `StructureValidator` + `PacingValidator` + `ComedyValidator` —
en eist **pass**. Acht bewust kapotte mutanten van diezelfde golden (duur
+30%, alles in één locatie, geen closer, climax weggehaald, climax vooraan,
cast-flicker, 4.3 woorden/sec, stille beat weg) moeten **falen met de
verwachte violation-string**. Wie een validator versoepelt, ziet hier rood; wie hem
aanscherpt, ziet de golden falen en werkt de fixture bewust bij.

Eerlijke kanttekening (gevonden bij de bouw): `StructureValidator` checkte
fase-VOLGORDE aanvankelijk niet, behalve "laatste scène = closer".
**GEFIXT**: er is nu een harde niet-dalende-fase-index-check
("Phase order violated: …"), vastgepind door de "climax vooraan"-mutant.
Nog open: de system-prompt bevat letterlijk `%%` (uit `±15%%`/`20%%` in
niet-geformatteerde stukken van `PromptBuilder`) — het harnas pint dat
bewust níet vast, maar het is een bestaand schoonheidsfoutje richting het
model.

## Brief of golden toevoegen

- **Brief**: blokje toevoegen aan `briefs.yaml` (arc-id moet in bible
  `storyArcs` bestaan, anders faalt het harnas bewust). Bestaande briefs niet
  wijzigen — dat reset de diff-baarheid van het rapport.
- **Golden script**: nieuwe json in `eval/scripts/`, laden in
  `ScriptEvalHarness` en als GOED-case asserten. Reken de faseaantallen/-duren
  na tegen `bible/channel.yml episodeStructure` vóór je commit.

## De afspraak

**Elke promptwijziging = harnas draaien + `target/eval-report.md` diffen**
(vóór en ná). Groen + een verklaarbare diff → dan pas de duurdere trappen:
`run-eval.py` voor kwaliteitsscores, golden-test voor de hele pipeline.

---

# Script eval harness (text-only)

A cheap, objective safety net for changing the script prompts. Runs a **fixed**
set of briefs through the running script-service and collects **every score the
service already computes**, so you can tell whether a prompt change actually
helped — and that it didn't quietly break something else — **before** spending
any render money.

No images, no Veo. Just Haiku script calls → pennies.

## Axes captured (per brief)

| key | label | scale | pass | note |
|---|---|---|---|---|
| `structureScore`  | structure | /100 | ≥80 | deterministic beat-sheet (StructureValidator) |
| `criticScore`     | critic    | /100 | ≥70 | qualitative story-critic (ScriptCritic) — the headline axis |
| `comedy`          | comedy    | /10  | ≥6  | critic comedy axis |
| `emotionalImpact` | emotion   | /10  | ≥6  | critic emotional axis |
| `childPsychology` | child-fit | /10  | ≥5  | **SAFETY axis** — a drop below 5 fails the run |

## Use

```bash
# 1) script-service must be running WITH port 8081 published on the host.
#    The base compose file keeps 8081 internal-only (port-mapping cleanup
#    2026-06-12), so add the dev-ports override:
docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d script-service

# 2) set a baseline ONCE on the current prompts
python infra/eval/run-eval.py --save-baseline

# 3) change a script prompt, rebuild script-service, then re-run:
python infra/eval/run-eval.py
#    -> prints mean + pass-rate for ALL five axes
#    -> prints a per-axis delta vs baseline
#    -> exits non-zero if ANY axis regressed > --regress-eps, or child-fit < 5
```

Useful flags:
- `--runs 3` — average N runs per brief (script-gen is stochastic; 2–3 smooths noise; the report also prints the run-to-run stdev on the critic axis so you can see how much is real signal).
- `--save-baseline` — promote the current run to the new baseline.
- `--regress-eps 3` — points an axis may drop before it counts as a regression (default 3).
- `--base-url http://host:8081` — point at a non-default host (the default `http://localhost:8081` only works when script-service runs with the `docker-compose.dev-ports.yml` override).
- `--timeout 300` — per-script wait before giving up.

## What you get

- **Per-brief line** with all five axes + `ok/runs`.
- **Aggregate**: mean + pass-rate per axis, completed count, critic run-noise, and a SAFETY callout listing any brief whose child-fit fell below 5.
- **A timestamped JSON _and_ Markdown report** in `reports/` (the `.md` is a readable table — drop it straight into a PR).
- **A before/after delta vs `baseline.json`**, per axis, with a ⛔ REGRESSION flag.
- **Exit code** `0` clean / `1` regression-or-unsafe — wire it into CI or a pre-commit hook.

## Files

- `briefs.json` — the fixed test set (8 varied briefs). **Keep it stable** so scores stay comparable; editing the set resets baseline comparability.
- `run-eval.py` — the runner (stdlib only, no pip installs).
- `baseline.json` — created by `--save-baseline`.
- `reports/` — one JSON + one MD per run.

## Workflow it enables

Change a prompt → rebuild → `run-eval.py` → see, objectively and across 8 briefs
in a couple of minutes, whether structure / critic / comedy / emotion / child-fit
went up or down — instead of eyeballing one render and hoping. The per-axis
regression gate is what catches "a fix for comedy quietly tanked emotional
impact" before it ever reaches a paid render.

> Want the same for IMAGES later? That's the expensive variant — it would reuse
> `SceneImageQc` as the grader and cost money per image, so run it sparingly.

---

# Golden test set (full pipeline)

`golden-test.py` is de zware broer van bovenstaande: hij rendert de **twee
vaste golden episodes** (`pilot.json` + `pilot-hatching.json` in de repo-root)
end-to-end via de draaiende orchestrator en vergelijkt álle kwaliteitsmetrieken
met een baseline — QA-board-assen (incl. de deterministische gates), kosten,
masterduur vs target, loudness vs -14 LUFS, edge-bar-scan op de keyframes, en
een contact-sheet van de 8 keyframes voor de menselijke blik.

```bash
docker compose up -d
python infra/eval/golden-test.py --save-baseline   # eenmalig op een goede build
python infra/eval/golden-test.py                   # na elke pipeline-wijziging
golden-test.bat                                    # zelfde, vanaf Windows
```

Spelregels:
- **Bevries de golden briefs.** Een meetlat die meebeweegt meet niets.
- Golden runs gaan **nooit live** (privacyStatus=private, stopt op de
  review-gate). Eerdere human gates? Keur ze goed in het dashboard; de
  bench wacht door.
- Kosten: één ken_burns-run ≈ één normale aflevering (geen Veo). Met
  `--motion veo` test je ook het Veo-pad — duurder, doe dat bij
  Veo-gerelateerde wijzigingen.
- Non-zero exit bij regressie → bruikbaar in CI of als pre-release-check.
- Nieuw model of bewuste verbetering geaccepteerd? Draai `--save-baseline`
  om de nieuwe lat te zetten.
