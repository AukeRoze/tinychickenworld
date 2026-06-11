#!/usr/bin/env bash
set -euo pipefail

DATASET="${DATASET:-./dataset}"

declare -A TRIGGERS
TRIGGERS[pip]="pip_chicken"
TRIGGERS[mo]="mo_chicken"
TRIGGERS[bo]="bo_chicken"

# Bible-accurate descriptions (matches bible/channel.yml). The OLD values here
# were wrong (red-orange Pip, green glasses for Mo, watercolor) and poisoned the
# LoRA. Prefer generate-refs.py (writes correct captions itself); this is a fallback.
declare -A DESCS
DESCS[pip]="a fluffy newly-hatched baby chick with soft cream-white down feathers, a bright red pointy comb, extra-large round shiny brown eyes, a tiny straw farmer hat, a red bandana around the neck, small orange beak"
DESCS[mo]="a round fluffy baby chick with soft blue-grey down feathers, a bright red pointy comb, a red knitted scarf around the neck, extra-large shiny brown eyes, small orange beak"
DESCS[bo]="a fluffy baby chick with soft tan and sandy-brown down feathers, a small feather tuft on top, round thin-framed eyeglasses on the beak, a green scarf around the neck, extra-large shiny eyes, small orange beak"

shopt -s nullglob nocaseglob

for char in "${!TRIGGERS[@]}"; do
  dir="$DATASET/$char"
  if [ ! -d "$dir" ]; then
    echo "skip $dir (missing)"
    continue
  fi
  for img in "$dir"/*.{png,jpg,jpeg,webp}; do
    [ -e "$img" ] || continue
    base="${img%.*}"
    txt="$base.txt"
    if [ -s "$txt" ]; then
      echo "skip $txt (already filled)"
      continue
    fi
    printf '%s, %s, glossy 3D Pixar Illumination cartoon style, no clothing\n' \
      "${TRIGGERS[$char]}" "${DESCS[$char]}" > "$txt"
    echo "wrote $txt"
  done
done

echo
echo "Done. Review the .txt files and add scene context where useful."
