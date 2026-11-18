[CmdletBinding()]
param(
    # The database and Vault containers hold development data and stay running by default.
    # Pass -StopDatabase to also stop sdv-postgres (the container and its data volume are kept).
    [switch]$StopDatabase,
    # Accepted for older callers; keeping the database running is now the default.
    [switch]$KeepDatabase
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Runtime = Join-Path $Root '.runtime'
$Jar = Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar'

function Get-SdvOwnedProcess {
    param(
        [int]$ProcessId,
        [ValidateSet('backend','frontend')]
        [string]$Kind
    )

    $p = Get-CimInstance Win32_Process -Filter "ProcessId = $ProcessId" -ErrorAction SilentlyContinue
    if (-not $p) { return $null }

    $cmd = [string]$p.CommandLine
    if ([string]::IsNullOrWhiteSpace($cmd)) { return $null }

    if ($Kind -eq 'backend') {
        if ($p.Name -notmatch '^powershell(\.exe)?$') { return $null }
        if ($cmd -notmatch 'SDV Backend -') { return $null }
        return $p
    }

    if ($p.Name -notmatch '^powershell(\.exe)?$') { return $null }
    if ($cmd -notmatch 'SDV Frontend -') { return $null }
    return $p
}

foreach ($name in @('frontend', 'backend')) {
    $pidFile = Join-Path $Runtime "$name.pid"
    if (Test-Path -LiteralPath $pidFile) {
        $raw = (Get-Content -LiteralPath $pidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
        $processId = 0

        if ([int]::TryParse([string]$raw, [ref]$processId)) {
            $owned = Get-SdvOwnedProcess -ProcessId $processId -Kind $name
            if ($owned) {
                Write-Host "Stopping SDV $name PID $processId..."
                & taskkill.exe /PID $processId /T /F *> $null
                if ($LASTEXITCODE -ne 0) {
                    Stop-Process -Id $processId -Force -ErrorAction SilentlyContinue
                }
            } else {
                $other = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
                if ($other) {
                    Write-Warning "PID file for SDV $name points to PID $processId, but that PID now belongs to '$($other.Name)'. It will NOT be terminated."
                } else {
                    Write-Host "Removing stale SDV $name PID file for exited PID $processId."
                }
            }
        } else {
            Write-Warning "Removing invalid SDV $name PID file: $pidFile"
        }

        Remove-Item -LiteralPath $pidFile -Force -ErrorAction SilentlyContinue
    }
}

if ($StopDatabase -and -not $KeepDatabase) {
    # Stop only the database container. `docker compose down` must not be used
    # here: compose.local.yml also defines the sdv-vault service, so `down`
    # would stop and remove the persistent Vault container as well.
    $postgresRunning = (& docker ps --filter 'name=^/sdv-postgres$' --format '{{.Names}}' | Out-String).Trim()
    if ($postgresRunning -eq 'sdv-postgres') {
        Write-Host 'Stopping SDV PostgreSQL container (container and data volume are preserved)...'
        & docker stop sdv-postgres | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not stop sdv-postgres.' }
    } else {
        Write-Host 'sdv-postgres is not running.'
    }
} else {
    Write-Host 'sdv-postgres was left running (use -StopDatabase to stop it).'
}
Write-Host 'Persistent sdv-vault was left untouched.'

Write-Host 'SDV local services stopped.'
