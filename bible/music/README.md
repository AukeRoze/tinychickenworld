# Channel music

Royalty-free background tracks that become your channel's sonic identity.

## How it works

Every episode gets a **mood**, which the orchestrator buckets into one of three
categories and then plays a **random** track from that bucket:

| bucket       | used when the mood is…                                    |
|--------------|-----------------------------------------------------------|
| `energetic`  | energetic, adventure, excited, playful, silly, chaotic    |
| `thoughtful` | thoughtful, curious, wonder, discovery, mystery           |
| `calm`       | everything else (cozy, quiet, bedtime, warm) — the default |

## Adding tracks (no rebuild needed)

1. Drop the MP3 in this folder, e.g. `cozy_campfire.mp3`.
2. Register it in `../channel.yml` under `music.tracks`:

   ```yaml
   music:
     tracks:
       - id: cozy_campfire
         path: /bible/music/cozy_campfire.mp3
         mood: calm
   ```

   `path` is the **container** path (`/bible/...`), and `mood` must be one of
   `energetic | thoughtful | calm`. The file is read live, so a fresh job picks
   up new tracks immediately — no rebuild.

**Aim for 2-3 tracks per bucket** so episodes with the same mood don't reuse the
same track.

## Generating tracks (recommended)

`infra/sfx-generator/generate-music.py` generates a full brand-consistent
library via the ElevenLabs Music API (9 tracks, 3 per bucket, one shared
warm pastoral palette) and registers them in `channel.yml` with
`--register`. Or run everything at once: `fill-audio-library.bat` in the
repo root (ambient loops + music).

## Currently registered

- `sunny_adventure.mp3` → energetic
- `curious_clouds.mp3` → thoughtful
- `gentle_morning.mp3` → calm

After a `generate-music.py --register` run these are added:

- `rolling_hills_romp`, `bumblebee_boogie`, `puddle_jump_parade` → energetic
- `tiny_mystery`, `cloud_watching`, `what_is_that_glow` → thoughtful
- `starlight_nest`, `warm_straw_sunset`, `drowsy_dandelions` → calm

## Good free sources

- YouTube Audio Library (https://studio.youtube.com/ → Audio Library) — CC, no attribution
- Pixabay Music (https://pixabay.com/music/)
- Free Music Archive (https://freemusicarchive.org/)

Pick tracks that share one warm, soft, gentle palette. Avoid contemporary pop or
trendy sounds — you want timeless. Instrumental only (no vocals to fight the
narration).
