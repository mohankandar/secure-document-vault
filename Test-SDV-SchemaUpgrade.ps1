<#
.SYNOPSIS
  Repeatable upgrade check for task 0024: a database at the previous schema (up to changeset 022) moves to this
  version's schema (023, the document sensitivity level) with every existing document STANDARD and nothing else changed.

.DESCRIPTION
  Uses a scratch database, sdv_schema_test, inside the existing sdv-postgres server, and one scratch role,
  sdv_schema_owner, that owns it and runs the schema step. Its password is random for each run, held only in memory
  and never printed. The script never touches the sdv database and never creates, removes or recreates a container.
  Everything it creates is dropped at the end, also when a step fails.

  Steps:
    1. Previous version: the schema step's own "sql" output for an empty database, cut just before changeset 023, is
       applied with psql. Changesets 000-022 are the same files as on release/vero-r7, so this is the previous
       version's schema and changelog. Checked: 022 is the last changeset and documents has no sensitivity_level.
    2. Data at the previous version: a tenant, a sending system, a submission and documents in four states.
    3. Schema step: status lists only 023; sql (the SQL the database team reviews) holds the 023 statements; update.
    4. After: every document is STANDARD, the column is not null with default STANDARD, the check refuses unknown and
       lower-case values, every other column of every row is unchanged, no other table changed, status is up to date.

  Prerequisite: the application jar is built (.\Build-SDV-All.ps1).
#>
[CmdletBinding()]
param(
    [string]$Jar,
    # Drop a scratch database and role left by an earlier run of this script that could not clean up.
    [switch]$DropLeftover
)

$ErrorActionPreference = 'Stop'
$root = $PSScriptRoot
$container = 'sdv-postgres'
$db = 'sdv_schema_test'
$owner = 'sdv_schema_owner'
$hostUrl = 'jdbc:postgresql://localhost:5433/' + $db
$newChangeset = '023-document-sensitivity-level'
$lastPrevious = '022-retention-policy-creator-name'
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

# Every row of every application table except documents, as one fingerprint per table; and documents without the new column.
$otherTablesSql = @"
select string_agg(t.relname || '=' || (xpath('/row/c/text()', query_to_xml(format('select count(*) || '':'' || coalesce(md5(string_agg(x::text, ''|'' order by x::text)), '''') as c from %I x', t.relname), false, true, '')))[1]::text, ',' order by t.relname)
  from pg_stat_user_tables t
 where t.relname not in ('documents', 'databasechangelog', 'databasechangeloglock');
"@
$documentsOldColumnsSql = @"
select count(*) || ':' || md5(string_agg(row(document_id, tenant_id, submission_id, source_id, source_document_id, producer_idempotency_key,
       original_filename, media_type, content_sha256, content_size_bytes, status, retention_days_snapshot, ingested_at, expires_at,
       available_at, processing_attempt_count, processing_error_code, deleted_at, deletion_reason, page_count)::text, '|' order by document_id))
  from documents;
"@

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

    # ------------------------------------------------------------ 1. previous version (up to 022)
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
    $hasColumn = Get-Value "select count(*) from information_schema.columns where table_name = 'documents' and column_name = 'sensitivity_level';"
    Assert-That ($last -eq $lastPrevious) "the previous version should end with $lastPrevious, ends with $last"
    Assert-That ($hasColumn -eq '0') 'documents already has sensitivity_level at the previous version'
    $prevCount = Get-Value 'select count(*) from databasechangelog;'
    Write-Step '1. Previous version (changesets up to 022)' $w.Elapsed.TotalSeconds "$prevCount changesets, last $last, no sensitivity_level"

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
    $docsBefore = Get-Value $documentsOldColumnsSql
    $othersBefore = Get-Value $otherTablesSql
    # The fingerprints must really hold the rows, or "unchanged" below would prove nothing.
    Assert-That ($docsBefore -match '^4:[0-9a-f]{32}$') "documents fingerprint looks wrong: $docsBefore"
    Assert-That ($othersBefore -match '(^|,)tenants=1:[0-9a-f]{32}(,|$)' -and $othersBefore -match '(^|,)submissions=1:[0-9a-f]{32}(,|$)' -and $othersBefore -match '(^|,)ingestion_sources=1:[0-9a-f]{32}(,|$)') "table fingerprints look wrong: $othersBefore"
    Write-Step '2. Data at the previous version' $w.Elapsed.TotalSeconds "4 documents (available, received, failed, deleted)"

    # ------------------------------------------------------------ 3. schema step
    $st = Invoke-SchemaStep @('status')
    Assert-That ($st.Code -eq 0) "status failed: $($st.Err.Trim())"
    $wouldRun = ([regex]::Match($st.Out, '(\d+) changesets would run')).Groups[1].Value
    Assert-That ($wouldRun -eq '1' -and $st.Out.Contains($newChangeset)) "status should list only $newChangeset, said: $($st.Out.Trim())"
    Write-Step '3a. Schema step status' $st.Seconds "1 changeset would run: $newChangeset"

    $sqlUpgrade = Join-Path $run 'schema-upgrade.sql'
    $sq = Invoke-SchemaStep @('sql', '-Output', $sqlUpgrade)
    Assert-That ($sq.Code -eq 0 -and (Test-Path $sqlUpgrade)) "sql failed: $($sq.Err.Trim())"
    $upgradeText = Get-Content -Raw $sqlUpgrade
    Assert-That ($upgradeText -match "add column if not exists sensitivity_level text not null default 'STANDARD'") 'the reviewable SQL lacks the new column'
    Assert-That ($upgradeText -match 'ck_documents_sensitivity_level') 'the reviewable SQL lacks the check constraint'
    Assert-That (-not ($upgradeText -match "::$lastPrevious::")) 'the reviewable SQL repeats an already applied changeset'
    Assert-That ((Get-Value "select count(*) from information_schema.columns where table_name = 'documents' and column_name = 'sensitivity_level';") -eq '0') 'sql changed the database'
    Write-Step '3b. Schema step sql (the SQL to review)' $sq.Seconds 'only 023: the column and its check; database unchanged'

    $up = Invoke-SchemaStep @('update')
    Assert-That ($up.Code -eq 0) "update failed: $($up.Err.Trim()) $($up.Out.Trim())"
    Write-Step '3c. Schema step update' $up.Seconds (($up.Out.Trim() -split "`n" | Select-Object -Last 1).Trim())

    # ------------------------------------------------------------ 4. after the upgrade
    $w = [System.Diagnostics.Stopwatch]::StartNew()
    $levels = Get-Value "select string_agg(sensitivity_level, ',' order by document_id) from documents;"
    Assert-That ($levels -eq 'STANDARD,STANDARD,STANDARD,STANDARD') "every existing document should be STANDARD, got $levels"
    $column = Get-Value "select is_nullable || ' ' || data_type || ' ' || column_default from information_schema.columns where table_name = 'documents' and column_name = 'sensitivity_level';"
    Assert-That ($column -eq "NO text 'STANDARD'::text") "the column should be text, not null, default STANDARD; is $column"
    Assert-That ((Get-Value $documentsOldColumnsSql) -eq $docsBefore) 'a document column other than sensitivity_level changed'
    Assert-That ((Get-Value $otherTablesSql) -eq $othersBefore) 'a table other than documents changed'
    foreach ($bad in @('confidential', 'Confidential', 'SECRET', '')) {
        $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "update documents set sensitivity_level = '$bad' where source_document_id = 'd1';"
        Assert-That ($r.Code -ne 0 -and $r.Err -match 'ck_documents_sensitivity_level') "the check should refuse '$bad'"
    }
    $r = Invoke-Sql -Database $db -AsOwner -AllowFailure -Sql "update documents set sensitivity_level = null where source_document_id = 'd1';"
    Assert-That ($r.Code -ne 0 -and $r.Err -match 'not-null') 'the column should refuse null'
    $null = Invoke-Sql -Database $db -AsOwner -Sql "begin; update documents set sensitivity_level = 'CONFIDENTIAL' where source_document_id = 'd1'; update documents set sensitivity_level = 'HIGHLY_CONFIDENTIAL' where source_document_id = 'd2'; rollback;"
    Write-Step '4. After: existing documents STANDARD, nothing else changed' $w.Elapsed.TotalSeconds 'not null, default STANDARD; check refuses lower-case, unknown, blank and null'

    $st2 = Invoke-SchemaStep @('status')
    Assert-That ($st2.Code -eq 0 -and $st2.Out -match 'Up to date') "status after update should say up to date: $($st2.Out.Trim())"
    $after = Get-Value 'select count(*) from databasechangelog;'
    Assert-That ([int]$after -eq [int]$prevCount + 1) "the changelog should have one more row ($prevCount -> $after)"
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
