#!/usr/bin/env bash
# Generates 20+ varied reference images per character for LoRA training.
# Uses your OPENAI_API_KEY directly (no service needed).
#
# Requires: jq, curl
# Output: ./dataset/<character_id>/{trigger}_<n>.png

set -euo pipefail

: "${OPENAI_API_KEY:?Set OPENAI_API_KEY in your environment}"

OUTDIR="${OUTDIR:-./dataset}"
SIZE="${SIZE:-1024x1024}"
MODEL="${MODEL:-gpt-image-1}"

mkdir -p "$OUTDIR"

# Per character, define the SAME description as bible/channel.yml and a list
# of pose / context prompts that produce diverse training images.

# NOTE: prefer generate-refs.py (Gemini hero-reference mode + correct captions).
# These descriptions are now bible-accurate (the old ones were wrong: red-orange
# Pip, green glasses for Mo, white+spotted Bo — they poisoned the LoRA).
declare -A CHARACTERS
CHARACTERS[pip]="A small fluffy newly-hatched baby chick with soft cream-white down feathers all over the body, a bright red pointy comb, extra-large round shiny brown eyes with bright highlights, wearing a tiny straw farmer hat on the head and a red bandana around the neck, small orange beak, two thin orange legs, NO clothing"
CHARACTERS[mo]="A round fluffy newly-hatched baby chick with soft blue-grey down feathers all over the body, a bright red pointy comb, wearing a red knitted scarf around the neck, extra-large round shiny brown eyes, small orange beak, two thin orange legs, NO clothing"
CHARACTERS[bo]="A small fluffy newly-hatched baby chick with soft tan and sandy-brown down feathers, a small tuft of feathers on top of the head, wearing round thin-framed eyeglasses on the beak and a green scarf around the neck, extra-large round shiny eyes, small orange beak, two thin orange legs, NO clothing"

POSES=(
  "standing facing the camera, full body shot, glossy 3D Pixar Illumination cartoon style, no clothing"
  "in profile from the side, walking, glossy 3D Pixar Illumination cartoon style, no clothing"
  "looking up curiously, three-quarter view, glossy 3D Pixar Illumination cartoon style, no clothing"
  "sitting on a wooden bench, glossy 3D Pixar Illumination cartoon style, no clothing"
  "spreading wings, joyful expression, glossy 3D Pixar Illumination cartoon style, no clothing"
  "close-up portrait of the head, glossy 3D Pixar Illumination cartoon style, no clothing"
  "from behind, looking over the shoulder, glossy 3D Pixar Illumination cartoon style, no clothing"
  "in the sunflower garden, full body, glossy 3D Pixar Illumination cartoon style, no clothing"
  "in the cozy coop kitchen, glossy 3D Pixar Illumination cartoon style, no clothing"
  "by the frog pond, looking at the water, glossy 3D Pixar Illumination cartoon style, no clothing"
  "running with feathers ruffled, glossy 3D Pixar Illumination cartoon style, no clothing"
  "thinking with one wing on chin, glossy 3D Pixar Illumination cartoon style, no clothing"
  "laughing with beak open, glossy 3D Pixar Illumination cartoon style, no clothing"
  "sleeping curled up, glossy 3D Pixar Illumination cartoon style, no clothing"
  "pecking at the ground, glossy 3D Pixar Illumination cartoon style, no clothing"
  "jumping mid-air, dynamic pose, glossy 3D Pixar Illumination cartoon style, no clothing"
  "waving a wing hello, glossy 3D Pixar Illumination cartoon style, no clothing"
  "surprised expression, glossy 3D Pixar Illumination cartoon style, no clothing"
  "small, in the distance under blue sky, glossy 3D Pixar Illumination cartoon style, no clothing"
  "extreme close-up of the eye, glossy 3D Pixar Illumination cartoon style, no clothing"
)

for char in "${!CHARACTERS[@]}"; do
  desc="${CHARACTERS[$char]}"
  out="$OUTDIR/$char"
  mkdir -p "$out"
  echo "=== Generating references for $char ==="
  i=1
  for pose in "${POSES[@]}"; do
    prompt="$desc, $pose, no text, no watermark, no logos, single character, clean light background"
    file="$out/${char}_$(printf '%02d' $i).png"
    if [ -f "$file" ]; then
      echo "  $file (exists, skipping)"
      i=$((i+1))
      continue
    fi
    echo "  $file"
    payload=$(jq -n --arg m "$MODEL" --arg p "$prompt" --arg s "$SIZE" \
      '{model:$m, prompt:$p, size:$s, n:1}')
    resp=$(curl -sS -X POST https://api.openai.com/v1/images/generations \
      -H "Authorization: Bearer $OPENAI_API_KEY" \
      -H "Content-Type: application/json" \
      -d "$payload")
    b64=$(echo "$resp" | jq -r '.data[0].b64_json // empty')
    if [ -z "$b64" ]; then
      echo "    ERROR: $(echo "$resp" | jq -c '.error // .')" >&2
      continue
    fi
    echo "$b64" | base64 -d > "$file"
    i=$((i+1))
    sleep 1
  done
done

echo
echo "Done. Review $OUTDIR/*/ and DELETE bad images before captioning."
echo "Aim for 15-20 good images per character."
