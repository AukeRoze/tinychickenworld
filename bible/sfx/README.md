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
