#!/usr/bin/env python3
"""
Golden test set — FULL-PIPELINE regression bench.

Where run-eval.py guards the script prompts (text-only, pennies), this guards
the ENTIRE pipeline: it renders the two fixed golden episodes end-to-end via
the running orchestrator, collects every quality metric the system computes
(QA-board axes incl. the deterministic gates, cost, plus local media analysis
of the master), and compares against a stored baseline. Run it after every
pipeline change; a silent regression in any gate becomes a loud non-zero exit.

The golden inputs are the repo's pilot briefs (pilot.json, pilot-hatching.json)
— frozen, never edit them casually: a changing yardstick measures nothing.

Usage:
  docker compose up -d                       # whole stack must run
  python infra/eval/golden-test.py --save-baseline    # once, on a known-good build
  python infra/eval/golden-test.py                    # after every change
  python infra/eval/golden-test.py --motion veo       # golden run incl. Veo (€€)
  python infra/eval/golden-test.py --timeout-min 60

Notes:
  * Jobs run with privacyStatus=private and stop at the review gate — golden
    runs are NEVER published. If your pipeline has earlier human gates enabled
    (script/images review), approve them in the dashboard; the bench waits.
  * Media analysis (duration / loudness / edge bars / contact sheet) needs
    ffmpeg + Pillow on this machine and the ./workdir mount; each part is
    skipped with a warning when unavailable — API metrics still compare.
  * Cost: a ken_burns golden run costs roughly one normal episode render
    (Claude + Gemini + ElevenLabs), no Veo. Budget accordingly.
"""
from __future__ import annotations
import argparse
import json
import math
import re
import subprocess
import sys
import time
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parent.parent
REPORTS = HERE / "reports"
BASELINE = HERE / "golden-baseline.json"
GOLDEN_BRIEFS = [ROOT / "pilot.json", ROOT / "pilot-hatching.json"]

DONE_OK = {"COMPLETED", "THUMBNAIL_REVIEW_PENDING", "UPLOAD_REVIEW_PENDING"}
DONE_BAD = {"FAILED"}
GATE_WAIT = {"SCRIPT_REVIEW_PENDING", "IMAGES_REVIEW_PENDING",
             "ASSETS_REVIEW_PENDING", "VEO_REVIEW_PENDING"}

# metric -> (higher_is_better, regression tolerance before FAIL)
TOLERANCES = {
    "qa.score":          (True,  5.0),
    "durDeltaPct":       (False, 5.0),   # abs % off target — lower is better
    "loudnessLUFS_err":  (False, 1.0),   # abs distance from -14 LUFS
    "edgeBarFrames":     (False, 0.0),   # any new bar frame = fail
    "costEur":           (False, 1.5),   # cost creep guard
}


def http(method: str, url: str, body: dict | None = None, timeout: int = 30):
    req = urllib.request.Request(
        url, method=method,
        data=json.dumps(body).encode() if body is not None else None,
        headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return json.loads(r.read())


def submit(base: str, brief: dict, motion: str) -> str:
    body = dict(brief)
    body["privacyStatus"] = "private"      # golden runs never publish
    body["motionMode"] = motion
    resp = http("POST", base + "/api/v1/videos", body)
    return resp["id"]


def wait(base: str, job_id: str, timeout_min: int) -> dict:
    deadline = time.time() + timeout_min * 60
    warned_gate = False
    while time.time() < deadline:
        j = http("GET", base + f"/api/v1/videos/{job_id}")
        st = j.get("status", "")
        if st in DONE_OK or st in DONE_BAD:
            return j
        if st in GATE_WAIT and not warned_gate:
            print(f"    job {job_id[:8]} wacht op human gate ({st}) — "
                  f"keur goed in het dashboard, de bench wacht door...")
            warned_gate = True
        time.sleep(15)
    return {"status": "TIMEOUT", "id": job_id}


def flatten_numbers(node, prefix: str, out: dict):
    """Recursively pull numeric leaves out of the qaBoard blob."""
    if isinstance(node, dict):
        for k, v in node.items():
            flatten_numbers(v, f"{prefix}.{k}" if prefix else k, out)
    elif isinstance(node, (int, float)) and not isinstance(node, bool):
        out[prefix] = float(node)


# ── local media analysis (best-effort) ─────────────────────────────────

def ffprobe_duration(path: Path) -> float | None:
    try:
        out = subprocess.run(
            ["ffprobe", "-v", "error", "-show_entries", "format=duration",
             "-of", "csv=p=0", str(path)],
            capture_output=True, text=True, timeout=60)
        return float(out.stdout.strip())
    except Exception:
        return None


def loudness_lufs(path: Path) -> float | None:
    try:
        out = subprocess.run(
            ["ffmpeg", "-i", str(path), "-af", "ebur128", "-f", "null", "-"],
            capture_output=True, text=True, timeout=600)
        m = re.findall(r"I:\s+(-?[0-9.]+) LUFS", out.stderr)
        return float(m[-1]) if m else None
    except Exception:
        return None


def edge_bar_frames(audit_dir: Path) -> tuple[int, int] | None:
    """(framesWithBars, maxBarPx) over audit keyframes — mirrors EdgeBarsCheck."""
    try:
        from PIL import Image
    except ImportError:
        return None
    frames = sorted(audit_dir.glob("frame_*.png"))
    if not frames:
        return None
    with_bars, max_px = 0, 0
    for f in frames:
        im = Image.open(f).convert("RGB")
        w, h = im.size
        def col_black(x):
            return all(sum(im.getpixel((x, y))) < 30 for y in range(0, h, 8))
        def row_black(y):
            return all(sum(im.getpixel((x, y))) < 30 for x in range(0, w, 8))
        bars = []
        for probe in (lambda i: col_black(i), lambda i: col_black(w - 1 - i)):
            n = 0
            while n < 200 and probe(n): n += 1
            bars.append(n)
        for probe in (lambda i: row_black(i), lambda i: row_black(h - 1 - i)):
            n = 0
            while n < 200 and probe(n): n += 1
            bars.append(n)
        if max(bars) >= 4:
            with_bars += 1
            max_px = max(max_px, max(bars))
    return with_bars, max_px


def contact_sheet(audit_dir: Path, out: Path) -> bool:
    try:
        from PIL import Image
    except ImportError:
        return False
    frames = sorted(audit_dir.glob("frame_*.png"))[:8]
    if not frames:
        return False
    thumbs = [Image.open(f).convert("RGB").resize((320, 180)) for f in frames]
    cols = 4
    rows = math.ceil(len(thumbs) / cols)
    sheet = Image.new("RGB", (cols * 320, rows * 180), (20, 20, 20))
    for i, t in enumerate(thumbs):
        sheet.paste(t, ((i % cols) * 320, (i // cols) * 180))
    sheet.save(out, quality=88)
    return True


# ── metric collection per finished job ─────────────────────────────────

def collect(base: str, job: dict, brief: dict, workdir: Path, tag: str) -> dict:
    job_id = job.get("id", "")
    m: dict = {"status": job.get("status"), "error": job.get("error")}
    try:
        review = http("GET", base + f"/api/v1/videos/{job_id}/review")
        qa = {}
        flatten_numbers(review.get("qaBoard") or {}, "qa", qa)
        m.update(qa)
        cost = (review.get("cost") or {}).get("estimateEur")
        if cost is not None:
            m["costEur"] = float(cost)
    except Exception as e:
        print(f"    review fetch faalde: {e}")

    jobdir = workdir / job_id
    master = None
    vp = job.get("videoPath") or ""
    if vp:
        # container path /workdir/... -> host workdir mount
        cand = workdir / Path(vp).relative_to("/workdir") if vp.startswith("/workdir") else Path(vp)
        if cand.exists(): master = cand
    if master is None:
        for c in [jobdir / "out" / "final.mp4"]:
            if c.exists(): master = c

    target = float(brief.get("targetSeconds", 180))
    if master:
        dur = ffprobe_duration(master)
        if dur:
            m["masterSec"] = round(dur, 1)
            m["durDeltaPct"] = round(abs(dur - target) / target * 100, 1)
        lufs = loudness_lufs(master)
        if lufs is not None:
            m["loudnessLUFS"] = lufs
            m["loudnessLUFS_err"] = round(abs(lufs - (-14.0)), 2)
    eb = edge_bar_frames(jobdir / "audit")
    if eb is not None:
        m["edgeBarFrames"], m["edgeBarMaxPx"] = eb
    REPORTS.mkdir(exist_ok=True)
    sheet = REPORTS / f"golden-{tag}-contactsheet.jpg"
    if contact_sheet(jobdir / "audit", sheet):
        m["contactSheet"] = str(sheet)
    return m


# ── baseline compare ───────────────────────────────────────────────────

def compare(baseline: dict, current: dict) -> tuple[list[str], list[str]]:
    fails, warns = [], []
    for tag, cur in current.items():
        base = baseline.get(tag, {})
        if cur.get("status") in DONE_BAD or cur.get("status") == "TIMEOUT":
            fails.append(f"[{tag}] run {cur.get('status')}: {cur.get('error')}")
            continue
        for metric, (higher_better, tol) in TOLERANCES.items():
            b, c = base.get(metric), cur.get(metric)
            if b is None or c is None:
                continue
            delta = (c - b) if higher_better else (b - c)   # negative = worse
            worse = (b - c) if higher_better else (c - b)
            line = f"[{tag}] {metric}: {b} -> {c}"
            if worse > tol:
                fails.append(line + f"  (regressie > {tol})")
            elif worse > 0:
                warns.append(line)
    return fails, warns


def publish_dashboard(workdir: Path, ts: str, motion: str, results: dict,
                      fails: list, warns: list, baseline_too: bool = False):
    """Publishes run + sheets into {workdir}/golden/ — the shared volume the
    orchestrator serves to the dashboard (GET /api/v1/golden)."""
    try:
        gdir = workdir / "golden"
        gdir.mkdir(parents=True, exist_ok=True)
        for tag, m in results.items():
            sheet = m.get("contactSheet")
            if sheet and Path(sheet).exists():
                import shutil
                shutil.copy(sheet, gdir / f"{tag}-sheet.jpg")
                if baseline_too:
                    shutil.copy(sheet, gdir / f"{tag}-sheet-baseline.jpg")
                m["sheetName"] = f"{tag}-sheet.jpg"
        payload = {"ts": ts, "motion": motion, "results": results,
                   "fails": fails, "warns": warns}
        (gdir / "latest.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
        if baseline_too:
            (gdir / "baseline.json").write_text(json.dumps(payload, indent=2), encoding="utf-8")
        print(f"Dashboard-publicatie: {gdir} (zie /ui/golden.html)")
    except Exception as e:
        print(f"  dashboard-publicatie overgeslagen: {e}")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--workdir", default=str(ROOT / "workdir"))
    ap.add_argument("--motion", choices=["ken_burns", "veo"], default="ken_burns")
    ap.add_argument("--timeout-min", type=int, default=45, help="per golden job")
    ap.add_argument("--save-baseline", action="store_true")
    args = ap.parse_args()

    briefs = {}
    for p in GOLDEN_BRIEFS:
        briefs[p.stem] = json.loads(p.read_text(encoding="utf-8"))

    print(f"Golden run: {len(briefs)} jobs, motion={args.motion}, "
          f"orchestrator={args.base_url}\n")
    submitted = {}
    for tag, brief in briefs.items():
        jid = submit(args.base_url, brief, args.motion)
        submitted[tag] = jid
        print(f"  {tag} -> job {jid}")

    results = {}
    for tag, jid in submitted.items():
        print(f"\nWacht op {tag} ({jid[:8]}, max {args.timeout_min} min)...")
        job = wait(args.base_url, jid, args.timeout_min)
        print(f"  status: {job.get('status')}")
        results[tag] = collect(args.base_url, job,
                               briefs[tag], Path(args.workdir), tag)

    ts = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M")
    REPORTS.mkdir(exist_ok=True)
    run_file = REPORTS / f"golden-{ts}.json"
    run_file.write_text(json.dumps(results, indent=2), encoding="utf-8")
    print(f"\nRun opgeslagen: {run_file}")

    if args.save_baseline or not BASELINE.exists():
        BASELINE.write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"Baseline {'gezet' if args.save_baseline else 'aangemaakt (eerste run)'}: {BASELINE}")
        publish_dashboard(Path(args.workdir), ts, args.motion, results, [], [],
                          baseline_too=True)
        return

    baseline = json.loads(BASELINE.read_text(encoding="utf-8"))
    fails, warns = compare(baseline, results)
    publish_dashboard(Path(args.workdir), ts, args.motion, results, fails, warns)

    md = [f"# Golden run {ts} vs baseline", ""]
    for tag, cur in results.items():
        md.append(f"## {tag}")
        base = baseline.get(tag, {})
        keys = sorted(set(base) | set(cur) - {"contactSheet", "error"})
        md.append("| metric | baseline | nu |")
        md.append("|---|---|---|")
        for k in keys:
            md.append(f"| {k} | {base.get(k, '—')} | {cur.get(k, '—')} |")
        if cur.get("contactSheet"):
            md.append(f"\nKeyframes: `{cur['contactSheet']}` — leg naast de "
                      f"baseline-sheet voor de menselijke blik.")
        md.append("")
    if warns:
        md.append("## ⚠️ Verslechterd (binnen tolerantie)")
        md += [f"- {w}" for w in warns]
    if fails:
        md.append("## ❌ REGRESSIES")
        md += [f"- {f}" for f in fails]
    (REPORTS / f"golden-{ts}.md").write_text("\n".join(md), encoding="utf-8")
    print(f"Rapport: {REPORTS / f'golden-{ts}.md'}")

    if fails:
        print("\n❌ GOLDEN FAIL:")
        for f in fails: print("  " + f)
        sys.exit(1)
    print(f"\n✅ Golden OK ({len(warns)} kleine verslechtering(en) binnen tolerantie)")


if __name__ == "__main__":
    main()
