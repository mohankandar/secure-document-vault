[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$AllowExistingSdvContainers,
    [string]$ExpectedSourceArchiveSha256 = '',
    [string]$EvidenceDirectory = ''
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = (Resolve-Path $PSScriptRoot).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
$Base = 'http://localhost:8280'
$Tenant = '11111111-1111-1111-1111-111111111111'
$Suffix = ([guid]::NewGuid().ToString('N')).Substring(0,10)
$CorrelationId = "ga-clean-$Suffix"
$DbVolume = "sdv-ga-$Suffix-postgres"
$Work = Join-Path $Root ".runtime\ga-clean-install-work\$Suffix"
if ([string]::IsNullOrWhiteSpace($EvidenceDirectory)) {
    $EvidenceDirectory = Join-Path $Root ".runtime\ga-clean-install-evidence\$Suffix"
} else {
    $EvidenceDirectory = [IO.Path]::GetFullPath($EvidenceDirectory)
}
$StorageRoot = Join-Path $Work 'storage'
$Pdf = Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'
$Producer = Join-Path $Root 'samples\third-party-producer\Invoke-SDV-Producer.ps1'
$MetadataFile = Join-Path $Work 'metadata.json'
$ProducerResult = Join-Path $Work 'producer-result.json'
$BaselineMarker = Join-Path $Root 'docs\release\SDV_V1_GA_BASELINE.json'
$LockFile = Join-Path $Root 'frontend\sdv-ui\package-lock.json'
$SourceId = $null
$VolumeId = $null
$DocumentId = $null
$SubmissionId = $null
$CiphertextShaBefore = $null
$PageBytesBefore = 0
$PageBytesAfter = 0
$BuildPerformed = $false
$RestartPassed = $false
$PreExistingContainers = @()
$OriginalDbVolumeEnv = $env:SDV_POSTGRES_VOLUME

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Info([string]$Message) { Write-Host "  [INFO] $Message" -ForegroundColor Cyan }
function Require([bool]$Condition,[string]$Message) { if(-not $Condition){ throw $Message }; Ok $Message }

function Get-SdvContainers {
    $raw = (& docker ps -a --format '{{.Names}}' 2>$null | Out-String)
    if ($LASTEXITCODE -ne 0) { throw 'Docker engine is not reachable.' }
    return @($raw -split "`r?`n" | ForEach-Object { $_.Trim() } | Where-Object { $_ -like 'sdv-*' })
}

function UserHeaders([string]$Subject,[string]$Roles) {
    return @{
        'X-SDV-Tenant-Id'=$Tenant
        'X-SDV-Subject'=$Subject
        'X-SDV-Roles'=$Roles
        'X-Correlation-Id'=$CorrelationId
    }
}

function InvokeJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body) {
    try {
        $bodyText = if($null -eq $Body){$null}else{$Body | ConvertTo-Json -Depth 30}
        $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body $bodyText -UseBasicParsing -TimeoutSec 30
        $parsed = if([string]::IsNullOrWhiteSpace($r.Content)){$null}else{$r.Content | ConvertFrom-Json}
        return [PSCustomObject]@{StatusCode=[int]$r.StatusCode;Body=$parsed}
    } catch {
        if($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $parsed = if([string]::IsNullOrWhiteSpace($content)){$null}else{$content | ConvertFrom-Json}
            return [PSCustomObject]@{StatusCode=[int]$_.Exception.Response.StatusCode;Body=$parsed}
        }
        throw
    }
}

function DbScalar([string]$Sql) {
    $value = (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql 2>$null | Out-String).Trim()
    if($LASTEXITCODE -ne 0) { throw "Database scalar query failed: $Sql" }
    return $value
}

function SaveField([hashtable]$Headers,[string]$FieldKey,[string]$Label,[bool]$Identifier) {
    $r = InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel=$Label
        fieldType='TEXT'
        required=$true
        businessIdentifier=$Identifier
        searchable=$true
        normalization=if($Identifier){'DIGITS_ONLY'}else{'LOWERCASE'}
        searchMode=if($Identifier){'EXACT'}else{'PARTIAL'}
        validationRules=@{}
    }
    Require ($r.StatusCode -eq 200) "Configured clean-install metadata field $FieldKey"
}

function CleanupFixture {
    if([string]::IsNullOrWhiteSpace([string]$SourceId)) { return }
    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
      delete from submission_expected_documents where tenant_id='$Tenant' and submission_id in (select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId');
      delete from document_redaction_operations where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
      delete from document_crypto_metadata where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
      delete from document_storage_refs where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
      delete from documents where tenant_id='$Tenant' and source_id='$SourceId';
      delete from business_metadata_values where tenant_id='$Tenant' and source_id='$SourceId';
      delete from submissions where tenant_id='$Tenant' and source_id='$SourceId';
      delete from metadata_field_definitions where tenant_id='$Tenant' and source_id='$SourceId';
      delete from audit_events where tenant_id='$Tenant' and correlation_id='$CorrelationId';
      delete from ingestion_sources where tenant_id='$Tenant' and source_id='$SourceId';
    " *> $null
    if($LASTEXITCODE -ne 0) { throw 'Clean-install fixture database cleanup failed.' }
    if(-not [string]::IsNullOrWhiteSpace([string]$VolumeId)) {
        & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "delete from storage_volumes where tenant_id='$Tenant' and volume_id='$VolumeId';" *> $null
        if($LASTEXITCODE -ne 0) { throw 'Clean-install storage-volume cleanup failed.' }
    }
    Ok 'Disposable clean-install source/submission/document data removed'
}

# Strict clean-distribution checks happen before the script creates .runtime output.
Write-Host '=== PHASE 1 - Clean distribution / prerequisites ==='
foreach($required in @(
    'README.md','build-all.cmd','Start-SDV-Local.ps1','Stop-SDV-Local.ps1',
    'frontend\sdv-ui\package-lock.json','docs\release\SDV_V1_GA_BASELINE.json',
    'samples\third-party-producer\Invoke-SDV-Producer.ps1','test-tools\fixtures\business-viewer-two-page.pdf'
)) {
    Require (Test-Path -LiteralPath (Join-Path $Root $required)) "GA distribution contains $required"
}

$baseline = Get-Content -LiteralPath $BaselineMarker -Raw | ConvertFrom-Json
Require ([string]$baseline.version -eq '1.0.0') 'GA baseline marker declares version 1.0.0'
Require ([string]$baseline.frozenUnits -eq '0-25') 'GA baseline marker declares Units 0-25 frozen'
Require ([string]$baseline.latestMigration -eq '010') 'GA baseline marker declares migration 010 ceiling'

if(-not $SkipBuild) {
    $generated = @(
        (Join-Path $Root 'backend\sdv-web\target'),
        (Join-Path $Root 'frontend\sdv-ui\node_modules'),
        (Join-Path $Root 'frontend\sdv-ui\dist'),
        (Join-Path $Root '.runtime')
    ) | Where-Object { Test-Path -LiteralPath $_ }
    Require (@($generated).Count -eq 0) 'Fresh GA extraction has no pre-existing target/node_modules/dist/.runtime output'
}

foreach($cmd in @('docker','java','mvn','node','npm')) {
    Require ($null -ne (Get-Command $cmd -ErrorAction SilentlyContinue)) "Required command is available: $cmd"
}
& docker compose version *> $null
Require ($LASTEXITCODE -eq 0) 'Docker Compose is available'

$PreExistingContainers = Get-SdvContainers
$ConflictingContainers = @($PreExistingContainers | Where-Object { $_ -in @('sdv-postgres','sdv-vault') })
if($ConflictingContainers.Count -gt 0) {
    throw "The clean-install harness owns container names sdv-postgres/sdv-vault and cannot run while they already exist. Found: $($ConflictingContainers -join ', ')"
}
if($PreExistingContainers.Count -gt 0 -and -not $AllowExistingSdvContainers) {
    throw "Official clean-install evidence requires a machine/VM with no pre-existing sdv-* Docker containers. Found: $($PreExistingContainers -join ', ')"
}
if($PreExistingContainers.Count -gt 0) {
    Info "REHEARSAL MODE: preserving non-conflicting pre-existing SDV containers: $($PreExistingContainers -join ', ')"
} else {
    Ok 'No pre-existing SDV Docker containers are present'
}

$lockShaBefore = (Get-FileHash -Algorithm SHA256 -LiteralPath $LockFile).Hash.ToLowerInvariant()
Require ($lockShaBefore.Length -eq 64) 'Frontend package-lock SHA-256 captured before build'
if(-not [string]::IsNullOrWhiteSpace($ExpectedSourceArchiveSha256)) {
    Require ($ExpectedSourceArchiveSha256 -match '^[0-9a-fA-F]{64}$') 'Provided source-archive SHA-256 has valid format'
    $ExpectedSourceArchiveSha256 = $ExpectedSourceArchiveSha256.ToLowerInvariant()
} else {
    Info 'No source-archive SHA-256 was supplied; this run can only produce REHEARSAL evidence.'
}
$OfficialMode = (-not $AllowExistingSdvContainers) -and (-not $SkipBuild) -and (-not [string]::IsNullOrWhiteSpace($ExpectedSourceArchiveSha256))

New-Item -ItemType Directory -Force -Path $Work,$StorageRoot,$EvidenceDirectory | Out-Null
$env:SDV_POSTGRES_VOLUME = $DbVolume

try {
    Write-Host ''
    Write-Host '=== PHASE 2 - Build exact GA source ==='
    if(-not $SkipBuild) {
        & (Join-Path $Root 'build-all.cmd')
        if($LASTEXITCODE -ne 0) { throw 'GA clean-install build failed.' }
        $BuildPerformed = $true
        Ok 'Backend Maven verify + frontend npm ci/build passed from clean GA extraction'
    } else {
        Require (Test-Path -LiteralPath (Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar')) 'Prebuilt backend jar exists for -SkipBuild rehearsal'
        Require (Test-Path -LiteralPath (Join-Path $Root 'frontend\sdv-ui\node_modules')) 'Frontend dependencies exist for -SkipBuild rehearsal'
        Info 'Build skipped by explicit request; this is not sufficient for official clean-install evidence.'
    }
    $lockShaAfter = (Get-FileHash -Algorithm SHA256 -LiteralPath $LockFile).Hash.ToLowerInvariant()
    Require ($lockShaAfter -eq $lockShaBefore) 'npm ci/build did not mutate the validated frontend dependency lock'

    Write-Host ''
    Write-Host '=== PHASE 3 - First startup + migration 000-010 ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    Require ((Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Backend is healthy after first startup'
    $front = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 5
    Require ([int]$front.StatusCode -eq 200) 'Frontend is reachable after first startup'
    Require ((DbScalar "select count(*) from databasechangelog where id='010-performance-resilience-hardening';") -eq '1') 'Fresh database applied migration 010 exactly once'
    $laterMigration = DbScalar "select count(*) from databasechangelog where id ~ '^(011|01[2-9]|0[2-9][0-9]|[1-9][0-9]{2,})';"
    Require ($laterMigration -eq '0') 'Fresh database contains no migration beyond 010'

    Write-Host ''
    Write-Host '=== PHASE 4 - Minimal encrypted business lifecycle ==='
    $it = UserHeaders 'ga-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders 'ga-admin' 'BUSINESS_ADMIN'
    $business = UserHeaders 'ga-user' 'BUSINESS_USER'

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName="GA Clean Install Volume $Suffix"
        rootPath=$StorageRoot
        state='ACTIVE'
        priority=0
        warningPercent=90
        stopWritePercent=95
    }
    Require ($volume.StatusCode -eq 201) 'Created clean-install storage volume'
    $VolumeId = [string]$volume.Body.volume.volumeId

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey="ga-clean-$Suffix"
        displayName='GA Clean Install Producer'
        authType='OAUTH2_CLIENT_CREDENTIALS'
        credentialReference="external://ga-clean/$Suffix"
        limits=@{maxPdfBytes=10485760;maxPagesPerPdf=2000;maxDocumentsPerSubmission=10;maxAggregateSubmissionBytes=20971520;incompleteAttentionHours=24}
    }
    Require ($source.StatusCode -eq 201) 'Created clean-install producer source'
    $SourceId = [string]$source.Body.sourceId
    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    @{contractNumber='100000000001';memberName='GA Clean Install'} | ConvertTo-Json | Set-Content -LiteralPath $MetadataFile -Encoding UTF8
    & $Producer -BaseUrl $Base -SourceSubmissionId "ga-submission-$Suffix" -PdfPaths @($Pdf) -SourceDocumentIds @("ga-document-$Suffix") -MetadataJsonPath $MetadataFile -LocalDevTenantId $Tenant -LocalDevSourceId $SourceId -LocalDevSubject 'ga-producer' -CorrelationId $CorrelationId -ReplayFirstDocument -OutputJson $ProducerResult | Out-Null
    $producerOutput = Get-Content -LiteralPath $ProducerResult -Raw | ConvertFrom-Json
    Require ([string]$producerOutput.submissionStatus -eq 'COMPLETE') 'Standalone producer completed one-document submission'
    Require ([int]$producerOutput.documentCount -eq 1) 'Exactly one clean-install document was accepted'
    Require ([bool]$producerOutput.createIdempotentReplay -and [bool]$producerOutput.firstDocumentReplay) 'Submission/document replay remains idempotent'
    $SubmissionId = [string]$producerOutput.submissionId

    $search = InvokeJson 'POST' "$Base/api/v1/business/search" $business @{sourceId=$SourceId;criteria=@{contractNumber='100000000001'};limit=10}
    Require ($search.StatusCode -eq 200 -and @($search.Body.records).Count -eq 1) 'Business search finds the encrypted clean-install document'
    $recordRef = [string]$search.Body.records[0].recordRef
    $record = InvokeJson 'GET' "$Base/api/v1/business/records/$recordRef" $business $null
    Require ($record.StatusCode -eq 200 -and @($record.Body.documents).Count -eq 1) 'Business record aggregates the clean-install document'
    $DocumentId = [string]$record.Body.documents[0].documentId
    $viewer = InvokeJson 'GET' "$Base/api/v1/business/documents/$DocumentId/viewer" $business $null
    Require ($viewer.StatusCode -eq 200) 'Secure viewer metadata is available before restart'
    $page = Invoke-WebRequest -Uri "$Base/api/v1/business/documents/$DocumentId/viewer/pages/1?scale=1.0" -Headers $business -UseBasicParsing -TimeoutSec 30
    $PageBytesBefore = [int]$page.RawContentLength
    Require ($PageBytesBefore -gt 100) 'Secure viewer decrypts/renders page content before restart'
    $CiphertextShaBefore = DbScalar "select integrity_value from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    Require ($CiphertextShaBefore -match '^[0-9a-fA-F]{64}$') 'Committed ciphertext integrity fingerprint exists'

    Write-Host ''
    Write-Host '=== PHASE 5 - Application restart persistence smoke ==='
    & (Join-Path $Root 'Stop-SDV-Local.ps1') -KeepDatabase
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    Require ((Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Backend is healthy after application restart'
    $viewerAfter = InvokeJson 'GET' "$Base/api/v1/business/documents/$DocumentId/viewer" $business $null
    Require ($viewerAfter.StatusCode -eq 200) 'Same encrypted document is securely viewable after application restart'
    $pageAfter = Invoke-WebRequest -Uri "$Base/api/v1/business/documents/$DocumentId/viewer/pages/1?scale=1.0" -Headers $business -UseBasicParsing -TimeoutSec 30
    $PageBytesAfter = [int]$pageAfter.RawContentLength
    Require ($PageBytesAfter -gt 100) 'Same document decrypts/renders after application restart'
    $ciphertextShaAfter = DbScalar "select integrity_value from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    Require ($ciphertextShaAfter.ToLowerInvariant() -eq $CiphertextShaBefore.ToLowerInvariant()) 'Committed ciphertext identity remains stable across application restart'
    $RestartPassed = $true

    Write-Host ''
    Write-Host '=== PHASE 6 - Evidence ==='
    $evidenceResult = if($OfficialMode){'PASS'}else{'REHEARSAL PASS'}
    $archiveShaForEvidence = if([string]::IsNullOrWhiteSpace($ExpectedSourceArchiveSha256)){$null}else{$ExpectedSourceArchiveSha256}
    $evidence = [ordered]@{
        product = 'Secure Document Vault'
        version = '1.0.0'
        result = $evidenceResult
        observedAt = [DateTimeOffset]::UtcNow.ToString('o')
        frozenUnits = '0-25'
        latestMigration = '010'
        expectedSourceArchiveSha256 = $archiveShaForEvidence
        frontendPackageLockSha256 = $lockShaBefore
        buildPerformed = $BuildPerformed
        preExistingSdvContainers = @($PreExistingContainers)
        disposablePostgresVolume = $DbVolume
        submissionId = $SubmissionId
        documentId = $DocumentId
        ciphertextSha256 = $CiphertextShaBefore
        viewerPageBytesBeforeRestart = $PageBytesBefore
        viewerPageBytesAfterRestart = $PageBytesAfter
        restartPersistencePassed = $RestartPassed
        note = 'Local clean-install uses the LOCAL DEV Vault service. Production Vault durability/recovery is governed by the frozen Unit 22 runbook.'
    }
    $evidencePath = Join-Path $EvidenceDirectory 'SDV-V1-GA-CLEAN-INSTALL-EVIDENCE.json'
    $evidence | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    Require (Test-Path -LiteralPath $evidencePath) 'Clean-install evidence JSON was written'

    Write-Host ''
    Write-Host '=== RESULT ==='
    if(-not $OfficialMode) {
        Write-Host 'REHEARSAL PASS - SDV V1.0.0 GA clean-install smoke passed, but official evidence requires a fresh extraction, real build, no pre-existing SDV containers, and the verified source-archive SHA-256.' -ForegroundColor Yellow
    } else {
        Write-Host 'PASS - SDV V1.0.0 GA clean-install acceptance passed.' -ForegroundColor Green
    }
    Write-Host "Evidence: $EvidenceDirectory"
}
finally {
    try {
        if((Get-SdvContainers) -contains 'sdv-postgres') {
            try { CleanupFixture } catch { Write-Warning "Clean-install fixture cleanup failed: $($_.Exception.Message)" }
        }
    } catch { Write-Warning "Clean-install cleanup inspection failed: $($_.Exception.Message)" }

    try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning "SDV shutdown failed: $($_.Exception.Message)" }

    try {
        $exists = (& docker volume ls --format '{{.Name}}' | Where-Object { $_ -eq $DbVolume })
        if($exists) {
            & docker volume rm $DbVolume *> $null
            if($LASTEXITCODE -ne 0) { Write-Warning "Could not remove disposable PostgreSQL volume $DbVolume" }
            else { Ok "Disposable GA PostgreSQL volume removed ($DbVolume)" }
        }
    } catch { Write-Warning "Disposable DB volume cleanup failed: $($_.Exception.Message)" }

    try {
        $current = Get-SdvContainers
        $newContainers = @($current | Where-Object { $PreExistingContainers -notcontains $_ })
        if($newContainers.Count -gt 0) {
            Write-Warning "Clean-install run left new SDV container(s): $($newContainers -join ', ')"
        } else {
            Ok 'Clean-install run left no new SDV Docker containers behind'
        }
    } catch { Write-Warning "Docker leak verification failed: $($_.Exception.Message)" }

    Remove-Item -LiteralPath $Work -Recurse -Force -ErrorAction SilentlyContinue
    if($null -eq $OriginalDbVolumeEnv) { Remove-Item Env:SDV_POSTGRES_VOLUME -ErrorAction SilentlyContinue }
    else { $env:SDV_POSTGRES_VOLUME = $OriginalDbVolumeEnv }
}
