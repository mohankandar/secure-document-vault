[CmdletBinding()]
param([switch]$SkipBuild)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

$Root=$PSScriptRoot
$Base='http://localhost:8280'
$Node2Base='http://localhost:8281'
$Tenant='11111111-1111-1111-1111-111111111111'
$Suffix=([guid]::NewGuid().ToString('N')).Substring(0,10)
$Pdf=Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'
$Jar=Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar'
$Runtime=Join-Path $Root ".runtime\enterprise-readiness-e\$Suffix"
$Logs=Join-Path $Runtime 'logs'
$StorageRoot=Join-Path $Runtime 'storage'
$SourceId=$null
$VolumeId=$null
$SubmissionId=$null
$DocumentId=$null
$Node2Process=$null
$OldCiphertextPath=$null
$ReplacementCiphertextPath=$null
$HeldTableName="document_redaction_operations_u24_$Suffix"

$previousRecoveryInitial=$env:SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS
$previousRecoverySweep=$env:SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS
$env:SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS='2000'
$env:SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS='2000'

function Ok([string]$Message){ Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition,[string]$Message){ if(-not $Condition){ throw $Message }; Ok $Message }
function UserHeaders([string]$Subject,[string]$Roles){ return @{'X-SDV-Tenant-Id'=$Tenant;'X-SDV-Subject'=$Subject;'X-SDV-Roles'=$Roles;'X-Correlation-Id'="u24-$Suffix"} }
function SourceHeaders([string]$Source,[string]$Subject){ return @{'X-SDV-Tenant-Id'=$Tenant;'X-SDV-Source-Id'=$Source;'X-SDV-Subject'=$Subject;'X-Correlation-Id'="u24-$Suffix"} }
function DbScalar([string]$Sql){ return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim() }
function DbExec([string]$Sql){ & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c $Sql *> $null; if($LASTEXITCODE -ne 0){ throw 'Database command failed.' } }

function Resolve-StorageObjectPath([string]$VolumeId,[string]$ObjectKey){
    if([string]::IsNullOrWhiteSpace($VolumeId) -or [string]::IsNullOrWhiteSpace($ObjectKey)){ throw 'Storage volume id and object key are required.' }
    $root=DbScalar "select root_path from storage_volumes where tenant_id='$Tenant' and volume_id='$VolumeId';"
    if([string]::IsNullOrWhiteSpace($root)){ throw "Storage volume root is unavailable for volume $VolumeId." }
    return Join-Path $root ($ObjectKey.Replace('/',[System.IO.Path]::DirectorySeparatorChar))
}

function InvokeJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body){
    try {
        $bodyText=if($null -eq $Body){$null}else{$Body|ConvertTo-Json -Depth 30}
        $r=Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body $bodyText -UseBasicParsing -TimeoutSec 60
        $parsed=if([string]::IsNullOrWhiteSpace($r.Content)){$null}else{$r.Content|ConvertFrom-Json}
        return [PSCustomObject]@{StatusCode=[int]$r.StatusCode;Body=$parsed}
    } catch {
        if($_.Exception.Response -and $_.Exception.Response.StatusCode){
            $reader=New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content=$reader.ReadToEnd()
            $parsed=if([string]::IsNullOrWhiteSpace($content)){$null}else{$content|ConvertFrom-Json}
            return [PSCustomObject]@{StatusCode=[int]$_.Exception.Response.StatusCode;Body=$parsed}
        }
        throw
    }
}

function SaveField([hashtable]$Headers,[string]$FieldKey,[string]$Label,[bool]$Identifier){
    $r=InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel=$Label;fieldType='TEXT';required=$true;businessIdentifier=$Identifier;searchable=$true
        normalization=if($Identifier){'DIGITS_ONLY'}else{'LOWERCASE'};searchMode=if($Identifier){'EXACT'}else{'PARTIAL'};validationRules=@{}
    }
    Require ($r.StatusCode -eq 200) "Configured metadata field $FieldKey"
}

function Stop-Owned([object]$Process,[string]$Name){
    if($null -eq $Process){ return }
    try {
        $live=Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
        if($live){
            Write-Host "Stopping $Name PID $($Process.Id)..."
            & taskkill.exe /PID $Process.Id /T /F *> $null
            if($LASTEXITCODE -ne 0){ Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
        }
    } catch { }
}

function Start-BackendInstance([int]$Port,[string]$Name){
    $out=Join-Path $Logs "$Name.out.log"
    $err=Join-Path $Logs "$Name.err.log"
    $saved=@{
        SERVER_PORT=$env:SERVER_PORT
        SPRING_DATASOURCE_URL=$env:SPRING_DATASOURCE_URL
        SPRING_DATASOURCE_USERNAME=$env:SPRING_DATASOURCE_USERNAME
        SPRING_DATASOURCE_PASSWORD=$env:SPRING_DATASOURCE_PASSWORD
        SDV_AUTH_MODE=$env:SDV_AUTH_MODE
        SDV_KEY_PROVIDER=$env:SDV_KEY_PROVIDER
        SDV_VAULT_BASE_URL=$env:SDV_VAULT_BASE_URL
        SDV_VAULT_TOKEN=$env:SDV_VAULT_TOKEN
        SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS=$env:SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS
        SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS=$env:SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS
    }
    try {
        $env:SERVER_PORT=[string]$Port
        $env:SPRING_DATASOURCE_URL='jdbc:postgresql://localhost:5433/sdv'
        $env:SPRING_DATASOURCE_USERNAME='sdv'
        $env:SPRING_DATASOURCE_PASSWORD='sdv_local_only'
        $env:SDV_AUTH_MODE='local-header'
        $env:SDV_KEY_PROVIDER='vault'
        $env:SDV_VAULT_BASE_URL='http://127.0.0.1:8200'
        $env:SDV_VAULT_TOKEN='sdv-local-root'
        $env:SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS='2000'
        $env:SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS='2000'
        $p=Start-Process -FilePath 'java' -ArgumentList @('-jar',$Jar,'--spring.profiles.active=local') -WorkingDirectory $Root -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    } finally {
        foreach($k in $saved.Keys){ if($null -eq $saved[$k]){Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue}else{Set-Item -Path "Env:$k" -Value $saved[$k]} }
    }
    $up=$false
    for($i=0;$i -lt 60;$i++){
        try { $h=Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 2; if($h.status -eq 'UP'){$up=$true;break} } catch { }
        Start-Sleep -Seconds 1
    }
    if(-not $up){
        Stop-Owned $p $Name
        $tail=if(Test-Path $err){Get-Content $err -Tail 80|Out-String}else{''}
        throw "$Name did not become healthy on port $Port.`n$tail"
    }
    return $p
}

function CleanupSource([string]$Id){
    DbExec "
      delete from submission_expected_documents where tenant_id='$Tenant' and submission_id in (select submission_id from submissions where tenant_id='$Tenant' and source_id='$Id');
      delete from document_redaction_operations where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$Id');
      delete from document_crypto_metadata where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$Id');
      delete from document_storage_refs where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$Id');
      delete from audit_events where tenant_id='$Tenant' and (correlation_id='u24-$Suffix' or target_id in (select document_id::text from documents where tenant_id='$Tenant' and source_id='$Id'));
      delete from documents where tenant_id='$Tenant' and source_id='$Id';
      delete from business_metadata_values where tenant_id='$Tenant' and source_id='$Id';
      delete from submissions where tenant_id='$Tenant' and source_id='$Id';
      delete from metadata_field_definitions where tenant_id='$Tenant' and source_id='$Id';
      delete from ingestion_sources where tenant_id='$Tenant' and source_id='$Id';"
}

New-Item -ItemType Directory -Force -Path $Runtime,$Logs,$StorageRoot | Out-Null

try {
    if(-not $SkipBuild){
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if($LASTEXITCODE -ne 0){throw 'build-all.cmd failed.'}
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Frozen baseline + Unit 24 hardening ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    Require ((Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='009-audit-operations-readiness';") -eq '1') 'Frozen migration 009 remains applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='010-performance-resilience-hardening';") -eq '1') 'Unit 24 additive migration 010 is applied once'
    Require ((DbScalar "select count(*) from information_schema.columns where table_name='document_redaction_operations' and column_name in ('recovery_claimed_at','recovery_claim_token');") -eq '2') 'Corrective-redaction recovery lease timestamp/token columns are present'
    Require ((DbScalar "select count(*) from pg_constraint where conname='ck_document_redaction_recovery_claim_pair';") -eq '1') 'Recovery lease timestamp/token pairing is database-constrained'
    $opsSource=Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-application\src\main\java\com\sdv\platform\application\operations\OperationsService.java') -Raw
    Require ($opsSource -notmatch 'safeLong\(' -and $opsSource -notmatch 'safeList\(') 'Operational summary no longer masks repository failures with -1/empty safe fallbacks'

    $it=UserHeaders 'u24-it' 'TENANT_IT_ADMIN'
    $admin=UserHeaders 'u24-admin' 'BUSINESS_ADMIN'
    $business=UserHeaders 'u24-business' 'BUSINESS_USER'
    $steward=UserHeaders 'u24-steward' 'DOCUMENT_STEWARD'

    $summary=InvokeJson 'GET' "$Base/api/v1/operations/summary" $it $null
    Require ($summary.StatusCode -eq 200) 'Technical operations summary is available'
    Require (@($summary.Body.dataObservations).Count -eq 4) 'Operational summary exposes explicit data-read observations'
    Require (@($summary.Body.dataObservations | Where-Object {$_.status -eq 'DEGRADED'}).Count -eq 0) 'Healthy operational data sources report AVAILABLE rather than sentinel values'

    Write-Host ''
    Write-Host '=== PHASE 2 - Partial operational query failure is explicit, not falsely healthy ==='
    try {
        DbExec "alter table document_redaction_operations rename to $HeldTableName;"
        $degraded=InvokeJson 'GET' "$Base/api/v1/operations/summary" $it $null
        Require ($degraded.StatusCode -eq 200) 'Operations summary remains structurally available during one incident-query failure'
        $stuckObservation=@($degraded.Body.dataObservations | Where-Object {$_.section -eq 'stuck-documents'})[0]
        Require ($stuckObservation.status -eq 'DEGRADED') 'Failed stuck-document query is explicitly marked DEGRADED'
        Require ($null -eq $degraded.Body.stuckDocumentCount) 'Unavailable stuck-document count is null, never a misleading -1 or zero'
        Require ([string]$degraded.Body.overallStatus -in @('ATTENTION','DOWN')) 'Partial operational-data loss keeps overall status non-healthy (ATTENTION or pre-existing/higher-severity DOWN)'
    } finally {
        $exists=(DbScalar "select count(*) from information_schema.tables where table_name='$HeldTableName';")
        if($exists -eq '1'){ DbExec "alter table $HeldTableName rename to document_redaction_operations;" }
    }
    $recoveredSummary=InvokeJson 'GET' "$Base/api/v1/operations/summary" $it $null
    $recoveredStuck=@($recoveredSummary.Body.dataObservations | Where-Object {$_.section -eq 'stuck-documents'})[0]
    Require ($recoveredStuck.status -eq 'AVAILABLE') 'Operational observation returns to AVAILABLE after dependency recovery'
    Require ((InvokeJson 'GET' "$Base/api/v1/operations/summary" $business $null).StatusCode -eq 403) 'Operational technical detail remains capability-gated'

    Write-Host ''
    Write-Host '=== PHASE 3 - Multi-node stale corrective-redaction reconciliation ==='
    $volume=InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName="Unit 24 Resilience Volume $Suffix";rootPath=$StorageRoot;state='ACTIVE';priority=0;warningPercent=90;stopWritePercent=95
    }
    Require ($volume.StatusCode -eq 201) 'Created disposable Unit 24 storage volume'
    $VolumeId=[string]$volume.Body.volume.volumeId

    $source=InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey="u24-$Suffix";displayName="Unit 24 Resilience Source $Suffix";authType='OAUTH2_CLIENT_CREDENTIALS';credentialReference="external://u24/$Suffix"
        limits=@{maxPdfBytes=10485760;maxPagesPerPdf=2000;maxDocumentsPerSubmission=10;maxAggregateSubmissionBytes=20971520;incompleteAttentionHours=24}
    }
    Require ($source.StatusCode -eq 201) 'Created disposable Unit 24 source'
    $SourceId=[string]$source.Body.sourceId
    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    $submission=InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" (SourceHeaders $SourceId 'u24-producer') @{
        sourceSubmissionId="u24-$Suffix";expectedDocumentCount=1;expectedDocuments=@(@{sourceDocumentId="u24-doc-$Suffix"})
        metadata=@{contractNumber='240024000001';memberName='Unit 24 Recovery'}
    }
    Require ($submission.StatusCode -eq 201) 'Created Unit 24 recovery submission'
    $SubmissionId=[string]$submission.Body.submissionId

    $uploadHeaders=SourceHeaders $SourceId 'u24-producer'
    $uploadHeaders['X-SDV-Source-Document-Id']="u24-doc-$Suffix"
    $uploadHeaders['X-SDV-Filename']='business-viewer-two-page.pdf'
    $upload=Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" -Method Post -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 30
    $uploadBody=$upload.Content|ConvertFrom-Json
    Require ([int]$upload.StatusCode -eq 201 -and $uploadBody.currentStatus -eq 'AVAILABLE') 'Recovery fixture reached AVAILABLE'
    $DocumentId=[string]$uploadBody.documentId
    $oldObjectKey=DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    $oldVolumeId=DbScalar "select storage_volume_id from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    $oldContentSha=DbScalar "select content_sha256 from documents where tenant_id='$Tenant' and document_id='$DocumentId';"
    $OldCiphertextPath=Resolve-StorageObjectPath $oldVolumeId $oldObjectKey
    Require (Test-Path -LiteralPath $OldCiphertextPath -PathType Leaf) 'Original ciphertext exists at its committed storage-volume root before induced redaction interruption'

    $request=@{reason='Unit 24 crash-recovery proof';confirmed=$true;regions=@(@{pageNumber=1;x=0.08;y=0.06;width=0.72;height=0.10})}
    $lock=[System.IO.File]::Open($OldCiphertextPath,[System.IO.FileMode]::Open,[System.IO.FileAccess]::Read,[System.IO.FileShare]::Read)
    try {
        $failedRedaction=InvokeJson 'POST' "$Base/api/v1/business/documents/$DocumentId/redactions" $steward $request
    } finally {
        $lock.Dispose()
    }

    # Capture the staged replacement object before asserting the expected HTTP result so
    # cleanup can remove this test-owned ciphertext even if a later assertion fails.
    $pendingCount=DbScalar "select count(*) from document_redaction_operations where tenant_id='$Tenant' and document_id='$DocumentId';"
    if($pendingCount -eq '1'){
        $replacementVolumeId=DbScalar "select new_storage_volume_id from document_redaction_operations where tenant_id='$Tenant' and document_id='$DocumentId';"
        $replacementObjectKey=DbScalar "select new_object_key from document_redaction_operations where tenant_id='$Tenant' and document_id='$DocumentId';"
        $ReplacementCiphertextPath=Resolve-StorageObjectPath $replacementVolumeId $replacementObjectKey
    }
    Require ($failedRedaction.StatusCode -eq 422 -and [string]$failedRedaction.Body.code -eq 'REDACTION_FAILED') 'Forced prior-ciphertext deletion failure leaves redaction safely incomplete'
    Require (-not [string]::IsNullOrWhiteSpace([string]$ReplacementCiphertextPath) -and (Test-Path -LiteralPath $ReplacementCiphertextPath -PathType Leaf)) 'Interrupted redaction retains the staged replacement ciphertext for safe recovery'
    Require ((DbScalar "select status from documents where tenant_id='$Tenant' and document_id='$DocumentId';") -eq 'REDACTION_PROCESSING') 'Interrupted redaction remains in durable REDACTION_PROCESSING state'
    Require ((DbScalar "select count(*) from document_redaction_operations where tenant_id='$Tenant' and document_id='$DocumentId';") -eq '1') 'Interrupted redaction retains one durable staged operation'
    Require ((DbScalar "select count(*) from document_redaction_operations where tenant_id='$Tenant' and document_id='$DocumentId' and recovery_claimed_at is null and recovery_claim_token is null;") -eq '1') 'Handled failure releases its owned completion lease for safe retry/recovery'

    $Node2Process=Start-BackendInstance 8281 'backend-node2-u24'
    Require ((InvokeJson 'GET' "$Node2Base/api/v1/session" $business $null).StatusCode -eq 200) 'Second active node is healthy before recovery race'

    DbExec "update document_redaction_operations set created_at=current_timestamp - interval '11 minutes', recovery_claimed_at=null, recovery_claim_token=null where tenant_id='$Tenant' and document_id='$DocumentId';"
    $available=$false
    for($i=0;$i -lt 30;$i++){
        if((DbScalar "select status from documents where tenant_id='$Tenant' and document_id='$DocumentId';") -eq 'AVAILABLE'){$available=$true;break}
        Start-Sleep -Seconds 1
    }
    Require $available 'Bounded background reconciliation completes stale REDACTION_PROCESSING work'
    $newContentSha=DbScalar "select content_sha256 from documents where tenant_id='$Tenant' and document_id='$DocumentId';"
    Require ($newContentSha -ne $oldContentSha) 'Recovered redaction commits the sanitized replacement fingerprint'
    Require (-not (Test-Path -LiteralPath $OldCiphertextPath)) 'Recovered redaction permanently removes prior ciphertext from its committed storage volume'
    $committedReplacementObjectKey=DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    $committedReplacementVolumeId=DbScalar "select storage_volume_id from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    $committedReplacementPath=Resolve-StorageObjectPath $committedReplacementVolumeId $committedReplacementObjectKey
    Require (([System.IO.Path]::GetFullPath($committedReplacementPath)) -eq ([System.IO.Path]::GetFullPath($ReplacementCiphertextPath))) 'Recovered document commits the exact staged replacement ciphertext object'
    Require (Test-Path -LiteralPath $committedReplacementPath -PathType Leaf) 'Committed replacement ciphertext remains present after recovery switch'
    Require ((DbScalar "select count(*) from document_redaction_operations where tenant_id='$Tenant' and document_id='$DocumentId';") -eq '0') 'Recovered redaction removes pending operation after successful switch'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant' and action='DOCUMENT_PERMANENTLY_REDACTED' and target_id='$DocumentId' and outcome='SUCCESS';") -eq '1') 'Competing active nodes produce exactly one successful redaction completion audit'
    Require ((InvokeJson 'GET' "$Node2Base/api/v1/business/documents/$DocumentId/viewer" $business $null).StatusCode -eq 200) 'Recovered document is securely viewable through the second node'

    Write-Host ''
    Write-Host '=== PHASE 4 - Bounded concurrent read/decrypt load ==='
    $jobs=@()
    for($worker=1;$worker -le 4;$worker++){
        $targetBase=if(($worker % 2) -eq 0){$Node2Base}else{$Base}
        $jobs+=Start-Job -ScriptBlock {
            param($BaseUrl,$TenantId,$DocId,$Worker)
            $headers=@{'X-SDV-Tenant-Id'=$TenantId;'X-SDV-Subject'="u24-load-$Worker";'X-SDV-Roles'='BUSINESS_USER'}
            for($n=1;$n -le 5;$n++){
                $sw=[System.Diagnostics.Stopwatch]::StartNew()
                try {
                    $r=Invoke-WebRequest -Uri "$BaseUrl/api/v1/business/documents/$DocId/viewer/pages/1?scale=1.0" -Headers $headers -UseBasicParsing -TimeoutSec 30
                    $sw.Stop()
                    [PSCustomObject]@{Ok=([int]$r.StatusCode -eq 200 -and [int]$r.RawContentLength -gt 100);Ms=$sw.ElapsedMilliseconds;Node=$BaseUrl;Error=$null}
                } catch {
                    $sw.Stop(); [PSCustomObject]@{Ok=$false;Ms=$sw.ElapsedMilliseconds;Node=$BaseUrl;Error=$_.Exception.Message}
                }
            }
        } -ArgumentList $targetBase,$Tenant,$DocumentId,$worker
    }
    $null=Wait-Job -Job $jobs -Timeout 90
    $unfinished=@($jobs|Where-Object {$_.State -notin @('Completed','Failed','Stopped')})
    if($unfinished.Count -gt 0){ $unfinished|Stop-Job -ErrorAction SilentlyContinue }
    $loadResults=@($jobs|Receive-Job)
    $jobs|Remove-Job -Force -ErrorAction SilentlyContinue
    Require ($unfinished.Count -eq 0) 'Concurrent load workers complete within the bounded 90-second window'
    Require ($loadResults.Count -eq 20) 'Concurrent load completed all 20 authenticated decrypt/render requests across both active nodes'
    $failedLoad=@($loadResults|Where-Object {-not $_.Ok})
    if($failedLoad.Count -gt 0){
        $failedLoad|ForEach-Object { Write-Host "  [INFO] Failed load request node=$($_.Node) elapsedMs=$($_.Ms) error=$($_.Error)" }
    }
    Require ($failedLoad.Count -eq 0) 'Concurrent decrypt/render load completed without request failures on either active node'
    $times=@($loadResults|ForEach-Object {[long]$_.Ms}|Sort-Object)
    $p95Index=[int][Math]::Max(0,[Math]::Ceiling($times.Count*0.95)-1)
    $p95=[long]$times[$p95Index]
    Write-Host "  [INFO] Concurrent viewer page P95: ${p95} ms"
    Require ($p95 -lt 15000) 'Concurrent small-document decrypt/render P95 remains within the local acceptance ceiling'
    Require ((Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Primary backend remains healthy after concurrent load'
    Require ((Invoke-RestMethod -Uri "$Node2Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Second backend remains healthy after concurrent load'

    Write-Host ''
    Write-Host '=== PHASE 5 - Cadence, bounds and frozen security ==='
    $schedulerSource=Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-web\src\main\java\com\sdv\platform\web\redaction\RedactionRecoveryScheduler.java') -Raw
    Require ($schedulerSource -match 'SWEEP_LIMIT = 20' -and $schedulerSource -match 'recovery-sweep-delay-ms:60000') 'Redaction reconciliation is bounded and defaults to a one-minute cadence'
    $repoSource=Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-persistence\src\main\java\com\sdv\platform\persistence\redaction\JdbcDocumentRedactionRepository.java') -Raw
    Require ($repoSource -match 'skip locked' -and $repoSource -match 'current_timestamp' -and $repoSource -match 'recovery_claim_token') 'Recovery claim uses DB-time, ownership token and SKIP LOCKED multi-node coordination'
    Require ((InvokeJson 'GET' "$Base/api/v1/operations/summary" @{} $null).StatusCode -eq 401) 'Unauthenticated operational detail remains denied'
    Require ((InvokeJson 'GET' "$Base/api/v1/operations/summary" $business $null).StatusCode -eq 403) 'BUSINESS_USER still cannot access technical operations detail'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Enterprise / Operational Readiness Checkpoint E (Unit 24) acceptance passed.' -ForegroundColor Green
}
finally {
    Stop-Owned $Node2Process 'backend-node2-u24'

    try {
        $held=(DbScalar "select count(*) from information_schema.tables where table_name='$HeldTableName';")
        if($held -eq '1'){ DbExec "alter table $HeldTableName rename to document_redaction_operations;" }
    } catch { Write-Warning "Unit 24 table-restore cleanup failed: $($_.Exception.Message)" }

    try {
        # Remove only exact ciphertext objects created/owned by this Unit 24 run. This
        # matters when placement selected an already-existing ACTIVE volume outside
        # the Unit 24 runtime directory.
        foreach($ownedPath in @($OldCiphertextPath,$ReplacementCiphertextPath)){
            if(-not [string]::IsNullOrWhiteSpace([string]$ownedPath)){ Remove-Item -LiteralPath $ownedPath -Force -ErrorAction SilentlyContinue }
        }
        if(-not [string]::IsNullOrWhiteSpace([string]$SourceId)){ CleanupSource $SourceId; Ok 'Disposable Unit 24 source/submission/document data removed' }
        if(-not [string]::IsNullOrWhiteSpace([string]$VolumeId)){ DbExec "delete from storage_volumes where tenant_id='$Tenant' and volume_id='$VolumeId';" }
    } catch { Write-Warning "Unit 24 data cleanup failed: $($_.Exception.Message)" }

    Remove-Item -LiteralPath $Runtime -Recurse -Force -ErrorAction SilentlyContinue
    if($null -eq $previousRecoveryInitial){Remove-Item Env:SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS -ErrorAction SilentlyContinue}else{$env:SDV_REDACTION_RECOVERY_INITIAL_DELAY_MS=$previousRecoveryInitial}
    if($null -eq $previousRecoverySweep){Remove-Item Env:SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS -ErrorAction SilentlyContinue}else{$env:SDV_REDACTION_RECOVERY_SWEEP_DELAY_MS=$previousRecoverySweep}
    try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
}
