# Asset generator — brand & FX starter assets

Generates the optional assets the pipeline auto-detects, so the cheap (Ken Burns)
run looks as polished as possible **before** spending on Veo.

## What it makes

| File | Used for |
|---|---|
| `bible/fx/bell.png` | Shaking bell next to the SUBSCRIBE end-card |
| `bible/sting.mp3` | Sonic-logo chime under the opening title |
| `bible/sfx/transitions/whoosh.mp3` | Whoosh on scene transitions |
| `bible/intro.mp4` | 3s branded opener (logo + fade + sting) |
| `bible/outro.mp4` | 3s branded closer (logo + "SUBSCRIBE FOR MORE") |
| `bible/fx/ambient.webm` | Subtle transparent particle/bokeh loop over every scene |

All paths match what the code already looks for — no rebuild needed, the services
read these at runtime.

## Run it

Easiest (inside the assembly container — has ffmpeg + the font + the bible mounted):

```bash
docker compose cp infra/asset-gen video-assembly-service:/tmp/asset-gen
docker compose exec video-assembly-service bash /tmp/asset-gen/generate-assets.sh /bible
```

Or locally from the repo root (needs `ffmpeg` + `python3` + `pip install pillow`):

```bash
bash infra/asset-gen/generate-assets.sh ./bible
```

## Notes

- These are **clean starter assets**, not final art. Swap any of them for
  designed/stock files for a more polished channel — same filenames, done.
- `ambient.webm` is the hardest to fake well; for real butterflies/fireflies a
  designed transparent loop looks much better. The generated one is a subtle
  bokeh placeholder and is skipped automatically if your ffmpeg lacks VP9/alpha.
- `intro.mp4` / `outro.mp4` are skipped if `bible/logo.png` is missing.
