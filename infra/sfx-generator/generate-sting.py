#!/usr/bin/env python3
"""
Generate a branded audio sting for the channel intro via ElevenLabs SFX.
A single ~1.5s cheerful chime/sparkle that plays under the title card.

Usage:
  pip install elevenlabs
  export ELEVENLABS_API_KEY=sk_...
  python generate-sting.py
"""
import os, sys, time
from pathlib import Path

try:
    from elevenlabs import ElevenLabs
except ImportError:
    sys.exit("Missing dep — run:  pip install elevenlabs")

PROMPT = (
    "Bright cheerful magical chime sting, like a glockenspiel sparkle, "
    "soft warm tone, perfect for a kids cartoon channel intro. "
    "Very short, gentle, sweet, like a little sparkle of magic. "
    "Single short clip, no music, no voices. Cute Disney-style."
)
OUT_PATH = Path(__file__).resolve().parents[2] / "bible" / "sting.mp3"


def main():
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        sys.exit("Set ELEVENLABS_API_KEY env var")
    if OUT_PATH.exists() and OUT_PATH.stat().st_size > 1024:
        print(f"Sting already exists at {OUT_PATH} — skipping. Delete to regenerate.")
        return
    OUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    client = ElevenLabs(api_key=key)
    for attempt in range(1, 4):
        try:
            audio = client.text_to_sound_effects.convert(
                text=PROMPT,
                duration_seconds=1.5,
                prompt_influence=0.85,
            )
            with open(OUT_PATH, "wb") as f:
                for chunk in audio:
                    f.write(chunk)
            print(f"OK -> {OUT_PATH}")
            return
        except Exception as e:
            print(f"  error: {e}  retry ({attempt}/3)")
            time.sleep(2 * attempt)
    print("FAILED after 3 attempts")


if __name__ == "__main__":
    main()
