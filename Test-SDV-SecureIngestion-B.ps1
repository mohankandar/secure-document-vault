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
$SubmissionId = $null
$VolumeId = $null
$Document1Id = $null
$Document2Id = $null
$Document3Id = $null
$StorageRoot = Join-Path $Root ".runtime\secure-ingestion-b\$Suffix"
$Pdf1 = Join-Path $Root ".runtime\secure-ingestion-b-$Suffix-1.pdf"
$Pdf2 = Join-Path $Root ".runtime\secure-ingestion-b-$Suffix-2.pdf"
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
        'X-Correlation-Id' = "ingest-b-$Suffix"
    }
}
function SourceHeaders([string]$Tenant, [string]$Source, [string]$Subject) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Source-Id' = $Source
        'X-SDV-Subject' = $Subject
        'X-Correlation-Id' = "ingest-b-$Suffix"
    }
}
function DbScalar([string]$Sql) {
    return (& docker exec sdv-postgres psql -U sdv -d sdv -tAc $Sql | Out-String).Trim()
}
function InvokeJson([string]$Method, [string]$Uri, [hashtable]$Headers, [object]$Body) {
    try {
        $r = Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body ($Body | ConvertTo-Json -Depth 12) -UseBasicParsing -TimeoutSec 20
        return [PSCustomObject]@{ StatusCode=[int]$r.StatusCode; Body=($r.Content | ConvertFrom-Json) }
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            return [PSCustomObject]@{ StatusCode=[int]$_.Exception.Response.StatusCode; Body=($content | ConvertFrom-Json) }
        }
        throw
    }
}
function UploadPdf([string]$Uri, [hashtable]$Headers, [string]$Path) {
    try {
        $r = Invoke-WebRequest -Uri $Uri -Method Post -Headers $Headers -ContentType 'application/pdf' -InFile $Path -UseBasicParsing -TimeoutSec 30
        return [PSCustomObject]@{ StatusCode=[int]$r.StatusCode; Body=($r.Content | ConvertFrom-Json) }
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            $reader = New-Object System.IO.StreamReader($_.Exception.Response.GetResponseStream())
            $content = $reader.ReadToEnd()
            $body = $null
            if (-not [string]::IsNullOrWhiteSpace($content)) { $body = $content | ConvertFrom-Json }
            return [PSCustomObject]@{ StatusCode=[int]$_.Exception.Response.StatusCode; Body=$body }
        }
        throw
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
    Write-Host '=== PHASE 1 - Runtime, migration 005 and real Vault-backed crypto ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP on port 8280'
    Require ((DbScalar "select count(*) from databasechangelog where id='005-secure-ingestion-checkpoint-b';") -eq '1') 'Checkpoint B migration applied once'
    $vaultSecrets = (& docker exec -e VAULT_ADDR=http://127.0.0.1:8200 -e VAULT_TOKEN=sdv-local-root sdv-vault vault secrets list -format=json | Out-String)
    Require ($vaultSecrets -match '"transit/"') 'Local acceptance runtime has Vault Transit enabled'
    Require ((DbScalar "select count(*) from databasechangelog where id='004-secure-ingestion-checkpoint-a';") -eq '1') 'Frozen Checkpoint A migration remains applied once'

    $it1 = UserHeaders $Tenant1 'checkpoint-b-it' 'TENANT_IT_ADMIN'
    $sourceCreate = @{
        sourceKey = "ingest-b-$Suffix"
        displayName = "Checkpoint B Source $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "vault://local/checkpoint-b/$Suffix"
        limits = @{
            maxPdfBytes = 1048576
            maxPagesPerPdf = 2000
            maxDocumentsPerSubmission = 3
            maxAggregateSubmissionBytes = 3145728
            incompleteAttentionHours = 24
        }
    }
    $source = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources" -Method Post -Headers $it1 -ContentType 'application/json' -Body ($sourceCreate | ConvertTo-Json -Depth 12) -TimeoutSec 10
    $SourceId = [string]$source.sourceId
    Require (-not [string]::IsNullOrWhiteSpace($SourceId)) 'Tenant IT created disposable ingestion source'

    $volume = Invoke-RestMethod -Uri "$Base/api/v1/admin/storage-volumes" -Method Post -Headers $it1 -ContentType 'application/json' -Body (@{
        displayName = "Checkpoint B Volume $Suffix"
        rootPath = $StorageRoot
        state = 'ACTIVE'
        priority = 1
    } | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $VolumeId = [string]$volume.volume.volumeId
    Require ((Test-Path $StorageRoot)) 'Checkpoint B local ciphertext volume is prepared'

    $sourceHeaders = SourceHeaders $Tenant1 $SourceId 'checkpoint-b-producer'
    $submission = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
        sourceSubmissionId = "producer-b-$Suffix"
        expectedDocumentCount = 3
        expectedDocuments = @(
            @{ sourceDocumentId = "doc-a-$Suffix" },
            @{ sourceDocumentId = "doc-b-$Suffix" }
        )
        metadata = @{}
    }
    Require ($submission.StatusCode -eq 201) 'Created 3-document submission with partial 2-document stable manifest'
    $SubmissionId = [string]$submission.Body.submissionId

    New-Item -ItemType Directory -Force -Path (Split-Path $Pdf1 -Parent) | Out-Null
    [System.IO.File]::WriteAllText($Pdf1, "%PDF-1.7`n1 0 obj<</Type/Catalog>>endobj`nCheckpoint-B-A-$Suffix`n%%EOF", [System.Text.Encoding]::ASCII)
    [System.IO.File]::WriteAllText($Pdf2, "%PDF-1.7`n1 0 obj<</Type/Catalog>>endobj`nCheckpoint-B-B-$Suffix`n%%EOF", [System.Text.Encoding]::ASCII)

    Write-Host ''
    Write-Host '=== PHASE 2 - Unit 6 durable receipt, sourceDocumentId identity and lost-ACK retry ==='
    $h1 = SourceHeaders $Tenant1 $SourceId 'checkpoint-b-producer'
    $h1['X-SDV-Source-Document-Id'] = "doc-a-$Suffix"
    $h1['X-SDV-Filename'] = 'approved-a.pdf'
    $u1 = UploadPdf "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" $h1 $Pdf1
    Require ($u1.StatusCode -eq 201) 'First PDF upload returns HTTP 201'
    Require ($u1.Body.receiptAcknowledgement -eq 'RECEIVED') 'Successful upload explicitly returns durable RECEIVED acknowledgement'
    $Document1Id = [string]$u1.Body.documentId
    Require (-not [string]::IsNullOrWhiteSpace($Document1Id)) 'SDV generated documentId independent from producer IDs'
    Require ($u1.Body.currentStatus -eq 'AVAILABLE') 'Durably received PDF passed Unit 9 finalization and became AVAILABLE'
    Require ($u1.Body.idempotentReplay -eq $false) 'Initial PDF upload is not marked as replay'

    Require ((DbScalar "select count(*) from documents where tenant_id='$Tenant1' and document_id='$Document1Id' and status='AVAILABLE' and content_sha256 is not null and content_size_bytes > 0;") -eq '1') 'Document receipt row is committed with content integrity metadata'
    Require ((DbScalar "select count(*) from document_storage_refs where tenant_id='$Tenant1' and document_id='$Document1Id' and storage_volume_id='$VolumeId' and integrity_value ~ '^[0-9a-f]{64}$';") -eq '1') 'Ciphertext storage reference is committed before successful receipt'
    Require ((DbScalar "select count(*) from document_crypto_metadata where tenant_id='$Tenant1' and document_id='$Document1Id' and crypto_profile='SDV-ENC-001' and key_provider_id='VAULT_TRANSIT' and protected_data_key_reference like 'vault:v%';") -eq '1') 'Protected per-document key reference and crypto profile are committed'
    Require ((DbScalar "select count(*) from submission_expected_documents where tenant_id='$Tenant1' and submission_id='$SubmissionId' and source_document_id='doc-a-$Suffix' and state='RECEIVED' and received_document_id='$Document1Id';") -eq '1') 'Stable expected manifest entry is linked to durable document receipt'

    $retry1 = UploadPdf "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" $h1 $Pdf1
    Require ($retry1.StatusCode -eq 200) 'Lost-ACK style exact retry returns HTTP 200 replay'
    Require ([string]$retry1.Body.documentId -eq $Document1Id) 'Exact retry returns same SDV documentId'
    Require ($retry1.Body.idempotentReplay -eq $true) 'Exact retry is explicitly identified as replay'
    Require ((DbScalar "select count(*) from documents where tenant_id='$Tenant1' and submission_id='$SubmissionId' and source_document_id='doc-a-$Suffix';") -eq '1') 'Retry does not duplicate the document'
    Require ((DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and target_id='$Document1Id' and action='DOCUMENT_RECEIVED';") -eq '1') 'Retry does not duplicate logical DOCUMENT_RECEIVED audit event'

    $changedPath = Join-Path $Root ".runtime\secure-ingestion-b-$Suffix-changed.pdf"
    [System.IO.File]::WriteAllText($changedPath, "%PDF-1.7`nDIFFERENT-CONTENT-$Suffix`n%%EOF", [System.Text.Encoding]::ASCII)
    $changed = UploadPdf "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" $h1 $changedPath
    Require ($changed.StatusCode -eq 409) 'Same sourceDocumentId with changed content fails closed as conflict'
    Remove-Item -Force $changedPath -ErrorAction SilentlyContinue

    Write-Host ''
    Write-Host '=== PHASE 3 - Declared manifest integrity, Idempotency-Key fallback and exact duplicate detection ==='
    $h2 = SourceHeaders $Tenant1 $SourceId 'checkpoint-b-producer'
    $h2['X-SDV-Source-Document-Id'] = "doc-b-$Suffix"
    $h2['X-SDV-Filename'] = 'approved-b.pdf'
    $u2 = UploadPdf "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" $h2 $Pdf2
    Require ($u2.StatusCode -eq 201) 'Second declared stable sourceDocumentId is received independently'
    $Document2Id = [string]$u2.Body.documentId
    Require ((DbScalar "select count(*) from submission_expected_documents where tenant_id='$Tenant1' and submission_id='$SubmissionId' and source_document_id='doc-b-$Suffix' and state='RECEIVED' and received_document_id='$Document2Id';") -eq '1') 'Every specifically declared manifest entry must be fulfilled before completion'

    $h3 = SourceHeaders $Tenant1 $SourceId 'checkpoint-b-producer'
    $h3['Idempotency-Key'] = "idem-$Suffix-3"
    $h3['X-SDV-Filename'] = 'approved-b-copy.pdf'
    $u3 = UploadPdf "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" $h3 $Pdf2
    Require ($u3.StatusCode -eq 201) 'Undeclared third PDF succeeds with explicit Idempotency-Key under a partial manifest'
    $Document3Id = [string]$u3.Body.documentId
    Require ((DbScalar "select producer_idempotency_key from documents where tenant_id='$Tenant1' and document_id='$Document3Id';") -eq "idem-$Suffix-3") 'Idempotency-Key fallback identity is persisted for the document lacking sourceDocumentId'
    Require ($Document3Id -ne $Document2Id) 'Exact duplicate content is not used as document identity'
    Require ($u3.Body.exactDuplicateContent -eq $true) 'Strong fingerprint detects exact duplicate content in the source context without merging it'

    Write-Host ''
    Write-Host '=== PHASE 4 - Unit 10 completion and clean completed response ==='
    $complete = Invoke-RestMethod -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId" -Headers $sourceHeaders -TimeoutSec 10
    Require ($complete.status -eq 'COMPLETE') 'Submission becomes COMPLETE when received document count reaches expected count'
    Require ($null -eq $complete.progress) 'Completed submission hides incomplete expected/received/missing progress'
    Require (@($complete.documents).Count -eq 3) 'Completed submission exposes its three document status summaries'
    Require ((DbScalar "select count(*) from submissions where tenant_id='$Tenant1' and submission_id='$SubmissionId' and status='COMPLETE' and completed_at is not null;") -eq '1') 'Completion state and completed_at are committed'

    Write-Host ''
    Write-Host '=== PHASE 5 - Incomplete attention threshold and later resume ==='
    $attention = InvokeJson 'POST' "$Base/api/v1/ingestion/submissions" $sourceHeaders @{
        sourceSubmissionId = "attention-b-$Suffix"
        expectedDocumentCount = 2
        expectedDocuments = @(@{ sourceDocumentId = "attention-a-$Suffix" }, @{ sourceDocumentId = "attention-b-$Suffix" })
        metadata = @{}
    }
    $AttentionSubmissionId = [string]$attention.Body.submissionId
    & docker exec sdv-postgres psql -U sdv -d sdv -c "update submissions set last_activity_at=now()-interval '25 hours' where submission_id='$AttentionSubmissionId';" *> $null
    $attentionGet = Invoke-RestMethod -Uri "$Base/api/v1/ingestion/submissions/$AttentionSubmissionId" -Headers $sourceHeaders -TimeoutSec 10
    Require ($attentionGet.status -eq 'INCOMPLETE') '24-hour inactivity threshold communicates INCOMPLETE rather than deleting/closing submission'
    Require ([int]$attentionGet.progress.expectedCount -eq 2 -and [int]$attentionGet.progress.receivedCount -eq 0 -and [int]$attentionGet.progress.missingCount -eq 2) 'Incomplete response includes expected/received/missing counts'
    Require (@($attentionGet.progress.missingSourceDocumentIds).Count -eq 2) 'Incomplete response identifies known missing sourceDocumentIds'

    $ha = SourceHeaders $Tenant1 $SourceId 'checkpoint-b-producer'
    $ha['X-SDV-Source-Document-Id'] = "attention-a-$Suffix"
    $ha['X-SDV-Filename'] = 'attention-a.pdf'
    $ra = UploadPdf "$Base/api/v1/ingestion/submissions/$AttentionSubmissionId/documents" $ha $Pdf1
    Require ($ra.StatusCode -eq 201) 'Previously incomplete submission accepts a later resumed document upload'
    $afterResume = Invoke-RestMethod -Uri "$Base/api/v1/ingestion/submissions/$AttentionSubmissionId" -Headers $sourceHeaders -TimeoutSec 10
    Require ($afterResume.status -eq 'RECEIVING') 'Activity resumes INCOMPLETE submission back to RECEIVING while still missing documents'

    $hb = SourceHeaders $Tenant1 $SourceId 'checkpoint-b-producer'
    $hb['X-SDV-Source-Document-Id'] = "attention-b-$Suffix"
    $hb['X-SDV-Filename'] = 'attention-b.pdf'
    $rb = UploadPdf "$Base/api/v1/ingestion/submissions/$AttentionSubmissionId/documents" $hb $Pdf2
    Require ($rb.StatusCode -eq 201) 'Resumed submission accepts its final missing document'
    $afterComplete = Invoke-RestMethod -Uri "$Base/api/v1/ingestion/submissions/$AttentionSubmissionId" -Headers $sourceHeaders -TimeoutSec 10
    Require ($afterComplete.status -eq 'COMPLETE') 'Previously INCOMPLETE submission can later become COMPLETE'
    Require ($null -eq $afterComplete.progress) 'Completed resumed submission again hides incomplete progress'

    Write-Host ''
    Write-Host '=== PHASE 6 - Security / plaintext / durable-object checks ==='
    $source2 = SourceHeaders $Tenant2 $SourceId 'foreign-source'
    try {
        $foreignList = Invoke-WebRequest -Uri "$Base/api/v1/ingestion/submissions/$SubmissionId/documents" -Headers $source2 -UseBasicParsing -TimeoutSec 10
        $foreignStatus = [int]$foreignList.StatusCode
    } catch {
        $foreignStatus = [int]$_.Exception.Response.StatusCode
    }
    Require ($foreignStatus -eq 404) 'Cross-tenant source context cannot enumerate another tenant submission documents'

    $objectKey = DbScalar "select object_key from document_storage_refs where tenant_id='$Tenant1' and document_id='$Document1Id';"
    Require ($objectKey -notmatch $Tenant1 -and $objectKey -notmatch $SubmissionId -and $objectKey -notmatch 'approved-a') 'Stored object key is opaque and does not embed tenant/submission/business filename'
    $objectPath = Join-Path $StorageRoot ($objectKey -replace '/', [System.IO.Path]::DirectorySeparatorChar)
    Require (Test-Path $objectPath) 'Committed ciphertext object exists on selected volume'
    $cipherBytes = [System.IO.File]::ReadAllBytes($objectPath)
    $cipherText = [System.Text.Encoding]::ASCII.GetString($cipherBytes)
    Require ($cipherText -notmatch "Checkpoint-B-A-$Suffix") 'Configured final storage does not contain readable PDF payload text'

    $Passed = $true
    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Secure Ingestion Checkpoint B (Units 6, 9, 10) acceptance passed.' -ForegroundColor Green
} finally {
    if (-not $KeepTestData) {
        try {
            if (-not [string]::IsNullOrWhiteSpace([string]$SourceId)) {
                # Cleanup while DB is still available. Guard every optional ID because
                # Phase 1 can fail before disposable entities are created.
                $corr = "ingest-b-$Suffix"
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                    delete from audit_events where tenant_id='$Tenant1' and correlation_id='$corr';
                    delete from submission_expected_documents where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_crypto_metadata where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from document_storage_refs where tenant_id='$Tenant1' and document_id in (select document_id from documents where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from documents where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from business_metadata_values where tenant_id='$Tenant1' and submission_id in (select submission_id from submissions where tenant_id='$Tenant1' and source_id='$SourceId');
                    delete from submissions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId';
                    delete from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';
                " *> $null

                if (-not [string]::IsNullOrWhiteSpace([string]$VolumeId)) {
                    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
                        delete from storage_volumes where tenant_id='$Tenant1' and volume_id='$VolumeId';
                    " *> $null
                }
                Ok 'Disposable Checkpoint B database data removed'
            }
        } catch {
            Write-Warning "Checkpoint B database cleanup failed: $($_.Exception.Message)"
        }
        Remove-Item -Recurse -Force $StorageRoot -ErrorAction SilentlyContinue
        Remove-Item -Force $Pdf1,$Pdf2 -ErrorAction SilentlyContinue
    }

    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
    }
}
