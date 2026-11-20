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
# Documents: @{ id; file; path }. Expected: the source document IDs the delivery announces.
function Deliver([string]$SourceId, [string]$SubKey, [hashtable]$Metadata, [string[]]$Expected, [object[]]$Documents, [string]$Label) {
    $submissionId = Db "select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id=$(Lit $SubKey) limit 1;"
    if ([string]::IsNullOrWhiteSpace($submissionId)) {
        $s = Api 'POST' '/api/v1/ingestion/submissions' (SourceHeaders $SourceId) @{
            sourceSubmissionId = $SubKey; expectedDocumentCount = $Expected.Count; expectedDocuments = @($Expected | ForEach-Object { @{ sourceDocumentId = $_ } }); metadata = $Metadata
        }
        $submissionId = [string]$s.submissionId
        Ok "Record $Label"
    }
    foreach ($d in $Documents) {
        $have = Db "select count(*) from documents where tenant_id='$Tenant' and submission_id='$submissionId' and source_document_id=$(Lit $d.id);"
        if ([int]$have -eq 0) {
            $h = SourceHeaders $SourceId; $h['X-SDV-Source-Document-Id'] = $d.id; $h['X-SDV-Filename'] = $d.file
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
Write-Host 'Demo data ready.' -ForegroundColor Green
