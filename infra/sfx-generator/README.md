# SFX generator

One-shot script that fills the `bible/sfx/` library with character-specific
chicken sound effects via the ElevenLabs Sound Effects API.

## Setup

```bash
pip install elevenlabs
export ELEVENLABS_API_KEY=sk_...
```

(Get the key from https://elevenlabs.io/app/settings/api-keys — even the free
tier has enough credits for a single full library run.)

## Run

```bash
# Full library — 3 characters × 10 emotions × 2 variants = 60 clips
python generate-sfx.py

# Just one character (e.g. testing prompt quality first)
python generate-sfx.py --character pip

# Just one emotion across all characters
python generate-sfx.py --emotion curious

# Tweak clip length (default 2 sec)
python generate-sfx.py --duration 2.5

# More variants per emotion (less repetitive, but more cost)
python generate-sfx.py --variants 3
```

Resume-safe: existing files are skipped, so you can rerun without recharging.

## Cost

ElevenLabs charges ~3 credits per generated second of SFX. A full library
of 60 clips × 2s = 360 credits ≈ €2-3 on the Creator plan.

## Output

```
bible/sfx/
├── pip/curious-1.mp3, curious-2.mp3, excited-1.mp3, ...
├── mo/...
└── bo/...
```

The voice-service picks these up automatically when `VOICE_MODE=sounds`.

## Cleanup tips

After generating, listen through each clip and delete the obvious duds
(off-style, wrong species, distorted). Re-run with `--character X --emotion Y`
to regenerate just those.

## Editing the prompts

The emotion → prompt templates live in `EMOTIONS` and `CHARACTERS` near the
top of `generate-sfx.py`. Tweak them if you want a different sonic identity
per character.
