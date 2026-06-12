# Sound effects library

Used by voice-service when `VOICE_MODE=sounds`. Each character has their own
sub-folder; common ambient sounds go in `common/`; narrator phrases (when
enabled) in `narrator/`.

## Structure

```
sfx/
├── pip/                         ← Pip's sonic identity (high, fast, excited)
│   ├── curious-1.mp3            ← variant 1
│   ├── curious-2.mp3            ← variant 2
│   ├── excited-1.mp3
│   ├── ...
├── mo/                          ← Mo's sonic identity (low, slow, calm)
│   ├── ...
├── bo/                          ← Bo's sonic identity (dramatic, silly)
│   ├── ...
├── common/                      ← Shared ambient sounds
│   ├── coop-ambient.mp3
│   ├── porch-ambient.mp3
│   ├── ...
└── narrator/                    ← Optional TTS-generated narrator phrases
    ├── intro.mp3
    └── ...
```

## Ambient beds (`ambient/`)

`ambient/{locationId}.mp3` — a looping sound bed per bible location (`coop`,
`garden`, `pond`, `porch`, `kitchen`, `willowGrove`, `oak`, `hills`, …), mixed
softly (~ -16 dB) under every scene at that location by the voice-service's
AmbientMixer. Mapping is configured in `channel.yml` (`ambientByLocation`).

These beds are the SOUND half of the ambient FX overlays in `bible/fx/`
(see `bible/fx/README.md`): the visual overlay `fx/location/{locationId}.*`
and the bed `sfx/ambient/{locationId}.mp3` share the same id, so dropping
both couples picture and sound automatically.

### Weather beds (override the location bed)

`ambient/{weather}.mp3` — a looping bed per weather id. When the scene
carries a weather id AND a matching file exists, the AmbientMixer picks the
weather bed INSTEAD of the location bed — so the rain you see (the visual
overlay `fx/weather/{weather}.*`) also sounds like rain instead of like the
garden. No file (or `clear` weather) = the normal location bed.

Weather file names match the script-bible weather ids:

```
ambient/
├── lightRain.mp3   ← soft rain patter, drips
├── breezy.mp3      ← gusting wind through leaves
└── snow.mp3        ← muffled winter hush, faint wind
```

`clear` is intentionally NOT a file — clear weather never overrides, the
location bed keeps playing. Like everything in the bible this is
dormant-until-asset: drop a file and the next synthesis uses it.

## Emotion taxonomy

Used by script-service emotion tag → voice-service file picker mapping.
Each emotion should have 2-3 variants per character so it doesn't repeat
mechanically.

| Tag | Description |
|---|---|
| `curious`     | Wondering, investigating sound |
| `excited`     | Happy energetic peeps |
| `surprised`   | Sudden gasp |
| `gasping`     | Big wide-eyed wonder gasp |
| `laughing`    | Cackle-laugh, character-specific |
| `thinking`    | Slow contemplative cluck |
| `sleepy`      | Drowsy peep / yawn |
| `content`     | Soft happy chirp |
| `confused`    | Questioning cluck |
| `agreeing`    | Yes-yes-yes nodding sound |

## Generating the library

Use `infra/sfx-generator/generate-sfx.py` (ElevenLabs Sound Effects API)
to create the full set in ~30 minutes for €2-3.

```bash
export ELEVENLABS_API_KEY=sk_...
python infra/sfx-generator/generate-sfx.py
```

The script is resume-safe — drops files that already exist.
