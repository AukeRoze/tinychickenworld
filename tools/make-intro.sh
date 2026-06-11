#!/usr/bin/env bash
# FULLY AUTOMATED one-time intro builder.
#
# Chains the running pipeline services to make the chickens clip, then bakes the
# branded intro — no manual Vertex Studio step:
#   1. image-service  → a character-consistent START STILL (meadow + sign + the
#      three chicks peeking behind it), using the Gemini anchors.
#   2. video-gen      → a Veo clip animating that still (chicks pop up one by
#      one, wave, say hello, show their signature behaviour).
#   3. build-intro.sh → floats the TINY / CHICKEN / WORLD title + SFX over it
#      and writes bible/intro.mp4 (auto-prepended to every video).
#
# Requirements: services running (docker-compose up), VEO configured + working,
# and curl + python3 on the host. Run from anywhere.
#
#   tools/make-intro.sh
#
# Re-run any time you want to refresh the intro (it overwrites bible/intro.mp4).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
IMG_URL="${IMG_URL:-http://localhost:8084}"
VID_URL="${VID_URL:-http://localhost:8087}"
JOB="$(python3 -c 'import uuid;print(uuid.uuid4())')"
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT

# Map a container /workdir path to the host path.
hostpath() { printf '%s' "${1/#\/workdir/$ROOT/workdir}"; }

STILL_DESC="A sunny meadow with soft blue sky and green grass; a low wooden \
signboard stands in the lower centre of the frame. The three little chicks stand \
close together just BEHIND the sign, visible from the chest up, peeking over \
its top edge and smiling warmly at the camera. Keep the TOP THIRD of the frame \
as empty open sky. Bright, wholesome, centred composition."

MOTION_DESC="Hold briefly on the meadow and the wooden sign, then the three \
little cartoon chickens pop up ONE BY ONE from behind the sign, each waving a \
wing and INTRODUCING THEMSELVES BY NAME as they appear. FIRST Pip pops up, tips \
her straw hat, leans in curiously and says brightly: 'Hello! I'm Pip!'. THEN Mo \
rises, gives a calm slow thoughtful blink and a gentle head tilt and says \
warmly: 'Hi, I'm Mo.'. THEN Bo grins, nudges his round glasses up with a \
wing-tip and says with a giggle: 'And I'm Bo!'. Clear, cheerful, simple spoken \
English; each name said slowly and distinctly so it is easy to follow. \
Warm, playful, high energy. Keep the top third of the frame empty."

echo "▶ 1/3  image-service: intro start still (job $JOB)…"
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

echo "▶ 2/3  video-gen: Veo chickens clip (this can take a few minutes)…"
python3 - "$JOB" "$STILL" "$MOTION_DESC" > "$TMP/clip_req.json" <<'PY'
import json,sys
job,still,desc=sys.argv[1],sys.argv[2],sys.argv[3]
print(json.dumps({"jobId":job,"format":"landscape","scenes":[
  {"seq":1,"sceneType":"intro","startImagePath":still,
   "visualDesc":desc,"durationSeconds":8}]}))
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

echo "▶ 3/3  build-intro.sh: compositing title + SFX → bible/intro.mp4…"
bash "$ROOT/tools/build-intro.sh" "$ROOT/bible/intro.mp4" "$CLIP"

echo "✅ Done. bible/intro.mp4 refreshed — it's auto-prepended to every video."
