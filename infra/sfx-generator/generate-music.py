#!/usr/bin/env python3
"""
Generate the channel music library via the ElevenLabs Music API
(POST /v1/music, model music_v1) — 9 instrumental tracks, 3 per mood
bucket (energetic / thoughtful / calm), each ~40s and loop-friendly,
all sharing one warm pastoral palette so the channel keeps a single
sonic identity.

Resume-safe: existing files are skipped. Uses plain HTTP (requests),
no elevenlabs SDK needed.

Usage:
  pip install requests
  export ELEVENLABS_API_KEY=sk_...        (or set in .env and use the .bat)
  python generate-music.py                # all 9 tracks
  python generate-music.py --track tiny_mystery
  python generate-music.py --mood calm
  python generate-music.py --register     # also add new tracks to channel.yml
  python generate-music.py --outro        # ONLY the outro bed (bible/sfx/outro/calm.mp3)

Cost: Eleven Music is billed per generated minute — 9 × 40s = 6 min.
Check https://elevenlabs.io/pricing/api for your plan's music rate
before a full run.
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

try:
    import requests
except ImportError:
    sys.exit("Missing dep — run:  pip install requests")

API = "https://api.elevenlabs.io/v1/music"

# One shared palette keeps every track recognisably "Tiny Chicken World".
PALETTE = (
    "Gentle instrumental background music for a warm preschool cartoon "
    "(children aged 3-6) set in a soft pastoral countryside. "
    "Acoustic palette: ukulele, glockenspiel, soft felt piano, pizzicato "
    "strings, light recorder, gentle brushed percussion. "
    "Absolutely no vocals, no singing, no harsh sounds, no electronic drops. "
    "Mixed quiet and smooth so narration can sit on top. "
    "Starts and ends softly so it loops seamlessly. "
)

# id -> (mood bucket, flavour prompt)
TRACKS = {
    # ── energetic (adventure, playful, silly) ────────────────────────
    "rolling_hills_romp": ("energetic",
        "A playful, bouncy romp: skipping ukulele strums, hand-clap accents, "
        "cheerful glockenspiel melody that tumbles downhill and giggles back up. "
        "Bright, sunny, full of forward motion — chicks racing through grass."),
    "bumblebee_boogie": ("energetic",
        "A light-footed pizzicato chase: quick plucked strings like tiny "
        "running feet, a curious recorder answering, playful stop-and-go "
        "moments that wink at the listener. Mischievous but always friendly."),
    "puddle_jump_parade": ("energetic",
        "A tiny marching parade: soft marimba and woodblock taps like little "
        "boots in puddles, proud toy-like melody, a happy swing in the step. "
        "Joyful, triumphant, never loud."),

    # ── thoughtful (curious, wonder, discovery, mystery) ─────────────
    "tiny_mystery": ("thoughtful",
        "A curious tip-toe theme: sparse pizzicato steps, a questioning "
        "celesta phrase that rises like an eyebrow, small pauses full of "
        "wonder. The sound of 'what IS that?' — intrigued, never spooky."),
    "cloud_watching": ("thoughtful",
        "A floating daydream: slow warm piano chords, a soft flute drifting "
        "like a cloud, gentle shimmer of glockenspiel. Open, airy, full of "
        "quiet amazement — lying in the grass looking up."),
    "what_is_that_glow": ("thoughtful",
        "A gentle discovery build: soft repeating piano pattern that slowly "
        "blooms, strings warming underneath, a tiny chime when the wonder "
        "lands. The feeling of dawn light growing — hushed awe."),

    # ── calm (cozy, bedtime, warm — the default) ─────────────────────
    "starlight_nest": ("calm",
        "A music-box lullaby: delicate celesta melody rocking slowly, warm "
        "low strings like a blanket, the softest brush of wind chimes. "
        "Sleepy, safe, starry — chicks dozing in the nest."),
    "warm_straw_sunset": ("calm",
        "A cozy acoustic sundown: slow fingerpicked guitar, soft humming "
        "strings, a tender glockenspiel goodnight phrase. Golden, settled, "
        "content — the day folding itself up."),
    "drowsy_dandelions": ("calm",
        "A drowsy afternoon waltz: soft felt piano swaying in slow 3/4, "
        "faint warm pads, tiny harp touches like drifting seeds. Peaceful "
        "and warm, barely moving."),
}

MUSIC_DIR = Path(__file__).resolve().parents[2] / "bible" / "music"
CHANNEL_YML = Path(__file__).resolve().parents[2] / "bible" / "channel.yml"

# ── Outro-bed (end-screen-outro, 2026-06-12) ─────────────────────────────
# Apart van de musicLibrary: dit bestand wordt door OutroBuilder gelezen
# (app.brand.outro-music, default /bible/sfx/outro/calm.mp3) en geloopt/
# getrimd tot de outro-duur. NIET registreren in music.tracks — het is een
# brand-asset, geen episode-track. Genereren: python generate-music.py --outro
OUTRO_BED = Path(__file__).resolve().parents[2] / "bible" / "sfx" / "outro" / "calm.mp3"
OUTRO_PROMPT = (
    "The gentlest possible end-card lullaby loop: a single warm music-box "
    "melody over the softest felt-piano chords, barely-there warm pads, "
    "very slow, settled and final — the sound of a picture book closing "
    "and a night-light switching on. Even quieter and simpler than a "
    "normal lullaby; it sits UNDER a short spoken farewell and a YouTube "
    "end screen. Starts and ends in near-silence so it loops seamlessly."
)


def generate_one(key: str, prompt: str, length_ms: int, out: Path,
                 retries: int = 3) -> bool:
    body = {
        "prompt": prompt,
        "music_length_ms": length_ms,
        "model_id": "music_v1",
        "force_instrumental": True,
    }
    for attempt in range(1, retries + 1):
        try:
            r = requests.post(
                API, json=body,
                headers={"xi-api-key": key, "Content-Type": "application/json"},
                timeout=300)
            if r.status_code == 200 and r.content:
                out.write_bytes(r.content)
                return True
            print(f"  HTTP {r.status_code}: {r.text[:200]}  retry ({attempt}/{retries})")
        except Exception as e:
            print(f"  error: {e}  retry ({attempt}/{retries})")
        time.sleep(3 * attempt)
    return False


def register(new_ids: list[str]) -> None:
    """Adds generated tracks to channel.yml under music.tracks (top-level
    music: block). Text-based, comment-preserving; makes a .bak first and
    skips ids that are already registered."""
    text = CHANNEL_YML.read_text(encoding="utf-8")
    lines = text.splitlines(keepends=True)
    # Locate the LAST top-level "music:" block's "  tracks:" line. channel.yml
    # historically contains TWO top-level music: blocks; YAML lets the last
    # one win, so that's the only block the services actually read.
    tracks_idx = None
    for i, line in enumerate(lines):
        if line.rstrip("\n") == "music:":
            for j in range(i + 1, min(i + 5, len(lines))):
                if lines[j].strip() == "tracks:":
                    tracks_idx = j   # keep scanning — last block wins
                    break
    if tracks_idx is None:
        print("!! could not find top-level music.tracks in channel.yml — register manually:")
        print(snippet(new_ids))
        return
    to_add = [tid for tid in new_ids if f"- id: {tid}" not in text]
    if not to_add:
        print("channel.yml: all tracks already registered")
        return
    insert = "".join(
        f"    - id: {tid}\n"
        f"      path: /bible/music/{tid}.mp3\n"
        f"      mood: {TRACKS[tid][0]}\n"
        for tid in to_add)
    CHANNEL_YML.with_suffix(".yml.bak-music").write_text(text, encoding="utf-8")
    lines.insert(tracks_idx + 1, insert)
    CHANNEL_YML.write_text("".join(lines), encoding="utf-8")
    print(f"channel.yml: registered {len(to_add)} track(s) "
          f"(backup: {CHANNEL_YML.name}.bak-music)")


def snippet(ids: list[str]) -> str:
    return "".join(
        f"    - id: {tid}\n      path: /bible/music/{tid}.mp3\n"
        f"      mood: {TRACKS[tid][0]}\n" for tid in ids)


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--track", help="generate one track id only")
    ap.add_argument("--mood", choices=["energetic", "thoughtful", "calm"])
    ap.add_argument("--duration", type=float, default=40.0,
                    help="track length in seconds (default 40, like the originals)")
    ap.add_argument("--register", action="store_true",
                    help="add generated tracks to channel.yml music.tracks")
    ap.add_argument("--outro", action="store_true",
                    help="generate ONLY the outro music bed (bible/sfx/outro/calm.mp3, ~20s)")
    args = ap.parse_args()

    key = os.environ.get("ELEVENLABS_API_KEY")
    if not key:
        # Fall back to the repo .env (same convenience as generate-ref-angles.py).
        env = MUSIC_DIR.parents[1] / ".env"
        if env.exists():
            for line in env.read_text(encoding="utf-8").splitlines():
                if line.startswith("ELEVENLABS_API_KEY="):
                    key = line.split("=", 1)[1].strip()
                    break
    if not key:
        sys.exit("Set ELEVENLABS_API_KEY env var (of zet hem in .env)")

    if args.outro:
        if OUTRO_BED.exists():
            sys.exit(f"= outro bed exists, skipped: {OUTRO_BED} (verwijder 'm om opnieuw te genereren)")
        OUTRO_BED.parent.mkdir(parents=True, exist_ok=True)
        print(f"> outro bed [calm] -> {OUTRO_BED}")
        if generate_one(key, PALETTE + OUTRO_PROMPT, 20_000, OUTRO_BED):
            print("  ok — de eerstvolgende outro-(re)build mixt 'm automatisch mee (-16dB)")
        else:
            sys.exit("  FAILED: outro bed")
        return

    names = [args.track] if args.track else [
        t for t, (mood, _) in TRACKS.items() if not args.mood or mood == args.mood]
    unknown = [n for n in names if n not in TRACKS]
    if unknown:
        sys.exit(f"Unknown track(s): {unknown} — choose from {list(TRACKS)}")

    MUSIC_DIR.mkdir(parents=True, exist_ok=True)
    length_ms = int(args.duration * 1000)
    print(f"Generating {len(names)} track(s) at {args.duration:.0f}s each "
          f"({len(names) * args.duration / 60:.1f} min of music)\n")

    ok, skipped, failed, done_ids = 0, 0, 0, []
    for tid in names:
        out = MUSIC_DIR / f"{tid}.mp3"
        if out.exists():
            print(f"= {tid} (exists, skipped)")
            skipped += 1
            done_ids.append(tid)
            continue
        mood, flavour = TRACKS[tid]
        print(f"> {tid} [{mood}] ...")
        if generate_one(key, PALETTE + flavour, length_ms, out):
            print(f"  ok -> {out.name}")
            ok += 1
            done_ids.append(tid)
        else:
            print(f"  FAILED: {tid}")
            failed += 1

    print(f"\nDone: {ok} generated, {skipped} skipped, {failed} failed")
    if done_ids:
        if args.register:
            register(done_ids)
        else:
            print("\nRegister them in bible/channel.yml under music.tracks "
                  "(or rerun with --register):\n" + snippet(done_ids))


if __name__ == "__main__":
    main()
