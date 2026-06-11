#!/usr/bin/env bash
# Build the ONE-TIME branded intro (reused on every video → no per-video cost).
#
#   Title animation (FFmpeg, free):
#     - "TINY"    slides in from the left   (whoosh)
#     - "CHICKEN" slides in from the right  (whoosh)
#     - "WORLD"   drops in letter-by-letter (ding per letter)
#     - a wooden SIGN rises from the bottom (thunk) + sparkle when complete
#
#   Chickens: supply a ONE-TIME Veo clip (2nd arg) of the full scene — sky, a
#   wooden sign lower-centre, the three chicks popping up one by one from behind
#   it, waving, saying hello, then a quick signature behaviour. Leave the TOP
#   THIRD of that clip empty for the title. This script floats the animated
#   title + sound effects over that clip.
#
# Usage:
#   tools/build-intro.sh [outfile] [chicken_clip.mp4]
#     no clip -> renders a title-only PREVIEW (approve look/timing)
#     w/ clip -> renders the FINAL intro (title over your Veo scene)
#
# Needs ffmpeg. Run from anywhere; paths resolve off the repo root.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SFXDIR="$ROOT/bible/sfx/intro"
FONT="${FONT:-/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}"
OUT="${1:-$ROOT/bible/intro.mp4}"
CLIP="${2:-}"
W=1920; H=1080; FPS=30
SIGN="$SFXDIR/_sign.png"
FG="$(mktemp)"; trap 'rm -f "$FG"' EXIT

# Wooden sign board (tan panel + dark border) — used by the preview only.
ffmpeg -y -loglevel error -f lavfi -i "color=c=0xC9A06A:s=1000x300" \
  -vf "drawbox=x=0:y=0:w=1000:h=300:color=0x5A3A1E:t=22" -frames:v 1 "$SIGN"

if [ -n "$CLIP" ]; then DUR=8.0; else DUR=5.0; fi

# ── Video inputs ──────────────────────────────────────────────────────────
VIN=(); BASE_A=0
if [ -n "$CLIP" ]; then
  VIN=( -i "$CLIP" ); BASE_A=1                      # input 0 = clip
else
  VIN=( -f lavfi -i "color=c=0xAEE6F7:s=${W}x${H}:r=${FPS}:d=${DUR}" -loop 1 -i "$SIGN" )
  BASE_A=2                                          # inputs 0=bg, 1=sign
fi

# ── Audio SFX list: file:delay_ms:volume ─────────────────────────────────
SFX=( "sign_thunk.mp3:300:0.9" "word_whoosh.mp3:600:0.9" "word_whoosh.mp3:1000:0.9" \
      "letter_ding.mp3:1700:0.8" "letter_ding.mp3:1880:0.8" "letter_ding.mp3:2060:0.8" \
      "letter_ding.mp3:2240:0.8" "letter_ding.mp3:2420:0.8" "title_sparkle.mp3:2700:0.7" )
if [ -n "$CLIP" ]; then
  SFX+=( "chick_pop.mp3:3000:0.7" "chick_pop.mp3:3700:0.7" "chick_pop.mp3:4400:0.7" )
fi

AIN=(); for e in "${SFX[@]}"; do AIN+=( -i "$SFXDIR/${e%%:*}" ); done

# Include the Veo clip's own audio (chicken hellos / ambient) if it has any.
CLIP_HAS_AUDIO=""
if [ -n "$CLIP" ]; then
  CLIP_HAS_AUDIO="$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$CLIP" | head -1 || true)"
fi

# ── Filtergraph ───────────────────────────────────────────────────────────
{
  # Base picture → [v0]
  if [ -n "$CLIP" ]; then
    echo "[0:v]scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},setsar=1[v0];"
  else
    echo "[0:v]drawbox=x=0:y=900:w=${W}:h=180:color=0x86C36A:t=fill,format=rgba[bg];"
    echo "[1:v]scale=1000:-1[sign];"
    echo "[bg][sign]overlay=x=(W-1000)/2:y='if(lt(t,0.3),${H},max(720,${H}-(t-0.3)/0.4*360))'[v0];"
  fi

  # Playful & colourful: warm per-word colours, thick white outline + soft shadow.
  C="fontfile=${FONT}:borderw=10:bordercolor=0xFFFFFF:shadowcolor=0x3A2A1E@0.5:shadowx=6:shadowy=6"
  echo "[v0]drawtext=${C}:fontcolor=0xFFC21F:text='TINY':fontsize=160:y=160:x='min((w-text_w)/2,-text_w+(t-0.6)/0.4*((w-text_w)/2+text_w))'[v1];"
  echo "[v1]drawtext=${C}:fontcolor=0xFF7A1A:text='CHICKEN':fontsize=160:y=330:x='w-min(1,max(0,(t-1.0)/0.4))*((w+text_w)/2)'[v2];"
  prev=v2; i=0
  for pair in "W:1.70:620:0xFF5A4D" "O:1.88:790:0xFFB02E" "R:2.06:960:0xFFC21F" "L:2.24:1130:0xFF7A1A" "D:2.42:1300:0xE8472F"; do
    ch=${pair%%:*}; r=${pair#*:}; tt=${r%%:*}; r2=${r#*:}; cx=${r2%%:*}; col=${r2#*:}; nxt="lw$i"
    echo "[$prev]drawtext=${C}:fontcolor=${col}:text='${ch}':fontsize=190:x='${cx}-text_w/2':y='540-max(0,(1-(t-${tt})/0.25))*170':alpha='if(lt(t,${tt}),0,min(1,(t-${tt})/0.2))':enable='gte(t,${tt})'[$nxt];"
    prev=$nxt; i=$((i+1))
  done
  echo "[$prev]null[vout];"

  # Audio: delay+gain each SFX, then mix (plus clip audio if present).
  labels=""; idx=$BASE_A; n=0
  for e in "${SFX[@]}"; do
    d=${e#*:}; ms=${d%%:*}; vol=${d##*:}
    echo "[${idx}:a]adelay=${ms}|${ms},volume=${vol}[as${n}];"
    labels="${labels}[as${n}]"; idx=$((idx+1)); n=$((n+1))
  done
  mixn=$n
  if [ -n "$CLIP_HAS_AUDIO" ]; then
    echo "[0:a]volume=1.0[ca];"; labels="${labels}[ca]"; mixn=$((mixn+1))
  fi
  echo "${labels}amix=inputs=${mixn}:normalize=0:dropout_transition=0[aout]"
} > "$FG"

ffmpeg -y -loglevel error "${VIN[@]}" "${AIN[@]}" \
  -filter_complex_script "$FG" \
  -map "[vout]" -map "[aout]" -t "$DUR" -r "$FPS" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 192k "$OUT"

echo "Wrote $OUT"
