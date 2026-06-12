# Ambient FX overlays (visual)

Looping, **transparent-background** video clips that video-assembly-service
composites over every Ken Burns scene (opacity 0.8) to add life to a still
image: drifting fireflies, butterflies, falling petals, rain drops, bokeh.

Everything here is **dormant-until-asset**: drop a file and the next render
picks it up automatically (checks are re-statted within a minute) — no rebuild,
no restart, no config. An empty folder = the exact render you had before.

## Formats

`.mov` (e.g. ProRes 4444 / QuickTime with alpha) or `.webm` (VP9 with alpha).
When both exist for the same name, `.mov` wins. Clips should LOOP cleanly;
they are stretched to the full canvas and repeated for the scene duration.

## File layout + resolution order

Per scene, the **first existing file wins** (each level tries `.mov` then `.webm`):

```
fx/
├── weather/{weather}.mov|webm       1. weather beats everything
│   ├── lightRain.webm                  e.g. drops on the lens
│   └── snow.webm
├── time/{timeOfDay}.mov|webm        2. time of day
│   ├── night.webm                      e.g. fireflies / drifting stars
│   └── dusk.webm
├── location/{locationId}.mov|webm   3. location
│   ├── garden.webm                     e.g. butterflies
│   └── pond.webm
└── ambient.mov|webm                 4. global fallback (original behaviour)
```

The ids come straight from the script bible, per scene:

- `weather`: `clear`, `lightRain`, `breezy`, `snow`
- `timeOfDay`: `goldenHour`, `midday`, `dusk`, `night`
- `locationId`: the bible location ids — `coop`, `garden`, `pond`, `porch`,
  `kitchen`, `willowGrove`, `oak`, `hills`, …

So: a rainy night in the garden with all three assets present shows the
**rain** overlay (weather wins); remove `weather/lightRain.webm` and the same
scene falls through to `time/night.webm`, then `location/garden.webm`, then
`ambient.*`, then nothing.

Tip: you probably do NOT want a `weather/clear.*` or `time/midday.*` file —
leaving the default states empty keeps overlays special.

## Effect ↔ sound coupling

The matching **sound** beds already exist on the audio side:
`bible/sfx/ambient/{locationId}.mp3` is mixed under each scene's audio by the
voice-service (AmbientMixer), keyed on the same `locationId`. So when a
location overlay matches, picture and sound are coupled automatically by the
shared id — drop `fx/location/garden.webm` next to `sfx/ambient/garden.mp3`
and the garden both flutters and buzzes.

**Weather** is coupled the same way: a `sfx/ambient/{weather}.mp3` bed
(e.g. `lightRain.mp3`, `breezy.mp3`, `snow.mp3`) takes priority over the
location bed in the voice-service's AmbientMixer, mirroring the
weather-beats-everything order of the visual overlays. `clear` never
overrides. See `bible/sfx/README.md` for the sound-side file names.
