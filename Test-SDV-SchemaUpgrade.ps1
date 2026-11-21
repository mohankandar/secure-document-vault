<#
.SYNOPSIS
  Repeatable upgrade check for task 0024: a database at an earlier schema moves to this version's schema with its data
  unchanged. -From 032 (the default, task 0033) starts at 031 and checks changeset 032 (the stewards' notices);
  -From 030 starts at 029 and checks changesets 030 (the tenant time zone), 031 (the report indexes) and 032;
  -From 031 starts at 030 and checks 031 and 032; -From 029 starts at 028 and checks changeset 029 (exact
  duplicates) and then 030 and 031;
  -From 028 starts at 027 and checks changeset 028 (the level-change history) and then 029;
  -From 027 starts at 026 and checks changeset 027 (access requests, hold names) and then 028;
  -From 026 starts at 025 and checks changeset 026 (legal holds) and then 027; -From 025
  starts at 024 and checks changeset 025 (the role tables) and then 026; -From 024
  starts at 023 and checks 024 (record sensitivity levels) and then 025; -From 023 starts at 022 and checks 023 (the
  document sensitivity level), 024 and 025.

.DESCRIPTION
  Uses a scratch database, sdv_schema_test, inside the existing sdv-postgres server, and one scratch role,
  sdv_schema_owner, that owns it and runs the schema step. Its password is random for each run, held only in memory
  and never printed. The script never touches the sdv database and never creates, removes or recreates a container.
  Everything it creates is dropped at the end, also when a step fails.

  Steps:
    1. Previous version: the schema step's own "sql" output for an empty database, cut just before changeset -From,
       is applied with psql. The changesets before it are the same files as when they were released, so this is the
       previous version's schema and changelog. Checked: the last changeset, and that the new objects don't exist yet.
    2. Data at the previous version: a tenant, a sending system, a submission and documents in four states (with
       different sensitivity levels when the column already exists).
    3. Schema step: status lists exactly the new changesets; sql (the SQL the database team reviews) holds their
       statements and changes nothing; update.
    4. After, for 023: every document is STANDARD, the column is not null with default STANDARD, the check refuses
       unknown and lower-case values. For 024: record_sensitivity_levels exists and is empty, its checks refuse an
       unknown level, a key whose SHA-256 doesn't match, a second row for the same record and an unknown sending
       system; "insert ... on conflict do nothing" (what intake runs) adds nothing for the same record; the database
       computes the same SHA-256 as the application. For 025: the three role tables exist and are empty, the
       statements the application runs on them behave as it expects, and their checks refuse bad rows. For 026: legal_holds
       exists and is empty, its checks refuse bad rows, and LegalHoldPostgresTest runs the application's legal-hold and
       retention code against the scratch database (held documents kept, released ones deleted, races, tenants). For 027:
       access_requests exists and is empty, a hold placed before keeps its columns and gets no names, and
       AccessRequestPostgresTest runs requests, grants, expiry and the table's checks. For 028: sensitivity_level_changes
       exists and is empty, an access request made before keeps every column, and AccessRequestPostgresTest also writes
       and reads the history and checks its table. For 029: document_duplicate_receipts exists and is empty, a level
       change made before keeps every column, and DuplicateIntakePostgresTest runs the duplicate lookup, the intake
       service's duplicate path, the received counts and the table's checks. For 030: tenants.time_zone exists, is null for every
       tenant and its check refuses padded, blank and long values. For 031: the three report indexes exist and are valid, and
       IntakeReportPostgresTest runs the uncorrected-copy check (task 0032, section 0) and the Daily intake report's SQL.
       For 032 (task 0033): steward_notices exists, an uncorrected copy received before became one unread notice (the
       audit event is unchanged), its checks refuse bad rows, IntakeReportPostgresTest writes and reads the stored
       notices (read per person), and SensitivityReportPostgresTest runs the Sensitivity report's SQL.
       Always: every other column of every row is unchanged, no other
       table changed, status is up to date.

  Prerequisite: the application jar is built (.\Build-SDV-All.ps1).
#>
[CmdletBinding()]
param(
    [string]$Jar,
    # The first changeset the upgrade applies; the scratch database is built up to the one before it.
    [ValidateSet('023', '024', '025', '026', '027', '028', '029', '030', '031', '032')]
    [string]$From = '032',
    # Drop a scratch database and role left by an earlier run of this script that could not clean up.
    [switch]$DropLeftover
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$container = 'sdv-postgres'
$db = 'sdv_schema_test'
$owner = 'sdv_schema_owner'
$hostUrl = 'jdbc:postgresql://localhost:5433/' + $db
# In order. The upgrade applies every changeset from -From on.
$checked = @('022-retention-policy-creator-name', '023-document-sensitivity-level', '024-record-sensitivity-levels', '025-role-separation', '026-legal-holds', '027-access-requests', '028-sensitivity-level-changes', '029-document-duplicate-receipts', '030-tenant-time-zone', '031-intake-report-indexes', '032-steward-notices')
$start = [array]::IndexOf(($checked | ForEach-Object { $_.Substring(0, 3) }), $From)
$newChangesets = @($checked[$start..($checked.Count - 1)])
$newChangeset = $newChangesets[0]
$lastPrevious = $checked[$start - 1]
$has023 = $newChangesets -contains '023-document-sensitivity-level'
$has024 = $newChangesets -contains '024-record-sensitivity-levels'
$has025 = $newChangesets -contains '025-role-separation'
$has026 = $newChangesets -contains '026-legal-holds'
$has027 = $newChangesets -contains '027-access-requests'
$has028 = $newChangesets -contains '028-sensitivity-level-changes'
$has029 = $newChangesets -contains '029-document-duplicate-receipts'
$has030 = $newChangesets -contains '030-tenant-time-zone'
$has031 = $newChangesets -contains '031-intake-report-indexes'
$has032 = $newChangesets -contains '032-steward-notices'
# Task 0033: an uncorrected copy received at the previous version, which 032 must turn into one stored notice.
$oldCopyEvent = '50000000-0000-0000-0000-0000000000c1'
$reportIndexes = @('idx_submissions_tenant_created', 'idx_documents_tenant_ingested', 'idx_audit_events_redaction_old_sha')
$roleTables = @('role_conflict_problems', 'role_group_mappings_seen', 'role_mapping_notices')
$run = Join-Path $root ('.runtime\schema-upgrade\' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
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

# psql inside the container as the server's superuser (local socket), or as the scratch owner over TCP.
function Invoke-Sql {
    param([string]$Database, [string]$Sql, [switch]$AsOwner, [switch]$AllowFailure)
    $environment = @{}
    $dockerArgs = @('exec', '-i')
    if ($AsOwner) { $environment['PGPASSWORD'] = $ownerPassword; $dockerArgs += @('-e', 'PGPASSWORD') }
    $dockerArgs += @($container, 'psql', '-X', '-q', '-tA', '-v', 'ON_ERROR_STOP=1', '-d', $Database)
    if ($AsOwner) { $dockerArgs += @('-h', '127.0.0.1', '-U', $owner) } else { $dockerArgs += @('-U', 'sdv') }
    $r = Invoke-Native -File 'docker' -Arguments $dockerArgs -InputText $Sql -Environment $environment
    if (-not $AllowFailure -and $r.Code -ne 0) { throw "psql failed on $Database (exit $($r.Code)): $($r.Err.Trim())" }
    $r
}
function Get-Value([string]$Sql) { ((Invoke-Sql -Database $db -Sql $Sql).Out -split "`n" | Where-Object { $_.Trim() } | Select-Object -Last 1).Trim() }

function New-Secret { -join ((1..32) | ForEach-Object { '{0:x}' -f (Get-Random -Maximum 16) }) }

function Remove-Scratch {
    $null = Invoke-Sql -Database 'postgres' -Sql @"
select pg_terminate_backend(pid) from pg_stat_activity where datname = '$db' and pid <> pg_backend_pid();
drop database if exists $db;
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

# Every row of every application table except documents and the new table, as one fingerprint per table; and documents
# with the columns they had at the previous version (sensitivity_level too when it existed then).
$otherTablesSql = @"
select string_agg(t.relname || '=' || (xpath('/row/c/text()', query_to_xml(format('select count(*) || '':'' || coalesce(md5(string_agg(x::text, ''|'' order by x::text)), '''') as c from %I x', t.relname), false, true, '')))[1]::text, ',' order by t.relname)
  from pg_stat_user_tables t
 where t.relname not in ('documents', 'record_sensitivity_levels', 'role_conflict_problems', 'role_group_mappings_seen', 'role_mapping_notices', 'legal_holds', 'access_requests', 'sensitivity_level_changes', 'document_duplicate_receipts', 'tenants', 'steward_notices', 'databasechangelog', 'databasechangeloglock');
"@
# Task 0032: tenants with every column it had before 030 (030 adds time_zone, null for every existing tenant).
$tenantsOldColumnsSql = "select count(*) || ':' || md5(string_agg((to_jsonb(x) - 'time_zone')::text, '|' order by x.tenant_id)) from tenants x;"
$hasTimeZoneSql = "select count(*) from information_schema.columns where table_name = 'tenants' and column_name = 'time_zone';"
$reportIndexesSql = "select count(*) from pg_indexes where indexname in ('" + ($reportIndexes -join "', '") + "');"
$duplicateTableBefore = if ($has029) { '0' } else { '1' }
$levelColumn = if ($has023) { '' } else { ', sensitivity_level' }
$documentsOldColumnsSql = @"
select count(*) || ':' || md5(string_agg(row(document_id, tenant_id, submission_id, source_id, source_document_id, producer_idempotency_key,
       original_filename, media_type, content_sha256, content_size_bytes, status, retention_days_snapshot, ingested_at, expires_at,
       available_at, processing_attempt_count, processing_error_code, deleted_at, deletion_reason, page_count$levelColumn)::text, '|' order by document_id))
  from documents;
"@
$hasColumnSql = "select count(*) from information_schema.columns where table_name = 'documents' and column_name = 'sensitivity_level';"
$hasTableSql = "select count(*) from information_schema.tables where table_name = 'record_sensitivity_levels';"
$hasRoleTablesSql = "select count(*) from information_schema.tables where table_name in ('role_conflict_problems', 'role_group_mappings_seen', 'role_mapping_notices');"
$hasHoldTableSql = "select count(*) from information_schema.tables where table_name = 'legal_holds';"
$hasRequestTableSql = "select count(*) from information_schema.tables where table_name = 'access_requests';"
$hasChangeTableSql = "select count(*) from information_schema.tables where table_name = 'sensitivity_level_changes';"
$hasDuplicateTableSql = "select count(*) from information_schema.tables where table_name = 'document_duplicate_receipts';"
$hasNoticeTableSql = "select count(*) from information_schema.tables where table_name = 'steward_notices';"
$noticeTableBefore = if ($has032) { '0' } else { '1' }
$changeTableBefore = if ($has028) { '0' } else { '1' }
$requestTableBefore = if ($has027) { '0' } else { '1' }
$holdTableBefore = if ($has026) { '0' } else { '1' }
$roleTablesBefore = if ($has025) { '0' } else { '3' }
$recordTableBefore = if ($has024) { '0' } else { '1' }

# ---------------------------------------------------------------- preconditions
if ($Jar) { $jarPath = (Resolve-Path -LiteralPath $Jar).Path }
else {
    $found = @(Get-ChildItem (Join-Path $root 'backend\sdv-web\target') -Filter 'sdv-web-*.jar' -File -ErrorAction SilentlyContinue)
    Assert-That ($found.Count -eq 1) 'build the application first (.\Build-SDV-All.ps1); backend\sdv-web\target has no single sdv-web jar'
    $jarPath = $found[0].FullName
}
$state = (Invoke-Native -File 'docker' -Arguments @('inspect', '-f', '{{.State.Running}}', $container)).Out.Trim()
Assert-That ($state -eq 'true') "$container is not running"
$leftover = (Invoke-Sql -Database 'postgres' -Sql "select count(*) from pg_database where datname = '$db'; select count(*) from pg_roles where rolname = '$owner';").Out -split "`n" | Where-Object { $_.Trim() } | ForEach-Object { [int]$_ }
if (($leftover | Measure-Object -Sum).Sum -gt 0) {
    Assert-That $DropLeftover.IsPresent "$db or $owner exists from an earlier run; rerun with -DropLeftover to drop them"
    Remove-Scratch
}

$ownerPassword = New-Secret
$total = [System.Diagnostics.Stopwatch]::StartNew()
try {
    # ------------------------------------------------------------ scratch database and its owner
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    $r = Invoke-Native -File 'docker' -Arguments @('exec', '-i', '-e', 'SDV_TEST_OWNER_PW', $container, 'psql', '-X', '-q', '-tA', '-v', 'ON_ERROR_STOP=1', '-d', 'postgres', '-U', 'sdv') -Environment @{ SDV_TEST_OWNER_PW = $ownerPassword } -InputText @"
\getenv owner_pw SDV_TEST_OWNER_PW
create role $owner login password :'owner_pw';
create database $db owner $owner;
revoke all on database $db from public;
"@
    Assert-That ($r.Code -eq 0) "could not create the scratch database: $($r.Err.Trim())"
    Write-Step "Create $db and its owner" $w.Elapsed.TotalSeconds 'scratch only'

    # ------------------------------------------------------------ 1. previous version (up to the one before -From)
    $sqlFull = Join-Path $run 'all-changesets.sql'
    $sq = Invoke-SchemaStep @('sql', '-Output', $sqlFull)
    Assert-That ($sq.Code -eq 0 -and (Test-Path $sqlFull)) "sql on the empty database failed: $($sq.Err.Trim()) $($sq.Out.Trim())"
    $full = Get-Content -Raw $sqlFull
    $marker = [regex]::Match($full, '(?m)^-- Changeset [^\r\n]*::' + [regex]::Escape($newChangeset) + '::')
    Assert-That $marker.Success "the SQL for an empty database has no changeset $newChangeset"
    $previous = $full.Substring(0, $marker.Index)
    Assert-That ($previous.Contains("::$lastPrevious::")) "the cut SQL does not end with $lastPrevious"
    $previous | Set-Content -Encoding utf8 (Join-Path $run 'previous-version.sql')
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    # Applied statement by statement (psql autocommit), as the install guide says for 018's concurrent indexes.
    $null = Invoke-Sql -Database $db -AsOwner -Sql $previous
    # The cut leaves out the lock release at the end of the file; release it as Liquibase would.
    $null = Invoke-Sql -Database $db -AsOwner -Sql 'update databasechangeloglock set locked = false, lockgranted = null, lockedby = null where id = 1;'
    $last = Get-Value 'select id from databasechangelog order by orderexecuted desc limit 1;'
    Assert-That ($last -eq $lastPrevious) "the previous version should end with $lastPrevious, ends with $last"
    $hasColumn = Get-Value $hasColumnSql
    Assert-That ($hasColumn -eq $(if ($has023) { '0' } else { '1' })) "documents.sensitivity_level should $(if ($has023) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasTableSql) -eq $recordTableBefore) "record_sensitivity_levels should $(if ($has024) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasRoleTablesSql) -eq $roleTablesBefore) "the role tables should $(if ($has025) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasHoldTableSql) -eq $holdTableBefore) "legal_holds should $(if ($has026) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasRequestTableSql) -eq $requestTableBefore) "access_requests should $(if ($has027) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasChangeTableSql) -eq $changeTableBefore) "sensitivity_level_changes should $(if ($has028) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasDuplicateTableSql) -eq $duplicateTableBefore) "document_duplicate_receipts should $(if ($has029) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasTimeZoneSql) -eq $(if ($has030) { '0' } else { '1' })) "tenants.time_zone should $(if ($has030) { 'not ' })exist at the previous version"
    $indexesBefore = if ($has031) { '0' } else { '3' }
    Assert-That ((Get-Value $reportIndexesSql) -eq $indexesBefore) "the report indexes should $(if ($has031) { 'not ' })exist at the previous version"
    Assert-That ((Get-Value $hasNoticeTableSql) -eq $noticeTableBefore) "steward_notices should $(if ($has032) { 'not ' })exist at the previous version"
    $prevCount = Get-Value 'select count(*) from databasechangelog;'
    $missing = @(); if ($has023) { $missing += 'no sensitivity_level' }; if ($has024) { $missing += 'no record_sensitivity_levels' }; if ($has025) { $missing += 'no role tables' }; if ($has026) { $missing += 'no legal_holds' }; if ($has027) { $missing += 'no access_requests' }; if ($has028) { $missing += 'no sensitivity_level_changes' }; if ($has029) { $missing += 'no document_duplicate_receipts' }; if ($has030) { $missing += 'no tenants.time_zone' }; if ($has031) { $missing += 'no report indexes' }; if ($has032) { $missing += 'no steward_notices' }
    Write-Step "1. Previous version (changesets up to $($lastPrevious.Substring(0, 3)))" $w.Elapsed.TotalSeconds "$prevCount changesets, last $last, $($missing -join ', ')"

    # ------------------------------------------------------------ 2. data at the previous version
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    $null = Invoke-Sql -Database $db -AsOwner -Sql @"
insert into tenants(tenant_id, tenant_key, display_name, status) values ('10000000-0000-0000-0000-000000000001', 'upgrade-test', 'Upgrade test', 'ACTIVE');
insert into ingestion_sources(source_id, tenant_id, source_key, display_name, status, auth_type)
  values ('20000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', 'loans', 'Loans', 'ACTIVE', 'JWT_BEARER');
insert into submissions(submission_id, tenant_id, source_id, source_submission_id, expected_document_count, status)
  values ('30000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'sub-1', 4, 'COMPLETE');
insert into documents(document_id, tenant_id, submission_id, source_id, source_document_id, original_filename, media_type,
                      content_sha256, content_size_bytes, status, available_at, page_count, deleted_at, deletion_reason)
values ('40000000-0000-0000-0000-000000000001', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'd1', 'a.pdf', 'application/pdf', repeat('a', 64), 100, 'AVAILABLE', now(), 3, null, null),
       ('40000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'd2', 'b.pdf', 'application/pdf', repeat('b', 64), 200, 'RECEIVED', null, null, null, null),
       ('40000000-0000-0000-0000-000000000003', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'd3', 'c.pdf', 'application/pdf', repeat('c', 64), 300, 'PROCESSING_FAILED', null, null, null, null),
       ('40000000-0000-0000-0000-000000000004', '10000000-0000-0000-0000-000000000001', '30000000-0000-0000-0000-000000000001', '20000000-0000-0000-0000-000000000001', 'd4', 'd.pdf', 'application/pdf', repeat('d', 64), 400, 'EXPIRED_DELETED', null, null, now(), 'RETENTION_EXPIRED');
"@
    if (-not $has023) {
        # The level column exists already: give the documents different levels, which 024 must leave alone.
        $null = Invoke-Sql -Database $db -AsOwner -Sql "update documents set sensitivity_level = 'CONFIDENTIAL' where source_document_id = 'd1'; update documents set sensitivity_level = 'HIGHLY_CONFIDENTIAL' where source_document_id = 'd2';"
    }
    # Task 0033: an uncorrected copy received before 032 (task 0032 kept it in the audit only); 032 makes it a notice.
    if ($has032) {
        $null = Invoke-Sql -Database $db -AsOwner -Sql "insert into audit_events(audit_event_id, tenant_id, actor_type, actor_id, action, target_type, target_id, occurred_at, outcome, safe_details) values ('$oldCopyEvent', '10000000-0000-0000-0000-000000000001', 'SOURCE', 'loans-producer', 'DOCUMENT_UNCORRECTED_COPY_RECEIVED', 'DOCUMENT', '40000000-0000-0000-0000-000000000001', now() - interval '2 days', 'SUCCESS', jsonb_build_object('sourceId', '20000000-0000-0000-0000-000000000001', 'submissionId', '30000000-0000-0000-0000-000000000001'));"
    }
    # Task 0028: with legal_holds already there, a released hold placed before 027 must keep every column it had.
    $holdsOldColumnsSql = "select count(*) || ':' || coalesce(md5(string_agg(row(hold_id, tenant_id, document_id, reason, placed_by, placed_at, released_by, released_at)::text, '|' order by hold_id)), '') from legal_holds;"
    if (-not $has026) {
        $null = Invoke-Sql -Database $db -AsOwner -Sql "insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at, released_by, released_at) values ('80000000-0000-0000-0000-0000000000f1', '10000000-0000-0000-0000-000000000001', '40000000-0000-0000-0000-000000000001', 'Litigation', 'admin', now() - interval '1 day', 'admin', now());"
        $holdsBefore = Get-Value $holdsOldColumnsSql
        Assert-That ($holdsBefore -match '^1:[0-9a-f]{32}$') "legal_holds fingerprint looks wrong: $holdsBefore"
    }
    # Task 0029: with access_requests already there, a request made before 028 must keep every column it had.
    $requestsOldColumnsSql = "select count(*) || ':' || coalesce(md5(string_agg(x::text, '|' order by x.request_id)), '') from access_requests x;"
    if (-not $has027) {
        $null = Invoke-Sql -Database $db -AsOwner -Sql "insert into access_requests(request_id, tenant_id, kind, document_id, requested_level, reason, requested_by, requested_at, status, expires_at) values ('90000000-0000-0000-0000-0000000000a1', '10000000-0000-0000-0000-000000000001', 'LOWER_LEVEL', '40000000-0000-0000-0000-000000000001', 'STANDARD', 'Marked by mistake', 'steward', now(), 'PENDING', now() + interval '7 days');"
        $requestsBefore = Get-Value $requestsOldColumnsSql
        Assert-That ($requestsBefore -match '^1:[0-9a-f]{32}$') "access_requests fingerprint looks wrong: $requestsBefore"
    }
    # Task 0031: with sensitivity_level_changes already there, a level change made before 029 must keep every column.
    $changesOldColumnsSql = "select count(*) || ':' || coalesce(md5(string_agg(x::text, '|' order by x.change_id)), '') from sensitivity_level_changes x;"
    if (-not $has028) {
        $null = Invoke-Sql -Database $db -AsOwner -Sql "insert into sensitivity_level_changes(change_id, tenant_id, scope, document_id, direction, from_level, to_level, reason, changed_by, changed_at) values ('a0000000-0000-0000-0000-0000000000c1', '10000000-0000-0000-0000-000000000001', 'DOCUMENT', '40000000-0000-0000-0000-000000000001', 'RAISED', 'CONFIDENTIAL', 'HIGHLY_CONFIDENTIAL', 'Salary details', 'steward', now());"
        $changesBefore = Get-Value $changesOldColumnsSql
        Assert-That ($changesBefore -match '^1:[0-9a-f]{32}$') "sensitivity_level_changes fingerprint looks wrong: $changesBefore"
    }
    $docsBefore = Get-Value $documentsOldColumnsSql
    $othersBefore = Get-Value $otherTablesSql
    $tenantsBefore = Get-Value $tenantsOldColumnsSql
    Assert-That ($tenantsBefore -match '^[1-9][0-9]*:[0-9a-f]{32}$') "tenants fingerprint looks wrong: $tenantsBefore"
    # The fingerprints must really hold the rows, or "unchanged" below would prove nothing.
    Assert-That ($docsBefore -match '^4:[0-9a-f]{32}$') "documents fingerprint looks wrong: $docsBefore"
    Assert-That ($othersBefore -match '(^|,)submissions=1:[0-9a-f]{32}(,|$)' -and $othersBefore -match '(^|,)ingestion_sources=1:[0-9a-f]{32}(,|$)') "table fingerprints look wrong: $othersBefore"
    $levelsNote = if ($has023) { '' } else { '; levels CONFIDENTIAL, HIGHLY_CONFIDENTIAL, STANDARD, STANDARD' }
    Write-Step '2. Data at the previous version' $w.Elapsed.TotalSeconds "4 documents (available, received, failed, deleted)$levelsNote"

    # ------------------------------------------------------------ 3. schema step
    $st = Invoke-SchemaStep @('status')
    Assert-That ($st.Code -eq 0) "status failed: $($st.Err.Trim())"
    $wouldRun = ([regex]::Match($st.Out, '(\d+) changesets would run')).Groups[1].Value
    Assert-That ($wouldRun -eq [string]$newChangesets.Count) "status should list $($newChangesets.Count) changesets ($($newChangesets -join ', ')), said: $($st.Out.Trim())"
    foreach ($c in $newChangesets) { Assert-That ($st.Out.Contains($c)) "status does not list $c" }
    Write-Step '3a. Schema step status' $st.Seconds "$wouldRun would run: $($newChangesets -join ', ')"

    $sqlUpgrade = Join-Path $run 'schema-upgrade.sql'
    $sq = Invoke-SchemaStep @('sql', '-Output', $sqlUpgrade)
    Assert-That ($sq.Code -eq 0 -and (Test-Path $sqlUpgrade)) "sql failed: $($sq.Err.Trim())"
    $upgradeText = Get-Content -Raw $sqlUpgrade
    if ($has023) {
        Assert-That ($upgradeText -match "add column if not exists sensitivity_level text not null default 'STANDARD'") 'the reviewable SQL lacks the new column'
        Assert-That ($upgradeText -match 'ck_documents_sensitivity_level') 'the reviewable SQL lacks the check constraint'
    }
    if ($has024) {
        Assert-That ($upgradeText -match 'create table if not exists record_sensitivity_levels') 'the reviewable SQL lacks record_sensitivity_levels'
        foreach ($c in @('ck_record_sensitivity_levels_level', 'ck_record_sensitivity_levels_key_sha256', 'uq_record_sensitivity_levels_record', 'fk_record_sensitivity_levels_source')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has025) {
        foreach ($c in ($roleTables | ForEach-Object { "create table $_" }) + @('uq_role_conflict_problems', 'ck_role_conflict_problems_seen', 'idx_role_conflict_problems_tenant_seen', 'idx_role_mapping_notices_tenant_time')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has026) {
        foreach ($c in @('create table legal_holds', 'fk_legal_holds_document', 'ck_legal_holds_reason', 'ck_legal_holds_release', 'ck_legal_holds_times', 'uq_legal_holds_active', 'idx_legal_holds_tenant_active')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has027) {
        foreach ($c in @('add column if not exists placed_by_display_name', 'add column if not exists released_by_display_name', 'create table access_requests',
                'ck_access_requests_target', 'ck_access_requests_not_own', 'ck_access_requests_used', 'idx_access_requests_pending',
                'idx_access_requests_grants', 'uq_access_requests_pending_document', 'uq_access_requests_pending_record')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has028) {
        foreach ($c in @('create table sensitivity_level_changes', 'ck_level_changes_target', 'ck_level_changes_from', 'ck_level_changes_request',
                'ck_level_changes_reason', 'fk_level_changes_document', 'idx_level_changes_document', 'idx_level_changes_record')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has029) {
        foreach ($c in @('create table document_duplicate_receipts', 'fk_duplicate_receipts_submission', 'fk_duplicate_receipts_document',
                'ck_duplicate_receipts_identity', 'ck_duplicate_receipts_sha', 'ck_duplicate_receipts_level', 'ck_duplicate_receipts_outcome',
                'uq_duplicate_receipts_source_document', 'uq_duplicate_receipts_idempotency', 'idx_duplicate_receipts_received',
                'idx_duplicate_receipts_submission')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has030) {
        foreach ($c in @('add column time_zone', 'ck_tenants_time_zone')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    if ($has031) {
        foreach ($c in $reportIndexes) {
            Assert-That ($upgradeText.Contains("create index concurrently $c")) "the reviewable SQL lacks create index concurrently $c"
        }
    }
    if ($has032) {
        foreach ($c in @('create table steward_notices', 'fk_steward_notices_source', 'ck_steward_notices_kind', 'ck_steward_notices_read_by',
                'uq_steward_notices_event', 'idx_steward_notices_tenant_created', 'insert into steward_notices')) {
            Assert-That ($upgradeText.Contains($c)) "the reviewable SQL lacks $c"
        }
    }
    Assert-That (-not ($upgradeText -match "::$lastPrevious::")) 'the reviewable SQL repeats an already applied changeset'
    Assert-That ((Get-Value $hasColumnSql) -eq $hasColumn) 'sql changed the database (documents)'
    Assert-That ((Get-Value $hasTableSql) -eq $recordTableBefore) 'sql changed the database (record_sensitivity_levels)'
    Assert-That ((Get-Value $hasRoleTablesSql) -eq $roleTablesBefore) 'sql changed the database (role tables)'
    Assert-That ((Get-Value $hasHoldTableSql) -eq $holdTableBefore) 'sql changed the database (legal_holds)'
    Assert-That ((Get-Value $hasRequestTableSql) -eq $requestTableBefore) 'sql changed the database (access_requests)'
    Assert-That ((Get-Value $hasChangeTableSql) -eq $changeTableBefore) 'sql changed the database (sensitivity_level_changes)'
    Assert-That ((Get-Value $hasDuplicateTableSql) -eq $duplicateTableBefore) 'sql changed the database (document_duplicate_receipts)'
    Assert-That ((Get-Value $hasTimeZoneSql) -eq $(if ($has030) { '0' } else { '1' })) 'sql changed the database (tenants.time_zone)'
    Assert-That ((Get-Value $reportIndexesSql) -eq $indexesBefore) 'sql changed the database (report indexes)'
    Assert-That ((Get-Value $hasNoticeTableSql) -eq $noticeTableBefore) 'sql changed the database (steward_notices)'
    Write-Step '3b. Schema step sql (the SQL to review)' $sq.Seconds "only $($newChangesets.ForEach({ $_.Substring(0, 3) }) -join ' and '); database unchanged"

    $up = Invoke-SchemaStep @('update')
    Assert-That ($up.Code -eq 0) "update failed: $($up.Err.Trim()) $($up.Out.Trim())"
    Write-Step '3c. Schema step update' $up.Seconds (($up.Out.Trim() -split "`n" | Select-Object -Last 1).Trim())

    # ------------------------------------------------------------ 4. after the upgrade
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    Assert-That ((Get-Value $documentsOldColumnsSql) -eq $docsBefore) 'a document column that existed before changed'
    Assert-That ((Get-Value $otherTablesSql) -eq $othersBefore) 'a table other than documents and record_sensitivity_levels changed'
    Assert-That ((Get-Value $tenantsOldColumnsSql) -eq $tenantsBefore) 'a tenant column that existed before changed'
    if ($has023) {
        $levels = Get-Value "select string_agg(sensitivity_level, ',' order by document_id) from documents;"
        Assert-That ($levels -eq 'STANDARD,STANDARD,STANDARD,STANDARD') "every existing document should be STANDARD, got $levels"
        $column = Get-Value "select is_nullable || ' ' || data_type || ' ' || column_default from information_schema.columns where table_name = 'documents' and column_name = 'sensitivity_level';"
        Assert-That ($column -eq "NO text 'STANDARD'::text") "the column should be text, not null, default STANDARD; is $column"
        foreach ($bad in @('confidential', 'Confidential', 'SECRET', '')) {
            $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "update documents set sensitivity_level = '$bad' where source_document_id = 'd1';"
            Assert-That ($r.Code -ne 0 -and $r.Err -match 'ck_documents_sensitivity_level') "the check should refuse '$bad'"
        }
        $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "update documents set sensitivity_level = null where source_document_id = 'd1';"
        Assert-That ($r.Code -ne 0 -and $r.Err -match 'not-null') 'the column should refuse null'
        $null = Invoke-Sql -Database $db -AsOwner -Sql "begin; update documents set sensitivity_level = 'CONFIDENTIAL' where source_document_id = 'd1'; update documents set sensitivity_level = 'HIGHLY_CONFIDENTIAL' where source_document_id = 'd2'; rollback;"
        Write-Step '4a. After 023: existing documents STANDARD' $w.Elapsed.TotalSeconds 'not null, default STANDARD; check refuses lower-case, unknown, blank and null'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has024) {
        Assert-That ((Get-Value $hasTableSql) -eq '1') 'record_sensitivity_levels was not created'
        Assert-That ((Get-Value 'select count(*) from record_sensitivity_levels;') -eq '0') 'record_sensitivity_levels should start empty'
        if (-not $has023) {
            $levels = Get-Value "select string_agg(sensitivity_level, ',' order by document_id) from documents;"
            Assert-That ($levels -eq 'CONFIDENTIAL,HIGHLY_CONFIDENTIAL,STANDARD,STANDARD') "document levels should be unchanged, got $levels"
        }
        $nullable = Get-Value "select string_agg(column_name || ':' || is_nullable, ',' order by ordinal_position) from information_schema.columns where table_name = 'record_sensitivity_levels';"
        Assert-That ($nullable -eq 'record_level_id:NO,tenant_id:NO,source_id:NO,record_key:NO,record_key_sha256:NO,sensitivity_level:NO,reason:YES,set_by:NO,set_at:NO,updated_at:NO') "unexpected columns: $nullable"

        # The application computes the SHA-256 over the key's UTF-8 bytes (RecordKeyTest pins sha256('abc')); the
        # database check must agree, also for a non-ASCII value. The value is built from code points on both sides so
        # no console code page is involved.
        Assert-That ((Get-Value "select encode(sha256(convert_to('abc', 'UTF8')), 'hex');") -eq 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad') 'the database SHA-256 of abc is wrong'
        $key = 'identifiers:[["name","Zo' + [char]0x00EB + '"]]'
        $sha = -join ([System.Security.Cryptography.SHA256]::Create().ComputeHash([System.Text.Encoding]::UTF8.GetBytes($key)) | ForEach-Object { $_.ToString('x2') })
        $dbSha = Get-Value "select encode(sha256(convert_to('identifiers:[[""name"",""Zo' || chr(235) || '""]]', 'UTF8')), 'hex');"
        Assert-That ($dbSha -eq $sha) "the database and the application disagree on the SHA-256 of a non-ASCII key ($dbSha vs $sha)"

        $t = "'10000000-0000-0000-0000-000000000001'"; $s = "'20000000-0000-0000-0000-000000000001'"
        $k = "'identifiers:[[""contractNumber"",""300000000076""]]'"
        $insert = { param($id, $key, $level, $source)
            "insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_key, record_key_sha256, sensitivity_level, reason, set_by, set_at, updated_at) " +
            "values ('$id', $t, $source, $key, encode(sha256(convert_to($key, 'UTF8')), 'hex'), '$level', null, 'SOURCE', now(), now())" }
        $first = & $insert '50000000-0000-0000-0000-000000000001' $k 'CONFIDENTIAL' $s
        $null = Invoke-Sql -Database $db -AsOwner -Sql "begin; $first; $(& $insert '50000000-0000-0000-0000-000000000002' $k 'HIGHLY_CONFIDENTIAL' $s) on conflict (tenant_id, source_id, record_key_sha256) do nothing; select 1/(case when (select count(*) from record_sensitivity_levels) = 1 and (select sensitivity_level from record_sensitivity_levels) = 'CONFIDENTIAL' then 1 else 0 end); rollback;"
        $refusals = [ordered]@{
            'ck_record_sensitivity_levels_level'      = (& $insert '50000000-0000-0000-0000-000000000003' $k 'confidential' $s)
            'ck_record_sensitivity_levels_key_sha256' = "insert into record_sensitivity_levels(record_level_id, tenant_id, source_id, record_key, record_key_sha256, sensitivity_level, set_by, set_at, updated_at) values ('50000000-0000-0000-0000-000000000004', $t, $s, $k, repeat('0', 64), 'STANDARD', 'SOURCE', now(), now())"
            'uq_record_sensitivity_levels_record'     = "$first; $(& $insert '50000000-0000-0000-0000-000000000005' $k 'STANDARD' $s)"
            'fk_record_sensitivity_levels_source'     = (& $insert '50000000-0000-0000-0000-000000000006' $k 'STANDARD' "'20000000-0000-0000-0000-000000000099'")
            'ck_record_sensitivity_levels_key'        = (& $insert '50000000-0000-0000-0000-000000000007' "''" 'STANDARD' $s)
        }
        foreach ($name in $refusals.Keys) {
            $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "begin; $($refusals[$name]); rollback;"
            Assert-That ($r.Code -ne 0 -and $r.Err -match $name) "$name should refuse the row; got exit $($r.Code): $($r.Err.Trim())"
        }
        Assert-That ((Get-Value 'select count(*) from record_sensitivity_levels;') -eq '0') 'a check row was left behind'
        Write-Step '4b. After 024: record_sensitivity_levels' $w.Elapsed.TotalSeconds 'empty; checks refuse bad level, wrong SHA, duplicate, unknown source, blank key; SHA agrees'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has025) {
        Assert-That ((Get-Value $hasRoleTablesSql) -eq '3') 'the three role tables were not created'
        foreach ($table in $roleTables) { Assert-That ((Get-Value "select count(*) from $table;") -eq '0') "$table should start empty" }
        $t = "'10000000-0000-0000-0000-000000000001'"
        # The statements JdbcRoleGovernanceRepository runs, with literal values. A second sighting adds no row (the
        # insert does nothing) and the update counts it; the mapping upsert keeps one row per role.
        $conflictInsert = { param($id)
            "insert into role_conflict_problems(problem_id, tenant_id, subject, display_name, resolved_from, held_roles, withheld_roles, broken_rules, first_seen_at, last_seen_at, occurrences) " +
            "values ('$id', $t, 'person-1', 'Pat Lee', 'LOGIN_GROUPS', 'BUSINESS_ADMIN,BUSINESS_USER,RESTRICTED_STEWARD', 'BUSINESS_ADMIN,RESTRICTED_STEWARD', 'RESTRICTED_STEWARD_NOT_ADMIN', now(), now(), 1)" }
        $onConflict = ' on conflict (tenant_id, subject, resolved_from, held_roles) do nothing'
        $repositorySql = @"
begin;
$(& $conflictInsert '60000000-0000-0000-0000-000000000001')$onConflict;
$(& $conflictInsert '60000000-0000-0000-0000-000000000002')$onConflict;
update role_conflict_problems set last_seen_at = greatest(last_seen_at, now()), occurrences = occurrences + 1, display_name = coalesce(null, display_name), withheld_roles = 'BUSINESS_ADMIN,RESTRICTED_STEWARD', broken_rules = 'RESTRICTED_STEWARD_NOT_ADMIN'
 where tenant_id = $t and subject = 'person-1' and resolved_from = 'LOGIN_GROUPS' and held_roles = 'BUSINESS_ADMIN,BUSINESS_USER,RESTRICTED_STEWARD';
select 1/(case when (select count(*) from role_conflict_problems) = 1 and (select occurrences from role_conflict_problems) = 2 and (select display_name from role_conflict_problems) = 'Pat Lee' then 1 else 0 end);
insert into role_group_mappings_seen(role_name, login_groups, recorded_at) values ('RESTRICTED_STEWARD', '', now())
  on conflict (role_name) do update set login_groups = excluded.login_groups, recorded_at = excluded.recorded_at;
insert into role_group_mappings_seen(role_name, login_groups, recorded_at) values ('RESTRICTED_STEWARD', 'hc-readers,vero-restricted', now())
  on conflict (role_name) do update set login_groups = excluded.login_groups, recorded_at = excluded.recorded_at;
select 1/(case when (select count(*) from role_group_mappings_seen) = 1 and (select login_groups from role_group_mappings_seen) = 'hc-readers,vero-restricted' then 1 else 0 end);
insert into role_mapping_notices(notice_id, tenant_id, role_name, groups_before, groups_after, created_at) values ('70000000-0000-0000-0000-000000000001', $t, 'RESTRICTED_STEWARD', null, 'hc-readers,vero-restricted', now());
rollback;
"@
        $null = Invoke-Sql -Database $db -AsOwner -Sql $repositorySql
        $first = & $conflictInsert '60000000-0000-0000-0000-000000000003'
        $roleRefusals = [ordered]@{
            'role_conflict_problems_resolved_from_check' = $first -replace "'LOGIN_GROUPS'", "'SOMEWHERE_ELSE'"
            'role_conflict_problems_held_roles_check'    = $first -replace "'BUSINESS_ADMIN,BUSINESS_USER,RESTRICTED_STEWARD'", "''"
            'role_conflict_problems_occurrences_check'   = $first -replace ', 1\)$', ', 0)'
            'ck_role_conflict_problems_seen'             = $first -replace "now\(\), now\(\), 1\)$", "now(), now() - interval '1 minute', 1)"
            'role_conflict_problems_tenant_id_fkey'      = $first -replace [regex]::Escape($t), "'10000000-0000-0000-0000-000000000099'"
            'uq_role_conflict_problems'                  = "$first; $(& $conflictInsert '60000000-0000-0000-0000-000000000004')"
            'role_mapping_notices_tenant_id_fkey'        = "insert into role_mapping_notices(notice_id, tenant_id, role_name, groups_before, groups_after, created_at) values ('70000000-0000-0000-0000-000000000002', '10000000-0000-0000-0000-000000000099', 'RESTRICTED_STEWARD', null, '', now())"
        }
        foreach ($name in $roleRefusals.Keys) {
            $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "begin; $($roleRefusals[$name]); rollback;"
            Assert-That ($r.Code -ne 0 -and $r.Err -match $name) "$name should refuse the row; got exit $($r.Code): $($r.Err.Trim())"
        }
        foreach ($table in $roleTables) { Assert-That ((Get-Value "select count(*) from $table;") -eq '0') "a check row was left behind in $table" }
        Write-Step '4c. After 025: role tables' $w.Elapsed.TotalSeconds 'empty; repository statements behave; checks refuse bad source, blank roles, count 0, time order, unknown tenant, duplicate'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has026) {
        Assert-That ((Get-Value $hasHoldTableSql) -eq '1') 'legal_holds was not created'
        Assert-That ((Get-Value 'select count(*) from legal_holds;') -eq '0') 'legal_holds should start empty'
        $nullable = Get-Value "select string_agg(column_name || ':' || is_nullable, ',' order by ordinal_position) from information_schema.columns where table_name = 'legal_holds';"
        $holdColumns = 'hold_id:NO,tenant_id:NO,document_id:NO,reason:NO,placed_by:NO,placed_at:NO,released_by:YES,released_at:YES'
        if ($has027) { $holdColumns += ',placed_by_display_name:YES,released_by_display_name:YES' }
        Assert-That ($nullable -eq $holdColumns) "unexpected columns: $nullable"
        $t = "'10000000-0000-0000-0000-000000000001'"; $d = "'40000000-0000-0000-0000-000000000001'"
        $hold = { param($id, $reason, $releasedBy, $releasedAt, $document)
            "insert into legal_holds(hold_id, tenant_id, document_id, reason, placed_by, placed_at, released_by, released_at) " +
            "values ('$id', $t, $document, $reason, 'admin', now(), $releasedBy, $releasedAt)" }
        # A released hold and a new active one for the same document are fine; a second active one is not.
        $null = Invoke-Sql -Database $db -AsOwner -Sql "begin; $(& $hold '80000000-0000-0000-0000-000000000001' "'Litigation'" "'admin'" 'now()' $d); $(& $hold '80000000-0000-0000-0000-000000000002' "'Litigation'" 'null' 'null' $d); $(& $hold '80000000-0000-0000-0000-000000000003' "'$('x' * 500)'" 'null' 'null' "'40000000-0000-0000-0000-000000000002'"); rollback;"
        $holdRefusals = [ordered]@{
            'ck_legal_holds_reason'      = (& $hold '80000000-0000-0000-0000-000000000004' "'ab'" 'null' 'null' $d)
            'ck_legal_holds_reason '     = (& $hold '80000000-0000-0000-0000-000000000005' "' Litigation'" 'null' 'null' $d)
            'value too long'             = (& $hold '80000000-0000-0000-0000-000000000006' "'$('x' * 501)'" 'null' 'null' $d)
            'ck_legal_holds_release'     = (& $hold '80000000-0000-0000-0000-000000000007' "'Litigation'" "'admin'" 'null' $d)
            'ck_legal_holds_times'       = (& $hold '80000000-0000-0000-0000-000000000008' "'Litigation'" "'admin'" "now() - interval '1 minute'" $d)
            'ck_legal_holds_released_by' = (& $hold '80000000-0000-0000-0000-000000000009' "'Litigation'" "''" 'now()' $d)
            'fk_legal_holds_document'    = (& $hold '80000000-0000-0000-0000-000000000010' "'Litigation'" 'null' 'null' "'40000000-0000-0000-0000-000000000099'")
            'uq_legal_holds_active'      = "$(& $hold '80000000-0000-0000-0000-000000000011' "'Litigation'" 'null' 'null' $d); $(& $hold '80000000-0000-0000-0000-000000000012' "'Inquiry'" 'null' 'null' $d)"
        }
        foreach ($key in $holdRefusals.Keys) {
            $name = $key.Trim()
            $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "begin; $($holdRefusals[$key]); rollback;"
            Assert-That ($r.Code -ne 0 -and $r.Err -match $name) "$name should refuse the row; got exit $($r.Code): $($r.Err.Trim())"
        }
        Assert-That ((Get-Value 'select count(*) from legal_holds;') -eq '0') 'a check row was left behind in legal_holds'
        Write-Step '4d. After 026: legal_holds' $w.Elapsed.TotalSeconds 'empty; history allowed; checks refuse short, padded and long reasons, half a release, release before placing, unknown document, second active hold'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has027) {
        Assert-That ((Get-Value $hasRequestTableSql) -eq '1') 'access_requests was not created'
        Assert-That ((Get-Value 'select count(*) from access_requests;') -eq '0') 'access_requests should start empty'
        $nullable = Get-Value "select string_agg(column_name || ':' || is_nullable, ',' order by ordinal_position) from information_schema.columns where table_name = 'access_requests';"
        Assert-That ($nullable -eq 'request_id:NO,tenant_id:NO,kind:NO,document_id:YES,source_id:YES,record_key:YES,record_key_sha256:YES,requested_level:YES,reason:NO,requested_by:NO,requested_by_display_name:YES,requested_at:NO,status:NO,decided_by:YES,decided_by_display_name:YES,decided_at:YES,expires_at:NO,used_at:YES') "unexpected columns: $nullable"
        if (-not $has026) {
            # The hold placed before 027 keeps every column and gets no name (none is made up).
            Assert-That ((Get-Value $holdsOldColumnsSql) -eq $holdsBefore) 'a legal hold placed before 027 changed'
            Assert-That ((Get-Value "select count(*) from legal_holds where placed_by_display_name is null and released_by_display_name is null;") -eq '1') 'an older hold should have no names'
        }
        Write-Step '4e. After 027: access_requests and hold names' $w.Elapsed.TotalSeconds "empty; hold name columns nullable$(if (-not $has026) { '; the older hold unchanged, no names' })"
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has028) {
        Assert-That ((Get-Value $hasChangeTableSql) -eq '1') 'sensitivity_level_changes was not created'
        Assert-That ((Get-Value 'select count(*) from sensitivity_level_changes;') -eq '0') 'sensitivity_level_changes should start empty'
        $nullable = Get-Value "select string_agg(column_name || ':' || is_nullable, ',' order by ordinal_position) from information_schema.columns where table_name = 'sensitivity_level_changes';"
        Assert-That ($nullable -eq 'change_id:NO,tenant_id:NO,scope:NO,document_id:YES,source_id:YES,record_key:YES,record_key_sha256:YES,direction:NO,from_level:YES,to_level:NO,reason:NO,changed_by:NO,changed_by_display_name:YES,changed_at:NO,access_request_id:YES') "unexpected columns: $nullable"
        if (-not $has027) {
            Assert-That ((Get-Value $requestsOldColumnsSql) -eq $requestsBefore) 'an access request made before 028 changed'
        }
        Write-Step '4g. After 028: sensitivity_level_changes' $w.Elapsed.TotalSeconds "empty$(if (-not $has027) { '; the older access request unchanged' })"
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has029) {
        Assert-That ((Get-Value $hasDuplicateTableSql) -eq '1') 'document_duplicate_receipts was not created'
        Assert-That ((Get-Value 'select count(*) from document_duplicate_receipts;') -eq '0') 'document_duplicate_receipts should start empty'
        $nullable = Get-Value "select string_agg(column_name || ':' || is_nullable, ',' order by ordinal_position) from information_schema.columns where table_name = 'document_duplicate_receipts';"
        Assert-That ($nullable -eq 'duplicate_id:NO,tenant_id:NO,source_id:NO,submission_id:NO,source_document_id:YES,producer_idempotency_key:YES,existing_document_id:NO,content_sha256:NO,content_size_bytes:NO,sent_level:NO,level_outcome:NO,received_at:NO') "unexpected columns: $nullable"
        if (-not $has028) {
            Assert-That ((Get-Value $changesOldColumnsSql) -eq $changesBefore) 'a level change made before 029 changed'
        }
        Write-Step '4h. After 029: document_duplicate_receipts' $w.Elapsed.TotalSeconds "empty$(if (-not $has028) { '; the older level change unchanged' })"
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has030) {
        Assert-That ((Get-Value $hasTimeZoneSql) -eq '1') 'tenants.time_zone was not created'
        $column = Get-Value "select is_nullable || ' ' || data_type || ' ' || character_maximum_length || ' ' || coalesce(column_default, 'no default') from information_schema.columns where table_name = 'tenants' and column_name = 'time_zone';"
        Assert-That ($column -eq 'YES character varying 64 no default') "time_zone should be a nullable varchar(64) without a default; is $column"
        Assert-That ((Get-Value 'select count(*) from tenants where time_zone is not null;') -eq '0') 'every existing tenant should keep the install default (time_zone null)'
        foreach ($bad in @("' UTC'", "'UTC '", "''", "repeat('x', 65)")) {
            $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "update tenants set time_zone = $bad where tenant_id = '10000000-0000-0000-0000-000000000001';"
            Assert-That ($r.Code -ne 0) "the time_zone check should refuse $bad"
        }
        $null = Invoke-Sql -Database $db -AsOwner -Sql "update tenants set time_zone = 'America/New_York' where tenant_id = '10000000-0000-0000-0000-000000000001'; update tenants set time_zone = null where tenant_id = '10000000-0000-0000-0000-000000000001';"
        Write-Step '4i. After 030: tenants.time_zone' $w.Elapsed.TotalSeconds 'nullable, null for every tenant; check refuses padded, blank and long values'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has031) {
        Assert-That ((Get-Value $reportIndexesSql) -eq '3') 'the three report indexes were not created'
        $invalid = Get-Value ("select count(*) from pg_index i join pg_class c on c.oid = i.indexrelid where not i.indisvalid and c.relname in ('" + ($reportIndexes -join "', '") + "');")
        Assert-That ($invalid -eq '0') 'a report index is INVALID (an interrupted concurrent build)'
        $partial = Get-Value "select indexdef from pg_indexes where indexname = 'idx_audit_events_redaction_old_sha';"
        Assert-That ($partial -match "oldContentSha256" -and $partial -match "DOCUMENT_PERMANENTLY_REDACTED") "the redaction index should be on oldContentSha256, for redactions only: $partial"
        Write-Step '4j. After 031: report indexes' $w.Elapsed.TotalSeconds 'three indexes, valid; the old-checksum index is partial'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has032) {
        Assert-That ((Get-Value $hasNoticeTableSql) -eq '1') 'steward_notices was not created'
        # The uncorrected copy received before became exactly one notice, unread by everyone, for its sending system.
        $notice = Get-Value "select count(*) || ' ' || coalesce(min(kind), '-') || ' ' || coalesce(min(source_id::text), '-') || ' ' || coalesce(min(read_by::text), '-') || ' ' || bool_and(created_at = (select occurred_at from audit_events where audit_event_id = '$oldCopyEvent')) from steward_notices where audit_event_id = '$oldCopyEvent';"
        Assert-That ($notice -eq '1 UNCORRECTED_COPY_RECEIVED 20000000-0000-0000-0000-000000000001 {} true') "the earlier uncorrected copy should be one unread notice at its time: $notice"
        Assert-That ((Get-Value 'select count(*) from steward_notices;') -eq '1') 'only uncorrected copies become notices'
        foreach ($bad in @(
                "insert into steward_notices(notice_id, tenant_id, kind, audit_event_id, created_at) values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'OTHER', '$oldCopyEvent', now());",
                "insert into steward_notices(notice_id, tenant_id, kind, audit_event_id, created_at) values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'UNCORRECTED_COPY_RECEIVED', '$oldCopyEvent', now());",
                "insert into steward_notices(notice_id, tenant_id, kind, audit_event_id, created_at) values (gen_random_uuid(), '10000000-0000-0000-0000-000000000001', 'UNCORRECTED_COPY_RECEIVED', gen_random_uuid(), now());",
                "update steward_notices set read_by = '[]'::jsonb;",
                "update steward_notices set record_identifier = ' ';")) {
            $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql $bad
            Assert-That ($r.Code -ne 0) "steward_notices should refuse: $bad"
        }
        Write-Step '4k. After 032: steward_notices' $w.Elapsed.TotalSeconds 'the earlier uncorrected copy is one unread notice; checks refuse a bad kind, a second notice, an unknown event, a bad read_by'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    if ($has027 -or $has028 -or $has029 -or $has030 -or $has031 -or $has032) {

        # The application's own repositories and services against this database: holds and the retention cleanup
        # (LegalHoldPostgresTest), requests, grants, expiry and the table's checks (AccessRequestPostgresTest), exact
        # duplicates (DuplicateIntakePostgresTest), uncorrected copies, stored notices and the Daily intake report (IntakeReportPostgresTest),
        # the Sensitivity report (SensitivityReportPostgresTest), one ACTIVE sign-in connection per issuer (SignInConnectionPostgresTest, task 0038). The scratch owner's password goes to the child process only, as
        # SDV_IT_JDBC_PASSWORD.
        $w = [System.Diagnostics.Stopwatch]::StartNew()
        $mvn = (Get-Command mvn.cmd -ErrorAction Stop).Source
        $it = Invoke-Native -File $mvn -Arguments @('-o', '-f', (Join-Path $root 'backend\pom.xml'), '-pl', 'sdv-web', '-am', 'test',
            '-Dtest=LegalHoldPostgresTest,AccessRequestPostgresTest,DuplicateIntakePostgresTest,IntakeReportPostgresTest,SensitivityReportPostgresTest,SignInConnectionPostgresTest', '-Dsurefire.failIfNoSpecifiedTests=false') -Environment @{
            SDV_IT_JDBC_URL = $hostUrl; SDV_IT_JDBC_USER = $owner; SDV_IT_JDBC_PASSWORD = $ownerPassword }
        foreach ($test in @('legalhold.LegalHoldPostgresTest', 'approvals.AccessRequestPostgresTest', 'document.DuplicateIntakePostgresTest', 'reports.IntakeReportPostgresTest', 'reports.SensitivityReportPostgresTest', 'identity.SignInConnectionPostgresTest')) {
            $itLine = ($it.Out -split "`n" | Where-Object { $_ -match ('Tests run: .* in com\.sdv\.platform\.application\.' + [regex]::Escape($test)) } | Select-Object -First 1)
            if ($it.Code -ne 0 -or -not $itLine -or $itLine -notmatch 'Tests run: 1, Failures: 0, Errors: 0, Skipped: 0') {
                $it.Out | Set-Content -Encoding utf8 (Join-Path $run 'postgres-tests.log')
                throw "FAIL: $test did not pass (exit $($it.Code)): $(if ($itLine) { $itLine.Trim() } else { 'no result line' }). Log: $run\postgres-tests.log"
            }
        }
        Write-Step '4f. Holds, requests, grants, duplicates and reports on PostgreSQL' $w.Elapsed.TotalSeconds 'holds and cleanup; grants, expiry, level history, duplicates, uncorrected copies, stored notices, the intake and Sensitivity reports, checks'
        $w = [System.Diagnostics.Stopwatch]::StartNew()
    }
    Write-Step '4. After: nothing else changed' $w.Elapsed.TotalSeconds 'other document columns and every other table unchanged'

    $st2 = Invoke-SchemaStep @('status')
    Assert-That ($st2.Code -eq 0 -and $st2.Out -match 'Up to date') "status after update should say up to date: $($st2.Out.Trim())"
    $after = Get-Value 'select count(*) from databasechangelog;'
    Assert-That ([int]$after -eq [int]$prevCount + $newChangesets.Count) "the changelog should have $($newChangesets.Count) more rows ($prevCount -> $after)"
    Write-Step '   Schema step status again' $st2.Seconds "up to date; $after changesets"
}
finally {
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    try { Remove-Scratch; $gone = (Invoke-Sql -Database 'postgres' -Sql "select count(*) from pg_database where datname = '$db';").Out.Trim() }
    catch { Write-Host "Cleanup failed: $($_.Exception.Message). Rerun with -DropLeftover." -ForegroundColor Red; $gone = '?' }
    if ($gone -eq '0') { Write-Step "Drop $db and its owner" $w.Elapsed.TotalSeconds 'dropped' }
    $results | ConvertTo-Json | Set-Content -Encoding utf8 (Join-Path $run 'schema-upgrade-steps.json')
}

Write-Host ''
$results | Format-Table -AutoSize | Out-String | Write-Host
Write-Host ("PASS - schema upgrade check passed in {0:N0} s. Files: {1}" -f $total.Elapsed.TotalSeconds, $run) -ForegroundColor Green
