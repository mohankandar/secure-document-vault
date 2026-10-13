[CmdletBinding()]
param(
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

if (-not $KeepDatabase) {
    Write-Host 'Stopping SDV PostgreSQL container (data volume is preserved)...'
    & docker compose -f (Join-Path $Root 'compose.local.yml') down
    if ($LASTEXITCODE -ne 0) { throw 'docker compose down failed.' }
}

Write-Host 'SDV local services stopped.'
