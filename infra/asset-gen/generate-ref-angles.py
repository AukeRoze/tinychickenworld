#!/usr/bin/env python3
"""
Multi-angle character refs — drift-killer for side/back poses.

GeminiImageProvider already prefers a folder bible/refs/{id}/*.png (max 3
angles) over the single hero anchor, and tells the model the images are ONE
character from different angles. This script fills those folders: it takes
the existing front anchor as the identity reference and generates a
three-quarter and a side view via the SAME Gemini model the pipeline uses —
so the new angles are pixel-faithful to the canon, not re-imagined.

Per character: 01-front.png (copy of the canon anchor), 02-three-quarter.png,
03-side.png. Resume-safe: existing files are skipped. EYEBALL the results —
delete a bad angle and rerun to regenerate just that one.

Usage:
  python infra/asset-gen/generate-ref-angles.py             # all chicks
  python infra/asset-gen/generate-ref-angles.py --char pip  # one chick

Key: GEMINI_API_KEY env var, or read automatically from the repo .env.
Cost: 6 images ≈ cents.
"""
from __future__ import annotations
import argparse
import base64
import json
import os
import shutil
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
REFS = ROOT / "bible" / "refs"
MODEL = "gemini-2.5-flash-image"
API = f"https://generativelanguage.googleapis.com/v1beta/models/{MODEL}:generateContent"

CHARS = ["pip", "mo", "bo"]

# Per-character corrections for drift Gemini keeps reintroducing. Appended to
# every angle prompt for that character. Mo: the model loves giving this round
# blue chick feminine lashes — Mo is male, canon has none.
CHAR_EXTRAS = {
    "mo": ("IMPORTANT: Mo is a MALE chick. Absolutely NO eyelashes — plain "
           "simple round eyes with a thin dark upper lid only, exactly like "
           "the reference image. No feminine features."),
}

STYLE = ("glossy 3D Pixar / Illumination CGI render, soft studio lighting, "
         "plain soft white background, full body visible including feet, "
         "newly-hatched baby-chick proportions with an oversized head. "
         "EXACTLY the same character as the reference image: identical feather "
         "colours, identical accessories, identical eye colour and proportions. "
         "Do not redesign anything — only the viewing angle changes. "
         "No text, no watermark.")

ANGLES = {
    "02-three-quarter": "The SAME character as in the reference image, now seen "
        "from a THREE-QUARTER view (turned ~45 degrees to the right), neutral "
        "friendly expression, standing. " + STYLE,
    "03-side": "The SAME character as in the reference image, now seen in full "
        "SIDE PROFILE (turned 90 degrees, facing right), neutral friendly "
        "expression, standing. " + STYLE,
}


def api_key() -> str:
    key = os.environ.get("GEMINI_API_KEY", "")
    if not key:
        env = ROOT / ".env"
        if env.exists():
            for line in env.read_text(encoding="utf-8").splitlines():
                if line.startswith("GEMINI_API_KEY="):
                    key = line.split("=", 1)[1].strip()
                    break
    if not key:
        sys.exit("GEMINI_API_KEY niet gevonden (env var of .env)")
    return key


def generate(key: str, anchor_png: bytes, prompt: str, retries: int = 4) -> bytes | None:
    body = {
        "contents": [{"parts": [
            {"inline_data": {"mime_type": "image/png",
                             "data": base64.b64encode(anchor_png).decode()}},
            {"text": prompt},
        ]}],
        "generationConfig": {"responseModalities": ["IMAGE", "TEXT"],
                             "imageConfig": {"aspectRatio": "1:1"}},
    }
    delay = 3
    for attempt in range(1, retries + 1):
        try:
            req = urllib.request.Request(
                f"{API}?key={key}", data=json.dumps(body).encode(),
                headers={"Content-Type": "application/json"}, method="POST")
            with urllib.request.urlopen(req, timeout=120) as r:
                resp = json.loads(r.read())
            for cand in resp.get("candidates", []):
                for part in cand.get("content", {}).get("parts", []):
                    inline = part.get("inlineData") or part.get("inline_data") or {}
                    if inline.get("data"):
                        return base64.b64decode(inline["data"])
            print(f"  geen image in antwoord (poging {attempt}/{retries})")
        except urllib.error.HTTPError as e:
            msg = e.read()[:200]
            print(f"  HTTP {e.code}: {msg}  (poging {attempt}/{retries})")
            if e.code == 400 and b"aspectRatio" in msg:
                body["generationConfig"].pop("imageConfig", None)
        except Exception as e:
            print(f"  fout: {e}  (poging {attempt}/{retries})")
        time.sleep(delay)
        delay = min(delay * 2, 20)
    return None


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--char", choices=CHARS)
    args = ap.parse_args()
    key = api_key()

    for cid in ([args.char] if args.char else CHARS):
        anchor = REFS / f"{cid}.png"
        if not anchor.exists():
            print(f"!! {anchor} ontbreekt — overgeslagen")
            continue
        outdir = REFS / cid
        outdir.mkdir(exist_ok=True)
        front = outdir / "01-front.png"
        if not front.exists():
            shutil.copy(anchor, front)
            print(f"{cid}: 01-front.png (kopie van canon-anchor)")
        anchor_png = anchor.read_bytes()
        extra = CHAR_EXTRAS.get(cid, "")
        for name, prompt in ANGLES.items():
            prompt = prompt + (" " + extra if extra else "")
            out = outdir / f"{name}.png"
            if out.exists():
                print(f"{cid}: {name} (bestaat, overgeslagen)")
                continue
            print(f"{cid}: {name} genereren...")
            png = generate(key, anchor_png, prompt)
            if png:
                out.write_bytes(png)
                print(f"  ok -> {out.relative_to(ROOT)}")
            else:
                print(f"  MISLUKT: {cid}/{name} — rerun later")
    print("\nKlaar. BEKIJK de hoeken even (kleuren/accessoires identiek?) — "
          "slechte hoek? Verwijder het bestand en rerun. De pipeline pakt de "
          "mappen automatisch op bij de eerstvolgende job; geen rebuild nodig.")


if __name__ == "__main__":
    main()
