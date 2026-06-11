# Channel Bible

Everything that defines your channel's identity lives here. Three services
load this folder at startup:

- **script-service** uses `channel.yml` (cast names, personalities, catchphrases)
  to write scripts that always feature the same characters.
- **image-service** uses `channel.yml` (character descriptions + visual style
  + locations) to draw consistent images.
- **voice-service** uses `channel.yml` (voiceId per character) to route each
  spoken line through the right ElevenLabs voice.

## Required files

- `channel.yml` — the cast, world, style and voice mapping.

## Optional files (recommended)

- `intro.mp4` — 3-second branded opener (logo + character wave + jingle).
  Pre-render once. Concatenated to the front of every video.
- `outro.mp4` — 3-second branded closer (subscribe animation + character wave).
- `music/*.mp3` — channel sound. 3-5 royalty-free tracks max. Listed in
  `channel.yml`. Orchestrator picks one per video based on mood.

If `intro.mp4` / `outro.mp4` are missing, the pipeline skips them silently.
The video still ships — just without branding bumpers.

## ElevenLabs voice IDs

`channel.yml` references voice IDs via env vars: `VOICE_ID_PIP`, `VOICE_ID_MO`,
`VOICE_ID_BO`. Set these in `.env` at the project root. Pick three distinct
voices from the ElevenLabs Voice Library (or clone your own on the Pro tier).

If a voice ID is missing the service falls back to `ELEVENLABS_VOICE_ID`
(the default narrator voice).

## Adding more characters later

Append a new entry under `characters:` in `channel.yml`. Match the existing
structure (id, name, description, personality, optional voiceId). Restart
the affected services. No migrations needed — the bible is config, not data.
