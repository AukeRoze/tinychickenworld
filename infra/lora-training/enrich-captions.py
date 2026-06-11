#!/usr/bin/env python3
"""
Enrich the basic captions written by caption-images.sh with per-image
context derived from the filename. Idempotent: runs you can safely re-run
because it always rewrites from scratch using filename hints.

Run:
    python3 enrich-captions.py
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

DATASET = Path(__file__).resolve().parent / "dataset"

TRIGGERS = {
    "pip": "pip_chicken",
    "mo":  "mo_chicken",
    "bo":  "bo_chicken",
}

DESCS = {
    "pip": "a red-orange chicken with a bright red comb, golden-yellow feathers, a bright red knitted scarf",
    "mo":  "a brown speckled chicken with round green eyeglasses, white chest feathers",
    "bo":  "a white chicken with black spots, a red rooster comb on the head, and a green knitted scarf around the neck",
}

STYLE = "soft watercolor storybook illustration"

# Multi-token combos checked first (longest match wins).
COMBO = [
    ("running_laughing",     "running with mouth open laughing"),
    ("running_focused",      "running with a focused expression"),
    ("running_cheerful",     "running cheerfully"),
    ("sitting_curious",      "sitting with a curious expression"),
    ("sitting_focused",      "sitting with a focused expression"),
    ("standing_angry",       "standing with an angry expression"),
    ("standing_playful",     "standing in a playful pose"),
    ("standing_smiling",     "standing and smiling"),
    ("laughing_standing",    "standing and laughing"),
    ("laughing_pose",        "in a laughing pose"),
    ("digging_cheerfully",   "digging cheerfully in the soil"),
    ("waving_happily",       "waving a wing happily"),
    ("hands_in_air",         "wings raised high in the air"),
    ("side_view",            "side view profile"),
]

# Single-token fallbacks.
SINGLE = {
    "running":   "running",
    "sitting":   "sitting upright",
    "standing":  "standing",
    "digging":   "digging in the soil",
    "waving":    "waving a wing",
    "laughing":  "laughing with mouth open",
    "focused":   "with a focused expression",
    "curious":   "with a curious expression",
    "cheerful":  "cheerful expression",
    "cheerfully":"cheerfully",
    "angry":     "with an angry expression",
    "playful":   "playful pose",
    "smiling":   "smiling",
    "happily":   "happily",
    "variations": "varied pose study",
}

# Tokens to ignore entirely — they don't add info.
IGNORE = {
    "white", "chicken", "pose", "image", "training", "dataset",
    "generate", "different", "differe",
}

# Strip trailing date stamp tokens like _202606031028 and an optional _N suffix.
DATE_RE = re.compile(r"_?\d{8,}(?:_\d+)?$")


def derive_context(stem: str) -> str:
    s = DATE_RE.sub("", stem).lower()
    s = s.replace("-", "_").replace(" ", "_").replace("…", "")
    s = re.sub(r"[^a-z_]", "", s)
    if not s:
        return ""

    parts: list[str] = []

    # Check multi-token combos first.
    matched = []
    for combo, phrase in COMBO:
        if combo in s:
            matched.append((combo, phrase))
            s = s.replace(combo, " ")
    parts.extend(p for _, p in matched)

    # Then per-token.
    tokens = [t for t in s.split("_") if t and t not in IGNORE]
    seen = set(p for _, p in matched)
    for t in tokens:
        if t in SINGLE and SINGLE[t] not in seen:
            parts.append(SINGLE[t])
            seen.add(SINGLE[t])

    return ", ".join(parts)


def write_caption(txt_path: Path, character: str, context: str) -> None:
    trigger = TRIGGERS[character]
    desc = DESCS[character]
    pieces = [trigger, desc]
    if context:
        pieces.append(context)
    pieces.append(STYLE)
    txt_path.write_text(", ".join(pieces) + "\n", encoding="utf-8")


def main() -> int:
    if not DATASET.is_dir():
        print(f"missing {DATASET}", file=sys.stderr)
        return 1

    total = 0
    for character in TRIGGERS:
        cdir = DATASET / character
        if not cdir.is_dir():
            print(f"skip {cdir} (missing)")
            continue
        n_char = 0
        for img in sorted(cdir.iterdir()):
            if img.suffix.lower() not in {".png", ".jpg", ".jpeg", ".webp"}:
                continue
            ctx = derive_context(img.stem)
            txt = img.with_suffix(".txt")
            write_caption(txt, character, ctx)
            n_char += 1
        print(f"{character}: rewrote {n_char} captions")
        total += n_char
    print(f"total: {total}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
