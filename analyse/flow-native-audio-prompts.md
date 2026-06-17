# Flow / Veo 3.1 native audio — promptwijziging

**Datum:** 2026-06-16 · **Status:** code klaar, `mvn test` + redeploy nog nodig (sandbox-VM lag eruit)

## Wat er verandert

De Veo-promptcompiler regisseert het geluid nu **zelf** in plaats van te rekenen op een
aparte ElevenLabs-stem + losse muziekmix. Dit geldt voor zowel de kopieerbare prompt in de
UI (waarmee je in Google Flow werkt) als het geautomatiseerde Veo-pad — beide lopen door
dezelfde `VeoPromptCompiler.compile(...)`.

Gestuurd door één master-kill-switch in de bible: `veoNativeAudio: true`
(afwezig/false = exact het oude gedrag, byte-identiek).

Drie dingen komen erbij, één regel verdwijnt:

1. **Wordeloze kuikenstemmen** — per aanwezig personage zijn `dna.signatureSound`, gekleurd
   door de scène-emotie. Géén taal (Pingu/Shaun-stijl blijft, wereldwijd schaalbaar).
2. **Ambient soundscape** — afgeleid van de locatie (pond/coop/forest/garden…) + weer.
3. **Muziek** — passend bij de episode-mood (energetic / thoughtful / calm), dezelfde mood
   waarop de orchestrator z'n track kiest.
4. **Weg:** de oude regel "do NOT lip-sync — keep the beak mostly closed". De snavel beweegt
   nu juist natuurlijk mee met de eigen chirps.

## Voorbeeld — audioblok dat nu achter de prompt komt

Scène: Pip + Mo bij de vijver, mood = *energetic*, emotie = *wonder*:

> The characters move with small lifelike motion — blinking, soft breathing, slight head and
> wing movement, their beaks opening and moving naturally in time with their own chirps and
> peeps (natural chick-sound motion, not human-word lip-sync) — with soft ambient life …
>
> *(camera/wereld/identiteit/cast-lock zoals altijd) …*
>
> **Audio (generate natively and perfectly in sync):** wordless, expressive chick
> vocalisations — Pip with a bright rising curious chirp and Mo with a soft low thoughtful hum
> — small chirps, peeps and trills that clearly carry wonder, in sync with each beak (NO human
> words, NO discernible language, no sung lyrics). **Ambient noise:** gentle lapping water,
> soft reed rustle and faint distant birdsong. **Music:** a joyful, upbeat orchestral
> children's score — plucky pizzicato strings, a bouncy ukulele, bright glockenspiel and light
> hand percussion, warm and playful, sitting softly under the scene. No spoken words, no human
> dialogue, no narration, and no on-screen text or captions.

## Gewijzigde bestanden

- `services/orchestrator/.../service/VeoPromptCompiler.java` — flag, signatureSound-cache,
  music/ambient/vocalisation-helpers, 12-arg `compile(...)`-overload, beak-branch + audioblok.
- `services/orchestrator/.../service/PipelineOrchestrator.java` — `musicMood` (`job.getMood()`)
  doorgegeven aan `buildVeoScenes` (3 call-sites: hoofdrun + 2 reroll-paden).
- `services/orchestrator/.../api/VideoController.java` — `job.getMood()` mee in de
  kopieerbare-prompt-aanroep.
- `bible/channel.yml` — `veoNativeAudio: true`.
- Test: `VeoPromptCompilerLeanTest` — 2 nieuwe cases (native-audio aan + uit).

## Belangrijk

- **Dubbel geluid vermijden:** als je het geautomatiseerde Vertex-pad draait (niet alleen
  handmatig Flow), zet dan de aparte stem-/muziekmix uit (`VOICE_MODE=silent` staat al zo;
  de muziek-mix in de assembly-service moet dan ook uit), anders krijg je Flow-audio *plus*
  de oude track.
- **Intro/outro** brand-clips bouwen via `IntroRebuildService`/`OutroRebuildService`, niet via
  deze compiler — die zijn (nog) niet aangepast.
- `mvn test` in de orchestrator draaien vóór redeploy.
