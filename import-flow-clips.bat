@echo off
REM ===========================================================================
REM  Importeert je in Google Flow gemaakte scene-clips in een bestaande job en
REM  hermonteert (stemmen + muziek + intro/outro over je Flow-beelden).
REM
REM  Gebruik:   import-flow-clips.bat <JOB-ID> [episode]
REM  Voorbeeld: import-flow-clips.bat 3f8c1a90-... 1
REM
REM  Vereist: de pipeline draait (docker compose up) en de job heeft script +
REM  stemmen + scene-images. De clips staan in bible\afleveringen\<episode>\
REM  als scene-1.mp4 .. scene-29.mp4, OF met beschrijvende naam
REM  scene-1-<titel>.mp4 (titel = scene-goal-slug; de import matcht op het
REM  "scene-<nummer>-" voorvoegsel, dus het achtervoegsel is vrij).
REM ===========================================================================
setlocal
set "BASE=http://localhost:8080"
set "JOBID=%~1"
set "EP=%~2"
REM Dubbelklik-vriendelijk: vraag de Job-ID als die niet is meegegeven.
if "%JOBID%"=="" set /p "JOBID=Job-ID (staat in de dashboard-URL): "
if "%JOBID%"=="" (
  echo Geen Job-ID opgegeven. Afgebroken.
  pause
  exit /b 1
)
if "%EP%"=="" set /p "EP=Aflevering [1]: "
if "%EP%"=="" set "EP=1"

echo.
echo [1/2] Clips importeren voor job %JOBID% (episode %EP%) ...
curl -s -X POST "%BASE%/api/v1/videos/%JOBID%/import-clips?episode=%EP%"
echo.
echo.
echo [2/2] Hermonteren (stemmen + muziek over je Flow-clips) ...
curl -s -X POST "%BASE%/api/v1/videos/%JOBID%/reassemble"
echo.
echo.
echo Klaar. Volg de voortgang op het dashboard: %BASE%
endlocal
