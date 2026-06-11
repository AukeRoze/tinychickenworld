#!/usr/bin/env python3
"""
Generate ambient sound loops per location via ElevenLabs Sound Effects API.
Produces longer atmospheric clips (10s each) that voice-service loops under
the main scene audio.

Output:
  bible/sfx/ambient/{name}.mp3

Usage:
  pip install elevenlabs
  export ELEVENLABS_API_KEY=sk_...
  python generate-ambient.py                     # full set
  python generate-ambient.py --location coop     # one ambient only
  python generate-ambient.py --duration 15       # longer loops
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

try:
    from elevenlabs import ElevenLabs
except ImportError:
    sys.exit("Missing dep — run:  pip install elevenlabs")

# ─────────────────────────────────────────────────────────────────────
# Ambient prompt templates. Each describes a soft, looping atmosphere
# without strong rhythmic elements (so loops aren't obvious).
# ─────────────────────────────────────────────────────────────────────

AMBIENTS = {
    "coop": (
        "Soft cozy interior wooden cabin ambient: gentle wood creaks, "
        "very faint breeze through small gaps, soft straw rustles. "
        "Quiet, calm, warm and cozy. No music, no voices. Loopable."
    ),
    "kitchen": (
        "Cozy kitchen ambient: faint kettle hum, very soft crackle of a "
        "small fire, gentle wood creaks. Warm and inviting. "
        "No music, no voices, no human sounds. Loopable."
    ),
    "porch": (
        "Soft outdoor porch ambient: distant cheerful bird chirps, gentle "
        "breeze through grass, occasional leaf rustle. Calm and warm. "
        "No music, no voices. Loopable."
    ),
    "garden": (
        "Sunny garden ambient: soft bee buzzes, gentle leaf rustles in "
        "warm breeze, distant cheerful birds, occasional faint butterfly "
        "wing flap. Light and warm. No music, no voices. Loopable."
    ),
    "pond": (
        "Gentle pond ambient: soft water lapping at shore, distant frog "
        "ribbits, dragonfly wing buzz, faint reed rustles. Calm and "
        "watery. No music, no voices. Loopable."
    ),
    "willowGrove": (
        "Soft willow grove ambient: gentle leaf whispers, very distant "
        "water sound, faint bird calls deep in the trees. Cool and "
        "shaded. No music, no voices. Loopable."
    ),
    "oak": (
        "Soft oak tree ambient: gentle creaking of old wooden branches, "
        "wind moving through dense leaves above, distant cheerful birds. "
        "Calm and rooted. No music, no voices. Loopable."
    ),
    "hills": (
        "Open hillside ambient: gentle breeze across open grass, distant "
        "bird songs far in the sky, soft grass swaying. Wide and free. "
        "No music, no voices. Loopable."
    ),
    "night": (
        "Soft night ambient: gentle cricket chirps, very distant owl "
        "hoot, faint breeze, distant tiny rustles. Calm and starry. "
        "No music, no voices. Loopable."
    ),
}


def make_client() -> ElevenLabs:
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        sys.exit("Set ELEVENLABS_API_KEY env var")
    return ElevenLabs(api_key=key)


def generate_one(client, prompt: str, duration: float, out: Path,
                 retries: int = 3) -> bool:
    for attempt in range(1, retries + 1):
        try:
            audio = client.text_to_sound_effects.convert(
                text=prompt,
                duration_seconds=duration,
                prompt_influence=0.6,   # lower for ambient — more variety
            )
            with open(out, "wb") as f:
                for chunk in audio:
                    f.write(chunk)
            return True
        except Exception as e:
            print(f"  error: {e}  retry ({attempt}/{retries})")
            time.sleep(2 * attempt)
    return False


def run(names: list[str], duration: float, ambient_dir: Path) -> None:
    client = make_client()
    ambient_dir.mkdir(parents=True, exist_ok=True)
    print(f"Generating {len(names)} ambient loop(s) at {duration}s each")
    print(f"Estimated cost: ~{len(names) * duration * 3:.0f} credits "
          f"(≈ €{len(names) * duration * 3 / 100 * 0.18:.2f})\n")

    ok = failed = skipped = 0
    for name in names:
        prompt = AMBIENTS[name]
        out = ambient_dir / f"{name}.mp3"
        if out.exists() and out.stat().st_size > 5000:
            print(f"  [{name}] skip (exists)")
            skipped += 1
            continue
        print(f"  [{name}] generating ...")
        if generate_one(client, prompt, duration, out):
            ok += 1
            print(f"  [{name}] OK -> {out.name}")
        else:
            failed += 1
            print(f"  [{name}] FAILED")
    print(f"\nDone. ok={ok}  skipped={skipped}  failed={failed}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--location", choices=list(AMBIENTS) + ["all"], default="all")
    ap.add_argument("--duration", type=float, default=10.0,
                    help="Clip length in seconds (default 10 — loops smoothly)")
    ap.add_argument("--ambient-dir", type=Path,
                    default=Path(__file__).resolve().parents[2] / "bible" / "sfx" / "ambient")
    args = ap.parse_args()
    names = list(AMBIENTS) if args.location == "all" else [args.location]
    run(names, args.duration, args.ambient_dir)


if __name__ == "__main__":
    main()
