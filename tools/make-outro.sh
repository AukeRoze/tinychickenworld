#!/usr/bin/env bash
# FULLY AUTOMATED one-time OUTRO builder (mirrors tools/make-intro.sh).
#
# Replaces the old static logo + "SUBSCRIBE FOR MORE" card with a LIVING outro:
# the three chicks wave goodbye ONE BY ONE, then a branded "SUBSCRIBE FOR MORE"
# call-to-action floats in over them. One-time render → reused on every video,
# so there is no per-video cost.
#
#   1. image-service → a character-consistent END STILL (the three chicks in the
#      meadow, facing camera, lifting a wing to wave; lower third kept clear for
#      the CTA), using the Gemini anchors.
#   2. video-gen     → a Veo clip animating that still: Pip waves and says
#      "Bye bye!", then Mo a calm "See you soon!", then Bo a silly "Byeee!",
#      and all three wave together at the end.
#   3. composite     → floats an animated "SUBSCRIBE FOR MORE" CTA + logo + SFX
#      over the clip and writes bible/outro.mp4 (auto-appended to every video).
#
# Requirements: services running (docker-compose up), VEO configured + working,
# ffmpeg + curl + python3 on the host. Run from anywhere.
#
#   tools/make-outro.sh
#
# Re-run any time you want to refresh the outro (it overwrites bible/outro.mp4).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG_URL="${IMG_URL:-http://localhost:8084}"
VID_URL="${VID_URL:-http://localhost:8087}"
SFXDIR="$ROOT/bible/sfx/intro"
FONT="${FONT:-/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf}"
OUT="${OUT:-$ROOT/bible/outro.mp4}"
W=1920; H=1080; FPS=30; DUR=6.0
JOB="$(python3 -c 'import uuid;print(uuid.uuid4())')"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# Map a container /workdir path to the host path.
hostpath() { printf '%s' "${1/#\/workdir/$ROOT/workdir}"; }

STILL_DESC="A sunny golden-hour meadow with soft blue sky and warm green grass. \
The three little chicks stand close together, centred, facing the camera and \
smiling warmly, each lifting one wing as if about to wave goodbye. Keep the \
LOWER THIRD of the frame as open grass with nothing in it (room for a caption). \
Bright, wholesome, cosy, centred composition."

MOTION_DESC="The three little cartoon chickens wave goodbye to the viewer ONE BY ONE, each \
saying a cheerful farewell as they wave, then all three wave together at the \
end. FIRST Pip waves her wing energetically, tips her straw hat and says \
brightly: 'Bye bye!'. THEN Mo gives a calm, gentle wave and a warm slow blink \
and says: 'See you soon!'. THEN Bo waves both wings in a silly wobble, nudges \
his round glasses and giggles: 'Byeee!'. Finally all three wave together and \
beam at the camera. Lively, bouncy, high-energy and warm. Keep the lower third \
of the frame clear."

echo "▶ 1/3  image-service: outro end still (job $JOB)…"
python3 - "$JOB" "$STILL_DESC" > "$TMP/img_req.json" <<'PY'
import json,sys
job,desc=sys.argv[1],sys.argv[2]
print(json.dumps({"jobId":job,"format":"landscape","scenes":[
  {"seq":1,"visualDesc":desc,"characters":["pip","mo","bo"]}]}))
PY
curl -fsS --max-time 180 -H 'Content-Type: application/json' \
  -d @"$TMP/img_req.json" "$IMG_URL/api/v1/images/generate" > "$TMP/img_resp.json"
STILL="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["scenes"][0]["imagePath"])' "$TMP/img_resp.json")"
echo "   still: $STILL"

echo "▶ 2/3  video-gen: Veo chickens-waving clip (this can take a few minutes)…"
python3 - "$JOB" "$STILL" "$MOTION_DESC" > "$TMP/clip_req.json" <<'PY'
import json,sys
job,still,desc=sys.argv[1],sys.argv[2],sys.argv[3]
print(json.dumps({"jobId":job,"format":"landscape","scenes":[
  {"seq":1,"sceneType":"outro","startImagePath":still,
   "visualDesc":desc,"durationSeconds":6}]}))
PY
curl -fsS --max-time 900 -H 'Content-Type: application/json' \
  -d @"$TMP/clip_req.json" "$VID_URL/api/v1/clips/generate" > "$TMP/clip_resp.json"

python3 - "$TMP/clip_resp.json" <<'PY'
import json,sys
r=json.load(open(sys.argv[1]));c=r["clips"][0]
print("   status:",c.get("status"),"model:",c.get("model"),"cost €:",r.get("totalCostEur"))
if c.get("status")!="OK" or not c.get("clipPath"):
    sys.exit("✗ Veo clip not OK ("+str(c.get('status'))+": "+str(c.get('reason'))+"). Check VEO config/quota.")
PY
CLIP_C="$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["clips"][0]["clipPath"])' "$TMP/clip_resp.json")"
CLIP="$(hostpath "$CLIP_C")"
echo "   clip: $CLIP"

echo "▶ 3/3  composite: SUBSCRIBE CTA + logo + SFX → bible/outro.mp4…"

# Does the Veo clip carry its own audio (chick "bye bye"s)?
CLIP_HAS_AUDIO="$(ffprobe -v error -select_streams a -show_entries stream=index -of csv=p=0 "$CLIP" | head -1 || true)"

LOGO="$ROOT/bible/logo.png"
HAVE_LOGO=""; [ -f "$LOGO" ] && HAVE_LOGO=1

# Inputs: 0 = Veo clip, 1 = sting, (2 = sparkle), (3 = logo)
VIN=( -i "$CLIP" -i "$SFXDIR/title_sparkle.mp3" )
SPARKLE_IDX=1
if [ -n "$HAVE_LOGO" ]; then VIN+=( -loop 1 -i "$LOGO" ); LOGO_IDX=2; fi

# Bouncy, outlined CTA text styling (matches the intro look).
C="fontfile=${FONT}:borderw=10:bordercolor=0xFFFFFF:shadowcolor=0x3A2A1E@0.5:shadowx=6:shadowy=6"

{
  # Fit the clip to 1920x1080.
  echo "[0:v]scale=${W}:${H}:force_original_aspect_ratio=increase,crop=${W}:${H},setsar=1[v0];"

  prev="v0"
  if [ -n "$HAVE_LOGO" ]; then
    # Small logo fades in bottom-right (fade on the logo stream — overlay has
    # no alpha-expression option).
    echo "[${LOGO_IDX}:v]scale=300:-1,format=rgba,fade=t=in:st=2.6:d=0.4:alpha=1[logo];"
    echo "[${prev}][logo]overlay=x=W-w-60:y=H-h-60[vl];"
    prev="vl"
  fi

  # "SUBSCRIBE FOR MORE!" bounces up from the lower third around t=2.6s.
  echo "[${prev}]drawtext=${C}:fontcolor=0xFF5A4D:text='SUBSCRIBE FOR MORE!':fontsize=120:x='(w-text_w)/2':y='820-max(0,(1-(t-2.6)/0.3))*120':alpha='min(1,max(0,(t-2.6)/0.25))':enable='gte(t,2.6)'[vtxt];"
  # Soft fade in at the very start + fade out at the end.
  echo "[vtxt]format=yuv420p,fade=t=in:st=0:d=0.3,fade=t=out:st=$(awk "BEGIN{print ${DUR}-0.5}"):d=0.5[vout];"

  # Audio: sting + sparkle (delayed to the CTA) + the clip's own chick farewells.
  echo "[${SPARKLE_IDX}:a]adelay=2600|2600,volume=0.7[spark];"
  if [ -n "$CLIP_HAS_AUDIO" ]; then
    echo "[0:a]volume=1.0[ca];"
    echo "[ca][spark]amix=inputs=2:normalize=0:dropout_transition=0[aout]"
  else
    echo "[spark]anull[aout]"
  fi
} > "$TMP/fg"

ffmpeg -y -loglevel error "${VIN[@]}" \
  -filter_complex_script "$TMP/fg" \
  -map "[vout]" -map "[aout]" -t "$DUR" -r "$FPS" \
  -c:v libx264 -pix_fmt yuv420p -c:a aac -b:a 192k "$OUT"

echo "✅ Done. bible/outro.mp4 refreshed — it's auto-appended to every video."
