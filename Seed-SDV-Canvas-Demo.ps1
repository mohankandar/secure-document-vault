<#
.SYNOPSIS
  Local demo data that looks like the approved canvas (task 0018). Local development stack only.

.DESCRIPTION
  - Organization: the local demo tenant is named "Acme Lending".
  - A "Loan Origination System" sending system with the canvas's fields (Contract number, Borrower, Loan type,
    Closing date), its Record title set to Borrower, and the canvas's records 300000000001-300000000008,
    delivered through the producer API like any other delivery. 300000000003 (Marcus Lee) has 2 of 4 documents;
    Appraisal and Insurance are still expected.
  - LexaVault (the source the screen tour uses): a "Loan Type" field, its Record title set to First Name + Last
    Name, and full names and a loan type on every demo record. Records that were delivered without names
    (100000000001-100000000003) get them added to their delivery in the local database, because a new delivery
    would add a document to each and change what the tour expects.

  It never runs against anything but http://localhost / 127.0.0.1, and it is safe to run again: existing data is
  left as it is. Run it after Seed-SDV-Demo-Records.ps1 (and its -FreshTestData and -ProblemDemo runs).
#>
[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8280',
    [string]$PdfPath = (Join-Path $PSScriptRoot 'test-tools\fixtures\business-viewer-two-page.pdf')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

if ($Base -notmatch '^http://(localhost|127\.0\.0\.1)[:/]') { throw 'The canvas demo data is for the local development stack only.' }
if (-not (Test-Path -LiteralPath $PdfPath -PathType Leaf)) { throw "Demo PDF fixture not found: $PdfPath" }

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
function Db([string]$Sql) { (& docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -tAc $Sql | Out-String).Trim() }
function Lit([string]$s) { "'" + $s.Replace("'", "''") + "'" }

$admin = Headers 'local-demo-business-admin' 'BUSINESS_ADMIN'
$it = Headers 'local-demo-tenant-it' 'TENANT_IT_ADMIN'
function EnsureField([string]$SourceId, [string]$Key, [string]$Label, [string]$Type, [bool]$Identifier, [bool]$Required, [string]$Normalization, [string]$Mode) {
    $null = Api 'PUT' "/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$Key" $admin @{
        displayLabel = $Label; fieldType = $Type; required = $Required; businessIdentifier = $Identifier; searchable = $true
        normalization = $Normalization; searchMode = $Mode; validationRules = @{}
    }
    Ok "Field ready: $Label"
}

Write-Host '=== SDV CANVAS DEMO DATA (local only) ==='
try { $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5 } catch { throw "SDV is not reachable at $Base. Start it with .\start-local.cmd" }
if ($health.status -ne 'UP') { throw "SDV is not UP at $Base" }

# ---- Organization ----
$null = Db "update tenants set display_name='Acme Lending', updated_at=now() where tenant_id='$Tenant' and display_name<>'Acme Lending';"
Ok 'Organization: Acme Lending'

# ---- Loan Origination System ----
# Windows PowerShell returns a JSON array as one object; piping a variable that holds it lists the items.
$rawSources = Api 'GET' '/api/v1/admin/ingestion-sources' $it
$sources = @($rawSources | ForEach-Object { $_ })
$los = $sources | Where-Object { $_.sourceKey -eq 'loanOrigination' } | Select-Object -First 1
if (-not $los) {
    $los = Api 'POST' '/api/v1/admin/ingestion-sources' $it @{ sourceKey = 'loanOrigination'; displayName = 'Loan Origination System'; authType = 'OAUTH2_CLIENT_CREDENTIALS'; credentialReference = 'vault/loan-origination-client' }
    Ok 'Created sending system: Loan Origination System'
}
if ($los.status -ne 'ACTIVE') {
    $los = Api 'PUT' "/api/v1/admin/ingestion-sources/$($los.sourceId)" $it @{ displayName = $los.displayName; status = 'ACTIVE'; authType = $los.authType; credentialReference = $los.credentialReference }
}
$LosId = [string]$los.sourceId
EnsureField $LosId 'contractNumber' 'Contract number' 'TEXT' $true $true 'DIGITS_ONLY' 'EXACT'
EnsureField $LosId 'borrowerName' 'Borrower' 'TEXT' $false $true 'TRIM' 'PARTIAL'
EnsureField $LosId 'loanType' 'Loan type' 'TEXT' $false $true 'TRIM' 'EXACT'
EnsureField $LosId 'closingDate' 'Closing date' 'DATE' $false $false 'NONE' 'EXACT'
$null = Api 'PUT' "/api/v1/admin/metadata-sources/$LosId/display-order" $admin @{ fieldKeys = @('contractNumber', 'borrowerName', 'loanType', 'closingDate') }
$null = Api 'PUT' "/api/v1/admin/metadata-sources/$LosId/record-title" $admin @{ fieldKeys = @('borrowerName') }
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
    $submissionId = Db "select submission_id from submissions where tenant_id='$Tenant' and source_id='$LosId' and source_submission_id='$subKey' limit 1;"
    $manifest = @(); for ($i = 0; $i -lt $files.Count; $i++) { $manifest += @{ sourceDocumentId = "$subKey-$($i + 1)" } }
    foreach ($m in $missing) { $manifest += @{ sourceDocumentId = $m } }
    if ([string]::IsNullOrWhiteSpace($submissionId)) {
        $s = Api 'POST' '/api/v1/ingestion/submissions' (SourceHeaders $LosId) @{
            sourceSubmissionId = $subKey; expectedDocumentCount = $files.Count + $missing.Count; expectedDocuments = $manifest
            metadata = @{ contractNumber = $r.n; borrowerName = $r.who; loanType = $r.type; closingDate = $r.closing }
        }
        $submissionId = [string]$s.submissionId
        Ok "Record $($r.n) $($r.who)"
    }
    for ($i = 0; $i -lt $files.Count; $i++) {
        $docKey = "$subKey-$($i + 1)"
        $have = Db "select count(*) from documents where tenant_id='$Tenant' and submission_id='$submissionId' and source_document_id='$docKey';"
        if ([int]$have -eq 0) {
            $h = SourceHeaders $LosId; $h['X-SDV-Source-Document-Id'] = $docKey; $h['X-SDV-Filename'] = [string]$files[$i]
            $null = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $h -ContentType 'application/pdf' -InFile $PdfPath -UseBasicParsing -TimeoutSec 30
        }
    }
}
Ok 'Loan Origination System records delivered'

# ---- LexaVault: a loan type and full names on every demo record ----
$lexa = $sources | Where-Object { $_.displayName -eq 'LexaVault' } | Select-Object -First 1
if (-not $lexa) { throw 'The LexaVault demo source was not found. Run Seed-SDV-Demo-Records.ps1 first.' }
$LexaId = [string]$lexa.sourceId
EnsureField $LexaId 'loanType' 'Loan Type' 'TEXT' $false $false 'TRIM' 'EXACT'
# The stored field order must name every field (the screen tour puts it back as it found it), so Loan Type is added
# to it. This is the order the demo source had, with Loan Type last.
$null = Api 'PUT' "/api/v1/admin/metadata-sources/$LexaId/display-order" $admin @{ fieldKeys = @('contractNumber', 'firstName', 'lastName', 'memberNumber', 'serviceEntity', 'loanType') }
$null = Api 'PUT' "/api/v1/admin/metadata-sources/$LexaId/record-title" $admin @{ fieldKeys = @('firstName', 'lastName') }
Ok 'LexaVault field order (Loan Type last) and Record title: First Name + Last Name'

# Names for the deliveries that were sent without them, and a loan type on every LexaVault delivery. Written as a
# delivery would have stored them (trimmed text: stored and normalized values are the same).
$names = @{ '100000000001' = @('Jordan', 'Ellis'); '100000000002' = @('Priya', 'Shah'); '100000000003' = @('Marcus', 'Lee') }
function AddValue([string]$SubmissionId, [string]$Key, [string]$Value) {
    $null = Db @"
insert into business_metadata_values (metadata_value_id, tenant_id, submission_id, definition_id, field_key, definition_version, stored_value, normalized_value, source_id)
select gen_random_uuid(), d.tenant_id, '$SubmissionId', d.definition_id, d.field_key, d.version, $(Lit $Value), $(Lit $Value), d.source_id
from metadata_field_definitions d
where d.tenant_id='$Tenant' and d.source_id='$LexaId' and d.field_key='$Key' and d.active
  and not exists (select 1 from business_metadata_values v where v.tenant_id='$Tenant' and v.submission_id='$SubmissionId' and v.field_key='$Key')
order by d.version desc limit 1;
"@
}
$rows = Db "select s.submission_id || '|' || coalesce((select v.normalized_value from business_metadata_values v where v.submission_id=s.submission_id and v.field_key='contractNumber'),'') from submissions s where s.tenant_id='$Tenant' and s.source_id='$LexaId';"
$index = 0
foreach ($line in ($rows -split "`n" | Where-Object { $_.Trim() })) {
    $sid, $contract = $line.Trim().Split('|')
    if ($names.ContainsKey($contract)) { AddValue $sid 'firstName' $names[$contract][0]; AddValue $sid 'lastName' $names[$contract][1] }
    AddValue $sid 'loanType' $(if ($index % 3 -eq 2) { 'Auto' } else { 'Mortgage' })
    $index++
}
Ok "LexaVault: names on 100000000001-100000000003, a loan type on $index deliveries"

# ---- Admin canvas (task 0019): two storage volumes and a second sending system ----
$rawVolumes = Api 'GET' '/api/v1/admin/storage-volumes' $it
$volumes = @($rawVolumes | ForEach-Object { $_ })
$inUse = $volumes | Where-Object { $_.volume.displayName -in @('Local demo volume', 'primary-a') } | Select-Object -First 1
if ($inUse -and $inUse.volume.displayName -ne 'primary-a') {
    $v = $inUse.volume
    $null = Api 'PUT' "/api/v1/admin/storage-volumes/$($v.volumeId)" $it @{ displayName = 'primary-a'; state = $v.state; priority = $v.priority; warningPercent = $v.warningPercent; stopWritePercent = $v.stopWritePercent }
}
Ok 'Volume in use: primary-a'
if (-not ($volumes | Where-Object { $_.volume.displayName -eq 'primary-b' })) {
    $folder = Join-Path $PSScriptRoot '.runtime\demo-storage-b'
    New-Item -ItemType Directory -Force -Path $folder | Out-Null
    $priority = if ($inUse) { [int]$inUse.volume.priority + 1 } else { 2 }
    $null = Api 'POST' '/api/v1/admin/storage-volumes' $it @{ displayName = 'primary-b'; rootPath = $folder; state = 'ACTIVE'; priority = $priority; warningPercent = 80; stopWritePercent = 95 }
}
Ok 'Standby volume: primary-b'
$tr = $sources | Where-Object { $_.sourceKey -eq 'titleRecords' } | Select-Object -First 1
if (-not $tr) {
    $tr = Api 'POST' '/api/v1/admin/ingestion-sources' $it @{ sourceKey = 'titleRecords'; displayName = 'Title Records'; authType = 'OAUTH2_CLIENT_CREDENTIALS'; credentialReference = 'vault/title-records-client' }
}
# The canvas shows Title Records paused.
if ($tr.status -ne 'DISABLED') {
    $null = Api 'PUT' "/api/v1/admin/ingestion-sources/$($tr.sourceId)" $it @{ displayName = $tr.displayName; status = 'DISABLED'; authType = $tr.authType; credentialReference = $tr.credentialReference }
}
Ok 'Sending system: Title Records (paused)'
Write-Host 'Canvas demo data ready.' -ForegroundColor Green
