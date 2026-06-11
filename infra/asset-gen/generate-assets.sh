#!/usr/bin/env bash
#
# Generates the optional brand/FX assets the pipeline picks up automatically.
# Outputs straight into the bible/ folders the code already looks for:
#   bible/fx/bell.png                  — shaking end-card bell
#   bible/sting.mp3                    — opening sonic logo
#   bible/sfx/transitions/whoosh.mp3   — scene-transition whoosh
#   bible/intro.mp4 / bible/outro.mp4  — branded bumpers
#   bible/fx/ambient.webm              — subtle ambient particle loop (best-effort)
#
# These are clean STARTER assets — swap any of them for designed/stock files
# later for a more polished channel.
#
# Requirements: ffmpeg, python3 + pillow (pip install pillow).
# Easiest run (has ffmpeg + DejaVu font + the bible mounted):
#   docker compose cp infra/asset-gen video-assembly-service:/tmp/asset-gen
#   docker compose exec video-assembly-service bash /tmp/asset-gen/generate-assets.sh /bible
# Or locally from the repo root:
#   bash infra/asset-gen/generate-assets.sh ./bible
set -euo pipefail

BIBLE="${1:-./bible}"
HERE="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$BIBLE/fx" "$BIBLE/sfx/transitions" "$BIBLE/music"

# Find a usable bold font for the bumper text (fallbacks for non-Debian hosts).
FONT=""
for f in \
  /usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf \
  /usr/share/fonts/truetype/liberation/LiberationSans-Bold.ttf \
  /Library/Fonts/Arial.ttf \
  /System/Library/Fonts/Supplemental/Arial.ttf ; do
  [ -f "$f" ] && FONT="$f" && break
done

echo "==> bell.png"
if command -v python3 >/dev/null 2>&1 && python3 -c "import PIL" >/dev/null 2>&1; then
  python3 "$HERE/make_bell.py" "$BIBLE/fx/bell.png"
else
  echo "   (skipping bell.png — needs python3 + pillow: 'pip install pillow', or run make_bell.py on your host)"
fi

echo "==> sting.mp3 (cheerful 3-note chime)"
ffmpeg -y -loglevel error \
  -f lavfi -i "sine=frequency=1047:duration=1.6" \
  -f lavfi -i "sine=frequency=1319:duration=1.6" \
  -f lavfi -i "sine=frequency=1568:duration=1.6" \
  -filter_complex "[0]adelay=0|0[a];[1]adelay=180|180[b];[2]adelay=360|360[c];\
[a][b][c]amix=inputs=3:normalize=0,volume=0.35,afade=t=out:st=1.0:d=0.6" \
  -t 1.6 -ac 2 -ar 44100 "$BIBLE/sting.mp3"

echo "==> whoosh.mp3 (transition swoosh)"
ffmpeg -y -loglevel error \
  -f lavfi -i "anoisesrc=d=0.45:c=pink:a=0.6" \
  -af "highpass=f=400,lowpass=f=6000,afade=t=in:st=0:d=0.08,afade=t=out:st=0.18:d=0.27,volume=0.8" \
  -t 0.45 -ac 2 -ar 44100 "$BIBLE/sfx/transitions/whoosh.mp3"

# Bumpers need the logo; skip gracefully if it's missing.
if [ -f "$BIBLE/logo.png" ]; then
  echo "==> intro.mp4"
  ffmpeg -y -loglevel error \
    -f lavfi -i "color=c=0xF4A35E:s=1920x1080:d=3:r=30" \
    -i "$BIBLE/logo.png" -i "$BIBLE/sting.mp3" \
    -filter_complex "[1]scale=1000:-1[logo];[0][logo]overlay=(W-w)/2:(H-h)/2,\
format=yuv420p,fade=t=in:st=0:d=0.4,fade=t=out:st=2.4:d=0.6[v]" \
    -map "[v]" -map 2:a -shortest -c:v libx264 -pix_fmt yuv420p -r 30 -c:a aac "$BIBLE/intro.mp4"

  echo "==> outro.mp4"
  TEXT_FILTER=""
  if [ -n "$FONT" ]; then
    TEXT_FILTER=",drawtext=fontfile=$FONT:text='SUBSCRIBE FOR MORE':fontcolor=white:fontsize=78:x=(w-text_w)/2:y=h*0.74"
  fi
  ffmpeg -y -loglevel error \
    -f lavfi -i "color=c=0xB83A1F:s=1920x1080:d=3:r=30" \
    -i "$BIBLE/logo.png" -i "$BIBLE/sting.mp3" \
    -filter_complex "[1]scale=820:-1[logo];[0][logo]overlay=(W-w)/2:(H-h)/2-110[b];\
[b]format=yuv420p${TEXT_FILTER},fade=t=in:st=0:d=0.4,fade=t=out:st=2.4:d=0.6[v]" \
    -map "[v]" -map 2:a -shortest -c:v libx264 -pix_fmt yuv420p -r 30 -c:a aac "$BIBLE/outro.mp4"
else
  echo "   (skipping intro/outro — bible/logo.png not found)"
fi

echo "==> ambient.mov (subtle transparent glint placeholder)"
# Transparent alpha video is most reliable as ProRes 4444 in a .mov (the code
# checks ambient.mov before ambient.webm). This is a deliberately SUBTLE, dim
# placeholder — for real butterflies/fireflies a designed/stock transparent clip
# looks far better. Swap bible/fx/ambient.mov for stock anytime.
if ffmpeg -y -loglevel error \
    -f lavfi -i "life=s=160x90:r=6:ratio=0.03:mold=60:life_color=0xFFD27A:death_color=black:seed=11" \
    -t 6 -filter_complex "[0]scale=1280:720,boxblur=22:3,split[c][a];\
[a]format=gray,curves=all=0/0 0.1/0 0.5/0.22 1/0.38[m];\
[c][m]alphamerge,format=yuva444p10le[v]" \
    -map "[v]" -c:v prores_ks -profile:v 4444 -pix_fmt yuva444p10le "$BIBLE/fx/ambient.mov" ; then
  echo "   ambient.mov written (subtle — replace with stock for real particles)"
else
  echo "   (ambient.mov skipped — drop a stock transparent clip instead)"
fi

echo ""
echo "Done. Generated assets under: $BIBLE"
echo "Rebuild is NOT needed — the services read these files at runtime."
