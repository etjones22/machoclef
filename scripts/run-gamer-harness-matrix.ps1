param(
    [ValidateSet("Fast", "Final")]
    [string]$Mode = "Fast",
    [int]$Workers = 0,
    [int]$MaxMinutes = 0,
    [int]$StallSeconds = 0,
    [int]$RepeatedWarningLimit = 0,
    [int]$SampleTicks = 20,
    [int]$StartDelayTicks = 100,
    [string[]]$Seeds = @(),
    [string]$RunRoot = "",
    [string]$Command = "gamer",
    [switch]$FailOnTimeout,
    [switch]$NoBuild
)

$ErrorActionPreference = "Stop"

$repoRoot = Resolve-Path (Join-Path $PSScriptRoot "..")
$repoRootPath = $repoRoot.Path
$singleHarnessScript = Join-Path $PSScriptRoot "run-gamer-harness.ps1"

if ($Mode -eq "Final") {
    $Workers = 1
    if ($MaxMinutes -le 0) { $MaxMinutes = 360 }
    if ($StallSeconds -le 0) { $StallSeconds = 300 }
    if ($RepeatedWarningLimit -le 0) { $RepeatedWarningLimit = 8 }
    $FailOnTimeout = $true
} else {
    if ($Workers -le 0) { $Workers = 2 }
    if ($MaxMinutes -le 0) { $MaxMinutes = 25 }
    if ($StallSeconds -le 0) { $StallSeconds = 90 }
    if ($RepeatedWarningLimit -le 0) { $RepeatedWarningLimit = 4 }
}

if ([string]::IsNullOrWhiteSpace($RunRoot)) {
    $RunRoot = Join-Path $repoRootPath "run\harness-workers"
} elseif (-not [System.IO.Path]::IsPathRooted($RunRoot)) {
    $RunRoot = Join-Path $repoRootPath $RunRoot
}
$RunRoot = [System.IO.Path]::GetFullPath($RunRoot)
New-Item -ItemType Directory -Force -Path $RunRoot | Out-Null

if (-not $NoBuild) {
    Push-Location $repoRootPath
    try {
        & .\gradlew.bat --no-build-cache build
        if ($LASTEXITCODE -ne 0) {
            exit $LASTEXITCODE
        }
    } finally {
        Pop-Location
    }
}

function Quote-Arg([string]$Value) {
    if ($null -eq $Value) {
        return '""'
    }
    return '"' + $Value.Replace('"', '\"') + '"'
}

function Stop-ProcessTree([int]$ProcessId) {
    $children = Get-CimInstance Win32_Process | Where-Object { $_.ParentProcessId -eq $ProcessId }
    foreach ($child in $children) {
        Stop-ProcessTree -ProcessId $child.ProcessId
    }
    Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
}

function Get-LatestHarnessResult([string]$HarnessDir) {
    if (-not (Test-Path $HarnessDir)) {
        return $null
    }

    $latestLog = Get-ChildItem -Path $HarnessDir -Filter "gamer-*.jsonl" |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1

    if ($null -eq $latestLog) {
        return $null
    }

    $resultLine = Select-String -Path $latestLog.FullName -Pattern '"type":"result"' |
        Select-Object -Last 1

    if ($null -eq $resultLine) {
        return [pscustomobject]@{
            LogPath = $latestLog.FullName
            Status = "running"
            Reason = ""
            Message = ""
            Raw = ""
        }
    }

    try {
        $json = $resultLine.Line | ConvertFrom-Json
        return [pscustomobject]@{
            LogPath = $latestLog.FullName
            Status = [string]$json.status
            Reason = [string]$json.reason
            Message = [string]$json.message
            Raw = $resultLine.Line
        }
    } catch {
        return [pscustomobject]@{
            LogPath = $latestLog.FullName
            Status = "invalid"
            Reason = "INVALID_JSON"
            Message = $_.Exception.Message
            Raw = $resultLine.Line
        }
    }
}

function Is-HardFailure($Result) {
    if ($null -eq $Result) {
        return $false
    }
    if ($Result.Status -ne "failure") {
        return $false
    }
    if ($Result.Reason -eq "TIMEOUT" -and -not $FailOnTimeout) {
        return $false
    }
    return $true
}

$runStamp = Get-Date -Format "yyyyMMdd-HHmmss"
$workersList = New-Object System.Collections.Generic.List[object]

for ($i = 1; $i -le $Workers; $i++) {
    if ($Seeds.Count -ge $i) {
        $seed = $Seeds[$i - 1]
    } else {
        $seed = [string]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds() + $i)
    }

    $workerName = "{0}-{1:00}" -f $Mode.ToLowerInvariant(), $i
    $workerRoot = Join-Path $RunRoot "$runStamp-$workerName"
    $harnessDir = Join-Path $workerRoot "altoclef-harness"
    $stdoutPath = Join-Path $workerRoot "worker.out.log"
    $stderrPath = Join-Path $workerRoot "worker.err.log"
    $worldName = "Machoclef $Mode $runStamp worker $i"

    New-Item -ItemType Directory -Force -Path $workerRoot | Out-Null

    $args = @(
        "-NoProfile",
        "-ExecutionPolicy", "Bypass",
        "-File", (Quote-Arg $singleHarnessScript),
        "-NoBuild",
        "-MaxMinutes", $MaxMinutes,
        "-StallSeconds", $StallSeconds,
        "-RepeatedWarningLimit", $RepeatedWarningLimit,
        "-SampleTicks", $SampleTicks,
        "-StartDelayTicks", $StartDelayTicks,
        "-WorldName", (Quote-Arg $worldName),
        "-Seed", (Quote-Arg $seed),
        "-RunDir", (Quote-Arg $workerRoot),
        "-Command", (Quote-Arg $Command)
    )
    if ($Mode -eq "Fast" -and -not $FailOnTimeout) {
        $args += "-AllowTimeout"
    }

    $process = Start-Process -FilePath "powershell.exe" `
        -ArgumentList ($args -join " ") `
        -WorkingDirectory $repoRootPath `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru `
        -WindowStyle Hidden

    $workersList.Add([pscustomobject]@{
        Id = $i
        Name = $workerName
        Seed = $seed
        RunDir = $workerRoot
        HarnessDir = $harnessDir
        Stdout = $stdoutPath
        Stderr = $stderrPath
        Process = $process
        Result = $null
        Complete = $false
    })
}

Write-Host "Started $($workersList.Count) $Mode gamer harness worker(s)."
foreach ($worker in $workersList) {
    Write-Host "Worker $($worker.Id): seed=$($worker.Seed) runDir=$($worker.RunDir)"
}

$hardFailure = $null

while ($true) {
    $allComplete = $true

    foreach ($worker in $workersList) {
        if ($worker.Complete) {
            continue
        }

        $worker.Process.Refresh()
        $result = Get-LatestHarnessResult $worker.HarnessDir
        if ($result -ne $null -and $result.Status -ne "running") {
            $worker.Result = $result
            $worker.Complete = $true
            Write-Host "Worker $($worker.Id) result: $($result.Status) $($result.Reason) $($result.Message)"

            if (Is-HardFailure $result) {
                $hardFailure = $worker
                break
            }
            continue
        }

        if ($worker.Process.HasExited) {
            if ($result -ne $null) {
                $worker.Result = $result
            } else {
                $worker.Result = [pscustomobject]@{
                    LogPath = ""
                    Status = "failure"
                    Reason = "NO_RESULT"
                    Message = "Worker exited without a harness result."
                    Raw = ""
                }
            }
            $worker.Complete = $true
            Write-Host "Worker $($worker.Id) exited: $($worker.Result.Status) $($worker.Result.Reason) $($worker.Result.Message)"
            if (Is-HardFailure $worker.Result) {
                $hardFailure = $worker
                break
            }
            continue
        }

        $allComplete = $false
    }

    if ($hardFailure -ne $null) {
        Write-Host "Stopping remaining workers after worker $($hardFailure.Id) hard failure."
        foreach ($worker in $workersList) {
            if (-not $worker.Process.HasExited) {
                Stop-ProcessTree -ProcessId $worker.Process.Id
            }
        }
        break
    }

    if ($allComplete) {
        break
    }

    Start-Sleep -Seconds 2
}

foreach ($worker in $workersList) {
    if ($worker.Result -eq $null) {
        $worker.Result = Get-LatestHarnessResult $worker.HarnessDir
    }
}

Write-Host ""
Write-Host "Harness matrix summary:"
foreach ($worker in $workersList) {
    $result = $worker.Result
    if ($null -eq $result) {
        Write-Host ("Worker {0}: no result | seed={1} | run={2}" -f $worker.Id, $worker.Seed, $worker.RunDir)
        continue
    }
    Write-Host ("Worker {0}: {1} {2} | seed={3} | log={4}" -f $worker.Id, $result.Status, $result.Reason, $worker.Seed, $result.LogPath)
}

if ($hardFailure -ne $null) {
    exit 1
}

if ($Mode -eq "Final") {
    $final = $workersList[0].Result
    if ($null -eq $final -or $final.Status -ne "success") {
        exit 1
    }
}

exit 0
