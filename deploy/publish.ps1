# =============================================================================
# publish.ps1 - run on the CORPORATE LAPTOP.
#
#   1. takes the branch (main) from GitHub and pushes it to the corporate GitLab
#   2. builds the JAR (Maven) and the Docker image qtracker-app:offline
#   3. saves the image, gzips it and uploads it to the GitLab package registry
#      (Generic Packages) as version yyyy.MM.dd-HHmm-<commit>, and marks it latest
#
# The server then takes it with server-update.ps1. Nothing is written to the repository.
#
#   powershell -ExecutionPolicy Bypass -File .\deploy\publish.ps1
#   ... -RunTests      run the tests (mvn verify) before building
#   ... -DryRun        build and pack only: no git push, no upload
# =============================================================================
[CmdletBinding()]
param(
    [string]$Config = "$env:USERPROFILE\.qtracker\deploy.psd1",
    [switch]$RunTests,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'

function Step($text) { Write-Host ""; Write-Host "== $text" -ForegroundColor Cyan }
function Ok($text) { Write-Host "[OK] $text" -ForegroundColor Green }

# Runs a native command and stops on a non-zero exit code
function Run {
    param([string]$File, [string[]]$Arguments)
    & $File @Arguments
    if ($LASTEXITCODE -ne 0) { throw "$File $($Arguments -join ' ') failed (exit $LASTEXITCODE)" }
}

if (-not (Test-Path $Config)) { throw "No settings file $Config (copy deploy\deploy.example.psd1 there and fill it in)" }
$c = Import-PowerShellDataFile $Config
$repo = Split-Path -Parent $PSScriptRoot
Set-Location $repo

# --- 1. Git: GitHub -> GitLab ------------------------------------------------
if (-not $DryRun) {
    Step "Git: $($c.GitHubRemote)/$($c.Branch) -> $($c.GitLabRemote)/$($c.Branch)"
    $dirty = git status --porcelain --untracked-files=no
    if ($dirty) { throw "The working tree has changes; commit or stash them first:`n$dirty" }
    Run git @('fetch', $c.GitHubRemote, $c.Branch)
    Run git @('checkout', $c.Branch)
    Run git @('merge', '--ff-only', "$($c.GitHubRemote)/$($c.Branch)")
    Run git @('push', $c.GitLabRemote, $c.Branch)
    Ok "GitLab has $($c.Branch) at $(git rev-parse --short HEAD)"
}
$commit = (git rev-parse --short HEAD).Trim()
$version = '{0:yyyy.MM.dd-HHmm}-{1}' -f (Get-Date), $commit

# --- 2. Build ----------------------------------------------------------------
Step "Build $version"
if ($RunTests) { Run "$repo\mvnw.cmd" @('-B', 'clean', 'verify') } else { Run "$repo\mvnw.cmd" @('-B', 'clean', 'package', '-DskipTests') }
Run docker @('build', '-t', 'qtracker-app:offline', '--label', "qtracker.version=$version", '.')
Ok "image qtracker-app:offline built"

# --- 3. Pack -----------------------------------------------------------------
Step "Pack"
$out = Join-Path $env:TEMP "qtracker-publish"
New-Item -ItemType Directory -Force -Path $out | Out-Null
$tar = Join-Path $out 'qtracker-app.tar'
$gz = Join-Path $out 'qtracker-app.tar.gz'
Remove-Item $tar, $gz -Force -ErrorAction SilentlyContinue
Run docker @('save', '-o', $tar, 'qtracker-app:offline')
# docker load reads a gzipped archive as it is
$in = [System.IO.File]::OpenRead($tar)
$fileOut = [System.IO.File]::Create($gz)
$zip = New-Object System.IO.Compression.GZipStream($fileOut, [System.IO.Compression.CompressionLevel]::Optimal)
try { $in.CopyTo($zip) } finally { $zip.Dispose(); $fileOut.Dispose(); $in.Dispose() }
Remove-Item $tar -Force
$sha = (Get-FileHash $gz -Algorithm SHA256).Hash.ToLowerInvariant()
Set-Content -Path "$gz.sha256" -Value $sha -NoNewline -Encoding ascii
Set-Content -Path (Join-Path $out 'version.txt') -Value $version -NoNewline -Encoding ascii
Ok ("{0} ({1:N0} MB), sha256 {2}" -f $gz, ((Get-Item $gz).Length / 1MB), $sha)

if ($DryRun) {
    Write-Host ""
    Write-Host "DRY RUN: nothing pushed or uploaded. Files in $out" -ForegroundColor Yellow
    exit 0
}

# --- 4. Upload to the GitLab package registry --------------------------------
Step "Upload to $($c.GitLabUrl)"
$token = (Get-Content $c.PublishTokenFile -Raw).Trim()
if (-not $token) { throw "The token file $($c.PublishTokenFile) is empty" }
$project = [uri]::EscapeDataString([string]$c.ProjectPath)
$base = "$($c.GitLabUrl)/api/v4/projects/$project/packages/generic"
# The token goes in a header file, so it is not on the command line
$header = Join-Path $out 'auth-header.txt'
Set-Content -Path $header -Value "PRIVATE-TOKEN: $token" -NoNewline -Encoding ascii
try {
    foreach ($file in @($gz, "$gz.sha256")) {
        $name = Split-Path $file -Leaf
        Run curl.exe @('--fail-with-body', '-sS', '-H', "@$header", '--upload-file', $file,
                "$base/$($c.PackageName)/$version/$name")
        Write-Host ""
        Ok "uploaded $name"
    }
    # The pointer the server reads: the version to install now
    Run curl.exe @('--fail-with-body', '-sS', '-H', "@$header", '--upload-file', (Join-Path $out 'version.txt'),
            "$base/$($c.PackageName)-latest/latest/version.txt")
    Write-Host ""
    Ok "latest = $version"
} finally {
    Remove-Item $header -Force -ErrorAction SilentlyContinue
}
Remove-Item $gz, "$gz.sha256" -Force -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "PUBLISHED $version. On the server: server-update.ps1 (or wait for its scheduled run)." -ForegroundColor Green
