# generate-foley.ps1 - vult bible\sfx\foley\ met korte actie-geluiden via de
# ElevenLabs Sound-Effects API. Windows-PowerShell variant van generate-foley.sh
# (cmd kan de bash-versie niet draaien). Geen Higgsfield-credits; gebruikt je
# bestaande ElevenLabs-sleutel uit .env.
#
# STARTEN (PowerShell of cmd, in de projectmap):
#     powershell -ExecutionPolicy Bypass -File infra\asset-gen\generate-foley.ps1
#
# Bestaande clips worden overgeslagen (herdraaien kost niets). Verwijder een
# .mp3 om hem opnieuw te genereren.

$ErrorActionPreference = "Stop"
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12

# Repo-root = twee mappen boven dit script (infra\asset-gen\..\..).
$root    = Split-Path -Parent (Split-Path -Parent $PSScriptRoot)
$bible   = Join-Path $root "bible"
$out     = Join-Path $bible "sfx\foley"
$envFile = Join-Path $root ".env"
$api     = "https://api.elevenlabs.io/v1/sound-generation"

# Sleutel uit omgeving, anders uit .env.
$key = $env:ELEVENLABS_API_KEY
if (-not $key -and (Test-Path $envFile)) {
    foreach ($line in Get-Content $envFile) {
        if ($line -match '^\s*ELEVENLABS_API_KEY\s*=\s*(.+?)\s*$') { $key = $matches[1]; break }
    }
}
if (-not $key) { Write-Error "ELEVENLABS_API_KEY niet gevonden (omgeving of .env)"; exit 1 }

New-Item -ItemType Directory -Force -Path $out | Out-Null

# verb / duur(s) / prompt - kort, zacht, cartoon, geisoleerd (geen muziek).
$rows = @(
  @{ verb="knock";   dur=1.0; prompt="a soft gentle wooden knock, two light taps on an eggshell, cartoon, clean isolated sound effect, no music" },
  @{ verb="tap";     dur=0.8; prompt="a light soft single finger tap, gentle, cartoon, clean isolated sound effect, no music" },
  @{ verb="slip";    dur=1.2; prompt="a quick comical slip and slide whoosh, smooth and light, cartoon, clean isolated sound effect, no music" },
  @{ verb="climb";   dur=1.0; prompt="soft scrabbling climb onto straw, gentle rustle, cartoon, clean isolated sound effect, no music" },
  @{ verb="drum";    dur=1.4; prompt="a quick playful light drum roll on a soft surface, bouncy, cartoon, clean isolated sound effect, no music" },
  @{ verb="roll";    dur=1.0; prompt="a small round object gently rolling on wood, soft, cartoon, clean isolated sound effect, no music" },
  @{ verb="bounce";  dur=0.9; prompt="a soft bouncy boing, light cartoon bounce, clean isolated sound effect, no music" },
  @{ verb="land";    dur=0.9; prompt="a soft light landing on straw, gentle thud, cartoon, clean isolated sound effect, no music" },
  @{ verb="hop";     dur=0.8; prompt="a light springy little hop, soft boing, cartoon, clean isolated sound effect, no music" },
  @{ verb="tumble";  dur=1.2; prompt="a gentle comedic tumble and roll, soft and light, cartoon, clean isolated sound effect, no music" },
  @{ verb="splash";  dur=1.0; prompt="a small soft water splash, light and playful, cartoon, clean isolated sound effect, no music" },
  @{ verb="peck";    dur=0.6; prompt="a tiny soft peck tap, light and quick, cartoon, clean isolated sound effect, no music" },
  @{ verb="scratch"; dur=1.0; prompt="soft light ground scratching, gentle scrape, cartoon, clean isolated sound effect, no music" },
  @{ verb="dig";     dur=1.0; prompt="soft gentle digging in soil, light scoop, cartoon, clean isolated sound effect, no music" }
)

foreach ($r in $rows) {
    $dest = Join-Path $out ($r.verb + ".mp3")
    if (Test-Path $dest) { Write-Host "skip  $($r.verb) (bestaat al)"; continue }
    Write-Host "maak  $($r.verb) ($($r.dur)s)"
    $body = @{ text = $r.prompt; duration_seconds = $r.dur; prompt_influence = 0.6 } | ConvertTo-Json -Compress
    try {
        Invoke-WebRequest -Uri $api -Method Post `
            -Headers @{ "xi-api-key" = $key } `
            -ContentType "application/json" -Body $body -OutFile $dest | Out-Null
    } catch {
        Write-Warning "FOUT voor $($r.verb): $($_.Exception.Message)"
        if (Test-Path $dest) { Remove-Item $dest -Force }
    }
}

Write-Host ""
Write-Host "Klaar. Clips staan in $out"
Write-Host "Let op: foley wordt bij de STEM-stap gemengd; voor EP3 is een audio-refresh nodig,"
Write-Host "nieuwe afleveringen krijgen het automatisch."
