@echo off
REM ─────────────────────────────────────────────────────────────────────
REM Fills the audio library in one run (resume-safe, skips existing files):
REM   1. bible/sfx/ambient/  — 9 ambient loops per locatie  (~270 credits)
REM   2. bible/music/        — 9 muziektracks, 3 per mood   (6 min music)
REM Key wordt uit .env gelezen (ELEVENLABS_API_KEY).
REM ─────────────────────────────────────────────────────────────────────
setlocal
cd /d "%~dp0"

for /f "tokens=1,* delims==" %%a in ('findstr /b "ELEVENLABS_API_KEY=" .env') do set "ELEVENLABS_API_KEY=%%b"
if "%ELEVENLABS_API_KEY%"=="" (
    echo FOUT: ELEVENLABS_API_KEY niet gevonden in .env
    exit /b 1
)

pip install --quiet elevenlabs requests

echo.
echo ── Ambient loops per locatie ──────────────────────────────────────
python infra\sfx-generator\generate-ambient.py
if errorlevel 1 echo (ambient had fouten — script is resume-safe, draai opnieuw)

echo.
echo ── Muziektracks (3 per mood) + registratie in channel.yml ─────────
python infra\sfx-generator\generate-music.py --register
if errorlevel 1 echo (music had fouten — script is resume-safe, draai opnieuw)

echo.
echo Klaar. Nieuwe jobs pakken de tracks en ambients direct op (live gelezen).
endlocal
