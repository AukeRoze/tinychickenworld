# Windows-native PowerShell equivalent of replicate-train.sh.
#
# Usage:
#   $env:REPLICATE_API_TOKEN = "r8_..."
#   $env:REPLICATE_USERNAME  = "yourname"
#   .\replicate-train.ps1 https://your.cdn/dataset.zip
#
# Optional env:
#   MODEL_NAME    default: tiny-chicken-world-cast-v2  (Pixar retrain).
#                 IMPORTANT: use a NEW name to avoid overwriting your v1
#                 model so you can roll back if the retrain disappoints.
#   TRIGGER_WORD  default: empty (we use per-character triggers in captions)
#   STEPS         default: 1500   (was 1000 — stricter character lock)
#   LORA_RANK     default: 32     (was 16 — more capacity for 3 characters)

param(
    [Parameter(Mandatory=$true)]
    [string]$DatasetUrl
)

$ErrorActionPreference = 'Stop'

if (-not $env:REPLICATE_API_TOKEN) { throw "Set REPLICATE_API_TOKEN" }
if (-not $env:REPLICATE_USERNAME)  { throw "Set REPLICATE_USERNAME" }

$modelName   = if ($env:MODEL_NAME)   { $env:MODEL_NAME }   else { 'tiny-chicken-world-cast-v3' }
$triggerWord = if ($env:TRIGGER_WORD) { $env:TRIGGER_WORD } else { '' }
$steps       = if ($env:STEPS)        { [int]$env:STEPS }   else { 2500 }
$loraRank    = if ($env:LORA_RANK)    { [int]$env:LORA_RANK } else { 64 }
# Lower learning rate so character separation is preserved across training
# steps. v2 used 4e-4 default and one character (Mo) absorbed the others.
$learningRate = if ($env:LEARNING_RATE) { [double]$env:LEARNING_RATE } else { 0.0002 }
$trainer     = 'ostris/flux-dev-lora-trainer'
$dest        = "$($env:REPLICATE_USERNAME)/$modelName"

$headers = @{
    Authorization  = "Token $($env:REPLICATE_API_TOKEN)"
    'Content-Type' = 'application/json'
}

Write-Host "Resolving trainer version..."
$trainerInfo = Invoke-RestMethod -Headers $headers -Method Get `
    -Uri "https://api.replicate.com/v1/models/$trainer"
$trainerVersion = $trainerInfo.latest_version.id
Write-Host "  $trainer -> $trainerVersion"

Write-Host "Ensuring destination model $dest exists..."
$createBody = @{
    owner      = $env:REPLICATE_USERNAME
    name       = $modelName
    visibility = 'private'
    hardware   = 'gpu-h100'
} | ConvertTo-Json
try {
    Invoke-RestMethod -Headers $headers -Method Post `
        -Uri 'https://api.replicate.com/v1/models' -Body $createBody | Out-Null
} catch {
    if ($_.Exception.Response.StatusCode -ne 'Conflict' -and
        $_.Exception.Response.StatusCode -ne 422) {
        Write-Warning "Model create returned: $($_.Exception.Message)"
    }
}

Write-Host "Starting training..."
$trainBody = @{
    destination = $dest
    input = @{
        input_images  = $DatasetUrl
        trigger_word  = $triggerWord
        steps         = $steps
        learning_rate = $learningRate
        batch_size    = 1
        resolution    = '1024'
        autocaption   = $false
        lora_rank     = $loraRank
    }
} | ConvertTo-Json -Depth 6

Write-Host ("Training config: model={0}  steps={1}  lora_rank={2}  lr={3}" -f $dest, $steps, $loraRank, $learningRate)

# Per-version training endpoint (the old /v1/trainings 404s for newer models)
$trainUri = "https://api.replicate.com/v1/models/$trainer/versions/$trainerVersion/trainings"
try {
    $resp = Invoke-RestMethod -Headers $headers -Method Post `
        -Uri $trainUri -Body $trainBody
} catch {
    Write-Error "Training start failed: $($_.Exception.Message)"
    if ($_.ErrorDetails.Message) { Write-Error $_.ErrorDetails.Message }
    exit 1
}

$trainingId = $resp.id
if (-not $trainingId) {
    Write-Error "No training id returned. Response: $($resp | ConvertTo-Json -Depth 6)"
    exit 1
}
Write-Host "Training id: $trainingId"
Write-Host "Monitor:     https://replicate.com/p/$trainingId"

Write-Host "Polling..."
while ($true) {
    Start-Sleep -Seconds 30
    $statusJson = Invoke-RestMethod -Headers $headers -Method Get `
        -Uri "https://api.replicate.com/v1/trainings/$trainingId"
    $status = $statusJson.status
    Write-Host ("  [{0}] {1}" -f $status, (Get-Date -Format o))
    switch ($status) {
        'succeeded' {
            Write-Host ''
            Write-Host 'Trained version:'
            Write-Host ("  version: {0}" -f $statusJson.output.version)
            Write-Host ("  weights: {0}" -f $statusJson.output.weights)
            return
        }
        'failed' {
            Write-Error "Training FAILED. logs:"
            Write-Error ($statusJson.logs | Out-String)
            exit 1
        }
        'canceled' {
            Write-Error "Training canceled."
            exit 1
        }
    }
}
