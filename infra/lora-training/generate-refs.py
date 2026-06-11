#!/usr/bin/env python3
"""
Generate Pixar-style training references for Pip / Mo / Bo via Gemini's
image API (Nano Banana = gemini-2.5-flash-image).

Output:
  dataset/{pip,mo,bo}/refxxx.png      — image
  dataset/{pip,mo,bo}/refxxx.txt      — caption (used by the LoRA trainer)

HERO REFERENCE MODE (recommended)
  Drop ONE perfect image per character into dataset-refs/:
      dataset-refs/pip-hero.png
      dataset-refs/mo-hero.png
      dataset-refs/bo-hero.png
  (.png / .jpg / .jpeg / .webp accepted)
  The script then passes the hero image to Gemini as a visual anchor for
  every variation — far better character consistency than text-only.

  You can also drop multiple hero refs per char (e.g. pip-hero-1.png,
  pip-hero-2.png) as a tiny turnaround sheet; all of them are included
  as references on every call.

  If no hero image is present, the script falls back to pure text prompts.

Resume-safe: skips any (image, caption) pair that already exists.

Usage:
  pip install google-genai pillow
  export GEMINI_API_KEY=...        # or GOOGLE_API_KEY
  python generate-refs.py                       # all 3 characters, 20 each
  python generate-refs.py --character pip       # just one
  python generate-refs.py --count 25            # custom count
  python generate-refs.py --start 5             # resume from index 5
"""
from __future__ import annotations

import argparse
import os
import sys
import time
from pathlib import Path

try:
    from google import genai
    from google.genai import types
except ImportError:
    sys.exit("Missing dep — run:  pip install google-genai pillow")

# ----------------------------------------------------------------------
# Character templates. KEEP the accessory in the prompt EVERY time —
# that is the single biggest fix vs the previous LoRA.
# ----------------------------------------------------------------------

STYLE_TAIL = (
    "Stylized Pixar / Illumination 3D cartoon render — the SAME glossy 3D style "
    "as the banner. NEUTRAL, even daylight lighting (NOT golden-hour, NOT a warm "
    "orange tint), the CHARACTER in sharp focus with NO depth-of-field blur and "
    "NO bokeh. Keep the background simple and secondary so the character stays "
    "the clear subject. Consistent character design and colours. "
    "ABSOLUTELY ONLY ONE CHICK IN THE FRAME — no duplicates, no twin, no "
    "second bird visible anywhere, no reflection of another bird, no silhouette "
    "of another bird. Single character composition."
)

# Banner-true mapping: Pip = cream-white + straw hat, Mo = blue-grey + red scarf,
# Bo = tan + round glasses + green scarf.
CHARACTERS = {
    "pip": {
        "trigger": "pip_chicken",
        "body": (
            "a small fluffy newly-hatched chick with soft CREAM-WHITE down "
            "feathers all over the body, a bright red pointy chicken comb "
            "on top of the head, EXTRA-LARGE round shiny brown eyes with "
            "multiple bright reflective highlights and clearly visible iris "
            "detail, thick dark eyelashes, small orange beak, two thin "
            "orange legs, dense fluffy 3D-rendered baby feathers"
        ),
        "accessory": (
            "wears a tiny STRAW FARMER'S HAT on the head (the straw hat must "
            "be clearly visible — the signature accessory) and a small RED "
            "bandana around the neck (NOT a scarf-less neck, NOT glasses)"
        ),
    },
    "mo": {
        "trigger": "mo_chicken",
        "body": (
            "a round fluffy newly-hatched chick with soft BLUE-GREY down "
            "feathers all over the body, a bright red pointy chicken comb, "
            "EXTRA-LARGE round shining brown eyes with multiple highlights, "
            "thick dark eyelashes, warm friendly expression, small orange "
            "beak, two thin orange legs, dense fluffy 3D-rendered baby feathers"
        ),
        "accessory": (
            "wears a small RED knitted scarf around the neck (clearly visible "
            "— the signature accessory; NOT a hat, NOT eyeglasses)"
        ),
    },
    "bo": {
        "trigger": "bo_chicken",
        "body": (
            "a small fluffy newly-hatched chick with soft warm TAN and "
            "sandy-brown down feathers, a tiny tuft of feathers sticking up on "
            "top of the head, EXTRA-LARGE round shiny eyes with multiple "
            "highlights, thick dark eyelashes, slightly curious head tilt, "
            "small orange beak, two thin orange legs, dense fluffy 3D-rendered "
            "baby feathers"
        ),
        "accessory": (
            "wears ROUND THIN-FRAMED EYEGLASSES perched on the beak (clearly "
            "visible — signature accessory) and a small GREEN scarf around the "
            "neck (NOT a hat)"
        ),
    },
}

# Short identity phrases used for the TRAINING captions (lean — see build_caption).
SHORT_DESC = {
    "pip": "a cream-white fluffy baby chick wearing a straw hat and a red bandana",
    "mo":  "a blue-grey fluffy baby chick wearing a red scarf",
    "bo":  "a tan fluffy baby chick wearing round glasses and a green scarf",
}

# ----------------------------------------------------------------------
# Variation lists — combined by index. 20 unique combos per character.
# ----------------------------------------------------------------------

POSES = [
    "sitting peacefully on the ground",
    "standing tall and looking up curiously",
    "mid-jump with both tiny feet off the ground",
    "peering curiously at something on the floor",
    "yawning with eyes half-closed",
    "looking surprised with eyes wide open",
    "napping with head tucked into chest",
    "running playfully with feathers ruffled",
    "pecking gently at the ground",
    "spreading tiny wings wide",
    "thoughtful pose with head tilted to one side",
    "laughing with open beak",
    "waving one wing in greeting",
    "tiptoeing carefully",
    "peeking shyly from behind something with just eyes showing",
    "sitting calmly on a hay bale",
    "climbing onto a small wooden step",
    "looking back over one shoulder",
    "carrying a small leaf in the beak",
    "curled up sleeping with eyes closed and a small smile",
]

LOCATIONS = [
    "inside the cozy wooden chicken coop with red gabled roof and hanging paper lantern",
    "in the sunflower garden with tall yellow sunflowers and pink climbing roses",
    "by the calm frog pond with green lily pads and pink lotus flowers",
    "in the warm coop kitchen with copper pots and a steaming kettle",
    "on a golden hay bale next to a wooden barrel",
    "on a wooden porch with terracotta flowerpots of red geraniums",
    "among tall sunflowers with blue butterflies in the air",
    "next to a glowing hanging lantern",
    "in front of a hand-drawn treasure map pinned to a wooden wall",
    "by a small round wooden window with checkered yellow curtains",
    "on a soft moss patch under tall grass blades",
    "next to a basket of decorated eggs",
    "on a winding stone path lined with daisies",
    "next to a small painted wooden sign",
    "by a tiny wooden bench with a soft cushion",
    "near a wheelbarrow filled with vegetables",
    "under a rope bridge with rounded clouds in the background",
    "next to a cast-iron stove with a steaming kettle",
    "on a rug of red and white stripes inside the cosy kitchen",
    "by a hanging garland of dried flowers",
]

ANGLES = [
    "front-facing close-up portrait",
    "side profile medium shot",
    "low-angle hero shot looking up at the chick",
    "three-quarter angle medium shot at the chick's eye level",
    "slight overhead angle showing the chick from above",
    "front close-up with the chick centered",
    "side three-quarter shot at chick's eye level",
    "low-angle close-up making the chick look heroic",
    "medium shot showing chick from the front",
    "front-facing medium close-up",
    "three-quarter front shot",
    "side close-up portrait",
    "slight low-angle close-up",
    "front-facing wide close-up",
    "side medium shot with chick centered",
    "three-quarter rear angle showing chick looking back",
    "low-angle wide shot",
    "front close-up at eye level",
    "side three-quarter close-up",
    "slight three-quarter front portrait",
]


def build_prompt(char_id: str, idx: int, has_hero: bool) -> str:
    ch = CHARACTERS[char_id]
    pose = POSES[idx % len(POSES)]
    loc = LOCATIONS[idx % len(LOCATIONS)]
    angle = ANGLES[idx % len(ANGLES)]

    if has_hero:
        # Hero-reference mode. Gemini drifts when given freedom — the
        # PRESERVE / CHANGE / HARD RULES structure forces it to treat this
        # as an identity-preserving EDIT, not a creative GENERATE.
        # Framing it as an "edit task" gives noticeably more consistent
        # output than "generate based on reference".
        return (
            "TASK: edit the reference image to show the EXACT SAME "
            "character in a new scene. This is a character-consistency "
            "task — the character must be IDENTICAL to the reference; "
            "only the pose, location and camera angle may change.\n\n"
            "STRICTLY PRESERVE (must match the reference image EXACTLY, "
            "do not alter in any way):\n"
            "- Feather colors and pattern (exact same shades, exact same "
            "markings, exact same spot placement)\n"
            "- Eye color, eye shape, eye size, eyelash style and the "
            "specific arrangement of bright highlights inside the eyes\n"
            "- Head shape and head-to-body proportion\n"
            "- Body shape, body size and overall character proportions\n"
            "- Beak color, beak shape and beak size\n"
            "- Leg color\n"
            f"- ALL accessories from the reference, in identical color, "
            f"identical material and identical position on the body. "
            f"Specifically: {ch['accessory']}\n"
            "- Overall character age (a young chick, not an adult bird)\n"
            "- Pixar 3D cartoon render style (no shift to 2D, watercolor, "
            "felt, anime, photo-realism, or any other style)\n\n"
            "ONLY CHANGE (these may differ from the reference):\n"
            f"- Pose: {pose}\n"
            f"- Location and background: {loc}\n"
            f"- Camera angle and framing: {angle}\n\n"
            "HARD RULES:\n"
            "- Exactly ONE character in the frame. No duplicates, no twin, "
            "no second chick visible anywhere, no reflection of another "
            "chick, no silhouette of another chick.\n"
            "- The character must look like the SAME INDIVIDUAL as in the "
            "reference, not a similar-looking different chick.\n"
            "- Do not invent new accessories and do not remove any "
            "accessory present in the reference.\n"
            "- Do not modify feather color, eye color or body proportions "
            "even slightly.\n"
            "- Do not age the character up or make the chick smaller or "
            "larger relative to its head.\n\n"
            f"{STYLE_TAIL}"
        )

    # Fallback: pure text. Trigger word leading so the caption-derived
    # embedding lines up with how the image-service composes prompts.
    return (
        f"{ch['trigger']}, {ch['body']}, {ch['accessory']}. "
        f"{pose.capitalize()}, {loc}. {angle.capitalize()}. {STYLE_TAIL}"
    )


def build_caption(char_id: str, idx: int) -> str:
    """Caption stored alongside the image. The LoRA trainer reads these to
    bind the trigger word to the visual features.

    v2 captions: explicit anti-cross-leak language. The previous LoRA
    collapsed onto Mo because all 3 characters shared too many tokens
    ("Pixar 3D cartoon chick"). This version makes each character's
    caption EXCLUSIONARY — every Pip caption explicitly tells the trainer
    "NOT brown speckled, NOT wearing glasses" etc. Forces the LoRA to
    learn each trigger word as a tight, separated embedding."""
    ch = CHARACTERS[char_id]
    pose = POSES[idx % len(POSES)]

    # Lean, training-friendly caption: trigger word + a SHORT identity phrase +
    # the part that VARIES (pose). No negations / "must be visible" / other
    # trigger words — those are prompt instructions, not image descriptions, and
    # for per-character LoRAs the exclusions are unnecessary noise.
    short = SHORT_DESC[char_id]
    return f"{ch['trigger']}, {short}, {pose}, simple background, 3D Pixar cartoon style"


# ----------------------------------------------------------------------
# Gemini call
# ----------------------------------------------------------------------

def make_client() -> "genai.Client":
    key = os.environ.get("GEMINI_API_KEY") or os.environ.get("GOOGLE_API_KEY")
    if not key:
        sys.exit("Set GEMINI_API_KEY or GOOGLE_API_KEY env var")
    return genai.Client(api_key=key)


def load_hero_refs(char_id: str, refs_dir: Path) -> list[bytes]:
    """Find any hero reference images for this character. Filenames matched:
       {char}-hero.{png,jpg,jpeg,webp}
       {char}-hero-*.{...}   (e.g. pip-hero-front.png, pip-hero-side.png)
    """
    if not refs_dir.is_dir():
        return []
    exts = (".png", ".jpg", ".jpeg", ".webp")
    blobs: list[bytes] = []
    for p in sorted(refs_dir.iterdir()):
        if not p.is_file() or p.suffix.lower() not in exts:
            continue
        stem = p.stem.lower()
        if stem == f"{char_id}-hero" or stem.startswith(f"{char_id}-hero-"):
            blobs.append(p.read_bytes())
    return blobs


def _mime_for(blob: bytes) -> str:
    if blob[:8] == b"\x89PNG\r\n\x1a\n":
        return "image/png"
    if blob[:3] == b"\xff\xd8\xff":
        return "image/jpeg"
    if blob[:4] == b"RIFF" and blob[8:12] == b"WEBP":
        return "image/webp"
    return "image/png"


def generate_one(client, prompt: str, hero_refs: list[bytes],
                 out_png: Path, retries: int = 3) -> bool:
    """Returns True if a PNG was written, False if blocked / no image returned."""
    # Hero refs go BEFORE the prompt. Gemini's multimodal attention weights
    # earlier inline_data parts as the primary visual anchor.
    contents: list = []
    for blob in hero_refs:
        contents.append(types.Part.from_bytes(
            data=blob, mime_type=_mime_for(blob)))
    contents.append(prompt)

    for attempt in range(1, retries + 1):
        try:
            resp = client.models.generate_content(
                model="gemini-2.5-flash-image",
                contents=contents,
                config=types.GenerateContentConfig(
                    response_modalities=["IMAGE", "TEXT"],
                ),
            )
            for cand in resp.candidates or []:
                for part in (cand.content.parts if cand.content else []):
                    inline = getattr(part, "inline_data", None)
                    if inline and inline.data:
                        out_png.write_bytes(inline.data)
                        return True
            print(f"  no image in response, retrying ({attempt}/{retries})")
        except Exception as e:
            print(f"  error: {e}  retrying ({attempt}/{retries})")
        time.sleep(2 * attempt)
    return False


# ----------------------------------------------------------------------
# Main
# ----------------------------------------------------------------------

def run(characters: list[str], count: int, start: int,
        dataset: Path, refs_dir: Path) -> None:
    client = make_client()
    grand_ok = 0
    grand_fail = 0

    for ch_id in characters:
        out_dir = dataset / ch_id
        out_dir.mkdir(parents=True, exist_ok=True)
        hero_refs = load_hero_refs(ch_id, refs_dir)
        mode = f"HERO ({len(hero_refs)} ref{'s' if len(hero_refs)!=1 else ''})" \
               if hero_refs else "TEXT-ONLY"
        print(f"\n=== {ch_id.upper()} → {out_dir}  [mode: {mode}] ===")

        for i in range(start, start + count):
            stem = f"ref{i:03d}"
            png = out_dir / f"{stem}.png"
            txt = out_dir / f"{stem}.txt"

            if png.exists() and txt.exists():
                print(f"  [{i:03d}] skip (already exists)")
                continue

            prompt = build_prompt(ch_id, i, has_hero=bool(hero_refs))
            print(f"  [{i:03d}] generating ...")
            ok = generate_one(client, prompt, hero_refs, png)
            if ok:
                txt.write_text(build_caption(ch_id, i), encoding="utf-8")
                grand_ok += 1
                print(f"  [{i:03d}] OK  -> {png.name}")
            else:
                grand_fail += 1
                print(f"  [{i:03d}] FAILED — leave gap and move on")

    print(f"\nDone. ok={grand_ok}  failed={grand_fail}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--character", choices=list(CHARACTERS) + ["all"], default="all")
    ap.add_argument("--count", type=int, default=20)
    ap.add_argument("--start", type=int, default=0,
                    help="Starting index (use to resume after a failure batch)")
    ap.add_argument("--refs-dir", type=Path,
                    default=Path(__file__).parent / "dataset-refs",
                    help="Where to look for {char}-hero.png files")
    ap.add_argument("--dataset", type=Path,
                    default=Path(__file__).parent / "dataset")
    args = ap.parse_args()

    chars = list(CHARACTERS) if args.character == "all" else [args.character]
    run(chars, args.count, args.start, args.dataset, args.refs_dir)


if __name__ == "__main__":
    main()
