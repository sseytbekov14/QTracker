# Quick Docker startup script for QTracker DEV environment on Windows

param(
    [string]$Environment = "dev",
    [switch]$Build = $false,
    [switch]$Clean = $false,
    [switch]$Stop = $false,
    [switch]$Logs = $false,
    [switch]$Status = $false
)

$ErrorActionPreference = "Stop"

function Write-Success { Write-Host "[✓] $args" -ForegroundColor Green }
function Write-Error-Custom { Write-Host "[✗] $args" -ForegroundColor Red }
function Write-Info { Write-Host "[i] $args" -ForegroundColor Cyan }

# Check Docker Desktop is running
function Test-DockerRunning {
    try {
        docker ps > $null 2>&1
        return $true
    } catch {
        return $false
    }
}

# Main logic
try {
    Write-Info "QTracker Docker Control Script"
    Write-Info "Environment: $Environment"

    # Check Docker
    if (-not (Test-DockerRunning)) {
        Write-Error-Custom "Docker Desktop is not running!"
        Write-Info "Please start Docker Desktop and try again."
        exit 1
    }
    Write-Success "Docker Desktop is running"

    # Validate environment file
    $envFile = ".env.$Environment"
    if (-not (Test-Path $envFile)) {
        Write-Error-Custom "Environment file not found: $envFile"
        exit 1
    }
    Write-Success "Using configuration: $envFile"

    # Set environment variable for docker-compose
    $env:QTRACKER_ENV_FILE = $envFile

    # Show status
    if ($Status) {
        Write-Info "Container status:"
        docker compose ps
        exit 0
    }

    # Show logs
    if ($Logs) {
        Write-Info "Following application logs (Ctrl+C to stop)..."
        docker compose logs -f app --tail=100
        exit 0
    }

    # Stop containers
    if ($Stop) {
        Write-Info "Stopping containers..."
        docker compose down
        Write-Success "Containers stopped"
        exit 0
    }

    # Clean (full reset)
    if ($Clean) {
        Write-Info "Performing full cleanup (removing volumes)..."
        Write-Error-Custom "WARNING: This will delete all data in the database!"
        $response = Read-Host "Type 'yes' to confirm"
        if ($response -eq "yes") {
            docker compose down -v
            Write-Success "Full cleanup completed. Run again without -Clean to restart fresh."
            exit 0
        } else {
            Write-Info "Cleanup cancelled"
            exit 1
        }
    }

    # Start containers
    Write-Info "Starting QTracker ($Environment environment)..."
    
    if ($Build) {
        Write-Info "Building Docker image..."
        docker compose up -d --build
    } else {
        docker compose up -d
    }

    # Wait for services to be ready
    Write-Info "Waiting for services to be ready..."
    $maxRetries = 30
    $retry = 0

    while ($retry -lt $maxRetries) {
        try {
            $health = docker compose ps app | Select-String "healthy|running"
            if ($health) {
                Start-Sleep -Seconds 2
                break
            }
        } catch { }
        
        Start-Sleep -Seconds 1
        $retry++
    }

    # Final status
    Write-Info "Service status:"
    docker compose ps

    # Get access information
    $port = docker compose config | Select-String "APP_PORT|8081" | Select-Object -First 1
    Write-Success "✓ QTracker is starting up!"
    Write-Host ""
    Write-Host "=== ACCESS INFORMATION ===" -ForegroundColor Yellow
    Write-Host "URL:      http://localhost:8081" -ForegroundColor Yellow
    Write-Host "Database: localhost:5432" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "View logs:     .\start-dev.ps1 -Logs" -ForegroundColor Cyan
    Write-Host "Stop:          .\start-dev.ps1 -Stop" -ForegroundColor Cyan
    Write-Host "Rebuild:       .\start-dev.ps1 -Build" -ForegroundColor Cyan
    Write-Host "Full reset:    .\start-dev.ps1 -Clean" -ForegroundColor Cyan
}
catch {
    Write-Error-Custom "Error: $_"
    exit 1
}
