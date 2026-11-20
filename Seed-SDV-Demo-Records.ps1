[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8280',
    [string]$SourceDisplayName = 'laxavault',
    [string]$PdfPath = (Join-Path $PSScriptRoot 'test-tools\fixtures\business-viewer-two-page.pdf'),
    [switch]$FreshTestData,
    [ValidatePattern('^[0-9]{12}$')][string]$ContractNumber = '300000000076',
    [ValidatePattern('^([0-9]{11})?$')][string]$MemberNumber = '',
    [ValidateRange(1,100)][int]$Count = 1,
    # Task 0011: one record whose document could not be stored and one whose document is stuck (local only).
    [switch]$ProblemDemo,
    # Task 0023: the local database to read (sdv, or a scratch database such as sdv_seed_test in sdv-postgres).
    [ValidatePattern('^sdv(_[a-z0-9_]+)?$')][string]$Database = 'sdv'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Tenant = '11111111-1111-1111-1111-111111111111'
$Correlation = 'r3-demo-seed-' + ([guid]::NewGuid().ToString('N')).Substring(0, 10)

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function UserHeaders([string]$Subject,[string]$Roles) {
    return @{ 'X-SDV-Tenant-Id'=$Tenant; 'X-SDV-Subject'=$Subject; 'X-SDV-Roles'=$Roles; 'X-Correlation-Id'=$Correlation }
}
function SourceHeaders([string]$SourceId,[string]$Subject) {
    return @{ 'X-SDV-Tenant-Id'=$Tenant; 'X-SDV-Source-Id'=$SourceId; 'X-SDV-Subject'=$Subject; 'X-Correlation-Id'=$Correlation }
}
function InvokeJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body=$null) {
    $bodyText = if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 30 }
    $params = @{ Uri=$Uri; Method=$Method; Headers=$Headers; UseBasicParsing=$true; TimeoutSec=30 }
    if ($null -ne $Body) { $params.ContentType='application/json'; $params.Body=$bodyText }
    try {
        $r = Invoke-WebRequest @params
    } catch {
        $detail=''
        if ($_.Exception.Response) {
            try { $reader=New-Object IO.StreamReader($_.Exception.Response.GetResponseStream()); $detail=$reader.ReadToEnd() } catch { }
        }
        throw "HTTP $Method $Uri failed. $detail"
    }
    if ([string]::IsNullOrWhiteSpace($r.Content)) { return $null }
    return $r.Content | ConvertFrom-Json
}
function DbScalar([string]$Sql) {
    return (& docker exec sdv-postgres psql -U sdv -d $Database -v ON_ERROR_STOP=1 -tAc $Sql | Out-String).Trim()
}
# Task 0023: a field that already exists is left as it is. Every PUT adds a new field version and an audit event,
# so sending it again on each run would change the data every time.
function EnsureField([string]$SourceId,[string]$FieldKey,[string]$Label,[bool]$Identifier,[bool]$Required,[string]$Normalization,[string]$SearchMode) {
    $admin = UserHeaders 'local-demo-business-admin' 'BUSINESS_ADMIN'
    $have = DbScalar "select count(*) from metadata_field_definitions where tenant_id='$Tenant' and source_id='$SourceId' and field_key='$FieldKey' and active;"
    if ([int]$have -gt 0) { Ok "Metadata field already there: $Label"; return }
    InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $admin @{
        displayLabel=$Label; fieldType='TEXT'; required=$Required; businessIdentifier=$Identifier; searchable=$true
        normalization=$Normalization; searchMode=$SearchMode; validationRules=@{}
    } | Out-Null
    Ok "Metadata field ready: $Label"
}

Write-Host '=== SDV DEMO BUSINESS RECORD SEED ==='
if (-not (Test-Path -LiteralPath $PdfPath -PathType Leaf)) { throw "Demo PDF fixture not found: $PdfPath" }
try { $health=Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5 } catch { throw "SDV backend is not reachable at $Base. Start it with: .\Start-SDV-Local.ps1" }
if ($health.status -ne 'UP') { throw "SDV backend is not UP at $Base" }
Ok 'Backend is UP'

# Resolve one exact source. This avoids Windows PowerShell property-enumeration behavior
# that can accidentally concatenate every returned source id into one string.
$escapedName=$SourceDisplayName.Replace("'", "''")
$SourceId=DbScalar "select source_id from ingestion_sources where tenant_id='$Tenant' and (lower(display_name)=lower('$escapedName') or lower(source_key)=lower('lexaVault')) order by case when lower(display_name)=lower('$escapedName') then 0 else 1 end, created_at desc limit 1;"
if ([string]::IsNullOrWhiteSpace($SourceId)) { throw "Ingestion source '$SourceDisplayName' / sourceKey 'lexaVault' was not found." }
$ResolvedName=DbScalar "select display_name from ingestion_sources where tenant_id='$Tenant' and source_id='$SourceId';"
Ok "Using source: $ResolvedName ($SourceId)"

if ($ProblemDemo) {
    # Two records sent through the API like any other, then one document set to each problem state directly in
    # the local development database. Never run this against anything but the local stack.
    if ($Base -notmatch '^http://(localhost|127\.0\.0\.1)[:/]') { throw '-ProblemDemo only runs against the local stack.' }
    EnsureField $SourceId 'contractNumber' 'Contract Number' $true $true 'DIGITS_ONLY' 'EXACT'
    EnsureField $SourceId 'memberNumber' 'Member Number' $true $true 'DIGITS_ONLY' 'EXACT'
    EnsureField $SourceId 'serviceEntity' 'Service Entity' $true $true 'TRIM' 'EXACT'
    $problems = @(
        @{ contract='300000000094'; member='20000000094'; firstName='Olivia'; lastName='Brown'; file='appraisal.pdf'; kind='could-not-store' },
        @{ contract='300000000095'; member='20000000095'; firstName='Arjun'; lastName='Singh'; file='title-deed.pdf'; kind='stuck' },
        @{ contract='300000000096'; member='20000000096'; firstName='Sofia'; lastName='Garcia'; file='loan-agreement.pdf'; kind='missing' }
    )
    foreach ($p in $problems) {
        $sourceSubmissionId = "problem-demo-$($p.contract)"
        $submissionId = DbScalar "select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id='$sourceSubmissionId' limit 1;"
        $sourceDocumentId = "problem-demo-doc-$($p.contract)"
        if ([string]::IsNullOrWhiteSpace($submissionId)) {
            $submission = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" (SourceHeaders $SourceId 'local-demo-producer') @{
                sourceSubmissionId=$sourceSubmissionId; expectedDocumentCount=$(if ($p.kind -eq 'missing') { 2 } else { 1 }); expectedDocuments=@(if ($p.kind -eq 'missing') { @{ sourceDocumentId=$sourceDocumentId }; @{ sourceDocumentId="$sourceDocumentId-2" } } else { @{ sourceDocumentId=$sourceDocumentId } })
                metadata=@{ contractNumber=$p.contract; memberNumber=$p.member; serviceEntity='001'; firstName=$p.firstName; lastName=$p.lastName }
            }
            $submissionId = [string]$submission.submissionId
            Ok "Created problem record $($p.contract)"
        }
        $documentId = DbScalar "select document_id from documents where tenant_id='$Tenant' and submission_id='$submissionId' and source_document_id='$sourceDocumentId' limit 1;"
        if ([string]::IsNullOrWhiteSpace($documentId)) {
            $headers = SourceHeaders $SourceId 'local-demo-producer'
            $headers['X-SDV-Source-Document-Id'] = $sourceDocumentId
            $headers['X-SDV-Filename'] = $p.file
            $null = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $headers -ContentType 'application/pdf' -InFile $PdfPath -UseBasicParsing -TimeoutSec 30
            $documentId = DbScalar "select document_id from documents where tenant_id='$Tenant' and submission_id='$submissionId' and source_document_id='$sourceDocumentId' limit 1;"
        }
        if ($p.kind -eq 'missing') {
            # 1 of 2 received, last activity a week ago: past any attention time, so it is listed under Missing documents.
            $null = DbScalar "update submissions set last_activity_at=now() - interval '7 days' where tenant_id='$Tenant' and submission_id='$submissionId' and last_activity_at > now() - interval '7 days';"
            Ok "$($p.contract): 1 of 2 documents received a week ago"
        } elseif ($p.kind -eq 'could-not-store') {
            $null = DbScalar "update documents set status='PROCESSING_FAILED', processing_error_code='DOCUMENT_FINALIZATION_FAILED', processing_attempt_count=3 where tenant_id='$Tenant' and document_id='$documentId' and status<>'PROCESSING_FAILED';"
            Ok "$($p.contract): $($p.file) could not be stored"
        } else {
            $null = DbScalar "update documents set status='PROCESSING', processing_error_code=null, ingested_at=now() - interval '2 days' where tenant_id='$Tenant' and document_id='$documentId' and status<>'PROCESSING';"
            Ok "$($p.contract): $($p.file) is stuck (still being stored after 2 days)"
        }
    }
    Write-Host 'Problem demo complete.' -ForegroundColor Green
    return
}

# Fresh mode preflights the complete batch before any API writes.
if ($FreshTestData) {
    $startContract = [long]::Parse($ContractNumber)
    $startMember = if ($MemberNumber) { [long]::Parse($MemberNumber) } else { 20000000000L + ($startContract % 1000000000L) }
    if ($startContract + $Count - 1 -gt 999999999999L) { throw 'Contract range exceeds 12 digits.' }
    if ($startMember + $Count - 1 -gt 99999999999L) { throw 'Member range exceeds 11 digits.' }
    $firstNames = @('Ava','Noah','Mia','Liam','Emma','Ethan','Sofia','Lucas','Olivia','Arjun')
    $lastNames = @('Patel','Reed','Shah','Chen','Garcia','Kim','Singh','Brown','Lopez','Miller')
    $records = @()
    for ($j = 0; $j -lt $Count; $j++) {
        $contract = ($startContract + $j).ToString('D12')
        $member = ($startMember + $j).ToString('D11')
        $existingContract = DbScalar "select count(distinct submission_id) from business_metadata_values where tenant_id='$Tenant' and source_id='$SourceId' and field_key='contractNumber' and normalized_value='$contract';"
        $existingSubmission = DbScalar "select count(*) from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id='vault-persistence-test-$contract';"
        if ([int]$existingContract -gt 0 -or [int]$existingSubmission -gt 0) {
            # Resume only the exact fixture created by this script, never a pre-existing
            # business record or a different source submission using this contract.
            $matchingMember = DbScalar "select count(*) from business_metadata_values v join submissions s on s.submission_id=v.submission_id and s.tenant_id=v.tenant_id where v.tenant_id='$Tenant' and v.source_id='$SourceId' and v.field_key='memberNumber' and v.normalized_value='$member' and s.source_submission_id='vault-persistence-test-$contract';"
            if ([int]$existingContract -ne 1 -or [int]$existingSubmission -ne 1 -or [int]$matchingMember -ne 1) {
                throw "Contract $contract is occupied by another or inconsistent record. Nothing new was created during preflight."
            }
            Ok "Existing matching test fixture will be resumed: $contract"
        }
        $records += @{ contract=$contract; member=$member; firstName=$firstNames[$j % $firstNames.Count]; lastName=$lastNames[([int][math]::Floor($j / $firstNames.Count)) % $lastNames.Count]; service='001'; files=$(if ($j -eq 0) { @('Member Agreement.pdf', 'Identity Verification.pdf', 'Terms and Conditions.pdf') } elseif ($j -eq 1) { @('Application Form.pdf', 'Supporting Document.pdf') } else { @('Vault Persistence Test.pdf') }) }
    }
    EnsureField $SourceId 'contractNumber' 'Contract Number' $true $true 'DIGITS_ONLY' 'EXACT'
    EnsureField $SourceId 'memberNumber' 'Member Number' $true $true 'DIGITS_ONLY' 'EXACT'
    EnsureField $SourceId 'serviceEntity' 'Service Entity' $true $true 'TRIM' 'EXACT'
    EnsureField $SourceId 'firstName' 'First Name' $false $false 'TRIM' 'EXACT'
    EnsureField $SourceId 'lastName' 'Last Name' $false $false 'TRIM' 'EXACT'
} else {
    EnsureField $SourceId 'contractNumber' 'Contract Number' $true $true 'DIGITS_ONLY' 'EXACT'
    EnsureField $SourceId 'memberNumber' 'Member Number' $true $true 'DIGITS_ONLY' 'EXACT'
    EnsureField $SourceId 'serviceEntity' 'Service Entity' $true $true 'TRIM' 'EXACT'
    $records = @(
        @{ contract='100000000001'; member='20000000001'; service='001'; files=@('Member Agreement.pdf','Identity Verification.pdf','Terms and Conditions.pdf') },
        @{ contract='100000000002'; member='20000000002'; service='002'; files=@('Application Form.pdf') },
        @{ contract='100000000003'; member='20000000003'; service='003'; files=@('Member Correspondence.pdf') }
    )
}

foreach ($row in $records) {
    $files = @($row.files)  # Always an array, even for one PDF under StrictMode.
    $sourceSubmissionId = if ($FreshTestData) { "vault-persistence-test-$($row.contract)" } else { "r3-demo-contract-$($row.contract)" }
    $escapedSubmission = $sourceSubmissionId.Replace("'", "''")
    $submissionId = DbScalar "select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id='$escapedSubmission' limit 1;"

    $manifest=@()
    for($i=0;$i -lt $files.Count;$i++) { $manifest += @{ sourceDocumentId=$(if ($FreshTestData) { "vault-persistence-doc-$($row.contract)-$($i+1)" } else { "r3-demo-doc-$($row.contract)-$($i+1)" }) } }

    if ([string]::IsNullOrWhiteSpace($submissionId)) {
        $sourceHeaders=SourceHeaders $SourceId 'local-demo-producer'
        $submission=InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
            sourceSubmissionId=$sourceSubmissionId
            expectedDocumentCount=$files.Count
            expectedDocuments=$manifest
            metadata=$(if ($FreshTestData) { @{ contractNumber=$row.contract; memberNumber=$row.member; serviceEntity=$row.service; firstName=$row.firstName; lastName=$row.lastName } } else { @{ contractNumber=$row.contract; memberNumber=$row.member; serviceEntity=$row.service } })
        }
        $submissionId=[string]$submission.submissionId
        Ok "Created business record $($row.contract)"
    } else {
        Ok "Business record already exists: $($row.contract)"
    }

    for($i=0;$i -lt $files.Count;$i++) {
        $sourceDocumentId=if ($FreshTestData) { "vault-persistence-doc-$($row.contract)-$($i+1)" } else { "r3-demo-doc-$($row.contract)-$($i+1)" }
        $filename=[string]$files[$i]
        $escapedDoc=$sourceDocumentId.Replace("'", "''")
        $available=DbScalar "select count(*) from documents where tenant_id='$Tenant' and submission_id='$submissionId' and source_document_id='$escapedDoc' and status='AVAILABLE';"
        if ([int]$available -eq 0) {
            $uploadHeaders=SourceHeaders $SourceId 'local-demo-producer'
            $uploadHeaders['X-SDV-Source-Document-Id']=$sourceDocumentId
            $uploadHeaders['X-SDV-Filename']=$filename
            $upload=Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$submissionId/documents" -Method Post -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $PdfPath -UseBasicParsing -TimeoutSec 30
            $body=$upload.Content | ConvertFrom-Json
            if ($body.currentStatus -ne 'AVAILABLE') { throw "Document '$filename' for $($row.contract) did not become AVAILABLE. Status: $($body.currentStatus)" }
            Ok "Uploaded AVAILABLE PDF: $filename"
        } else {
            Ok "AVAILABLE PDF already exists: $filename"
        }
    }
}

Write-Host ''
Write-Host '=== DEMO SEARCH VALUES ===' -ForegroundColor Cyan
$records | ForEach-Object { Write-Host ("  Contract {0} | Member {1} | Service Entity {2} | {3} PDF(s)" -f $_.contract,$_.member,$_.service,@($_.files).Count) }
Write-Host ''
Write-Host "Use Source: $ResolvedName" -ForegroundColor Cyan
Write-Host 'Demo seed complete.' -ForegroundColor Green
