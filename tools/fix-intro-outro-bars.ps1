# ─────────────────────────────────────────────────────────────────────────
# Eenmalige fix: ingebakken zwarte balken uit bible/intro.mp4 en
# bible/outro.mp4 snijden.
#
# Waarom: elke assembly-run detecteert deze balken opnieuw (zie de
# Concatenator-warnings "carries baked-in black bars — stripping") en plakt
# er een extra crop-encode achteraan — verspilde tijd én een generatie
# kwaliteitsverlies per render. Eén keer schoon exporteren lost dat
# permanent op; de cropdetect vindt daarna niets meer en slaat over.
#
# Crop-waarden komen uit de productie-logs van ep 3:
#   intro.mp4 : crop=1888:1080:16:0  (2×16px pillarbox)
#   outro.mp4 : crop=1892:1080:14:0  (2×14px pillarbox)
#
# Gebruik (vanuit de project-root, met de stack draaiend):
#   powershell -ExecutionPolicy Bypass -File tools\fix-intro-outro-bars.ps1
#
# Het script maakt eerst .bak-backups; bij twijfel zet je die gewoon terug.
# ─────────────────────────────────────────────────────────────────────────
$ErrorActionPreference = "Stop"
Set-Location (Join-Path $PSScriptRoot "..")

Write-Host "1/4  Backups maken..." -ForegroundColor Cyan
Copy-Item bible\intro.mp4 bible\intro.mp4.bak -Force
Copy-Item bible\outro.mp4 bible\outro.mp4.bak -Force

Write-Host "2/4  Intro croppen + herschalen (in de assembly-container)..." -ForegroundColor Cyan
docker compose exec -T video-assembly-service ffmpeg -y -i /bible/intro.mp4 `
    -vf "crop=1888:1080:16:0,scale=1920:1080:flags=lanczos,setsar=1" `
    -c:v libx264 -preset slow -crf 16 -pix_fmt yuv420p -c:a copy `
    /workdir/intro_fixed.mp4
if ($LASTEXITCODE -ne 0) { throw "ffmpeg intro-fix faalde" }

Write-Host "3/4  Outro croppen + herschalen..." -ForegroundColor Cyan
docker compose exec -T video-assembly-service ffmpeg -y -i /bible/outro.mp4 `
    -vf "crop=1892:1080:14:0,scale=1920:1080:flags=lanczos,setsar=1" `
    -c:v libx264 -preset slow -crf 16 -pix_fmt yuv420p -c:a copy `
    /workdir/outro_fixed.mp4
if ($LASTEXITCODE -ne 0) { throw "ffmpeg outro-fix faalde" }

Write-Host "4/4  Schone versies terugzetten in bible\..." -ForegroundColor Cyan
# /bible is read-only gemount, dus de output ging naar ./workdir op de host.
Copy-Item workdir\intro_fixed.mp4 bible\intro.mp4 -Force
Copy-Item workdir\outro_fixed.mp4 bible\outro.mp4 -Force
Remove-Item workdir\intro_fixed.mp4, workdir\outro_fixed.mp4 -Force

Write-Host ""
Write-Host "Klaar ✓  intro.mp4 en outro.mp4 zijn schoon (backups: .bak)." -ForegroundColor Green
Write-Host "Verifieer bij de volgende assembly dat de log GEEN" -ForegroundColor Green
Write-Host "'carries baked-in black bars'-warning meer toont." -ForegroundColor Green
