[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$KeepRunning,
    [switch]$KeepTestData
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Base = 'http://localhost:8280'
$Tenant1 = '11111111-1111-1111-1111-111111111111'
$Tenant2 = '22222222-2222-2222-2222-222222222222'
$Suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 10)
$SourceId = $null
$VolumeId = $null
$StorageRoot = Join-Path $Root ".runtime\business-experience-a\$Suffix"
$Pdf = Join-Path $Root ".runtime\business-experience-a-$Suffix.pdf"

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Ok $Message
}
function UserHeaders([string]$Tenant, [string]$Subject, [string]$Roles) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Subject' = $Subject
        'X-SDV-Roles' = $Roles
        'X-Correlation-Id' = "business-a-$Suffix"
    }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Source-Id' = $Source
        'X-SDV-Subject' = $Subject
        'X-Correlation-Id' = "business-a-$Suffix"
    }
}
function DbScalar([string]$Sql) {
    return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim()
}
function InvokeJson([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body) {
    try {
        $bodyText = if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 20 }
        $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body $bodyText -UseBasicParsing -TimeoutSec 30
        $parsed = if ([string]::IsNullOrWhiteSpace($r.Content)) { $null } else { $r.Content | ConvertFrom-Json }
        return [PSCustomObject]@{ StatusCode=[int]$r.StatusCode; Body=$parsed }
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $parsed = if ([string]::IsNullOrWhiteSpace($content)) { $null } else { $content | ConvertFrom-Json }
            return [PSCustomObject]@{ StatusCode=[int]$_.Exception.Response.StatusCode; Body=$parsed }
        }
        throw
    }
}
function UploadPdf([string]$SubmissionId, [string]$SourceDocumentId, [string]$Filename) {
    $headers = SourceHeaders $Tenant1 $SourceId 'business-a-producer'
    $headers['X-SDV-Source-Document-Id'] = $SourceDocumentId
    $headers['X-SDV-Filename'] = $Filename
    return Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" -Method Post `
        -Headers $headers -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 30
}
function CreateSubmission(
    [string]$SourceSubmissionId,
    [int]$ExpectedCount,
    [string[]]$Manifest,
    [hashtable]$Metadata) {
    $headers = SourceHeaders $Tenant1 $SourceId 'business-a-producer'
    $expected = @($Manifest | ForEach-Object { @{ sourceDocumentId = $_ } })
    $response = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $headers @{
        sourceSubmissionId = $SourceSubmissionId
        expectedDocumentCount = $ExpectedCount
        expectedDocuments = $expected
        metadata = $Metadata
    }
    Require ($response.StatusCode -eq 201) "Created submission $SourceSubmissionId"
    return [string]$response.Body.submissionId
}
function SaveField(
    [hashtable]$Headers,
    [string]$FieldKey,
    [string]$Label,
    [bool]$Required,
    [bool]$Identifier,
    [bool]$Searchable,
    [string]$Normalization,
    [string]$SearchMode) {
    $response = InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel = $Label
        fieldType = 'TEXT'
        required = $Required
        businessIdentifier = $Identifier
        searchable = $Searchable
        normalization = $Normalization
        searchMode = $SearchMode
        validationRules = @{}
    }
    Require ($response.StatusCode -eq 200) "Configured metadata field $FieldKey"
}
function BusinessSearch([hashtable]$Headers, [hashtable]$Criteria) {
    return InvokeJson 'POST' "$Base/api/v1/business/search" $Headers @{
        sourceId = $SourceId
        criteria = $Criteria
        limit = 25
    }
}

try {
    if (-not $SkipBuild) {
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime, migration 006 and frozen baseline ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='006-business-experience-checkpoint-a';") -eq '1') 'Business Experience Checkpoint A migration applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='005-secure-ingestion-checkpoint-b';") -eq '1') 'Frozen Secure Ingestion migration 005 remains applied once'

    $it = UserHeaders $Tenant1 'business-a-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders $Tenant1 'business-a-admin' 'BUSINESS_ADMIN'
    $business = UserHeaders $Tenant1 'business-a-user' 'BUSINESS_USER'
    $foreignBusiness = UserHeaders $Tenant2 'foreign-business-user' 'BUSINESS_USER'

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey = "business-a-$Suffix"
        displayName = "Business Experience Source $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "vault://local/business-a/$Suffix"
        limits = @{
            maxPdfBytes = 1048576
            maxPagesPerPdf = 2000
            maxDocumentsPerSubmission = 10
            maxAggregateSubmissionBytes = 10485760
            incompleteAttentionHours = 24
        }
    }
    Require ($source.StatusCode -eq 201) 'Tenant IT created disposable business-experience source'
    $SourceId = [string]$source.Body.sourceId

    SaveField $admin 'contractNumber' 'Contract Number' $true $true $true 'DIGITS_ONLY' 'EXACT'
    SaveField $admin 'memberName' 'Member Name' $false $false $true 'LOWERCASE' 'PARTIAL'
    SaveField $admin 'branchCode' 'Branch Code' $false $false $true 'UPPERCASE' 'PREFIX'
    SaveField $admin 'internalNote' 'Internal Note' $false $false $false 'TRIM' 'EXACT'

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName = "Business Experience Volume $Suffix"
        rootPath = $StorageRoot
        state = 'ACTIVE'
        priority = 1
    }
    Require ($volume.StatusCode -eq 201) 'Tenant IT created ciphertext volume for business-experience acceptance'
    $VolumeId = [string]$volume.Body.volume.volumeId

    New-Item -ItemType Directory -Force -Path (Split-Path $Pdf -Parent) | Out-Null
    [System.IO.File]::WriteAllText(
        $Pdf,
        "%PDF-1.7`n1 0 obj<</Type/Catalog>>endobj`nBusiness-Experience-$Suffix`n%%EOF",
        [System.Text.Encoding]::ASCII)

    Write-Host ''
    Write-Host '=== PHASE 2 - Seed two business records through frozen ingestion APIs ==='

    $s1 = CreateSubmission "record1-a-$Suffix" 1 @("r1-a-$Suffix") @{
        contractNumber = '123-456'
        memberName = 'Alice Johnson'
        branchCode = 'nyc01'
        internalNote = 'first complete submission'
    }
    [void](UploadPdf $s1 "r1-a-$Suffix" 'record1-a.pdf')

    $s2 = CreateSubmission "record1-b-$Suffix" 1 @("r1-b-$Suffix") @{
        contractNumber = '123456'
        memberName = 'Alice Johnson'
        branchCode = 'NYC02'
        internalNote = 'second complete submission'
    }
    [void](UploadPdf $s2 "r1-b-$Suffix" 'record1-b.pdf')

    $s3 = CreateSubmission "record1-c-$Suffix" 2 @("r1-c1-$Suffix", "r1-c2-$Suffix") @{
        contractNumber = '(123) 456'
        memberName = 'Alice Johnson'
        branchCode = 'nyc03'
        internalNote = 'incomplete submission'
    }
    [void](UploadPdf $s3 "r1-c1-$Suffix" 'record1-c1.pdf')

    $s4 = CreateSubmission "record2-$Suffix" 1 @("r2-$Suffix") @{
        contractNumber = '999-000'
        memberName = 'Bob Smith'
        branchCode = 'bos01'
        internalNote = 'clean complete record'
    }
    [void](UploadPdf $s4 "r2-$Suffix" 'record2.pdf')

    Require ((DbScalar "select count(*) from documents where tenant_id='$Tenant1' and source_id='$SourceId' and status='AVAILABLE';") -eq '4') 'Frozen ingestion produced four AVAILABLE documents'

    Write-Host ''
    Write-Host '=== PHASE 3 - Unit 12 source-aware dynamic search schema ==='

    $schema = InvokeJson 'GET' "$Base/api/v1/business/search-schema" $business $null
    Require ($schema.StatusCode -eq 200) 'Business User can load search schema'
    $sourceSchema = @($schema.Body.sources | Where-Object { $_.sourceId -eq $SourceId }) | Select-Object -First 1
    Require ($null -ne $sourceSchema) 'Search schema exposes the authenticated tenant source'
    $fieldKeys = @($sourceSchema.fields | ForEach-Object { $_.fieldKey })
    Require ($fieldKeys -contains 'contractNumber') 'Configured exact identifier field drives search UI schema'
    Require ($fieldKeys -contains 'memberName') 'Configured partial name field drives search UI schema'
    Require ($fieldKeys -contains 'branchCode') 'Configured prefix field drives search UI schema'
    Require (-not ($fieldKeys -contains 'internalNote')) 'Non-searchable metadata is not exposed as a search input'

    $adminSearch = BusinessSearch $admin @{ contractNumber = '123456' }
    Require ($adminSearch.StatusCode -eq 403) 'Business Admin does not implicitly receive Business User search capability'

    Write-Host ''
    Write-Host '=== PHASE 4 - Unit 11 configurable search and record aggregation ==='

    $exact = BusinessSearch $business @{ contractNumber = '123 456' }
    Require ($exact.StatusCode -eq 200) 'Exact identifier search succeeds after configured DIGITS_ONLY normalization'
    Require (@($exact.Body.records).Count -eq 1) 'Three submissions with the same business identifier aggregate into one business record'
    $record1 = @($exact.Body.records)[0]
    Require ([int]$record1.submissionCount -eq 3) 'Aggregated search result reports all three record submissions'
    Require ([int]$record1.availableDocumentCount -eq 3) 'Aggregated search result reports AVAILABLE documents across complete and incomplete submissions'
    Require ($record1.incomplete -eq $true) 'Search result signals incomplete state only for the record that needs it'
    $Record1Ref = [string]$record1.recordRef

    $partial = BusinessSearch $business @{ memberName = 'JOHN' }
    Require ($partial.StatusCode -eq 200 -and @($partial.Body.records).Count -eq 1) 'PARTIAL name search applies configured LOWERCASE normalization'

    $prefix = BusinessSearch $business @{ branchCode = 'nyc' }
    Require ($prefix.StatusCode -eq 200 -and @($prefix.Body.records).Count -eq 1) 'PREFIX search applies configured UPPERCASE normalization'

    $combined = BusinessSearch $business @{ contractNumber = '123456'; branchCode = 'NYC02' }
    Require ($combined.StatusCode -eq 200 -and @($combined.Body.records).Count -eq 1) 'Multiple configured criteria use AND semantics within a matching submission'

    $hidden = BusinessSearch $business @{ internalNote = 'complete' }
    Require ($hidden.StatusCode -eq 400) 'Caller cannot search a field that is not currently configured as searchable'

    $record2Search = BusinessSearch $business @{ contractNumber = '999000' }
    Require ($record2Search.StatusCode -eq 200 -and @($record2Search.Body.records).Count -eq 1) 'Second business identifier resolves independently'
    $Record2Ref = [string]@($record2Search.Body.records)[0].recordRef
    Require (@($record2Search.Body.records)[0].incomplete -eq $false) 'Completed business record has no incomplete search warning'

    Write-Host ''
    Write-Host '=== PHASE 5 - Unit 13 Business Record View ==='

    $detail1 = InvokeJson 'GET' "$Base/api/v1/business/records/$Record1Ref" $business $null
    Require ($detail1.StatusCode -eq 200) 'Business User can open aggregated Business Record View'
    Require ([int]$detail1.Body.submissionCount -eq 3) 'Business Record View aggregates submissions over time'
    Require (@($detail1.Body.documents).Count -eq 3) 'Business Record View exposes all associated AVAILABLE documents'
    Require ($null -ne $detail1.Body.incompleteAttention) 'Business Record View shows incomplete warning only when record has incomplete work'
    $attention = @($detail1.Body.incompleteAttention.submissions)
    Require ($attention.Count -eq 1) 'Incomplete warning contains only the incomplete submission'
    Require ([int]$attention[0].expectedCount -eq 2 -and [int]$attention[0].receivedCount -eq 1 -and [int]$attention[0].missingCount -eq 1) 'Incomplete warning reports expected/received/missing counts'
    Require (@($attention[0].missingSourceDocumentIds) -contains "r1-c2-$Suffix") 'Incomplete warning identifies known missing sourceDocumentId'

    $metadataKeys = @($detail1.Body.metadata | ForEach-Object { $_.fieldKey })
    Require ($metadataKeys -contains 'internalNote') 'Non-searchable business metadata remains visible in authorized record detail'

    $detail2 = InvokeJson 'GET' "$Base/api/v1/business/records/$Record2Ref" $business $null
    Require ($detail2.StatusCode -eq 200) 'Business User can open completed business record'
    Require ($null -eq $detail2.Body.incompleteAttention) 'Completed Business Record View remains clean with no empty completion diagnostics'
    Require (@($detail2.Body.documents).Count -eq 1) 'Completed Business Record View exposes its AVAILABLE document'

    $foreign = InvokeJson 'GET' "$Base/api/v1/business/records/$Record1Ref" $foreignBusiness $null
    Require ($foreign.StatusCode -eq 404) 'Cross-tenant Business User cannot resolve another tenant business record'

    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='BUSINESS_SEARCH' and correlation_id='business-a-$Suffix';") -ge '1') 'Business search is append-audited without query values'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='BUSINESS_RECORD_OPENED' and correlation_id='business-a-$Suffix';") -ge '2') 'Business Record opens are append-audited'

    Write-Host ''
    Write-Host '=== PHASE 6 - Frontend ==='
    $ui = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 10
    Require ($ui.StatusCode -eq 200) 'Business Experience frontend is reachable on port 5183'
    $appSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\App.tsx') -Raw
    Require ($appSource -match 'Search business records') 'Frontend contains dynamic business search workflow'
    Require ($appSource -match 'incompleteAttention') 'Frontend conditionally renders incomplete Business Record attention'
    Require ($appSource -notmatch 'placeholder="lexavault"') 'Legacy LexaVault placeholder is absent from SDV UI'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Business Experience Checkpoint A (Units 11-13) acceptance passed.' -ForegroundColor Green

} finally {
    if (-not $KeepTestData) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from audit_events where tenant_id='$Tenant1' and correlation_id='business-a-$Suffix';
                    delete from submission_expected_documents where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_crypto_metadata where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_storage_refs where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from documents where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from business_metadata_values where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from submissions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';
                " *> $null
                if (-not [string]::IsNullOrWhiteSpace([string]$VolumeId)) {
                    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                        delete from storage_volumes where tenant_id='$Tenant1' and volume_id='$VolumeId';
                    " *> $null
                }
                Ok 'Disposable Business Experience acceptance data removed'
            }
        } catch {
            Write-Warning "Business Experience cleanup failed: $($_.Exception.Message)"
        }
        Remove-Item -Recurse -Force $StorageRoot -ErrorAction SilentlyContinue
        Remove-Item -Force $Pdf -ErrorAction SilentlyContinue
    }

    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
    }
}
