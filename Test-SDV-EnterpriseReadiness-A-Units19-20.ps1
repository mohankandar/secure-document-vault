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
$FailureDocumentId = $null
$StuckDocumentId = $null
$StorageRoot = Join-Path $Root ".runtime\enterprise-readiness-a\$Suffix"
$Pdf = Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; Ok $Message }
function UserHeaders([string]$Tenant, [string]$Subject, [string]$Roles) {
    return @{ 'X-SDV-Tenant-Id'=$Tenant; 'X-SDV-Subject'=$Subject; 'X-SDV-Roles'=$Roles; 'X-Correlation-Id'="ops-a-$Suffix" }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{ 'X-SDV-Tenant-Id'=$Tenant; 'X-SDV-Source-Id'=$Source; 'X-SDV-Subject'=$Subject; 'X-Correlation-Id'="ops-a-$Suffix" }
}
function DbScalar([string]$Sql) { return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim() }
function InvokeJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body) {
    try {
        $bodyText = if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 20 }
        $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body $bodyText -UseBasicParsing -TimeoutSec 30
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
function SaveField([hashtable]$Headers,[string]$FieldKey,[string]$Label,[bool]$Identifier) {
    $r = InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel=$Label; fieldType='TEXT'; required=$true; businessIdentifier=$Identifier; searchable=$true
        normalization=if($Identifier){'DIGITS_ONLY'}else{'LOWERCASE'}; searchMode=if($Identifier){'EXACT'}else{'PARTIAL'}; validationRules=@{}
    }
    Require ($r.StatusCode -eq 200) "Configured metadata field $FieldKey"
}
function CreateSubmissionAndUpload([hashtable]$SourceHeaders,[string]$Name,[int]$ExpectedCount,[bool]$Upload) {
    $manifest = @()
    for ($i=1; $i -le $ExpectedCount; $i++) { $manifest += @{ sourceDocumentId="$Name-doc-$i" } }
    $s = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $SourceHeaders @{
        sourceSubmissionId="$Name-$Suffix"; expectedDocumentCount=$ExpectedCount; expectedDocuments=$manifest
        metadata=@{ contractNumber=(Get-Random -Minimum 100000 -Maximum 999999).ToString(); memberName=$Name }
    }
    Require ($s.StatusCode -eq 201) "Created $Name submission"
    if ($Upload) {
        $h = @{} + $SourceHeaders; $h['X-SDV-Source-Document-Id']="$Name-doc-1"; $h['X-SDV-Filename']="$Name.pdf"
        $u = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$($s.Body.submissionId)/documents" -Method Post -Headers $h -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 30
        Require ([int]$u.StatusCode -eq 201) "$Name uploaded through frozen ingestion"
        $ub = $u.Content | ConvertFrom-Json
        return [PSCustomObject]@{ SubmissionId=[string]$s.Body.submissionId; DocumentId=[string]$ub.documentId }
    }
    return [PSCustomObject]@{ SubmissionId=[string]$s.Body.submissionId; DocumentId=$null }
}

try {
    if (-not $SkipBuild) {
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime, migration 009 and frozen baseline ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='009-audit-operations-readiness';") -eq '1') 'Units 19-20 migration 009 applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='008-controlled-download-retention';") -eq '1') 'Frozen migration 008 remains applied once'
    Require (Test-Path $Pdf) 'Two-page PDF fixture is present'

    $it = UserHeaders $Tenant1 'ops-tenant-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders $Tenant1 'ops-business-admin' 'BUSINESS_ADMIN'
    $techAdmin = UserHeaders $Tenant1 'ops-tech-admin' 'TENANT_IT_ADMIN,BUSINESS_ADMIN'
    $platform = UserHeaders $Tenant1 'ops-platform' 'PLATFORM_OPERATIONS_ADMIN'
    $business = UserHeaders $Tenant1 'ops-business-user' 'BUSINESS_USER'
    $steward = UserHeaders $Tenant1 'ops-steward' 'DOCUMENT_STEWARD'
    $foreignTech = UserHeaders $Tenant2 'foreign-tech' 'TENANT_IT_ADMIN'

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey="ops-a-$Suffix"; displayName="Operations Source $Suffix"; authType='OAUTH2_CLIENT_CREDENTIALS'
        credentialReference="vault://ops/$Suffix"; limits=@{ incompleteAttentionHours=24 }
    }
    Require ($source.StatusCode -eq 201) 'Tenant IT created disposable source'
    $SourceId=[string]$source.Body.sourceId
    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName="Operations Volume $Suffix"; rootPath=$StorageRoot; state='ACTIVE'; priority=1; warningPercent=90; stopWritePercent=95
    }
    Require ($volume.StatusCode -eq 201) 'Tenant IT created disposable storage volume'
    $VolumeId=[string]$volume.Body.volume.volumeId
    $used = [int]$volume.Body.capacity.usedPercent
    $warning = [Math]::Max(1, [Math]::Min(99, $used))
    $stop = [Math]::Min(100, [Math]::Max($warning + 1, 95))
    if ($stop -le $warning) { $warning=99; $stop=100 }
    $volUpdate = InvokeJson 'PUT' "$Base/api/v1/admin/storage-volumes/$VolumeId" $it @{
        displayName="Operations Volume $Suffix"; state='ACTIVE'; priority=1; warningPercent=$warning; stopWritePercent=$stop
    }
    Require ($volUpdate.StatusCode -eq 200) 'Tenant IT configured capacity warning for operational validation'

    $sourceHeaders = SourceHeaders $Tenant1 $SourceId 'ops-producer'
    $good = CreateSubmissionAndUpload $sourceHeaders 'ops-good' 1 $true
    $FailureDocumentId=$good.DocumentId
    $incomplete = CreateSubmissionAndUpload $sourceHeaders 'ops-incomplete' 2 $true
    $stuck = CreateSubmissionAndUpload $sourceHeaders 'ops-stuck' 1 $true
    $StuckDocumentId=$stuck.DocumentId

    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
      update documents set status='PROCESSING_FAILED', processing_error_code='OPS_ACCEPTANCE', processing_attempt_count=3 where document_id='$FailureDocumentId';
      update submissions set status='INCOMPLETE', last_activity_at=current_timestamp - interval '26 hours' where submission_id='$($incomplete.SubmissionId)';
      update documents set status='PROCESSING', ingested_at=current_timestamp - interval '30 minutes' where document_id='$StuckDocumentId';
    " *> $null
    Ok 'Seeded processing failure, stuck processing document and incomplete submission'

    Write-Host ''
    Write-Host '=== PHASE 2 - Unit 19 audit model and tenant-scoped views ==='
    $audit = InvokeJson 'GET' "$Base/api/v1/operations/audit-events?limit=2" $techAdmin $null
    Require ($audit.StatusCode -eq 200) 'Tenant IT can open tenant audit view'
    Require (@($audit.Body.events).Count -eq 2) 'Audit view honors bounded page size'
    Require ($audit.Body.hasMore -eq $true -and -not [string]::IsNullOrWhiteSpace([string]$audit.Body.nextCursor)) 'Audit view provides stable cursor pagination'
    $older = InvokeJson 'GET' "$Base/api/v1/operations/audit-events?limit=2&cursor=$([uri]::EscapeDataString([string]$audit.Body.nextCursor))" $techAdmin $null
    Require ($older.StatusCode -eq 200) 'Audit cursor retrieves older tenant events'

    $filtered = InvokeJson 'GET' "$Base/api/v1/operations/audit-events?action=INGESTION_SOURCE_CREATED&outcome=SUCCESS&limit=20" $techAdmin $null
    Require ($filtered.StatusCode -eq 200) 'Audit view supports action/outcome filtering'
    Require (@($filtered.Body.events | Where-Object { $_.action -eq 'INGESTION_SOURCE_CREATED' -and $_.outcome -eq 'SUCCESS' }).Count -ge 1) 'Audit filter returns matching configuration event'

    $denied = InvokeJson 'GET' "$Base/api/v1/operations/audit-events?limit=10" $business $null
    Require ($denied.StatusCode -eq 403) 'BUSINESS_USER cannot open technical audit view'
    $deniedAudit = InvokeJson 'GET' "$Base/api/v1/operations/audit-events?action=AUTHORIZATION_DENIED&outcome=DENIED&limit=20" $techAdmin $null
    Require (@($deniedAudit.Body.events | Where-Object { $_.actorId -eq 'ops-business-user' -and $_.targetId -eq 'VIEW_TECHNICAL_FAILURES' }).Count -ge 1) 'Denied capability decision is append-audited safely'

    $foreign = InvokeJson 'GET' "$Base/api/v1/operations/audit-events?correlationId=ops-a-$Suffix&limit=100" $foreignTech $null
    Require ($foreign.StatusCode -eq 200) 'Another tenant technical admin receives an isolated audit view'
    Require (@($foreign.Body.events).Count -eq 0) 'Cross-tenant audit data is not exposed'

    $auditText = ((InvokeJson 'GET' "$Base/api/v1/operations/audit-events?limit=100" $techAdmin $null).Body | ConvertTo-Json -Depth 20)
    Require ($auditText -notmatch 'vault://ops/' -and $auditText -notmatch 'Secure Viewer Alpha 7391') 'Audit view does not expose source credentials or PDF content'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='AUDIT_VIEWED';") -ge '1') 'Audit-view access itself is append-audited'

    Write-Host ''
    Write-Host '=== PHASE 3 - Unit 20 operational health / failures / stuck workflow ==='
    $summary = InvokeJson 'GET' "$Base/api/v1/operations/summary" $techAdmin $null
    Require ($summary.StatusCode -eq 200) 'Tenant IT can open operational summary'
    Require ($summary.Body.application.status -eq 'UP') 'Application operational check is UP'
    Require ($summary.Body.database.status -eq 'UP') 'Database operational check is UP'
    Require ($summary.Body.keyService.status -eq 'UP') 'Vault/key-service operational check is UP'
    Require (@($summary.Body.storageVolumes | Where-Object { $_.volumeId -eq $VolumeId }).Count -eq 1) 'Operational view includes configured storage volume'
    Require (@($summary.Body.storageVolumes | Where-Object { $_.volumeId -eq $VolumeId -and $_.warningThresholdReached }).Count -eq 1) 'Operational view exposes configured capacity warning'
    Require ([long]$summary.Body.processingFailureCount -ge 1) 'Operational view counts processing failures'
    Require (@($summary.Body.processingFailures | Where-Object { $_.documentId -eq $FailureDocumentId -and $_.errorCode -eq 'OPS_ACCEPTANCE' }).Count -eq 1) 'Operational view exposes safe processing failure evidence'
    Require ([long]$summary.Body.stuckDocumentCount -ge 1) 'Operational view counts stuck document transitions'
    Require (@($summary.Body.stuckDocuments | Where-Object { $_.documentId -eq $StuckDocumentId -and $_.status -eq 'PROCESSING' }).Count -eq 1) 'Operational view identifies stale PROCESSING document'
    Require ([long]$summary.Body.attentionSubmissionCount -ge 1) 'Operational view counts incomplete/stuck submissions'
    Require (@($summary.Body.attentionSubmissions | Where-Object { $_.submissionId -eq $incomplete.SubmissionId }).Count -eq 1) 'Operational view identifies incomplete submission needing attention'

    Require ((InvokeJson 'GET' "$Base/api/v1/operations/summary" $business $null).StatusCode -eq 403) 'BUSINESS_USER cannot access technical operational details'
    Require ((InvokeJson 'GET' "$Base/api/v1/operations/summary" $platform $null).StatusCode -eq 200) 'PLATFORM_OPERATIONS_ADMIN can view technical operational health'

    Write-Host ''
    Write-Host '=== PHASE 4 - Capacity banner privacy + technical admin actions ==='
    $banner = InvokeJson 'GET' "$Base/api/v1/operations/capacity-banner" $business $null
    Require ($banner.StatusCode -eq 200) 'Capacity banner is available to logged-in BUSINESS_USER'
    Require ($banner.Body.visible -eq $true -and $banner.Body.severity -in @('WARNING','CRITICAL')) 'Capacity banner becomes visible at configured threshold'
    $bannerText = $banner.Body | ConvertTo-Json -Depth 10
    Require ($bannerText -notmatch [regex]::Escape($StorageRoot) -and $bannerText -notmatch $VolumeId) 'All-user capacity banner exposes no root path or volume identifier'

    $readOnly = InvokeJson 'PUT' "$Base/api/v1/admin/storage-volumes/$VolumeId" $it @{
        displayName="Operations Volume $Suffix"; state='READ_ONLY'; priority=1; warningPercent=$warning; stopWritePercent=$stop
    }
    Require ($readOnly.StatusCode -eq 200 -and $readOnly.Body.volume.state -eq 'READ_ONLY') 'Tenant IT operational action can place a volume READ_ONLY'
    $active = InvokeJson 'PUT' "$Base/api/v1/admin/storage-volumes/$VolumeId" $it @{
        displayName="Operations Volume $Suffix"; state='ACTIVE'; priority=1; warningPercent=$warning; stopWritePercent=$stop
    }
    Require ($active.StatusCode -eq 200 -and $active.Body.volume.state -eq 'ACTIVE') 'Tenant IT operational action can restore volume ACTIVE'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and action='STORAGE_VOLUME_UPDATED' and target_id='$VolumeId';") -ge '2') 'Technical storage actions are append-audited'

    Write-Host ''
    Write-Host '=== PHASE 5 - Unit 19-20 frontend boundaries ==='
    $ui = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 10
    Require ($ui.StatusCode -eq 200) 'Frontend is reachable on port 5183'
    $appSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\App.tsx') -Raw
    $apiSource = Get-Content (Join-Path $Root 'frontend\sdv-ui\src\api.ts') -Raw
    Require ($appSource -match 'VIEW_TECHNICAL_FAILURES' -and $appSource -match 'Operational readiness') 'Frontend gates operational console on technical-view capability'
    Require ($appSource -match 'Tenant audit history' -and $appSource -match 'Load older events') 'Frontend provides filtered/paginated audit view'
    Require ($appSource -match 'capacityBanner' -and $appSource -match 'Storage capacity') 'Frontend renders all-user capacity warning banner'
    Require ($appSource -match 'CONFIGURE_STORAGE_KEY_PROVIDER' -and $appSource -match 'READ_ONLY') 'Frontend provides capability-gated storage admin actions'
    Require ($apiSource -match "if \(!import\.meta\.env\.DEV\) return \{\}") 'Production frontend still does not inject local authentication headers'
    Require ($appSource -notmatch 'LexaVault') 'Units 19-20 introduce no LexaVault coupling'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Enterprise / Operational Readiness Checkpoint A (Units 19-20) acceptance passed.' -ForegroundColor Green

} finally {
    if (-not $KeepTestData) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from audit_events where tenant_id='$Tenant1' and correlation_id='ops-a-$Suffix';
                    delete from audit_events where tenant_id='$Tenant1' and actor_id in ('ops-tenant-it','ops-business-admin','ops-tech-admin','ops-platform','ops-business-user','ops-steward','ops-producer');
                    delete from audit_events where tenant_id='$Tenant2' and actor_id='foreign-tech';
                    delete from submission_expected_documents where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_redaction_operations where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_crypto_metadata where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_storage_refs where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from documents where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from business_metadata_values where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from submissions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';
                " *> $null
                if (-not [string]::IsNullOrWhiteSpace([string]$VolumeId)) {
                    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "delete from storage_volumes where tenant_id='$Tenant1' and volume_id='$VolumeId';" *> $null
                }
                Ok 'Disposable Units 19-20 acceptance data removed'
            }
        } catch { Write-Warning "Units 19-20 cleanup failed: $($_.Exception.Message)" }
        Remove-Item -Recurse -Force $StorageRoot -ErrorAction SilentlyContinue
    }
    if (-not $KeepRunning) { try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message } }
}
