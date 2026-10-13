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
$StorageRoot = Join-Path $Root ".runtime\controlled-actions-b\$Suffix"
$Pdf = Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'
$DownloadBefore = Join-Path $Root ".runtime\download-before-$Suffix.pdf"
$DownloadAfter = Join-Path $Root ".runtime\download-after-$Suffix.pdf"
$Document1 = $null
$Document2 = $null
$Document3 = $null
$SubmissionIds = @()
$previousSweep = $env:SDV_RETENTION_SWEEP_DELAY_MS
$previousInitial = $env:SDV_RETENTION_INITIAL_DELAY_MS

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
        'X-Correlation-Id' = "unit17-18-$Suffix"
    }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Source-Id' = $Source
        'X-SDV-Subject' = $Subject
        'X-Correlation-Id' = "unit17-18-$Suffix"
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
function Sha256File([string]$Path) {
    return (Get-FileHash -Algorithm SHA256 -Path $Path).Hash.ToLowerInvariant()
}
function CreateDocument([string]$Label, [string]$Contract, [string]$Member) {
    $sourceHeaders = SourceHeaders $Tenant1 $SourceId 'unit17-18-producer'
    $submission = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
        sourceSubmissionId = "$Label-$Suffix"
        expectedDocumentCount = 1
        expectedDocuments = @(@{ sourceDocumentId = "$Label-doc-$Suffix" })
        metadata = @{ contractNumber = $Contract; memberName = $Member }
    }
    Require ($submission.StatusCode -eq 201) "Created $Label submission"
    $submissionId = [string]$submission.Body.submissionId
    $script:SubmissionIds += $submissionId

    $uploadHeaders = SourceHeaders $Tenant1 $SourceId 'unit17-18-producer'
    $uploadHeaders['X-SDV-Source-Document-Id'] = "$Label-doc-$Suffix"
    $uploadHeaders['X-SDV-Filename'] = "$Label.pdf"
    $upload = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$submissionId/documents" -Method Post `
        -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 60
    $body = $upload.Content | ConvertFrom-Json
    Require ([int]$upload.StatusCode -eq 201 -and $body.currentStatus -eq 'AVAILABLE') "$Label reached AVAILABLE"
    return [PSCustomObject]@{ SubmissionId=$submissionId; DocumentId=[string]$body.documentId }
}
function WaitForExpired([string]$DocumentId) {
    for ($i = 0; $i -lt 30; $i++) {
        $status = DbScalar "select status from documents where tenant_id='$Tenant1' and document_id='$DocumentId';"
        if ($status -eq 'EXPIRED_DELETED') { return $true }
        Start-Sleep -Milliseconds 500
    }
    return $false
}

try {
    if (-not $SkipBuild) {
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime, migration 008 and frozen baseline ==='
    $env:SDV_RETENTION_INITIAL_DELAY_MS = '1000'
    $env:SDV_RETENTION_SWEEP_DELAY_MS = '1000'
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='008-controlled-download-retention';") -eq '1') 'Units 17-18 migration 008 applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='007-permanent-corrective-redaction';") -eq '1') 'Frozen Unit 16 migration 007 remains applied once'
    Require ((Test-Path $Pdf)) 'Two-page PDF fixture is present'

    $it = UserHeaders $Tenant1 'unit17-18-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders $Tenant1 'unit17-18-admin' 'BUSINESS_ADMIN'
    $business = UserHeaders $Tenant1 'unit17-18-business' 'BUSINESS_USER'
    $steward = UserHeaders $Tenant1 'unit17-18-steward' 'DOCUMENT_STEWARD'
    $platform = UserHeaders $Tenant1 'unit17-18-platform' 'PLATFORM_OPERATIONS_ADMIN'
    $foreignSteward = UserHeaders $Tenant2 'unit17-18-foreign' 'DOCUMENT_STEWARD'

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey = "unit17-18-$Suffix"
        displayName = "Controlled Actions B $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "vault://local/unit17-18/$Suffix"
        limits = @{
            maxPdfBytes = 52428800
            maxPagesPerPdf = 2000
            maxDocumentsPerSubmission = 10
            maxAggregateSubmissionBytes = 104857600
            incompleteAttentionHours = 24
        }
    }
    Require ($source.StatusCode -eq 201) 'Tenant IT created disposable source'
    $SourceId = [string]$source.Body.sourceId
    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName = "Controlled Actions B Volume $Suffix"
        rootPath = $StorageRoot
        state = 'ACTIVE'
        priority = 1
    }
    Require ($volume.StatusCode -eq 201) 'Tenant IT created disposable ciphertext volume'
    $VolumeId = [string]$volume.Body.volume.volumeId

    Write-Host ''
    Write-Host '=== PHASE 2 - Unit 18 retention policy snapshot behavior ==='

    Require ((InvokeJson 'GET' "$Base/api/v1/admin/retention-policy" $business $null).StatusCode -eq 403) 'BUSINESS_USER cannot configure retention'
    Require ((InvokeJson 'GET' "$Base/api/v1/admin/retention-policy" $it $null).StatusCode -eq 403) 'TENANT_IT_ADMIN does not receive business retention configuration capability'
    Require ((InvokeJson 'GET' "$Base/api/v1/admin/retention-policy" $platform $null).StatusCode -eq 403) 'PLATFORM_OPERATIONS_ADMIN does not receive tenant retention configuration capability'

    $policy30 = InvokeJson 'PUT' "$Base/api/v1/admin/retention-policy" $admin @{ defaultRetentionDays = 30 }
    Require ($policy30.StatusCode -eq 200 -and [int]$policy30.Body.defaultRetentionDays -eq 30) 'BUSINESS_ADMIN configured 30-day future-document retention'
    $d1 = CreateDocument 'retention-a' '880-1001' 'Retention A'
    $Document1 = $d1.DocumentId
    $Snapshot1 = DbScalar "select retention_days_snapshot from documents where tenant_id='$Tenant1' and document_id='$Document1';"
    $Expires1 = DbScalar "select expires_at::text from documents where tenant_id='$Tenant1' and document_id='$Document1';"
    Require ($Snapshot1 -eq '30' -and -not [string]::IsNullOrWhiteSpace($Expires1)) 'Document A snapshotted the 30-day policy at ingestion'

    $policy60 = InvokeJson 'PUT' "$Base/api/v1/admin/retention-policy" $admin @{ defaultRetentionDays = 60 }
    Require ($policy60.StatusCode -eq 200 -and [int]$policy60.Body.defaultRetentionDays -eq 60) 'BUSINESS_ADMIN changed future-document retention to 60 days'
    Require ((DbScalar "select retention_days_snapshot from documents where tenant_id='$Tenant1' and document_id='$Document1';") -eq '30') 'Later policy change did not rewrite Document A retention snapshot'
    Require ((DbScalar "select expires_at::text from documents where tenant_id='$Tenant1' and document_id='$Document1';") -eq $Expires1) 'Later policy change did not rewrite Document A expiration'

    $d2 = CreateDocument 'retention-b' '880-1002' 'Retention B'
    $Document2 = $d2.DocumentId
    Require ((DbScalar "select retention_days_snapshot from documents where tenant_id='$Tenant1' and document_id='$Document2';") -eq '60') 'Document B snapshotted the new 60-day policy'

    $indefinite = InvokeJson 'PUT' "$Base/api/v1/admin/retention-policy" $admin @{ defaultRetentionDays = $null }
    Require ($indefinite.StatusCode -eq 200 -and $indefinite.Body.indefinite -eq $true) 'BUSINESS_ADMIN restored indefinite retention for future documents'
    $d3 = CreateDocument 'retention-c' '880-1003' 'Retention C'
    $Document3 = $d3.DocumentId
    Require ((DbScalar "select count(*) from documents where tenant_id='$Tenant1' and document_id='$Document3' and retention_days_snapshot is null and expires_at is null;") -eq '1') 'Indefinite policy produces no document expiration snapshot date'

    Write-Host ''
    Write-Host '=== PHASE 3 - Unit 17 controlled download authorization ==='

    $downloadUri = "$Base/api/v1/business/documents/$Document1/download"
    Require ((InvokeJson 'GET' $downloadUri @{} $null).StatusCode -eq 401) 'Unauthenticated download is denied'
    Require ((InvokeJson 'GET' $downloadUri $business $null).StatusCode -eq 403) 'BUSINESS_USER cannot download by default'
    Require ((InvokeJson 'GET' $downloadUri $admin $null).StatusCode -eq 403) 'BUSINESS_ADMIN cannot download by default'
    Require ((InvokeJson 'GET' $downloadUri $it $null).StatusCode -eq 403) 'TENANT_IT_ADMIN cannot download'
    Require ((InvokeJson 'GET' $downloadUri $platform $null).StatusCode -eq 403) 'PLATFORM_OPERATIONS_ADMIN cannot download'
    Require ((InvokeJson 'GET' $downloadUri $foreignSteward $null).StatusCode -eq 404) 'Cross-tenant Document Steward cannot download another tenant document'

    $download = Invoke-WebRequest -Uri $downloadUri -Headers $steward -OutFile $DownloadBefore -PassThru -UseBasicParsing -TimeoutSec 60
    Require ([int]$download.StatusCode -eq 200) 'DOCUMENT_STEWARD can download the current approved PDF'
    Require ([string]$download.Headers['Content-Type'] -match 'application/pdf') 'Controlled download response is application/pdf'
    Require ([string]$download.Headers['Content-Disposition'] -match 'attachment') 'Controlled download uses attachment content disposition'
    Require ([string]$download.Headers['Cache-Control'] -match 'no-store') 'Controlled download response is non-cacheable'
    Require ([string]$download.Headers['X-Content-Type-Options'] -eq 'nosniff') 'Controlled download disables content sniffing'
    $CurrentSha = DbScalar "select content_sha256 from documents where tenant_id='$Tenant1' and document_id='$Document1';"
    Require ((Sha256File $DownloadBefore) -eq $CurrentSha) 'Downloaded plaintext fingerprint matches current approved document fingerprint'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_DOWNLOADED' and target_id='$Document1' and outcome='SUCCESS';") -eq '1') 'Successful controlled download is append-audited'

    Write-Host ''
    Write-Host '=== PHASE 4 - Download always resolves current post-redaction content ==='

    $redactionUri = "$Base/api/v1/business/documents/$Document1/redactions"
    $redacted = InvokeJson 'POST' $redactionUri $steward @{
        reason = 'Unit 17 current-version download proof'
        confirmed = $true
        regions = @(@{ pageNumber=1; x=0.08; y=0.06; width=0.72; height=0.10 })
    }
    Require ($redacted.StatusCode -eq 200 -and $redacted.Body.status -eq 'AVAILABLE') 'Frozen Unit 16 redaction produced a new current AVAILABLE version'
    $PostRedactionSha = DbScalar "select content_sha256 from documents where tenant_id='$Tenant1' and document_id='$Document1';"
    Require ($PostRedactionSha -ne $CurrentSha) 'Corrective redaction changed the current document fingerprint'

    $download2 = Invoke-WebRequest -Uri $downloadUri -Headers $steward -OutFile $DownloadAfter -PassThru -UseBasicParsing -TimeoutSec 60
    Require ([int]$download2.StatusCode -eq 200) 'Controlled download still succeeds after corrective redaction'
    Require ((Sha256File $DownloadAfter) -eq $PostRedactionSha) 'Download returns the current corrected PDF, not the pre-correction version'
    Require ((Sha256File $DownloadAfter) -ne (Sha256File $DownloadBefore)) 'Pre-correction and corrected downloads are not the same content'

    Write-Host ''
    Write-Host '=== PHASE 5 - Unit 18 automatic expiration deletion / tombstone ==='

    $ObjectKeyBeforeExpiry = DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant1' and document_id='$Document1';"
    $ProtectedRefBeforeExpiry = DbScalar "select protected_data_key_reference from document_crypto_metadata where tenant_id='$Tenant1' and document_id='$Document1';"
    Require (-not [string]::IsNullOrWhiteSpace($ProtectedRefBeforeExpiry)) 'Protected data-key reference exists before expiration'
    Require (Test-Path (ObjectPath $ObjectKeyBeforeExpiry)) 'Current ciphertext exists before expiration'

    # Test-only clock acceleration: production deletion is scheduler-driven; no manual document-delete API exists.
    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
        update documents
           set expires_at = current_timestamp - interval '1 minute'
         where tenant_id='$Tenant1' and document_id='$Document1';
    " *> $null

    Require (WaitForExpired $Document1) 'Automatic retention scheduler transitioned expired document to EXPIRED_DELETED'
    Require (-not (Test-Path (ObjectPath $ObjectKeyBeforeExpiry))) 'Expired protected ciphertext was removed automatically'
    Require ((DbScalar "select count(*) from document_storage_refs where tenant_id='$Tenant1' and document_id='$Document1';") -eq '0') 'Expired storage reference was removed'
    Require ((DbScalar "select count(*) from document_crypto_metadata where tenant_id='$Tenant1' and document_id='$Document1';") -eq '0') 'Expired protected key reference/crypto metadata was removed'
    Require ((DbScalar "select count(*) from documents where tenant_id='$Tenant1' and document_id='$Document1' and status='EXPIRED_DELETED' and deleted_at is not null and deletion_reason='RETENTION_EXPIRED';") -eq '1') 'Document tombstone retains deletion time and reason'
    Require ((DbScalar "select retention_days_snapshot from documents where tenant_id='$Tenant1' and document_id='$Document1';") -eq '30') 'Expiration did not erase the original retention snapshot'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_RETENTION_DELETED' and target_id='$Document1' and outcome='SUCCESS';") -eq '1') 'Automatic retention deletion is append-audited'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_RETENTION_DELETED' and target_id='$Document1' and safe_details->>'deletionReason'='RETENTION_EXPIRED';") -eq '1') 'Retention audit preserves deletion reason'

    Require ((InvokeJson 'GET' "$Base/api/v1/business/documents/$Document1/viewer" $steward $null).StatusCode -eq 409) 'Expired document can no longer be viewed'
    Require ((InvokeJson 'GET' "$Base/api/v1/business/documents/$Document1/download" $steward $null).StatusCode -eq 409) 'Expired document can no longer be downloaded'
    Require ((DbScalar "select status from documents where tenant_id='$Tenant1' and document_id='$Document2';") -eq 'AVAILABLE') 'Non-expired finite-retention document remains AVAILABLE'
    Require ((DbScalar "select status from documents where tenant_id='$Tenant1' and document_id='$Document3';") -eq 'AVAILABLE') 'Indefinite-retention document remains AVAILABLE'

    Write-Host ''
    Write-Host '=== PHASE 6 - Business view + UI boundaries ==='

    $search = InvokeJson 'POST' "$Base/api/v1/business/search" $steward @{
        sourceId = $SourceId
        criteria = @{ contractNumber = '8801001' }
        limit = 10
    }
    Require ($search.StatusCode -eq 200 -and $search.Body.records.Count -eq 1) 'Expired record metadata remains searchable as historical business metadata'
    Require ([int]$search.Body.records[0].availableDocumentCount -eq 0) 'Expired document is no longer exposed as AVAILABLE business content'

    $ui = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 10
    Require ($ui.StatusCode -eq 200) 'Frontend is reachable on port 5183'
    $appSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\App.tsx') -Raw
    $viewerSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\PdfViewer.tsx') -Raw
    $apiSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\api.ts') -Raw
    $webSource = Get-ChildItem (Join-Path $Root 'backend\sdv-web\src\main\java') -Recurse -Filter *.java | ForEach-Object { Get-Content $_.FullName -Raw } | Out-String

    Require ($appSource -match "capabilities\.includes\('DOWNLOAD_CURRENT_PDF'\)") 'Frontend gates controlled download on DOWNLOAD_CURRENT_PDF capability'
    Require ($viewerSource -match 'Download current PDF') 'Document Steward viewer exposes explicit controlled download action'
    Require ($appSource -match 'Retention policy' -and $appSource -match 'defaultRetentionDays') 'Business Admin UI exposes future-document retention policy configuration'
    Require ($apiSource -match "if \(!import\.meta\.env\.DEV\) return \{\}") 'Production frontend still does not inject local auth headers'
    Require ($webSource -notmatch '@DeleteMapping\([^\)]*documents') 'V1 exposes no manual user/admin document-delete endpoint'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Controlled Document Actions Checkpoint B (Units 17-18) acceptance passed.' -ForegroundColor Green

} finally {
    Remove-Item -Force $DownloadBefore, $DownloadAfter -ErrorAction SilentlyContinue

    if (-not $KeepTestData) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from document_redaction_operations where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from audit_events where tenant_id='$Tenant1' and (correlation_id='unit17-18-$Suffix' or target_id in (select document_id::text from documents where tenant_id='$Tenant1' and source_id='$SourceId'));
                    delete from submission_expected_documents where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_crypto_metadata where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_storage_refs where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from documents where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from business_metadata_values where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from submissions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';
                    update tenants set default_retention_days=null where tenant_id='$Tenant1';
                " *> $null
                if (-not [string]::IsNullOrWhiteSpace([string]$VolumeId)) {
                    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                        delete from storage_volumes where tenant_id='$Tenant1' and volume_id='$VolumeId';
                    " *> $null
                }
                Ok 'Disposable Units 17-18 acceptance data removed'
            }
        } catch {
            Write-Warning "Units 17-18 cleanup failed: $($_.Exception.Message)"
        }
        Remove-Item -Recurse -Force $StorageRoot -ErrorAction SilentlyContinue
    }

    $env:SDV_RETENTION_SWEEP_DELAY_MS = $previousSweep
    $env:SDV_RETENTION_INITIAL_DELAY_MS = $previousInitial

    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
    }
}
