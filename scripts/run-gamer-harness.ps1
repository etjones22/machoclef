param(
    [int]$MaxMinutes = 360,
    [int]$StallSeconds = 300,
    [int]$RepeatedWarningLimit = 8,
    [int]$SampleTicks = 20,
    [int]$StartDelayTicks = 100,
    [string]$WorldName = "",
    [string]$Seed = "",
    [string]$RunDir = "",
    [string]$Command = "gamer",
    [switch]$AllowTimeout,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$repoRootPath = $repoRoot.Path
if ([string]::IsNullOrWhiteSpace($RunDir)) {
    $RunDir = Join-Path $repoRootPath "run"
} elseif (-not [System.IO.Path]::IsPathRooted($RunDir)) {
    $RunDir = Join-Path $repoRootPath $RunDir
}

$runDir = [System.IO.Path]::GetFullPath($RunDir)
$gradleRunDir = $runDir
$repoPrefix = $repoRootPath.TrimEnd([System.IO.Path]::DirectorySeparatorChar, [System.IO.Path]::AltDirectorySeparatorChar) + [System.IO.Path]::DirectorySeparatorChar
if ($runDir.StartsWith($repoPrefix, [System.StringComparison]::OrdinalIgnoreCase)) {
    $gradleRunDir = $runDir.Substring($repoPrefix.Length)
}
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
$env:ALTOCLEF_GAMER_HARNESS_COMMAND = $Command
$env:ALTOCLEF_GAMER_HARNESS_START_DELAY_TICKS = [string]$StartDelayTicks
$env:ALTOCLEF_GAMER_HARNESS_SAMPLE_TICKS = [string]$SampleTicks
$env:ALTOCLEF_GAMER_HARNESS_STALL_SECONDS = [string]$StallSeconds
$env:ALTOCLEF_GAMER_HARNESS_MAX_SECONDS = [string]($MaxMinutes * 60)
$env:ALTOCLEF_GAMER_HARNESS_REPEATED_WARNING_LIMIT = [string]$RepeatedWarningLimit
$env:ALTOCLEF_GAMER_HARNESS_STOP_CLIENT = "true"

Push-Location $repoRoot
try {
    & .\gradlew.bat "-Paltoclef.runDir=$gradleRunDir" runClient
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
    if ($AllowTimeout -and $resultLine.Line -match '"reason":"TIMEOUT"') {
        exit 0
    }
    exit 1
}

exit 0
