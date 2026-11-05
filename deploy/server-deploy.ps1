#Requires -RunAsAdministrator
# =============================================================================
# server-deploy.ps1 - installs <Dir>\qtracker-app.tar on the SERVER (called by server-update.ps1; can be run by hand).
# The update script of the server, with its settings in the settings file and no password in it: pg_dump takes the
# password of the database user from pgpass.conf (%APPDATA%\postgresql\pgpass.conf of the user who runs it).
#
#   1. Docker must run (else /root/start-qtracker.sh)
#   2. database backup (the last 10 kept); the update stops if it fails
#   3. docker load; the image before is kept as :previous
#   4. docker-compose up -d
#   5. waits up to 3 minutes for the application; on failure prints the logs and how to roll back
#   6. PortProxy 443 -> WSL
#   7. removes the images left without a tag (each update leaves one)
# =============================================================================
param(
    [string]$Config = 'C:\MyApps\QTracker\secrets\deploy.psd1',
    [switch]$SkipBackup
)

$ErrorActionPreference = 'Continue'
if (-not (Test-Path $Config)) { throw "No settings file $Config (see deploy\deploy.example.psd1)" }
$c = Import-PowerShellDataFile $Config

$Dir       = $c.Dir
$Tar       = Join-Path $Dir 'qtracker-app.tar'
$WslDir    = $c.WslDir
$Distro    = $c.WslDistro
$Image     = 'qtracker-app'
$Tag       = 'offline'
$AppPort   = $c.AppPort
$AppHost   = $c.AppHost
$BackupDir = Join-Path $Dir 'backups'

function Wsl-Run($cmd) {
    wsl -d $Distro -u root -e bash -c "($cmd) 2>&1"
    if ($LASTEXITCODE -ne 0) { throw "WSL command failed (exit $LASTEXITCODE): $cmd" }
}

if (-not (Test-Path $Tar)) { throw "Not found: $Tar" }

# The Windows address as WSL sees it (changes after a restart)
$route = wsl -d $Distro -u root -e bash -c "ip route show default"
$WINHOST = ($route -split '\s+')[2]
if (-not $WINHOST) { throw "Could not get the Windows host IP from WSL." }

# 1. Docker must run
wsl -d $Distro -u root -e bash -c "docker info >/dev/null 2>&1"
if ($LASTEXITCODE -ne 0) {
    Write-Host "Docker is down, starting..." -ForegroundColor Yellow
    Wsl-Run "export WINHOST=$WINHOST; /root/start-qtracker.sh"
}

# 2. Database backup (the last 10 kept)
if (-not $SkipBackup) {
    New-Item -ItemType Directory -Force -Path $BackupDir -ErrorAction Stop | Out-Null
    $dump = Join-Path $BackupDir "$($c.DbName)_$(Get-Date -Format yyyyMMdd-HHmmss).dump"
    & (Join-Path $c.PgBin 'pg_dump.exe') -w -h $c.DbHost -U $c.DbUser -d $c.DbName -Fc -f $dump
    if ($LASTEXITCODE -ne 0 -or -not (Test-Path $dump) -or (Get-Item $dump).Length -lt 1000) {
        throw "DB backup failed, update stopped (is the password of $($c.DbUser) in pgpass.conf?)."
    }
    Write-Host "DB backup OK: $dump" -ForegroundColor Green
    Get-ChildItem $BackupDir -Filter *.dump | Sort-Object LastWriteTime -Descending | Select-Object -Skip 10 | Remove-Item -Force
} else {
    Write-Host "WARNING: DB backup skipped." -ForegroundColor Red
}

# 3. Load the image; :previous changes only when the image did
$oldId = (wsl -d $Distro -u root -e bash -c "docker images -q ${Image}:${Tag}") | Select-Object -First 1
Write-Host "Loading app image..." -ForegroundColor Green
Wsl-Run "docker load -i $WslDir/qtracker-app.tar"
$newId = (wsl -d $Distro -u root -e bash -c "docker images -q ${Image}:${Tag}") | Select-Object -First 1
if ($oldId -and $oldId -ne $newId) {
    Wsl-Run "docker tag $oldId ${Image}:previous"
    Write-Host "Previous version saved as :previous." -ForegroundColor Green
}

# 4. Recreate the container
Wsl-Run "cd $WslDir && WINHOST=$WINHOST docker-compose up -d"

# 5. Wait for the application (up to 3 minutes)
$WSL_IP = ((wsl -d $Distro -u root -e bash -c "hostname -I") -split '\s+')[0].Trim()
$ok = $false
for ($i = 1; $i -le 90; $i++) {
    # --resolve: to WSL_IP with the right Host/SNI, so the canonical-host filter answers instead of a 301
    $code = curl.exe -k -s -o NUL -w "%{http_code}" --max-time 3 `
        --resolve "${AppHost}:${AppPort}:${WSL_IP}" `
        "https://${AppHost}:${AppPort}/login"
    if ($code -match '^(200|302)$') { $ok = $true; break }
    Write-Host "Waiting for app... ($i) HTTP=$code"
    Start-Sleep -Seconds 2
}

if (-not $ok) {
    Write-Host "DEPLOY FAILED: app did not start." -ForegroundColor Red
    wsl -d $Distro -u root -e bash -c "cd $WslDir && WINHOST=$WINHOST docker-compose logs --tail 50 app 2>&1"
    Write-Host "Rollback: wsl -d $Distro -u root -e bash -c ""docker tag ${Image}:previous ${Image}:${Tag}; cd $WslDir && WINHOST=$WINHOST docker-compose up -d""" -ForegroundColor Yellow
    Write-Host "If migrations were applied, also restore the DB from the latest dump in $BackupDir" -ForegroundColor Yellow
    exit 1
}

# 6. PortProxy 443 -> WSL
netsh interface portproxy delete v4tov4 listenport=443 listenaddress=0.0.0.0 | Out-Null
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=443 connectaddress=$WSL_IP connectport=$AppPort
Restart-Service iphlpsvc

# 7. The image that was :previous before this update has no tag now: remove such images, they only take space
#    (:offline and :previous, the way back, stay). A failure here does not undo the update.
wsl -d $Distro -u root -e bash -c "docker image prune -f 2>&1"
if ($LASTEXITCODE -ne 0) {
    Write-Host "WARNING: docker image prune failed (exit $LASTEXITCODE); the update itself is done." -ForegroundColor Yellow
}

Write-Host "DEPLOY OK: QTracker is up." -ForegroundColor Green
exit 0
