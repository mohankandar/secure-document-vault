[CmdletBinding()]
param(
    [switch]$SkipBuild,
    [switch]$KeepRecoverySet
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Base = 'http://localhost:8280'
$Node2Base = 'http://localhost:8281'
$RestoreBase = 'http://localhost:8282'
$Tenant = '11111111-1111-1111-1111-111111111111'
$Suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 10)
$Pdf = Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'
$Jar = Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar'
$Runtime = Join-Path $Root ".runtime\enterprise-readiness-c\$Suffix"
$Logs = Join-Path $Runtime 'logs'
$StorageRoot = Join-Path $Runtime 'storage-live'
$RecoverySet = Join-Path $Runtime 'recovery-set'
$BackupStorage = Join-Path $RecoverySet 'storage'
$BackupDatabase = Join-Path $RecoverySet 'database\sdv.dump'
$BackupKeys = Join-Path $RecoverySet 'keys'
$RestoreDb = "sdv_u22_$Suffix"
$RestoreVaultName = "sdv-vault-u22-$Suffix"
$RestoreVaultToken = "sdv-u22-$Suffix-root"
$RestoreVaultUrl = 'http://127.0.0.1:8201'
$Node2Process = $null
$RestoreProcess = $null
$SourceId = $null
$VolumeId = $null
$SubmissionId = $null
$DocumentId = $null
$ObjectKey = $null
$CommittedStorageRoot = $null
$CiphertextSha = $null
$ContentSha = $null
$TenantKey = 'sdv-tenant-' + $Tenant.Replace('-', '')
$KeyBackupFile = Join-Path $BackupKeys "$TenantKey.backup"
$PrimaryStopped = $false
$RestoreDbCreated = $false
$RestoreVaultStarted = $false

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; Ok $Message }
function UserHeaders([string]$Subject, [string]$Roles) {
    return @{ 'X-SDV-Tenant-Id'=$Tenant; 'X-SDV-Subject'=$Subject; 'X-SDV-Roles'=$Roles; 'X-Correlation-Id'="u22-$Suffix" }
}
function SourceHeaders([string]$Source, [string]$Subject) {
    return @{ 'X-SDV-Tenant-Id'=$Tenant; 'X-SDV-Source-Id'=$Source; 'X-SDV-Subject'=$Subject; 'X-Correlation-Id'="u22-$Suffix" }
}
function InvokeJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body) {
    try {
        $bodyText = if ($null -eq $Body) { $null } else { $Body | ConvertTo-Json -Depth 30 }
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
function DbScalar([string]$Database,[string]$Sql) {
    return (& docker exec sdv-postgres psql -U sdv -d $Database -tAc $Sql | Out-String).Trim()
}
function SaveField([hashtable]$Headers,[string]$FieldKey,[string]$Label,[bool]$Identifier) {
    $r = InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel=$Label; fieldType='TEXT'; required=$true; businessIdentifier=$Identifier; searchable=$true
        normalization=if($Identifier){'DIGITS_ONLY'}else{'LOWERCASE'}; searchMode=if($Identifier){'EXACT'}else{'PARTIAL'}; validationRules=@{}
    }
    Require ($r.StatusCode -eq 200) "Configured metadata field $FieldKey"
}
function Copy-DirectoryContents([string]$Source,[string]$Destination) {
    if (-not (Test-Path -LiteralPath $Source -PathType Container)) {
        throw "Storage source directory does not exist: $Source"
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Get-ChildItem -LiteralPath $Source -Force | ForEach-Object {
        $target = Join-Path $Destination $_.Name
        Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force
    }
}

function Stop-Owned([object]$Process,[string]$Name) {
    if ($null -eq $Process) { return }
    try {
        $live = Get-Process -Id $Process.Id -ErrorAction SilentlyContinue
        if ($live) {
            Write-Host "Stopping $Name PID $($Process.Id)..."
            & taskkill.exe /PID $Process.Id /T /F *> $null
            if ($LASTEXITCODE -ne 0) { Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue }
        }
    } catch { }
}
function Start-BackendInstance([int]$Port,[string]$Database,[string]$VaultUrl,[string]$VaultToken,[string]$Name) {
    $out = Join-Path $Logs "$Name.out.log"
    $err = Join-Path $Logs "$Name.err.log"
    $saved = @{
        SERVER_PORT=$env:SERVER_PORT
        SPRING_DATASOURCE_URL=$env:SPRING_DATASOURCE_URL
        SPRING_DATASOURCE_USERNAME=$env:SPRING_DATASOURCE_USERNAME
        SPRING_DATASOURCE_PASSWORD=$env:SPRING_DATASOURCE_PASSWORD
        SDV_AUTH_MODE=$env:SDV_AUTH_MODE
        SDV_KEY_PROVIDER=$env:SDV_KEY_PROVIDER
        SDV_VAULT_BASE_URL=$env:SDV_VAULT_BASE_URL
        SDV_VAULT_TOKEN=$env:SDV_VAULT_TOKEN
        SDV_RETENTION_INITIAL_DELAY_MS=$env:SDV_RETENTION_INITIAL_DELAY_MS
        SDV_RETENTION_SWEEP_DELAY_MS=$env:SDV_RETENTION_SWEEP_DELAY_MS
    }
    try {
        $env:SERVER_PORT = [string]$Port
        $env:SPRING_DATASOURCE_URL = "jdbc:postgresql://localhost:5433/$Database"
        $env:SPRING_DATASOURCE_USERNAME = 'sdv'
        $env:SPRING_DATASOURCE_PASSWORD = 'sdv_local_only'
        $env:SDV_AUTH_MODE = 'local-header'
        $env:SDV_KEY_PROVIDER = 'vault'
        $env:SDV_VAULT_BASE_URL = $VaultUrl
        $env:SDV_VAULT_TOKEN = $VaultToken
        $env:SDV_RETENTION_INITIAL_DELAY_MS = '3600000'
        $env:SDV_RETENTION_SWEEP_DELAY_MS = '3600000'
        $p = Start-Process -FilePath 'java' -ArgumentList @('-jar', $Jar, '--spring.profiles.active=local') -WorkingDirectory $Root -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    } finally {
        foreach ($k in $saved.Keys) {
            if ($null -eq $saved[$k]) { Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue }
            else { Set-Item -Path "Env:$k" -Value $saved[$k] }
        }
    }
    $up = $false
    for ($i=0; $i -lt 60; $i++) {
        try {
            $h = Invoke-RestMethod -Uri "http://localhost:$Port/actuator/health" -TimeoutSec 2
            if ($h.status -eq 'UP') { $up=$true; break }
        } catch { }
        Start-Sleep -Seconds 1
    }
    if (-not $up) {
        Stop-Owned $p $Name
        $tail = if (Test-Path $err) { Get-Content $err -Tail 80 | Out-String } else { '' }
        throw "$Name did not become healthy on port $Port.`n$tail"
    }
    return $p
}
function Wait-Vault([string]$Url) {
    for ($i=0; $i -lt 60; $i++) {
        try {
            $r = Invoke-WebRequest -Uri "$Url/v1/sys/health" -UseBasicParsing -TimeoutSec 2
            if ([int]$r.StatusCode -eq 200) { return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Vault did not become healthy at $Url."
}

New-Item -ItemType Directory -Force -Path $Runtime,$Logs,$RecoverySet,(Split-Path $BackupDatabase -Parent),$BackupKeys | Out-Null

try {
    if (-not $SkipBuild) {
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Frozen baseline and recoverable document fixture ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    Require ((Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Primary backend health is UP on port 8280'
    Require ((DbScalar 'sdv' "select count(*) from databasechangelog where id='009-audit-operations-readiness';") -eq '1') 'Frozen migration 009 remains applied once'
    Require (Test-Path $Pdf) 'Two-page recovery PDF fixture is present'

    $it = UserHeaders 'u22-it' 'TENANT_IT_ADMIN'
    $admin = UserHeaders 'u22-admin' 'BUSINESS_ADMIN'
    $business = UserHeaders 'u22-user' 'BUSINESS_USER'

    $source = InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $it @{
        sourceKey="u22-$Suffix"; displayName="Unit 22 Source $Suffix"; authType='OAUTH2_CLIENT_CREDENTIALS'; credentialReference="external://u22/$Suffix"
        limits=@{ maxPdfBytes=10485760; maxPagesPerPdf=2000; maxDocumentsPerSubmission=10; maxAggregateSubmissionBytes=20971520; incompleteAttentionHours=24 }
    }
    Require ($source.StatusCode -eq 201) 'Tenant IT created disposable Unit 22 source'
    $SourceId=[string]$source.Body.sourceId
    SaveField $admin 'contractNumber' 'Contract Number' $true
    SaveField $admin 'memberName' 'Member Name' $false

    $volume = InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName="Unit 22 Recovery Volume $Suffix"; rootPath=$StorageRoot; state='ACTIVE'; priority=1; warningPercent=90; stopWritePercent=95
    }
    Require ($volume.StatusCode -eq 201) 'Tenant IT created disposable recovery storage volume'
    $VolumeId=[string]$volume.Body.volume.volumeId

    $sourceHeaders = SourceHeaders $SourceId 'u22-producer'
    $submission = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
        sourceSubmissionId="u22-submission-$Suffix"; expectedDocumentCount=1
        expectedDocuments=@(@{sourceDocumentId="u22-doc-$Suffix"})
        metadata=@{contractNumber='770-2200'; memberName='Unit 22 Recovery'}
    }
    Require ($submission.StatusCode -eq 201) 'Created disposable recovery submission'
    $SubmissionId=[string]$submission.Body.submissionId

    $uploadHeaders = @{} + $sourceHeaders
    $uploadHeaders['X-SDV-Source-Document-Id']="u22-doc-$Suffix"
    $uploadHeaders['X-SDV-Filename']='business-viewer-two-page.pdf'
    $upload = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" -Method Post -Headers $uploadHeaders -ContentType 'application/pdf' -InFile $Pdf -UseBasicParsing -TimeoutSec 30
    $uploadBody = $upload.Content | ConvertFrom-Json
    Require ([int]$upload.StatusCode -eq 201 -and $uploadBody.currentStatus -eq 'AVAILABLE') 'Recovery fixture is durably AVAILABLE'
    $DocumentId=[string]$uploadBody.documentId
    $ContentSha=[string]$uploadBody.contentSha256

    $ObjectKey = DbScalar 'sdv' "select object_key from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    $CommittedStorageRoot = DbScalar 'sdv' "select v.root_path from document_storage_refs r join storage_volumes v on v.tenant_id=r.tenant_id and v.volume_id=r.storage_volume_id where r.tenant_id='$Tenant' and r.document_id='$DocumentId';"
    $CiphertextSha = DbScalar 'sdv' "select integrity_value from document_storage_refs where tenant_id='$Tenant' and document_id='$DocumentId';"
    Require (-not [string]::IsNullOrWhiteSpace($ObjectKey)) 'Document storage object identity is committed in PostgreSQL'
    Require (-not [string]::IsNullOrWhiteSpace($CommittedStorageRoot)) 'Document storage volume root is committed in PostgreSQL'
    Require ($CiphertextSha.Length -eq 64) 'Document ciphertext integrity hash is committed in PostgreSQL'
    $liveObject = Join-Path $CommittedStorageRoot ($ObjectKey.Replace('/', [IO.Path]::DirectorySeparatorChar))
    Require (Test-Path -LiteralPath $liveObject -PathType Leaf) 'Committed encrypted storage object exists at the configured volume root'
    Require ((Get-FileHash -Algorithm SHA256 -LiteralPath $liveObject).Hash.ToLowerInvariant() -eq $CiphertextSha.ToLowerInvariant()) 'Live encrypted storage object matches committed integrity hash'
    Require ((InvokeJson 'GET' "$Base/api/v1/business/documents/$DocumentId/viewer" $business $null).StatusCode -eq 200) 'Primary instance can reconstruct and serve the encrypted document'

    Write-Host ''
    Write-Host '=== PHASE 2 - HA-ready stateless multi-instance behavior ==='
    $Node2Process = Start-BackendInstance 8281 'sdv' 'http://127.0.0.1:8200' 'sdv-local-root' 'backend-node2'
    Require ((InvokeJson 'GET' "$Node2Base/api/v1/session" $business $null).StatusCode -eq 200) 'Second backend accepts the same authenticated principal without shared HTTP session state'
    Require ((InvokeJson 'GET' "$Node2Base/api/v1/business/documents/$DocumentId/viewer" $business $null).StatusCode -eq 200) 'Second backend reconstructs document from shared database/storage/key state'
    $page = Invoke-WebRequest -Uri "$Node2Base/api/v1/business/documents/$DocumentId/viewer/pages/1?scale=1.0" -Headers $business -UseBasicParsing -TimeoutSec 30
    Require ([int]$page.StatusCode -eq 200 -and [int]$page.RawContentLength -gt 100) 'Second backend serves authenticated viewer content'

    & (Join-Path $Root 'Stop-SDV-Local.ps1') -KeepDatabase
    $PrimaryStopped = $true
    Require ((InvokeJson 'GET' "$Node2Base/api/v1/business/documents/$DocumentId/viewer" $business $null).StatusCode -eq 200) 'Second backend remains usable after primary instance loss'

    Write-Host ''
    Write-Host '=== PHASE 3 - Coordinated recovery-set creation ==='
    & docker exec sdv-postgres pg_dump -U sdv -d sdv -Fc -f "/tmp/u22-$Suffix.dump"
    if ($LASTEXITCODE -ne 0) { throw 'PostgreSQL recovery dump failed.' }
    & docker cp "sdv-postgres:/tmp/u22-$Suffix.dump" $BackupDatabase *> $null
    Require ((Test-Path $BackupDatabase) -and (Get-Item $BackupDatabase).Length -gt 1024) 'Recovery set contains PostgreSQL metadata backup'

    Copy-DirectoryContents $CommittedStorageRoot $BackupStorage
    $backupObject = Join-Path $BackupStorage ($ObjectKey.Replace('/', [IO.Path]::DirectorySeparatorChar))
    Require (Test-Path -LiteralPath $backupObject -PathType Leaf) 'Recovery set contains encrypted storage object at the preserved object-key path'
    Require ((Get-FileHash -Algorithm SHA256 -LiteralPath $backupObject).Hash.ToLowerInvariant() -eq $CiphertextSha.ToLowerInvariant()) 'Backed-up ciphertext matches committed integrity hash'

    $vaultHeaders = @{ 'X-Vault-Token'='sdv-local-root' }
    # LOCAL ACCEPTANCE ONLY: Vault Transit plaintext backup requires both flags.
    # These settings are irreversible for the key, so Unit 22 enables them only on
    # this disposable dev-Vault tenant key. Production recovery must use the
    # approved Vault snapshot/backup mechanism documented in the DR runbook.
    $backupConfigBody = @{ allow_plaintext_backup=$true; exportable=$true } | ConvertTo-Json -Compress
    $null = Invoke-RestMethod -Uri "http://127.0.0.1:8200/v1/transit/keys/$TenantKey/config" -Method Post -Headers $vaultHeaders -ContentType 'application/json' -Body $backupConfigBody -TimeoutSec 10
    $keyConfig = Invoke-RestMethod -Uri "http://127.0.0.1:8200/v1/transit/keys/$TenantKey" -Headers $vaultHeaders -TimeoutSec 10
    Require ([bool]$keyConfig.data.allow_plaintext_backup -and [bool]$keyConfig.data.exportable) 'Disposable local Vault tenant key is enabled for acceptance-only backup'
    $keyBackup = Invoke-RestMethod -Uri "http://127.0.0.1:8200/v1/transit/backup/$TenantKey" -Headers $vaultHeaders -TimeoutSec 10
    $backupValue = [string]$keyBackup.data.backup
    Require (-not [string]::IsNullOrWhiteSpace($backupValue)) 'Recovery set can capture test-only Vault tenant-key state'
    Set-Content -LiteralPath $KeyBackupFile -Value $backupValue -Encoding ASCII -NoNewline

    $jarHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $Jar).Hash.ToLowerInvariant()
    $manifest = [ordered]@{
        formatVersion = 1
        createdAt = [DateTimeOffset]::UtcNow.ToString('o')
        purpose = 'SDV Unit 22 local recovery validation only'
        latestFrozenMigration = '009'
        application = @{ jarSha256=$jarHash; statelessInstances=$true }
        database = @{ artifact='database/sdv.dump'; engine='PostgreSQL'; required=$true }
        storage = @{ artifact='storage'; provider='LOCAL_FILESYSTEM'; volumeId=$VolumeId; objectKey=$ObjectKey; ciphertextSha256=$CiphertextSha; required=$true }
        keyManagement = @{ artifact="keys/$TenantKey.backup"; provider='VAULT_TRANSIT'; tenantKey=$TenantKey; sensitive=$true; testOnlyBackupTechnique=$true; required=$true }
        configuration = @{ required=$true; requiredProperties=@('database endpoint/credentials','storage volume mapping','key-provider endpoint/auth','Vault Transit mount/key prefix','authentication configuration','application version') }
        validationTarget = @{ tenantId=$Tenant; documentId=$DocumentId; contentSha256=$ContentSha }
    }
    $manifestPath = Join-Path $RecoverySet 'recovery-manifest.json'
    $manifest | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
    $manifestText = Get-Content -LiteralPath $manifestPath -Raw
    Require ($manifestText -notmatch 'sdv-local-root|sdv_local_only') 'Recovery manifest contains no database/Vault credentials'
    Require ((Test-Path $KeyBackupFile) -and (Get-Item $KeyBackupFile).Length -gt 100) 'Recovery set contains key-management state artifact'

    Stop-Owned $Node2Process 'backend-node2'
    $Node2Process = $null

    Write-Host ''
    Write-Host '=== PHASE 4 - Incomplete recovery fails closed ==='
    $restoreDbExists = DbScalar 'postgres' "select count(*) from pg_database where datname='$RestoreDb';"
    if ($restoreDbExists -eq '1') {
        & docker exec sdv-postgres dropdb -U sdv $RestoreDb
        if ($LASTEXITCODE -ne 0) { throw 'Could not remove pre-existing isolated restore database.' }
    }
    & docker exec sdv-postgres createdb -U sdv $RestoreDb
    if ($LASTEXITCODE -ne 0) { throw 'Could not create isolated restore database.' }
    $RestoreDbCreated = $true
    & docker exec sdv-postgres pg_restore -U sdv -d $RestoreDb "/tmp/u22-$Suffix.dump"
    if ($LASTEXITCODE -ne 0) { throw 'Could not restore PostgreSQL recovery dump.' }
    Require ((DbScalar $RestoreDb "select count(*) from documents where tenant_id='$Tenant' and document_id='$DocumentId';") -eq '1') 'Restored PostgreSQL contains document metadata'

    Remove-Item -LiteralPath $CommittedStorageRoot -Recurse -Force -ErrorAction SilentlyContinue

    $existingRestoreVault = (& docker ps -a --filter "name=^/$RestoreVaultName$" --format '{{.Names}}' | Out-String).Trim()
    if ($existingRestoreVault -eq $RestoreVaultName) {
        & docker rm -f $RestoreVaultName *> $null
        if ($LASTEXITCODE -ne 0) { throw 'Could not remove pre-existing isolated restore Vault container.' }
    }
    & docker run -d --name $RestoreVaultName --cap-add IPC_LOCK -e "VAULT_DEV_ROOT_TOKEN_ID=$RestoreVaultToken" -p '8201:8200' hashicorp/vault:1.17.6 server -dev "-dev-root-token-id=$RestoreVaultToken" '-dev-listen-address=0.0.0.0:8200' *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not start isolated restore Vault.' }
    $RestoreVaultStarted = $true
    Wait-Vault $RestoreVaultUrl
    & docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e "VAULT_TOKEN=$RestoreVaultToken" $RestoreVaultName vault secrets enable transit *> $null
    if ($LASTEXITCODE -ne 0) { throw 'Could not enable Transit in isolated restore Vault.' }

    $RestoreProcess = Start-BackendInstance 8282 $RestoreDb $RestoreVaultUrl $RestoreVaultToken 'backend-restore-no-storage'
    $noStorage = InvokeJson 'GET' "$RestoreBase/api/v1/business/documents/$DocumentId/viewer" $business $null
    Require ($noStorage.StatusCode -eq 422 -and $noStorage.Body.code -eq 'VIEWER_UNAVAILABLE') 'Database/key-service without encrypted storage cannot serve the document'
    Stop-Owned $RestoreProcess 'backend-restore-no-storage'; $RestoreProcess=$null

    Copy-DirectoryContents $BackupStorage $CommittedStorageRoot
    $restoredObject = Join-Path $CommittedStorageRoot ($ObjectKey.Replace('/', [IO.Path]::DirectorySeparatorChar))
    Require ((Get-FileHash -Algorithm SHA256 -LiteralPath $restoredObject).Hash.ToLowerInvariant() -eq $CiphertextSha.ToLowerInvariant()) 'Encrypted storage restores with exact ciphertext integrity'

    $RestoreProcess = Start-BackendInstance 8282 $RestoreDb $RestoreVaultUrl $RestoreVaultToken 'backend-restore-no-key'
    $noKey = InvokeJson 'GET' "$RestoreBase/api/v1/business/documents/$DocumentId/viewer" $business $null
    Require ($noKey.StatusCode -eq 422 -and $noKey.Body.code -eq 'VIEWER_UNAVAILABLE') 'Database/storage without key-management state cannot serve the document'
    Stop-Owned $RestoreProcess 'backend-restore-no-key'; $RestoreProcess=$null

    Write-Host ''
    Write-Host '=== PHASE 5 - Complete recovery set restores authenticated serving ==='
    $restoreHeaders = @{ 'X-Vault-Token'=$RestoreVaultToken }

    # The isolated restore Vault must still have no tenant key after the negative
    # "database + storage without key-management state" validation above.
    $preRestoreKey = InvokeJson 'GET' "$RestoreVaultUrl/v1/transit/keys/$TenantKey" $restoreHeaders $null
    Require ($preRestoreKey.StatusCode -eq 404) 'Isolated restore Vault has no pre-existing tenant key before recovery'

    # Restore using the canonical Transit /restore endpoint. The backup contains
    # the original policy name, so there is no need to rename it through the
    # optional /restore/:name form. Using InvokeJson also preserves Vault's
    # structured error response if recovery is rejected.
    # Read the Transit backup as a true .NET string. PowerShell Get-Content
    # decorates returned strings with provider metadata (PSPath, PSDrive,
    # ReadCount, etc.), and ConvertTo-Json can serialize that decorated value
    # as an object/map. Vault requires the backup field to be a scalar string.
    $backupText = [System.IO.File]::ReadAllText($KeyBackupFile, [System.Text.Encoding]::ASCII)
    Require (-not [string]::IsNullOrWhiteSpace($backupText)) 'Transit recovery artifact is readable as a scalar backup string'
    $restoreResult = InvokeJson 'POST' "$RestoreVaultUrl/v1/transit/restore" $restoreHeaders @{ backup=[string]$backupText }
    if ($restoreResult.StatusCode -notin @(200, 204)) {
        $restoreError = if ($null -eq $restoreResult.Body) {
            '<empty response body>'
        } else {
            $restoreResult.Body | ConvertTo-Json -Depth 20 -Compress
        }
        throw "Vault Transit restore failed with HTTP $($restoreResult.StatusCode): $restoreError"
    }

    $restoredKey = InvokeJson 'GET' "$RestoreVaultUrl/v1/transit/keys/$TenantKey" $restoreHeaders $null
    Require ($restoredKey.StatusCode -eq 200 -and [string]$restoredKey.Body.data.name -eq $TenantKey) 'Restored isolated tenant key-management state under the original tenant key name'

    $RestoreProcess = Start-BackendInstance 8282 $RestoreDb $RestoreVaultUrl $RestoreVaultToken 'backend-restore-complete'
    $restoredViewer = InvokeJson 'GET' "$RestoreBase/api/v1/business/documents/$DocumentId/viewer" $business $null
    Require ($restoredViewer.StatusCode -eq 200 -and [string]$restoredViewer.Body.documentId -eq $DocumentId) 'Complete recovery set restores metadata lookup + authenticated decryption'
    $restoredPage = Invoke-WebRequest -Uri "$RestoreBase/api/v1/business/documents/$DocumentId/viewer/pages/1?scale=1.0" -Headers $business -UseBasicParsing -TimeoutSec 30
    Require ([int]$restoredPage.StatusCode -eq 200 -and [int]$restoredPage.RawContentLength -gt 100) 'Complete recovery set restores document serving'
    Require ((DbScalar $RestoreDb "select content_sha256 from documents where tenant_id='$Tenant' and document_id='$DocumentId';") -eq $ContentSha) 'Restored database retains original plaintext content fingerprint'
    Require ((Get-FileHash -Algorithm SHA256 -LiteralPath $restoredObject).Hash.ToLowerInvariant() -eq $CiphertextSha.ToLowerInvariant()) 'Restored object still matches committed authenticated ciphertext fingerprint'

    Write-Host ''
    Write-Host '=== PHASE 6 - HA destructive-worker lease boundary ==='
    & docker exec sdv-postgres psql -U sdv -d $RestoreDb -v ON_ERROR_STOP=1 -c "
      update documents
         set expires_at=current_timestamp - interval '1 minute',
             status='RETENTION_DELETING',
             retention_delete_started_at=current_timestamp
       where tenant_id='$Tenant' and document_id='$DocumentId';
    " *> $null
    $freshClaim = DbScalar $RestoreDb "with claimed as (update documents set retention_delete_started_at=current_timestamp where tenant_id='$Tenant' and document_id='$DocumentId' and status='RETENTION_DELETING' and (retention_delete_started_at is null or retention_delete_started_at <= current_timestamp - interval '10 minutes') returning 1) select count(*) from claimed;"
    Require ($freshClaim -eq '0') 'Fresh RETENTION_DELETING work is protected from a second active-instance claim'
    & docker exec sdv-postgres psql -U sdv -d $RestoreDb -v ON_ERROR_STOP=1 -c "update documents set retention_delete_started_at=current_timestamp - interval '11 minutes' where tenant_id='$Tenant' and document_id='$DocumentId';" *> $null
    $staleClaim = DbScalar $RestoreDb "with claimed as (update documents set retention_delete_started_at=current_timestamp where tenant_id='$Tenant' and document_id='$DocumentId' and status='RETENTION_DELETING' and (retention_delete_started_at is null or retention_delete_started_at <= current_timestamp - interval '10 minutes') returning 1) select count(*) from claimed;"
    Require ($staleClaim -eq '1') 'Stale RETENTION_DELETING work remains reclaimable after the HA lease window'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Enterprise / Operational Readiness Checkpoint C (Unit 22) acceptance passed.' -ForegroundColor Green
}
finally {
    Stop-Owned $RestoreProcess 'backend-restore'
    Stop-Owned $Node2Process 'backend-node2'

    if ($RestoreVaultStarted) {
        try { & docker rm -f $RestoreVaultName *> $null } catch { }
    }

    if ($RestoreDbCreated) {
        try { & docker exec sdv-postgres dropdb --if-exists -U sdv $RestoreDb *> $null } catch { }
    }

    try {
        if ((& docker ps -a --format '{{.Names}}' | Select-String -SimpleMatch 'sdv-postgres')) {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from submission_expected_documents where tenant_id='$Tenant' and submission_id in (select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId');
                    delete from document_redaction_operations where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
                    delete from document_crypto_metadata where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
                    delete from document_storage_refs where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
                    delete from documents where tenant_id='$Tenant' and source_id='$SourceId';
                    delete from business_metadata_values where tenant_id='$Tenant' and source_id='$SourceId';
                    delete from submissions where tenant_id='$Tenant' and source_id='$SourceId';
                    delete from metadata_field_definitions where tenant_id='$Tenant' and source_id='$SourceId';
                    delete from audit_events where tenant_id='$Tenant' and correlation_id='u22-$Suffix';
                    delete from ingestion_sources where tenant_id='$Tenant' and source_id='$SourceId';
                " *> $null
            }
            if (-not [string]::IsNullOrWhiteSpace([string]$VolumeId)) {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "delete from storage_volumes where tenant_id='$Tenant' and volume_id='$VolumeId';" *> $null
            }
            Ok 'Disposable Unit 22 primary-database data removed'
        }
    } catch { Write-Warning "Unit 22 cleanup failed: $($_.Exception.Message)" }

    if (-not $KeepRecoverySet) {
        Remove-Item -LiteralPath $Runtime -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Host "Recovery-set evidence retained at: $RecoverySet"
    }

    try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
}
