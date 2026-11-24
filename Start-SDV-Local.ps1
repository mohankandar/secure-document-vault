[CmdletBinding()]
param(
    [switch]$WithVault = $true,
    # Restricted application Vault token file. Defaults to SDV_VAULT_TOKEN_FILE,
    # then to ..\secrets\sdv-app.token beside (never inside) the repository.
    [string]$VaultTokenFile = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$VaultUrl = 'http://127.0.0.1:8200'
if (-not $VaultTokenFile) {
    $VaultTokenFile = if ($env:SDV_VAULT_TOKEN_FILE) { $env:SDV_VAULT_TOKEN_FILE } else { Join-Path (Split-Path -Parent $Root) 'secrets\sdv-app.token' }
}
$Runtime = Join-Path $Root '.runtime'
$BackendPid = Join-Path $Runtime 'backend.pid'
$FrontendPid = Join-Path $Runtime 'frontend.pid'
$Jar = Join-Path $Root 'backend\sdv-web\target\local\sdv-web-0.1.0-SNAPSHOT-local.jar'
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

if ($WithVault) {
    if (-not (Test-Path -LiteralPath $VaultTokenFile -PathType Leaf) -or (Get-Item -LiteralPath $VaultTokenFile).Length -eq 0) {
        throw "Application Vault token file is missing or empty: $VaultTokenFile"
    }
    $VaultTokenFile = (Resolve-Path -LiteralPath $VaultTokenFile).Path
    $rootPrefix = $Root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($VaultTokenFile.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
        throw 'The Vault token file must live outside the repository so it can never be committed.'
    }
}

# Task 0064: the local database password comes from an untracked file, never from git. Compose and the backend window
# get it through the environment only.
$env:SDV_LOCAL_DB_PASSWORD = & (Join-Path $Root 'scripts\local\Get-SdvLocalDbPassword.ps1') -Root $Root

# Reuse the existing persistent database container. Compose is used only when
# no sdv-postgres container exists yet, so an existing one is never recreated.
$existingPostgres = (& docker ps -a --filter 'name=^/sdv-postgres$' --format '{{.Names}}' | Out-String).Trim()
if ($existingPostgres -eq 'sdv-postgres') {
    $postgresRunning = (& docker inspect --format='{{.State.Running}}' sdv-postgres | Out-String).Trim()
    if ($postgresRunning -ne 'true') {
        Write-Host 'Starting existing sdv-postgres container...'
        & docker start sdv-postgres | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start existing sdv-postgres container.' }
    } else {
        Write-Host 'Existing sdv-postgres container is already running.'
    }
} else {
    Write-Host 'Starting isolated SDV PostgreSQL...'
    & docker compose -f (Join-Path $Root 'compose.local.yml') up -d sdv-postgres
    if ($LASTEXITCODE -ne 0) { throw 'Could not start sdv-postgres.' }
}

$healthy = $false
for ($i = 0; $i -lt 40; $i++) {
    $health = (& docker inspect --format='{{.State.Health.Status}}' sdv-postgres 2>$null | Out-String).Trim()
    if ($health -eq 'healthy') { $healthy = $true; break }
    Start-Sleep -Seconds 1
}
if (-not $healthy) { throw 'sdv-postgres did not become healthy.' }


if ($WithVault) {
    # Never reconcile a persistent container using ephemeral dev-mode Compose.
    $existingVault = (& docker ps -a --filter 'name=^/sdv-vault$' --format '{{.Names}}' | Out-String).Trim()
    if ($existingVault -ne 'sdv-vault') {
        throw 'Persistent sdv-vault was not found. Configure and initialize it using compose.vault-persistent.yml first. Refusing ephemeral dev-mode startup.'
    }
    $vaultRunning = (& docker inspect --format='{{.State.Running}}' sdv-vault | Out-String).Trim()
    if ($vaultRunning -ne 'true') {
        Write-Host 'Starting existing persistent sdv-vault container...'
        & docker start sdv-vault | Out-Null
        if ($LASTEXITCODE -ne 0) { throw 'Could not start existing sdv-vault container.' }
    }
    Write-Host 'Checking existing Vault (without recreating it)...'

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
        throw "sdv-vault is not healthy/unsealed. Unseal the existing persistent Vault, then retry. Do not reinitialize it.`n$vaultLogs"
    }
    Write-Host 'Vault health is UP.'

    # Non-mutating preflight with the restricted application token, matching
    # scripts/sdv-vault-preflight.py: it checks seal state and that the token's
    # policy reaches the SDV Transit paths. It creates no keys, performs no
    # cryptographic operation and never prints the token.
    $sealState = Invoke-RestMethod -Uri "$VaultUrl/v1/sys/seal-status" -TimeoutSec 5
    if (-not $sealState.initialized -or $sealState.sealed) {
        throw 'Vault is sealed or uninitialized. Unseal the existing persistent Vault, then retry. Do not reinitialize it.'
    }
    $keyPrefix = if ($env:SDV_VAULT_KEY_PREFIX) { $env:SDV_VAULT_KEY_PREFIX } else { 'sdv-tenant-' }
    $transitMount = if ($env:SDV_VAULT_TRANSIT_MOUNT) { $env:SDV_VAULT_TRANSIT_MOUNT.Trim('/') } else { 'transit' }
    if (-not $transitMount -or -not $keyPrefix -or $keyPrefix.Contains('/')) {
        throw 'Invalid SDV Transit mount or tenant-key prefix configuration.'
    }
    $appToken = (Get-Content -LiteralPath $VaultTokenFile -Raw -Encoding UTF8).Trim()
    if (-not $appToken) { throw 'Application Vault token file is empty.' }
    try {
        foreach ($operation in @('keys', 'datakey/plaintext', 'decrypt')) {
            $target = "$transitMount/$operation/${keyPrefix}preflight"
            $mountType = $null
            try {
                $mount = Invoke-RestMethod -Uri "$VaultUrl/v1/sys/internal/ui/mounts/$target" -Headers @{ 'X-Vault-Token' = $appToken } -TimeoutSec 5
                $mountType = $mount.data.type
            } catch {
                throw "Vault Transit ACL preflight denied or unavailable for '$operation'. Check the application token and its policy; do not change the Vault ACL to work around this."
            }
            if ($mountType -ne 'transit') {
                throw "Vault Transit mount is missing or inaccessible for '$operation'."
            }
        }
    } finally {
        $appToken = $null
    }
    Write-Host 'Vault preflight PASS: unsealed, existing Transit mount and SDV policy paths accessible.'
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
# The backend reads the restricted token from the file itself. The token value
# is never placed on a command line or in an environment variable.
$tokenFileLine = if ($WithVault) { "`$env:SDV_VAULT_TOKEN_FILE = '" + $VaultTokenFile.Replace("'", "''") + "'" } else { '' }
$backendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'SDV Backend - LOCAL'
Set-Location -LiteralPath '$rootEscaped'
`$env:SDV_KEY_PROVIDER = '$keyProvider'
`$env:SDV_VAULT_BASE_URL = '$VaultUrl'
Remove-Item Env:SDV_VAULT_TOKEN -ErrorAction SilentlyContinue
$tokenFileLine
Write-Host 'SDV backend LOCAL console. Close this window to stop the backend.' -ForegroundColor Cyan
java -jar '$jarEscaped' --spring.profiles.active=local
"@

Write-Host 'Starting SDV backend in a separate PowerShell window...'
$backendProcess = Start-Process -FilePath 'powershell.exe' -ArgumentList @('-NoProfile', '-NoExit', '-Command', $backendCommand) -PassThru
Remove-Item Env:SDV_LOCAL_DB_PASSWORD -ErrorAction SilentlyContinue
Set-Content -Path $BackendPid -Value $backendProcess.Id -Encoding ASCII

Write-Host 'Waiting for SDV backend health...'
$backendUp = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $health = Invoke-RestMethod -Uri 'http://127.0.0.1:8280/actuator/health' -TimeoutSec 2
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
        $response = Invoke-WebRequest -Uri 'http://127.0.0.1:5183' -UseBasicParsing -TimeoutSec 2
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
