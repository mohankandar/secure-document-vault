<#
.SYNOPSIS
  Vero for the owner to test, with one command (task 0095): demo data, the real sign-in, the screens.

.DESCRIPTION
      .\Start-SDV-OwnerTest.ps1

  Local computer only. What is there already is used as it is; nothing is ever dropped, removed or reset:
    1. starts the containers sdv-postgres, sdv-vault and lexa-keycloak (with its database) when they are stopped;
    2. builds Vero when the last build is not of this version;
    3. makes the database sdv_vero when it is missing, and runs the schema step;
    4. the first time, makes the customer's keys and loads the demo customer (setup), and asks for a Vault token with the policy
       platform-crypto-setup; the token goes to setup only and is never stored or printed;
    5. the first time, fills the demo data (Vero runs for that on port 8291, without sign-in, for a minute);
    6. starts Vero on port 8280 with the sign-in of Keycloak (client sdv-web) and the screens on port 5183.
  Who to sign in as and what to try: docs\OWNER-TEST-GUIDE.md. Stop it with .\Stop-SDV-OwnerTest.ps1.

  -Database and -ScratchKeyService are for proving this script: another database, and the check scripts' key service.
#>
[CmdletBinding()]
param(
    [ValidatePattern('^sdv_[a-z0-9_]+$')][string]$Database = 'sdv_vero',
    [switch]$ScratchKeyService,
    # Only the file's keys are looked at (is there such a test person?), never a password.
    [string]$UsersFile = 'C:\dev\sdv\dev-test-users.properties'
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = $PSScriptRoot
$issuer = 'http://localhost:8181/realms/document-platform-local'
$jar = Join-Path $root 'backend\sdv-web\target\local\sdv-web-0.1.0-SNAPSHOT-local.jar'
$ui = Join-Path $root 'frontend\sdv-ui'
$run = Join-Path $root '.runtime\owner-test'
$state = Join-Path $run 'running.json'
New-Item -ItemType Directory -Force -Path $run | Out-Null

function Say([string]$Text) { Write-Host "  $Text" }
function Stopped([string]$Why) { Write-Host "STOPPED: $Why" -ForegroundColor Yellow; exit 2 }
function Sql([string]$In, [string]$Text) {
    $out = $Text | & docker exec -i sdv-postgres psql -X -q -tA -v ON_ERROR_STOP=1 -U sdv -d $In
    if ($LASTEXITCODE -ne 0) { throw "psql failed on $In" }
    ($out | Out-String).Trim()
}
function Answers([string]$Url) { try { (Invoke-WebRequest $Url -UseBasicParsing -TimeoutSec 5).StatusCode -eq 200 } catch { $false } }
function Wait-Until([scriptblock]$Until, [int]$Seconds) { for ($i = 0; $i -lt $Seconds; $i++) { if (& $Until) { return $true }; Start-Sleep -Seconds 1 }; $false }
function Listening([int]$Port) { [bool](Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) }
# A step in its own window, its output in a log; a native program's stderr must not stop this script.
function Invoke-Step([string]$What, [string]$Log, [string]$Script, [string[]]$Arguments) {
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root $Script) @Arguments *> (Join-Path $run $Log)
    $code = $LASTEXITCODE; $ErrorActionPreference = $before
    if ($code -ne 0) { throw "$What failed (exit $code). Log: $(Join-Path $run $Log)" }
}
function Start-Vero([string]$Log, [int]$Port) {
    $p = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $root `
        -ArgumentList @('-jar', "`"$jar`"", '--spring.profiles.active=local', "--server.port=$Port", "--spring.datasource.url=jdbc:postgresql://localhost:5433/$Database") `
        -RedirectStandardOutput (Join-Path $run "$Log.out.log") -RedirectStandardError (Join-Path $run "$Log.err.log")
    $up = Wait-Until { $p.HasExited -or (Answers "http://127.0.0.1:$Port/actuator/health/readiness") } 150
    if (-not $up -or $p.HasExited) {
        if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force }
        throw "Vero did not start. Log: $(Join-Path $run "$Log.out.log")"
    }
    $p
}

Write-Host "Vero owner test (database $Database)"

# ---- 1. What is needed
if (Test-Path -LiteralPath $state) {
    $was = Get-Content -Raw -LiteralPath $state | ConvertFrom-Json
    $alive = @($was.backend, $was.frontend | Where-Object { Get-Process -Id $_ -ErrorAction SilentlyContinue })
    if ($alive.Count -eq 2 -and (Answers 'http://127.0.0.1:8280/actuator/health/readiness') -and (Listening 5183)) {
        Say "already running on database $($was.database): nothing started"
        Write-Host 'Open http://localhost:5183/' -ForegroundColor Green
        exit 0
    }
}
foreach ($p in 8280, 5183, 8291) { if (Listening $p) { Stopped "port $p is in use. Stop what uses it (.\Stop-SDV-OwnerTest.ps1, or .\stop-local.cmd for an earlier local start) and run this again." } }
if (-not (Test-Path -LiteralPath $UsersFile -PathType Leaf)) { Stopped "the test people are not made yet. Run .\Set-SDV-DevKeycloak.ps1 once (it asks for the Keycloak admin), then this again." }
foreach ($person in 'PLATFORM_OPERATIONS', 'BUSINESS_ADMIN_2') {
    if (-not (Select-String -LiteralPath $UsersFile -Pattern "^$person\.username=" -Quiet)) { Write-Warning "Test person $person is missing: run .\Set-SDV-DevKeycloak.ps1 once more. The other people work." }
}
$containers = @('sdv-postgres', 'lexa-keycloak-db', 'lexa-keycloak') + @(if (-not $ScratchKeyService) { 'sdv-vault' })
foreach ($name in $containers) {
    $s = (& docker ps -a --filter "name=^/$name$" --format '{{.State}}' | Out-String).Trim()
    if ($s -eq '') { Stopped "there is no container $name. This script never makes one." }
    if ($s -eq 'running') { Say "${name}: running" }
    else { & docker start $name | Out-Null; if ($LASTEXITCODE -ne 0) { throw "$name could not be started." }; Say "${name}: started" }
}
if (-not (Wait-Until { & docker exec sdv-postgres pg_isready -U sdv -q; $LASTEXITCODE -eq 0 } 60)) { throw 'PostgreSQL does not answer.' }
if (-not (Wait-Until { Answers "$issuer/.well-known/openid-configuration" } 120)) { throw "Keycloak does not answer at $issuer." }
if ($ScratchKeyService) { & (Join-Path $root 'scripts\local\Use-SdvScratchVault.ps1') }
else {
    $sealed = $null
    $null = Wait-Until { try { $script:sealed = (Invoke-RestMethod 'http://127.0.0.1:8200/v1/sys/seal-status' -TimeoutSec 5).sealed; $true } catch { $false } } 30
    if ($null -eq $script:sealed) { throw 'Vault does not answer on port 8200.' }
    if ($script:sealed) { Stopped 'Vault is sealed. Unseal it (docs\OWNER-TEST-GUIDE.md, "After a computer restart"), then run this again.' }
    Say 'Vault: unsealed'
}

# ---- 2. Build
$version = ((& git -C $root rev-parse 'HEAD:backend' 'HEAD:frontend') -join ' ') + ' ' + (& git -C $root status --porcelain -- backend frontend | Out-String).GetHashCode()
$built = Join-Path $run 'built.txt'
if ((Test-Path -LiteralPath $jar) -and (Test-Path -LiteralPath (Join-Path $ui 'node_modules')) -and (Test-Path -LiteralPath $built) -and (Get-Content -LiteralPath $built -TotalCount 1) -eq $version) { Say 'build: this version is built already' }
else {
    Say 'build: running (a few minutes)'
    Invoke-Step 'The build' 'build.log' 'Build-SDV-All.ps1' @()
    Set-Content -LiteralPath $built -Encoding ascii -Value $version
    Say 'build: done'
}

# ---- 3. Database
$password = & (Join-Path $root 'scripts\local\Get-SdvLocalDbPassword.ps1') -Root $root
if ([int](Sql 'postgres' "select count(*) from pg_database where datname = '$Database';") -eq 0) { $null = Sql 'postgres' "create database $Database owner sdv;"; Say "database ${Database}: made" }
else { Say "database ${Database}: there already" }
$settings = 'SDV_SCHEMA_DB_PASSWORD', 'SDV_LOCAL_DB_PASSWORD', 'SDV_AUTH_MODE', 'SDV_AUTH_JWT_ISSUER', 'SDV_AUTH_JWT_JWK_SET_URI', 'SDV_AUTH_JWT_AUDIENCE', 'SDV_AUTH_JWT_PROVIDER_ID', 'SDV_API_TARGET', 'VITE_SECURITY_MODE', 'PLATFORM_SETUP_VAULT_ADDR', 'PLATFORM_SETUP_VAULT_TRANSIT_MOUNT', 'PLATFORM_SETUP_VAULT_TOKEN', 'PLATFORM_SETUP_VAULT_TOKEN_FILE', 'SDV_KEY_SERVICE_ADDRESS', 'SDV_VAULT_LOGIN_FILE'
$before = @{}; foreach ($n in $settings) { $before[$n] = [Environment]::GetEnvironmentVariable($n) }
try {
    $env:SDV_SCHEMA_DB_PASSWORD = $password
    try { Invoke-Step 'The schema step' 'schema.log' 'Update-SDV-Schema.ps1' @('update', '-Url', "jdbc:postgresql://localhost:5433/$Database", '-Username', 'sdv') }
    finally { Remove-Item Env:SDV_SCHEMA_DB_PASSWORD -ErrorAction SilentlyContinue }
    Say "schema step: $((Get-Content -LiteralPath (Join-Path $run 'schema.log') | Where-Object { $_ -match '^(Up to date|Done)' } | Select-Object -Last 1))"

    # ---- 4. Setup
    # Setup and the demo data (which runs setup again) need the token: asked for once, and only in the environment of those two.
    $script:token = $null
    function Invoke-SetupStep([string]$What, [string]$Log, [string]$Script, [string[]]$Arguments) {
        if ($ScratchKeyService) { Invoke-Step $What $Log $Script $Arguments; return }
        if ($null -eq $script:token) {
            Write-Host '  setup makes the customer''s keys. docs\OWNER-TEST-GUIDE.md says how to get the token.'
            $script:token = Read-Host '  Vault token with the policy platform-crypto-setup (not shown)' -AsSecureString
        }
        if ($script:token.Length -eq 0) { throw 'No token was given.' }
        $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($script:token)
        try {
            $env:PLATFORM_SETUP_VAULT_ADDR = 'http://127.0.0.1:8200'; $env:PLATFORM_SETUP_VAULT_TRANSIT_MOUNT = 'vero-transit'
            $env:PLATFORM_SETUP_VAULT_TOKEN = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer)
            Invoke-Step $What $Log $Script $Arguments
        } finally {
            [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer)
            Remove-Item Env:PLATFORM_SETUP_VAULT_TOKEN -ErrorAction SilentlyContinue
        }
    }
    $isSetUp = (Sql $Database "select (select count(*) from idn_customer) > 0 and (select count(*) from lic_licence) > 0 and (select count(*) from tenants) > 0;") -eq 't'
    if ($isSetUp) { Say 'setup: done before' }
    else {
        Invoke-SetupStep 'Setup' 'setup.log' 'Seed-SDV-Canvas-Demo.ps1' @('-SetupOnly', '-Database', $Database)
        Say 'setup: demo customer, licence and keys made'
    }

    # ---- 5. Demo data
    $env:SDV_LOCAL_DB_PASSWORD = $password
    $seeded = Join-Path $run "seeded-$Database-$(Sql 'postgres' "select oid from pg_database where datname = '$Database';").txt"
    if (Test-Path -LiteralPath $seeded) { Say 'demo data: there already' }
    else {
        $env:SDV_AUTH_MODE = 'local-header'
        $seedVero = Start-Vero 'seed-vero' 8291
        try {
            $more = @(if ($Database -ne 'sdv_vero') { '-StorageFolder', (Join-Path $run "$Database-storage-a"), '-StandbyStorageFolder', (Join-Path $run "$Database-storage-b") })
            Invoke-SetupStep 'The demo data' 'seed.log' 'Seed-SDV-Canvas-Demo.ps1' (@('-Base', 'http://127.0.0.1:8291', '-Database', $Database) + $more)
        } finally { if (-not $seedVero.HasExited) { Stop-Process -Id $seedVero.Id -Force; $null = $seedVero.WaitForExit(30000) } }
        Set-Content -LiteralPath $seeded -Encoding ascii -Value (Get-Date -Format s)
        Say 'demo data: made'
    }

    if ($script:token) { $script:token.Dispose() }
    Remove-Item Env:PLATFORM_SETUP_VAULT_TOKEN_FILE -ErrorAction SilentlyContinue

    # ---- 6. Vero and the screens
    $env:SDV_AUTH_MODE = 'enterprise-jwt'; $env:SDV_AUTH_JWT_ISSUER = $issuer; $env:SDV_AUTH_JWT_JWK_SET_URI = "$issuer/protocol/openid-connect/certs"
    $env:SDV_AUTH_JWT_AUDIENCE = 'sdv-api'; $env:SDV_AUTH_JWT_PROVIDER_ID = 'customer-oidc'
    $vero = Start-Vero 'vero' 8280
    Say 'Vero: started on port 8280'
    $env:SDV_API_TARGET = 'http://127.0.0.1:8280'
    $env:VITE_SECURITY_MODE = 'OIDC' # or the development screens use the local test sign-in
    Remove-Item Env:SDV_LOCAL_DB_PASSWORD -ErrorAction SilentlyContinue
    $screens = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'npm', 'run', 'dev') -WorkingDirectory $ui -PassThru -WindowStyle Hidden `
        -RedirectStandardOutput (Join-Path $run 'screens.out.log') -RedirectStandardError (Join-Path $run 'screens.err.log')
    [pscustomobject]@{ backend = $vero.Id; frontend = $screens.Id; database = $Database } | ConvertTo-Json | Set-Content -LiteralPath $state -Encoding ascii
    if (-not (Wait-Until { Listening 5183 } 60)) { throw "The screens did not start. Log: $(Join-Path $run 'screens.out.log')" }
    Say 'screens: started on port 5183'
} finally { foreach ($n in $settings) { [Environment]::SetEnvironmentVariable($n, $before[$n]) } }

Write-Host ''
Write-Host 'Open http://localhost:5183/' -ForegroundColor Green
Write-Host "Test people and passwords: $UsersFile. What to try: docs\OWNER-TEST-GUIDE.md. Stop: .\Stop-SDV-OwnerTest.ps1"
exit 0
