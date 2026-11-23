[CmdletBinding()]
param(
    [string]$IssuerUri = 'http://localhost:8181/realms/document-platform-local',
    [string]$JwkSetUri = '',
    [string]$Audience = 'sdv-api',
    # Task 0041: with the issuer and the audience, the provider ID finds the customer's login connection. It is the
    # sign-in file's "providerId" (dev\setup\signin-vero-demo.json: customer-oidc).
    [string]$ProviderId = 'customer-oidc',
    [string]$WebClientId = 'sdv-web',
    [string]$WebRedirectUri = 'http://localhost:5183/',
    # Task 0041 (Part B 6): the persistent sdv-vault, as Start-SDV-Local.ps1 uses it. Only the path of the restricted
    # application token file is passed on, never the token. Default: SDV_VAULT_TOKEN_FILE, then ..\secrets\sdv-app.token
    # beside (never inside) the repository.
    [string]$VaultTokenFile = ''
)

# Local OIDC check (task 0041): Vero with OIDC sign-in against the local development login server (lexa-keycloak).
# Who may sign in, with which role in which entity, and which client may send for which entity, come from
# platform-core's idn_ tables: run .\Seed-SDV-Canvas-Demo.ps1 (or .\setup.ps1 with the dev\setup files) first. No
# group list, entity ID or tenant ID is set here any more.

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
$Jar = Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar'
$Frontend = Join-Path $Root 'frontend\sdv-ui'

if ([string]::IsNullOrWhiteSpace($IssuerUri)) { throw 'OIDC issuer URI is required.' }
if ([string]::IsNullOrWhiteSpace($JwkSetUri)) { $JwkSetUri = $IssuerUri.TrimEnd('/') + '/protocol/openid-connect/certs' }
if ([string]::IsNullOrWhiteSpace($Audience)) { throw 'OIDC API audience is required.' }
if ([string]::IsNullOrWhiteSpace($ProviderId)) { throw 'The provider ID (the sign-in file''s providerId) is required.' }

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

    if ($p.Name -notmatch '^powershell(\.exe)?$') { return $null }
    if ($Kind -eq 'backend') {
        if ($cmd -notmatch 'SDV Backend -') { return $null }
        return $p
    }
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

if (-not (Test-Path -LiteralPath $VaultTokenFile -PathType Leaf) -or (Get-Item -LiteralPath $VaultTokenFile).Length -eq 0) {
    throw "Application Vault token file is missing or empty: $VaultTokenFile"
}
$VaultTokenFile = (Resolve-Path -LiteralPath $VaultTokenFile).Path
$rootPrefix = $Root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
if ($VaultTokenFile.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) {
    throw 'The Vault token file must live outside the repository so it can never be committed.'
}

# Reuse the existing persistent database container. Compose is used only when no sdv-postgres container exists yet,
# so an existing one is never recreated.
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

# The persistent sdv-vault only: never Compose, never a dev-mode Vault, never initialized, reset or resealed here.
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
$vaultReady = $false
for ($i = 0; $i -lt 60; $i++) {
    try {
        $vaultHealth = Invoke-WebRequest -Uri "$VaultUrl/v1/sys/health" -UseBasicParsing -TimeoutSec 2 -ErrorAction Stop
        if ($vaultHealth.StatusCode -eq 200) { $vaultReady = $true; break }
    } catch {
        # Vault refuses connections briefly while it starts.
    }
    Start-Sleep -Seconds 1
}
if (-not $vaultReady) {
    throw 'sdv-vault is not healthy/unsealed. Unseal the existing persistent Vault, then retry. Do not reinitialize it.'
}
$sealState = Invoke-RestMethod -Uri "$VaultUrl/v1/sys/seal-status" -TimeoutSec 5
if (-not $sealState.initialized -or $sealState.sealed) {
    throw 'Vault is sealed or uninitialized. Unseal the existing persistent Vault, then retry. Do not reinitialize it.'
}
Write-Host 'Persistent Vault is up and unsealed.'

Remove-StalePidFile -PidFile $BackendPid -Kind 'backend'

$backendListener = Get-NetTCPConnection -LocalPort 8280 -State Listen -ErrorAction SilentlyContinue
if ($backendListener) {
    $owner = @($backendListener | Select-Object -ExpandProperty OwningProcess -Unique) -join ', '
    throw "Port 8280 is already in use by PID(s) $owner. SDV will not terminate an unverified process."
}

$rootEscaped = $Root.Replace("'", "''")
$jarEscaped = $Jar.Replace("'", "''")
$issuerEscaped = $IssuerUri.Replace("'", "''")
$jwkEscaped = $JwkSetUri.Replace("'", "''")
$audienceEscaped = $Audience.Replace("'", "''")
$providerEscaped = $ProviderId.Replace("'", "''")
$tokenFileEscaped = $VaultTokenFile.Replace("'", "''")
# The backend reads the restricted token from the file itself; the token is never on a command line or in a variable.
$backendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'SDV Backend - OIDC'
Set-Location -LiteralPath '$rootEscaped'
`$env:SDV_KEY_PROVIDER = 'vault'
`$env:SDV_VAULT_BASE_URL = '$VaultUrl'
Remove-Item Env:SDV_VAULT_TOKEN -ErrorAction SilentlyContinue
`$env:SDV_VAULT_TOKEN_FILE = '$tokenFileEscaped'
`$env:SDV_AUTH_MODE = 'enterprise-jwt'
`$env:SDV_AUTH_JWT_ISSUER = '$issuerEscaped'
`$env:SDV_AUTH_JWT_JWK_SET_URI = '$jwkEscaped'
`$env:SDV_AUTH_JWT_AUDIENCE = '$audienceEscaped'
`$env:SDV_AUTH_JWT_PROVIDER_ID = '$providerEscaped'
`$env:SDV_AUTH_JWT_GROUPS_CLAIM = 'groups'
Write-Host 'SDV backend LOCAL/OIDC console. Close this window to stop the backend.' -ForegroundColor Cyan
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
    throw "SDV backend did not become healthy. Review the separate SDV Backend - OIDC window (the database may need .\Update-SDV-Schema.ps1 update)."
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
$webClientEscaped = $WebClientId.Replace("'", "''")
$webRedirectEscaped = $WebRedirectUri.Replace("'", "''")
$frontendCommand = @"
`$Host.UI.RawUI.WindowTitle = 'SDV Frontend - OIDC'
Set-Location -LiteralPath '$frontendEscaped'
`$env:VITE_SECURITY_MODE = 'OIDC'
`$env:VITE_OIDC_ISSUER = '$issuerEscaped'
`$env:VITE_OIDC_CLIENT_ID = '$webClientEscaped'
`$env:VITE_OIDC_REDIRECT_URI = '$webRedirectEscaped'
`$env:VITE_OIDC_SCOPE = 'openid profile email'
Write-Host 'SDV frontend LOCAL/OIDC console. Close this window to stop the frontend.' -ForegroundColor Cyan
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
    throw "SDV frontend did not become reachable. Review the separate SDV Frontend - OIDC window."
}

Write-Host ''
Write-Host 'SDV OIDC local validation services started.'
Write-Host 'Backend : http://localhost:8280'
Write-Host 'Health  : http://localhost:8280/actuator/health'
Write-Host 'Frontend: http://localhost:5183'
Write-Host "OIDC    : $IssuerUri (provider $ProviderId, API audience $Audience, web client $WebClientId)"
Write-Host 'Entities: from setup (idn_ tables); run .\Seed-SDV-Canvas-Demo.ps1 if they are missing'
Write-Host 'Postgres: localhost:5433 (container sdv-postgres)'
Write-Host 'Vault   : localhost:8200 (persistent sdv-vault; token file only)'
Write-Host 'Logs    : separate Backend and Frontend console windows (LOCAL profile only)'
