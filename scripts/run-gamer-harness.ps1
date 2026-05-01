param(
    [int]$MaxMinutes = 360,
    [int]$StallSeconds = 300,
    [string]$WorldName = "",
    [string]$Seed = "",
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$runDir = Join-Path $repoRoot "run"
$harnessDir = Join-Path $runDir "altoclef-harness"
$crashDir = Join-Path $runDir "crash-reports"
$startTime = Get-Date

if ([string]::IsNullOrWhiteSpace($WorldName)) {
    $WorldName = "Machoclef Harness {0}" -f (Get-Date -Format "yyyyMMdd-HHmmss")
}

New-Item -ItemType Directory -Force -Path $harnessDir | Out-Null

if (-not $NoBuild) {
    & (Join-Path $repoRoot "gradlew.bat") --no-build-cache build
    if ($LASTEXITCODE -ne 0) {
        exit $LASTEXITCODE
    }
}

$env:ALTOCLEF_GAMER_HARNESS = "true"
$env:ALTOCLEF_GAMER_HARNESS_CREATE_WORLD = "true"
$env:ALTOCLEF_GAMER_HARNESS_WORLD_NAME = $WorldName
$env:ALTOCLEF_GAMER_HARNESS_SEED = $Seed
$env:ALTOCLEF_GAMER_HARNESS_COMMAND = "gamer"
$env:ALTOCLEF_GAMER_HARNESS_STALL_SECONDS = [string]$StallSeconds
$env:ALTOCLEF_GAMER_HARNESS_MAX_SECONDS = [string]($MaxMinutes * 60)
$env:ALTOCLEF_GAMER_HARNESS_STOP_CLIENT = "true"

Push-Location $repoRoot
try {
    & .\gradlew.bat runClient
    $clientExit = $LASTEXITCODE
} finally {
    Pop-Location
}

$latestResult = Get-ChildItem -Path $harnessDir -Filter "gamer-*.jsonl" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1

if ($null -eq $latestResult) {
    Write-Error "No gamer harness result file was written."
    exit 1
}

$resultLine = Select-String -Path $latestResult.FullName -Pattern '"type":"result"' |
    Select-Object -Last 1

$newCrash = $false
if (Test-Path $crashDir) {
    $newCrash = [bool](Get-ChildItem -Path $crashDir -Filter "*.txt" |
        Where-Object { $_.LastWriteTime -ge $startTime } |
        Select-Object -First 1)
}

Write-Host "Harness log: $($latestResult.FullName)"
if ($resultLine) {
    Write-Host $resultLine.Line
}

if ($clientExit -ne 0) {
    Write-Error "Minecraft/Gradle exited with code $clientExit."
    exit $clientExit
}

if ($newCrash) {
    Write-Error "A new crash report was created during the harness run."
    exit 1
}

if ($null -eq $resultLine) {
    Write-Error "Harness ended without a result line."
    exit 1
}

if ($resultLine.Line -notmatch '"status":"success"') {
    exit 1
}

exit 0
