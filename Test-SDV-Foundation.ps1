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
$SourceKey = "foundation-$Suffix"
$SourceId = $null
$Passed = $false


# Windows PowerShell 5.1 can preserve a JSON array returned by Invoke-RestMethod
# as a single array object in some pipeline contexts. Normalize recursively so
# list assertions work the same way on PowerShell 5.1 and newer PowerShell.
function Expand-SdvJsonItems {
    param([AllowNull()][object]$Value)

    if ($null -eq $Value) {
        return
    }

    if ($Value -is [System.Array]) {
        foreach ($item in $Value) {
            Expand-SdvJsonItems -Value $item
        }
        return
    }

    Write-Output $Value
}

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition, [string]$Message) {
    if (-not $Condition) { throw $Message }
    Ok $Message
}
function Headers([string]$Tenant, [string]$Subject, [string]$Roles) {
    return @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Subject' = $Subject
        'X-SDV-Roles' = $Roles
        'X-Correlation-Id' = "foundation-$Suffix"
    }
}
function HttpStatus([string]$Method, [string]$Uri, [hashtable]$Headers = @{}, [object]$Body = $null) {
    try {
        $args = @{ Uri=$Uri; Method=$Method; Headers=$Headers; UseBasicParsing=$true; TimeoutSec=10 }
        if ($null -ne $Body) {
            $args['ContentType'] = 'application/json'
            $args['Body'] = ($Body | ConvertTo-Json -Depth 8)
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

$it1 = Headers $Tenant1 'foundation-it-1' 'TENANT_IT_ADMIN'
$biz1 = Headers $Tenant1 'foundation-biz-1' 'BUSINESS_ADMIN'
$both1 = Headers $Tenant1 'foundation-both-1' 'TENANT_IT_ADMIN,BUSINESS_ADMIN'
$it2 = Headers $Tenant2 'foundation-it-2' 'TENANT_IT_ADMIN'
$biz2 = Headers $Tenant2 'foundation-biz-2' 'BUSINESS_ADMIN'

Write-Host ''
Write-Host '=== SDV FOUNDATION ACCEPTANCE (UNITS 1-4) ==='

try {
    if (-not $SkipBuild) {
        Write-Host ''
        Write-Host '=== PHASE 0 - Build ==='
        & (Join-Path $Root 'Build-SDV-All.ps1')
        if ($LASTEXITCODE -ne 0) { throw 'Build failed.' }
        Ok 'Backend + frontend build passed'
    }

    Write-Host ''
    Write-Host '=== PHASE 1 - Runtime and Liquibase ==='
    & (Join-Path $Root 'Start-SDV-Local.ps1')
    if ($LASTEXITCODE -ne 0) { throw 'Local startup failed.' }

    $health = Invoke-RestMethod -Uri "$Base/actuator/health" -TimeoutSec 5
    Require ($health.status -eq 'UP') 'Backend health is UP'
    Require ((DbScalar "select count(*) from databasechangelog where id='001-foundation-core';") -eq '1') 'Foundation schema changeset applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='002-local-foundation-seed';") -eq '1') 'Local foundation seed applied once'
    Require ((DbScalar "select count(*) from databasechangelog where id='003-foundation-integrity-hardening';") -eq '1') 'Foundation integrity-hardening changeset applied once'
    Require ((DbScalar "select count(*) from tenants where tenant_id in ('$Tenant1','$Tenant2');") -eq '2') 'Two isolated local acceptance tenants exist'

    $corr = "unit0-foundation-$Suffix"
    $systemResponse = Invoke-WebRequest -Uri "$Base/api/v1/system/info" -Headers @{ 'X-Correlation-Id' = $corr } -UseBasicParsing -TimeoutSec 5
    $systemInfo = $systemResponse.Content | ConvertFrom-Json
    Require ($systemInfo.productCode -eq 'SDV') 'Unit 0 system endpoint identifies SDV'
    Require ($systemInfo.correlationId -eq $corr) 'Unit 0 correlation ID flows through system endpoint'
    Require ($systemResponse.Headers['X-Correlation-Id'] -eq $corr) 'Unit 0 correlation ID is returned in response header'

    $tables = @('ingestion_sources','metadata_field_definitions','submissions','submission_expected_documents','documents','business_metadata_values','document_storage_refs','document_crypto_metadata','audit_events')
    foreach ($table in $tables) {
        $hasTenant = DbScalar "select count(*) from information_schema.columns where table_schema='public' and table_name='$table' and column_name='tenant_id';"
        Require ($hasTenant -eq '1') "$table is tenant-scoped"
    }

    Require ((DbScalar "select count(*) from information_schema.columns where table_schema='public' and table_name='business_metadata_values' and column_name='source_id' and is_nullable='NO';") -eq '1') 'Business metadata persists non-null source identity'
    $integrityConstraints = @('fk_document_submission_source','fk_expected_received_document','fk_business_metadata_submission_source','fk_business_metadata_definition_identity')
    foreach ($constraint in $integrityConstraints) {
        Require ((DbScalar "select count(*) from pg_constraint where conname='$constraint';") -eq '1') "Schema integrity constraint $constraint is present"
    }

    Write-Host ''
    Write-Host '=== PHASE 2 - Fail-closed security and role composition ==='
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/foundation/context") -eq 401) 'Protected API denies unauthenticated request'
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/ingestion-sources" $biz1) -eq 403) 'Business Admin cannot configure ingestion sources by default'

    $context = Invoke-RestMethod -Uri "$Base/api/v1/admin/foundation/context" -Headers $both1 -TimeoutSec 5
    Require (@($context.capabilities) -contains 'CONFIGURE_INGESTION_SOURCES') 'Multi-role principal receives Tenant IT capability'
    Require (@($context.capabilities) -contains 'CONFIGURE_METADATA_SEARCH') 'Multi-role principal receives Business Admin capability'
    Require (-not (@($context.capabilities) -contains 'VIEW_PDF')) 'Administrative roles do not implicitly receive PDF view capability'

    Write-Host ''
    Write-Host '=== PHASE 3 - Tenant IT ingestion-source administration ==='
    $sourceBody = @{
        sourceKey = $SourceKey
        displayName = "Foundation Acceptance $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "local://acceptance/$Suffix"
        limits = @{
            maxDocumentsPerSubmission = 75
            incompleteAttentionHours = 36
        }
    }
    $source = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources" -Method Post -Headers $it1 -ContentType 'application/json' -Body ($sourceBody | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $SourceId = [string]$source.sourceId
    Require (-not [string]::IsNullOrWhiteSpace($SourceId)) 'Tenant IT created an ingestion source'
    Require ($source.sourceKey -eq $SourceKey) 'Source stable key was persisted'
    Require ($source.tenantId -eq $Tenant1) 'Source is owned by authenticated tenant'
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/ingestion-sources/$SourceId" $it2) -eq 404) 'Second tenant cannot retrieve first tenant source'
    $tenant2Raw = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources" -Headers $it2 -TimeoutSec 5
    $tenant2List = @(Expand-SdvJsonItems -Value $tenant2Raw)
    $tenant2SourceIds = @(
        $tenant2List | ForEach-Object {
            $sourceIdProperty = $_.PSObject.Properties['sourceId']
            if ($null -ne $sourceIdProperty) {
                [string]$sourceIdProperty.Value
            }
        }
    )
    Require (-not ($tenant2SourceIds -contains $SourceId)) 'Source list is tenant-isolated'

    Write-Host ''
    Write-Host '=== PHASE 4 - Business metadata configuration and history ==='
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields" $it1) -eq 403) 'Tenant IT cannot configure business metadata by default'

    $fieldV1 = @{
        displayLabel = 'Contract Number'
        fieldType = 'TEXT'
        required = $true
        businessIdentifier = $true
        searchable = $true
        normalization = 'DIGITS_ONLY'
        searchMode = 'EXACT'
        validationRules = @{ exactLength = 12 }
    }
    $created = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/contractNumber" -Method Put -Headers $biz1 -ContentType 'application/json' -Body ($fieldV1 | ConvertTo-Json -Depth 8) -TimeoutSec 10
    Require ($created.version -eq 1) 'Initial metadata definition is version 1'
    Require ($created.fieldKey -eq 'contractNumber') 'Stable metadata field key persisted'

    $fieldV2 = $fieldV1.Clone()
    $fieldV2.displayLabel = 'Contract #'
    $fieldV2.validationRules = @{ exactLength = 12; example = '000000000000' }
    $revised = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/contractNumber" -Method Put -Headers $biz1 -ContentType 'application/json' -Body ($fieldV2 | ConvertTo-Json -Depth 8) -TimeoutSec 10
    Require ($revised.version -eq 2) 'Changing metadata configuration creates version 2'

    $historyRaw = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields/contractNumber/history" -Headers $biz1 -TimeoutSec 5
    $history = @(Expand-SdvJsonItems -Value $historyRaw | Sort-Object { [int]$_.version })
    Require ($history.Count -eq 2) 'Metadata definition history retains both versions'
    Require ($history[0].active -eq $false -and $history[1].active -eq $true) 'Only newest metadata definition is active'
    Require ((HttpStatus 'GET' "$Base/api/v1/admin/ingestion-sources/$SourceId/metadata-fields" $biz2) -eq 404) 'Second tenant cannot reach metadata schema through foreign source ID'

    Write-Host ''
    Write-Host '=== PHASE 5 - Persistence invariants and audit ==='
    Require ((DbScalar "select count(*) from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId' and field_key='contractNumber';") -eq '2') 'Historical metadata definitions remain persisted'
    Require ((DbScalar "select count(*) from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId' and field_key='contractNumber' and active=true;") -eq '1') 'Database enforces one current metadata definition'
    $auditCount = [int](DbScalar "select count(*) from audit_events where tenant_id='$Tenant1' and correlation_id='foundation-$Suffix';")
    Require ($auditCount -ge 3) 'Source/metadata configuration changes are append-audited'
    Require ((DbScalar "select credential_reference from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';") -eq "local://acceptance/$Suffix") 'Source stores an opaque credential reference, not an embedded credential value'

    Write-Host ''
    Write-Host '=== PHASE 6 - Frontend ==='
    $front = Invoke-WebRequest -Uri 'http://localhost:5183' -UseBasicParsing -TimeoutSec 5
    Require ($front.StatusCode -eq 200) 'Foundation UI is reachable on port 5183'

    $Passed = $true
    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Foundation Units 1-4 acceptance passed.' -ForegroundColor Green
    Write-Host "Disposable source: $SourceId"
} finally {
    if ($SourceId -and ($Passed -or -not $KeepTestData)) {
        if ($Passed -and -not $KeepTestData) {
            try {
                & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "delete from audit_events where tenant_id='$Tenant1' and correlation_id='foundation-$Suffix'; delete from metadata_field_definitions where tenant_id='$Tenant1' and source_id='$SourceId'; delete from ingestion_sources where tenant_id='$Tenant1' and source_id='$SourceId';" | Out-Null
                Ok 'Disposable Foundation acceptance data removed'
            } catch { Write-Warning "Cleanup failed: $_" }
        }
    }
    if (-not $KeepRunning) {
        try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_ }
    }
}
