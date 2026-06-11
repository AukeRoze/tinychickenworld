@echo off
REM ─────────────────────────────────────────────────────────────────────
REM Golden test set — full-pipeline regressiebench.
REM Rendert de 2 vaste golden episodes (pilot + hatching) en vergelijkt
REM ALLE kwaliteitsmetrieken met de baseline. Draai dit na elke
REM pipeline-wijziging. Eerste keer (of na een bewuste verbetering):
REM   golden-test.bat --save-baseline
REM Veo meenemen in de golden run (duurder):
REM   golden-test.bat --motion veo
REM ─────────────────────────────────────────────────────────────────────
setlocal
cd /d "%~dp0"
python infra\eval\golden-test.py %*
endlocal
