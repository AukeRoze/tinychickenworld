# Windows-native equivalent of zip-dataset.sh. No WSL/bash needed.
# Run from this folder:
#   powershell -ExecutionPolicy Bypass -File .\zip-dataset.ps1

$ErrorActionPreference = 'Stop'

$Dataset = if ($env:DATASET) { $env:DATASET } else { Join-Path $PSScriptRoot 'dataset' }
$Out     = if ($env:OUT)     { $env:OUT }     else { Join-Path $PSScriptRoot 'dataset.zip' }

if (-not (Test-Path $Dataset)) {
    Write-Error "Missing $Dataset"
}

$tmp = New-Item -ItemType Directory -Path (Join-Path $env:TEMP ("lora-stage-" + [guid]::NewGuid()))
try {
    $allowed = @('.png', '.jpg', '.jpeg', '.webp')
    $count = 0
    foreach ($char in @('pip', 'mo', 'bo')) {
        $dir = Join-Path $Dataset $char
        if (-not (Test-Path $dir)) { continue }
        Get-ChildItem -File -Path $dir | Where-Object { $allowed -contains $_.Extension.ToLower() } | ForEach-Object {
            $base = $_.BaseName
            $ext  = $_.Extension
            Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $tmp.FullName ($base + $ext))
            $srcTxt = Join-Path $_.DirectoryName ($base + '.txt')
            if (Test-Path -LiteralPath $srcTxt) {
                Copy-Item -LiteralPath $srcTxt -Destination (Join-Path $tmp.FullName ($base + '.txt'))
            }
            $count++
        }
    }
    Write-Host "Packing $count images..."
    if (Test-Path $Out) { Remove-Item $Out -Force }
    Compress-Archive -Path (Join-Path $tmp.FullName '*') -DestinationPath $Out
    Write-Host "Wrote $Out"
}
finally {
    Remove-Item -Recurse -Force $tmp.FullName
}
