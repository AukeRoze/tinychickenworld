#!/usr/bin/env bash
#
# Builds the combined cast training zip (all 3 characters, JPEG so it stays well
# under GitHub's 100 MB limit) and prints the Replicate training command.
#
#   bash infra/lora-training/prepare-training.sh
#   git add infra/lora-training/zips && git commit -m "cast training zip" && git push
#   # then run the printed train command (PowerShell: replicate-train.ps1)
#
# Each image's caption already carries its own trigger word (pip_chicken /
# mo_chicken / bo_chicken), so one combined LoRA learns all three. Requires
# python3 + pillow + git. The GitHub repo must be PUBLIC (Replicate fetches the
# raw URL unauthenticated).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
cd "$HERE"
mkdir -p zips

echo "=== Building combined cast.zip (JPEG q92) ==="
python3 - <<'PY'
import zipfile, glob, os, io
from PIL import Image
out = "zips/cast.zip"; n = 0
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for c in ("pip", "mo", "bo"):
        for png in sorted(glob.glob(f"dataset/{c}/ref*.png")):
            base = os.path.basename(png)[:-4]
            im = Image.open(png).convert("RGB")
            buf = io.BytesIO(); im.save(buf, "JPEG", quality=92)
            z.writestr(f"{c}_{base}.jpg", buf.getvalue())
            t = png[:-4] + ".txt"
            if os.path.exists(t):
                z.writestr(f"{c}_{base}.txt", open(t, encoding="utf-8").read())
            n += 1
print(f"  zips/cast.zip: {n} images, {os.path.getsize(out)//1024//1024} MB")
PY

remote="$(git -C ../.. config --get remote.origin.url 2>/dev/null || true)"
branch="$(git -C ../.. rev-parse --abbrev-ref HEAD 2>/dev/null || echo main)"
slug=""
case "$remote" in
  git@github.com:*) slug="${remote#git@github.com:}";;
  https://github.com/*) slug="${remote#https://github.com/}";;
esac
slug="${slug%.git}"
if [ -n "$slug" ]; then
  url="https://raw.githubusercontent.com/$slug/$branch/infra/lora-training/zips/cast.zip"
else
  url="https://raw.githubusercontent.com/<owner>/<repo>/<branch>/infra/lora-training/zips/cast.zip"
fi

echo
echo "=== 1) Commit + push (repo must be PUBLIC) ==="
echo "git add infra/lora-training/zips && git commit -m 'cast training zip' && git push"
echo
echo "=== 2) Train (PowerShell) — one combined cast LoRA ==="
echo '$env:MODEL_NAME="tiny-chicken-world-cast-v3"; $env:STEPS="2000"; $env:LORA_RANK="32"'
echo ".\\replicate-train.ps1 $url"
echo
echo "=== 3) Deploy ==="
echo "Set REPLICATE_FLUX_MODEL in .env to the trained version it prints"
echo "(aukeroze/tiny-chicken-world-cast-v3:VERSION), then restart image-service."
