#!/usr/bin/env bash
# generate-foley.sh — vult bible/sfx/foley/ met korte actie-geluiden via de
# ElevenLabs Sound-Effects API (POST /v1/sound-generation). De FoleyMixer in
# voice-service is "dormant-until-asset": zodra deze clips bestaan, mengt hij ze
# automatisch ONDER de dialoog op scènes waarvan de tekst het werkwoord bevat
# (knock, slip, drum, ...). Geen Higgsfield-credits nodig; gebruikt je bestaande
# ElevenLabs-sleutel. Geen rebuild nodig voor de assets zelf — de service leest
# /bible/sfx/foley/ runtime. (De extra verbs 'drum'/'drumroll' vereisen wél de
# voice-service rebuild met de bijgewerkte FoleyMixer.)
#
# GEBRUIK (repo-root; ELEVENLABS_API_KEY moet in de omgeving staan — komt al uit
# je .env):
#   set -a; source .env; set +a
#   bash infra/asset-gen/generate-foley.sh ./bible
#
# Of in de container (heeft curl + de bible gemount):
#   docker compose exec voice-service sh /tmp/asset-gen/generate-foley.sh /bible
#
# Bestaat een clip al, dan wordt hij overgeslagen (herdraaien kost dus niets
# extra). Verwijder een .mp3 om hem opnieuw te laten genereren.

set -euo pipefail

BIBLE="${1:-./bible}"
OUT="$BIBLE/sfx/foley"
API="${ELEVENLABS_BASE_URL:-https://api.elevenlabs.io/v1}/sound-generation"
KEY="${ELEVENLABS_API_KEY:-}"

if [ -z "$KEY" ]; then
  echo "ELEVENLABS_API_KEY ontbreekt. Doe eerst: set -a; source .env; set +a" >&2
  exit 1
fi

mkdir -p "$OUT"

# verb|duration_seconds|prompt  — kort, zacht, cartoon, geïsoleerd (geen muziek).
# prompt_influence hoog (0.6) zodat het effect dicht bij de beschrijving blijft.
ROWS=(
  "knock|1.0|a soft gentle wooden knock, two light taps on an eggshell, cartoon, clean isolated sound effect, no music"
  "tap|0.8|a light soft single finger tap, gentle, cartoon, clean isolated sound effect, no music"
  "slip|1.2|a quick comical slip and slide whoosh, smooth and light, cartoon, clean isolated sound effect, no music"
  "climb|1.0|soft scrabbling climb onto straw, gentle rustle, cartoon, clean isolated sound effect, no music"
  "drum|1.4|a quick playful light drum roll on a soft surface, bouncy, cartoon, clean isolated sound effect, no music"
  "roll|1.0|a small round object gently rolling on wood, soft, cartoon, clean isolated sound effect, no music"
  "bounce|0.9|a soft bouncy boing, light cartoon bounce, clean isolated sound effect, no music"
  "land|0.9|a soft light landing on straw, gentle thud, cartoon, clean isolated sound effect, no music"
  "hop|0.8|a light springy little hop, soft boing, cartoon, clean isolated sound effect, no music"
  "tumble|1.2|a gentle comedic tumble and roll, soft and light, cartoon, clean isolated sound effect, no music"
  "splash|1.0|a small soft water splash, light and playful, cartoon, clean isolated sound effect, no music"
  "peck|0.6|a tiny soft peck tap, light and quick, cartoon, clean isolated sound effect, no music"
  "scratch|1.0|soft light ground scratching, gentle scrape, cartoon, clean isolated sound effect, no music"
  "dig|1.0|soft gentle digging in soil, light scoop, cartoon, clean isolated sound effect, no music"
)

for row in "${ROWS[@]}"; do
  IFS='|' read -r verb dur prompt <<< "$row"
  dest="$OUT/$verb.mp3"
  if [ -f "$dest" ]; then
    echo "skip  $verb (bestaat al)"
    continue
  fi
  echo "maak  $verb (${dur}s)"
  http=$(curl -sS -w '%{http_code}' -o "$dest.tmp" -X POST "$API" \
    -H "xi-api-key: $KEY" \
    -H "Content-Type: application/json" \
    -d "$(printf '{"text":"%s","duration_seconds":%s,"prompt_influence":0.6}' "$prompt" "$dur")")
  if [ "$http" != "200" ]; then
    echo "  FOUT ($http) voor $verb — zie $dest.tmp; overslaan" >&2
    rm -f "$dest.tmp"
    continue
  fi
  mv "$dest.tmp" "$dest"
done

echo "Klaar. Clips staan in $OUT/"
echo "Let op: foley wordt bij de STEM-stap gemengd, dus voor EP3 moet de scene-audio"
echo "ververst worden; nieuwe afleveringen krijgen het automatisch."
