# commit-cast-recovery.ps1
# Doel: de openstaande werkkopie netjes committen.
# De Cowork-sandbox mag niet in .git schrijven, daarom draai jij dit lokaal.
# Gebruik: rechtsklik > "Run with PowerShell", of in een terminal:  .\commit-cast-recovery.ps1

$ErrorActionPreference = "Stop"
$repo = "D:\claude\youtube-channel"
Set-Location $repo

Write-Host "1. Stale git-lock opruimen..." -ForegroundColor Cyan
Get-Process git -ErrorAction SilentlyContinue | Stop-Process -Force -ErrorAction SilentlyContinue
Remove-Item "$repo\.git\index.lock" -Force -ErrorAction SilentlyContinue
if (Test-Path "$repo\.git\index.lock") {
    Write-Host "   LET OP: lock nog aanwezig - sluit GitHub Desktop/IDE-git en draai opnieuw." -ForegroundColor Red
    exit 1
}

Write-Host "2. Staging leegmaken (er stonden ~1136 bestanden van een afgebroken add)..." -ForegroundColor Cyan
git reset -q

Write-Host "3. Alleen de relevante wijzigingen stagen..." -ForegroundColor Cyan
git add `
  "services/orchestrator/src/main/java/com/youtubeauto/orchestrator/service/VeoPromptCompiler.java" `
  "services/orchestrator/src/test/java/com/youtubeauto/orchestrator/service/VeoPromptCompilerCastRecoveryTest.java" `
  "services/orchestrator/src/main/resources/static/assets/js/job-page.js" `
  "bible/channel.yml" `
  "REDEPLOY-CHECKLIST.md" `
  "analyse/BACKLOG-character-consistency.md" `
  "analyse/FLOW-prompts-EP3-ready.md" `
  "bible/afleveringen/1/overgangsschema.md"

Write-Host "   Ge-staged:" -ForegroundColor DarkGray
git diff --cached --name-only

Write-Host "4. Committen..." -ForegroundColor Cyan
git commit `
  -m "fix(orchestrator): cast-recovery voor sprekende/zichtbare gast (eendje)" `
  -m "- VeoPromptCompiler.augmentPresentCast(): spreker uit lines + niet-kip in actietekst wordt altijd in cast-lock/roster opgenomen (EP3 sc.19/21/24)
- VeoPromptCompilerCastRecoveryTest: 6 cases incl. woordgrens 'duck under' != duckling
- bible/channel.yml: character-canon (Mo wattles + always-worn, Pip pure-white comb/geen wattles, Bo geen comb/wattles)
- job-page.js: GoogleFlow Omni als default re-roll model (Flow-workflow)
- docs: REDEPLOY-CHECKLIST + EP3 prompt/overgangs-notities"

Write-Host "`n5. Resultaat:" -ForegroundColor Cyan
git log -1 --stat

Write-Host "`nKlaar. De bridge/results-logs en regeleinde-ruis zijn bewust NIET meegecommit." -ForegroundColor Green
Write-Host "Tip: voeg 'bridge/results/' aan .gitignore toe om die churn te stoppen." -ForegroundColor Green
