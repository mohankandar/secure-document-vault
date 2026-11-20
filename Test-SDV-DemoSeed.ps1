<#
.SYNOPSIS
  Seed check (task 0023): the one demo seed command on an empty database, twice.

.DESCRIPTION
  Uses a scratch database, sdv_seed_test, inside the existing sdv-postgres server. The script never touches the sdv
  database and never creates, removes or recreates a container. The scratch database is dropped at the end, also
  when a step fails.

  Steps:
    1. Create sdv_seed_test (empty) and start Vero on it, in local mode, on port 8291 (the usual app on 8280 keeps
       running). Vault is used as Start-SDV-Local.ps1 uses it: Vero reads the application token file itself; this
       script only passes its path and never opens it.
    2. Run .\Seed-SDV-Canvas-Demo.ps1 against it and check what it made.
    3. Run it again and check that nothing changed (every table, row by row; the audit too).
    4. With -WithTour: start a second Vite dev server on port 5193 that talks to that Vero, then run
       npm run ui:capture and npm run ui:compare against it.

  Documents delivered by the seed are stored in .runtime\seed-test\<run>\storage-a and -b (kept, like the other
  check folders under .runtime; the files are encrypted and belong to nothing once the database is dropped).

  Prerequisite: the application jar is built (.\Build-SDV-All.ps1) and Vault is unsealed (as for start-local).
#>
[CmdletBinding()]
param(
    [int]$Port = 8291,
    [int]$UiPort = 5193,
    [int]$StartTimeoutSeconds = 150,
    [switch]$WithTour,
    # Drop a scratch database left by an earlier run of this script that could not clean up.
    [switch]$DropLeftover
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = $PSScriptRoot
$container = 'sdv-postgres'
$db = 'sdv_seed_test'
$jarPath = Join-Path $root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar'
$frontend = Join-Path $root 'frontend\sdv-ui'
$run = Join-Path $root ('.runtime\seed-test\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $run | Out-Null
$tokenFile = if ($env:SDV_VAULT_TOKEN_FILE) { $env:SDV_VAULT_TOKEN_FILE } else { Join-Path (Split-Path -Parent $root) 'secrets\sdv-app.token' }

function Pass([string]$m) { Write-Host "PASS  $m" -ForegroundColor Green }
function Assert-That([bool]$c, [string]$m) { if (-not $c) { throw "FAIL: $m" }; Pass $m }
function Sql([string]$Database, [string]$Text) {
    $out = $Text | & docker exec -i $container psql -X -q -tA -v ON_ERROR_STOP=1 -U sdv -d $Database
    if ($LASTEXITCODE -ne 0) { throw "psql failed on $Database" }
    ($out | Out-String).Trim()
}
function Scalar([string]$Text) { Sql $db $Text }

if (-not (Test-Path -LiteralPath $jarPath)) { throw "Jar not found: $jarPath. Run .\Build-SDV-All.ps1 first." }
if (-not (Test-Path -LiteralPath $tokenFile -PathType Leaf)) { throw "The application Vault token file is missing: $tokenFile (the same file start-local uses)." }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is in use." }

$exists = Sql 'postgres' "select count(*) from pg_database where datname='$db';"
if ([int]$exists -gt 0) {
    if (-not $DropLeftover) { throw "$db already exists (left by an earlier run). Run again with -DropLeftover to drop it first." }
    $null = Sql 'postgres' "drop database $db with (force);"
}

$script:app = $null
$script:vite = $null
try {
    # ---- 1. Empty database, Vero on it ----
    $null = Sql 'postgres' "create database $db owner sdv;"
    Pass "Created scratch database $db"
    $env:SDV_KEY_PROVIDER = 'vault'; $env:SDV_VAULT_BASE_URL = 'http://127.0.0.1:8200'; $env:SDV_VAULT_TOKEN_FILE = $tokenFile
    try {
        $script:app = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $run `
            -ArgumentList @('-jar', "`"$jarPath`"", '--spring.profiles.active=local', "--server.port=$Port", "--spring.datasource.url=jdbc:postgresql://localhost:5433/$db") `
            -RedirectStandardOutput (Join-Path $run 'vero.out.log') -RedirectStandardError (Join-Path $run 'vero.err.log')
    } finally {
        foreach ($k in 'SDV_KEY_PROVIDER', 'SDV_VAULT_BASE_URL', 'SDV_VAULT_TOKEN_FILE') { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        if ($script:app.HasExited) { throw "FAIL: Vero exited during start. See $run\vero.out.log" }
        try { if ((Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 3).status -eq 'UP') { break } } catch { }
        if ($watch.Elapsed.TotalSeconds -gt $StartTimeoutSeconds) { throw "FAIL: Vero did not start within $StartTimeoutSeconds s" }
        Start-Sleep -Milliseconds 500
    }
    Pass ("Vero started on the empty database in {0:N0} s" -f $watch.Elapsed.TotalSeconds)
    Assert-That ([int](Scalar 'select count(*) from ingestion_sources;') -eq 0) 'The empty database has no sending system before the seed'

    $seed = Join-Path $root 'Seed-SDV-Canvas-Demo.ps1'
    $seedArgs = @{ Base = "http://localhost:$Port"; Database = $db; StorageFolder = (Join-Path $run 'storage-a'); StandbyStorageFolder = (Join-Path $run 'storage-b') }

    # ---- 2. First run ----
    & $seed @seedArgs *> (Join-Path $run 'seed-1.log')
    Pass 'Seed run 1 finished'
    $T = "'11111111-1111-1111-1111-111111111111'"
    $sources = Scalar "select string_agg(display_name || ':' || status, ', ' order by display_name) from ingestion_sources where tenant_id=$T;"
    Assert-That ($sources -eq 'LexaVault:ACTIVE, Loan Origination System:ACTIVE, Title Records:DISABLED, Tour test system 20261118181639:DISABLED') "Sending systems: $sources"
    $contracts = Scalar "select string_agg(v.normalized_value, ',' order by v.normalized_value) from business_metadata_values v where v.tenant_id=$T and v.field_key='contractNumber';"
    $want = '100000000001,100000000002,100000000003,300000000001,300000000002,300000000003,300000000004,300000000005,300000000006,300000000007,300000000008,300000000009,300000000076,300000000077,300000000078,300000000090,300000000092,300000000094,300000000095,300000000096'
    Assert-That ($contracts -eq $want) '20 demo records (LexaVault 100000000001-3, 300000000076-78, 090, 092, 094-096; loan system 300000000001-9)'
    $fourteen = Scalar "select d.original_filename || ':' || d.status || ':' || coalesce(d.page_count::text, '?') from documents d join submissions s on s.submission_id=d.submission_id where s.source_submission_id='demo-14-pages-300000000092';"
    Assert-That ($fourteen -eq 'loan-file.pdf:AVAILABLE:14') "The 14-page demo document on 300000000092 ($fourteen)"
    $incomplete = Scalar "select s.status || ':' || (select count(*) from documents d where d.submission_id=s.submission_id) || ' of ' || s.expected_document_count from submissions s where s.source_submission_id='demo-incomplete-300000000090';"
    Assert-That ($incomplete -eq 'RECEIVING:1 of 2') "300000000090 has 1 of 2 documents ($incomplete)"
    $tag = Scalar "select count(*) from document_tags t join documents d on d.document_id=t.document_id where t.tag_value='e2e-demo' and d.source_document_id='r3-demo-doc-100000000001-3';"
    Assert-That ([int]$tag -eq 1) 'The e2e-demo tag on 100000000001 Terms and Conditions.pdf'
    $comment = Scalar "select count(*) from document_comments c join documents d on d.document_id=c.document_id where d.source_document_id='r3-demo-doc-100000000001-3';"
    Assert-That ([int]$comment -eq 1) 'One demo comment on the same document'
    $problems = Scalar "select string_agg(d.status, ',' order by s.source_submission_id) from documents d join submissions s on s.submission_id=d.submission_id where s.source_submission_id like 'problem-demo-%';"
    Assert-That ($problems -eq 'PROCESSING_FAILED,PROCESSING,AVAILABLE') "Problems demo: could not store, stuck, missing ($problems)"
    $tourFields = Scalar "select count(*) filter (where f.searchable) || '/' || count(*) from metadata_field_definitions f join ingestion_sources s on s.source_id=f.source_id where s.source_key='tour-20261118181639' and f.active;"
    Assert-That ($tourFields -eq '0/2') 'Tour test system: paused, two fields, none searchable'
    $volumes = Scalar "select string_agg(display_name || ':' || state, ', ' order by priority, display_name) from storage_volumes where tenant_id=$T;"
    Assert-That ($volumes -eq 'primary-a:ACTIVE, primary-b:ACTIVE') "Volumes: $volumes"
    $newest = Scalar "select src.display_name from ingestion_sources src where src.tenant_id=$T order by (select max(s.last_activity_at) from submissions s where s.source_id=src.source_id) desc nulls last limit 1;"
    Assert-That ($newest -eq 'Loan Origination System') 'The loan system delivered most recently (the Records default)'
    Assert-That ((Scalar "select display_name from tenants where tenant_id=$T;") -eq 'Acme Lending') 'Organization: Acme Lending'

    # ---- 3. Second run changes nothing ----
    $snapshotSql = @'
select string_agg(format('select %L || ''='' || count(*) || '':'' || coalesce(md5(string_agg(t::text, ''|'' order by t::text)), ''-'') from %I t', table_name, table_name), ' union all ' order by table_name)
from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name not like 'databasechangelog%';
'@
    $query = Scalar $snapshotSql
    $before = (Scalar ($query + ';')) -split "`n" | ForEach-Object { $_.Trim() } | Sort-Object
    & $seed @seedArgs *> (Join-Path $run 'seed-2.log')
    $after = (Scalar ($query + ';')) -split "`n" | ForEach-Object { $_.Trim() } | Sort-Object
    $diff = @(Compare-Object $before $after)
    if ($diff.Count) { $diff | Format-Table -AutoSize | Out-String | Write-Host }
    Assert-That ($diff.Count -eq 0) "Seed run 2 changed nothing ($($before.Count) tables compared row by row, audit included)"

    # ---- 4. Tour and compare against it ----
    if ($WithTour) {
        if (Get-NetTCPConnection -LocalPort $UiPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $UiPort is in use." }
        $env:SDV_UI_PORT = "$UiPort"; $env:SDV_API_TARGET = "http://localhost:$Port"
        try {
            $script:vite = Start-Process -FilePath 'cmd.exe' -ArgumentList @('/c', 'npm', 'run', 'dev') -WorkingDirectory $frontend -PassThru -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $run 'vite.out.log') -RedirectStandardError (Join-Path $run 'vite.err.log')
        } finally { Remove-Item Env:SDV_UI_PORT, Env:SDV_API_TARGET -ErrorAction SilentlyContinue }
        $watch = [Diagnostics.Stopwatch]::StartNew()
        while (-not (Get-NetTCPConnection -LocalPort $UiPort -State Listen -ErrorAction SilentlyContinue)) {
            if ($watch.Elapsed.TotalSeconds -gt 60) { throw 'FAIL: the second Vite did not start' }
            Start-Sleep -Milliseconds 500
        }
        Pass "Second Vite on port $UiPort, talking to the scratch Vero"
        # "localhost", not 127.0.0.1: the local test role switch is only offered on localhost (src/api.ts).
        $env:SDV_UI_BASE_URL = "http://localhost:$UiPort"
        Push-Location $frontend
        try {
            & npm run ui:capture *> (Join-Path $run 'ui-capture.log'); $capture = $LASTEXITCODE
            & npm run ui:compare *> (Join-Path $run 'ui-compare.log'); $compare = $LASTEXITCODE
        } finally { Pop-Location; Remove-Item Env:SDV_UI_BASE_URL -ErrorAction SilentlyContinue }
        foreach ($f in 'ui-capture', 'ui-compare') { Get-Content (Join-Path $run "$f.log") | Select-String -Pattern '^\s+\d+ (passed|failed|skipped|flaky)' | ForEach-Object { Write-Host "  $f $($_.Line.Trim())" } }
        Assert-That ($capture -eq 0) 'npm run ui:capture against the seeded scratch database'
        Assert-That ($compare -eq 0) 'npm run ui:compare against the seeded scratch database'
    }
    Write-Host "Seed check passed. Logs: $run" -ForegroundColor Green
} finally {
    if ($script:vite -and -not $script:vite.HasExited) { & taskkill /PID $script:vite.Id /T /F *> $null }
    if ($script:app -and -not $script:app.HasExited) { Stop-Process -Id $script:app.Id -Force; $script:app.WaitForExit(30000) | Out-Null }
    try { $null = Sql 'postgres' "drop database if exists $db with (force);"; Write-Host "Dropped scratch database $db" } catch { Write-Warning "Could not drop $db; run again with -DropLeftover. $_" }
}
