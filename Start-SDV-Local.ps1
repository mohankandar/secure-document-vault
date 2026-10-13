[CmdletBinding()]
param(
    [switch]$WithVault
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Runtime = Join-Path $Root '.runtime'
$BackendPid = Join-Path $Runtime 'backend.pid'
$FrontendPid = Join-Path $Runtime 'frontend.pid'
$Jar = Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar'
$Frontend = Join-Path $Root 'frontend\sdv-ui'

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

function Remove-StalePidFile {
    param(
        [string]$PidFile,
        [string]$Kind
    )

    if (-not (Test-Path -LiteralPath $PidFile)) { return }

    $raw = (Get-Content -LiteralPath $PidFile -ErrorAction SilentlyContinue | Select-Object -First 1)
    $processId = 0
    if (-not [int]::TryParse([string]$raw, [ref]$processId)) {
        Write-Warning "Removing invalid SDV $Kind PID file: $PidFile"
        Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
        return
    }

    $owned = Get-SdvOwnedProcess -ProcessId $processId -Kind $Kind
    if ($owned) {
        throw "SDV $Kind is already running as PID $processId."
    }

    $other = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
    if ($other) {
        Write-Warning "Ignoring stale SDV $Kind PID $processId because it now belongs to '$($other.Name)'. No unrelated process will be terminated."
    } else {
        Write-Host "Removing stale SDV $Kind PID file for exited PID $processId."
    }

    Remove-Item -LiteralPath $PidFile -Force -ErrorAction SilentlyContinue
}

function Stop-OwnedProcess {
    param(
        [int]$ProcessId,
        [ValidateSet('backend','frontend')]
        [string]$Kind
    )

    $owned = Get-SdvOwnedProcess -ProcessId $ProcessId -Kind $Kind
    if (-not $owned) { return }

    & taskkill.exe /PID $ProcessId /T /F *> $null
    if ($LASTEXITCODE -ne 0) {
        Stop-Process -Id $ProcessId -Force -ErrorAction SilentlyContinue
    }
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) { throw 'Docker is required on PATH.' }
if (-not (Test-Path $Jar)) { throw "Backend jar not found. Run .\build-all.cmd first.`n$Jar" }
if (-not (Test-Path (Join-Path $Frontend 'node_modules'))) { throw 'Frontend dependencies not installed. Run .\build-all.cmd first.' }

New-Item -ItemType Directory -Force -Path $Runtime | Out-Null

Write-Host 'Starting isolated SDV PostgreSQL...'
& docker compose -f (Join-Path $Root 'compose.local.yml') up -d sdv-postgres
if ($LASTEXITCODE -ne 0) { throw 'Could not start sdv-postgres.' }

$healthy = $false
for ($i = 0; $i -lt 40; $i++) {
    $health = (& docker inspect --format='{{.State.Health.Status}}' sdv-postgres 2>$null | Out-String).Trim()
    if ($health -eq 'healthy') { $healthy = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $healthy) { throw 'sdv-postgres did not become healthy.' }


if ($WithVault) {
    Write-Host 'Starting local-only Vault dev service for SDV document crypto...'
    & docker compose -f (Join-Path $Root 'compose.local.yml') up -d sdv-vault
    if ($LASTEXITCODE -ne 0) { throw 'Could not start sdv-vault.' }

    $vaultReady = $false
    for ($i = 0; $i -lt 60; $i++) {
        try {
            $vaultHealth = Invoke-WebRequest -Uri 'http://127.0.0.1:8200/v1/sys/health' -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
            if ($vaultHealth.StatusCode -eq 200) {
                $vaultReady = $true
                break
            }
        } catch {
            # Vault is expected to refuse connections briefly while the dev server boots.
        }
        Start-Sleep -Seconds 1
    }
    if (-not $vaultReady) {
        $vaultLogs = (& docker logs sdv-vault --tail 80 2>&1 | Out-String)
        throw "sdv-vault did not become healthy on http://127.0.0.1:8200.`n$vaultLogs"
    }
    Write-Host 'Vault health is UP.'

    # Make local Vault startup idempotent. A reused dev container may already
    # have Transit mounted; calling `vault secrets enable transit` again writes
    # an expected error to stderr, which PowerShell can promote to a terminating
    # NativeCommandError before LASTEXITCODE can be inspected.
    $secrets = (& docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=sdv-local-root sdv-vault vault secrets list -format=json 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0) {
        throw 'Could not inspect Vault secret-engine mounts for local SDV runtime.'
    }

    if ($secrets -notmatch '"transit/"') {
        & docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=sdv-local-root sdv-vault vault secrets enable transit *> $null
        if ($LASTEXITCODE -ne 0) {
            throw 'Could not enable Vault Transit for local SDV runtime.'
        }
    }
    Write-Host 'Vault Transit is ready.'
}

Remove-StalePidFile -PidFile $BackendPid -Kind 'backend'

$backendListener = Get-NetTCPConnection -LocalPort 8280 -State Listen -ErrorAction SilentlyContinue
if ($backendListener) {
    $owner = @($backendListener | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
    throw "Port 8280 is already in use by PID(s) $owner. SDV will not terminate an unverified process."
}

$rootEscaped = $Root.Replace("'", "''")
$jarEscaped = $Jar.Replace("'", "''")
$keyProvider = if ($WithVault) { 'vault' } else { 'disabled' }
$backendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'SDV Backend - LOCAL'
Set-Location -LiteralPath '$rootEscaped'
`$env:SDV_KEY_PROVIDER = '$keyProvider'
`$env:SDV_VAULT_BASE_URL = 'http://127.0.0.1:8200'
`$env:SDV_VAULT_TOKEN = 'sdv-local-root'
Write-Host 'SDV backend LOCAL console. Close this window to stop the backend.' -ForegroundColor Cyan
java -jar '$jarEscaped' --spring.profiles.active=local
"@

Write-Host 'Starting SDV backend in a separate PowerShell window...'
$backendProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NoExit', '-Command', $backendCommand) -PassThru
Set-Content -Path $BackendPid -Value $backendProcess.Id -Encoding ASCII

Write-Host 'Waiting for SDV backend health...'
$backendUp = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://localhost:8280/actuator/health' -TimeoutSec 2
        if ($health.status -eq 'UP') { $backendUp = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $backendUp) {
    Stop-OwnedProcess -ProcessId $backendProcess.Id -Kind 'backend'
    Remove-Item -LiteralPath $BackendPid -Force -ErrorAction SilentlyContinue
    throw "SDV backend did not become healthy. Review the separate SDV Backend - LOCAL window."
}

Remove-StalePidFile -PidFile $FrontendPid -Kind 'frontend'

$frontendListener = Get-NetTCPConnection -LocalPort 5183 -State Listen -ErrorAction SilentlyContinue
if ($frontendListener) {
    $owner = @($frontendListener | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
    Stop-OwnedProcess -ProcessId $backendProcess.Id -Kind 'backend'
    Remove-Item -LiteralPath $BackendPid -Force -ErrorAction SilentlyContinue
    throw "Port 5183 is already in use by PID(s) $owner. SDV will not terminate an unverified process."
}

$frontendEscaped = $Frontend.Replace("'", "''")
$frontendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'SDV Frontend - LOCAL'
Set-Location -LiteralPath '$frontendEscaped'
Write-Host 'SDV frontend LOCAL console. Close this window to stop the frontend.' -ForegroundColor Cyan
 npm run dev -- --host 127.0.0.1
"@
Write-Host 'Starting SDV frontend in a separate PowerShell window...'
$frontendProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NoExit', '-Command', $frontendCommand) -PassThru
Set-Content -Path $FrontendPid -Value $frontendProcess.Id -Encoding ASCII

$frontendUp = $false
for ($i = 0; $i -lt 40; $i++) {
    try {
        $response = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 2
        if ($response.StatusCode -eq 200) { $frontendUp = $true; break }
    } catch { }
    Start-Sleep -Seconds 1
}
if (-not $frontendUp) {
    Stop-OwnedProcess -ProcessId $frontendProcess.Id -Kind 'frontend'
    Remove-Item -LiteralPath $FrontendPid -Force -ErrorAction SilentlyContinue
    Stop-OwnedProcess -ProcessId $backendProcess.Id -Kind 'backend'
    Remove-Item -LiteralPath $BackendPid -Force -ErrorAction SilentlyContinue
    throw "SDV frontend did not become reachable. Review the separate SDV Frontend - LOCAL window."
}

Write-Host ''
Write-Host 'SDV local services started.'
Write-Host 'Backend : http://localhost:8280'
Write-Host 'Health  : http://localhost:8280/actuator/health'
Write-Host 'System  : http://localhost:8280/api/v1/system/info'
Write-Host 'Frontend: http://localhost:5183'
Write-Host 'Postgres: localhost:5433 (container sdv-postgres)'
if ($WithVault) { Write-Host 'Vault   : localhost:8200 (LOCAL DEV ONLY; container sdv-vault)' }
Write-Host 'Logs    : separate Backend and Frontend console windows (LOCAL profile only)'
