#Requires -RunAsAdministrator
# =============================================================================
# server-update.ps1 - run on the SERVER (it needs only the corporate GitLab).
#
#   1. reads from GitLab which version is the latest (published by publish.ps1)
#   2. nothing to do when that version is the one installed (deployed-version.txt)
#   3. downloads the image, checks its SHA-256 and installs it with server-deploy.ps1
#      (database backup, docker load, docker-compose up, wait for the application)
#
# Safe to run on a schedule (Task Scheduler): it does something only for a new version.
#
#   powershell -ExecutionPolicy Bypass -File C:\MyApps\QTracker\deploy\server-update.ps1
#   ... -Force         install the latest version again
#   ... -SkipBackup    no database backup (not recommended)
# =============================================================================
param(
    [string]$Config = 'C:\MyApps\QTracker\secrets\deploy.psd1',
    [switch]$Force,
    [switch]$SkipBackup
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

if (-not (Test-Path $Config)) { throw "No settings file $Config (see deploy\deploy.example.psd1)" }
$c = Import-PowerShellDataFile $Config
$logDir = Join-Path $c.Dir 'logs'
New-Item -ItemType Directory -Force -Path $logDir | Out-Null
Start-Transcript -Path (Join-Path $logDir "update-$(Get-Date -Format yyyyMMdd-HHmmss).log") | Out-Null

$exitCode = 0
$header = Join-Path $env:TEMP "qtracker-deploy-header-$PID.txt"
try {
    $pair = (Get-Content $c.DeployTokenFile -Raw).Trim()
    if ($pair -notmatch ':') { throw "$($c.DeployTokenFile) must hold username:token of the GitLab deploy token" }
    $token = $pair.Substring($pair.IndexOf(':') + 1)
    Set-Content -Path $header -Value "DEPLOY-TOKEN: $token" -NoNewline -Encoding ascii
    $project = [uri]::EscapeDataString([string]$c.ProjectPath)
    $base = "$($c.GitLabUrl)/api/v4/projects/$project/packages/generic"

    function Download([string]$url, [string]$file) {
        & curl.exe --fail -sS -H "@$header" -o $file $url
        if ($LASTEXITCODE -ne 0) { throw "Download failed (curl exit $LASTEXITCODE): $url" }
    }

    # --- 1. Which version is the latest --------------------------------------
    $downloads = Join-Path $c.Dir 'downloads'
    New-Item -ItemType Directory -Force -Path $downloads | Out-Null
    $pointer = Join-Path $downloads 'latest-version.txt'
    Download "$base/$($c.PackageName)-latest/latest/version.txt" $pointer
    $version = (Get-Content $pointer -Raw).Trim()
    if ($version -notmatch '^[0-9A-Za-z.\-]+$') { throw "Unexpected version in GitLab: '$version'" }
    $installedFile = Join-Path $c.Dir 'deployed-version.txt'
    $installed = if (Test-Path $installedFile) { (Get-Content $installedFile -Raw).Trim() } else { '' }
    Write-Host "Latest in GitLab: $version, installed: $(if ($installed) { $installed } else { 'unknown' })"
    if ($version -eq $installed -and -not $Force) {
        Write-Host "UP TO DATE: nothing to do." -ForegroundColor Green
        return
    }

    # --- 2. Download and check -----------------------------------------------
    $gz = Join-Path $downloads "qtracker-app-$version.tar.gz"
    Download "$base/$($c.PackageName)/$version/qtracker-app.tar.gz" $gz
    Download "$base/$($c.PackageName)/$version/qtracker-app.tar.gz.sha256" "$gz.sha256"
    $expected = (Get-Content "$gz.sha256" -Raw).Trim().ToLowerInvariant()
    $actual = (Get-FileHash $gz -Algorithm SHA256).Hash.ToLowerInvariant()
    if ($expected -ne $actual) { throw "SHA-256 does not match: GitLab $expected, downloaded $actual" }
    Write-Host ("Downloaded {0} ({1:N0} MB), SHA-256 OK" -f $gz, ((Get-Item $gz).Length / 1MB)) -ForegroundColor Green

    # server-deploy.ps1 loads <Dir>\qtracker-app.tar; docker load reads the gzipped file as it is
    Copy-Item $gz (Join-Path $c.Dir 'qtracker-app.tar') -Force

    # --- 3. Install ------------------------------------------------------------
    $deploy = Join-Path $PSScriptRoot 'server-deploy.ps1'
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $deploy, '-Config', $Config)
    if ($SkipBackup) { $arguments += '-SkipBackup' }
    & powershell.exe @arguments
    if ($LASTEXITCODE -ne 0) { throw "server-deploy.ps1 failed (exit $LASTEXITCODE); $version is NOT recorded as installed" }
    Set-Content -Path $installedFile -Value $version -NoNewline -Encoding ascii
    Write-Host "UPDATED to $version." -ForegroundColor Green

    # Keep the last 3 downloads
    Get-ChildItem $downloads -Filter 'qtracker-app-*.tar.gz' | Sort-Object LastWriteTime -Descending |
        Select-Object -Skip 3 | ForEach-Object { Remove-Item $_.FullName, "$($_.FullName).sha256" -Force -ErrorAction SilentlyContinue }
} catch {
    Write-Host "UPDATE FAILED: $_" -ForegroundColor Red
    $exitCode = 1
} finally {
    Remove-Item $header -Force -ErrorAction SilentlyContinue
    Stop-Transcript | Out-Null
}
exit $exitCode
