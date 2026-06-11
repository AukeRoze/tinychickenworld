#!/usr/bin/env python3
"""
Regenerate JUST Mo's hero anchor with a CHUNKY RED KNITTED scarf.

Why: the current bible/refs/mo.png was derived from the banner crop, where Mo
wears a thin RED BANDANA. Every Mo render inherits that bandana look, so Mo's
canonical "red knitted scarf" keeps drifting. This script generates a fresh Mo
anchor that:
  * matches the EXACT art style of the two GOOD anchors (Pip + Bo) by passing
    them as visual style references, and
  * explicitly demands a chunky knitted scarf (never a bandana).

Uses your own Gemini image provider (the same one the image-service uses) —
NOT Higgsfield.

Output: bible/refs/mo-candidate.png  (review it; if good, replace mo.png).

Usage:
  pip install google-genai pillow
  export GEMINI_API_KEY=AIza...          # or GOOGLE_API_KEY (aistudio.google.com)
  python infra/lora-training/make-mo-anchor.py
  # review bible/refs/mo-candidate.png, then:
  #   copy /Y bible\\refs\\mo-candidate.png bible\\refs\\mo.png   (Windows)
"""
from __future__ import annotations
import os
import sys
import time
from pathlib import Path

try:
    from google import genai
    from google.genai import types
except ImportError:
    sys.exit("Missing dep — run: pip install google-genai pillow")

ROOT = Path(__file__).resolve().parents[2]          # repo root
REFS = ROOT / "bible" / "refs"
PIP = REFS / "pip.png"
BO = REFS / "bo.png"
OUT = REFS / "mo-candidate.png"

PROMPT = (
    "Using the two attached reference chicks ONLY as a STYLE and proportion "
    "guide (the cream-white chick with the straw hat is Pip; the tan chick with "
    "round glasses is Bo), create a brand-new SINGLE character: MO. "
    "Mo is a small fluffy baby chick with soft BLUE-GREY down feathers all over "
    "the body, a bright red pointy chicken comb on top of the head, the SAME "
    "EXTRA-LARGE round shiny brown eyes with multiple bright highlights and thick "
    "dark eyelashes as the reference chicks, a small orange beak, two thin orange "
    "legs, and the same oversized-head baby-chick proportions and glossy 3D "
    "rendering as the references. "
    "Mo wears a CHUNKY RED KNITTED WOOL scarf bunched thick around the neck — "
    "clearly KNITTED with visible chunky stitch texture, soft and woolly. "
    "IMPORTANT: it is a knitted scarf, NOT a thin bandana, NOT a kerchief, NOT a "
    "triangle cloth tied at the neck. Mo wears NO hat and NO glasses. "
    "Render Mo as ONE full-body character, standing, facing the camera, LARGE — "
    "filling most of a SQUARE (1:1) frame — on a plain solid WHITE background, "
    "neutral even daylight (no golden-hour tint), the character in sharp focus, "
    "no depth-of-field blur. Exactly ONE chick, no other birds, no reflections, "
    "no background scenery, no text."
)


def main() -> None:
    key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not key:
        sys.exit("Set GEMINI_API_KEY (a valid AIza... key from aistudio.google.com)")
    for p in (PIP, BO):
        if not p.is_file():
            sys.exit(f"Missing style reference: {p}")

    cl = genai.Client(api_key=key)
    contents = [
        types.Part.from_bytes(data=PIP.read_bytes(), mime_type="image/png"),
        types.Part.from_bytes(data=BO.read_bytes(), mime_type="image/png"),
        PROMPT,
    ]

    for attempt in range(1, 6):
        try:
            resp = cl.models.generate_content(
                model="gemini-2.5-flash-image",
                contents=contents,
                config=types.GenerateContentConfig(response_modalities=["IMAGE", "TEXT"]),
            )
            for cand in resp.candidates or []:
                for part in (cand.content.parts if cand.content else []):
                    inline = getattr(part, "inline_data", None)
                    if inline and inline.data:
                        OUT.write_bytes(inline.data)
                        print(f"OK -> {OUT}")
                        print("Review it; if good:  copy bible\\refs\\mo-candidate.png "
                              "over bible\\refs\\mo.png")
                        return
            print(f"  no image, retry {attempt}/5")
        except Exception as e:
            print(f"  error: {e}  retry {attempt}/5")
        time.sleep(2 * attempt)
    sys.exit("FAILED after 5 attempts")


if __name__ == "__main__":
    main()
