<#
.SYNOPSIS
  A key service for the check scripts (task 0076): a scratch Vault in memory, never sdv-vault.

.DESCRIPTION
  Starts (or uses) the container vero-vault-checks on port 8210 of this computer, prepares it with
  Setup-SDV-VaultTransit.ps1 as the owner prepares the real one, and sets for this process only:
    SDV_KEY_SERVICE_ADDRESS, SDV_VAULT_LOGIN_FILE      how Vero signs in (may use keys, not make them);
    PLATFORM_SETUP_VAULT_ADDR, _TOKEN_FILE, _TRANSIT_MOUNT   how the setup command makes the customer's keys.
  Its keys are gone when the container stops, and with them every document encrypted with them: for checks only. Its login
  files are under .runtime\scratch-vault. Remove it with: docker rm -f vero-vault-checks
#>
[CmdletBinding()]
param([string]$Root = (Split-Path -Parent (Split-Path -Parent $PSScriptRoot)))
$ErrorActionPreference = 'Stop'
$name = 'vero-vault-checks'
$address = 'http://127.0.0.1:8210'
$folder = Join-Path $env:LOCALAPPDATA 'vero-checks'  # never inside the repository
New-Item -ItemType Directory -Force -Path $folder | Out-Null
$rootFile = Join-Path $folder 'root.token'
$loginFile = Join-Path $folder 'login.properties'
$setupFile = Join-Path $folder 'setup.token'

function Call([string]$Method, [string]$Path, $Body = $null) {
    $json = $null; if ($null -ne $Body) { $json = ConvertTo-Json -InputObject $Body -Depth 5 -Compress }
    Invoke-RestMethod -Uri "$address/v1/$Path" -Method $Method -Headers @{ 'X-Vault-Token' = ([IO.File]::ReadAllText($rootFile).Trim()) } -Body $json -ContentType 'application/json' -TimeoutSec 10
}

$state = (& docker ps -a --filter "name=^/$name$" --format '{{.State}}' | Out-String).Trim()
if ($state -eq '') {
    $token = ([guid]::NewGuid().ToString('N') + [guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllText($rootFile, $token)
    $env:VAULT_DEV_ROOT_TOKEN_ID = $token
    try { & docker run -d --name $name -p 127.0.0.1:8210:8200 -e VAULT_DEV_ROOT_TOKEN_ID --cap-add IPC_LOCK hashicorp/vault:1.17.6 server '-dev' | Out-Null }
    finally { Remove-Item Env:VAULT_DEV_ROOT_TOKEN_ID -ErrorAction SilentlyContinue }
    if ($LASTEXITCODE -ne 0) { throw "The scratch Vault could not be started." }
} elseif ($state -ne 'running') {
    & docker start $name | Out-Null
    if ($LASTEXITCODE -ne 0) { throw "The scratch Vault could not be started." }
}
$up = $false
for ($i = 0; $i -lt 60; $i++) { try { if ((Invoke-WebRequest "$address/v1/sys/health" -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $up = $true; break } } catch { }; Start-Sleep -Milliseconds 500 }
if (-not $up) { throw 'The scratch Vault did not answer.' }

# A restarted one is empty: what the last one's login said is worth nothing then.
$mounts = Call 'GET' 'sys/mounts'
if ($null -eq $mounts.PSObject.Properties['vero-transit/'] -and $null -eq $mounts.data.PSObject.Properties['vero-transit/']) {
    Remove-Item -LiteralPath $loginFile -ErrorAction SilentlyContinue
}
$env:VAULT_TOKEN = [IO.File]::ReadAllText($rootFile).Trim()
try {
    $arguments = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $Root 'Setup-SDV-VaultTransit.ps1'), '-VaultAddress', $address, '-OutFile', $loginFile)
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & powershell.exe @arguments *> (Join-Path $folder 'prepare.log')
    $code = $LASTEXITCODE; $ErrorActionPreference = $before
} finally { Remove-Item Env:VAULT_TOKEN -ErrorAction SilentlyContinue }
if ($code -ne 0) { throw "The scratch Vault could not be prepared; see $folder\prepare.log." }
$setup = Call 'POST' 'auth/token/create' @{ policies = @('platform-crypto-setup'); ttl = '8h'; no_default_policy = $true }
[IO.File]::WriteAllText($setupFile, $setup.auth.client_token)

$env:SDV_KEY_SERVICE_ADDRESS = $address
$env:SDV_VAULT_LOGIN_FILE = ($loginFile -replace '\\', '/')
$env:PLATFORM_SETUP_VAULT_ADDR = $address
$env:PLATFORM_SETUP_VAULT_TOKEN_FILE = $setupFile
$env:PLATFORM_SETUP_VAULT_TRANSIT_MOUNT = 'vero-transit'
Write-Host "scratch key service: $address (container $name)"
