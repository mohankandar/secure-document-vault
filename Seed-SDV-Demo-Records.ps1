[CmdletBinding()]
param(
    [string]$Base = 'http://localhost:8280',
    [string]$SourceDisplayName = 'laxavault',
    [string]$PdfPath = (Join-Path $PSScriptRoot 'test-tools\fixtures\business-viewer-two-page.pdf'),
    [switch]$FreshTestData,
    [ValidatePattern('^[0-9]{12}$')][string]$ContractNumber = '300000000076',
    [ValidatePattern('^([0-9]{11})?$')][string]$MemberNumber = '',
    [ValidateRange(1,100)][int]$Count = 1
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
    return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim()
}
function EnsureField([string]$SourceId,[string]$FieldKey,[string]$Label,[bool]$Identifier,[bool]$Required,[string]$Normalization,[string]$SearchMode) {
    $admin = UserHeaders 'local-demo-business-admin' 'BUSINESS_ADMIN'
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
        if ([int]$existingContract -gt 0 -or [int]$existingSubmission -gt 0) { throw "Contract $contract already exists. No records created. Choose an unused range." }
        $records += @{ contract=$contract; member=$member; firstName=$firstNames[$j % $firstNames.Count]; lastName=$lastNames[([int][math]::Floor($j / $firstNames.Count)) % $lastNames.Count]; service='001'; files=@('Vault Persistence Test.pdf') }
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
    $sourceSubmissionId = if ($FreshTestData) { "vault-persistence-test-$($row.contract)" } else { "r3-demo-contract-$($row.contract)" }
    $escapedSubmission = $sourceSubmissionId.Replace("'", "''")
    $submissionId = DbScalar "select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId' and source_submission_id='$escapedSubmission' limit 1;"

    $manifest=@()
    for($i=0;$i -lt $row.files.Count;$i++) { $manifest += @{ sourceDocumentId=$(if ($FreshTestData) { "vault-persistence-doc-$($row.contract)-$($i+1)" } else { "r3-demo-doc-$($row.contract)-$($i+1)" }) } }

    if ([string]::IsNullOrWhiteSpace($submissionId)) {
        $sourceHeaders=SourceHeaders $SourceId 'local-demo-producer'
        $submission=InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
            sourceSubmissionId=$sourceSubmissionId
            expectedDocumentCount=$row.files.Count
            expectedDocuments=$manifest
            metadata=$(if ($FreshTestData) { @{ contractNumber=$row.contract; memberNumber=$row.member; serviceEntity=$row.service; firstName=$row.firstName; lastName=$row.lastName } } else { @{ contractNumber=$row.contract; memberNumber=$row.member; serviceEntity=$row.service } })
        }
        $submissionId=[string]$submission.submissionId
        Ok "Created business record $($row.contract)"
    } else {
        Ok "Business record already exists: $($row.contract)"
    }

    for($i=0;$i -lt $row.files.Count;$i++) {
        $sourceDocumentId=if ($FreshTestData) { "vault-persistence-doc-$($row.contract)-$($i+1)" } else { "r3-demo-doc-$($row.contract)-$($i+1)" }
        $filename=[string]$row.files[$i]
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
$records | ForEach-Object { Write-Host ("  Contract {0} | Member {1} | Service Entity {2} | {3} PDF(s)" -f $_.contract,$_.member,$_.service,$_.files.Count) }
Write-Host ''
Write-Host "Use Source: $ResolvedName" -ForegroundColor Cyan
Write-Host 'Demo seed complete.' -ForegroundColor Green
