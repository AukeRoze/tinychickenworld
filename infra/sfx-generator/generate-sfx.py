#!/usr/bin/env python3
"""
Generate the chicken-sound library via ElevenLabs Sound Effects API.

Produces ~60 short clips (3 characters × 10 emotions × 2 variants) under
bible/sfx/{pip,mo,bo}/{emotion}-{variant}.mp3 — each tuned to that
character's sonic identity.

Resume-safe: skips files that already exist.

Cost (June 2025 pricing): ~3 credits per second of audio. Each clip is
~2 seconds → ~6 credits. 60 clips × 6 = 360 credits ≈ €2-3 total.

Usage:
  pip install elevenlabs
  export ELEVENLABS_API_KEY=sk_...
  python generate-sfx.py                              # full library
  python generate-sfx.py --character pip              # one char only
  python generate-sfx.py --emotion curious            # one emotion only
  python generate-sfx.py --duration 2.5               # clip length
  python generate-sfx.py --variants 3                 # variants per emotion
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
# Character voices — these prompt-prefixes give each character their
# distinctive sonic identity. The ElevenLabs SFX model picks up on tone
# descriptors very well, so we're specific.
# ─────────────────────────────────────────────────────────────────────

CHARACTERS = {
    "pip": {
        "name": "Pip",
        "voice_desc": "tiny baby chick, very soft high peeps, sweet and innocent",
    },
    "mo": {
        "name": "Mo",
        "voice_desc": "tiny baby chick, soft slightly-lower gentle peeps, calm and cozy",
    },
    "bo": {
        "name": "Bo",
        "voice_desc": "tiny baby chick, soft playful peeps with mild pitch variation",
    },
}

# ─────────────────────────────────────────────────────────────────────
# Emotion taxonomy. The prompt template combines:
#   {voice_desc} of the character + {emotion_action} of the moment.
# ─────────────────────────────────────────────────────────────────────

EMOTIONS = {
    "curious":    "softly wondering, gentle questioning",
    "excited":    "sweetly happy, soft joyful peeps",
    "surprised":  "soft tender little gasp, brief",
    "gasping":    "soft wide-eyed wonder, gentle breath of awe",
    "laughing":   "tiny tender giggle, soft warm baby laughter",
    "thinking":   "soft thoughtful humming peep",
    "sleepy":     "soft drowsy peep, gentle yawn",
    "content":    "soft tender happy peep, settled and cozy",
    "confused":   "soft questioning little peep, head-tilt",
    "agreeing":   "soft gentle yes-yes peeps, warm",
}


def build_prompt(char_id: str, emotion: str, variant: int) -> str:
    ch = CHARACTERS[char_id]
    action = EMOTIONS[emotion]
    nuance = {1: "very soft", 2: "slightly cheerful", 3: "short and gentle"}.get(variant, "soft")
    # Total ~280 chars — well under ElevenLabs' 450 char limit.
    return (
        f"Sound effect: {ch['voice_desc']}, {action}. "
        f"Very short, {nuance}. Cute Disney-style baby chick, animated kids cartoon for ages 3-6. "
        "NO harsh, NO sharp, NO loud, NO realistic farm sounds. "
        "Soft plush toy come to life. No music, no background."
    )


# ─────────────────────────────────────────────────────────────────────
# ElevenLabs Sound Effects call
# ─────────────────────────────────────────────────────────────────────

def make_client() -> ElevenLabs:
    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        sys.exit("Set ELEVENLABS_API_KEY env var")
    return ElevenLabs(api_key=key)


def generate_one(client: ElevenLabs, prompt: str, duration: float,
                 prompt_influence: float, out_path: Path, retries: int = 3) -> bool:
    for attempt in range(1, retries + 1):
        try:
            # ElevenLabs sound_generation.convert returns an audio iterator.
            # API spec: text describes the SFX, duration_seconds is target length.
            audio_stream = client.text_to_sound_effects.convert(
                text=prompt,
                duration_seconds=duration,
                prompt_influence=prompt_influence,
            )
            with open(out_path, "wb") as f:
                for chunk in audio_stream:
                    f.write(chunk)
            return True
        except Exception as e:
            print(f"  error: {e}  retry ({attempt}/{retries})")
            time.sleep(2 * attempt)
    return False


# ─────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────

def run(characters: list[str], emotions: list[str], variants: int,
        duration: float, prompt_influence: float, sfx_root: Path) -> None:
    client = make_client()
    ok = failed = skipped = 0
    total = len(characters) * len(emotions) * variants
    print(f"Will generate up to {total} clips ({duration}s each)")
    print(f"Estimated cost: {total * duration * 3:.0f} credits "
          f"(≈ €{total * duration * 3 / 100 * 0.18:.2f} on a Creator plan)\n")

    for ch_id in characters:
        out_dir = sfx_root / ch_id
        out_dir.mkdir(parents=True, exist_ok=True)
        print(f"=== {ch_id.upper()} → {out_dir} ===")
        for emotion in emotions:
            for v in range(1, variants + 1):
                out = out_dir / f"{emotion}-{v}.mp3"
                if out.exists() and out.stat().st_size > 1024:
                    print(f"  [{emotion}-{v}] skip (exists)")
                    skipped += 1
                    continue
                prompt = build_prompt(ch_id, emotion, v)
                print(f"  [{emotion}-{v}] generating ...")
                if generate_one(client, prompt, duration, prompt_influence, out):
                    ok += 1
                    print(f"  [{emotion}-{v}] OK")
                else:
                    failed += 1
                    print(f"  [{emotion}-{v}] FAILED")
        print()

    print(f"Done. ok={ok}  skipped={skipped}  failed={failed}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--character", choices=list(CHARACTERS) + ["all"], default="all")
    ap.add_argument("--emotion",   choices=list(EMOTIONS) + ["all"],   default="all")
    ap.add_argument("--variants",  type=int, default=2)
    ap.add_argument("--duration",  type=float, default=1.5,
                    help="Target clip length in seconds (shorter = cuter)")
    ap.add_argument("--prompt-influence", type=float, default=0.85,
                    help="0-1. Higher = strictly follow the prompt (default 0.85)")
    ap.add_argument("--sfx-root",  type=Path,
                    default=Path(__file__).resolve().parents[2] / "bible" / "sfx")
    args = ap.parse_args()

    chars = list(CHARACTERS) if args.character == "all" else [args.character]
    emos  = list(EMOTIONS)   if args.emotion   == "all" else [args.emotion]

    run(chars, emos, args.variants, args.duration, args.prompt_influence, args.sfx_root)


if __name__ == "__main__":
    main()
