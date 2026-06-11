# Word sound-effects (onomatopoeia → real SFX)

When a script line contains a sound-effect word like **Bonk!**, **Boom boom!** or
**Tap tap tap**, the voice-service no longer lets the chicken *say* it. Instead it:

1. strips the word from the text sent to ElevenLabs, and
2. layers the matching clip from this folder into that line's audio.

## Filename convention

`words/<name>.mp3` (or `.wav`). The `<name>` is the **canonical** effect name.
Many spellings map onto one clip (see `OnomatopoeiaSfx.VOCAB`), e.g.
`bang/kaboom → boom`, `woosh/swoosh → whoosh`, `bzzz/bzz → buzz`,
`boink/sproing → boing`.

Recognised canonical names currently:
`bonk, boom, tap, whoosh, splat, splash, plop, pop, boing, buzz, thud, crash,
ding, zoom, knock, squeak`.

If a recognised word has **no** clip here, it is still removed from the spoken
text (so the chicken never says it) — it just produces no sound. Add a clip to
turn it on.

## ⚠️ These are auto-generated placeholders

The clips shipped here are simple ffmpeg-synthesised blips so the pipeline works
end-to-end. **Replace them with real, high-quality kids SFX** (same filenames) for
a better result — e.g. royalty-free packs from Pixabay, Freesound (CC0), or
Zapsplat. Keep them short (≈0.1–0.6 s), mono, and not too loud; the line audio is
normalised to mono 44.1 kHz on use.

## Adding a new effect word

1. Drop `words/<name>.mp3`.
2. Add the spellings → `<name>` to `VOCAB` in
   `services/voice-service/.../service/OnomatopoeiaSfx.java`.
