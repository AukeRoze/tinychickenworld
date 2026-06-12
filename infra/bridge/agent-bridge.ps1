# agent-bridge.ps1 - file-based command bridge tussen Claude (Cowork) en de
# lokale pipeline. (ASCII-only: Windows PowerShell 5.1 leest ps1 zonder BOM
# als ANSI en struikelt over em-dashes/pijltjes.)
#
# WAAROM: Claude's sandbox heeft geen netwerktoegang tot localhost, maar wel
# lees/schrijftoegang tot deze projectmap. Claude schrijft commandobestanden,
# dit script voert ze LOKAAL uit en schrijft het antwoord terug.
#
# STARTEN (cmd of PowerShell, in de projectmap):
#     powershell -ExecutionPolicy Bypass -File infra\bridge\agent-bridge.ps1
# Stoppen: Ctrl+C of venster sluiten.
#
# VEILIGHEID:
#  - praat UITSLUITEND met localhost; whitelist:
#       orchestrator = http://localhost:8080   (default)
#       image        = http://localhost:8084   (ref-stills nieuwe castleden)
#  - alleen paden die beginnen met /api/v1/
#  - geen shell-commando's, geen bestandsoperaties; alleen HTTP.
#
# LET OP (port-mapping cleanup 2026-06-12): docker-compose.yml publiceert
# alleen nog 8080 (orchestrator) en 8089 (OAuth-callback) op de host. De
# "image"-whitelist-entry (8084) werkt dus ALLEEN als je de stack tijdelijk
# start met de dev-override die de oude mappings terugzet:
#     docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d
# Zonder die override geeft een image-commando netjes status -1 met een
# verbindingsfout in bridge/results/<id>.json; orchestrator-commando's (de
# default) blijven gewoon werken.
#
# PROTOCOL:
#   bridge/commands/<id>.json :
#       { "method": "GET|POST|PATCH|DELETE", "path": "/api/v1/...",
#         "service": "orchestrator|image" (optioneel), "body": { ... } }
#   resultaat: bridge/results/<id>.json  { status, body, executedAt }
#   verwerkt commando gaat naar bridge/done/

$ErrorActionPreference = "Continue"
$Services = @{
    "orchestrator" = "http://localhost:8080"
    "image"        = "http://localhost:8084"
}
$Root = Join-Path $PSScriptRoot "..\..\bridge"
$CmdDir = Join-Path $Root "commands"
$ResDir = Join-Path $Root "results"
$DoneDir = Join-Path $Root "done"

foreach ($d in @($Root, $CmdDir, $ResDir, $DoneDir)) {
    if (-not (Test-Path $d)) { New-Item -ItemType Directory -Path $d | Out-Null }
}

Write-Host "agent-bridge actief - let op $CmdDir (Ctrl+C om te stoppen)"
Write-Host "Alleen localhost (orchestrator 8080 / image 8084) + /api/v1/* wordt uitgevoerd."
Write-Host "NB: 8084 is standaard niet meer gepubliceerd; image-commando's vereisen de"
Write-Host "    dev-override: docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d"

while ($true) {
    Get-ChildItem -Path $CmdDir -Filter "*.json" -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTime | ForEach-Object {
        $file = $_
        $id = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
        $resPath = Join-Path $ResDir ($id + ".json")
        $svc = "orchestrator"
        try {
            $cmd = Get-Content -Raw -Path $file.FullName | ConvertFrom-Json
            $method = ("" + $cmd.method).ToUpper()
            $path = "" + $cmd.path

            if ($method -notin @("GET", "POST", "PATCH", "DELETE")) {
                throw ("method '" + $method + "' niet toegestaan")
            }
            if (-not $path.StartsWith("/api/v1/")) {
                throw ("alleen /api/v1/* paden toegestaan (was: " + $path + ")")
            }
            $svc = if ($cmd.service) { "" + $cmd.service } else { "orchestrator" }
            if (-not $Services.ContainsKey($svc)) {
                throw ("service '" + $svc + "' niet in whitelist (orchestrator|image)")
            }

            $req = @{
                Uri = $Services[$svc] + $path
                Method = $method
                TimeoutSec = 900
                UseBasicParsing = $true
            }
            if ($null -ne $cmd.body) {
                $req["Body"] = ($cmd.body | ConvertTo-Json -Depth 20)
                $req["ContentType"] = "application/json"
            }

            Write-Host ((Get-Date -Format "HH:mm:ss") + "  " + $svc + " " + $method + " " + $path)
            try {
                $resp = Invoke-WebRequest @req
                $status = [int]$resp.StatusCode
                $bodyText = $resp.Content
            } catch [System.Net.WebException] {
                $r = $_.Exception.Response
                if ($null -ne $r) {
                    $status = [int]$r.StatusCode
                    $reader = New-Object System.IO.StreamReader($r.GetResponseStream())
                    $bodyText = $reader.ReadToEnd()
                } else { throw }
            }

            $parsed = $null
            try { $parsed = $bodyText | ConvertFrom-Json } catch { $parsed = $bodyText }
            @{ status = $status; body = $parsed; executedAt = (Get-Date -Format "o") } |
                ConvertTo-Json -Depth 20 | Set-Content -Path $resPath -Encoding UTF8
        } catch {
            $err = "" + $_.Exception.Message
            if ($svc -eq "image") {
                # 8084 is sinds de port-mapping-cleanup niet meer gepubliceerd;
                # zie de header: start met docker-compose.dev-ports.yml.
                $err += " (hint: image-service:8084 is standaard niet gepubliceerd; " +
                        "start met: docker compose -f docker-compose.yml -f docker-compose.dev-ports.yml up -d)"
            }
            @{ status = -1; error = $err; executedAt = (Get-Date -Format "o") } |
                ConvertTo-Json -Depth 5 | Set-Content -Path $resPath -Encoding UTF8
            Write-Host ("  FOUT: " + $err) -ForegroundColor Red
        } finally {
            Move-Item -Path $file.FullName -Destination (Join-Path $DoneDir $file.Name) -Force
        }
    }
    Start-Sleep -Seconds 2
}
