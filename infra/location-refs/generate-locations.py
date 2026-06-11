#!/usr/bin/env python3
"""
Generate per-location hero reference images via Gemini's image API
(Nano Banana — gemini-2.5-flash-image). Mirrors generate-refs.py but for
ENVIRONMENTS instead of characters.

For each location in bible/channel.yml, generates 3-4 hero shots in
different lighting moods (dawn, morning, goldenHour, dusk, night).
These become visual anchors that the inference pipeline can use as
context references (similar to character hero refs).

Output:
  bible/refs/locations/{location_id}/{mood}.png

Usage:
  pip install google-genai PyYAML
  export GEMINI_API_KEY=...
  python generate-locations.py                            # all locations
  python generate-locations.py --location bigOak          # just one
  python generate-locations.py --mood goldenHour          # one mood only
  python generate-locations.py --variants 3               # extra variants
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

try:
    from google import genai
    from google.genai import types
    import yaml
except ImportError:
    sys.exit("Missing dep — run:  pip install google-genai PyYAML")

DEFAULT_BIBLE = Path(__file__).resolve().parents[2] / "bible" / "channel.yml"

STYLE_TAIL = (
    "Stylized Pixar-quality 3D cartoon environment in the style of "
    "Illumination Entertainment. Warm saturated colors, rich detailed "
    "props and storytelling elements, painterly background depth, soft "
    "cinematic depth of field. NO CHARACTERS in the frame, just the "
    "empty environment as a hero establishing shot. Wide-aspect "
    "establishing shot, suitable as a visual reference for downstream "
    "scene generation. NO text, NO logos, NO watermarks."
)


def load_bible(path: Path) -> dict:
    with open(path, "r", encoding="utf-8") as f:
        return yaml.safe_load(f)


def build_prompt(location: dict, mood: dict) -> str:
    return (
        f"Establishing hero shot of {location.get('name', location['id'])}: "
        f"{location.get('description', '').strip()} "
        f"LIGHTING: {mood.get('description', mood['label']).strip()} "
        f"{STYLE_TAIL}"
    )


def make_client() -> "genai.Client":
    key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not key:
        sys.exit("Set GEMINI_API_KEY env var")
    return genai.Client(api_key=key)


def generate_one(client, prompt: str, out: Path, retries: int = 3) -> bool:
    for attempt in range(1, retries + 1):
        try:
            resp = client.models.generate_content(
                model="gemini-2.5-flash-image",
                contents=prompt,
                config=types.GenerateContentConfig(
                    response_modalities=["IMAGE", "TEXT"],
                ),
            )
            for cand in resp.candidates or []:
                for part in (cand.content.parts if cand.content else []):
                    inline = getattr(part, "inline_data", None)
                    if inline and inline.data:
                        out.write_bytes(inline.data)
                        return True
            print(f"  no image in response, retry ({attempt}/{retries})")
        except Exception as e:
            print(f"  error: {e}  retry ({attempt}/{retries})")
        time.sleep(2 * attempt)
    return False


def run(bible: dict, only_loc: str | None, only_mood: str | None,
        variants: int, refs_root: Path) -> None:
    client = make_client()
    locations = bible.get("locations", [])
    moods     = bible.get("timeOfDay", [])
    if only_loc:
        locations = [l for l in locations if l.get("id") == only_loc]
    if only_mood:
        moods = [m for m in moods if m.get("id") == only_mood]

    print(f"Generating {len(locations)} locations × {len(moods)} moods × "
          f"{variants} variant(s) = {len(locations) * len(moods) * variants} images")

    ok = failed = skipped = 0
    for loc in locations:
        loc_id = loc.get("id")
        dir = refs_root / loc_id
        dir.mkdir(parents=True, exist_ok=True)
        print(f"\n=== {loc_id} ({loc.get('name', '')}) → {dir} ===")
        for mood in moods:
            mood_id = mood.get("id")
            for v in range(1, variants + 1):
                stem = mood_id if variants == 1 else f"{mood_id}-{v}"
                out = dir / f"{stem}.png"
                if out.exists() and out.stat().st_size > 1024:
                    print(f"  [{stem}] skip (exists)")
                    skipped += 1
                    continue
                prompt = build_prompt(loc, mood)
                print(f"  [{stem}] generating ({len(prompt)} chars)")
                if generate_one(client, prompt, out):
                    ok += 1
                    print(f"  [{stem}] OK -> {out.name}")
                else:
                    failed += 1
                    print(f"  [{stem}] FAILED")

    print(f"\nDone. ok={ok}  skipped={skipped}  failed={failed}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--bible",    type=Path, default=DEFAULT_BIBLE)
    ap.add_argument("--location", default=None)
    ap.add_argument("--mood",     default=None)
    ap.add_argument("--variants", type=int, default=1)
    ap.add_argument("--refs-root", type=Path,
                    default=Path(__file__).resolve().parents[2] / "bible" / "refs" / "locations")
    args = ap.parse_args()

    bible = load_bible(args.bible)
    run(bible, args.location, args.mood, args.variants, args.refs_root)


if __name__ == "__main__":
    main()
