#!/usr/bin/env python3
"""
Script-stage eval harness — measure prompt changes OBJECTIVELY before spending
any render money.

Runs a fixed set of briefs (briefs.json) through the running script-service and
collects every score the service already produces, per brief:
  * structureScore  — deterministic beat-sheet (StructureValidator)  0-100
  * criticScore     — qualitative story-critic (ScriptCritic)        0-100
  * comedy          — critic comedy axis                              0-10
  * emotionalImpact — critic emotional axis                           0-10
  * childPsychology — critic child-fit / SAFETY axis                  0-10

It aggregates them, saves a timestamped JSON + a readable Markdown report, and
(if a baseline exists) prints a per-axis before/after delta and FAILS (non-zero
exit) if any axis regressed by more than --regress-eps or the safety axis drops
below 5 — so you can wire it into CI / a pre-commit hook and iterate the giant
prompts safely. Text-only: no images, no Veo — cheap (just Haiku script calls).

Usage:
  # script-service must be running WITH its port published on the host.
  # Since the port-mapping cleanup (2026-06-12) 8081 is internal-only in
  # docker-compose.yml, so start with the dev-ports override:
  #   docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d script-service
  python infra/eval/run-eval.py
  python infra/eval/run-eval.py --runs 3            # average N runs/brief (stochastic)
  python infra/eval/run-eval.py --save-baseline     # set THIS run as the baseline
  python infra/eval/run-eval.py --base-url http://localhost:8081
  python infra/eval/run-eval.py --regress-eps 3     # tolerance before a fail

Typical loop: --save-baseline once on a known-good prompt, then after every
prompt change run plain `run-eval.py` and read the "vs baseline" block.
"""
from __future__ import annotations
import argparse
import json
import statistics
import sys
import time
import urllib.request
import urllib.error
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
BRIEFS = HERE / "briefs.json"
REPORTS = HERE / "reports"
BASELINE = HERE / "baseline.json"

# axis key -> (label, scale, pass-threshold). The safety axis is special-cased.
AXES = {
    "structureScore":  ("structure", 100, 80),
    "criticScore":     ("critic",    100, 70),
    "comedy":          ("comedy",     10,  6),
    "emotionalImpact": ("emotion",    10,  6),
    "childPsychology": ("child-fit",  10,  5),
}
SAFETY_AXIS = "childPsychology"
SAFETY_MIN = 5


def _post(base: str, body: dict) -> dict:
    req = urllib.request.Request(
        base + "/api/v1/scripts",
        data=json.dumps(body).encode(),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def _get(base: str, job_id: str) -> dict:
    req = urllib.request.Request(base + f"/api/v1/scripts/{job_id}", method="GET")
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.loads(r.read())


def run_one(base: str, brief: dict, defaults: dict, timeout_s: int) -> dict:
    body = {
        "topic": brief["topic"],
        "audience": brief.get("audience", defaults.get("audience", "kids_3_6")),
        "targetSeconds": brief.get("targetSeconds", defaults.get("targetSeconds", 60)),
    }
    for k in ("numScenes", "styleHint", "brief", "lesson", "mood", "angle", "hook"):
        if brief.get(k) is not None:
            body[k] = brief[k]

    job = _post(base, body)
    job_id = job["jobId"]
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        resp = _get(base, job_id)
        status = resp.get("status")
        if status == "COMPLETED":
            s = resp.get("script") or {}
            out = {"status": "COMPLETED", "scenes": len(s.get("scenes") or []),
                   "estSeconds": s.get("estSeconds")}
            for key in AXES:
                out[key] = s.get(key)
            return out
        if status == "FAILED":
            return {"status": "FAILED", "error": resp.get("error")}
        time.sleep(3)
    return {"status": "TIMEOUT"}


def mean(xs):
    xs = [x for x in xs if isinstance(x, (int, float))]
    return round(statistics.mean(xs), 1) if xs else None


def stdev(xs):
    xs = [x for x in xs if isinstance(x, (int, float))]
    return round(statistics.pstdev(xs), 1) if len(xs) > 1 else 0.0


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8081")
    ap.add_argument("--runs", type=int, default=1, help="runs per brief (averaged)")
    ap.add_argument("--timeout", type=int, default=300, help="per-script timeout seconds")
    ap.add_argument("--save-baseline", action="store_true")
    ap.add_argument("--regress-eps", type=float, default=3.0,
                    help="max allowed drop (in axis points) before a fail")
    args = ap.parse_args()

    cfg = json.loads(BRIEFS.read_text(encoding="utf-8"))
    defaults = cfg.get("defaults", {})
    briefs = cfg["briefs"]

    print(f"Eval: {len(briefs)} briefs x {args.runs} run(s) -> {args.base_url}\n")
    rows = []
    for b in briefs:
        vals = {k: [] for k in AXES}
        statuses, stab = [], []
        for _ in range(args.runs):
            try:
                r = run_one(args.base_url, b, defaults, args.timeout)
            except urllib.error.URLError as e:
                print(f"  ! {b['id']}: cannot reach script-service ({e}). Is it running?")
                print("    NB: 8081 is no longer published by default. Start with the override:")
                print("    docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d script-service")
                return 2
            statuses.append(r["status"])
            if r["status"] == "COMPLETED":
                for k in AXES:
                    vals[k].append(r.get(k))
                stab.append(r.get("criticScore"))
        row = {"id": b["id"], "ok": statuses.count("COMPLETED"), "runs": args.runs}
        for k in AXES:
            row[k] = mean(vals[k])
        row["criticStdev"] = stdev(stab)  # run-to-run noise on the headline axis
        rows.append(row)
        cells = "  ".join(f"{AXES[k][0]}={str(row[k]):>5}" for k in AXES)
        print(f"  {row['id']:<22} {cells}  ok={row['ok']}/{row['runs']}")

    # Aggregate: mean per axis + pass-rate per axis.
    agg = {}
    for k, (label, scale, thr) in AXES.items():
        agg[f"mean_{k}"] = mean([r[k] for r in rows])
        passed = sum(1 for r in rows if (r[k] or 0) >= thr)
        agg[f"passRate_{k}"] = round(100 * passed / len(rows)) if rows else None
    agg["completed"] = sum(r["ok"] for r in rows)
    agg["total"] = sum(r["runs"] for r in rows)
    agg["meanCriticStdev"] = mean([r["criticStdev"] for r in rows])
    agg["unsafe"] = [r["id"] for r in rows if (r[SAFETY_AXIS] or 99) < SAFETY_MIN]

    report = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "baseUrl": args.base_url, "runs": args.runs,
        "aggregate": agg, "perBrief": rows,
    }
    REPORTS.mkdir(exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    (REPORTS / f"eval-{stamp}.json").write_text(json.dumps(report, indent=2), encoding="utf-8")

    print("\n--- aggregate (mean / pass-rate) ---")
    for k, (label, scale, thr) in AXES.items():
        print(f"  {label:<10} {str(agg['mean_'+k]):>5} /{scale}   (>={thr} pass {agg['passRate_'+k]}%)")
    print(f"  completed  {agg['completed']}/{agg['total']}   critic run-noise ±{agg['meanCriticStdev']}")
    if agg["unsafe"]:
        print(f"  ! SAFETY: child-fit < {SAFETY_MIN} on: {', '.join(agg['unsafe'])}")

    # Baseline compare — per axis.
    failed = bool(agg["unsafe"])
    if BASELINE.exists():
        base = json.loads(BASELINE.read_text(encoding="utf-8"))["aggregate"]
        print("\n--- vs baseline ---")
        for k, (label, scale, thr) in AXES.items():
            now, before = agg["mean_"+k], base.get("mean_"+k)
            d = _delta(now, before)
            flag = ""
            if now is not None and before is not None and now < before - args.regress_eps:
                flag = "  ⛔ REGRESSION"
                failed = True
            print(f"  {label:<10} {str(before):>5} -> {str(now):>5}  ({d}){flag}")
    else:
        print("\n(no baseline yet — run with --save-baseline to set one)")

    _write_markdown(stamp, report, BASELINE)

    if args.save_baseline:
        BASELINE.write_text(json.dumps(report, indent=2), encoding="utf-8")
        print(f"\nBaseline saved -> {BASELINE.name}")

    print(f"\nReport: infra/eval/reports/eval-{stamp}.json (+ .md)")
    return 1 if failed else 0


def _delta(now, before):
    if now is None or before is None:
        return "n/a"
    d = round(now - before, 1)
    return f"{'+' if d >= 0 else ''}{d}"


def _write_markdown(stamp, report, baseline_path):
    agg = report["aggregate"]
    base = None
    if baseline_path.exists():
        try:
            base = json.loads(baseline_path.read_text(encoding="utf-8"))["aggregate"]
        except Exception:
            base = None
    lines = [f"# Script eval — {report['timestamp']}",
             f"_{report['runs']} run(s)/brief · {agg['completed']}/{agg['total']} completed · "
             f"critic run-noise ±{agg['meanCriticStdev']}_", "",
             "## Aggregate", "",
             "| axis | mean | scale | pass-rate | baseline | Δ |",
             "|---|---|---|---|---|---|"]
    for k, (label, scale, thr) in AXES.items():
        b = base.get("mean_"+k) if base else None
        lines.append(f"| {label} | {agg['mean_'+k]} | /{scale} | "
                     f"{agg['passRate_'+k]}% (≥{thr}) | {b if b is not None else '–'} | "
                     f"{_delta(agg['mean_'+k], b)} |")
    if agg.get("unsafe"):
        lines += ["", f"> ⛔ **Safety:** child-fit < {SAFETY_MIN} on: {', '.join(agg['unsafe'])}"]
    lines += ["", "## Per brief", "",
              "| brief | structure | critic | comedy | emotion | child-fit | ok |",
              "|---|---|---|---|---|---|---|"]
    for r in report["perBrief"]:
        lines.append(f"| {r['id']} | {r['structureScore']} | {r['criticScore']} | "
                     f"{r['comedy']} | {r['emotionalImpact']} | {r['childPsychology']} | "
                     f"{r['ok']}/{r['runs']} |")
    (REPORTS / f"eval-{stamp}.md").write_text("\n".join(lines), encoding="utf-8")


if __name__ == "__main__":
    sys.exit(main())
