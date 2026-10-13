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
$SourceKey = "ingest-a-$Suffix"
$SourceId = $null
$SubmissionId = $null
$Volume1Id = $null
$Volume2Id = $null
$TestStorageRoot = Join-Path $Root ".runtime\secure-ingestion-a\$Suffix"
$Passed = $false

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
        'X-Correlation-Id' = "ingest-a-$Suffix"
    }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Source-Id' = $Source
        'X-SDV-Subject' = $Subject
        'X-Correlation-Id' = "ingest-a-$Suffix"
    }
}
function HttpStatus([string]$Method, [string]$Uri, [hashtable]$Headers = @{}, [object]$Body = $null) {
    try {
        $args = @{ Uri=$Uri; Method=$Method; Headers=$Headers; UseBasicParsing=$true; TimeoutSec=15 }
        if ($null -ne $Body) {
            $args['ContentType'] = 'application/json'
            $args['Body'] = ($Body | ConvertTo-Json -Depth 12)
        }
        $r = Invoke-WebRequest @args
        return [int]$r.StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}
function DbScalar([string]$Sql) {
    return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim()
}
function InvokeJson([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body = $null) {
    $args = @{ Uri=$Uri; Method=$Method; Headers=$Headers; UseBasicParsing=$true; TimeoutSec=15 }
    if ($null -ne $Body) {
        $args['ContentType'] = 'application/json'
        $args['Body'] = ($Body | ConvertTo-Json -Depth 12)
    }
    $response = Invoke-WebRequest @args
    return [PSCustomObject]@{
        StatusCode = [int]$response.StatusCode
        Body = ($response.Content | ConvertFrom-Json)
    }
}

$it1 = UserHeaders $Tenant1 'ingest-a-it-1' 'TENANT_IT_ADMIN'
$biz1 = UserHeaders $Tenant1 'ingest-a-biz-1' 'BUSINESS_ADMIN'
$it2 = UserHeaders $Tenant2 'ingest-a-it-2' 'TENANT_IT_ADMIN'

Write-Host ''
Write-Host '=== SDV SECURE INGESTION CHECKPOINT A (UNITS 5, 7, 8) ==='

try {
    if (-not $SkipBuild) {
        Write-Host ''
        Write-Host '=== PHASE 0 - Build and focused unit tests ==='
        & (Join-Path $Root 'Build-SDV-All.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
        Ok 'Backend + frontend build passed'
        Require (Test-Path (Join-Path $Root 'backend\sdv-crypto\target\surefire-reports\TEST-com.sdv.platform.crypto.enc.SdvEnc001StreamingCryptoTest.xml')) 'SDV-ENC-001 crypto tests executed'
        Require (Test-Path (Join-Path $Root 'backend\sdv-crypto\target\surefire-reports\TEST-com.sdv.platform.crypto.key.VaultTransitKeyProtectionProviderTest.xml')) 'Vault tenant-key naming test executed'
        Require (Test-Path (Join-Path $Root 'backend\sdv-storage\target\surefire-reports\TEST-com.sdv.platform.storage.local.LocalFilesystemStorageProviderTest.xml')) 'Local filesystem storage-provider tests executed'
        Require (Test-Path (Join-Path $Root 'backend\sdv-application\target\surefire-reports\TEST-com.sdv.platform.application.storage.StoragePlacementServiceTest.xml')) 'Storage placement/capacity tests executed'
        Require (Test-Path (Join-Path $Root 'backend\sdv-application\target\surefire-reports\TEST-com.sdv.platform.application.submission.SubmissionServiceTest.xml')) 'Submission idempotency/metadata-history tests executed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime, migration and Foundation no-regression ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Local startup failed.' }
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='003-foundation-integrity-hardening';") -eq '1') 'Frozen Foundation integrity migration remains applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='004-secure-ingestion-checkpoint-a';") -eq '1') 'Checkpoint A migration applied once'
    Require ((DbScalar "select count(*) from information_schema.tables where table_schema='public' and table_name='storage_volumes';") -eq '1') 'Tenant-scoped storage volume table exists'
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/ingestion-sources" $biz1) -eq 403) 'Frozen Foundation role boundary still denies Business Admin source administration'

    Write-Host ''
    Write-Host '=== PHASE 2 - Source setup and metadata-driven submission create ==='
    $sourceBody = @{
        sourceKey = $SourceKey
        displayName = "Secure Ingestion A $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "local://checkpoint-a/$Suffix"
        limits = @{ maxDocumentsPerSubmission = 10 }
    }
    $source = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources" -Method Post -Headers $it1 -ContentType 'application/json' -Body ($sourceBody | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $SourceId = [string]$source.sourceId
    Require (-not [string]::IsNullOrWhiteSpace($SourceId)) 'Tenant IT created disposable ingestion source'

    $field = @{
        displayLabel = 'Contract Number'
        fieldType = 'TEXT'
        required = $true
        businessIdentifier = $true
        searchable = $true
        normalization = 'DIGITS_ONLY'
        searchMode = 'EXACT'
        validationRules = @{ exactLength = 12 }
    }
    $null = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/contractNumber" -Method Put -Headers $biz1 -ContentType 'application/json' -Body ($field | ConvertTo-Json -Depth 8) -TimeoutSec 10

    $source1 = SourceHeaders $Tenant1 $SourceId 'checkpoint-a-producer'
    Require ((HttpStatus 'POST' "$Base/api/v1/ingestion/submissions" $it1 @{ sourceSubmissionId='wrong-principal'; expectedDocumentCount=1 }) -eq 401) 'User/admin principal cannot impersonate an ingestion source'

    $submissionBody = @{
        sourceSubmissionId = "producer-submission-$Suffix"
        expectedDocumentCount = 3
        expectedDocuments = @(
            @{ sourceDocumentId = "doc-a-$Suffix" },
            @{ sourceDocumentId = "doc-b-$Suffix" }
        )
        metadata = @{ contractNumber = '9375-5221-2763' }
    }
    $createdResponse = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $source1 $submissionBody
    Require ($createdResponse.StatusCode -eq 201) 'First submission create returns HTTP 201'
    $SubmissionId = [string]$createdResponse.Body.submissionId
    Require (-not [string]::IsNullOrWhiteSpace($SubmissionId)) 'SDV generated its own submissionId'
    Require ($createdResponse.Body.sourceSubmissionId -eq "producer-submission-$Suffix") 'Source submission identity retained for traceability'
    Require ([int]$createdResponse.Body.expectedDocumentCount -eq 3) 'Declared expected document count retained'
    Require (@($createdResponse.Body.expectedDocuments).Count -eq 2) 'Partial stable sourceDocumentId manifest retained independently of expected count'
    Require ($createdResponse.Body.idempotentReplay -eq $false) 'Initial create is not marked as an idempotent replay'

    $retryResponse = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $source1 $submissionBody
    Require ($retryResponse.StatusCode -eq 200) 'Exact create retry returns HTTP 200 replay rather than a duplicate'
    Require ([string]$retryResponse.Body.submissionId -eq $SubmissionId) 'Exact create retry returns the same SDV submissionId'
    Require ($retryResponse.Body.idempotentReplay -eq $true) 'Exact retry is explicitly marked as idempotent replay'

    $conflictBody = $submissionBody.Clone()
    $conflictBody.expectedDocumentCount = 4
    Require ((HttpStatus 'POST' "$Base/api/v1/ingestion/submissions" $source1 $conflictBody) -eq 409) 'Same sourceSubmissionId with changed create request fails closed as conflict'

    $queried = Invoke-RestMethod -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId" -Headers $source1 -TimeoutSec 10
    Require ([string]$queried.submissionId -eq $SubmissionId) 'Submission and expected set are queryable by SDV submissionId'
    $bySource = Invoke-RestMethod -Uri "$Base/api/v1/ingestion/submissions?sourceSubmissionId=producer-submission-$Suffix" -Headers $source1 -TimeoutSec 10
    Require ([string]$bySource.submissionId -eq $SubmissionId) 'Submission is queryable by sourceSubmissionId within authenticated source context'

    $source2Foreign = SourceHeaders $Tenant2 $SourceId 'foreign-producer'
    Require ((HttpStatus 'GET' "$Base/api/v1/ingestion/submissions/$SubmissionId" $source2Foreign) -eq 404) 'Cross-tenant source context cannot retrieve submission'
    Require ((DbScalar "select count(*) from submissions where tenant_id='$Tenant1' and source_id='$SourceId' and submission_id='$SubmissionId' and length(create_request_sha256)=64;") -eq '1') 'Submission persists strong create-request idempotency fingerprint'
    Require ((DbScalar "select normalized_value from business_metadata_values where tenant_id='$Tenant1' and submission_id='$SubmissionId' and field_key='contractNumber';") -eq '937552212763') 'Configured DIGITS_ONLY normalization drove ingestion metadata persistence'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and target_type='SUBMISSION' and target_id='$SubmissionId' and action='SUBMISSION_CREATED' and actor_type='SOURCE';") -eq '1') 'Submission create is source-attributed and append-audited once despite retry'

    Write-Host ''
    Write-Host '=== PHASE 3 - Multi-volume local storage administration and placement ==='
    $root1 = Join-Path $TestStorageRoot 'volume-1'
    $root2 = Join-Path $TestStorageRoot 'volume-2'
    $volume1Body = @{
        displayName = "Checkpoint A Volume 1 $Suffix"
        rootPath = $root1
        state = 'ACTIVE'
        priority = 20
    }
    $volume2Body = @{
        displayName = "Checkpoint A Volume 2 $Suffix"
        rootPath = $root2
        state = 'ACTIVE'
        priority = 10
    }
    $v1 = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes" -Method Post -Headers $it1 -ContentType 'application/json' -Body ($volume1Body | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $v2 = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes" -Method Post -Headers $it1 -ContentType 'application/json' -Body ($volume2Body | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $Volume1Id = [string]$v1.volume.volumeId
    $Volume2Id = [string]$v2.volume.volumeId
    Require ((Test-Path $root1) -and (Test-Path $root2)) 'Configured local volume roots are prepared and writable'
    Require ([int]$v1.volume.warningPercent -eq 90 -and [int]$v1.volume.stopWritePercent -eq 95) 'Tenant default 90% warning / 95% stop-write thresholds are applied'

    $foreignNestedRoot = Join-Path $root1 'foreign-tenant-nested-root'
    $foreignVolumeBody = @{
        displayName = "Foreign Nested Volume $Suffix"
        rootPath = $foreignNestedRoot
        state = 'ACTIVE'
        priority = 1
    }
    Require ((HttpStatus 'POST' "$Base/api/v1/admin/storage-volumes" $it2 $foreignVolumeBody) -eq 400) 'Cross-tenant overlapping filesystem root is rejected before configuration'
    Require (-not (Test-Path $foreignNestedRoot)) 'Rejected overlapping root is not created as a side effect'

    $placement1 = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes/placement-preview?requiredBytes=1" -Headers $it1 -TimeoutSec 10
    Require ([string]$placement1.volume.volumeId -eq $Volume2Id) 'Healthy ACTIVE volume with higher configured priority is selected for new writes'

    $v2Update = @{
        displayName = "Checkpoint A Volume 2 $Suffix"
        state = 'READ_ONLY'
        priority = 10
        warningPercent = 90
        stopWritePercent = 95
    }
    $null = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes/$Volume2Id" -Method Put -Headers $it1 -ContentType 'application/json' -Body ($v2Update | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $placement2 = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes/placement-preview?requiredBytes=1" -Headers $it1 -TimeoutSec 10
    Require ([string]$placement2.volume.volumeId -eq $Volume1Id) 'READ_ONLY volume is skipped for new placement while remaining configured for reads'

    $v1Update = @{
        displayName = "Checkpoint A Volume 1 $Suffix"
        state = 'OFFLINE'
        priority = 20
        warningPercent = 90
        stopWritePercent = 95
    }
    $null = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes/$Volume1Id" -Method Put -Headers $it1 -ContentType 'application/json' -Body ($v1Update | ConvertTo-Json -Depth 8) -TimeoutSec 10
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/storage-volumes/placement-preview?requiredBytes=1" $it1) -eq 409) 'No writable ACTIVE capacity fails placement safely'

    # Use the raw HTTP response here instead of Invoke-RestMethod. On Windows PowerShell 5.1,
    # an empty JSON array (`[]`) may enumerate to no pipeline objects and become $null, which
    # makes string method calls fail under StrictMode. The raw response content is always a string.
    $tenant2VolumeResponse = Invoke-WebRequest -Uri "$Base/api/v1/admin/storage-volumes" -Headers $it2 -UseBasicParsing -TimeoutSec 10
    $tenant2Text = [string]$tenant2VolumeResponse.Content
    Require (-not $tenant2Text.Contains($Volume1Id) -and -not $tenant2Text.Contains($Volume2Id)) 'Storage volume administration is tenant-isolated'
    Require ((DbScalar "select count(*) from storage_volumes where tenant_id='$Tenant1' and volume_id in ('$Volume1Id','$Volume2Id') and provider_id='LOCAL_FILESYSTEM';") -eq '2') 'Storage roots persist as tenant-owned logical volumes'

    Write-Host ''
    Write-Host '=== PHASE 4 - Checkpoint A boundaries ==='
    Require ((DbScalar "select count(*) from document_storage_refs where tenant_id='$Tenant1';") -eq '0') 'Checkpoint A does not claim document receipt before Unit 6 durable upload exists'
    Require ((DbScalar "select count(*) from document_crypto_metadata where tenant_id='$Tenant1';") -eq '0') 'Checkpoint A does not fabricate per-document crypto metadata before document intake'
    Require ((DbScalar "select count(*) from information_schema.columns where table_schema='public' and table_name='storage_volumes' and column_name='tenant_id' and is_nullable='NO';") -eq '1') 'Every storage volume is tenant-scoped at persistence boundary'

    $Passed = $true
    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Secure Ingestion Checkpoint A (Units 5, 7, 8) acceptance passed.' -ForegroundColor Green
    Write-Host "Disposable source     : $SourceId"
    Write-Host "Disposable submission : $SubmissionId"
    Write-Host "Disposable volumes    : $Volume1Id, $Volume2Id"
} finally {
    if (-not $KeepTestData) {
        try {
            if ($SourceId -or $Volume1Id -or $Volume2Id) {
                $cleanupStatements = New-Object System.Collections.Generic.List[string]
                $cleanupStatements.Add("delete from audit_events where tenant_id='$Tenant1' and correlation_id='ingest-a-$Suffix';")
                if ($SourceId) {
                    $cleanupStatements.Add("delete from business_metadata_values where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');")
                    $cleanupStatements.Add("delete from submission_expected_documents where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');")
                    $cleanupStatements.Add("delete from submissions where tenant_id='$Tenant1' and source_id='$SourceId';")
                    $cleanupStatements.Add("delete from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId';")
                    $cleanupStatements.Add("delete from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';")
                }
                if ($Volume1Id) {
                    $cleanupStatements.Add("delete from storage_volumes where tenant_id='$Tenant1' and volume_id='$Volume1Id';")
                }
                if ($Volume2Id) {
                    $cleanupStatements.Add("delete from storage_volumes where tenant_id='$Tenant1' and volume_id='$Volume2Id';")
                }
                $cleanup = [string]::Join([Environment]::NewLine, $cleanupStatements)
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c $cleanup | Out-Null
                Ok 'Disposable Checkpoint A database data removed'
            }
        } catch { Write-Warning "Database cleanup failed: $_" }
    }
    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_ }
    }
    if (-not $KeepTestData) {
        try { if (Test-Path $TestStorageRoot) { Remove-Item -Recurse -Force $TestStorageRoot } } catch { Write-Warning "Storage-root cleanup failed: $_" }
    }
}
