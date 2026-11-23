<#
.SYNOPSIS
  Repeatable clean-install check for the separate schema step (task 0013).

.DESCRIPTION
  Uses a scratch database, sdv_install_test, inside the existing sdv-postgres server, and two scratch roles:
    sdv_install_owner  owns the database; the schema step runs as this user
    sdv_install_app    the running app's user: connect, and select/insert/update/delete on tables only
  The passwords are random for each run, held only in memory and never printed.
  The script never touches the sdv database and never creates, removes or recreates a container.
  Everything it creates is dropped at the end, also when a step fails.

  Steps:
    0. Task 0037: a packaged start (no local profile) with sign-in or the key service switched off refuses to start,
       with SDV-SYS-006 or SDV-KEY-001 and no stack trace, and never opens its port.
    1. Start Vero (no local profile, production context) against the empty database: it refuses with the "older" message.
       Task 0041: the schema step also runs platform-core's identity and licensing tables (their own Flyway runs).
    2. Schema step: status, sql (to a file, changes nothing), update.
    3. Start again: it serves, as the read/write user, and the tenants table is empty (no seed data).
    4. Start again: nothing new is applied, and the start is quick.
    5. Task 0037: start with the database unreachable, then bring it up: Vero serves without a restart. Then take it
       away and bring it back again: readiness turns not ready and ready again. "The database" is a local TCP relay
       (test-tools\TcpRelay.java) in front of sdv-postgres; the container itself is never stopped. Task 0039 part 2:
       at start without the database, as later, only readiness is 503; liveness and the overall health are 200. With
       SDV_TRUSTED_PROXIES not set, X-Forwarded-For is ignored.
    6. Task 0038: start with the sign-in hub's keys away: readiness 503, a signed-in request 503 SDV-AUT-004. Then
       bring the keys back: ready again without a restart, and the same request gets 401 SDV-AUT-006 (no sign-in
       connection is set up). "The sign-in hub" is a local key set (test-tools\KeySetServer.java) with a test key made
       for the run. Task 0039 part 2: this start trusts this computer as a proxy (SDV_TRUSTED_PROXIES); 1,000 requests
       without sign-in in under a minute, "from" 198.51.100.7, are at most two rows (a minute may turn) counting 1,000.

  Since task 0037 a packaged start needs sign-in and a key service. The starts here use placeholder settings: an issuer
  under the reserved .invalid domain (never contacted), and a key service address on this computer where nothing
  listens. The placeholder token file holds no secret. Task 0038: readiness fetches the sign-in keys, so the key set
  address is the local KeySetServer; its test key and token are made for the run and deleted at the end.

  Prerequisite: the application jar is built (.\Build-SDV-All.ps1).
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [int]$Port = 8291,
    [int]$StartTimeoutSeconds = 120,
    # Task 0037: the local port of the TCP relay that plays the database in step 5.
    [int]$RelayPort = 55433,
    # Task 0038: the local port of the stand-in sign-in hub's key set (step 6 stops and starts it).
    [int]$KeysPort = 55434,
    # Drop a scratch database and roles left by an earlier run of this script that could not clean up.
    [switch]$DropLeftover
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$container = 'sdv-postgres'
$db = 'sdv_install_test'
$owner = 'sdv_install_owner'
$app = 'sdv_install_app'
$hostUrl = 'jdbc:postgresql://localhost:5433/' + $db
$run = Join-Path $root ('.runtime\clean-install\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $run | Out-Null

$results = New-Object System.Collections.Generic.List[object]
function Write-Step([string]$name, [double]$seconds, [string]$detail) {
    $results.Add([pscustomobject]@{ Step = $name; Seconds = [math]::Round($seconds, 1); Result = $detail })
    Write-Host ("PASS  {0,-58} {1,6:N1} s  {2}" -f $name, $seconds, $detail) -ForegroundColor Green
}
function Assert-That([bool]$condition, [string]$message) { if (-not $condition) { throw "FAIL: $message" } }

# Runs a program; secrets go in $Environment (passed to the child only), never in its arguments.
function Invoke-Native {
    param([string]$File, [string[]]$Arguments, [string]$InputText, [hashtable]$Environment = @{})
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = $File
    $psi.Arguments = ($Arguments | ForEach-Object { if ($_ -match '[\s"]') { '"' + ($_ -replace '"', '\"') + '"' } else { $_ } }) -join ' '
    $psi.UseShellExecute = $false
    $psi.RedirectStandardInput = $true
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.WorkingDirectory = $root
    foreach ($k in $Environment.Keys) { $psi.EnvironmentVariables[$k] = $Environment[$k] }
    $p = [System.Diagnostics.Process]::Start($psi)
    $outTask = $p.StandardOutput.ReadToEndAsync()
    $errTask = $p.StandardError.ReadToEndAsync()
    if ($InputText) { $p.StandardInput.Write($InputText) }
    $p.StandardInput.Close()
    $p.WaitForExit()
    [pscustomobject]@{ Code = $p.ExitCode; Out = $outTask.Result; Err = $errTask.Result }
}

# psql inside the container as the server's superuser (local socket), or as a scratch user over TCP.
function Invoke-Sql {
    param([string]$Database, [string]$Sql, [string]$User, [hashtable]$Environment = @{}, [switch]$AllowFailure)
    $dockerArgs = @('exec', '-i')
    foreach ($k in $Environment.Keys) { $dockerArgs += @('-e', $k) }
    $dockerArgs += @($container, 'psql', '-X', '-q', '-tA', '-v', 'ON_ERROR_STOP=1', '-d', $Database)
    if ($User) { $dockerArgs += @('-h', '127.0.0.1', '-U', $User) } else { $dockerArgs += @('-U', 'sdv') }
    $r = Invoke-Native -File 'docker' -Arguments $dockerArgs -InputText $Sql -Environment $Environment
    if (-not $AllowFailure -and $r.Code -ne 0) { throw "psql failed on $Database (exit $($r.Code)): $($r.Err.Trim())" }
    $r
}

function Get-Http([string]$Path, [hashtable]$Headers = @{}) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri ("http://localhost:$Port" + $Path) -Headers $Headers -TimeoutSec 5
        # Windows PowerShell returns bytes for content types it doesn't know (the actuator's vnd.spring-boot JSON).
        $content = if ($r.Content -is [byte[]]) { [System.Text.Encoding]::UTF8.GetString($r.Content) } else { $r.Content }
        return [pscustomobject]@{ Status = [int]$r.StatusCode; Body = $content }
    } catch [System.Net.WebException] {
        $resp = $_.Exception.Response
        if ($null -eq $resp) { return $null }
        # Windows PowerShell has usually read the error body already; it is then in ErrorDetails.
        $body = if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message }
                else { (New-Object System.IO.StreamReader($resp.GetResponseStream())).ReadToEnd() }
        return [pscustomobject]@{ Status = [int]$resp.StatusCode; Body = $body }
    }
}

function New-Secret { -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) }) }

$script:appProcess = $null
# Every connection the Vero process opens to anything other than this computer (expected: none; Vero sends no usage
# data, and in this check the database is local, the key service and sign-in are off).
$script:outbound = New-Object 'System.Collections.Generic.HashSet[string]'
$script:connectionSamples = 0
function Watch-Outbound {
    if (-not $script:appProcess -or $script:appProcess.HasExited) { return }
    $script:connectionSamples++
    foreach ($c in @(Get-NetTCPConnection -OwningProcess $script:appProcess.Id -ErrorAction SilentlyContinue)) {
        if ($c.RemoteAddress -notin @('127.0.0.1', '::1', '0.0.0.0', '::') -and $c.RemotePort -ne 0) {
            [void]$script:outbound.Add("$($c.RemoteAddress):$($c.RemotePort)")
        }
    }
}
function Wait-Watching([double]$seconds) {
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    while ($w.Elapsed.TotalSeconds -lt $seconds) { Watch-Outbound; Start-Sleep -Milliseconds 200 }
}
# Task 0037: a packaged start refuses with sign-in or the key service switched off, so these placeholders switch them on.
# Nothing in these steps contacts them: the issuer is under the reserved .invalid domain, and nothing listens on 8299.
$placeholderToken = Join-Path $run 'placeholder-vault-token.txt'
Set-Content -Encoding ascii -NoNewline -Path $placeholderToken -Value 'placeholder-not-a-token'
$packagedSettings = [ordered]@{
    SDV_AUTH_MODE = 'enterprise-jwt'
    SDV_AUTH_JWT_ISSUER = 'https://sign-in.invalid/realms/vero-check'
    SDV_AUTH_JWT_JWK_SET_URI = "http://127.0.0.1:$KeysPort/certs"
    SDV_AUTH_JWT_AUDIENCE = 'vero-clean-install'
    SDV_KEY_PROVIDER = 'vault'
    SDV_VAULT_BASE_URL = 'http://127.0.0.1:8299'
    SDV_VAULT_TOKEN_FILE = $placeholderToken
}

# Starts the Vero process with the scratch database and the packaged settings; $Override replaces some of them.
function Start-VeroProcess([string]$name, [hashtable]$Override = @{}, [string]$DbUrl = $hostUrl) {
    $out = Join-Path $run "$name.out.log"; $err = Join-Path $run "$name.err.log"
    $settings = [ordered]@{ SDV_DB_URL = $DbUrl; SDV_DB_USERNAME = $app; SDV_DB_PASSWORD = $appPassword
                            SDV_SERVER_PORT = "$Port"; SDV_RETENTION_CRON = '* * * * * *' }
    foreach ($k in $packagedSettings.Keys) { $settings[$k] = $packagedSettings[$k] }
    foreach ($k in $Override.Keys) { $settings[$k] = $Override[$k] }
    try {
        foreach ($k in $settings.Keys) { Set-Item "Env:$k" $settings[$k] }
        # No --spring.profiles.active: this is a customer-style (packaged, production context) start.
        $script:appProcess = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $run `
            -ArgumentList @('-jar', "`"$jarPath`"", '--sdv.redaction.recovery-initial-delay-ms=1000') `
            -RedirectStandardOutput $out -RedirectStandardError $err
    } finally {
        foreach ($k in $settings.Keys) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
    }
    $out
}

# Task 0037: a start that must refuse. It has to exit with an error, print the code in the failure report, never show a
# stack trace and never answer on its port.
function Assert-StartRefused([string]$name, [hashtable]$Override, [string]$Code) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $out = Start-VeroProcess $name $Override
    $answered = $false
    while (-not $script:appProcess.HasExited -and $watch.Elapsed.TotalSeconds -lt $StartTimeoutSeconds) {
        if ($null -ne (Get-Http '/actuator/health')) { $answered = $true }
        Start-Sleep -Milliseconds 200
    }
    $exited = $script:appProcess.HasExited
    $exitCode = if ($exited) { $script:appProcess.ExitCode } else { $null }
    Stop-Vero
    Assert-That $exited "Vero kept running although $Code should refuse the start ($name)"
    Assert-That ($exitCode -ne 0) "Vero exited with 0 although $Code should refuse the start ($name)"
    Assert-That (-not $answered) "Vero answered on port $Port before refusing ($name)"
    $log = Get-Content -Raw $out
    Assert-That ($log.Contains('APPLICATION FAILED TO START') -and $log -match "Description:\s+$Code ") "the failure report doesn't start with $Code ($name). See $out"
    Assert-That ($log -match "ERROR .* $Code ") "no ERROR line with $Code ($name)"
    Assert-That (-not ($log -match '(?m)^\s+at [a-z]+\.')) "the refused start printed a stack trace ($name)"
    $watch.Elapsed.TotalSeconds
}

# Task 0037: "the database" for step 5, a TCP relay to sdv-postgres that the script stops and starts.
$script:relayProcess = $null
function Test-Port([int]$p) {
    $c = New-Object System.Net.Sockets.TcpClient
    try { $c.Connect('127.0.0.1', $p); return $true } catch { return $false } finally { $c.Close() }
}
function Start-Relay {
    $script:relayProcess = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $run `
        -ArgumentList @("`"$(Join-Path $root 'test-tools\TcpRelay.java')`"", "$RelayPort", '127.0.0.1', '5433') `
        -RedirectStandardOutput (Join-Path $run 'relay.out.log') -RedirectStandardError (Join-Path $run 'relay.err.log')
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Port $RelayPort)) {
        if ($script:relayProcess.HasExited -or $w.Elapsed.TotalSeconds -gt 60) { throw "FAIL: the TCP relay did not start on $RelayPort" }
        Start-Sleep -Milliseconds 200
    }
}
function Stop-Relay {
    if ($script:relayProcess -and -not $script:relayProcess.HasExited) {
        Stop-Process -Id $script:relayProcess.Id -Force
        $script:relayProcess.WaitForExit(30000) | Out-Null
    }
    $script:relayProcess = $null
}
# Task 0038: the stand-in sign-in hub. Readiness fetches its key set; step 6 stops and starts it. The key file and the
# token file are made for this run only and deleted at the end.
$script:keysProcess = $null
$keyFile = Join-Path $run 'test-signing-key.txt'
$tokenFile = Join-Path $run 'test-token.txt'
function Start-KeyServer {
    $script:keysProcess = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $run `
        -ArgumentList @("`"$(Join-Path $root 'test-tools\KeySetServer.java')`"", "$KeysPort", "`"$keyFile`"", "`"$tokenFile`"",
                        $packagedSettings.SDV_AUTH_JWT_ISSUER, $packagedSettings.SDV_AUTH_JWT_AUDIENCE) `
        -RedirectStandardOutput (Join-Path $run 'keys.out.log') -RedirectStandardError (Join-Path $run 'keys.err.log')
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    while (-not (Test-Port $KeysPort)) {
        if ($script:keysProcess.HasExited -or $w.Elapsed.TotalSeconds -gt 60) { throw "FAIL: the key set server did not start on $KeysPort" }
        Start-Sleep -Milliseconds 200
    }
}
function Stop-KeyServer {
    if ($script:keysProcess -and -not $script:keysProcess.HasExited) {
        Stop-Process -Id $script:keysProcess.Id -Force
        $script:keysProcess.WaitForExit(30000) | Out-Null
    }
    $script:keysProcess = $null
}
# Polls readiness until it has the status wanted; false when it doesn't within the time.
function Wait-Readiness([int]$want, [int]$seconds) {
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    while ($w.Elapsed.TotalSeconds -lt $seconds) {
        Watch-Outbound
        $r = Get-Http '/actuator/health/readiness'
        if ($null -ne $r -and $r.Status -eq $want) { return $true }
        if ($script:appProcess.HasExited) { return $false }
        Start-Sleep -Milliseconds 500
    }
    $false
}

function Start-Vero([string]$name, [string]$DbUrl = $hostUrl, [hashtable]$Override = @{}) {
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $out = Start-VeroProcess $name $Override $DbUrl
    while ($watch.Elapsed.TotalSeconds -lt $StartTimeoutSeconds) {
        if ($script:appProcess.HasExited) { throw "FAIL: Vero exited during start ($name). See $out" }
        Watch-Outbound
        $h = Get-Http '/actuator/health'
        if ($null -ne $h) { return [pscustomobject]@{ Seconds = $watch.Elapsed.TotalSeconds; Health = $h; Log = $out } }
        Start-Sleep -Milliseconds 200
    }
    throw "FAIL: Vero did not answer within $StartTimeoutSeconds s ($name). See $out"
}
function Stop-Vero {
    if ($script:appProcess -and -not $script:appProcess.HasExited) {
        Watch-Outbound
        Stop-Process -Id $script:appProcess.Id -Force
        $script:appProcess.WaitForExit(30000) | Out-Null
    }
    $script:appProcess = $null
}

function Remove-Scratch {
    $null = Invoke-Sql -Database 'postgres' -Sql @"
select pg_terminate_backend(pid) from pg_stat_activity where datname = '$db' and pid <> pg_backend_pid();
drop database if exists $db;
drop role if exists $app;
drop role if exists $owner;
"@
}

function Invoke-SchemaStep([string[]]$StepArgs) {
    $psArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'Update-SDV-Schema.ps1')) + $StepArgs + @('-Url', $hostUrl, '-Username', $owner, '-Jar', $jarPath)
    $watch = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-Native -File 'powershell.exe' -Arguments $psArgs -Environment @{ SDV_SCHEMA_DB_PASSWORD = $ownerPassword }
    $r | Add-Member -NotePropertyName Seconds -NotePropertyValue $watch.Elapsed.TotalSeconds
    $r
}

# ---------------------------------------------------------------- preconditions
if ($Jar) { $jarPath = (Resolve-Path -LiteralPath $Jar).Path }
else {
    $found = @(Get-ChildItem (Join-Path $root 'backend\sdv-web\target') -Filter 'sdv-web-*.jar' -File -ErrorAction SilentlyContinue)
    Assert-That ($found.Count -eq 1) 'build the application first (.\Build-SDV-All.ps1); backend\sdv-web\target has no single sdv-web jar'
    $jarPath = $found[0].FullName
}
$state = (Invoke-Native -File 'docker' -Arguments @('inspect', '-f', '{{.State.Running}}', $container)).Out.Trim()
Assert-That ($state -eq 'true') "$container is not running"
Assert-That ($null -eq (Get-Http '/actuator/health')) "port $Port is already in use"
Assert-That (-not (Test-Port $RelayPort)) "relay port $RelayPort is already in use"
Assert-That (-not (Test-Port $KeysPort)) "key set port $KeysPort is already in use"
$leftover = (Invoke-Sql -Database 'postgres' -Sql "select count(*) from pg_database where datname = '$db'; select count(*) from pg_roles where rolname in ('$owner','$app');").Out -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { [int]$_ }
if (($leftover | Measure-Object -Sum).Sum -gt 0) {
    Assert-That $DropLeftover.IsPresent "$db or its scratch roles exist from an earlier run; rerun with -DropLeftover to drop them"
    Remove-Scratch
}

$ownerPassword = New-Secret
$appPassword = New-Secret
$total = [System.Diagnostics.Stopwatch]::StartNew()
try {
    # ------------------------------------------------------------ scratch database and the two users
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-Sql -Database 'postgres' -Environment @{ SDV_TEST_OWNER_PW = $ownerPassword; SDV_TEST_APP_PW = $appPassword } -Sql @"
\getenv owner_pw SDV_TEST_OWNER_PW
\getenv app_pw SDV_TEST_APP_PW
create role $owner login password :'owner_pw';
create role $app login password :'app_pw' nocreatedb nocreaterole;
create database $db owner $owner;
revoke all on database $db from public;
grant connect on database $db to $app;
"@
    # PostgreSQL 16: schema public belongs to the database owner and PUBLIC cannot create in it. Grants for tables the
    # owner creates later are given ahead of time (install guide, "Update the database").
    $null = Invoke-Sql -Database $db -Sql @"
revoke create on schema public from public;
grant usage on schema public to $app;
alter default privileges for role $owner in schema public grant select, insert, update, delete on tables to $app;
"@
    Write-Step 'Create sdv_install_test, owner user and read/write app user' $w.Elapsed.TotalSeconds 'scratch only'
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    Start-KeyServer
    Write-Step 'Start the stand-in sign-in key set (task 0038)' $w.Elapsed.TotalSeconds "http://127.0.0.1:$KeysPort/certs, test key for this run"

    # ------------------------------------------------------------ 0. task 0037: packaged start, something switched off
    $t = Assert-StartRefused 'start-0-sign-in-off' @{ SDV_AUTH_MODE = 'disabled' } 'SDV-SYS-006'
    Write-Step '0a. Packaged start with sign-in switched off: refused' $t 'SDV-SYS-006, exit code not 0, port never answered, no stack trace'
    $t = Assert-StartRefused 'start-0-key-service-off' @{ SDV_KEY_PROVIDER = 'disabled' } 'SDV-KEY-001'
    Write-Step '0b. Packaged start with the key service switched off: refused' $t 'SDV-KEY-001, exit code not 0, port never answered, no stack trace'

    # ------------------------------------------------------------ 1. start against the empty database
    $s = Start-Vero 'start-1-empty'
    Assert-That ($s.Health.Status -eq 503) "health should be 503 DOWN on an empty database, was $($s.Health.Status)"
    $info = (Get-Http '/api/v1/system/info').Body | ConvertFrom-Json
    Assert-That ($info.databaseSchema.state -eq 'OLDER') "system info should say OLDER, said $($info.databaseSchema.state)"
    $older = 'The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database").'
    Assert-That ($info.databaseSchema.message -eq $older) 'system info message differs from the agreed sentence'
    $gated = Get-Http '/api/v1/operations/summary'
    Assert-That ($gated.Status -eq 503 -and $gated.Body -like '*SDV-SYS-001*') "a protected request should get 503 SDV-SYS-001 (was DATABASE_OLDER before task 0036), got $($gated.Status)"
    Wait-Watching 3   # let the background jobs' 1 s initial delay pass: they must stay off
    # Up but not ready: health and readiness OUT_OF_SERVICE (503), liveness UP (200).
    $health = Get-Http '/actuator/health'; $ready = Get-Http '/actuator/health/readiness'; $live = Get-Http '/actuator/health/liveness'
    Assert-That ($health.Status -eq 503 -and $health.Body -like '*OUT_OF_SERVICE*') "health should be 503 OUT_OF_SERVICE, was $($health.Status) $($health.Body)"
    Assert-That ($ready.Status -eq 503 -and $ready.Body -like '*OUT_OF_SERVICE*') "readiness should be 503 OUT_OF_SERVICE, was $($ready.Status) $($ready.Body)"
    Assert-That ($live.Status -eq 200 -and $live.Body -like '*UP*') "liveness should be 200 UP, was $($live.Status) $($live.Body)"
    $log = Get-Content -Raw $s.Log
    Assert-That ($log.Contains($older)) 'the startup log does not contain the "older" sentence'
    Assert-That (-not ($log -match 'liquibase\.(changelog|lockservice)')) 'Liquibase ran during a production start'
    Assert-That (-not ($log -match 'RETENTION_SWEEP_FAILED')) 'the cleanup job ran against the empty database'
    Assert-That (-not ($log -match 'Application run failed|CannotCreateTransactionException')) 'the start-up work stopped the start'
    $tables = (Invoke-Sql -Database $db -Sql "select count(*) from pg_tables where schemaname = 'public';").Out.Trim()
    Assert-That ($tables -eq '0') "the production start created $tables tables; it must change nothing"
    Stop-Vero
    Write-Step '1. Start on the empty database: refuses with "older"' $s.Seconds 'not ready (health and readiness 503 OUT_OF_SERVICE, liveness UP), API 503, 0 tables created'

    # ------------------------------------------------------------ 2. schema step
    $st = Invoke-SchemaStep @('status')
    Assert-That ($st.Code -eq 0) "status failed: $($st.Err.Trim())"
    $wouldRun = ([regex]::Match($st.Out, '(\d+) changesets would run')).Groups[1].Value
    Assert-That ($wouldRun -ne '' -and -not $st.Out.Contains('002-local-foundation-seed')) 'status should list the changesets and never the local seed'
    Write-Step '2a. Schema step status' $st.Seconds "$wouldRun changesets would run, local seed not listed"

    $sqlFile = Join-Path $run 'schema-update.sql'
    $sq = Invoke-SchemaStep @('sql', '-Output', $sqlFile)
    Assert-That ($sq.Code -eq 0 -and (Test-Path $sqlFile)) "sql failed: $($sq.Err.Trim())"
    $sqlText = Get-Content -Raw $sqlFile
    Assert-That ($sqlText -match '(?i)create index concurrently') 'the SQL file should contain the 018 concurrent indexes'
    Assert-That (-not $sqlText.Contains('002-local-foundation-seed')) 'the SQL file must not contain the local seed'
    $tables = (Invoke-Sql -Database $db -Sql "select count(*) from pg_tables where schemaname = 'public';").Out.Trim()
    Assert-That ($tables -eq '0') "sql changed the database ($tables tables)"
    Write-Step '2b. Schema step sql (written to a file)' $sq.Seconds ("{0:N0} KB, database unchanged" -f ((Get-Item $sqlFile).Length / 1KB))

    $up = Invoke-SchemaStep @('update')
    Assert-That ($up.Code -eq 0) "update failed: $($up.Err.Trim()) $($up.Out.Trim())"
    # Task 0041: "would run" counts Vero's changesets and platform-core's identity (V1-V3) and licensing (V1) versions.
    $liquibase = (Invoke-Sql -Database $db -Sql "select count(*) from databasechangelog;").Out.Trim()
    $platform = (Invoke-Sql -Database $db -Sql "select (select max(version) from idn_schema_version) || ' ' || (select max(version) from lic_schema_version) || ' ' || (select count(*) from idn_schema_history where success) || ' ' || (select count(*) from lic_schema_history where success);").Out.Trim().Split(' ')
    Assert-That ($platform[0] -eq '3' -and $platform[1] -eq '1') "identity tables at version $($platform[0]) (3 expected), licensing at $($platform[1]) (1 expected)"
    Assert-That ([int]$platform[2] -ge 3 -and [int]$platform[3] -ge 1) 'the identity and licensing runs must each record their versions in their own history table'
    $applied = [int]$liquibase + [int]$platform[0] + [int]$platform[1]
    $seedRows = (Invoke-Sql -Database $db -Sql "select count(*) from databasechangelog where id = '002-local-foundation-seed';").Out.Trim()
    Assert-That ("$applied" -eq $wouldRun -and $seedRows -eq '0') "update applied $applied changesets and platform versions (expected $wouldRun), seed rows $seedRows"
    $oldIdentity = (Invoke-Sql -Database $db -Sql "select count(*) from pg_tables where tablename in ('enterprise_identity_connections', 'enterprise_identity_connection_history', 'role_group_mappings_seen', 'role_mapping_notices');").Out.Trim()
    Assert-That ($oldIdentity -eq '0') "changeset 035 must drop the old identity tables, $oldIdentity left"
    $invalid = (Invoke-Sql -Database $db -Sql "select count(*) from pg_index where not indisvalid;").Out.Trim()
    Assert-That ($invalid -eq '0') "$invalid indexes are invalid after the concurrent index step"
    Write-Step '2c. Schema step update' $up.Seconds "$liquibase changesets and identity V3, licensing V1 applied (own history tables), old identity tables dropped, 018 indexes valid"
    $up.Out | Set-Content -Encoding utf8 (Join-Path $run 'schema-update.out.txt')

    # The app user can read and write but not create or alter.
    $create = Invoke-Sql -Database $db -User $app -Environment @{ PGPASSWORD = $appPassword } -Sql 'create table vero_should_not_exist (i int);' -AllowFailure
    Assert-That ($create.Code -ne 0 -and $create.Err -match 'permission denied') 'the app user must not be able to create tables'
    $alter = Invoke-Sql -Database $db -User $app -Environment @{ PGPASSWORD = $appPassword } -Sql 'alter table tenants add column vero_should_not_exist int;' -AllowFailure
    Assert-That ($alter.Code -ne 0 -and $alter.Err -match 'must be owner') 'the app user must not be able to alter tables'
    $null = Invoke-Sql -Database $db -User $app -Environment @{ PGPASSWORD = $appPassword } -Sql 'begin; select count(*) from tenants; delete from tenants where false; rollback;'
    Write-Step '   App user: no create, no alter; select and delete allowed' 0 'checked'

    # ------------------------------------------------------------ 3. start after the update
    # Table reads outside Liquibase's own tables, to show the background jobs really ran as the read/write user
    # (they log nothing when there is nothing to do).
    $readsSql = "select pg_stat_force_next_flush(); select coalesce(sum(coalesce(seq_scan,0) + coalesce(idx_scan,0)),0) from pg_stat_user_tables where relname not like 'databasechangelog%';"
    $readsBefore = [long]((Invoke-Sql -Database $db -Sql $readsSql).Out -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 1)
    $s = Start-Vero 'start-2-updated'
    Assert-That ($s.Health.Status -eq 200) "health should be 200 after the update, was $($s.Health.Status): $($s.Health.Body)"
    $info = (Get-Http '/api/v1/system/info').Body | ConvertFrom-Json
    Assert-That ($info.databaseSchema.state -eq 'CURRENT' -and $info.status -eq 'UP') "system info should say CURRENT, said $($info.databaseSchema.state)"
    $protected = Get-Http '/api/v1/operations/summary'
    Assert-That ($protected.Status -eq 401) "with the gate open a request without sign-in should get 401, got $($protected.Status)"
    Wait-Watching 4   # let the cleanup and redaction-recovery jobs run once as the read/write user
    $ready = Get-Http '/actuator/health/readiness'
    Assert-That ($ready.Status -eq 200 -and $ready.Body -like '*UP*') "readiness should be 200 UP after the update, was $($ready.Status) $($ready.Body)"
    $log = Get-Content -Raw $s.Log
    Assert-That ($log.Contains('the database matches this Vero version')) 'the startup log does not say the database matches'
    Assert-That (-not ($log -match 'RETENTION_SWEEP_FAILED|recovery sweep failed|permission denied')) 'a background job failed as the read/write user'
    Stop-Vero
    $readsAfter = [long]((Invoke-Sql -Database $db -Sql $readsSql).Out -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 1)
    Assert-That ($readsAfter -gt $readsBefore) 'the background jobs did not read any table while Vero ran'
    $tenants = (Invoke-Sql -Database $db -Sql 'select count(*) from tenants;').Out.Trim()
    Assert-That ($tenants -eq '0') "the tenants table should be empty (no seed data), has $tenants rows"
    Write-Step '3. Start after update: serves as the read/write user' $s.Seconds "health and readiness 200, CURRENT, 0 tenants, jobs read tables $($readsAfter - $readsBefore) times, no errors"

    # ------------------------------------------------------------ 4. start again: nothing new
    $before = (Invoke-Sql -Database $db -Sql "select count(*) || ' ' || max(orderexecuted) from databasechangelog;").Out.Trim()
    $st2 = Invoke-SchemaStep @('status')
    Assert-That ($st2.Code -eq 0 -and $st2.Out -match 'Up to date') "status after update should say up to date: $($st2.Out.Trim())"
    $s = Start-Vero 'start-3-again'
    Assert-That ($s.Health.Status -eq 200) "health should be 200 on the second start, was $($s.Health.Status)"
    Stop-Vero
    $after = (Invoke-Sql -Database $db -Sql "select count(*) || ' ' || max(orderexecuted) from databasechangelog;").Out.Trim()
    Assert-That ($before -eq $after) "the changelog changed on a plain start ($before -> $after)"
    $springStart = ([regex]::Match((Get-Content -Raw $s.Log), 'Started SdvApplication in ([\d.]+) seconds')).Groups[1].Value
    Write-Step '4. Start again: nothing applied' $s.Seconds "Spring start $springStart s; changelog unchanged ($($before.Split(' ')[0]) rows); status: up to date ($([math]::Round($st2.Seconds,1)) s)"

    # ------------------------------------------------------------ 5. task 0037: the database away at start, then back
    $relayUrl = "jdbc:postgresql://127.0.0.1:$RelayPort/$db"
    $s = Start-Vero 'start-4-database-away' $relayUrl
    $veroId = $script:appProcess.Id
    $ready = Get-Http '/actuator/health/readiness'; $live = Get-Http '/actuator/health/liveness'
    # Task 0039 part 2 (owner answer 4): the same rule at start as later: only readiness is 503 (not ready).
    $overall = Get-Http '/actuator/health'
    Assert-That ($ready.Status -eq 503 -and $ready.Body -like '*NOT_READY*') "readiness should be 503 NOT_READY while the database is away, was $($ready.Status) $($ready.Body)"
    Assert-That ($s.Health.Status -eq 200 -and $overall.Status -eq 200) "the overall health should be 200 while the database is away at start, was $($s.Health.Status) and $($overall.Status) $($overall.Body)"
    Assert-That ($live.Status -eq 200) "liveness should stay 200 while the database is away, was $($live.Status)"
    $gated = Get-Http '/api/v1/operations/summary'
    $gatedBody = $gated.Body | ConvertFrom-Json
    Assert-That ($gated.Status -eq 503 -and $gatedBody.code -eq 'SDV-SYS-014' -and $gatedBody.reference) "a request should get 503 SDV-SYS-014 with a reference while the database is away, got $($gated.Status) $($gated.Body)"
    Assert-That ((Get-Http '/api/v1/system/info').Body -like '*UNREACHABLE*') 'system info should say UNREACHABLE'
    $log = Get-Content -Raw $s.Log
    Assert-That ($log -match "ERROR .* SDV-SYS-003 Vero couldn't reach its database at start\..*127\.0\.0\.1:$RelayPort") 'no SDV-SYS-003 ERROR line with the host and port'
    Assert-That (-not $log.Contains($appPassword)) 'the database password is in the log'
    Write-Step '5a. Start with the database away: up, not ready, 503 SDV-SYS-014' $s.Seconds 'readiness 503 NOT_READY, liveness and overall health 200, SYS-003 logged with host and port, start-up work waits'

    $w = [System.Diagnostics.Stopwatch]::StartNew()
    Start-Relay
    Assert-That (Wait-Readiness 200 120) "Vero did not become ready within 120 s of the database coming back. See $($s.Log)"
    Assert-That (-not $script:appProcess.HasExited -and $script:appProcess.Id -eq $veroId) 'Vero was restarted'
    $protected = Get-Http '/api/v1/operations/summary'
    Assert-That ($protected.Status -eq 401) "with the database back a request without sign-in should get 401, got $($protected.Status)"
    $log = Get-Content -Raw $s.Log
    Assert-That ($log -match 'can be reached again after \d+ tries') 'the log does not say the database is back'
    # Task 0039 part 2 (owner answer 3): SDV_TRUSTED_PROXIES is not set, so X-Forwarded-For is ignored.
    $direct = Get-Http '/api/v1/ingestion/submissions' @{ 'X-Forwarded-For' = '198.51.100.8' }
    $directBody = $direct.Body | ConvertFrom-Json
    Assert-That ($direct.Status -eq 401 -and $directBody.code -eq 'SDV-AUT-001') "an intake request without sign-in should get 401 SDV-AUT-001, got $($direct.Status) $($direct.Body)"
    $directFrom = (Invoke-Sql -Database $db -Sql "select coalesce(string_agg(caller_address, ','), '-') from unidentified_intake_attempts where reference = '$($directBody.reference)';").Out.Trim()
    Assert-That ($directFrom -in @('127.0.0.1', '0:0:0:0:0:0:0:1')) "without trusted proxies the caller address should be this computer's, was: $directFrom"
    Write-Step '5b. Database up: Vero serves without a restart' $w.Elapsed.TotalSeconds "readiness 200, the same process, API 401 (gate open); X-Forwarded-For ignored (recorded $directFrom)"

    $w = [System.Diagnostics.Stopwatch]::StartNew()
    Stop-Relay
    Assert-That (Wait-Readiness 503 60) 'readiness did not turn 503 within 60 s of the database going away'
    Assert-That ((Get-Http '/actuator/health/liveness').Status -eq 200) 'liveness should stay 200 while the database is gone'
    # Task 0039: the database ping belongs to readiness only; the overall health is not DOWN for it.
    $overall = Get-Http '/actuator/health'
    Assert-That ($overall.Status -eq 200) "the overall health should stay 200 while the database is gone, was $($overall.Status) $($overall.Body)"
    $wentAway = $w.Elapsed.TotalSeconds
    Start-Relay
    Assert-That (Wait-Readiness 200 60) 'readiness did not turn 200 again within 60 s of the database coming back'
    Assert-That (-not $script:appProcess.HasExited -and $script:appProcess.Id -eq $veroId) 'Vero was restarted'
    Write-Step '5c. Database away and back after the start: readiness 503, then 200' $w.Elapsed.TotalSeconds ("not ready after {0:N1} s, ready again, liveness and overall health 200 throughout, the same process" -f $wentAway)
    Stop-Vero
    Stop-Relay

    # ------------------------------------------------------------ 6. task 0038: the sign-in hub's keys away, then back
    $token = (Get-Content -Raw $tokenFile).Trim()
    $signature = $token.Substring($token.LastIndexOf('.') + 1)
    $signedIn = @{ Authorization = "Bearer $token" }
    Stop-KeyServer
    # Task 0039 part 2: this start trusts this computer as a proxy, for the X-Forwarded-For check in 6b.
    $s = Start-Vero 'start-5-sign-in-keys-away' $hostUrl @{ SDV_TRUSTED_PROXIES = '127.0.0.1, ::1' }
    $veroId = $script:appProcess.Id
    Assert-That (Wait-Readiness 503 30) "readiness should be 503 while the sign-in keys can't be fetched. See $($s.Log)"
    Assert-That ((Get-Http '/actuator/health/liveness').Status -eq 200) 'liveness should stay 200 while the sign-in keys are away'
    # Task 0039: the sign-in keys check belongs to readiness only; the overall health is not DOWN for it.
    $overall = Get-Http '/actuator/health'
    Assert-That ($overall.Status -eq 200) "the overall health should stay 200 while the sign-in keys are away, was $($overall.Status) $($overall.Body)"
    $away = Get-Http '/api/v1/operations/summary' $signedIn
    $awayBody = $away.Body | ConvertFrom-Json
    Assert-That ($away.Status -eq 503 -and $awayBody.code -eq 'SDV-AUT-004' -and $awayBody.reference) "a signed-in request should get 503 SDV-AUT-004 with a reference while the keys are away, got $($away.Status) $($away.Body)"
    $anonymous = Get-Http '/api/v1/operations/summary'
    Assert-That ($anonymous.Status -eq 401 -and ($anonymous.Body | ConvertFrom-Json).code -eq 'SDV-AUT-001') "a request without sign-in should get 401 SDV-AUT-001, got $($anonymous.Status) $($anonymous.Body)"
    $log = Get-Content -Raw $s.Log
    Assert-That ($log -match "WARN .*code=SDV-AUT-004 The sign-in keys at 127\.0\.0\.1:$KeysPort can't be fetched") 'no readiness WARN line with SDV-AUT-004 and the key set host'
    Assert-That ($log -match "ERROR .*code=SDV-AUT-004 reference=$([regex]::Escape($awayBody.reference)) .*127\.0\.0\.1:$KeysPort") 'no ERROR line with SDV-AUT-004, the reference and the key set host'
    Write-Step '6a. Start with the sign-in keys away: not ready, 503 SDV-AUT-004' $s.Seconds 'readiness 503, liveness and overall health 200, signed-in request 503 AUT-004 with reference, no token 401 AUT-001, WARN and ERROR lines with the host'

    $w = [System.Diagnostics.Stopwatch]::StartNew()
    Start-KeyServer
    Assert-That (Wait-Readiness 200 60) "Vero did not become ready within 60 s of the sign-in keys coming back. See $($s.Log)"
    Assert-That (-not $script:appProcess.HasExited -and $script:appProcess.Id -eq $veroId) 'Vero was restarted'
    $back = Get-Http '/api/v1/operations/summary' $signedIn
    $backBody = $back.Body | ConvertFrom-Json
    Assert-That ($back.Status -eq 401 -and $backBody.code -eq 'SDV-AUT-006') "with the keys back the token is checked and no connection is set up: 401 SDV-AUT-006 expected, got $($back.Status) $($back.Body)"
    # Task 0039: a failed sign-in on an intake address is recorded for IT (no known sending system, no tenant),
    # with its address (IDs blanked), code and reference, and nothing from the token.
    $intake = Get-Http "/api/v1/ingestion/submissions/$([guid]::NewGuid())" $signedIn
    $intakeBody = $intake.Body | ConvertFrom-Json
    Assert-That ($intake.Status -eq 401 -and $intakeBody.code -eq 'SDV-AUT-006') "a failed sign-in on an intake address should get 401 SDV-AUT-006, got $($intake.Status) $($intake.Body)"
    $attempt = (Invoke-Sql -Database $db -Sql "select count(*) || ' ' || coalesce(min(method), '-') || ' ' || coalesce(min(address), '-') || ' ' || coalesce(min(tenant_id::text), 'none') || ' ' || coalesce(min(status)::text, '-') from unidentified_intake_attempts where reference = '$($intakeBody.reference)' and code = 'SDV-AUT-006';").Out.Trim()
    Assert-That ($attempt -eq '1 GET /api/v1/ingestion/submissions/{id} none 401') "the failed sign-in on the intake address should be one unidentified attempt, was: $attempt"
    $attempts = (Invoke-Sql -Database $db -Sql "select coalesce(string_agg(row_to_json(a)::text, ' '), '') from unidentified_intake_attempts a;").Out
    Assert-That (-not $attempts.Contains($signature) -and -not $attempts.Contains('clean-install-person') -and -not $attempts.Contains('eyJ')) 'an unidentified attempt holds token content'
    $log = Get-Content -Raw $s.Log
    Assert-That ($log -match "Sign-in keys at 127\.0\.0\.1:$KeysPort can be fetched again") 'the log does not say the sign-in keys are back'
    Assert-That ($log -match "WARN .*code=SDV-AUT-006 reference=$([regex]::Escape($backBody.reference))") 'no WARN line with SDV-AUT-006 and the reference'
    Assert-That (-not $log.Contains($signature)) 'the token is in the log'
    foreach ($b in @($away.Body, $anonymous.Body, $back.Body)) { Assert-That (-not $b.Contains($signature) -and -not $b.Contains('clean-install-person')) 'an answer holds token content' }
    Write-Step '6b. Sign-in keys back: ready without a restart' $w.Elapsed.TotalSeconds 'readiness 200, the same process, the token checked: 401 SDV-AUT-006 (no connection), no token content in answers or log; on an intake address recorded as an unidentified attempt, no token content'

    # Task 0039 part 2 (owner answers 2 and 3): 1,000 requests without sign-in, sent through a trusted proxy (this
    # computer) for 198.51.100.7. They are one row per minute with their count, at the caller's real address.
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    Add-Type -AssemblyName System.Net.Http
    $client = New-Object System.Net.Http.HttpClient
    $floodStatuses = @{}
    try {
        for ($i = 0; $i -lt 1000; $i++) {
            $req = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, "http://localhost:$Port/api/v1/ingestion/submissions/$([guid]::NewGuid())")
            $null = $req.Headers.TryAddWithoutValidation('X-Forwarded-For', '198.51.100.7')
            $resp = $client.SendAsync($req).GetAwaiter().GetResult()
            $floodStatuses[[int]$resp.StatusCode] = 1 + [int]$floodStatuses[[int]$resp.StatusCode]
            $resp.Dispose(); $req.Dispose()
        }
    } finally { $client.Dispose() }
    $floodSeconds = $w.Elapsed.TotalSeconds
    Assert-That ($floodSeconds -lt 60) ("the 1,000 requests took {0:N1} s, not under a minute" -f $floodSeconds)
    Assert-That ($floodStatuses.Count -eq 1 -and $floodStatuses[401] -eq 1000) "all 1,000 requests should get 401: $(($floodStatuses.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ', ')"
    $flood = (Invoke-Sql -Database $db -Sql "select count(*) || ' ' || coalesce(sum(attempt_count), 0) || ' ' || coalesce(min(address), '-') || ' ' || coalesce(min(code), '-') from unidentified_intake_attempts where caller_address = '198.51.100.7';").Out.Trim()
    $floodParts = $flood.Split(' ')
    Assert-That ([int]$floodParts[0] -ge 1 -and [int]$floodParts[0] -le 2 -and $floodParts[1] -eq '1000' -and $floodParts[2] -eq '/api/v1/ingestion/submissions/{id}' -and $floodParts[3] -eq 'SDV-AUT-001') "1,000 requests in under a minute should be one or two rows (a minute may turn) counting 1,000 for 198.51.100.7, was: $flood"
    Write-Step '6c. 1,000 unidentified requests through a trusted proxy' $w.Elapsed.TotalSeconds ("{0:N1} s; {1} row(s) counting {2}, caller 198.51.100.7 from X-Forwarded-For" -f $floodSeconds, $floodParts[0], $floodParts[1])
    Stop-Vero
    Stop-KeyServer

    # ------------------------------------------------------------ no usage data out
    Assert-That ($script:outbound.Count -eq 0) ("Vero opened connections outside this computer: " + (@($script:outbound) -join ', '))
    Write-Step 'Vero opened no connection outside this computer' 0 "$($script:connectionSamples) samples over the starts"
}
finally {
    Stop-Vero
    Stop-Relay
    Stop-KeyServer
    Remove-Item -Force -ErrorAction SilentlyContinue -Path $keyFile, $tokenFile
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    try { Remove-Scratch; $gone = (Invoke-Sql -Database 'postgres' -Sql "select count(*) from pg_database where datname = '$db';").Out.Trim() }
    catch { Write-Host "Cleanup failed: $($_.Exception.Message). Rerun with -DropLeftover." -ForegroundColor Red; $gone = '?' }
    if ($gone -eq '0') { Write-Step 'Drop sdv_install_test and the scratch users' $w.Elapsed.TotalSeconds 'dropped' }
    $results | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $run 'clean-install-steps.json')
}

Write-Host ''
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host ("PASS - clean-install check passed in {0:N0} s. Logs: {1}" -f $total.Elapsed.TotalSeconds, $run) -ForegroundColor Green
