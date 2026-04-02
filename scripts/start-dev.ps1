param(
    [int]$Port = 8081,
    [string]$Profile = "dev",
    [string]$DbUrl = "jdbc:postgresql://localhost:5432/QTracker",
    [string]$DbUser = "postgres",
    [string]$DbPassword = "postgres",
    [switch]$ForceKill,
    [switch]$SkipRun
)

$ErrorActionPreference = "Stop"

function Get-ListeningPidsForPort {
    param([int]$TargetPort)

    $lines = netstat -ano | Select-String ":$TargetPort"
    $pids = @()

    foreach ($line in $lines) {
        $text = $line.Line.Trim()
        if ($text -match "LISTENING\s+(\d+)$") {
            $pids += [int]$matches[1]
        }
    }

    return $pids | Sort-Object -Unique
}

function Stop-ConflictingPortProcesses {
    param(
        [int]$TargetPort,
        [switch]$KillAll
    )

    $pids = Get-ListeningPidsForPort -TargetPort $TargetPort
    if (-not $pids -or $pids.Count -eq 0) {
        Write-Host "Port $TargetPort is free."
        return
    }

    Write-Host "Found listeners on port ${TargetPort}: $($pids -join ', ')"

    foreach ($pid in $pids) {
        try {
            $proc = Get-Process -Id $pid -ErrorAction Stop
            Write-Host "- PID=$($proc.Id) Name=$($proc.ProcessName)"

            if ($KillAll -or $proc.ProcessName -eq "java") {
                Stop-Process -Id $proc.Id -Force
                Write-Host "  Stopped PID $($proc.Id)."
            } else {
                Write-Host "  Skipped non-java process (use -ForceKill to stop it)."
            }
        } catch {
            Write-Host "  Could not inspect/stop PID ${pid}: $($_.Exception.Message)"
        }
    }

    Start-Sleep -Milliseconds 300
}

try {
    Stop-ConflictingPortProcesses -TargetPort $Port -KillAll:$ForceKill

    $remaining = Get-ListeningPidsForPort -TargetPort $Port
    if ($remaining -and $remaining.Count -gt 0) {
        throw "Port $Port is still busy. Remaining PIDs: $($remaining -join ', ')."
    }

    $env:SPRING_PROFILES_ACTIVE = $Profile
    $env:SPRING_DATASOURCE_URL = $DbUrl
    $env:SPRING_DATASOURCE_USERNAME = $DbUser
    $env:SPRING_DATASOURCE_PASSWORD = $DbPassword
    $env:SERVER_PORT = "$Port"
    $env:APP_BASE_URL = "http://localhost:$Port"

    Write-Host "Environment prepared: profile=$Profile port=$Port"
    Write-Host "APP_BASE_URL=$($env:APP_BASE_URL)"

    if ($SkipRun) {
        Write-Host "SkipRun enabled. Exiting without starting application."
        exit 0
    }

    & .\mvnw.cmd spring-boot:run
    exit $LASTEXITCODE
} catch {
    Write-Error $_.Exception.Message
    exit 1
}
