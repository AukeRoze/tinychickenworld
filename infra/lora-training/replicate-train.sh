#!/usr/bin/env bash
# Kicks off a Flux LoRA training on Replicate, polls until done.
#
# Usage:
#   ./replicate-train.sh <PUBLIC_DATASET_ZIP_URL>
#
# Env:
#   REPLICATE_API_TOKEN     required
#   REPLICATE_USERNAME      required, your Replicate username (lowercase)
#   MODEL_NAME              optional, default tiny-chicken-world-cast
#   TRIGGER_WORD            optional, leave empty for multi-character LoRA
#   STEPS                   optional, default 1000

set -euo pipefail

: "${REPLICATE_API_TOKEN:?Set REPLICATE_API_TOKEN}"
: "${REPLICATE_USERNAME:?Set REPLICATE_USERNAME}"

dataset_url="${1:-}"
if [ -z "$dataset_url" ]; then
  echo "Usage: $0 <dataset.zip public URL>" >&2
  exit 1
fi

model_name="${MODEL_NAME:-tiny-chicken-world-cast}"
trigger_word="${TRIGGER_WORD:-}"
steps="${STEPS:-1000}"

TRAINER_OWNER="ostris"
TRAINER_NAME="flux-dev-lora-trainer"

echo "Resolving trainer version..."
trainer_version=$(curl -sS -H "Authorization: Token $REPLICATE_API_TOKEN" \
  "https://api.replicate.com/v1/models/$TRAINER_OWNER/$TRAINER_NAME" \
  | jq -r '.latest_version.id')
echo "  $TRAINER_OWNER/$TRAINER_NAME -> $trainer_version"

dest="$REPLICATE_USERNAME/$model_name"
echo "Ensuring destination model $dest exists..."
create_resp=$(curl -sS -X POST https://api.replicate.com/v1/models \
  -H "Authorization: Token $REPLICATE_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"owner\":\"$REPLICATE_USERNAME\",\"name\":\"$model_name\",\"visibility\":\"private\",\"hardware\":\"gpu-h100\"}")
echo "  $(echo "$create_resp" | jq -c '.detail // .url // .')"

echo "Starting training..."
input_json=$(jq -n \
  --arg url "$dataset_url" \
  --arg t "$trigger_word" \
  --argjson steps "$steps" \
  '{input_images: $url, trigger_word: $t, steps: $steps,
    learning_rate: 0.0004, batch_size: 1, resolution: "1024",
    autocaption: false}')

body=$(jq -n --arg d "$dest" --argjson i "$input_json" \
  '{destination:$d, input:$i}')

# NEW per-version training endpoint
train_url="https://api.replicate.com/v1/models/$TRAINER_OWNER/$TRAINER_NAME/versions/$trainer_version/trainings"

resp=$(curl -sS -X POST "$train_url" \
  -H "Authorization: Token $REPLICATE_API_TOKEN" \
  -H "Content-Type: application/json" \
  -d "$body")

training_id=$(echo "$resp" | jq -r '.id // empty')
if [ -z "$training_id" ]; then
  echo "Training start failed:" >&2
  echo "$resp" | jq -c '.' >&2
  exit 1
fi
echo "Training id: $training_id"
echo "Monitor:     https://replicate.com/p/$training_id"

echo "Polling..."
while true; do
  status_json=$(curl -sS -H "Authorization: Token $REPLICATE_API_TOKEN" \
    "https://api.replicate.com/v1/trainings/$training_id")
  status=$(echo "$status_json" | jq -r '.status')
  echo "  [$status] $(date)"
  case "$status" in
    succeeded)
      echo "Trained version:"
      echo "$status_json" | jq -r '.output.version, .output.weights'
      exit 0
      ;;
    failed|canceled)
      echo "FAILED:" >&2
      echo "$status_json" | jq -c '.error, .logs' >&2
      exit 1
      ;;
  esac
  sleep 30
done
