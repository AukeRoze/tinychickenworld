@echo off
REM Frame extraction for AI vision critique (CMD version).
REM
REM Usage:
REM   extract-critique-frames.bat <REFERENCE_URL> <JOB_ID> [FRAME_COUNT]
REM
REM Example:
REM   extract-critique-frames.bat https://youtu.be/zGn6PwRkD7c abc-123-def 8
REM
REM Produces: critique-out\reference_NN.jpg + mine_NN.jpg + README.txt

setlocal enabledelayedexpansion

set "REF_URL=%~1"
set "JOB_ID=%~2"
set "FRAME_COUNT=%~3"
if "%FRAME_COUNT%"=="" set "FRAME_COUNT=8"

if "%REF_URL%"=="" goto :usage
if "%JOB_ID%"=="" goto :usage

set "ROOT=%~dp0.."
pushd "%ROOT%"

set "MASTER=%ROOT%\workdir\jobs\%JOB_ID%\final.mp4"
if not exist "%MASTER%" (
    echo Master not found: %MASTER%
    exit /b 1
)

set "OUT=%ROOT%\critique-out"
if exist "%OUT%" rmdir /s /q "%OUT%"
mkdir "%OUT%"

echo [1/3] Downloading reference video...
set "TMP_REF=%OUT%\_ref.mp4"
yt-dlp -f "best[height<=720]" -o "%TMP_REF%" "%REF_URL%"
if errorlevel 1 (
    echo yt-dlp failed
    exit /b 1
)

echo [2/3] Extracting %FRAME_COUNT% frames from reference...
for /f "delims=" %%d in ('ffprobe -v error -show_entries format^=duration -of default^=noprint_wrappers^=1:nokey^=1 "%TMP_REF%"') do set "REF_DUR=%%d"
for /f %%i in ('powershell -nologo -command "[math]::Max(1, [int]([double]'%REF_DUR%' / %FRAME_COUNT%))"') do set "REF_INT=%%i"

ffmpeg -y -loglevel error -i "%TMP_REF%" -vf "fps=1/%REF_INT%,scale=1280:-1" -frames:v %FRAME_COUNT% "%OUT%\reference_%%02d.jpg"

echo [3/3] Extracting %FRAME_COUNT% frames from your video...
for /f "delims=" %%d in ('ffprobe -v error -show_entries format^=duration -of default^=noprint_wrappers^=1:nokey^=1 "%MASTER%"') do set "MINE_DUR=%%d"
for /f %%i in ('powershell -nologo -command "[math]::Max(1, [int]([double]'%MINE_DUR%' / %FRAME_COUNT%))"') do set "MINE_INT=%%i"

ffmpeg -y -loglevel error -i "%MASTER%" -vf "fps=1/%MINE_INT%,scale=1280:-1" -frames:v %FRAME_COUNT% "%OUT%\mine_%%02d.jpg"

del /q "%TMP_REF%"

(
echo === AI-VISION CRITIQUE WORKFLOW ===
echo.
echo Frames extracted to: %OUT%
echo.
echo NEXT STEPS:
echo.
echo 1. Open https://claude.ai (recommended) or https://chat.openai.com
echo.
echo 2. Drag-drop ALL .jpg files from %OUT% into one Claude message.
echo.
echo 3. Paste this prompt:
echo.
echo ----------------------------------------------------------------
echo You are a senior creative director for a kids' YouTube channel.
echo.
echo I have uploaded two sets of frames. Set A ^(reference_NN.jpg^) is
echo from a successful kids YouTube channel. Set B ^(mine_NN.jpg^) is
echo from my channel "Tiny Chicken World" - a watercolor storybook
echo style with three recurring chickens ^(Pip, Mo, Bo^) for ages 3-6.
echo.
echo Compare A vs B on these 8 dimensions. For each: score A and B
echo from 1-10, identify the gap, and give ONE concrete change I can
echo make to a generative-AI prompt or video pipeline.
echo.
echo 1. Color palette and saturation
echo 2. Composition variety ^(close-up / medium / wide rotation^)
echo 3. Character expression and pose dynamism
echo 4. Background richness and depth
echo 5. Lighting quality ^(warmth, rim light, contrast^)
echo 6. Visual energy ^(implied motion, framing variety^)
echo 7. Brand recognizability
echo 8. Click-worthiness for a 4-year-old
echo.
echo End with a prioritized table of the TOP 5 changes I should make,
echo ranked by impact: Priority ^| Change ^| Why it matters.
echo ----------------------------------------------------------------
echo.
echo 4. Apply the top 1-2 items to your pipeline. Generate a new test
echo    video. Re-run with the same reference URL to compare progress.
) > "%OUT%\README.txt"

echo.
echo Done. Frames + prompt in %OUT%
popd
exit /b 0

:usage
echo Usage: %~nx0 ^<REFERENCE_URL^> ^<JOB_ID^> [FRAME_COUNT]
echo Example: %~nx0 https://youtu.be/zGn6PwRkD7c abc-123-def 8
exit /b 1
