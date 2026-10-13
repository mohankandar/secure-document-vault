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
$StorageRoot = Join-Path $Root ".runtime\business-experience-b\$Suffix"
$Pdf = Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'

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
        'X-Correlation-Id' = "business-b-$Suffix"
    }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Source-Id' = $Source
        'X-SDV-Subject' = $Subject
        'X-Correlation-Id' = "business-b-$Suffix"
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
        return [PSCustomObject]@{ StatusCode=[int]$r.StatusCode; Body=$parsed; Headers=$r.Headers; RawLength=[int]$r.RawContentLength }
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $parsed = if ([string]::IsNullOrWhiteSpace($content)) { $null } else { $content | ConvertFrom-Json }
            return [PSCustomObject]@{ StatusCode=[int]$_.Exception.Response.StatusCode; Body=$parsed; Headers=$_.Exception.Response.Headers; RawLength=0 }
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
function CapabilitySet([object]$Session) {
    return @($Session.capabilities | Sort-Object)
}
function RequireExactCapabilities([string]$Roles, [string[]]$Expected, [string]$Message) {
    $sessionResponse = InvokeJson 'GET' "$Base/api/v1/session" (UserHeaders $Tenant1 "role-$Roles" $Roles) $null
    Require ($sessionResponse.StatusCode -eq 200) "$Message session endpoint is authorized"
    $actual = @($sessionResponse.Body.capabilities | Sort-Object)
    $expectedSorted = @($Expected | Sort-Object)
    Require (($actual -join '|') -eq ($expectedSorted -join '|')) "$Message capability set is exact"
    return $sessionResponse.Body
}

try {
    if (-not $SkipBuild) {
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime and frozen-baseline no-regression ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='006-business-experience-checkpoint-a';") -eq '1') 'Frozen Business Experience Checkpoint A migration 006 remains applied once'
    Require ((Test-Path $Pdf)) 'Two-page viewer acceptance PDF fixture is present'

    $it = UserHeaders $Tenant1 'business-b-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders $Tenant1 'business-b-admin' 'BUSINESS_ADMIN'
    $business = UserHeaders $Tenant1 'business-b-user' 'BUSINESS_USER'
    $steward = UserHeaders $Tenant1 'business-b-steward' 'DOCUMENT_STEWARD'
    $platform = UserHeaders $Tenant1 'business-b-platform' 'PLATFORM_OPERATIONS_ADMIN'
    $multi = UserHeaders $Tenant1 'business-b-multi' 'BUSINESS_ADMIN,BUSINESS_USER'
    $foreignBusiness = UserHeaders $Tenant2 'foreign-business' 'BUSINESS_USER'

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey = "business-b-$Suffix"
        displayName = "Secure Viewer Source $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "vault://local/business-b/$Suffix"
        limits = @{
            maxPdfBytes = 10485760
            maxPagesPerPdf = 2000
            maxDocumentsPerSubmission = 10
            maxAggregateSubmissionBytes = 20971520
            incompleteAttentionHours = 24
        }
    }
    Require ($source.StatusCode -eq 201) 'Tenant IT created disposable viewer source'
    $SourceId = [string]$source.Body.sourceId

    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName = "Secure Viewer Volume $Suffix"
        rootPath = $StorageRoot
        state = 'ACTIVE'
        priority = 1
    }
    Require ($volume.StatusCode -eq 201) 'Tenant IT created disposable viewer ciphertext volume'
    $VolumeId = [string]$volume.Body.volume.volumeId

    $sourceHeaders = SourceHeaders $Tenant1 $SourceId 'business-b-producer'
    $submission = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
        sourceSubmissionId = "viewer-$Suffix"
        expectedDocumentCount = 1
        expectedDocuments = @(@{ sourceDocumentId = "viewer-doc-$Suffix" })
        metadata = @{
            contractNumber = '555-0123'
            memberName = 'Viewer Acceptance'
        }
    }
    Require ($submission.StatusCode -eq 201) 'Created disposable viewer business record submission'
    $SubmissionId = [string]$submission.Body.submissionId

    $uploadHeaders = SourceHeaders $Tenant1 $SourceId 'business-b-producer'
    $uploadHeaders['X-SDV-Source-Document-Id'] = "viewer-doc-$Suffix"
    $uploadHeaders['X-SDV-Filename'] = 'business-viewer-two-page.pdf'
    $upload = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" -Method Post `
        -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 30
    $uploadBody = $upload.Content | ConvertFrom-Json
    Require ([int]$upload.StatusCode -eq 201) 'Frozen durable-ingestion flow accepted valid two-page PDF'
    Require ($uploadBody.currentStatus -eq 'AVAILABLE') 'Viewer fixture reached AVAILABLE before secure viewing'
    $DocumentId = [string]$uploadBody.documentId

    Write-Host ''
    Write-Host '=== PHASE 2 - Unit 15 exact V1 role/capability matrix ==='

    [void](RequireExactCapabilities 'BUSINESS_USER' @(
        'SEARCH_RECORDS','VIEW_PDF'
    ) 'BUSINESS_USER')

    [void](RequireExactCapabilities 'DOCUMENT_STEWARD' @(
        'SEARCH_RECORDS','VIEW_PDF','PERMANENT_REDACTION','DOWNLOAD_CURRENT_PDF'
    ) 'DOCUMENT_STEWARD')

    [void](RequireExactCapabilities 'BUSINESS_ADMIN' @(
        'CONFIGURE_METADATA_SEARCH','CONFIGURE_RETENTION','CONFIGURE_BUSINESS_LIMITS'
    ) 'BUSINESS_ADMIN')

    [void](RequireExactCapabilities 'TENANT_IT_ADMIN' @(
        'CONFIGURE_INGESTION_SOURCES','CONFIGURE_TENANT_AUTH',
        'CONFIGURE_STORAGE_KEY_PROVIDER','VIEW_TECHNICAL_FAILURES'
    ) 'TENANT_IT_ADMIN')

    [void](RequireExactCapabilities 'PLATFORM_OPERATIONS_ADMIN' @(
        'PLATFORM_CONFIGURATION','VIEW_TECHNICAL_FAILURES'
    ) 'PLATFORM_OPERATIONS_ADMIN')

    $multiSession = RequireExactCapabilities 'BUSINESS_ADMIN,BUSINESS_USER' @(
        'SEARCH_RECORDS','VIEW_PDF',
        'CONFIGURE_METADATA_SEARCH','CONFIGURE_RETENTION','CONFIGURE_BUSINESS_LIMITS'
    ) 'Explicit BUSINESS_ADMIN + BUSINESS_USER multi-role'
    Require (@($multiSession.roles).Count -eq 2) 'Multi-role session preserves both explicitly assigned roles'

    Write-Host ''
    Write-Host '=== PHASE 3 - Unit 14 viewer authorization and metadata ==='

    $viewerUri = "$Base/api/v1/business/documents/$DocumentId/viewer"

    $unauth = InvokeJson 'GET' $viewerUri @{} $null
    Require ($unauth.StatusCode -eq 401) 'Secure viewer denies unauthenticated access'

    $businessMetadata = InvokeJson 'GET' $viewerUri $business $null
    Require ($businessMetadata.StatusCode -eq 200) 'BUSINESS_USER can open secure viewer'
    Require ([int]$businessMetadata.Body.pageCount -eq 2) 'Viewer reports two-page PDF metadata'
    Require ($businessMetadata.Body.originalFilename -eq 'business-viewer-two-page.pdf') 'Viewer returns safe document display filename'
    Require ([string]$businessMetadata.Headers['Cache-Control'] -match 'no-store') 'Viewer metadata uses Cache-Control no-store'

    $stewardMetadata = InvokeJson 'GET' $viewerUri $steward $null
    Require ($stewardMetadata.StatusCode -eq 200) 'DOCUMENT_STEWARD can open secure viewer'

    $multiMetadata = InvokeJson 'GET' $viewerUri $multi $null
    Require ($multiMetadata.StatusCode -eq 200) 'Explicit admin + Business User multi-role gains viewer only through content role'

    Require ((InvokeJson 'GET' $viewerUri $admin $null).StatusCode -eq 403) 'BUSINESS_ADMIN alone cannot view PDFs'
    Require ((InvokeJson 'GET' $viewerUri $it $null).StatusCode -eq 403) 'TENANT_IT_ADMIN alone cannot view PDFs'
    Require ((InvokeJson 'GET' $viewerUri $platform $null).StatusCode -eq 403) 'PLATFORM_OPERATIONS_ADMIN alone cannot view PDFs'
    Require ((InvokeJson 'GET' $viewerUri $foreignBusiness $null).StatusCode -eq 404) 'Cross-tenant Business User cannot view another tenant document'

    Write-Host ''
    Write-Host '=== PHASE 4 - Page rendering, navigation/zoom contract and cache protection ==='

    $page1 = Invoke-WebRequest -Uri "$viewerUri/pages/1?scale=1.00" -Headers $business -UseBasicParsing -TimeoutSec 30
    Require ([int]$page1.StatusCode -eq 200) 'Viewer renders page 1 through authorized SDV flow'
    Require ([string]$page1.Headers['Content-Type'] -match 'image/png') 'Viewer page response is PNG, not a raw PDF download'
    Require ($page1.RawContentLength -gt 1000) 'Rendered page 1 contains non-empty image content'
    Require ([string]$page1.Headers['Cache-Control'] -match 'no-store') 'Rendered page response is non-cacheable'
    Require ([string]$page1.Headers['Pragma'] -match 'no-cache') 'Rendered page response carries legacy no-cache protection'
    Require ([string]$page1.Headers['X-Content-Type-Options'] -eq 'nosniff') 'Rendered page response disables content sniffing'

    $page2 = Invoke-WebRequest -Uri "$viewerUri/pages/2?scale=1.50" -Headers $business -UseBasicParsing -TimeoutSec 30
    Require ([int]$page2.StatusCode -eq 200 -and $page2.RawContentLength -gt 1000) 'Viewer renders page 2 at a different authorized zoom scale'
    Require ((InvokeJson 'GET' "$viewerUri/pages/3?scale=1.0" $business $null).StatusCode -eq 400) 'Viewer rejects page number beyond document bounds'
    Require ((InvokeJson 'GET' "$viewerUri/pages/1?scale=4.0" $business $null).StatusCode -eq 400) 'Viewer rejects unsafe zoom scale'

    Write-Host ''
    Write-Host '=== PHASE 5 - Viewer text search without audit leakage ==='

    $q = [uri]::EscapeDataString('Omega 8842')
    $search = InvokeJson 'GET' "$viewerUri/search?q=$q" $business $null
    Require ($search.StatusCode -eq 200) 'Authorized viewer text search succeeds'
    Require ([int]$search.Body.totalMatches -ge 1) 'Viewer text search finds known searchable text'
    $matchingPages = @($search.Body.pages | ForEach-Object { [int]$_.pageNumber })
    Require ($matchingPages -contains 2) 'Viewer text search locates known phrase on page 2'

    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_VIEW_OPENED' and target_id='$DocumentId';") -ge '1') 'Viewer open is append-audited'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_PAGE_VIEWED' and target_id='$DocumentId';") -ge '2') 'Page views are append-audited'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='DOCUMENT_TEXT_SEARCHED' and target_id='$DocumentId';") -ge '1') 'Viewer text search is append-audited'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and safe_details::text ilike '%Omega%' and target_id='$DocumentId';") -eq '0') 'Viewer audit does not persist search query text'

    Write-Host ''
    Write-Host '=== PHASE 6 - AVAILABLE-only and plaintext-storage boundary ==='

    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
        update documents set status='PROCESSING_FAILED', processing_error_code='VIEWER_ACCEPTANCE' where document_id='$DocumentId';
    " *> $null
    Require ((InvokeJson 'GET' $viewerUri $business $null).StatusCode -eq 409) 'Secure viewer refuses document that is not AVAILABLE'
    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
        update documents set status='AVAILABLE', processing_error_code=null where document_id='$DocumentId';
    " *> $null

    $objectKey = DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant1' and document_id='$DocumentId';"
    $objectPath = Join-Path $StorageRoot ($objectKey -replace '/', [System.IO.Path]::DirectorySeparatorChar)
    Require (Test-Path $objectPath) 'Encrypted source object remains in configured storage after viewing'
    $cipherText = [System.Text.Encoding]::ASCII.GetString([System.IO.File]::ReadAllBytes($objectPath))
    Require ($cipherText -notmatch 'Secure Viewer Alpha 7391' -and $cipherText -notmatch 'Omega 8842') 'Configured final storage still contains no readable viewer fixture text'

    $logText = ''
    $backendOut = Join-Path $Root '.runtime\logs\backend-local.out.log'
    $backendErr = Join-Path $Root '.runtime\logs\backend-local.err.log'
    if (Test-Path $backendOut) { $logText += (Get-Content $backendOut -Raw) }
    if (Test-Path $backendErr) { $logText += (Get-Content $backendErr -Raw) }
    Require ($logText -notmatch 'Omega 8842' -and $logText -notmatch 'Secure Viewer Alpha 7391') 'Viewer does not log PDF text or viewer search query'

    Write-Host ''
    Write-Host '=== PHASE 7 - Unit 15 frontend permission enforcement ==='

    $ui = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 10
    Require ($ui.StatusCode -eq 200) 'Business Experience frontend is reachable on port 5183'
    $appSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\App.tsx') -Raw
    $viewerSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\PdfViewer.tsx') -Raw
    $apiSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\api.ts') -Raw

    Require ($appSource -match "capabilities\.includes\('VIEW_PDF'\)") 'Frontend gates View PDF action on VIEW_PDF capability'
    Require ($appSource -match "capabilities\.includes\('SEARCH_RECORDS'\)") 'Frontend gates business search on SEARCH_RECORDS capability'
    Require ($viewerSource -match 'Previous' -and $viewerSource -match 'Next' -and $viewerSource -match 'Search document') 'Viewer UI contains navigation and text-search controls'
    Require ($viewerSource -match 'zoom' -and $viewerSource -match 'Page number') 'Viewer UI contains zoom and page-jump controls'
    Require ($viewerSource -notmatch 'Download PDF' -and $viewerSource -notmatch 'download=') 'Viewer UI does not expose a PDF download action'
    Require ($apiSource -match "if \(!import\.meta\.env\.DEV\) return \{\}") 'Production frontend does not inject local authentication headers'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Business Experience Checkpoint B (Units 14-15) acceptance passed.' -ForegroundColor Green

} finally {
    if (-not $KeepTestData) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from audit_events where tenant_id='$Tenant1' and correlation_id='business-b-$Suffix';
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
                Ok 'Disposable Business Experience Checkpoint B data removed'
            }
        } catch {
            Write-Warning "Business Experience Checkpoint B cleanup failed: $($_.Exception.Message)"
        }
        Remove-Item -Recurse -Force $StorageRoot -ErrorAction SilentlyContinue
    }

    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
    }
}
