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
    1. Start Vero (no local profile, production context) against the empty database: it refuses with the "older" message.
    2. Schema step: status, sql (to a file, changes nothing), update.
    3. Start again: it serves, as the read/write user, and the tenants table is empty (no seed data).
    4. Start again: nothing new is applied, and the start is quick.

  Prerequisite: the application jar is built (.\Build-SDV-All.ps1).
#>
[CmdletBinding()]
param(
    [string]$Jar,
    [int]$Port = 8291,
    [int]$StartTimeoutSeconds = 120,
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

function Get-Http([string]$Path) {
    try {
        $r = Invoke-WebRequest -UseBasicParsing -Uri ("http://localhost:$Port" + $Path) -TimeoutSec 5
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
function Start-Vero([string]$name) {
    $out = Join-Path $run "$name.out.log"; $err = Join-Path $run "$name.err.log"
    $env:SDV_DB_URL = $hostUrl; $env:SDV_DB_USERNAME = $app; $env:SDV_DB_PASSWORD = $appPassword
    $env:SDV_SERVER_PORT = "$Port"; $env:SDV_RETENTION_INITIAL_DELAY_MS = '1000'
    try {
        $watch = [System.Diagnostics.Stopwatch]::StartNew()
        # No --spring.profiles.active: this is a customer-style (production context) start. Sign-in stays "disabled".
        $script:appProcess = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $run `
            -ArgumentList @('-jar', "`"$jarPath`"", '--sdv.redaction.recovery-initial-delay-ms=1000') `
            -RedirectStandardOutput $out -RedirectStandardError $err
    } finally {
        foreach ($k in 'SDV_DB_URL', 'SDV_DB_USERNAME', 'SDV_DB_PASSWORD', 'SDV_SERVER_PORT', 'SDV_RETENTION_INITIAL_DELAY_MS') { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
    }
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

    # ------------------------------------------------------------ 1. start against the empty database
    $s = Start-Vero 'start-1-empty'
    Assert-That ($s.Health.Status -eq 503) "health should be 503 DOWN on an empty database, was $($s.Health.Status)"
    $info = (Get-Http '/api/v1/system/info').Body | ConvertFrom-Json
    Assert-That ($info.databaseSchema.state -eq 'OLDER') "system info should say OLDER, said $($info.databaseSchema.state)"
    $older = 'The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database").'
    Assert-That ($info.databaseSchema.message -eq $older) 'system info message differs from the agreed sentence'
    $gated = Get-Http '/api/v1/operations/summary'
    Assert-That ($gated.Status -eq 503 -and $gated.Body -like '*DATABASE_OLDER*') "a protected request should get 503 DATABASE_OLDER, got $($gated.Status)"
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
    $applied = (Invoke-Sql -Database $db -Sql "select count(*) from databasechangelog;").Out.Trim()
    $seedRows = (Invoke-Sql -Database $db -Sql "select count(*) from databasechangelog where id = '002-local-foundation-seed';").Out.Trim()
    Assert-That ($applied -eq $wouldRun -and $seedRows -eq '0') "update applied $applied changesets (expected $wouldRun), seed rows $seedRows"
    $invalid = (Invoke-Sql -Database $db -Sql "select count(*) from pg_index where not indisvalid;").Out.Trim()
    Assert-That ($invalid -eq '0') "$invalid indexes are invalid after the concurrent index step"
    Write-Step '2c. Schema step update' $up.Seconds "$applied changesets applied, 018 indexes valid"
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

    # ------------------------------------------------------------ no usage data out
    Assert-That ($script:outbound.Count -eq 0) ("Vero opened connections outside this computer: " + (@($script:outbound) -join ', '))
    Write-Step 'Vero opened no connection outside this computer' 0 "$($script:connectionSamples) samples over the three starts"
}
finally {
    Stop-Vero
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    try { Remove-Scratch; $gone = (Invoke-Sql -Database 'postgres' -Sql "select count(*) from pg_database where datname = '$db';").Out.Trim() }
    catch { Write-Host "Cleanup failed: $($_.Exception.Message). Rerun with -DropLeftover." -ForegroundColor Red; $gone = '?' }
    if ($gone -eq '0') { Write-Step 'Drop sdv_install_test and the scratch users' $w.Elapsed.TotalSeconds 'dropped' }
    $results | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $run 'clean-install-steps.json')
}

Write-Host ''
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host ("PASS - clean-install check passed in {0:N0} s. Logs: {1}" -f $total.Elapsed.TotalSeconds, $run) -ForegroundColor Green
