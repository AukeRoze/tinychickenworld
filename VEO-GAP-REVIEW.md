# VEO Gap Review — "Tiny Chicken World"

*Two-expert review (VEO Expert + Senior Java Engineer). Core rule: bad video =
VEO had to guess. We hunt for what's MISSING, not what's present.*

This review is **grounded in the actual pipeline**, so it deliberately does NOT
re-recommend what already exists. The point is the remaining guesswork.

> **Build status (2026-06-08):** ALL of G1–G6 are **DONE**. G3 (focus + DoF),
> G4 (momentum/ease), G1 (scale lock), G2 (surface/ground physics), G5 (inter-clip
> continuity carry between hero clips) and G6 (anticipation telegraph on hero
> beats) — all implemented as bible fields + `compileVeoPrompt()` / `buildVeoScenes()`
> clauses, no extra LLM calls, verified statically + YAML-validated. The Java 21
> build remains the real gate.

---

## 0. What already removes guesswork (do NOT rebuild)

| Layer | Where it lives | Status |
|---|---|---|
| Character DNA (coreColor, accessory, silhouette, feathers, build, weight, eyeColor, antiAccessory, tic) | `bible characters[].dna`, `dnaIdentityClause()` | ✅ strong |
| Shot-DNA (goal, emotion, motionSpeed, endPose, motionDesc) | script `ScriptScene`, `compileVeoPrompt()` | ✅ strong |
| World Bible (location desc, timeOfDay→light, weather, recurringMotif) | `bible locations[]`, `lightPhrase/weatherPhrase` | ✅ good |
| Camera Bible **per phase** (angle, lens, movement) | `bible cameraBible`, `cameraSpec()` | ✅ partial — see G3 |
| Deterministic Prompt Compiler | `compileVeoPrompt()` | ✅ strong |
| Directed motion (start→end interpolation) | `endImagePath` / last-frame | ✅ |
| Stability negative | `VEO_NEGATIVE` | ✅ |
| Audio (voice, ambient, music, char SFX) | assembly-service (decoupled from VEO clip) | ✅ by design |

So the five "bibles" the framework predicts as big wins (Character / Shot / World
/ Camera / Compiler) **already exist**. The remaining quality ceiling is in the
gaps below.

---

## Stap 1 + 2 — Remaining gaps (VEO effect + Java fix)

### G1 — Scale / size relationships *(World Bible gap — HIGH)*
**Missing:** nothing tells VEO how BIG a chick is relative to the world. A
sunflower, a pebble, the Big Oak, a puddle — all guessed.
**VEO effect (Cinematographer):** the single biggest "it doesn't feel like one
show" killer. A chick is flower-sized in one shot, rabbit-sized the next. Props
rescale between clips. Eyelines and staging break because VEO doesn't know the
characters share a consistent height.
**Java fix:** add a `scaleAnchor` to each character DNA and a `props` scale map to
locations. Compiler emits one "scale lock" sentence.
```
Character { ... scaleAnchor: "about as tall as 3 stacked pebbles, half a sunflower's height" }
Location  { ... props: { sunflower: "4x a chick", pebble: "fits under a wing", bigOak: "towering, 50x a chick" } }
```

### G2 — Surface / material physics *(Scene/World gap — HIGH for action)*
**Missing:** the ground/material a character interacts with. The framework's own
example: a chick digs, but VEO isn't told the ground is soft mud vs hard dirt vs
sand → a different dig every time.
**VEO effect (VFX Supervisor):** action animation is inconsistent and unconvincing
— no splash on water, no dust on dry earth, no give on mud. Movement reads
"floaty" because VEO invents the physics each clip.
**Java fix:** add `surface` to each location (and optional per-scene override).
Compiler maps it to a physics phrase only when the beat involves contact.
```
Location { ... surface: "soft mud" | "dry pebbles" | "shallow water" | "wooden porch" | "grass" }
→ "Ground reacts as soft mud: gentle squish, small clumps, footprints stay."
```

### G3 — Camera focus + depth-of-field *(Camera Bible gap — MEDIUM-HIGH)*
**Missing:** `cameraBible` has angle/lens/movement but no **focus point** or
**depth of field**. VEO guesses what's sharp and how much background blur.
**VEO effect (Film Director):** the "filmic" separation flickers — a close-up has
creamy bokeh in one clip and a flat everything-sharp look in the next. Subject
sometimes loses focus mid-clip.
**Java fix:** extend each cameraBible phase with `focus` + `depthOfField`; compiler
appends them to the Camera sentence.
```
hook: { ... focus: "lock focus on the main character's eyes", depthOfField: "shallow, soft creamy background" }
climax: { ... focus: "keep whole flock sharp", depthOfField: "deep, everything crisp" }
```

### G4 — Momentum / ease *(Animation gap — MEDIUM, cheap)*
**Missing:** motionSpeed → pace exists, but no **ease-in/out, weight follow-through,
anticipation**. VEO guesses acceleration.
**VEO effect (Pixar Director):** motion starts/stops robotically or drifts at
constant velocity → uncanny. Real character animation anticipates (dip before a
jump) and settles (overshoot + recover).
**Java fix:** no new data needed — derive from emotion + motionSpeed in the
compiler. One sentence: *"Motion eases in and out with a tiny anticipation before
the main action and a soft settle after; weight follows through naturally."*

### G5 — Inter-clip continuity carry *(Hero chaining — MEDIUM)*
**Missing:** within a scene, start→end is chained via last-frame. **Between**
consecutive hero clips there's no "continue from the previous clip's end state."
**VEO effect:** a visible jump in pose/camera between two hero shots that should
flow. Breaks the "one continuous film" feel at exactly the high-value moments.
**Java fix:** when scene N and N+1 are both hero, pass scene N's `endPose` (or its
last-frame still) as context into N+1's prompt ("continues seamlessly from: …").

### G6 — Anticipation/reaction as VEO performance data *(Emotion gap — LOW-MED)*
**Missing:** we now generate anticipation in the *script* (comedy craft), but it's
not a structured **performance** cue for VEO (what the face/body telegraphs before
the beat lands).
**Java fix:** optional `reaction` field on hero scenes → "telegraphs X a beat
before it happens."

---

## Stap 3 — Expert discussion (condensed)

**VEO Expert:** "G1 is the one. Identity is locked, but *scale* isn't — and a
character that changes size relative to a flower reads as a different character to
a 4-year-old, even with the right hat. That's our biggest remaining 'not one show'
signal."

**Java Engineer:** "Scale is static per character + per location, so it belongs in
the bible, not the per-scene script. One `scaleAnchor` field on DNA, one `props`
map on locations, and the compiler emits a scale-lock clause. No model changes
downstream — it's bible + `compileVeoPrompt()` only."

**VEO Expert:** "G2 only matters when there's contact — digging, splashing,
hopping. Gate the physics phrase on the beat goal so calm talking shots don't get
a pointless 'mud squishes' line."

**Java Engineer:** "Agreed — `surface` on the location, but the compiler only emits
it when `goal`/`motionDesc` mentions contact verbs. Cheap heuristic, no new LLM
call."

**VEO Expert:** "G3 and G4 are pure prompt-compiler upgrades — no new per-scene
data. Highest value-per-line-of-code. Do them in the same pass."

---

## Stap 4 — Improvement plan

| Missing info | Impact | Where it's guessed today | Fix (structural) | Cost |
|---|---|---|---|---|
| **G1 Scale / size** | **High** | every shot with a prop | `scaleAnchor` on Character DNA + `props` on Location; compiler scale-lock clause | bible + compiler |
| **G2 Surface / physics** | **High** (action) | every contact action (dig, splash, hop) | `surface` on Location (+ scene override); gated physics phrase | bible + compiler |
| **G3 Focus + DoF** | Med-High | every clip | add `focus`+`depthOfField` to cameraBible phases | bible + compiler |
| **G4 Momentum / ease** | Medium | every motion | derive ease/anticipation sentence in compiler | compiler only |
| **G5 Inter-clip carry** | Medium | hero→hero transitions | pass prev endPose into next hero prompt | orchestrator |
| **G6 Anticipation perf** | Low-Med | hero performance | optional `reaction` field | script + compiler |

**Recommended build order:** G3 + G4 first (compiler-only, zero data migration,
instant lift) → G1 + G2 next (bible fields + gated clauses, the real
series-consistency wins) → G5 → G6.

---

## Stap 5 — "Perfect VEO input" target (worked example)

Scene: *Pip digs at the base of the Big Oak and finds a shiny pebble (climax,
golden hour, after rain).*

**Today's compiled prompt (already good):**
> Animate from the start frame with slow, gentle motion… Camera: slightly low
> angle, 35mm wide, slow cinematic pull-back… Setting: the Big Oak clearing,
> warm golden-hour light, light rain with soft wet sparkle. Beat goal: Pip digs
> and uncovers a pebble. Performance: clearly convey wonder. Character identity
> (keep EXACT…): Pip is a fluffy yellow chick, ALWAYS wearing a straw hat… The
> characters move with small lifelike motion… Keep every character's identity…

**With G1–G4 added (the gaps closed):**
> …Camera: slightly low angle, 35mm wide, slow cinematic pull-back, **lock focus
> on Pip's eyes then rack to the pebble, shallow depth of field with soft creamy
> background**. Setting: the Big Oak clearing, warm golden-hour light, light rain…
> **Scale lock: Pip is about as tall as three stacked pebbles, half a sunflower;
> the Big Oak towers ~50× her height.** **Ground reacts as rain-soft mud — gentle
> squish, tiny clumps fly, a small footprint stays; the pebble glistens wet.**
> Beat goal: Pip digs and uncovers a pebble. Performance: clearly convey wonder,
> **telegraphed a beat early with a sharp little gasp**. **Motion eases in and out
> with a tiny anticipation dip before each dig and a soft settle after; weight
> follows through.** Character identity (keep EXACT…): Pip is a fluffy yellow
> chick…

The delta is entirely **structured data + compiler clauses** — no extra LLM calls,
fully deterministic, and it removes exactly the four things VEO is still guessing
in that shot: focus/DoF, scale, ground physics, and motion easing.
