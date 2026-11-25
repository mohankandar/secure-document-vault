<#
.SYNOPSIS
  The real browser sign-in check (task 0088): Vero on a scratch database, the local Keycloak, a real browser.

.DESCRIPTION
  Needs a build (Build-SDV-All.ps1), the test people of Set-SDV-DevKeycloak.ps1 (the owner's step) and the ports 5183 and
  -Port free. Makes the database sdv_keycloak_test in sdv-postgres, seeds it, starts Vero in company sign-in mode and drops
  the database at the end. Never the sdv database. Nothing is changed in Keycloak. No password and no token is printed.

.EXAMPLE
  .\Test-SDV-KeycloakLive.ps1
#>
[CmdletBinding()]
param(
    [string]$UsersFile = 'C:\dev\sdv\dev-test-users.properties',
    # Another local Keycloak only to prove this script; the scratch database is then pointed at it.
    [ValidatePattern('^http://localhost:\d{2,5}$')][string]$Keycloak = 'http://localhost:8181',
    [int]$Port = 8292
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = $PSScriptRoot
$db = 'sdv_keycloak_test'
$issuer = "$Keycloak/realms/document-platform-local"
$jar = Join-Path $root 'backend\sdv-web\target\local\sdv-web-0.1.0-SNAPSHOT-local.jar'
$ui = Join-Path $root 'frontend\sdv-ui'
$run = Join-Path $root '.runtime\keycloak-live'

if (-not (Test-Path -LiteralPath $UsersFile -PathType Leaf)) {
    Write-Host "STOPPED: $UsersFile isn't there. The owner's step is open: .\Set-SDV-DevKeycloak.ps1" -ForegroundColor Yellow
    exit 2
}
if (-not (Test-Path -LiteralPath $jar) -or -not (Test-Path -LiteralPath (Join-Path $ui 'dist\index.html'))) { throw 'Build first: .\Build-SDV-All.ps1' }
foreach ($p in 5183, $Port) { if (Get-NetTCPConnection -LocalPort $p -State Listen -ErrorAction SilentlyContinue) { throw "Port $p is in use. Stop what uses it (.\stop-local.cmd for Vero) and run this again." } }
try { $null = Invoke-RestMethod "$issuer/.well-known/openid-configuration" -TimeoutSec 10 } catch { throw "Keycloak doesn't answer at $issuer." }
New-Item -ItemType Directory -Force -Path $run | Out-Null

function Sql([string]$Database, [string]$Text) {
    $out = $Text | & docker exec -i sdv-postgres psql -X -q -tA -v ON_ERROR_STOP=1 -U sdv -d $Database
    if ($LASTEXITCODE -ne 0) { throw 'psql failed' }
    ($out | Out-String).Trim()
}
$password = & (Join-Path $root 'scripts\local\Get-SdvLocalDbPassword.ps1') -Root $root
if ([int](Sql 'postgres' "select count(*) from pg_database where datname = '$db';") -gt 0) { throw "The database $db is there already (an earlier run?). Look at it and drop it first." }

$script:app = $null
$settings = 'SDV_KEY_SERVICE_ADDRESS', 'SDV_ERASE_LOG_FOLDER', 'SDV_VAULT_LOGIN_FILE', 'PLATFORM_SETUP_VAULT_ADDR', 'PLATFORM_SETUP_VAULT_TOKEN_FILE', 'PLATFORM_SETUP_VAULT_TRANSIT_MOUNT', 'SDV_LOCAL_DB_PASSWORD', 'SDV_AUTH_MODE', 'SDV_AUTH_JWT_ISSUER', 'SDV_AUTH_JWT_JWK_SET_URI', 'SDV_AUTH_JWT_AUDIENCE', 'SDV_AUTH_JWT_PROVIDER_ID', 'SDV_AUTH_BROWSER_CLIENT_ID', 'SDV_SCHEMA_DB_PASSWORD'
$before = @{}; foreach ($n in $settings) { $before[$n] = [Environment]::GetEnvironmentVariable($n) }
function Start-Vero([string]$Name) {
    $script:app = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $root -ArgumentList @('-jar', "`"$jar`"", '--spring.profiles.active=local', "--server.port=$Port", "--spring.datasource.url=jdbc:postgresql://localhost:5433/$db") -RedirectStandardOutput (Join-Path $run "$Name.out.log") -RedirectStandardError (Join-Path $run "$Name.err.log")
    for ($i = 0; $i -lt 120; $i++) {
        try { if ((Invoke-RestMethod "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 2).status -eq 'UP') { Write-Host "Vero ($Name): up"; return } } catch { }
        if ($script:app.HasExited) { break }
        Start-Sleep -Milliseconds 800
    }
    throw "Vero ($Name) did not start; see $run."
}
function Stop-Vero { if ($script:app -and -not $script:app.HasExited) { Stop-Process -Id $script:app.Id -Force; $null = $script:app.WaitForExit(30000) } }

$code = 1
try {
    & (Join-Path $root 'scripts\local\Use-SdvScratchVault.ps1')
    # Task 0080: the scratch database's own erase log.
    $env:SDV_ERASE_LOG_FOLDER = (Join-Path $run 'erase-log')
    $null = Sql 'postgres' "create database $db owner sdv;"
    $env:SDV_SCHEMA_DB_PASSWORD = $password
    $ErrorActionPreference = 'Continue'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'Update-SDV-Schema.ps1') update -Url "jdbc:postgresql://localhost:5433/$db" -Username sdv *> (Join-Path $run 'schema.log')
    $step = $LASTEXITCODE; $ErrorActionPreference = 'Stop'
    if ($step -ne 0) { throw "The schema step on the scratch database failed; see $run." }
    $ErrorActionPreference = 'Continue'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'Seed-SDV-Canvas-Demo.ps1') -SetupOnly -Database $db *> (Join-Path $run 'setup.log')
    $step = $LASTEXITCODE; $ErrorActionPreference = 'Stop'
    if ($step -ne 0) { throw "Setup on the scratch database failed; see $run." }
    $env:SDV_LOCAL_DB_PASSWORD = $password
    Start-Vero 'seed'
    $ErrorActionPreference = 'Continue'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'Seed-SDV-Canvas-Demo.ps1') -Base "http://127.0.0.1:$Port" -Database $db -StorageFolder (Join-Path $run 'storage-a') -StandbyStorageFolder (Join-Path $run 'storage-b') *> (Join-Path $run 'seed.log')
    $step = $LASTEXITCODE; $ErrorActionPreference = 'Stop'
    if ($step -ne 0) { throw "The demo seed failed; see $run." }
    Write-Host 'demo seed: ok'
    Stop-Vero
    if ($Keycloak -ne 'http://localhost:8181') { $null = Sql $db "begin; update idn_login_connection set issuer_uri = '$issuer'; update idn_group_grant set issuer_uri = '$issuer'; commit;" }
    $env:SDV_AUTH_MODE = 'enterprise-jwt'; $env:SDV_AUTH_JWT_ISSUER = $issuer; $env:SDV_AUTH_JWT_JWK_SET_URI = "$issuer/protocol/openid-connect/certs"
    $env:SDV_AUTH_JWT_AUDIENCE = 'sdv-api'; $env:SDV_AUTH_JWT_PROVIDER_ID = 'customer-oidc'
    Start-Vero 'company-sign-in'
    $ErrorActionPreference = 'Continue'
    Push-Location $ui
    try { & node (Join-Path $ui 'e2e\keycloak-live.mjs') $UsersFile $issuer $Port; $code = $LASTEXITCODE } finally { Pop-Location }
    $ErrorActionPreference = 'Stop'
} finally {
    Stop-Vero
    foreach ($n in $settings) { [Environment]::SetEnvironmentVariable($n, $before[$n]) }
    $null = Sql 'postgres' "drop database if exists $db with (force);"
    Write-Host "dropped $db"
}
exit $code
