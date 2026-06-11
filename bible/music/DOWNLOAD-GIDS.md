# Download-gids: 9 gratis muziektracks (optie 2)

Bronnen (gratis, geen account nodig behalve YT Studio):
- **Pixabay Music** — https://pixabay.com/music/ (geen attributie nodig)
- **YouTube Audio Library** — https://studio.youtube.com → Audiobibliotheek (filter: attributie niet vereist)

Eisen per track: **instrumentaal** (geen zang), warm/zacht/akoestisch, geen
moderne pop of EDM-drops, minimaal ~40s (langer mag — de mixer loopt/kort af).

## Zoektermen + doelbestandsnaam

Hernoem elke download naar **exact** deze naam en zet hem in `bible\music\`:

| Zoek op (Pixabay/YT) | Opslaan als | Mood |
|---|---|---|
| "playful ukulele kids" | `rolling_hills_romp.mp3` | energetic |
| "pizzicato playful children" | `bumblebee_boogie.mp3` | energetic |
| "happy marimba kids march" | `puddle_jump_parade.mp3` | energetic |
| "curious pizzicato sneaky cute" | `tiny_mystery.mp3` | thoughtful |
| "dreamy flute soft piano" | `cloud_watching.mp3` | thoughtful |
| "gentle piano wonder build" | `what_is_that_glow.mp3` | thoughtful |
| "music box lullaby" | `starlight_nest.mp3` | calm |
| "calm acoustic guitar warm" | `warm_straw_sunset.mp3` | calm |
| "soft piano waltz sleepy" | `drowsy_dandelions.mp3` | calm |

Luister 10 seconden per kandidaat: past hij bij de andere acht? Eén warm
palet is belangrijker dan de perfecte individuele track.

## Daarna: registreren (geen API nodig)

```
python infra\sfx-generator\generate-music.py --register
```

Bestaande bestanden worden overgeslagen (geen ElevenLabs-call) maar wél in
`channel.yml` gezet met de juiste mood. Nieuwe jobs pakken ze direct op.

Tip: tracks die tegenvallen vervang je later gewoon — zelfde bestandsnaam,
geen herregistratie nodig.
