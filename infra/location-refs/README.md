# Location hero references

Generates atmospheric "establishing shot" images for every location in
`bible/channel.yml`, in different time-of-day moods. These serve as
visual anchors so the inference pipeline produces consistent environments
across scenes.

Mirrors the `infra/lora-training/generate-refs.py` pattern but for
environments instead of characters.

## Setup

```bash
pip install google-genai PyYAML
export GEMINI_API_KEY=...   # https://aistudio.google.com/apikey
```

## Run

```bash
# Full set — every location × every time-of-day mood
python generate-locations.py

# Just one location (e.g. testing prompt quality)
python generate-locations.py --location bigOak

# One mood across all locations
python generate-locations.py --mood goldenHour

# 3 variants of each combo (more variety, more cost)
python generate-locations.py --variants 3
```

Resume-safe: existing files are skipped.

## Cost

Gemini 2.5 Flash Image ≈ €0.04 per image. Full library with 12 locations
× 6 moods × 1 variant = 72 images ≈ €3. With 3 variants ≈ €9.

## Output

```
bible/refs/locations/
├── coop/
│   ├── dawn.png
│   ├── morning.png
│   ├── midday.png
│   ├── goldenHour.png
│   ├── dusk.png
│   └── night.png
├── garden/
│   └── ...
├── bigOak/
│   └── ...
```

## Manual cleanup

After generating, browse through the folders and delete obvious duds
(off-style, anatomically wrong, characters accidentally included).
Re-run with `--location X --mood Y` to regenerate just those.

## How the pipeline uses these

(Coming soon — once the image-service is updated to optionally pass a
location ref image to the generation API. For now these serve as visual
documentation of the world's look.)
