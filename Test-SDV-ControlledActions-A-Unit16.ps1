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
$SubmissionId = $null
$DocumentId = $null
$StorageRoot = Join-Path $Root ".runtime\controlled-actions-a\$Suffix"
$Pdf = Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'
$RenderedPng = Join-Path $Root ".runtime\redacted-page-$Suffix.png"

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
        'X-Correlation-Id' = "unit16-$Suffix"
    }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Source-Id' = $Source
        'X-SDV-Subject' = $Subject
        'X-Correlation-Id' = "unit16-$Suffix"
    }
}
function DbScalar([string]$Sql) {
    return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim()
}
function InvokeJson([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body) {
    try {
        $bodyText = if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 30 }
        $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' `
            -Body $bodyText -UseBasicParsing -TimeoutSec 90
        $parsed = if ([string]::IsNullOrWhiteSpace($r.Content)) { $null } else { $r.Content | ConvertFrom-Json }
        return [PSCustomObject]@{ StatusCode=[int]$r.StatusCode; Body=$parsed; Headers=$r.Headers }
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $parsed = if ([string]::IsNullOrWhiteSpace($content)) { $null } else { $content | ConvertFrom-Json }
            return [PSCustomObject]@{ StatusCode=[int]$_.Exception.Response.StatusCode; Body=$parsed; Headers=$_.Exception.Response.Headers }
        }
        throw
    }
}
function SaveField([hashtable]$Headers, [string]$FieldKey, [string]$Label, [bool]$Identifier) {
    $response = InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel = $Label
        fieldType = 'TEXT'
        required = $true
        businessIdentifier = $Identifier
        searchable = $true
        normalization = if ($Identifier) { 'DIGITS_ONLY' } else { 'LOWERCASE' }
        searchMode = if ($Identifier) { 'EXACT' } else { 'PARTIAL' }
        validationRules = @{}
    }
    Require ($response.StatusCode -eq 200) "Configured metadata field $FieldKey"
}
function ObjectPath([string]$ObjectKey) {
    return Join-Path $StorageRoot ($ObjectKey -replace '/', [System.IO.Path]::DirectorySeparatorChar)
}

try {
    if (-not $SkipBuild) {
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime, migration 007 and frozen baseline ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='007-permanent-corrective-redaction';") -eq '1') 'Unit 16 migration 007 applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='006-business-experience-checkpoint-a';") -eq '1') 'Frozen migration 006 remains applied once'
    Require ((Test-Path $Pdf)) 'Two-page PDF fixture is present'

    $it = UserHeaders $Tenant1 'unit16-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders $Tenant1 'unit16-admin' 'BUSINESS_ADMIN'
    $business = UserHeaders $Tenant1 'unit16-business' 'BUSINESS_USER'
    $steward = UserHeaders $Tenant1 'unit16-steward' 'DOCUMENT_STEWARD'
    $platform = UserHeaders $Tenant1 'unit16-platform' 'PLATFORM_OPERATIONS_ADMIN'
    $foreignSteward = UserHeaders $Tenant2 'unit16-foreign-steward' 'DOCUMENT_STEWARD'

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey = "unit16-$Suffix"
        displayName = "Unit 16 Redaction Source $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "vault://local/unit16/$Suffix"
        limits = @{
            maxPdfBytes = 52428800
            maxPagesPerPdf = 2000
            maxDocumentsPerSubmission = 10
            maxAggregateSubmissionBytes = 104857600
            incompleteAttentionHours = 24
        }
    }
    Require ($source.StatusCode -eq 201) 'Tenant IT created disposable Unit 16 source'
    $SourceId = [string]$source.Body.sourceId

    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName = "Unit 16 Volume $Suffix"
        rootPath = $StorageRoot
        state = 'ACTIVE'
        priority = 1
    }
    Require ($volume.StatusCode -eq 201) 'Tenant IT created disposable ciphertext volume'
    $VolumeId = [string]$volume.Body.volume.volumeId

    $sourceHeaders = SourceHeaders $Tenant1 $SourceId 'unit16-producer'
    $submission = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
        sourceSubmissionId = "unit16-$Suffix"
        expectedDocumentCount = 1
        expectedDocuments = @(@{ sourceDocumentId = "unit16-doc-$Suffix" })
        metadata = @{
            contractNumber = '777-1200'
            memberName = 'Corrective Redaction Acceptance'
        }
    }
    Require ($submission.StatusCode -eq 201) 'Created Unit 16 submission'
    $SubmissionId = [string]$submission.Body.submissionId

    $uploadHeaders = SourceHeaders $Tenant1 $SourceId 'unit16-producer'
    $uploadHeaders['X-SDV-Source-Document-Id'] = "unit16-doc-$Suffix"
    $uploadHeaders['X-SDV-Filename'] = 'business-viewer-two-page.pdf'
    $upload = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" -Method Post `
        -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 30
    $uploadBody = $upload.Content | ConvertFrom-Json
    Require ([int]$upload.StatusCode -eq 201 -and $uploadBody.currentStatus -eq 'AVAILABLE') 'Frozen ingestion produced an AVAILABLE document'
    $DocumentId = [string]$uploadBody.documentId

    $OldObjectKey = DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $OldProtectedKey = DbScalar "select protected_data_key_reference from document_crypto_metadata where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $OldContentSha = DbScalar "select content_sha256 from documents where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $OldExpiresAt = DbScalar "select coalesce(expires_at::text,'') from documents where tenant_id='$Tenant1' and document_id='$DocumentId';"
    Require (Test-Path (ObjectPath $OldObjectKey)) 'Original encrypted object exists before redaction'

    Write-Host ''
    Write-Host '=== PHASE 2 - Unit 16 authorization / confirmation boundaries ==='

    $redactionUri = "$Base/api/v1/business/documents/$DocumentId/redactions"
    $request = @{
        reason = 'Missed sensitive region correction'
        confirmed = $true
        regions = @(
            @{ pageNumber=1; x=0.08; y=0.06; width=0.72; height=0.10 },
            @{ pageNumber=2; x=0.08; y=0.06; width=0.72; height=0.10 }
        )
    }

    Require ((InvokeJson 'POST' $redactionUri @{} $request).StatusCode -eq 401) 'Unauthenticated corrective redaction is denied'
    Require ((InvokeJson 'POST' $redactionUri $business $request).StatusCode -eq 403) 'BUSINESS_USER cannot permanently redact'
    Require ((InvokeJson 'POST' $redactionUri $admin $request).StatusCode -eq 403) 'BUSINESS_ADMIN cannot permanently redact by default'
    Require ((InvokeJson 'POST' $redactionUri $it $request).StatusCode -eq 403) 'TENANT_IT_ADMIN cannot permanently redact'
    Require ((InvokeJson 'POST' $redactionUri $platform $request).StatusCode -eq 403) 'PLATFORM_OPERATIONS_ADMIN cannot permanently redact'
    Require ((InvokeJson 'POST' $redactionUri $foreignSteward $request).StatusCode -eq 404) 'Cross-tenant Document Steward cannot redact another tenant document'

    $notConfirmed = $request.Clone()
    $notConfirmed.confirmed = $false
    Require ((InvokeJson 'POST' $redactionUri $steward $notConfirmed).StatusCode -eq 400) 'Server requires explicit permanent-redaction confirmation'

    $noReason = $request.Clone()
    $noReason.reason = ' '
    Require ((InvokeJson 'POST' $redactionUri $steward $noReason).StatusCode -eq 400) 'Server requires mandatory redaction reason'

    $badRegion = @{
        reason = 'Invalid region should fail'
        confirmed = $true
        regions = @(@{ pageNumber=1; x=0.95; y=0.95; width=0.20; height=0.20 })
    }
    Require ((InvokeJson 'POST' $redactionUri $steward $badRegion).StatusCode -eq 400) 'Server rejects out-of-bounds redaction rectangle'

    Write-Host ''
    Write-Host '=== PHASE 3 - Permanent sanitization and atomic encrypted replacement ==='

    $redacted = InvokeJson 'POST' $redactionUri $steward $request
    Require ($redacted.StatusCode -eq 200) 'DOCUMENT_STEWARD completed permanent corrective redaction'
    Require ($redacted.Body.status -eq 'AVAILABLE') 'Document returned to AVAILABLE only after replacement completed'
    Require ([int]$redacted.Body.regionCount -eq 2) 'Multiple redaction regions across multiple pages were accepted'

    $NewObjectKey = DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $NewProtectedKey = DbScalar "select protected_data_key_reference from document_crypto_metadata where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $NewContentSha = DbScalar "select content_sha256 from documents where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $NewExpiresAt = DbScalar "select coalesce(expires_at::text,'') from documents where tenant_id='$Tenant1' and document_id='$DocumentId';"

    Require ($NewObjectKey -ne $OldObjectKey) 'Corrective redaction switched to a newly encrypted storage object'
    Require ($NewProtectedKey -ne $OldProtectedKey) 'Corrective redaction generated a new per-document data-key reference'
    Require ($NewContentSha -ne $OldContentSha) 'Current document fingerprint changed after sanitization'
    Require ($NewExpiresAt -eq $OldExpiresAt) 'Corrective redaction preserved original retention expiration exactly'
    Require (-not (Test-Path (ObjectPath $OldObjectKey))) 'Prior pre-correction ciphertext was removed before success'
    Require (Test-Path (ObjectPath $NewObjectKey)) 'New corrected ciphertext exists after successful switch'
    Require ((DbScalar "select count(*) from document_redaction_operations where tenant_id='$Tenant1' and document_id='$DocumentId';") -eq '0') 'No recoverable pending pre-correction operation remains after success'

    $newCipherBytes = [System.IO.File]::ReadAllBytes((ObjectPath $NewObjectKey))
    $newCipherAscii = [System.Text.Encoding]::ASCII.GetString($newCipherBytes)
    Require ($newCipherAscii -notmatch 'Secure Viewer Alpha 7391' -and $newCipherAscii -notmatch 'Omega 8842') 'Corrected final storage contains ciphertext only'

    Write-Host ''
    Write-Host '=== PHASE 4 - Corrected viewer result and sanitization evidence ==='

    $viewerUri = "$Base/api/v1/business/documents/$DocumentId/viewer"
    $metadata = InvokeJson 'GET' $viewerUri $steward $null
    Require ($metadata.StatusCode -eq 200 -and [int]$metadata.Body.pageCount -eq 2) 'Corrected document remains viewable with both pages'

    $secretQuery = [uri]::EscapeDataString('Secure Viewer Alpha 7391')
    $search = InvokeJson 'GET' "$viewerUri/search?q=$secretQuery" $steward $null
    Require ($search.StatusCode -eq 200 -and [int]$search.Body.totalMatches -eq 0) 'Removed source text is not recoverable through corrected PDF text search'

    Invoke-WebRequest -Uri "$viewerUri/pages/1?scale=1.0" -Headers $steward -OutFile $RenderedPng -UseBasicParsing -TimeoutSec 30
    Add-Type -AssemblyName System.Drawing
    $bitmap = New-Object System.Drawing.Bitmap($RenderedPng)
    try {
        $x = [int]($bitmap.Width * 0.40)
        $y = [int]($bitmap.Height * 0.10)
        $pixel = $bitmap.GetPixel($x, $y)
        Require ($pixel.R -lt 25 -and $pixel.G -lt 25 -and $pixel.B -lt 25) 'Redacted rectangle renders as permanently black sanitized pixels'
    } finally {
        $bitmap.Dispose()
    }

    Write-Host ''
    Write-Host '=== PHASE 5 - Audit privacy / integrity evidence ==='

    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_PERMANENTLY_REDACTED' and target_id='$DocumentId' and outcome='SUCCESS';") -eq '1') 'Successful permanent redaction is append-audited once'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_PERMANENTLY_REDACTED' and target_id='$DocumentId' and safe_details->>'reason'='Missed sensitive region correction';") -eq '1') 'Redaction audit preserves mandatory reason'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_PERMANENTLY_REDACTED' and target_id='$DocumentId' and safe_details->>'oldContentSha256'='$OldContentSha' and safe_details->>'newContentSha256'='$NewContentSha';") -eq '1') 'Redaction audit preserves old/new integrity evidence'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and target_id='$DocumentId' and safe_details::text ilike '%Secure Viewer Alpha 7391%';") -eq '0') 'Redaction audit never stores removed PDF text'

    Write-Host ''
    Write-Host '=== PHASE 6 - Frontend corrective-redaction controls ==='

    $ui = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 10
    Require ($ui.StatusCode -eq 200) 'Frontend is reachable on port 5183'
    $appSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\App.tsx') -Raw
    $viewerSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\PdfViewer.tsx') -Raw
    $apiSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\api.ts') -Raw

    Require ($appSource -match "capabilities\.includes\('PERMANENT_REDACTION'\)") 'Frontend gates corrective redaction on PERMANENT_REDACTION'
    Require ($viewerSource -match 'Corrective redaction' -and $viewerSource -match 'Confirm permanent redaction') 'Viewer provides explicit corrective-redaction workflow'
    Require ($viewerSource -match 'Mandatory reason' -and $viewerSource -match 'confirmed') 'Viewer requires reason and explicit confirmation'
    Require ($viewerSource -match 'onPointerDown' -and $viewerSource -match 'redactionMarks') 'Viewer supports manual rectangular region selection'
    Require ($apiSource -match 'DOCUMENT_STEWARD') 'Local development can explicitly simulate Document Steward without changing production authentication'
    Require ($viewerSource -notmatch 'Download PDF') 'Unit 16 does not prematurely implement Unit 17 download'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Controlled Document Actions Checkpoint A / Unit 16 acceptance passed.' -ForegroundColor Green

} finally {
    Remove-Item -Force $RenderedPng -ErrorAction SilentlyContinue

    if (-not $KeepTestData) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from document_redaction_operations where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from audit_events where tenant_id='$Tenant1' and correlation_id='unit16-$Suffix';
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
                Ok 'Disposable Unit 16 acceptance data removed'
            }
        } catch {
            Write-Warning "Unit 16 cleanup failed: $($_.Exception.Message)"
        }
        Remove-Item -Recurse -Force $StorageRoot -ErrorAction SilentlyContinue
    }

    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
    }
}
