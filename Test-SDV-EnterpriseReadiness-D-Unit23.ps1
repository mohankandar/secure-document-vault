[CmdletBinding()]
param([switch]$SkipBuild)

$ErrorActionPreference='Stop'
Set-StrictMode -Version Latest

$Root=$PSScriptRoot
$Base='http://localhost:8280'
$Tenant='11111111-1111-1111-1111-111111111111'
$Suffix=([guid]::NewGuid().ToString('N')).Substring(0,10)
$Pdf=Join-Path $Root 'test-tools\fixtures\business-viewer-two-page.pdf'
$Contract=Join-Path $Root 'docs\api\sdv-ingestion-v1.openapi.json'
$Schema=Join-Path $Root 'docs\api\schemas\sdv-ingestion-submission-v1.schema.json'
$Producer=Join-Path $Root 'samples\third-party-producer\Invoke-SDV-Producer.ps1'
$Runtime=Join-Path $Root ".runtime\enterprise-readiness-d\$Suffix"
$StorageRoot=Join-Path $Runtime 'storage'
$VolumeId=$null
$SourceIds=New-Object System.Collections.Generic.List[string]

function Ok([string]$Message){ Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition,[string]$Message){ if(-not $Condition){ throw $Message }; Ok $Message }
function UserHeaders([string]$Subject,[string]$Roles){ return @{'X-SDV-Tenant-Id'=$Tenant;'X-SDV-Subject'=$Subject;'X-SDV-Roles'=$Roles;'X-Correlation-Id'="u23-$Suffix"} }
function SourceHeaders([string]$Source,[string]$Subject){ return @{'X-SDV-Tenant-Id'=$Tenant;'X-SDV-Source-Id'=$Source;'X-SDV-Subject'=$Subject;'X-Correlation-Id'="u23-$Suffix"} }

function InvokeJson([string]$Method,[string]$Uri,[hashtable]$Headers,[object]$Body){
    try {
        $bodyText=if($null -eq $Body){$null}else{$Body|ConvertTo-Json -Depth 30}
        $r=Invoke-WebRequest -Uri $Uri -Method $Method -Headers $Headers -ContentType 'application/json' -Body $bodyText -UseBasicParsing -TimeoutSec 30
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

function SaveField([hashtable]$Headers,[string]$SourceId,[string]$FieldKey,[string]$Label,[bool]$Identifier){
    $r=InvokeJson 'PUT' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/$FieldKey" $Headers @{
        displayLabel=$Label;fieldType='TEXT';required=$true;businessIdentifier=$Identifier;searchable=$true
        normalization=if($Identifier){'DIGITS_ONLY'}else{'LOWERCASE'};searchMode=if($Identifier){'EXACT'}else{'PARTIAL'};validationRules=@{}
    }
    Require ($r.StatusCode -eq 200) "Configured $FieldKey for source $SourceId"
}

function NewSource([hashtable]$It,[string]$Key,[string]$Name){
    $r=InvokeJson 'POST' "$Base/api/v1/admin/ingestion-sources" $It @{
        sourceKey=$Key;displayName=$Name;authType='OAUTH2_CLIENT_CREDENTIALS';credentialReference="external://u23/$Key"
        limits=@{maxPdfBytes=10485760;maxPagesPerPdf=2000;maxDocumentsPerSubmission=10;maxAggregateSubmissionBytes=20971520;incompleteAttentionHours=24}
    }
    Require ($r.StatusCode -eq 201) "Created independent producer source $Name"
    $id=[string]$r.Body.sourceId
    $SourceIds.Add($id)
    return $id
}

function CleanupSource([string]$SourceId){
    & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "
      delete from submission_expected_documents where tenant_id='$Tenant' and submission_id in (select submission_id from submissions where tenant_id='$Tenant' and source_id='$SourceId');
      delete from document_redaction_operations where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
      delete from document_crypto_metadata where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
      delete from document_storage_refs where tenant_id='$Tenant' and document_id in (select document_id from documents where tenant_id='$Tenant' and source_id='$SourceId');
      delete from documents where tenant_id='$Tenant' and source_id='$SourceId';
      delete from business_metadata_values where tenant_id='$Tenant' and source_id='$SourceId';
      delete from submissions where tenant_id='$Tenant' and source_id='$SourceId';
      delete from metadata_field_definitions where tenant_id='$Tenant' and source_id='$SourceId';
      delete from audit_events where tenant_id='$Tenant' and correlation_id='u23-$Suffix';
      delete from ingestion_sources where tenant_id='$Tenant' and source_id='$SourceId';
    " *> $null
}

New-Item -ItemType Directory -Force -Path $Runtime,$StorageRoot | Out-Null

try {
    if(-not $SkipBuild){
        Write-Host '=== BUILD ==='
        & (Join-Path $Root 'build-all.cmd')
        if($LASTEXITCODE -ne 0){throw 'build-all.cmd failed.'}
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Frozen baseline + public compatibility artifacts ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1') -WithVault
    Require ((Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5).status -eq 'UP') 'Backend health is UP on port 8280'
    $migration=(& docker exec sdv-postgres psql -U sdv -d sdv -tAc "select count(*) from databasechangelog where id='009-audit-operations-readiness';" | Out-String).Trim()
    Require ($migration -eq '1') 'Frozen migration 009 remains applied once'
    Require (Test-Path $Contract) 'Public OpenAPI JSON contract is present'
    Require (Test-Path $Schema) 'Portable producer submission JSON Schema is present'
    Require (Test-Path $Producer) 'Standalone third-party producer sample is present'
    Require (Test-Path $Pdf) 'PDF compatibility fixture is present'

    $openapi=(Get-Content -LiteralPath $Contract -Raw) | ConvertFrom-Json
    Require ([string]$openapi.openapi -eq '3.1.0') 'OpenAPI contract parses as version 3.1.0'
    $paths=@($openapi.paths.PSObject.Properties.Name)
    Require ($paths -contains '/api/v1/ingestion/submissions') 'OpenAPI publishes submission create/retrieval contract'
    Require ($paths -contains '/api/v1/ingestion/submissions/{submissionId}/documents') 'OpenAPI publishes independent PDF upload/list contract'
    Require ($null -ne $openapi.components.securitySchemes.bearerAuth) 'OpenAPI publishes provider-neutral bearer authentication contract'
    $jsonSchema=(Get-Content -LiteralPath $Schema -Raw) | ConvertFrom-Json
    Require ([string]$jsonSchema.type -eq 'object' -and @($jsonSchema.required).Count -ge 3) 'Producer JSON Schema is machine-readable and bounded'

    $machineText=(Get-Content -LiteralPath $Contract -Raw)+(Get-Content -LiteralPath $Schema -Raw)+(Get-Content -LiteralPath $Producer -Raw)
    Require ($machineText -notmatch '(?i)lexavault') 'Machine contract and sample producer contain no sister-product coupling'
    Require ($machineText -notmatch 'com\.sdv\.|backend\\|sdv-domain|sdv-application') 'Standalone producer imports no SDV internal code or modules'

    Write-Host ''
    Write-Host '=== PHASE 2 - Two independent producer identities use the same contract ==='
    $it=UserHeaders 'u23-it' 'TENANT_IT_ADMIN'
    $admin=UserHeaders 'u23-admin' 'BUSINESS_ADMIN'
    $business=UserHeaders 'u23-business' 'BUSINESS_USER'

    $volume=InvokeJson 'POST' "$Base/api/v1/admin/storage-volumes" $it @{
        displayName="Unit 23 Compatibility Volume $Suffix";rootPath=$StorageRoot;state='ACTIVE';priority=1;warningPercent=90;stopWritePercent=95
    }
    Require ($volume.StatusCode -eq 201) 'Created disposable compatibility storage volume'
    $VolumeId=[string]$volume.Body.volume.volumeId

    $sourceA=NewSource $it "u23-a-$Suffix" 'Acme Claims Producer'
    $sourceB=NewSource $it "u23-b-$Suffix" 'Northstar Archive Producer'
    foreach($sourceId in @($sourceA,$sourceB)){
        SaveField $admin $sourceId 'contractNumber' 'Contract Number' $true
        SaveField $admin $sourceId 'memberName' 'Member Name' $false
    }

    $metadataA=Join-Path $Runtime 'producer-a-metadata.json'
    $metadataB=Join-Path $Runtime 'producer-b-metadata.json'
    @{contractNumber='990023450001';memberName='Acme Example'} | ConvertTo-Json | Set-Content -LiteralPath $metadataA -Encoding UTF8
    @{contractNumber='990023450002';memberName='Northstar Example'} | ConvertTo-Json | Set-Content -LiteralPath $metadataB -Encoding UTF8
    $resultA=Join-Path $Runtime 'producer-a-result.json'
    $resultB=Join-Path $Runtime 'producer-b-result.json'

    & $Producer -BaseUrl $Base -SourceSubmissionId "acme-case-$Suffix" -PdfPaths @($Pdf,$Pdf) -SourceDocumentIds @("acme-doc-1-$Suffix","acme-doc-2-$Suffix") -MetadataJsonPath $metadataA -LocalDevTenantId $Tenant -LocalDevSourceId $sourceA -LocalDevSubject 'acme-producer' -CorrelationId "u23-$Suffix" -ReplayFirstDocument -OutputJson $resultA | Out-Null
    & $Producer -BaseUrl $Base -SourceSubmissionId "northstar-case-$Suffix" -PdfPaths @($Pdf) -SourceDocumentIds @("northstar-doc-1-$Suffix") -MetadataJsonPath $metadataB -LocalDevTenantId $Tenant -LocalDevSourceId $sourceB -LocalDevSubject 'northstar-producer' -CorrelationId "u23-$Suffix" -ReplayFirstDocument -OutputJson $resultB | Out-Null

    $a=(Get-Content -LiteralPath $resultA -Raw) | ConvertFrom-Json
    $b=(Get-Content -LiteralPath $resultB -Raw) | ConvertFrom-Json
    Require ([string]$a.contract -eq 'sdv-ingestion-v1' -and [string]$b.contract -eq 'sdv-ingestion-v1') 'Both producers used the same public contract version'
    Require ([string]$a.submissionStatus -eq 'COMPLETE' -and [int]$a.documentCount -eq 2) 'First third-party producer completed a two-document lifecycle'
    Require ([string]$b.submissionStatus -eq 'COMPLETE' -and [int]$b.documentCount -eq 1) 'Second independent producer completed the same lifecycle'
    Require ([bool]$a.createIdempotentReplay -and [bool]$a.firstDocumentReplay -and [bool]$b.createIdempotentReplay -and [bool]$b.firstDocumentReplay) 'Public contract supports safe create and document retry/idempotency'
    Require (@($a.receiptAcknowledgements).Count -eq 2 -and -not [string]::IsNullOrWhiteSpace([string]$a.receiptAcknowledgements[0])) 'Producer receives durable document acknowledgement evidence'

    Write-Host ''
    Write-Host '=== PHASE 3 - Source isolation + business lifecycle compatibility ==='
    $cross=InvokeJson 'GET' "$Base/api/v1/ingestion/submissions/$($a.submissionId)" (SourceHeaders $sourceB 'northstar-producer') $null
    Require ($cross.StatusCode -eq 404) 'One producer cannot retrieve another producer source submission'

    $searchA=InvokeJson 'POST' "$Base/api/v1/business/search" $business @{sourceId=$sourceA;criteria=@{contractNumber='990023450001'};limit=10}
    Require ($searchA.StatusCode -eq 200 -and @($searchA.Body.records).Count -eq 1) 'Third-party producer metadata becomes source-aware business-searchable data'
    $recordRef=[string]$searchA.Body.records[0].recordRef
    $record=InvokeJson 'GET' "$Base/api/v1/business/records/$recordRef" $business $null
    Require ($record.StatusCode -eq 200 -and @($record.Body.documents).Count -eq 2) 'Business record aggregates the externally produced documents'
    $firstDoc=[string]$record.Body.documents[0].documentId
    $viewer=InvokeJson 'GET' "$Base/api/v1/business/documents/$firstDoc/viewer" $business $null
    Require ($viewer.StatusCode -eq 200) 'Externally produced document completes lifecycle into authorized secure viewing'

    $searchWrongSource=InvokeJson 'POST' "$Base/api/v1/business/search" $business @{sourceId=$sourceB;criteria=@{contractNumber='990023450001'};limit=10}
    Require ($searchWrongSource.StatusCode -eq 200 -and @($searchWrongSource.Body.records).Count -eq 0) 'Business search remains source-aware across independent producers'

    Write-Host ''
    Write-Host '=== PHASE 4 - Compatibility boundaries ==='
    Require (-not (Test-Path (Join-Path $Root 'samples\third-party-producer\pom.xml'))) 'Sample producer has no Maven/shared-library dependency'
    $contractText=Get-Content -LiteralPath $Contract -Raw
    Require ($contractText -match 'sourceSubmissionId' -and $contractText -match 'sourceDocumentId' -and $contractText -match 'Idempotency-Key') 'Contract documents producer-owned identities and retry semantics'
    Require ($contractText -notmatch 'X-SDV-Tenant-Id' -and $contractText -notmatch 'X-SDV-Source-Id') 'Public production contract does not expose local-development identity headers'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Enterprise / Operational Readiness Checkpoint D (Unit 23) acceptance passed.' -ForegroundColor Green
}
finally {
    try {
        foreach($id in $SourceIds){ CleanupSource $id }
        if($SourceIds.Count -gt 0){ Ok 'Disposable Unit 23 source/submission/document data removed' }
    } catch { Write-Warning "Unit 23 cleanup failed: $($_.Exception.Message)" }

    try {
        if(-not [string]::IsNullOrWhiteSpace([string]$VolumeId)){
            & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "delete from storage_volumes where tenant_id='$Tenant' and volume_id='$VolumeId';" *> $null
        }
    } catch { }

    Remove-Item -LiteralPath $Runtime -Recurse -Force -ErrorAction SilentlyContinue
    try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_.Exception.Message }
}
