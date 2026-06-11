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
# 1) script-service must be running
docker compose up -d script-service        # or the whole stack

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
- `--base-url http://host:8081` — point at a non-default host.
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
