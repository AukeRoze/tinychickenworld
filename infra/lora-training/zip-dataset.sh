#!/usr/bin/env bash
set -euo pipefail

DATASET="${DATASET:-./dataset}"
OUT="${OUT:-./dataset.zip}"

if [ ! -d "$DATASET" ]; then
  echo "Missing $DATASET" >&2
  exit 1
fi

tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

shopt -s nullglob nocaseglob

for char in pip mo bo; do
  dir="$DATASET/$char"
  [ -d "$dir" ] || continue
  for img in "$dir"/*.{png,jpg,jpeg,webp}; do
    [ -e "$img" ] || continue
    ext="${img##*.}"
    base="$(basename "${img%.*}")"
    cp "$img" "$tmp/$base.$ext"
    src_txt="${img%.*}.txt"
    if [ -f "$src_txt" ]; then
      cp "$src_txt" "$tmp/$base.txt"
    fi
  done
done

count=$(ls "$tmp" 2>/dev/null | grep -ciE '\.(png|jpg|jpeg|webp)$' || true)
echo "Packing $count images..."
(cd "$tmp" && zip -qr - .) > "$OUT"
echo "Wrote $OUT"
