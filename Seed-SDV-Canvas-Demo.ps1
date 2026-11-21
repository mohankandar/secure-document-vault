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
  - LexaVault (the source the screen tour uses), with Contract Number, Member Number, Service Entity, First Name,
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

  The demo PDFs (test-tools\fixtures, scripts\demo-files) are for this local seed only. They are not part of the
  backend jar or the frontend build, and the source-package scripts leave scripts\demo-files out.

.PARAMETER Database
  The local database the app uses, for the few values written directly. A scratch database such as sdv_seed_test
  works too (used by the task 0023 seed check), together with -Base pointing at an app started on it.
#>
[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8280',
    [ValidatePattern('^sdv(_[a-z0-9_]+)?$')][string]$Database = 'sdv',
    [string]$PdfPath = (Join-Path $PSScriptRoot 'test-tools\fixtures\business-viewer-two-page.pdf'),
    [string]$FourteenPagePdfPath = (Join-Path $PSScriptRoot 'scripts\demo-files\loan-file-14-pages.pdf'),
    [string]$StorageFolder = (Join-Path $PSScriptRoot '.runtime\demo-storage'),
    [string]$StandbyStorageFolder = (Join-Path $PSScriptRoot '.runtime\demo-storage-b')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Base -notmatch '^http://(localhost|127\.0\.0\.1)[:/]') { throw 'The canvas demo data is for the local development stack only.' }
foreach ($f in @($PdfPath, $FourteenPagePdfPath)) { if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { throw "Demo PDF not found: $f" } }

$Tenant = '11111111-1111-1111-1111-111111111111'
$Correlation = 'canvas-demo-seed-' + ([guid]::NewGuid().ToString('N')).Substring(0, 10)
function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
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
$it = Headers 'local-demo-tenant-it' 'TENANT_IT_ADMIN'
$steward = Headers 'local-document-steward' 'DOCUMENT_STEWARD'

# A field that is already there is left as it is (each save adds a field version and an audit event).
function EnsureField([string]$SourceId, [string]$Key, [string]$Label, [string]$Type, [bool]$Identifier, [bool]$Required, [string]$Normalization, [string]$Mode, [bool]$Searchable = $true) {
    $have = Db "select count(*) from metadata_field_definitions where tenant_id='$Tenant' and source_id='$SourceId' and field_key=$(Lit $Key) and active;"
    if ([int]$have -gt 0) { Ok "Field already there: $Label"; return }
    $null = Api 'PUT' "/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$Key" $admin @{
        displayLabel = $Label; fieldType = $Type; required = $Required; businessIdentifier = $Identifier; searchable = $Searchable
        normalization = $Normalization; searchMode = $Mode; validationRules = @{}
    }
    Ok "Field added: $Label"
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
            $null = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $h -ContentType 'application/pdf' -InFile $d.path -UseBasicParsing -TimeoutSec 60
        }
    }
    $submissionId
}

Write-Host "=== SDV DEMO DATA (local only, database $Database) ==="
try { $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5 } catch { throw "SDV is not reachable at $Base. Start it with .\start-local.cmd" }
if ($health.status -ne 'UP') { throw "SDV is not UP at $Base" }
$tenantRow = Db "select count(*) from tenants where tenant_id='$Tenant';"
if ([int]$tenantRow -ne 1) { throw "The local demo tenant is not in database $Database. Is the app at $Base started on it (local profile)?" }

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

# ---- LexaVault and its records (Seed-SDV-Demo-Records.ps1) ----
$lexa = EnsureSource 'lexaVault' 'LexaVault' 'OAUTH2_CLIENT_CREDENTIALS' 'local-demo/lexavault-client'
$LexaId = [string]$lexa.sourceId
# The fields as the tour expects them, before the record seed (which then leaves them as they are).
EnsureField $LexaId 'contractNumber' 'Contract Number' 'TEXT' $true $true 'DIGITS_ONLY' 'EXACT'
EnsureField $LexaId 'memberNumber' 'Member Number' 'TEXT' $true $true 'DIGITS_ONLY' 'EXACT'
EnsureField $LexaId 'serviceEntity' 'Service Entity' 'TEXT' $true $true 'TRIM' 'EXACT'
EnsureField $LexaId 'firstName' 'First Name' 'TEXT' $false $false 'TRIM' 'EXACT'
EnsureField $LexaId 'lastName' 'Last Name' 'TEXT' $false $false 'TRIM' 'PARTIAL'
EnsureField $LexaId 'loanType' 'Loan Type' 'TEXT' $false $false 'TRIM' 'EXACT'
$recordSeed = Join-Path $PSScriptRoot 'Seed-SDV-Demo-Records.ps1'
$seedArgs = @{ Base = $Base; Database = $Database; SourceDisplayName = 'LexaVault'; PdfPath = $PdfPath }
& $recordSeed @seedArgs
& $recordSeed @seedArgs -FreshTestData -Count 3
& $recordSeed @seedArgs -ProblemDemo

# Task 0023: the two records that existed only in one machine's database.
$null = Deliver $LexaId 'demo-incomplete-300000000090' @{ contractNumber = '300000000090'; memberNumber = '20000000090'; serviceEntity = '001'; firstName = 'Omar'; lastName = 'Patelli'; loanType = 'Mortgage' } `
    @('Appraisal report', 'Insurance certificate') @(@{ id = 'Appraisal report'; file = 'Appraisal Report.pdf'; path = $PdfPath }) '300000000090 Omar Patelli (1 of 2 documents)'
$null = Deliver $LexaId 'demo-14-pages-300000000092' @{ contractNumber = '300000000092'; memberNumber = '20000000092'; serviceEntity = '001'; firstName = 'Lena'; lastName = 'Patel'; loanType = 'Mortgage' } `
    @('loan-file') @(@{ id = 'loan-file'; file = 'loan-file.pdf'; path = $FourteenPagePdfPath }) '300000000092 Lena Patel (14-page document)'
Ok 'LexaVault: 300000000090 and 300000000092'

# The stored field order must name every field (the screen tour puts it back as it found it).
$null = EnsureKeys $LexaId 'display-order' @('contractNumber', 'firstName', 'lastName', 'memberNumber', 'serviceEntity', 'loanType')
$null = EnsureKeys $LexaId 'record-title' @('firstName', 'lastName')
Ok 'LexaVault field order (Loan Type last) and Record title: First Name + Last Name'

# Names for the deliveries that were sent without them, and a loan type on every LexaVault delivery. Written as a
# delivery would have stored them (trimmed text: stored and normalized values are the same).
$names = @{ '100000000001' = @('Jordan', 'Ellis'); '100000000002' = @('Priya', 'Shah'); '100000000003' = @('Marcus', 'Lee') }
function AddValue([string]$SubmissionId, [string]$Key, [string]$Value, [string]$SourceId = $LexaId) {
    $null = Db @"
insert into business_metadata_values (metadata_value_id, tenant_id, submission_id, definition_id, field_key, definition_version, stored_value, normalized_value, source_id)
select gen_random_uuid(), d.tenant_id, '$SubmissionId', d.definition_id, d.field_key, d.version, $(Lit $Value), $(Lit $Value), d.source_id
from metadata_field_definitions d
where d.tenant_id='$Tenant' and d.source_id='$SourceId' and d.field_key='$Key' and d.active
  and not exists (select 1 from business_metadata_values v where v.tenant_id='$Tenant' and v.submission_id='$SubmissionId' and v.field_key='$Key')
order by d.version desc limit 1;
"@
}
$rows = Db "select s.submission_id || '|' || coalesce((select v.normalized_value from business_metadata_values v where v.submission_id=s.submission_id and v.field_key='contractNumber'),'') from submissions s where s.tenant_id='$Tenant' and s.source_id='$LexaId' order by s.created_at, s.submission_id;"
$index = 0
foreach ($line in ($rows -split "`n" | Where-Object { $_.Trim() })) {
    $sid, $contract = $line.Trim().Split('|')
    if ($names.ContainsKey($contract)) { AddValue $sid 'firstName' $names[$contract][0]; AddValue $sid 'lastName' $names[$contract][1] }
    AddValue $sid 'loanType' $(if ($index % 3 -eq 2) { 'Auto' } else { 'Mortgage' })
    $index++
}
Ok "LexaVault: names on 100000000001-100000000003, a loan type on $index deliveries"

# Task 0023: what the tour reads as a business user on 100000000001's Terms and Conditions.pdf: a tag and a comment.
$termsId = Db "select d.document_id from documents d join submissions s on s.submission_id=d.submission_id and s.tenant_id=d.tenant_id where d.tenant_id='$Tenant' and s.source_id='$LexaId' and d.source_document_id='r3-demo-doc-100000000001-3' limit 1;"
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
$los = EnsureSource 'loanOrigination' 'Loan Origination System' 'OAUTH2_CLIENT_CREDENTIALS' 'vault/loan-origination-client'
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
$tr = EnsureSource 'titleRecords' 'Title Records' 'OAUTH2_CLIENT_CREDENTIALS' 'vault/title-records-client'
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
function Decide([string]$Id, [string]$Decision) { $null = Api 'POST' "/api/v1/admin/access-requests/$Id/$Decision" $approver }
$rs = 'local-restricted-steward'; $ds = 'local-document-steward'
# Approved: Dana may print the settlement agreement once, within 24 hours (the viewer board's "Print, approved").
if ((Requests $rs "kind='PRINT' and document_id='$hc' and status in ('APPROVED','PENDING') and expires_at > now()") -eq 0) {
    Decide (Ask $restricted $hc 'PRINT' 'Board pack for legal review') 'approve'
}
# Waiting: Dana asks to lower the settlement agreement to Confidential.
if ((Requests $rs "kind='LOWER_LEVEL' and document_id='$hc' and status='PENDING'") -eq 0) {
    $null = AskLower $restricted $hc 'CONFIDENTIAL' 'Case closed'
}
# Declined: Dana's download of the court order.
if ((Requests $rs "status='DECLINED'") -eq 0) {
    $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='DOWNLOAD' and document_id='$court' and status='PENDING' limit 1;"
    if (-not $id) { $id = Ask $restricted $court 'DOWNLOAD' 'Copy for the insurer' }
    Decide $id 'decline'
}
# Used: Dana's print of the court order, approved and printed once.
if ((Requests $rs "status='USED'") -eq 0) {
    $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='PRINT' and document_id='$court' and status in ('PENDING','APPROVED') limit 1;"
    if (-not $id) { $id = Ask $restricted $court 'PRINT' 'Hearing on 2 December' }
    if ((Db "select status from access_requests where request_id='$id';") -eq 'PENDING') { Decide $id 'approve' }
    $null = Api 'POST' "/api/v1/business/documents/$court/print-event" $restricted
}
# Expired: a request nobody decided in 7 days. Made now and dated back eight days (local demo data only), then the
# list read marks it expired, as Vero would.
if ((Requests $rs "status='EXPIRED'") -eq 0) {
    $id = Db "select request_id from access_requests where tenant_id='$Tenant' and requested_by='$rs' and kind='LOWER_LEVEL' and document_id='$income' and status='PENDING' limit 1;"
    if (-not $id) { $id = AskLower $restricted $income 'STANDARD' 'Not sensitive after all' }
    $null = Db "update access_requests set requested_at = now() - interval '8 days', expires_at = now() - interval '1 day' where request_id='$id' and status='PENDING';"
    $null = ApiList '/api/v1/business/access-requests' $restricted
}
# Waiting: Daniel (Document steward) asks to lower the income statement to Standard.
if ((Requests $ds "kind='LOWER_LEVEL' and document_id='$income' and status='PENDING'") -eq 0) {
    $null = AskLower $steward $income 'STANDARD' 'Marked by mistake'
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
# (or 7 days), a run makes the 50 waiting requests again.
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

$askers = @(
    @{ id = 'local-demo-sam-kim'; name = 'Sam Kim' }, @{ id = 'local-demo-ali-haddad'; name = 'Ali Haddad' },
    @{ id = 'local-demo-leo-grant'; name = 'Leo Grant' }, @{ id = 'local-demo-mia-novak'; name = 'Mia Novak' },
    @{ id = 'local-demo-tom-reed'; name = 'Tom Reed' })
$why = @{ PRINT = @('Hearing bundle, two copies', 'Board pack for the risk committee', 'Signed copy for the file review', 'Copy for the mediation session', 'Paper copy for the auditor'); DOWNLOAD = @('Outside counsel needs a copy for the hearing on 2 December.', 'Regulator asked for the original by Friday', 'Send to the insurer for the claim', 'Evidence pack for the complaint', 'Copy for the external auditor') }
$made = 0
for ($p = 0; $p -lt $askers.Count; $p++) {
    $as = Headers $askers[$p].id 'RESTRICTED_STEWARD'
    foreach ($doc in $approvalDocs) {
        foreach ($kind in @('PRINT', 'DOWNLOAD')) {
            if ((Requests $askers[$p].id "kind='$kind' and document_id='$doc' and status='PENDING'") -eq 0) { $null = Ask $as $doc $kind $why[$kind][$p]; $made++ }
        }
    }
    $null = Db "update access_requests set requested_by_display_name=$(Lit $askers[$p].name) where tenant_id='$Tenant' and requested_by='$($askers[$p].id)' and requested_by_display_name is null;"
}
# Whole contract: Dana asks to lower 300000000002 (Highly Confidential as a whole contract) to Confidential.
if ((Requests $rs "kind='LOWER_RECORD_LEVEL' and status='PENDING'") -eq 0) {
    $found = Api 'POST' '/api/v1/business/search' $restricted @{ sourceId = $LosId; criteria = @{ contractNumber = '300000000002' }; page = 1; pageSize = 5 }
    $ref = @($found.records)[0].recordRef
    if (-not $ref) { throw 'Record 300000000002 was not found for the whole-contract request.' }
    $null = Api 'POST' "/api/v1/business/records/$([uri]::EscapeDataString($ref))/sensitivity/lower-request" $restricted @{ level = 'CONFIDENTIAL'; reason = 'Migration review finished; the old card numbers are now redacted.' }
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
Write-Host 'Demo data ready.' -ForegroundColor Green
