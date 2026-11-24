<#
.SYNOPSIS
  All local demo data in one command (tasks 0018, 0022, 0023). Local development stack only.

.DESCRIPTION
  One command gives a fresh developer machine (or demo laptop) the demo data the screen tour (npm run ui:capture)
  and npm run ui:compare use:

      .\Seed-SDV-Canvas-Demo.ps1

  It runs Seed-SDV-Demo-Records.ps1 itself (plain, -FreshTestData -Count 3 and -ProblemDemo), so that script no
  longer needs to be run first. What it makes:
  - Organization: the local demo tenant is named "Acme Lending".
  - Storage: "primary-a" in use (.runtime\demo-storage) and "primary-b" on standby (.runtime\demo-storage-b).
    An existing "Local demo volume" is renamed primary-a.
  - Redaction Service (the source the screen tour uses), with Contract Number, Member Number, Service Entity, First Name,
    Last Name (contains) and Loan Type; Record title First Name + Last Name. Records:
      100000000001-100000000003 (names added in the database, as before), 300000000076-300000000078 (Ava, Noah and
      Mia Patel), 300000000094-300000000096 (the Problems demo), and, task 0023:
      300000000090 Omar Patelli, 1 of 2 documents (Insurance certificate still expected: Needs attention);
      300000000092 Lena Patel, the 14-page demo document loan-file.pdf (scripts\demo-files).
    Task 0023: the tag "e2e-demo" and one demo comment on 100000000001's Terms and Conditions.pdf, the document the
    tour opens read-only.
  - A "Loan Origination System" sending system with the canvas's fields (Contract number, Borrower, Loan type,
    Closing date), its Record title set to Borrower, and the canvas's records 300000000001-300000000009,
    delivered through the producer API like any other delivery. 300000000003 (Marcus Lee) has 2 of 4 documents;
    Appraisal and Insurance are still expected. It is delivered last, so it is the sending system that delivered
    most recently (the Records default, task 0023).
  - Task 0022: Co-borrower and Closing agent fields on the Loan Origination System (last in its field order), with
    "Priya Patel" as 300000000004's co-borrower and "Patel & Sons Title" as 300000000006's closing agent.
  - "Title Records" (paused, no fields) for the admin canvas, and, task 0023, the paused "Tour test system
    20261118181639" with two fields that can't be searched: the test case of the Records default rule (task 0017).

  - Tasks 0029 and 0030: the sensitivity demo (see the sections at the end): Highly Confidential and Confidential
    documents, requests in every state, 50 waiting print and download requests from five demo Restricted stewards,
    a whole-contract request and two legal holds.

  It never runs against anything but http://localhost / 127.0.0.1 and a local sdv* database in sdv-postgres.
  Running it again changes nothing: whatever is already there is left as it is (no field, order or title is sent
  again, because every such save adds a version and an audit event).

  Task 0035: it is safe to run in any state. Before each request step it checks what is there. When the demo data
  has moved on (for example a demo document's level was lowered by hand, so the request the step makes is no longer
  possible), the step is skipped with a [SKIP] line instead of failing, and the end of the run says so.

  Reset (task 0035): puts the demo data back to the state this script makes, then runs the seed:

      .\Seed-SDV-Canvas-Demo.ps1 -Reset

  It refuses unless Vero at -Base runs in local mode, the database is a local sdv* database that this seed has
  filled (its canvas-demo deliveries are there) and Vero at -Base is using that database. Through the API, as a
  person would (so each change is audited), it puts back:
  - Roles & permissions: Business user without Download, Print, Tags, Comments and Redact; Document steward with all five.
  - Redaction Service receiving, and the seed's field settings (for example Last Name "Contains").
  - Waiting requests: every waiting request the seed did not make is declined.
  - Levels: the seed's levels on its documents and on 300000000002 (whole contract); every other document and contract
    Standard. A raise is made by "local-demo-reset"; a lowering is asked by "local-demo-reset" and approved by Priya
    Natarajan (Business admin).
  - Legal holds: the seed's three; any other hold is released.
  What it can't put back: a permanent redaction, and tags or comments added by hand (Vero has no way to remove them).
  Requests, decisions and audit events stay in the history; only what is waiting changes.

  The demo PDFs (test-tools\fixtures, scripts\demo-files) are for this local seed only. They are not part of the
  backend jar or the frontend build, and the source-package scripts leave scripts\demo-files out.

.PARAMETER Database
  The local database the app uses, for the few values written directly. A scratch database such as sdv_seed_test
  works too (used by the task 0023 seed check), together with -Base pointing at an app started on it.
#>
[CmdletBinding()]
param(
    [string]$Base = 'http://127.0.0.1:8280',
    [ValidatePattern('^sdv(_[a-z0-9_]+)?$')][string]$Database = 'sdv',
    # Task 0039 part 2: empty means the default under the script's folder. Windows PowerShell 5.1 leaves $PSScriptRoot
    # empty in parameter defaults under powershell.exe -File, so the defaults are filled in below, not here.
    [string]$PdfPath = '',
    [string]$FourteenPagePdfPath = '',
    [string]$StorageFolder = '',
    [string]$StandbyStorageFolder = '',
    # Task 0035: put the demo data back to the seed's known state first (see the description).
    [switch]$Reset
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$ScriptFolder = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }
if (-not $ScriptFolder) { throw 'The folder of Seed-SDV-Canvas-Demo.ps1 could not be found; pass -PdfPath, -FourteenPagePdfPath, -StorageFolder and -StandbyStorageFolder.' }
if (-not $PdfPath) { $PdfPath = Join-Path $ScriptFolder 'test-tools\fixtures\business-viewer-two-page.pdf' }
if (-not $FourteenPagePdfPath) { $FourteenPagePdfPath = Join-Path $ScriptFolder 'scripts\demo-files\loan-file-14-pages.pdf' }
if (-not $StorageFolder) { $StorageFolder = Join-Path $ScriptFolder '.runtime\demo-storage' }
if (-not $StandbyStorageFolder) { $StandbyStorageFolder = Join-Path $ScriptFolder '.runtime\demo-storage-b' }

if ($Base -notmatch '^http://(localhost|127\.0\.0\.1)[:/]') { throw 'The canvas demo data is for the local development stack only.' }
foreach ($f in @($PdfPath, $FourteenPagePdfPath)) { if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { throw "Demo PDF not found: $f" } }

$Tenant = 'bd960ee9-9d54-5053-9958-92aaa54893a6'
$Correlation = 'canvas-demo-seed-' + ([guid]::NewGuid().ToString('N')).Substring(0, 10)
function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
# Task 0035: a step that can't run because the data has moved on is skipped, never failed; the end of the run lists them.
$script:skipped = New-Object System.Collections.Generic.List[string]
function Skip([string]$Message) { $script:skipped.Add($Message); Write-Host "  [SKIP] $Message" -ForegroundColor Yellow }
function Fixed([string]$Message) { Write-Host "  [RESET] $Message" -ForegroundColor Cyan }
function Headers([string]$Subject, [string]$Roles) { @{ 'X-SDV-Tenant-Id' = $Tenant; 'X-SDV-Subject' = $Subject; 'X-SDV-Roles' = $Roles; 'X-Correlation-Id' = $Correlation } }
function SourceHeaders([string]$SourceId) { @{ 'X-SDV-Tenant-Id' = $Tenant; 'X-SDV-Source-Id' = $SourceId; 'X-SDV-Subject' = 'local-demo-producer'; 'X-Correlation-Id' = $Correlation } }
function Api([string]$Method, [string]$Path, [hashtable]$Headers, [object]$Body = $null) {
    $p = @{ Uri = "$Base$Path"; Method = $Method; Headers = $Headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $Body) { $p.ContentType = 'application/json'; $p.Body = ($Body | ConvertTo-Json -Depth 20) }
    try { $r = Invoke-WebRequest @p } catch {
        $detail = ''; if ($_.Exception.Response) { try { $detail = (New-Object IO.StreamReader($_.Exception.Response.GetResponseStream())).ReadToEnd() } catch { } }
        throw "HTTP $Method $Path failed. $detail"
    }
    if ([string]::IsNullOrWhiteSpace($r.Content)) { return $null }
    $r.Content | ConvertFrom-Json
}
# Windows PowerShell returns a JSON array as one object; piping it lists the items.
function ApiList([string]$Path, [hashtable]$Headers) { $raw = Api 'GET' $Path $Headers; @($raw | ForEach-Object { $_ } | Where-Object { $null -ne $_ }) }
function Db([string]$Sql) { (& docker exec sdv-postgres psql -U sdv -d $Database -v ON_ERROR_STOP=1 -tAc $Sql | Out-String).Trim() }
function Lit([string]$s) { "'" + $s.Replace("'", "''") + "'" }

$admin = Headers 'local-demo-business-admin' 'BUSINESS_ADMIN'
$it = Headers 'local-demo-tenant-it' 'IT_ADMIN'
$steward = Headers 'local-document-steward' 'DOCUMENT_STEWARD'

# A field that is already there is left as it is (each save adds a field version and an audit event). With -Reset, a
# field whose settings were changed is saved again with the seed's settings.
function EnsureField([string]$SourceId, [string]$Key, [string]$Label, [string]$Type, [bool]$Identifier, [bool]$Required, [string]$Normalization, [string]$Mode, [bool]$Searchable = $true) {
    $want = "$Label|$Type|$($Required.ToString().ToLower())|$($Identifier.ToString().ToLower())|$($Searchable.ToString().ToLower())|$Normalization|$Mode"
    $have = Db "select display_label || '|' || field_type || '|' || required::text || '|' || business_identifier::text || '|' || searchable::text || '|' || normalization || '|' || search_mode from metadata_field_definitions where tenant_id='$Tenant' and source_id='$SourceId' and field_key=$(Lit $Key) and active order by version desc limit 1;"
    if ($have -and ($have -ceq $want -or -not $Reset)) { Ok "Field already there: $Label"; return }
    $null = Api 'PUT' "/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$Key" $admin @{
        displayLabel = $Label; fieldType = $Type; required = $Required; businessIdentifier = $Identifier; searchable = $Searchable
        normalization = $Normalization; searchMode = $Mode; validationRules = @{}
    }
    if ($have) { Fixed "Field settings put back: $Label (were $have)" } else { Ok "Field added: $Label" }
}
# Field order and Record title are saved only when they differ (a save adds an audit event).
function EnsureKeys([string]$SourceId, [string]$What, [string[]]$Keys) {
    $now = @(ApiList "/api/v1/admin/metadata-sources/$SourceId/$What" $admin)
    if (($now -join ',') -ceq ($Keys -join ',')) { return }
    $null = Api 'PUT' "/api/v1/admin/metadata-sources/$SourceId/$What" $admin @{ fieldKeys = $Keys }
}
function Sources { ApiList '/api/v1/admin/ingestion-sources' $it }
function EnsureSource([string]$Key, [string]$Name, [string]$AuthType, [string]$Credential) {
    $s = Sources | Where-Object { $_.sourceKey -eq $Key } | Select-Object -First 1
    if ($s) { return $s }
    $s = Api 'POST' '/api/v1/admin/ingestion-sources' $it @{ sourceKey = $Key; displayName = $Name; authType = $AuthType; credentialReference = $Credential }
    Ok "Created sending system: $Name"
    $s
}
function SetSourceStatus([object]$Source, [string]$Status) {
    if ($Source.status -eq $Status) { return $Source }
    Api 'PUT' "/api/v1/admin/ingestion-sources/$($Source.sourceId)" $it @{ displayName = $Source.displayName; status = $Status; authType = $Source.authType; credentialReference = $Source.credentialReference }
}
# One delivery through the producer API, found again by its own delivery ID; documents already there are skipped.
# Documents: @{ id; file; path } and, task 0029, an optional level (sent as X-SDV-Sensitivity-Level). Expected: the source
# document IDs the delivery announces. RecordLevel (task 0029): the whole record's level, sent with the submission.
function Deliver([string]$SourceId, [string]$SubKey, [hashtable]$Metadata, [string[]]$Expected, [object[]]$Documents, [string]$Label, [string]$RecordLevel = '') {
    $submissionId = Db "select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id=$(Lit $SubKey) limit 1;"
    if ([string]::IsNullOrWhiteSpace($submissionId)) {
        $body = @{
            sourceSubmissionId = $SubKey; expectedDocumentCount = $Expected.Count; expectedDocuments = @($Expected | ForEach-Object { @{ sourceDocumentId = $_ } }); metadata = $Metadata
        }
        if ($RecordLevel) { $body.recordSensitivityLevel = $RecordLevel }
        $s = Api 'POST' '/api/v1/ingestion/submissions' (SourceHeaders $SourceId) $body
        $submissionId = [string]$s.submissionId
        Ok "Record $Label"
    }
    foreach ($d in $Documents) {
        $have = Db "select count(*) from documents where tenant_id='$Tenant' and submission_id='$submissionId' and source_document_id=$(Lit $d.id);"
        if ([int]$have -eq 0) {
            $h = SourceHeaders $SourceId; $h['X-SDV-Source-Document-Id'] = $d.id; $h['X-SDV-Filename'] = $d.file
            if ($d.ContainsKey('level') -and $d.level) { $h['X-SDV-Sensitivity-Level'] = $d.level }
            # Task 0031: the same file for the same contract is an exact duplicate and is not stored again. The demo
            # uses one sample PDF for many documents, so each upload gets its own copy: the sample plus a PDF comment
            # line after its end that names this delivery (readers ignore it; the checksum differs).
            $copyDir = Join-Path $ScriptFolder '.runtime\seed-pdfs'
            New-Item -ItemType Directory -Force -Path $copyDir | Out-Null
            $copy = Join-Path $copyDir ("$submissionId-" + ($d.id -replace '[^A-Za-z0-9._-]', '_') + '.pdf')
            $bytes = [System.IO.File]::ReadAllBytes($d.path) + [System.Text.Encoding]::ASCII.GetBytes("`n%vero-demo-copy $submissionId $($d.id -replace '[^A-Za-z0-9._-]', '_')`n")
            [System.IO.File]::WriteAllBytes($copy, $bytes)
            $null = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $h -ContentType 'application/pdf' -InFile $copy -UseBasicParsing -TimeoutSec 60
        }
    }
    $submissionId
}

Write-Host "=== SDV DEMO DATA (local only, database $Database) ==="
try { $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5 } catch { throw "SDV is not reachable at $Base. Start it with .\start-local.cmd (run .\Update-SDV-Schema.ps1 update first on a database older than this version)" }
if ($health.status -ne 'UP') { throw "SDV is not UP at $Base. If the database is older than this version, run .\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/$Database -Username sdv, then restart." }
# Task 0071: system information needs sign-in now; the sign-in settings are open and say whether this is local mode.
$signIn = Invoke-RestMethod -Uri "$Base/api/v1/session/sign-in" -TimeoutSec 10
if ($signIn.mode -ne 'local') { throw "The demo data is for a Vero in local mode; Vero at $Base signs people in with mode '$($signIn.mode)'." }

# ---- Task 0041: the demo entities come from platform-core setup and Vero's demo step, never from a changeset ----
# Setup loads the demo licence (signed by the development key, which only a development build trusts: Build-SDV-All.ps1), the customer,
# the two entity slots, the local login connection, the role grants and the client bindings. The demo step activates
# both slots and makes Vero's rows for them (entity UUIDs derived from the slot IDs). Both are safe to run again. The
# database user and password are the local profile's (task 0064: the password from the untracked file), set for these
# two runs only.
$SetupRuntime = Join-Path $ScriptFolder '.runtime\setup'
New-Item -ItemType Directory -Force -Path $SetupRuntime | Out-Null
$localUrl = "jdbc:postgresql://localhost:5433/$Database"
# Their log lines go to stderr; Windows PowerShell would stop on them, so only the exit codes decide here.
$stopOnStderr = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
$env:PLATFORM_SETUP_DB_URL = $localUrl; $env:PLATFORM_SETUP_DB_USER = 'sdv'; $env:PLATFORM_SETUP_DB_PASSWORD = & (Join-Path $ScriptFolder 'scripts\local\Get-SdvLocalDbPassword.ps1') -Root $ScriptFolder
try {
    $setupOut = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $ScriptFolder 'setup.ps1') apply `
        -Licence (Join-Path $ScriptFolder 'dev\setup\licence-vero-demo.json') -Signin (Join-Path $ScriptFolder 'dev\setup\signin-vero-demo.json') `
        --no-customer-keys --run-log (Join-Path $SetupRuntime 'platform-setup-runs.log') `
        --config-out (Join-Path $SetupRuntime 'platform-tenant.properties') 2>&1
    $setupExit = $LASTEXITCODE
} finally {
    Remove-Item Env:PLATFORM_SETUP_DB_URL, Env:PLATFORM_SETUP_DB_USER, Env:PLATFORM_SETUP_DB_PASSWORD -ErrorAction SilentlyContinue
}
if ($setupExit -ne 0 -and $setupExit -ne 3) { throw "Setup failed (exit $setupExit):`n$($setupOut -join "`n")" }
Ok "Setup applied: demo licence, customer, entity slots, login connection, grants, client bindings$(if ($setupExit -eq 3) { ' (some items need a flag; see the plan)' })"
$webJar = @(Get-ChildItem -Path (Join-Path $ScriptFolder 'backend\sdv-web\target') -Filter 'sdv-web-*.jar' -File | Where-Object { $_.Name -notlike '*.original' })[0].FullName
$env:SDV_DEMO_DB_URL = $localUrl; $env:SDV_DEMO_DB_USERNAME = 'sdv'; $env:SDV_DEMO_DB_PASSWORD = & (Join-Path $ScriptFolder 'scripts\local\Get-SdvLocalDbPassword.ps1') -Root $ScriptFolder
try {
    $demoOut = & java -cp $webJar '-Dloader.main=com.sdv.platform.web.install.DemoStep' org.springframework.boot.loader.launch.PropertiesLauncher `
        apply 2>&1
    $demoExit = $LASTEXITCODE
} finally {
    Remove-Item Env:SDV_DEMO_DB_URL, Env:SDV_DEMO_DB_USERNAME, Env:SDV_DEMO_DB_PASSWORD -ErrorAction SilentlyContinue
}
$ErrorActionPreference = $stopOnStderr
if ($demoExit -ne 0) { throw "The demo step failed (exit $demoExit):`n$($demoOut -join "`n")" }
Ok 'Demo step: entities ENT-VERO-DEMO-0001 (Acme Lending) and ENT-VERO-DEMO-0002 activated'

$tenantRow = Db "select count(*) from tenants where tenant_id='$Tenant';"
if ([int]$tenantRow -ne 1) { throw "The local demo entity is not in database $Database. Is the app at $Base started on it (local profile)?" }

# Task 0041 (owner answer 7): -Reset removes the two demo entities of the old local seed changeset (fixed UUIDs, no
# platform-core entity behind them) and everything recorded for them, in the local database only. Nothing else.
$LegacyDemoEntities = @('11111111-1111-1111-1111-111111111111', '22222222-2222-2222-2222-222222222222')
$legacyRows = [int](Db "select count(*) from tenants where tenant_id in ('$($LegacyDemoEntities -join "','")');")
$switchedOver = $false
if ($Reset -and $legacyRows -gt 0) {
    $legacySql = @"
do `$`$
declare
  ids uuid[] := array['$($LegacyDemoEntities -join "','")']::uuid[];
  t record; blocked int; pass int := 0;
begin
  loop
    pass := pass + 1; blocked := 0;
    for t in select c.table_name from information_schema.columns c
             join information_schema.tables b on b.table_schema = c.table_schema and b.table_name = c.table_name and b.table_type = 'BASE TABLE'
             where c.table_schema = 'public' and c.column_name = 'tenant_id' and c.data_type = 'uuid' and c.table_name <> 'tenants'
               and c.table_name not like 'idn_%' and c.table_name not like 'lic_%' loop
      begin
        execute format('delete from %I where tenant_id = any(`$1)', t.table_name) using ids;
      exception when foreign_key_violation then blocked := blocked + 1;
      end;
    end loop;
    exit when blocked = 0 or pass > 30;
  end loop;
  if blocked > 0 then raise exception 'rows of the old demo entities are still referenced'; end if;
  delete from tenants where tenant_id = any(ids);
end `$`$;
"@
    $null = Db $legacySql
    $left = [int](Db "select count(*) from tenants where tenant_id in ('$($LegacyDemoEntities -join "','")');")
    if ($left -ne 0) { throw 'The old demo entities could not be removed.' }
    Fixed "Old demo entities (local seed changeset) and their records removed: $legacyRows"
    $switchedOver = $true
}

# ---- Task 0035: the reset's safety checks, then the settings it puts back ----
if ($Reset -and $switchedOver -and [int](Db "select count(*) from submissions where tenant_id='$Tenant' and source_submission_id like 'canvas-demo-%';") -eq 0) {
    # Task 0041: the first reset after the switch-over: the new demo entity is empty, so there is nothing to put back.
    Ok 'Reset: the demo entity is new; the seed fills it'
    $Reset = $false
}
if ($Reset) {
    $signIn = Invoke-RestMethod -Uri "$Base/api/v1/session/sign-in" -TimeoutSec 10
    if ($signIn.mode -ne 'local') { throw "Reset refused: Vero at $Base is not in local mode (sign-in mode '$($signIn.mode)')." }
    $demoDeliveries = [int](Db "select count(*) from submissions where tenant_id='$Tenant' and source_submission_id like 'canvas-demo-%';")
    if ($demoDeliveries -eq 0) { throw "Reset refused: database $Database holds no delivery of this seed, so it is not a demo database. On a new demo database run .\Seed-SDV-Canvas-Demo.ps1 without -Reset." }
    # Vero at -Base must be using -Database: the loan system's ID in the database is the one the API lists.
    $losInDb = Db "select source_id from ingestion_sources where tenant_id='$Tenant' and source_key='loan-origination';"
    $losInApi = [string](Sources | Where-Object { $_.sourceKey -eq 'loan-origination' } | Select-Object -First 1).sourceId
    if (-not $losInDb -or $losInDb -ne $losInApi) { throw "Reset refused: Vero at $Base does not use database $Database (its Loan Origination System is not the one in that database)." }
    Ok "Reset allowed: local mode, demo database $Database ($demoDeliveries demo deliveries), used by Vero at $Base"

    # Roles & permissions as out of the box: what the screen tour expects.
    $wantRoles = [ordered]@{
        BUSINESS_USER    = [ordered]@{ DOWNLOAD_CURRENT_PDF = $false; PRINT_DOCUMENT = $false; ADD_DOCUMENT_TAG = $false; ADD_DOCUMENT_COMMENT = $false; PERMANENT_REDACTION = $false }
        DOCUMENT_STEWARD = [ordered]@{ DOWNLOAD_CURRENT_PDF = $true; PRINT_DOCUMENT = $true; ADD_DOCUMENT_TAG = $true; ADD_DOCUMENT_COMMENT = $true; PERMANENT_REDACTION = $true }
    }
    $nowRoles = (Api 'GET' '/api/v1/admin/role-capabilities' $admin).roles
    $differs = @(foreach ($role in $wantRoles.Keys) { foreach ($cap in $wantRoles[$role].Keys) { if ([bool]$nowRoles.$role.$cap -ne $wantRoles[$role][$cap]) { "$role $cap" } } })
    if ($differs.Count) {
        $null = Api 'PUT' '/api/v1/admin/role-capabilities' $admin @{ roles = $wantRoles }
        Fixed "Roles & permissions put back ($($differs -join ', '))"
    } else { Ok 'Roles & permissions as out of the box' }
}

# ---- Organization ----
$null = Db "update tenants set display_name='Acme Lending', updated_at=now() where tenant_id='$Tenant' and display_name<>'Acme Lending';"
Ok 'Organization: Acme Lending'

# ---- Storage: a volume in use before anything is delivered ----
$volumes = @(ApiList '/api/v1/admin/storage-volumes' $it)
$inUse = $volumes | Where-Object { $_.volume.displayName -in @('Local demo volume', 'primary-a') } | Select-Object -First 1
if (-not $inUse) {
    New-Item -ItemType Directory -Force -Path $StorageFolder | Out-Null
    $null = Api 'POST' '/api/v1/admin/storage-volumes' $it @{ displayName = 'primary-a'; rootPath = $StorageFolder; state = 'ACTIVE'; priority = 1; warningPercent = 80; stopWritePercent = 95 }
    $volumes = @(ApiList '/api/v1/admin/storage-volumes' $it)
    $inUse = $volumes | Where-Object { $_.volume.displayName -eq 'primary-a' } | Select-Object -First 1
} elseif ($inUse.volume.displayName -ne 'primary-a') {
    $v = $inUse.volume
    $null = Api 'PUT' "/api/v1/admin/storage-volumes/$($v.volumeId)" $it @{ displayName = 'primary-a'; state = $v.state; priority = $v.priority; warningPercent = $v.warningPercent; stopWritePercent = $v.stopWritePercent }
}
Ok 'Volume in use: primary-a'

# ---- Redaction Service and its records (Seed-SDV-Demo-Records.ps1) ----
$redaction = EnsureSource 'redaction-service' 'Redaction Service' 'OAUTH2_CLIENT_CREDENTIALS' 'local-demo/redaction-service-client'
# Task 0035: receiving, as the loan system below (a paused Redaction Service refuses the record seed's deliveries).
if ($redaction.status -ne 'ACTIVE') { $redaction = SetSourceStatus $redaction 'ACTIVE'; Fixed 'Redaction Service receiving again' }
$RedactionId = [string]$redaction.sourceId
# The fields as the tour expects them, before the record seed (which then leaves them as they are).
EnsureField $RedactionId 'contractNumber' 'Contract Number' 'TEXT' $true $true 'DIGITS_ONLY' 'EXACT'
EnsureField $RedactionId 'memberNumber' 'Member Number' 'TEXT' $true $true 'DIGITS_ONLY' 'EXACT'
EnsureField $RedactionId 'serviceEntity' 'Service Entity' 'TEXT' $true $true 'TRIM' 'EXACT'
EnsureField $RedactionId 'firstName' 'First Name' 'TEXT' $false $false 'TRIM' 'EXACT'
EnsureField $RedactionId 'lastName' 'Last Name' 'TEXT' $false $false 'TRIM' 'PARTIAL'
EnsureField $RedactionId 'loanType' 'Loan Type' 'TEXT' $false $false 'TRIM' 'EXACT'
$recordSeed = Join-Path $ScriptFolder 'Seed-SDV-Demo-Records.ps1'
$seedArgs = @{ Base = $Base; Database = $Database; SourceDisplayName = 'Redaction Service'; PdfPath = $PdfPath }
& $recordSeed @seedArgs
& $recordSeed @seedArgs -FreshTestData -Count 3
& $recordSeed @seedArgs -ProblemDemo

# Task 0023: the two records that existed only in one machine's database.
$null = Deliver $RedactionId 'demo-incomplete-300000000090' @{ contractNumber = '300000000090'; memberNumber = '20000000090'; serviceEntity = '001'; firstName = 'Omar'; lastName = 'Patelli'; loanType = 'Mortgage' } `
    @('Appraisal report', 'Insurance certificate') @(@{ id = 'Appraisal report'; file = 'Appraisal Report.pdf'; path = $PdfPath }) '300000000090 Omar Patelli (1 of 2 documents)'
$null = Deliver $RedactionId 'demo-14-pages-300000000092' @{ contractNumber = '300000000092'; memberNumber = '20000000092'; serviceEntity = '001'; firstName = 'Lena'; lastName = 'Patel'; loanType = 'Mortgage' } `
    @('loan-file') @(@{ id = 'loan-file'; file = 'loan-file.pdf'; path = $FourteenPagePdfPath }) '300000000092 Lena Patel (14-page document)'
Ok 'Redaction Service: 300000000090 and 300000000092'

# The stored field order must name every field (the screen tour puts it back as it found it).
$null = EnsureKeys $RedactionId 'display-order' @('contractNumber', 'firstName', 'lastName', 'memberNumber', 'serviceEntity', 'loanType')
$null = EnsureKeys $RedactionId 'record-title' @('firstName', 'lastName')
Ok 'Redaction Service field order (Loan Type last) and Record title: First Name + Last Name'

# Names for the deliveries that were sent without them, and a loan type on every Redaction Service delivery. Written as a
# delivery would have stored them (trimmed text: stored and normalized values are the same).
$names = @{ '100000000001' = @('Jordan', 'Ellis'); '100000000002' = @('Priya', 'Shah'); '100000000003' = @('Marcus', 'Lee') }
function AddValue([string]$SubmissionId, [string]$Key, [string]$Value, [string]$SourceId = $RedactionId) {
    $null = Db @"
insert into business_metadata_values (metadata_value_id, tenant_id, submission_id, definition_id, field_key, definition_version, stored_value, normalized_value, source_id)
select gen_random_uuid(), d.tenant_id, '$SubmissionId', d.definition_id, d.field_key, d.version, $(Lit $Value), $(Lit $Value), d.source_id
from metadata_field_definitions d
where d.tenant_id='$Tenant' and d.source_id='$SourceId' and d.field_key='$Key' and d.active
  and not exists (select 1 from business_metadata_values v where v.tenant_id='$Tenant' and v.submission_id='$SubmissionId' and v.field_key='$Key')
order by d.version desc limit 1;
"@
}
$rows = Db "select s.submission_id || '|' || coalesce((select v.normalized_value from business_metadata_values v where v.submission_id=s.submission_id and v.field_key='contractNumber'),'') from submissions s where s.tenant_id='$Tenant' and s.source_id='$RedactionId' order by s.created_at, s.submission_id;"
$index = 0
foreach ($line in ($rows -split "`n" | Where-Object { $_.Trim() })) {
    $sid, $contract = $line.Trim().Split('|')
    if ($names.ContainsKey($contract)) { AddValue $sid 'firstName' $names[$contract][0]; AddValue $sid 'lastName' $names[$contract][1] }
    AddValue $sid 'loanType' $(if ($index % 3 -eq 2) { 'Auto' } else { 'Mortgage' })
    $index++
}
Ok "Redaction Service: names on 100000000001-100000000003, a loan type on $index deliveries"

# Task 0023: what the tour reads as a business user on 100000000001's Terms and Conditions.pdf: a tag and a comment.
$termsId = Db "select d.document_id from documents d join submissions s on s.submission_id=d.submission_id and s.tenant_id=d.tenant_id where d.tenant_id='$Tenant' and s.source_id='$RedactionId' and d.source_document_id='r3-demo-doc-100000000001-3' limit 1;"
if (-not $termsId) { throw 'Demo document r3-demo-doc-100000000001-3 (Terms and Conditions.pdf) was not found.' }
$tags = @(ApiList "/api/v1/business/documents/$termsId/tags" $steward)
if (-not ($tags | Where-Object { $_.tag -eq 'e2e-demo' })) {
    $null = Api 'POST' "/api/v1/business/documents/$termsId/tags" $steward @{ tag = 'e2e-demo' }
}
Ok 'Tag e2e-demo on 100000000001 Terms and Conditions.pdf'
$comments = @(ApiList "/api/v1/business/documents/$termsId/comments" $steward)
if ($comments.Count -eq 0) {
    $null = Api 'POST' "/api/v1/business/documents/$termsId/comments" $steward @{ comment = 'Demo comment: checked against the signed copy.' }
}
Ok 'A comment on 100000000001 Terms and Conditions.pdf'

# ---- Loan Origination System, delivered last ----
$los = EnsureSource 'loan-origination' 'Loan Origination System' 'OAUTH2_CLIENT_CREDENTIALS' 'vault/loan-origination-client'
$los = SetSourceStatus $los 'ACTIVE'
$LosId = [string]$los.sourceId
EnsureField $LosId 'contractNumber' 'Contract number' 'TEXT' $true $true 'DIGITS_ONLY' 'EXACT'
EnsureField $LosId 'borrowerName' 'Borrower' 'TEXT' $false $true 'TRIM' 'PARTIAL'
EnsureField $LosId 'loanType' 'Loan type' 'TEXT' $false $true 'TRIM' 'EXACT'
EnsureField $LosId 'closingDate' 'Closing date' 'DATE' $false $false 'NONE' 'EXACT'
# Task 0022: two more searchable fields, last in the field order (so the Records columns stay Contract number,
# Borrower, Loan type).
EnsureField $LosId 'coBorrower' 'Co-borrower' 'TEXT' $false $false 'TRIM' 'PARTIAL'
EnsureField $LosId 'closingAgent' 'Closing agent' 'TEXT' $false $false 'TRIM' 'PREFIX'
$null = EnsureKeys $LosId 'display-order' @('contractNumber', 'borrowerName', 'loanType', 'closingDate', 'coBorrower', 'closingAgent')
$null = EnsureKeys $LosId 'record-title' @('borrowerName')
Ok 'Field order and Record title (Borrower) set'

$records = @(
    @{ n = '300000000001'; who = 'Jordan Ellis'; type = 'Mortgage'; closing = '2026-09-15'; files = @('loan-agreement.pdf', 'title-deed.pdf', 'borrower-id.pdf') },
    @{ n = '300000000002'; who = 'Priya Shah'; type = 'Auto'; closing = '2026-10-02'; files = @('loan-agreement.pdf', 'vehicle-title.pdf') },
    @{ n = '300000000003'; who = 'Marcus Lee'; type = 'Mortgage'; closing = '2026-09-30'; files = @('loan-agreement.pdf', 'title-deed.pdf'); missing = @('Appraisal', 'Insurance') },
    @{ n = '300000000004'; who = 'Ana Gomez'; type = 'Auto'; closing = '2026-10-20'; files = @('loan-agreement.pdf', 'vehicle-title.pdf') },
    @{ n = '300000000005'; who = 'Wei Chen'; type = 'Mortgage'; closing = '2026-08-28'; files = @('loan-agreement.pdf', 'title-deed.pdf', 'appraisal.pdf', 'insurance.pdf') },
    @{ n = '300000000006'; who = 'Sam Turner'; type = 'Auto'; closing = '2026-11-06'; files = @('loan-agreement.pdf', 'vehicle-title.pdf') },
    @{ n = '300000000007'; who = 'Fatima Noor'; type = 'Mortgage'; closing = '2026-07-31'; files = @('loan-agreement.pdf', 'title-deed.pdf', 'appraisal.pdf', 'insurance.pdf', 'borrower-id.pdf') },
    @{ n = '300000000008'; who = 'Liam Brooks'; type = 'Auto'; closing = '2026-10-09'; files = @('loan-agreement.pdf', 'vehicle-title.pdf', 'borrower-id.pdf') },
    # Delivered after page counts were kept (task 0018 follow-up): its documents show "2 pages" in the lists.
    @{ n = '300000000009'; who = 'Noah Kim'; type = 'Auto'; closing = '2026-12-04'; files = @('loan-agreement.pdf', 'borrower-id.pdf') }
)
foreach ($r in $records) {
    $files = @($r.files)
    $missing = @(); if ($r.ContainsKey('missing')) { $missing = @($r.missing) }
    $subKey = "canvas-demo-$($r.n)"
    $expected = @(); for ($i = 0; $i -lt $files.Count; $i++) { $expected += "$subKey-$($i + 1)" }
    $expected += $missing
    $docs = @(); for ($i = 0; $i -lt $files.Count; $i++) { $docs += @{ id = "$subKey-$($i + 1)"; file = [string]$files[$i]; path = $PdfPath } }
    $null = Deliver $LosId $subKey @{ contractNumber = $r.n; borrowerName = $r.who; loanType = $r.type; closingDate = $r.closing } $expected $docs "$($r.n) $($r.who)"
}
Ok 'Loan Origination System records delivered'

# Task 0022: a value on two existing records for the fields that aren't columns. No new record or document.
$extra = @{ '300000000004' = @('coBorrower', 'Priya Patel'); '300000000006' = @('closingAgent', 'Patel & Sons Title') }
foreach ($n in $extra.Keys) {
    $sid = Db "select submission_id from submissions where tenant_id='$Tenant' and source_id='$LosId' and source_submission_id='canvas-demo-$n' limit 1;"
    if ($sid) { AddValue $sid $extra[$n][0] $extra[$n][1] $LosId }
}
Ok 'Loan Origination System: Co-borrower on 300000000004, Closing agent on 300000000006'

# ---- Admin canvas (task 0019): a standby volume and a paused sending system ----
if (-not ($volumes | Where-Object { $_.volume.displayName -eq 'primary-b' })) {
    New-Item -ItemType Directory -Force -Path $StandbyStorageFolder | Out-Null
    $priority = if ($inUse) { [int]$inUse.volume.priority + 1 } else { 2 }
    $null = Api 'POST' '/api/v1/admin/storage-volumes' $it @{ displayName = 'primary-b'; rootPath = $StandbyStorageFolder; state = 'ACTIVE'; priority = $priority; warningPercent = 80; stopWritePercent = 95 }
}
Ok 'Standby volume: primary-b'
$tr = EnsureSource 'title-records' 'Title Records' 'OAUTH2_CLIENT_CREDENTIALS' 'vault/title-records-client'
$null = SetSourceStatus $tr 'DISABLED' # the canvas shows Title Records paused
Ok 'Sending system: Title Records (paused)'

# ---- Task 0023: the half-set-up system the Records default rule is tested with (task 0017) ----
# Paused, and its two fields can't be searched, so it has no field in the search schema and no record.
$tour = EnsureSource 'tour-20261118181639' 'Tour test system 20261118181639' 'JWT_BEARER' 'vault/tour-test-client'
EnsureField ([string]$tour.sourceId) 'policyNumber' 'Policy number' 'TEXT' $false $true 'DIGITS_ONLY' 'EXACT' $false
EnsureField ([string]$tour.sourceId) 'holderName' 'Holder name' 'TEXT' $false $false 'TRIM' 'EXACT' $false
$null = SetSourceStatus $tour 'DISABLED'
Ok 'Sending system: Tour test system 20261118181639 (paused, nothing to search)'

# ---- Task 0029: sensitivity demo (boards Sens-Results, Sens-Viewer, Sens-Level) ----
# New deliveries on four Loan Origination System records, each a second submission of the same contract, so the
# existing documents (and what the tour and the other boards use) stay as they are:
#   300000000003 Marcus Lee: settlement-agreement.pdf, Highly Confidential;
#   300000000005 Wei Chen and 300000000009 Noah Kim: income-statement.pdf and credit-report.pdf, Confidential;
#   300000000002 Priya Shah: the whole contract Highly Confidential (court-order.pdf, its own level Standard).
# Then requests in each state, by Dana Ruiz (Restricted steward, local-restricted-steward) and Daniel Reyes (Document
# steward), decided by Priya Natarajan (Business admin). Each state is made only when it is missing, so a second run
# changes nothing. An approval lasts 24 hours and a waiting request 7 days: after that a run makes a new one.
$restricted = Headers 'local-restricted-steward' 'RESTRICTED_STEWARD'
$approver = Headers 'local-business-admin' 'BUSINESS_ADMIN'
$sensitive = @(
    @{ n = '300000000003'; who = 'Marcus Lee'; type = 'Mortgage'; closing = '2026-09-30'; file = 'settlement-agreement.pdf'; level = 'HIGHLY_CONFIDENTIAL' },
    @{ n = '300000000005'; who = 'Wei Chen'; type = 'Mortgage'; closing = '2026-08-28'; file = 'income-statement.pdf'; level = 'CONFIDENTIAL' },
    @{ n = '300000000009'; who = 'Noah Kim'; type = 'Auto'; closing = '2026-12-04'; file = 'credit-report.pdf'; level = 'CONFIDENTIAL' },
    @{ n = '300000000002'; who = 'Priya Shah'; type = 'Auto'; closing = '2026-10-02'; file = 'court-order.pdf'; level = ''; record = 'HIGHLY_CONFIDENTIAL' }
)
$sensitiveDocs = @{}
foreach ($r in $sensitive) {
    $subKey = "canvas-demo-$($r.n)-sensitivity"
    $recordLevel = ''; if ($r.ContainsKey('record')) { $recordLevel = $r.record }
    $sid = Deliver $LosId $subKey @{ contractNumber = $r.n; borrowerName = $r.who; loanType = $r.type; closingDate = $r.closing } @("$subKey-1") @(@{ id = "$subKey-1"; file = $r.file; path = $PdfPath; level = $r.level }) "$($r.n) $($r.who) ($($r.file))" $recordLevel
    $sensitiveDocs[$r.n] = Db "select document_id from documents where tenant_id='$Tenant' and submission_id='$sid' and source_document_id='$subKey-1' limit 1;"
    if (-not $sensitiveDocs[$r.n]) { throw "Demo document $subKey-1 was not stored." }
}
$levels = Db "select string_agg(d.sensitivity_level, ',' order by d.source_document_id) from documents d where d.tenant_id='$Tenant' and d.source_document_id like 'canvas-demo-3000000000%-sensitivity-1';"
Ok "Sensitivity demo documents: $levels; 300000000002 Highly Confidential as a whole contract"

$hc = $sensitiveDocs['300000000003']; $income = $sensitiveDocs['300000000005']; $court = $sensitiveDocs['300000000002']

# ---- Task 0030 documents (boards Sens-Approvals, Sens-LegalHold) ----
# Two Highly Confidential documents more on each of 300000000007 Fatima Noor and 300000000008 Liam Brooks (second
# deliveries, so the existing documents stay as they are). With the settlement agreement that makes five. Delivered
# here, before any request (task 0035), so the reset below sees every demo document.
$approvalDocs = @($hc)
foreach ($r in @(
    @{ n = '300000000007'; who = 'Fatima Noor'; type = 'Mortgage'; closing = '2026-07-31'; files = @('hardship-letter.pdf', 'fraud-report.pdf') },
    @{ n = '300000000008'; who = 'Liam Brooks'; type = 'Auto'; closing = '2026-10-09'; files = @('police-report.pdf', 'medical-letter.pdf') })) {
    $subKey = "canvas-demo-$($r.n)-approvals"
    $expected = @(); $docs = @()
    for ($i = 0; $i -lt $r.files.Count; $i++) { $expected += "$subKey-$($i + 1)"; $docs += @{ id = "$subKey-$($i + 1)"; file = [string]$r.files[$i]; path = $PdfPath; level = 'HIGHLY_CONFIDENTIAL' } }
    $sid = Deliver $LosId $subKey @{ contractNumber = $r.n; borrowerName = $r.who; loanType = $r.type; closingDate = $r.closing } $expected $docs "$($r.n) $($r.who) (approvals demo)"
    foreach ($e in $expected) {
        $d = Db "select document_id from documents where tenant_id='$Tenant' and submission_id='$sid' and source_document_id='$e' limit 1;"
        if (-not $d) { throw "Demo document $e was not stored." }
        $approvalDocs += $d
    }
}
# A request needs an available document: wait for the new ones (they are usually available at once).
for ($try = 0; $try -lt 30; $try++) {
    $notYet = [int](Db "select count(*) from documents where tenant_id='$Tenant' and document_id in ($(($approvalDocs | ForEach-Object { "'$_'" }) -join ',')) and status <> 'AVAILABLE';")
    if ($notYet -eq 0) { break }
    Start-Sleep -Seconds 1
}
if ($notYet -ne 0) { throw "$notYet approvals demo document(s) are not available yet; run the seed again in a minute." }

# Reading the lists first lets Vero mark requests whose time is up as expired, so the counts below are current.
$null = ApiList '/api/v1/business/access-requests' $restricted
$null = ApiList '/api/v1/business/access-requests' $steward
function Requests([string]$Who, [string]$Where) { [int](Db "select count(*) from access_requests where tenant_id='$Tenant' and requested_by='$Who' and $Where;") }
function Ask([hashtable]$As, [string]$Doc, [string]$Kind, [string]$Reason) {
    (Api 'POST' "/api/v1/business/documents/$Doc/access-requests" $As @{ kind = $Kind; reason = $Reason }).requestId
}
function AskLower([hashtable]$As, [string]$Doc, [string]$Level, [string]$Reason) {
    (Api 'POST' "/api/v1/business/documents/$Doc/sensitivity/lower-request" $As @{ level = $Level; reason = $Reason }).requestId
}
function Decide([string]$Id, [string]$Decision, [hashtable]$As = $approver) { $null = Api 'POST' "/api/v1/admin/access-requests/$Id/$Decision" $As }
# Task 0035: what the request steps below need, read just before each one.
function OwnLevel([string]$Doc) { Db "select sensitivity_level from documents where tenant_id='$Tenant' and document_id='$Doc';" }
function ContractLevel([string]$SourceId, [string]$Contract) { $l = Db "select sensitivity_level from record_sensitivity_levels where tenant_id='$Tenant' and source_id='$SourceId' and record_key like '%$Contract%' limit 1;"; if ($l) { $l } else { 'STANDARD' } }
function RecordRef([string]$SourceId, [string]$Contract) {
    $found = Api 'POST' '/api/v1/business/search' $restricted @{ sourceId = $SourceId; criteria = @{ contractNumber = $Contract }; page = 1; pageSize = 5 }
    [string](@($found.records)[0].recordRef)
}
$rank = @{ STANDARD = 0; CONFIDENTIAL = 1; HIGHLY_CONFIDENTIAL = 2 }
$rs = 'local-restricted-steward'; $ds = 'local-document-steward'
$askers = @(
    @{ id = 'local-demo-sam-kim'; name = 'Sam Kim' }, @{ id = 'local-demo-ali-haddad'; name = 'Ali Haddad' },
    @{ id = 'local-demo-leo-grant'; name = 'Leo Grant' }, @{ id = 'local-demo-mia-novak'; name = 'Mia Novak' },
    @{ id = 'local-demo-tom-reed'; name = 'Tom Reed' })

# ---- Task 0035: reset of waiting requests, levels and legal holds (only with -Reset) ----
if ($Reset) {
    $resetter = Headers 'local-demo-reset' 'RESTRICTED_STEWARD'
    $resetAdmin = Headers 'local-demo-reset-admin' 'BUSINESS_ADMIN' # for a request Priya made herself (nobody decides their own) and holds she placed
    $resetWhy = 'Demo reset: back to the seed''s level'

    # 1. Waiting requests: the seed's are kept (one of each), every other one is declined.
    $mine = @{}
    $declined = 0
    $pendingRows = Db "select request_id || '|' || requested_by || '|' || kind || '|' || coalesce(document_id::text, '') || '|' || coalesce(requested_level, '') || '|' || coalesce(source_id::text, '') || '|' || coalesce(record_key, '') from access_requests where tenant_id='$Tenant' and status='PENDING' and expires_at > now() order by requested_at, request_id;"
    foreach ($line in ($pendingRows -split "`n" | Where-Object { $_.Trim() })) {
        $id, $who, $kind, $doc, $level, $src, $key = $line.Trim().Split('|', 7)
        $sig = $null
        if ($who -eq $rs -and $kind -eq 'LOWER_LEVEL' -and $doc -eq $hc -and $level -eq 'CONFIDENTIAL') { $sig = 'dana-lower-settlement' }
        elseif ($who -eq $ds -and $kind -eq 'LOWER_LEVEL' -and $doc -eq $income -and $level -eq 'STANDARD') { $sig = 'daniel-lower-income' }
        elseif ($who -eq $rs -and $kind -eq 'LOWER_RECORD_LEVEL' -and $src -eq $LosId -and $key -like '*300000000002*' -and $level -eq 'CONFIDENTIAL') { $sig = 'dana-lower-contract' }
        elseif (($askers | ForEach-Object { $_.id }) -contains $who -and $kind -in @('PRINT', 'DOWNLOAD') -and $approvalDocs -contains $doc) { $sig = "$who $kind $doc" }
        if ($sig -and -not $mine.ContainsKey($sig)) { $mine[$sig] = $true; continue }
        try {
            Decide $id 'decline' $(if ($who -eq 'local-business-admin') { $resetAdmin } else { $approver })
            $declined++
        } catch { Skip "Could not decline waiting request $id ($kind by $who): $($_.Exception.Message)" }
    }
    if ($declined) { Fixed "Declined $declined waiting request(s) the seed did not make" } else { Ok 'Waiting requests: only the seed''s' }

    # 2. Levels, contracts first (a document's level counts with its contract's): 300000000002 Highly Confidential as a
    # whole contract, every other contract Standard; then each document at the seed's level, every other one Standard.
    $recordRows = Db "select source_id || '|' || sensitivity_level || '|' || record_key from record_sensitivity_levels where tenant_id='$Tenant' order by record_key;"
    foreach ($line in ($recordRows -split "`n" | Where-Object { $_.Trim() })) {
        $src, $now, $key = $line.Trim().Split('|', 3)
        $contract = ([regex]::Match($key, '\d{6,}')).Value
        $target = if ($src -eq $LosId -and $contract -eq '300000000002') { 'HIGHLY_CONFIDENTIAL' } else { 'STANDARD' }
        if ($now -eq $target) { continue }
        try {
            $ref = RecordRef $src $contract
            if (-not $ref) { Skip "Contract $contract was not found to put its level back ($now)"; continue }
            $path = "/api/v1/business/records/$([uri]::EscapeDataString($ref))/sensitivity"
            if ($rank[$target] -gt $rank[$now]) { $null = Api 'POST' "$path/raise" $resetter @{ level = $target; reason = $resetWhy } }
            else { Decide (Api 'POST' "$path/lower-request" $resetter @{ level = $target; reason = $resetWhy }).requestId 'approve' }
            Fixed "Contract $contract level: $now -> $target"
        } catch { Skip "Could not put contract $contract back to $target ($now): $($_.Exception.Message)" }
    }
    $docTarget = @{
        'canvas-demo-300000000003-sensitivity-1' = 'HIGHLY_CONFIDENTIAL'; 'canvas-demo-300000000005-sensitivity-1' = 'CONFIDENTIAL'
        'canvas-demo-300000000009-sensitivity-1' = 'CONFIDENTIAL'
        'canvas-demo-300000000007-approvals-1' = 'HIGHLY_CONFIDENTIAL'; 'canvas-demo-300000000007-approvals-2' = 'HIGHLY_CONFIDENTIAL'
        'canvas-demo-300000000008-approvals-1' = 'HIGHLY_CONFIDENTIAL'; 'canvas-demo-300000000008-approvals-2' = 'HIGHLY_CONFIDENTIAL'
        # The screen tour's own record (frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts, task 0035).
        'tour-check-399000000001-1' = 'HIGHLY_CONFIDENTIAL'
    }
    $docRows = Db "select document_id || '|' || source_document_id || '|' || sensitivity_level from documents where tenant_id='$Tenant' and status='AVAILABLE' order by source_document_id, document_id;"
    $levelsBack = 0
    foreach ($line in ($docRows -split "`n" | Where-Object { $_.Trim() })) {
        $doc, $sourceDoc, $now = $line.Trim().Split('|', 3)
        $target = if ($docTarget.ContainsKey($sourceDoc)) { $docTarget[$sourceDoc] } else { 'STANDARD' }
        if ($now -eq $target) { continue }
        try {
            if ($rank[$target] -gt $rank[$now]) { $null = Api 'POST' "/api/v1/business/documents/$doc/sensitivity/raise" $resetter @{ level = $target; reason = $resetWhy } }
            else { Decide (AskLower $resetter $doc $target $resetWhy) 'approve' }
            Fixed "Document $sourceDoc level: $now -> $target"; $levelsBack++
        } catch { Skip "Could not put document $sourceDoc back to $target ($now): $($_.Exception.Message)" }
    }
    if (-not $levelsBack) { Ok 'Document levels as the seed makes them' }

    # 3. Legal holds: the seed's three (placed again below if missing); any other hold is released.
    $holdDocs = @($approvalDocs[1], $approvalDocs[2], $income)
    $holdRows = Db "select hold_id || '|' || document_id || '|' || placed_by from legal_holds where tenant_id='$Tenant' and released_at is null order by placed_at, hold_id;"
    $released = 0
    foreach ($line in ($holdRows -split "`n" | Where-Object { $_.Trim() })) {
        $holdId, $doc, $placer = $line.Trim().Split('|', 3)
        if ($holdDocs -contains $doc) { continue }
        # Task 0071: one Business Admin asks, another (not the one who placed the hold) approves.
        $decider = if ($placer -eq 'local-business-admin') { $resetAdmin } else { $approver }
        $asker = if ($placer -eq 'local-business-admin') { $approver } else { $resetAdmin }
        try { $asked = Api 'POST' "/api/v1/admin/legal-holds/$holdId/release" $asker @{ reason = 'Demo reset: not a hold of the seed' }; Decide $asked.requestId 'approve' $decider; $released++ } catch { Skip "Could not release legal hold ${holdId}: $($_.Exception.Message)" }
    }
    if ($released) { Fixed "Released $released legal hold(s) the seed did not place" } else { Ok 'Legal holds: only the seed''s' }
}
# Task 0035: each step first checks the level it needs. When the data has moved on (a level changed by hand), the
# step is skipped with a note; .\Seed-SDV-Canvas-Demo.ps1 -Reset puts the levels back first.
$hcLevel = OwnLevel $hc; $incomeLevel = OwnLevel $income; $courtContract = ContractLevel $LosId '300000000002'
$resetHint = 'run .\Seed-SDV-Canvas-Demo.ps1 -Reset to put the demo data back'
# Approved: Dana may print the settlement agreement once, within 24 hours (the viewer board's "Print, approved").
if ((Requests $rs "kind='PRINT' and document_id='$hc' and status='APPROVED' and expires_at > now()") -eq 0) {
    if ($hcLevel -ne 'HIGHLY_CONFIDENTIAL') { Skip "Dana's approved print: the settlement agreement is $hcLevel, not Highly Confidential; $resetHint" }
    else {
        # One left waiting by a run that stopped is approved; otherwise a new one is made.
        $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='PRINT' and document_id='$hc' and status='PENDING' and expires_at > now() limit 1;"
        if (-not $id) { $id = Ask $restricted $hc 'PRINT' 'Board pack for legal review' }
        Decide $id 'approve'
    }
}
# Waiting: Dana asks to lower the settlement agreement to Confidential.
if ((Requests $rs "kind='LOWER_LEVEL' and document_id='$hc' and status='PENDING'") -eq 0) {
    if ($hcLevel -ne 'HIGHLY_CONFIDENTIAL') { Skip "Dana's waiting lowering: the settlement agreement is $hcLevel, so it can't be asked to go to Confidential; $resetHint" }
    else { $null = AskLower $restricted $hc 'CONFIDENTIAL' 'Case closed' }
}
# Declined: Dana's download of the court order.
if ((Requests $rs "status='DECLINED'") -eq 0) {
    if ($courtContract -ne 'HIGHLY_CONFIDENTIAL') { Skip "Dana's declined download: contract 300000000002 is $courtContract, not Highly Confidential; $resetHint" }
    else {
        $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='DOWNLOAD' and document_id='$court' and status='PENDING' limit 1;"
        if (-not $id) { $id = Ask $restricted $court 'DOWNLOAD' 'Copy for the insurer' }
        Decide $id 'decline'
    }
}
# Used: Dana's print of the court order, approved and printed once.
if ((Requests $rs "status='USED'") -eq 0) {
    if ($courtContract -ne 'HIGHLY_CONFIDENTIAL') { Skip "Dana's used print: contract 300000000002 is $courtContract, not Highly Confidential; $resetHint" }
    else {
        $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='PRINT' and document_id='$court' and status in ('PENDING','APPROVED') limit 1;"
        if (-not $id) { $id = Ask $restricted $court 'PRINT' 'Hearing on 2 December' }
        if ((Db "select status from access_requests where request_id='$id';") -eq 'PENDING') { Decide $id 'approve' }
        $null = Api 'POST' "/api/v1/business/documents/$court/print-event" $restricted
    }
}
# Expired: a request nobody decided in 7 days. Made now and dated back eight days (local demo data only), then the
# list read marks it expired, as Vero would.
if ((Requests $rs "status='EXPIRED'") -eq 0) {
    $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='LOWER_LEVEL' and document_id='$income' and status='PENDING' limit 1;"
    if (-not $id -and $rank[$incomeLevel] -eq 0) { Skip "Dana's expired lowering: the income statement is already Standard; $resetHint" }
    else {
        if (-not $id) { $id = AskLower $restricted $income 'STANDARD' 'Not sensitive after all' }
        $null = Db "update access_requests set requested_at = now() - interval '8 days', expires_at = now() - interval '1 day' where request_id='$id' and status='PENDING';"
        $null = ApiList '/api/v1/business/access-requests' $restricted
    }
}
# Waiting: Daniel (Document steward) asks to lower the income statement to Standard.
if ((Requests $ds "kind='LOWER_LEVEL' and document_id='$income' and status='PENDING'") -eq 0) {
    # A Document steward can open Confidential, not Highly Confidential, and may ask to lower only from Confidential.
    if ($incomeLevel -ne 'CONFIDENTIAL') { Skip "Daniel's waiting lowering: the income statement is $incomeLevel, not Confidential; $resetHint" }
    else { $null = AskLower $steward $income 'STANDARD' 'Marked by mistake' }
}
$states = Db "select string_agg(status || ' ' || n, ', ' order by status) from (select status, count(*) n from access_requests where tenant_id='$Tenant' and requested_by in ('$rs','$ds') group by status) x;"
Ok "Demo requests by Dana Ruiz and Daniel Reyes: $states"

# ---- Task 0030: Approvals and Legal holds (boards Sens-Approvals, Sens-LegalHold) ----
# Two Highly Confidential documents more on each of 300000000007 Fatima Noor and 300000000008 Liam Brooks (second
# deliveries, so the existing documents stay as they are). With the settlement agreement that makes five. Five demo
# Restricted stewards who never sign in (Sam Kim, Ali Haddad, Leo Grant, Mia Novak, Tom Reed) each ask to print and to
# download all five: 50 waiting print and download requests, the bulk approval the task tests. Their names are written
# in the local database only, as the other demo names are. Dana Ruiz also asks to lower the whole of 300000000002 to
# Confidential (a whole-contract request). Two legal holds: the two new documents of 300000000007, and Wei Chen's
# income statement. Each is made only when it is missing, so a second run changes nothing; after the bulk approval
# (or 7 days), a run makes the 50 waiting requests again. (The documents are delivered further up, task 0035.)
$why = @{ PRINT = @('Hearing bundle, two copies', 'Board pack for the risk committee', 'Signed copy for the file review', 'Copy for the mediation session', 'Paper copy for the auditor'); DOWNLOAD = @('Outside counsel needs a copy for the hearing on 2 December.', 'Regulator asked for the original by Friday', 'Send to the insurer for the claim', 'Evidence pack for the complaint', 'Copy for the external auditor') }
$made = 0
# Print and download are asked for only on a Highly Confidential document (task 0035: one lowered by hand is skipped).
$askable = @($approvalDocs | Where-Object { (OwnLevel $_) -eq 'HIGHLY_CONFIDENTIAL' })
foreach ($doc in @($approvalDocs | Where-Object { $askable -notcontains $_ })) { Skip "The 10 print and download requests on approvals demo document $doc`: it is $(OwnLevel $doc), not Highly Confidential; $resetHint" }
for ($p = 0; $p -lt $askers.Count; $p++) {
    $as = Headers $askers[$p].id 'RESTRICTED_STEWARD'
    foreach ($doc in $askable) {
        foreach ($kind in @('PRINT', 'DOWNLOAD')) {
            if ((Requests $askers[$p].id "kind='$kind' and document_id='$doc' and status='PENDING'") -eq 0) { $null = Ask $as $doc $kind $why[$kind][$p]; $made++ }
        }
    }
    $null = Db "update access_requests set requested_by_display_name=$(Lit $askers[$p].name) where tenant_id='$Tenant' and requested_by='$($askers[$p].id)' and requested_by_display_name is null;"
}
# Whole contract: Dana asks to lower 300000000002 (Highly Confidential as a whole contract) to Confidential.
if ((Requests $rs "kind='LOWER_RECORD_LEVEL' and status='PENDING'") -eq 0) {
    $courtContract = ContractLevel $LosId '300000000002'
    if ($courtContract -ne 'HIGHLY_CONFIDENTIAL') { Skip "Dana's whole-contract lowering: contract 300000000002 is $courtContract, not Highly Confidential; $resetHint" }
    else {
        $ref = RecordRef $LosId '300000000002'
        if (-not $ref) { throw 'Record 300000000002 was not found for the whole-contract request.' }
        $null = Api 'POST' "/api/v1/business/records/$([uri]::EscapeDataString($ref))/sensitivity/lower-request" $restricted @{ level = 'CONFIDENTIAL'; reason = 'Migration review finished; the old card numbers are now redacted.' }
    }
}
$waiting = Db "select count(*) from access_requests where tenant_id='$Tenant' and status='PENDING' and expires_at > now();"
Ok "Approvals demo: $made new request(s); $waiting waiting in all"

# Legal holds, placed by Priya Natarajan: only on a document that has none.
foreach ($h in @(
    @{ doc = $approvalDocs[1]; reason = 'Regulator inquiry 2026-117' }, @{ doc = $approvalDocs[2]; reason = 'Regulator inquiry 2026-117' },
    @{ doc = $income; reason = 'HR dispute' })) {
    if ([int](Db "select count(*) from legal_holds where tenant_id='$Tenant' and document_id='$($h.doc)' and released_at is null;") -eq 0) {
        $null = Api 'POST' '/api/v1/admin/legal-holds' $approver @{ documentId = $h.doc; reason = $h.reason }
    }
}
Ok "Legal holds in place: $(Db "select count(*) from legal_holds where tenant_id='$Tenant' and released_at is null;")"
if ($script:skipped.Count) {
    Write-Host "Demo data ready, except $($script:skipped.Count) step(s) skipped because the data has moved on (see [SKIP] above). Run .\Seed-SDV-Canvas-Demo.ps1 -Reset to put it back." -ForegroundColor Yellow
} else {
    Write-Host 'Demo data ready.' -ForegroundColor Green
}
