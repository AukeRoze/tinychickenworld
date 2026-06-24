#!/usr/bin/env python3
"""
Regenerate the fallback music library in bible/music/ through YOUR OWN
self-hosted Suno wrapper — same subscription the pipeline already uses, so it
costs nothing per track (no ElevenLabs credits, unlike the older
generate-music.py).

Why this exists
---------------
The per-video soundtrack now comes from Suno v4.5+ (SUNO_MODEL=chirp-auk).
bible/music/ is only the *fallback* that plays when Suno is unreachable, and
those files were the old 128-160 kbps ElevenLabs tracks. This script overwrites
them in place with fresh chirp-auk instrumentals, using the EXACT mood->style
prompts from SongController.instrumentalStyleFor so the fallback matches the
channel's sonic identity. File names stay identical, so channel.yml needs no
changes.

It faithfully mirrors SunoMusicClient: POST /suno/submit/music with
{gpt_description_prompt, make_instrumental:true, mv:<model>}, poll
/suno/fetch/{id} until a clip is "complete", then download audio_url.

Reachability
------------
The suno-api container is `expose`-only (internal docker network). Run this from
the host with the port published:
  docker compose --profile suno -f docker-compose.yml -f docker-compose.dev-ports.yml up -d suno-api
  python infra/sfx-generator/generate-music-suno.py            # defaults to http://localhost:8000

Usage
-----
  pip install requests
  python infra/sfx-generator/generate-music-suno.py
  python infra/sfx-generator/generate-music-suno.py --only tiny_mystery
  python infra/sfx-generator/generate-music-suno.py --bucket calm
  python infra/sfx-generator/generate-music-suno.py --base-url http://localhost:8000 --dry-run
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
    sys.exit("Please `pip install requests` first.")

# ---------------------------------------------------------------------------
# Library layout — names + buckets mirror bible/music/README.md "Currently
# registered". File names are kept identical so channel.yml is untouched.
# ---------------------------------------------------------------------------
TRACKS = {
    "energetic": [
        "sunny_adventure", "rolling_hills_romp", "bumblebee_boogie", "puddle_jump_parade",
    ],
    "thoughtful": [
        "curious_clouds", "tiny_mystery", "cloud_watching", "what_is_that_glow",
    ],
    "calm": [
        "gentle_morning", "starlight_nest", "warm_straw_sunset", "drowsy_dandelions",
    ],
}


def style_for(bucket: str):
    """Port of SongController.instrumentalStyleFor — keeps the fallback library
    in the same palette as the per-video Suno tracks. Returns (style, prompt)."""
    mood = bucket
    if bucket == "energetic":
        style = ("upbeat children's cartoon adventure instrumental, bright and bouncy, "
                 "120-132 BPM, plucky pizzicato strings, perky staccato woodwinds (clarinet, "
                 "piccolo), tuned hand claps, bouncing marimba and xylophone, light snappy "
                 "shaker-and-tambourine groove, cheeky tuba bass, sunny major key, "
                 "playful and energetic, wholesome, no harsh or scary sounds, "
                 "instrumental, no vocals; mood: " + mood)
        prompt = ("Bouncy, adventurous background music for a preschool cartoon about three "
                  "little chickens - Pip, Mo and Bo - scampering and exploring their cozy tiny "
                  "world. Keep it fun, light and energetic but gentle enough for ages 3-6. "
                  "Mood: " + mood + ".")
    elif bucket == "thoughtful":
        style = ("gentle curious children's cartoon instrumental, soft and inquisitive, "
                 "84-96 BPM, sparkling music-box and celesta, twinkly glockenspiel, "
                 "tip-toeing pizzicato strings, warm vibraphone, mallet-finger marimba, "
                 "airy flute, light brushed percussion, sense of wonder and tiny discovery, "
                 "bright but soft major key, wholesome and tender, instrumental, no vocals; "
                 "mood: " + mood)
        prompt = ("Curious, wonder-filled background music for a preschool cartoon where three "
                  "little chickens - Pip, Mo and Bo - discover something new in their tiny world. "
                  "Soft, twinkly and inquisitive, full of gentle wonder for ages 3-6. "
                  "Mood: " + mood + ".")
    elif bucket == "calm":
        style = ("calm cozy children's lullaby instrumental, slow and soothing, 64-76 BPM, "
                 "soft fingerpicked nylon guitar, warm felt piano, gentle glockenspiel, "
                 "mellow vibraphone, airy sustained pad, faint music box, no percussion or "
                 "only the softest brushed shaker, tender and reassuring, warm major key, "
                 "wholesome bedtime feel, instrumental, no vocals; mood: " + mood)
        prompt = ("Soft, soothing bedtime background music for a preschool cartoon as three "
                  "little chickens - Pip, Mo and Bo - settle into their cozy barn nest in the "
                  "tiny world. Calm, warm and reassuring for ages 3-6. Mood: " + mood + ".")
    else:
        style = ("warm children's cartoon background instrumental, gentle and whimsical, "
                 "90-104 BPM, ukulele, glockenspiel, marimba, soft mallets, warm acoustic guitar, "
                 "light playful percussion, cozy and wholesome, sunny major key, "
                 "no harsh or scary sounds, instrumental, no vocals; mood: " + mood)
        prompt = ("Soft, cheerful background music for a preschool cartoon about three little "
                  "chickens - Pip, Mo and Bo - exploring their cozy tiny world. Warm, playful and "
                  "gentle for ages 3-6. Mood: " + mood + ".")
    return style, prompt


def load_env(repo_root: Path) -> dict:
    """Minimal .env reader so SUNO_* don't have to be exported."""
    env = {}
    f = repo_root / ".env"
    if f.exists():
        for line in f.read_text(encoding="utf-8", errors="ignore").splitlines():
            line = line.strip()
            if not line or line.startswith("#") or "=" not in line:
                continue
            k, _, v = line.partition("=")
            env[k.strip()] = v.strip()
    for k in ("SUNO_BASE_URL", "SUNO_SECRET_TOKEN", "SUNO_MODEL"):
        if os.environ.get(k):
            env[k] = os.environ[k]
    return env


def generate_one(session, base_url, model, bucket, name, out_dir, dry_run) -> bool:
    style, prompt = style_for(bucket)
    desc = (prompt + " | style: " + style).strip()
    body = {"gpt_description_prompt": desc, "make_instrumental": True, "mv": model}
    out_path = out_dir / (name + ".mp3")

    print("\n[%-10s] %s  (mv=%s)" % (bucket, name, model))
    if dry_run:
        print("  DRY-RUN -> would POST /suno/submit/music and save %s" % out_path)
        return True

    r = session.post(base_url + "/suno/submit/music", json=body, timeout=120)
    r.raise_for_status()
    created = r.json()
    task_id = None
    if isinstance(created, str):
        task_id = created
    elif isinstance(created, dict):
        data = created.get("data")
        if isinstance(data, str):
            task_id = data
        elif isinstance(data, dict):
            task_id = data.get("task_id") or data.get("taskId") or data.get("id")
        task_id = task_id or created.get("id")
    if not task_id:
        print("  ! submit returned no task id: %s" % created)
        return False
    print("  task %s submitted - polling (<=5 min)..." % task_id)

    for _ in range(60):
        time.sleep(5)
        fr = session.get(base_url + "/suno/fetch/" + str(task_id), timeout=60)
        fr.raise_for_status()
        data = fr.json()
        clips = data.get("data") if isinstance(data, dict) else data
        if isinstance(clips, dict):
            clips = clips.get("response") or clips.get("clips") or []
        if not isinstance(clips, list):
            continue
        for clip in clips:
            status = (clip.get("status") or "").lower()
            audio = clip.get("audio_url") or clip.get("audioUrl")
            if status == "complete" and audio:
                mp3 = session.get(audio, timeout=180).content
                if not mp3:
                    print("  ! empty audio download")
                    return False
                out_path.write_bytes(mp3)
                print("  OK saved %s  (%d bytes)" % (out_path, len(mp3)))
                return True
            if status == "error":
                print("  ! Suno reported an error for this task")
                return False
    print("  ! timed out waiting for completion")
    return False


def main() -> int:
    repo_root = Path(__file__).resolve().parents[2]
    env = load_env(repo_root)

    # .env's SUNO_BASE_URL is the docker-INTERNAL name (http://suno-api:8000),
    # which the host can't resolve. This is a host-side tool, so default to
    # localhost; only honour a .env value that is genuinely host-reachable.
    env_base = env.get("SUNO_BASE_URL", "")
    default_base = "http://localhost:8000"
    if env_base and "suno-api" not in env_base:
        default_base = env_base

    ap = argparse.ArgumentParser(description="Regenerate bible/music via your Suno wrapper.")
    ap.add_argument("--base-url", default=default_base,
                    help="Suno wrapper URL. Default http://localhost:8000 - needs the "
                         "suno-api port published (docker-compose.dev-ports.yml). The "
                         "internal name suno-api:8000 only works inside the docker network.")
    ap.add_argument("--token", default=env.get("SUNO_SECRET_TOKEN", ""),
                    help="Bearer token (SUNO_SECRET_TOKEN).")
    ap.add_argument("--model", default=env.get("SUNO_MODEL", "chirp-auk"),
                    help="Suno model / mv value (default from .env -> chirp-auk = v4.5+).")
    ap.add_argument("--out", default=str(repo_root / "bible" / "music"),
                    help="Output dir for the .mp3 files.")
    ap.add_argument("--bucket", choices=list(TRACKS), help="Only regenerate one bucket.")
    ap.add_argument("--only", help="Only regenerate one track by name (e.g. tiny_mystery).")
    ap.add_argument("--dry-run", action="store_true", help="Print what would happen, no calls.")
    args = ap.parse_args()

    out_dir = Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)

    session = requests.Session()
    if args.token:
        session.headers["Authorization"] = "Bearer " + args.token
    session.headers["Content-Type"] = "application/json"

    work = []
    for bucket, names in TRACKS.items():
        if args.bucket and bucket != args.bucket:
            continue
        for name in names:
            if args.only and name != args.only:
                continue
            work.append((bucket, name))

    if not work:
        print("Nothing matched the filters.")
        return 1

    print("Regenerating %d track(s) via %s  (model=%s)" % (len(work), args.base_url, args.model))
    if not args.dry_run and not args.token:
        print("WARNING: no SUNO_SECRET_TOKEN - the wrapper will likely reject the request.")

    ok = 0
    for bucket, name in work:
        try:
            if generate_one(session, args.base_url.rstrip("/"), args.model,
                            bucket, name, out_dir, args.dry_run):
                ok += 1
        except requests.RequestException as e:
            print("  ! request failed: %s" % e)

    print("\nDone: %d/%d track(s)." % (ok, len(work)))
    return 0 if ok == len(work) else 2


if __name__ == "__main__":
    raise SystemExit(main())
