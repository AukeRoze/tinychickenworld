# One-shot frame extraction for AI vision critique.
#
# Usage:
#   .\extract-critique-frames.ps1 -ReferenceUrl "https://youtube.com/watch?v=..." -JobId "<jobId>"
#
# Optional:
#   -FrameCount 10  (default: 8 per source)
#   -OutDir critique-out  (default)
#
# Produces:
#   critique-out\reference_01.jpg ... reference_NN.jpg
#   critique-out\mine_01.jpg ... mine_NN.jpg
#   critique-out\README.txt with the prompt + usage notes

param(
    [Parameter(Mandatory=$true)]  [string]$ReferenceUrl,
    [Parameter(Mandatory=$true)]  [string]$JobId,
    [int]$FrameCount = 8,
    [string]$OutDir = "critique-out"
)

$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$workdir = Join-Path $root "workdir\jobs\$JobId"
$master = Join-Path $workdir "final.mp4"

if (-not (Test-Path $master)) {
    Write-Error "Master not found: $master"
    exit 1
}

$out = Join-Path $root $OutDir
if (Test-Path $out) { Remove-Item $out -Recurse -Force }
New-Item -ItemType Directory -Path $out | Out-Null

Write-Host "[1/3] Downloading reference video..."
$tmpRef = Join-Path $out "_ref.mp4"
yt-dlp -f "best[height<=720]" -o $tmpRef $ReferenceUrl

Write-Host "[2/3] Extracting $FrameCount frames from reference..."
$refDur = [double](ffprobe -v error -show_entries format=duration `
    -of default=noprint_wrappers=1:nokey=1 $tmpRef)
$refInterval = [math]::Max(1, [int]($refDur / $FrameCount))
ffmpeg -y -loglevel error -i $tmpRef `
    -vf "fps=1/$refInterval,scale=1280:-1" `
    -frames:v $FrameCount `
    (Join-Path $out "reference_%02d.jpg")

Write-Host "[3/3] Extracting $FrameCount frames from your video..."
$mineDur = [double](ffprobe -v error -show_entries format=duration `
    -of default=noprint_wrappers=1:nokey=1 $master)
$mineInterval = [math]::Max(1, [int]($mineDur / $FrameCount))
ffmpeg -y -loglevel error -i $master `
    -vf "fps=1/$mineInterval,scale=1280:-1" `
    -frames:v $FrameCount `
    (Join-Path $out "mine_%02d.jpg")

Remove-Item $tmpRef -Force

@"
=== AI-VISION CRITIQUE WORKFLOW ===

Frames extracted to: $out

NEXT STEPS:

1. Open https://claude.ai (recommended — best at multi-image analysis)
   or https://chat.openai.com (GPT-4 with vision).

2. Start a new chat. Drag-drop ALL .jpg files from this folder
   into one message. Order matters — the reference_*.jpg files are
   the target you're aiming for; mine_*.jpg is your output.

3. Paste the prompt below as the user message:

----------------------------------------------------------------
You are a senior creative director for a kids' YouTube channel.

I've uploaded two sets of video frames. Set A (reference_NN.jpg)
is from a successful kids YouTube channel I'd like to learn from.
Set B (mine_NN.jpg) is from my own channel "Tiny Chicken World" —
a watercolor storybook style with three recurring chickens (Pip,
Mo, Bo) for ages 3-6.

Compare A vs B on these 8 dimensions. For each: give A and B a
score from 1-10, identify the specific gap, and give ONE concrete
change I could make to a generative-AI prompt or video pipeline
to close it.

1. Color palette and saturation
2. Composition variety (close-up / medium / wide rotation)
3. Character expression and pose dynamism
4. Background richness and depth
5. Lighting quality (warmth, rim light, contrast)
6. Visual energy (implied motion, framing variety)
7. Brand recognizability (would I know this is the same channel?)
8. Click-worthiness for a 4-year-old browsing thumbnails

End with a prioritized list of the TOP 5 changes I should make,
ranked by impact. Each item should be a single concrete sentence
I can act on tomorrow — not vague advice. Format as a Markdown
table with columns: Priority | Change | Why it matters.
----------------------------------------------------------------

4. Read the response. Pick the top 1-2 actionable items and apply
   them to your pipeline (usually edits to bible/channel.yml or
   the PromptBuilder system prompts). Skip the rest for now.

5. Generate a new test video, re-run this script with the same
   reference, and compare again. Each iteration should close one
   or two dimensions.

TIPS:
- Use the same reference URL across iterations so progress is comparable.
- Pick a reference that's stylistically close to where you want to be —
  comparing watercolor channel to Cocomelon will only tell you you
  need to be Cocomelon. Pick something realistic.
- After 3-4 iterations, the gaps that remain are typically the ones
  that need real-motion (Veo) or real audio (voice/music). Plan
  bigger lifts around those.
"@ | Out-File (Join-Path $out "README.txt") -Encoding utf8

Write-Host ""
Write-Host "Done. $FrameCount reference + $FrameCount mine = $($FrameCount * 2) frames in $out"
Write-Host "Read $out\README.txt for the next steps and prompt template."
