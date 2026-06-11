#!/usr/bin/env python3
"""Generate the 3 canonical hero anchors from the BANNER crops.

The banner (bible/youtube_banner.jpg) is the source of truth for the cast look.
A square crop per character lives in dataset-refs/{char}-ref.png (centered on
that chick). This passes the crop to Gemini ("Nano Banana") and asks it to turn
the chick in the centre into a clean, full-body character on white — same look,
same big eyes, square framing, character LARGE in frame, accessories kept.

Output: dataset-refs/{pip,mo,bo}-hero.png

Usage:
  pip install google-genai
  export GEMINI_API_KEY=...            # a valid AIza... key
  python make-anchors.py               # all three
  python make-anchors.py --character bo
"""
from __future__ import annotations
import argparse, os, sys, time
from pathlib import Path

try:
    from google import genai
    from google.genai import types
except ImportError:
    sys.exit("Missing dep — run: pip install google-genai")

COMMON = (
    "Recreate the chick in the CENTRE of the attached reference as a SINGLE "
    "full-body character, standing, facing the camera, LARGE — filling most of "
    "a SQUARE (1:1) frame — on a plain solid WHITE background. Keep the EXACT "
    "same colours, the oversized head and the EXTRA-LARGE shiny eyes from the "
    "reference. Warm glossy Pixar / Illumination 3D. Exactly ONE chick, no other "
    "birds, no background scenery, no text."
)

ANCHORS = {
    # Banner-true mapping: Pip = white + straw hat, Mo = blue-grey, Bo = brown + glasses.
    "pip": "The CREAM / WHITE chick wearing the STRAW HAT. KEEP the straw hat and "
           "its neck bandana. " + COMMON,
    "mo":  "The BLUE-GREY chick. KEEP its red comb and its neck scarf. " + COMMON,
    "bo":  "The BROWN / TAN chick. KEEP BOTH its round eyeglasses AND its green "
           "scarf — these are essential. " + COMMON,
}


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--character", choices=list(ANCHORS) + ["all"], default="all")
    ap.add_argument("--refs", type=Path, default=Path(__file__).parent / "dataset-refs")
    args = ap.parse_args()

    key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not key:
        sys.exit("Set GEMINI_API_KEY (a valid AIza... key from aistudio.google.com)")

    cl = genai.Client(api_key=key)
    chars = list(ANCHORS) if args.character == "all" else [args.character]

    for c in chars:
        ref = args.refs / f"{c}-ref.png"
        if not ref.is_file():
            print(f"=== {c}: SKIP — missing reference crop {ref}")
            continue
        dest = args.refs / f"{c}-hero.png"
        print(f"=== {c} -> {dest}")
        blob = ref.read_bytes()
        ok = False
        for attempt in range(1, 6):
            try:
                resp = cl.models.generate_content(
                    model="gemini-2.5-flash-image",
                    contents=[types.Part.from_bytes(data=blob, mime_type="image/png"),
                              ANCHORS[c]],
                    config=types.GenerateContentConfig(response_modalities=["IMAGE", "TEXT"]),
                )
                for cand in resp.candidates or []:
                    for part in (cand.content.parts if cand.content else []):
                        inline = getattr(part, "inline_data", None)
                        if inline and inline.data:
                            dest.write_bytes(inline.data)
                            ok = True
                            break
                    if ok:
                        break
                if ok:
                    break
                print(f"  no image, retry {attempt}/5")
            except Exception as e:
                print(f"  error: {e}  retry {attempt}/5")
            time.sleep(2 * attempt)
        print("  OK" if ok else "  FAILED")


if __name__ == "__main__":
    main()
