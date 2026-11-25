<#
.SYNOPSIS
  Seed check (task 0023): the one demo seed command on an empty database, twice.

.DESCRIPTION
  Uses a scratch database, sdv_seed_test, inside the existing sdv-postgres server. The script never touches the sdv
  database and never creates, removes or recreates a container. The scratch database is dropped at the end, also
  when a step fails.

  Steps:
    1. Create sdv_seed_test (empty), run the schema step on it (task 0084: Vero never makes its tables) and start Vero
       on it, in local mode (the local build, as start-local does), on port 8291 (the usual app on 8280 keeps running).
       Vault is used as Start-SDV-Local.ps1 uses it: Vero reads the application token file itself; this script only passes its path and never opens it.
    2. Run .\Seed-SDV-Canvas-Demo.ps1 against it and check what it made.
    3. Run it again and check that nothing changed (every table, row by row; the audit too).
    4. Task 0035, the data moves on: what a person did by hand on 2026-11-21 is repeated through the API (Daniel's
       lowering of the income statement approved, Dana's waiting lowering declined, the income statement's hold
       released, Last Name set to "Starts with", Print turned on for Business user, Redaction Service paused) and a stray
       waiting request is added. The plain seed must not fail (it skips what it can't do and says so), and a second
       plain run changes nothing.
    5. Task 0035, the reset: .\Seed-SDV-Canvas-Demo.ps1 -Reset puts the seed's state back (checked item by item), and
       a second reset changes nothing. Before step 2, the reset is refused on the empty database (no demo data).
    6. With -WithTour: start a second Vite dev server on port 5193 that talks to that Vero, then run
       npm run ui:capture and npm run ui:compare against it.
    7. With -WithRestore (task 0080): a dump, then a redaction and the cleanup erase; the dump is restored into
       sdv_seed_test_restored (Vero is paused, the erase replay, start, nothing of it readable), and once more under the
       running Vero. The scratch databases' erase log is in this run's folder.

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
    # Task 0078: only the tour steps whose title matches (Playwright's -g), without the comparison; with -WithTour.
    [string]$TourSteps = '',
    # Task 0080: then the restore proof (a dump, erasures, the dump restored beside it, refused, replay, start).
    [switch]$WithRestore,
    # Drop a scratch database left by an earlier run of this script that could not clean up.
    [switch]$DropLeftover
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = $PSScriptRoot
$container = 'sdv-postgres'
$db = 'sdv_seed_test'
$jarPath = Join-Path $root 'backend\sdv-web\target\local\sdv-web-0.1.0-SNAPSHOT-local.jar'
$frontend = Join-Path $root 'frontend\sdv-ui'
$run = Join-Path $root ('.runtime\seed-test\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
New-Item -ItemType Directory -Force -Path $run | Out-Null

function Pass([string]$m) { Write-Host "PASS  $m" -ForegroundColor Green }
function Assert-That([bool]$c, [string]$m) { if (-not $c) { throw "FAIL: $m" }; Pass $m }
function Sql([string]$Database, [string]$Text) {
    $out = $Text | & docker exec -i $container psql -X -q -tA -v ON_ERROR_STOP=1 -U sdv -d $Database
    if ($LASTEXITCODE -ne 0) { throw "psql failed on $Database" }
    ($out | Out-String).Trim()
}
function Scalar([string]$Text) { Sql $db $Text }

if (-not (Test-Path -LiteralPath $jarPath)) { throw "Jar not found: $jarPath. Run .\Build-SDV-All.ps1 first." }
if (Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) { throw "Port $Port is in use." }

$exists = Sql 'postgres' "select count(*) from pg_database where datname='$db';"
if ([int]$exists -gt 0) {
    if (-not $DropLeftover) { throw "$db already exists (left by an earlier run). Run again with -DropLeftover to drop it first." }
    $null = Sql 'postgres' "drop database $db with (force);"
}

if ($DropLeftover) { $null = Sql 'postgres' "drop database if exists ${db}_restored with (force);" }

$script:app = $null
$script:vite = $null
try {
    # ---- 1. Empty database, Vero on it ----
    $null = Sql 'postgres' "create database $db owner sdv;"
    Pass "Created scratch database $db"
    $localPassword = & (Join-Path $root 'scripts\local\Get-SdvLocalDbPassword.ps1') -Root $root
    # Windows PowerShell stops on a native program's stderr lines; the exit code decides here.
    $ErrorActionPreference = 'Continue'
    $env:SDV_SCHEMA_DB_PASSWORD = $localPassword
    try {
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'Update-SDV-Schema.ps1') update -Url "jdbc:postgresql://localhost:5433/$db" -Username sdv *> (Join-Path $run 'schema.log')
        $schemaExit = $LASTEXITCODE
    } finally { Remove-Item Env:SDV_SCHEMA_DB_PASSWORD -ErrorAction SilentlyContinue }
    $ErrorActionPreference = 'Stop'
    Assert-That ($schemaExit -eq 0) "Schema step on the scratch database (exit $schemaExit; log: $run\schema.log)"
    # Task 0076: a scratch key service, never sdv-vault.
    & (Join-Path $root 'scripts\local\Use-SdvScratchVault.ps1')
    # Task 0080: the scratch database's own erase log.
    $env:SDV_ERASE_LOG_FOLDER = (Join-Path $run 'erase-log')
    # Task 0075: Vero starts only with a licence.
    $ErrorActionPreference = 'Continue'
    & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $root 'Seed-SDV-Canvas-Demo.ps1') -SetupOnly -Database $db *> (Join-Path $run 'setup.log')
    $setupExit = $LASTEXITCODE; $ErrorActionPreference = 'Stop'
    Assert-That ($setupExit -eq 0) "Setup on the scratch database, before the first start (exit $setupExit; log: $run\setup.log)"
    $env:SDV_LOCAL_DB_PASSWORD = $localPassword
    try {
        # Task 0084: from the repository root, as start-local does: the local profile's file paths are relative to it.
        $script:app = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $root `
            -ArgumentList @('-jar', "`"$jarPath`"", '--spring.profiles.active=local', "--server.port=$Port", "--spring.datasource.url=jdbc:postgresql://localhost:5433/$db") `
            -RedirectStandardOutput (Join-Path $run 'vero.out.log') -RedirectStandardError (Join-Path $run 'vero.err.log')
    } finally {
        foreach ($k in @('SDV_LOCAL_DB_PASSWORD')) { Remove-Item "Env:$k" -ErrorAction SilentlyContinue }
    }
    $watch = [Diagnostics.Stopwatch]::StartNew()
    while ($true) {
        if ($script:app.HasExited) { throw "FAIL: Vero exited during start. See $run\vero.out.log" }
        try { if ((Invoke-RestMethod -Uri "http://127.0.0.1:$Port/actuator/health" -TimeoutSec 3).status -eq 'UP') { break } } catch { }
        if ($watch.Elapsed.TotalSeconds -gt $StartTimeoutSeconds) { throw "FAIL: Vero did not start within $StartTimeoutSeconds s" }
        Start-Sleep -Milliseconds 500
    }
    Pass ("Vero started on the empty database in {0:N0} s" -f $watch.Elapsed.TotalSeconds)
    Assert-That ([int](Scalar 'select count(*) from ingestion_sources;') -eq 0) 'The empty database has no sending system before the seed'

    $seed = Join-Path $root 'Seed-SDV-Canvas-Demo.ps1'
    $seedArgs = @{ Base = "http://127.0.0.1:$Port"; Database = $db; StorageFolder = (Join-Path $run 'storage-a'); StandbyStorageFolder = (Join-Path $run 'storage-b') }

    # Task 0035: the reset refuses a database the seed has not filled.
    $refused = $null
    try { & $seed @seedArgs -Reset *> (Join-Path $run 'reset-0-refused.log') } catch { $refused = $_.Exception.Message }
    Assert-That ($refused -match 'Reset refused: database .* holds no delivery of this seed') "The reset is refused on the empty database ($refused)"

    # ---- 2. First run ----
    & $seed @seedArgs *> (Join-Path $run 'seed-1.log')
    Pass 'Seed run 1 finished'
    $T = "'bd960ee9-9d54-5053-9958-92aaa54893a6'"
    $sources = Scalar "select string_agg(display_name || ':' || status, ', ' order by display_name) from ingestion_sources where tenant_id=$T;"
    Assert-That ($sources -eq 'Loan Origination System:ACTIVE, Redaction Service:ACTIVE, Title Records:DISABLED, Tour test system 20261118181639:DISABLED') "Sending systems: $sources"
    # Distinct: since tasks 0029 and 0030 some loan-system contracts have a second delivery (task 0035 fix of this check).
    $contracts = Scalar "select string_agg(distinct v.search_value, ',' order by v.search_value) from fld_value v where v.entity_uuid=$T and v.field_key='contractNumber';"
    $want = '100000000001,100000000002,100000000003,300000000001,300000000002,300000000003,300000000004,300000000005,300000000006,300000000007,300000000008,300000000009,300000000076,300000000077,300000000078,300000000090,300000000092,300000000094,300000000095,300000000096'
    Assert-That ($contracts -eq $want) "20 demo records (Redaction Service 100000000001-3, 300000000076-78, 090, 092, 094-096; loan system 300000000001-9): $contracts"
    $terms = Scalar "select count(*) from documents d join submissions s on s.submission_id=d.submission_id where s.source_submission_id='r3-demo-contract-100000000001';"
    Assert-That ([int]$terms -eq 3) "100000000001 has its 3 documents (task 0035: each upload is its own copy, so none is an exact duplicate): $terms"
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
    $tourFields = Scalar "select count(*) filter (where (x.value::jsonb->>'searchable')::boolean) || '/' || count(*) from fld_field_definition f join fld_field_extension x on x.definition_id=f.definition_id join ingestion_sources s on x.name='source.' || s.source_id::text where s.source_key='tour-20261118181639' and f.active;"
    Assert-That ($tourFields -eq '0/2') 'Tour test system: paused, two fields, none searchable'
    $volumes = Scalar "select string_agg(display_name || ':' || state, ', ' order by priority, display_name) from storage_volumes where tenant_id=$T;"
    Assert-That ($volumes -eq 'primary-a:ACTIVE, primary-b:ACTIVE') "Volumes: $volumes"
    $newest = Scalar "select src.display_name from ingestion_sources src where src.tenant_id=$T order by (select max(s.last_activity_at) from submissions s where s.source_id=src.source_id) desc nulls last limit 1;"
    Assert-That ($newest -eq 'Loan Origination System') 'The loan system delivered most recently (the Records default)'
    Assert-That ((Scalar "select display_name from tenants where tenant_id=$T;") -eq 'Acme Lending') 'Organization: Acme Lending'

    # ---- 3. Second run changes nothing ----
    # Task 0084: without lic_last_seen: the licence clock's latest time seen, which every setup run moves forward by design.
    # Task 0078: nor the usage ledger and the named seats, which the running Vero's own job writes when its time comes.
    $snapshotSql = @'
select string_agg(format('select %L || ''='' || count(*) || '':'' || coalesce(md5(string_agg(t::text, ''|'' order by t::text)), ''-'') from %I t', table_name, table_name), ' union all ' order by table_name)
from information_schema.tables where table_schema='public' and table_type='BASE TABLE' and table_name not like 'databasechangelog%'
  and table_name not in ('lic_last_seen', 'lic_chain_head', 'lic_usage', 'usage_named_seats');
'@
    $query = Scalar $snapshotSql
    $before = (Scalar ($query + ';')) -split "`n" | ForEach-Object { $_.Trim() } | Sort-Object
    & $seed @seedArgs *> (Join-Path $run 'seed-2.log')
    $after = (Scalar ($query + ';')) -split "`n" | ForEach-Object { $_.Trim() } | Sort-Object
    $diff = @(Compare-Object $before $after)
    if ($diff.Count) { $diff | Format-Table -AutoSize | Out-String | Write-Host }
    Assert-That ($diff.Count -eq 0) "Seed run 2 changed nothing ($($before.Count) tables compared row by row, audit included)"
    function Snapshot { (Scalar ($query + ';')) -split "`n" | ForEach-Object { $_.Trim() } | Sort-Object }
    function Unchanged([string[]]$A, [string[]]$B, [string]$What) {
        $d = @(Compare-Object $A $B)
        if ($d.Count) { $d | Format-Table -AutoSize | Out-String | Write-Host }
        Assert-That ($d.Count -eq 0) "$What changed nothing ($($A.Count) tables compared row by row, audit included)"
    }

    # ---- 4. Task 0035: the data moves on, as it did by hand on 2026-11-21 ----
    $base = "http://127.0.0.1:$Port"
    function As([string]$Subject, [string]$Roles) { @{ 'X-SDV-Tenant-Id' = 'bd960ee9-9d54-5053-9958-92aaa54893a6'; 'X-SDV-Subject' = $Subject; 'X-SDV-Roles' = $Roles } }
    function Call([string]$Method, [string]$Path, [hashtable]$H, [object]$Body = $null) {
        $p = @{ Uri = "$base$Path"; Method = $Method; Headers = $H; UseBasicParsing = $true; TimeoutSec = 30 }
        if ($null -ne $Body) { $p.ContentType = 'application/json'; $p.Body = ($Body | ConvertTo-Json -Depth 10) }
        $r = Invoke-WebRequest @p
        if ($r.Content) { $r.Content | ConvertFrom-Json }
    }
    $priya = As 'local-business-admin' 'BUSINESS_ADMIN'; $dana = As 'local-restricted-steward' 'RESTRICTED_STEWARD'
    $doc = { param($k) Scalar "select document_id from documents where tenant_id=$T and source_document_id='$k';" }
    $settlement = & $doc 'canvas-demo-300000000003-sensitivity-1'; $income = & $doc 'canvas-demo-300000000005-sensitivity-1'; $medical = & $doc 'canvas-demo-300000000008-approvals-2'
    $danielLower = Scalar "select request_id from access_requests where requested_by='local-document-steward' and kind='LOWER_LEVEL' and document_id='$income' and status='PENDING';"
    $danaLower = Scalar "select request_id from access_requests where requested_by='local-restricted-steward' and kind='LOWER_LEVEL' and document_id='$settlement' and status='PENDING';"
    $null = Call 'POST' "/api/v1/admin/access-requests/$danielLower/approve" $priya
    $null = Call 'POST' "/api/v1/admin/access-requests/$danaLower/decline" $priya
    $incomeHold = Scalar "select hold_id from legal_holds where document_id='$income' and released_at is null;"
    # Task 0071: asked for by one Business Admin, approved by another.
    $holdRelease = Call 'POST' "/api/v1/admin/legal-holds/$incomeHold/release" $priya @{ reason = 'Seed check' }
    $null = Call 'POST' "/api/v1/admin/access-requests/$($holdRelease.requestId)/approve" (As 'local-business-admin-2' 'BUSINESS_ADMIN')
    $redactionId = Scalar "select source_id from ingestion_sources where tenant_id=$T and source_key='redaction-service';"
    $null = Call 'PUT' "/api/v1/admin/ingestion-sources/$redactionId/metadata-fields/lastName" $priya @{ displayLabel = 'Last Name'; fieldType = 'TEXT'; required = $false; businessIdentifier = $false; searchable = $true; normalization = 'TRIM'; searchMode = 'PREFIX'; validationRules = @{} }
    $roles = (Call 'GET' '/api/v1/admin/role-capabilities' $priya).roles
    $roles.BUSINESS_USER.PRINT_DOCUMENT = $true
    $null = Call 'PUT' '/api/v1/admin/role-capabilities' $priya @{ roles = $roles }
    $it = As 'local-tenant-it' 'IT_ADMIN'
    $redaction = @(Call 'GET' '/api/v1/admin/ingestion-sources' $it | ForEach-Object { $_ }) | Where-Object { $_.sourceKey -eq 'redaction-service' }
    $null = Call 'PUT' "/api/v1/admin/ingestion-sources/$redactionId" $it @{ displayName = $redaction.displayName; status = 'DISABLED'; authType = $redaction.authType; credentialReference = $redaction.credentialReference }
    $stray = (Call 'POST' "/api/v1/business/documents/$medical/access-requests" $dana @{ kind = 'DOWNLOAD'; reason = 'Stray request (seed check)' }).requestId
    Assert-That ((Scalar "select sensitivity_level from documents where document_id='$income';") -eq 'STANDARD') 'Moved on: the income statement was lowered to Standard (as by hand on 2026-11-21)'

    & $seed @seedArgs *> (Join-Path $run 'seed-3-moved-on.log')
    $log = Get-Content (Join-Path $run 'seed-3-moved-on.log') -Raw
    Assert-That ($log -match "\[SKIP\] Daniel's waiting lowering: the income statement is STANDARD") 'Seed run 3 (data moved on) did not fail; it skipped Daniel''s lowering and said why'
    Assert-That ($log -match 'step\(s\) skipped because the data has moved on') 'Seed run 3 says at the end that steps were skipped and how to reset'
    Assert-That ((Scalar "select count(*) from access_requests where requested_by='local-restricted-steward' and kind='LOWER_LEVEL' and document_id='$settlement' and status='PENDING';") -eq '1') 'Seed run 3 made Dana''s waiting lowering again'
    Assert-That ((Scalar "select count(*) from legal_holds where document_id='$income' and released_at is null;") -eq '1') 'Seed run 3 placed the income statement''s hold again'
    Assert-That ((Scalar "select status from ingestion_sources where source_id='$redactionId';") -eq 'ACTIVE') 'Seed run 3 started Redaction Service receiving again'
    $before = Snapshot
    & $seed @seedArgs *> (Join-Path $run 'seed-4-moved-on.log')
    Unchanged $before (Snapshot) 'Seed run 4 (data still moved on)'

    # ---- 5. Task 0035: the reset ----
    & $seed @seedArgs -Reset *> (Join-Path $run 'reset-1.log')
    Pass 'Reset run 1 finished'
    $levels = Scalar "select string_agg(source_document_id || ':' || sensitivity_level, ',' order by source_document_id) from documents where tenant_id=$T and sensitivity_level <> 'STANDARD';"
    $wantLevels = 'canvas-demo-300000000003-sensitivity-1:HIGHLY_CONFIDENTIAL,canvas-demo-300000000005-sensitivity-1:CONFIDENTIAL,canvas-demo-300000000007-approvals-1:HIGHLY_CONFIDENTIAL,canvas-demo-300000000007-approvals-2:HIGHLY_CONFIDENTIAL,canvas-demo-300000000008-approvals-1:HIGHLY_CONFIDENTIAL,canvas-demo-300000000008-approvals-2:HIGHLY_CONFIDENTIAL,canvas-demo-300000000009-sensitivity-1:CONFIDENTIAL'
    Assert-That ($levels -eq $wantLevels) "Reset: document levels as the seed makes them ($levels)"
    Assert-That ((Scalar "select string_agg(sensitivity_level, ',') from record_sensitivity_levels where tenant_id=$T and sensitivity_level <> 'STANDARD';") -eq 'HIGHLY_CONFIDENTIAL') 'Reset: only 300000000002 has a contract level (Highly Confidential)'
    $waiting = Scalar "select string_agg(x, ', ' order by x) from (select requested_by || ' ' || kind || ' ' || count(*) x from access_requests where tenant_id=$T and status='PENDING' and expires_at > now() group by requested_by, kind) y;"
    $wantWaiting = 'local-demo-ali-haddad DOWNLOAD 5, local-demo-ali-haddad PRINT 5, local-demo-leo-grant DOWNLOAD 5, local-demo-leo-grant PRINT 5, local-demo-mia-novak DOWNLOAD 5, local-demo-mia-novak PRINT 5, local-demo-sam-kim DOWNLOAD 5, local-demo-sam-kim PRINT 5, local-demo-tom-reed DOWNLOAD 5, local-demo-tom-reed PRINT 5, local-document-steward LOWER_LEVEL 1, local-restricted-steward LOWER_LEVEL 1, local-restricted-steward LOWER_RECORD_LEVEL 1'
    Assert-That ($waiting -eq $wantWaiting) "Reset: only the seed's waiting requests ($waiting)"
    Assert-That ((Scalar "select status from access_requests where request_id='$stray';") -eq 'DECLINED') 'Reset: the stray waiting request was declined'
    $holds = Scalar "select string_agg(d.source_document_id, ',' order by d.source_document_id) from legal_holds h join documents d on d.document_id=h.document_id where h.tenant_id=$T and h.released_at is null;"
    Assert-That ($holds -eq 'canvas-demo-300000000005-sensitivity-1,canvas-demo-300000000007-approvals-1,canvas-demo-300000000007-approvals-2') "Reset: the seed's three legal holds ($holds)"
    Assert-That ((Scalar "select x.value::jsonb->>'mode' from fld_field_definition f join fld_field_extension x on x.definition_id=f.definition_id where x.name='source.$redactionId' and f.field_key='lastName' and f.active;") -eq 'PARTIAL') 'Reset: Last Name matches "Contains" again'
    $printNow = (Call 'GET' '/api/v1/admin/role-capabilities' $priya).roles.BUSINESS_USER.PRINT_DOCUMENT
    Assert-That (-not $printNow) 'Reset: Business user has no Print again'
    Assert-That (-not ((Get-Content (Join-Path $run 'reset-1.log') -Raw) -match '\[SKIP\]')) 'Reset run 1 skipped nothing'
    $before = Snapshot
    & $seed @seedArgs -Reset *> (Join-Path $run 'reset-2.log')
    Unchanged $before (Snapshot) 'Reset run 2'

    # ---- 6. Tour and compare against it ----
    if ($WithTour) {
        if (Get-NetTCPConnection -LocalPort $UiPort -State Listen -ErrorAction SilentlyContinue) { throw "Port $UiPort is in use." }
        $env:SDV_UI_PORT = "$UiPort"; $env:SDV_API_TARGET = "http://127.0.0.1:$Port"
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
        $env:SDV_UI_BASE_URL = "http://localhost:$UiPort"; $env:SDV_API_TARGET = "http://127.0.0.1:$Port"
        Push-Location $frontend
        try {
            if ($TourSteps) {
                & npm run ui:capture '--' '--project=desktop-chromium' '-g' $TourSteps *> (Join-Path $run 'ui-capture.log'); $capture = $LASTEXITCODE
                $compare = 0; Set-Content (Join-Path $run 'ui-compare.log') 'not run'
            } else {
                & npm run ui:capture *> (Join-Path $run 'ui-capture.log'); $capture = $LASTEXITCODE
                & npm run ui:compare *> (Join-Path $run 'ui-compare.log'); $compare = $LASTEXITCODE
            }
        } finally { Pop-Location; Remove-Item Env:SDV_UI_BASE_URL, Env:SDV_API_TARGET -ErrorAction SilentlyContinue }
        foreach ($f in 'ui-capture', 'ui-compare') { Get-Content (Join-Path $run "$f.log") | Select-String -Pattern '^\s+\d+ (passed|failed|skipped|flaky)' | ForEach-Object { Write-Host "  $f $($_.Line.Trim())" } }
        Assert-That ($capture -eq 0) 'npm run ui:capture against the seeded scratch database'
        Assert-That ($compare -eq 0) 'npm run ui:compare against the seeded scratch database'
    }
    # ---- 7. Task 0080: a restored backup ----
    if ($WithRestore) {
        if ($script:vite -and -not $script:vite.HasExited) { & taskkill /PID $script:vite.Id /T /F *> $null }
        $restored = "${db}_restored"
        function Stop-App { if ($script:app -and -not $script:app.HasExited) { Stop-Process -Id $script:app.Id -Force; $null = $script:app.WaitForExit(30000) } }
        function Start-App([string]$Name, [string]$Database, [string[]]$More = @()) {
            $env:SDV_LOCAL_DB_PASSWORD = $localPassword
            try {
                $script:app = Start-Process -FilePath 'java' -PassThru -WindowStyle Hidden -WorkingDirectory $root `
                    -ArgumentList (@('-jar', "`"$jarPath`"", '--spring.profiles.active=local', "--server.port=$Port", "--spring.datasource.url=jdbc:postgresql://localhost:5433/$Database", '--platform.erase-log.recheck=5s') + $More) `
                    -RedirectStandardOutput (Join-Path $run "$Name.out.log") -RedirectStandardError (Join-Path $run "$Name.err.log")
            } finally { Remove-Item Env:SDV_LOCAL_DB_PASSWORD -ErrorAction SilentlyContinue }
        }
        # Status and body of any answer; null while nothing answers.
        function Answer([string]$Path, [hashtable]$H = @{}) {
            try { $r = Invoke-WebRequest "$base$Path" -Headers $H -UseBasicParsing -TimeoutSec 10; [pscustomobject]@{ Status = [int]$r.StatusCode; Body = "$($r.Content)" } }
            catch [System.Net.WebException] {
                if ($null -eq $_.Exception.Response) { return $null }
                $body = if ($_.ErrorDetails -and $_.ErrorDetails.Message) { $_.ErrorDetails.Message } else { (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() }
                [pscustomobject]@{ Status = [int]$_.Exception.Response.StatusCode; Body = $body }
            }
        }
        function Wait-For([string]$What, [scriptblock]$Until, [int]$Seconds = 150) {
            $w = [Diagnostics.Stopwatch]::StartNew()
            while ($true) {
                if (& $Until) { Pass ("$What ({0:N0} s)" -f $w.Elapsed.TotalSeconds); return }
                if ($w.Elapsed.TotalSeconds -gt $Seconds) { throw "FAIL: $What, not within $Seconds s" }
                Start-Sleep -Milliseconds 500
            }
        }
        function Ready { $r = Answer '/actuator/health/readiness'; $r -and $r.Status -eq 200 }
        function Paused { $r = Answer '/actuator/health/readiness'; $r -and $r.Status -eq 503 -and $r.Body -match 'ERASE_LOG_RESTORE_DETECTED' -and $r.Body -match 'SDV-ERS-001' }
        function Replay([string]$Name, [string]$Database) {
            Start-App $Name $Database @('--erase-replay')
            $null = $script:app.Handle # or Windows PowerShell does not know the exit code afterwards
            if (-not $script:app.WaitForExit(180000)) { Stop-App; throw "FAIL: the erase replay did not end; see $run\$Name.out.log" }
            Assert-That ($script:app.ExitCode -eq 0) "The erase replay on $Database ended with 0: $((Get-Content (Join-Path $run "$Name.out.log") | Select-String '^Erase replay ' | Select-Object -First 1).Line)"
        }
        $steward = As 'local-document-steward' 'DOCUMENT_STEWARD'
        $user = As 'local-business-user' 'BUSINESS_USER'
        # A contract of one delivery goes with its last document; another document is redacted.
        $oldSub = Scalar "select s.submission_id from submissions s where s.tenant_id=$T and s.status='COMPLETE' and s.source_id='$redactionId' and (select count(*) from documents d where d.submission_id=s.submission_id)=1 and (select count(*) from submissions o where o.record_id=s.record_id)=1 and not exists (select 1 from documents d join legal_holds h on h.document_id=d.document_id and h.released_at is null where d.submission_id=s.submission_id) and exists (select 1 from documents d where d.submission_id=s.submission_id and d.status='AVAILABLE' and d.sensitivity_level='STANDARD') order by 1 limit 1;"
        $oldDoc = Scalar "select document_id from documents where submission_id='$oldSub';"
        $oldRecord = Scalar "select record_id from submissions where submission_id='$oldSub';"
        $cutDoc = & $doc 'r3-demo-doc-100000000001-3'
        Assert-That ($oldDoc -and $oldRecord -and $cutDoc -and $oldDoc -ne $cutDoc) "A document to erase ($oldDoc), and one to redact ($cutDoc)"
        & docker exec $container pg_dump -U sdv -d $db -Fc -f "/tmp/$db.dump"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: pg_dump' }
        Pass 'The older backup: a dump of the database'
        Copy-Item -Recurse (Join-Path $run 'storage-a') (Join-Path $run 'storage-a-backup')

        $answer = Call 'POST' "/api/v1/business/documents/$cutDoc/redactions" $steward @{ reason = 'Restore check'; confirmed = $true; regions = @(@{ pageNumber = 1; x = 0.1; y = 0.1; width = 0.2; height = 0.1 }) }
        Assert-That ($answer.status -eq 'AVAILABLE') 'One document was redacted; its original is erased through the log'
        $null = Scalar "update documents set expires_at = now() - interval '1 minute' where document_id='$oldDoc';"
        Stop-App
        Start-App 'restore-1-cleanup' $db @('"--sdv.retention.cron=*/5 * * * * *"')
        Wait-For 'The cleanup erased the document that was due' { (Scalar "select status from documents where document_id='$oldDoc';") -eq 'EXPIRED_DELETED' }
        Wait-For 'and the values of its record' { (Scalar "select count(*) from fld_record where record_id='$oldRecord';") -eq '0' } 60
        Assert-That ((Scalar "select count(*) from records where record_id='$oldRecord';") -eq '1') 'The record''s row stays, with nothing but its key'
        $logText = Get-Content -Raw (Join-Path $run 'erase-log\erase-log.jsonl')
        Assert-That (@($logText -split "`n" | Where-Object { $_.Trim() }).Count -eq 3 -and $logText -match '"mac"' -and $logText -match 'replaced-version' -and $logText -match 'case-fields') 'The erase log has three keyed entries: the redacted version, the document, the record''s values'
        Stop-App

        # The backup comes back: the database and the files of then, the log of now.
        $null = Sql 'postgres' "create database $restored owner sdv;"
        & docker exec $container pg_restore -U sdv -d $restored "/tmp/$db.dump"
        if ($LASTEXITCODE -ne 0) { throw 'FAIL: pg_restore' }
        Copy-Item -Recurse -Force (Join-Path $run 'storage-a-backup\*') (Join-Path $run 'storage-a')
        Assert-That ((Sql $restored "select status || ' ' || (select count(*) from fld_record where record_id='$oldRecord') from documents where document_id='$oldDoc';") -eq 'AVAILABLE 1') 'In the restored database the erased document and its values are back'
        Start-App 'restore-2-refused' $restored
        Wait-For 'Vero on the restored database is paused: readiness 503 with the log''s code' { Paused }
        foreach ($path in "/api/v1/business/documents/$oldDoc/viewer", '/api/v1/system/info', '/api/v1/operations/summary') {
            $r = Answer $path $user
            Assert-That ($r.Status -eq 503 -and $r.Body -match 'SDV-ERS-001') "Nothing answers but health: $path 503 SDV-ERS-001"
        }
        Assert-That ((Answer '/actuator/health/liveness').Status -eq 200) 'Liveness stays up'
        Stop-App
        Replay 'restore-3-replay' $restored
        Start-App 'restore-4-started' $restored
        Wait-For 'Vero takes work again' { Ready }
        Assert-That ((Sql $restored "select status || ' ' || (select count(*) from fld_record where record_id='$oldRecord') || ' ' || (select count(*) from document_crypto_metadata where document_id='$oldDoc') from documents where document_id='$oldDoc';") -eq 'EXPIRED_DELETED 0 0') 'The document, its key and the values are erased again'
        Assert-That ((Sql $restored "select status || ' ' || deletion_reason || ' ' || (select count(*) from document_crypto_metadata where document_id='$cutDoc') from documents where document_id='$cutDoc';") -eq 'EXPIRED_DELETED ERASED_AFTER_RESTORE 0') 'The original of the redacted document is erased: its redacted copy is not in the older backup'
        foreach ($d in $oldDoc, $cutDoc) { $r = Answer "/api/v1/business/documents/$d/viewer" (As 'local-restricted-steward' 'RESTRICTED_STEWARD'); Assert-That ($r.Status -ge 400) "Not readable: $d answers $($r.Status)" }
        $keys = @((& docker exec $container pg_restore -f - -a -t document_storage_refs "/tmp/$db.dump") | Where-Object { $_ -match "^($oldDoc|$cutDoc)" } | ForEach-Object { ([regex]::Match($_, 'objects/[^\s]+')).Value })
        Assert-That ($keys.Count -eq 2 -and @($keys | Where-Object { Test-Path (Join-Path $run "storage-a\$_") }).Count -eq 0) 'Their files, which the backup of the files had brought back, are gone'

        # While Vero runs, the database is restored under it.
        $null = Sql $restored 'set client_min_messages = warning; drop schema public cascade; create schema public;'
        & docker exec $container pg_restore -U sdv -d $restored "/tmp/$db.dump" 2> $null
        Wait-For 'A restore while Vero runs is noticed' { Paused } 120
        Stop-App
        Replay 'restore-5-replay' $restored
        Start-App 'restore-6-started' $restored
        Wait-For 'Vero takes work again' { Ready }
        Assert-That ((Sql $restored "select status from documents where document_id='$oldDoc';") -eq 'EXPIRED_DELETED') 'The document is erased again'
        Stop-App
    }
    Write-Host "Seed check passed. Logs: $run" -ForegroundColor Green
} finally {
    if ($script:vite -and -not $script:vite.HasExited) { & taskkill /PID $script:vite.Id /T /F *> $null }
    if ($script:app -and -not $script:app.HasExited) { Stop-Process -Id $script:app.Id -Force; $script:app.WaitForExit(30000) | Out-Null }
    Remove-Item Env:SDV_ERASE_LOG_FOLDER -ErrorAction SilentlyContinue
    try { $null = Sql 'postgres' "drop database if exists ${db}_restored with (force);"; & docker exec $container rm -f "/tmp/$db.dump" } catch { Write-Warning "Could not drop ${db}_restored or the dump. $_" }
    try { $null = Sql 'postgres' "drop database if exists $db with (force);"; Write-Host "Dropped scratch database $db" } catch { Write-Warning "Could not drop $db; run again with -DropLeftover. $_" }
}
