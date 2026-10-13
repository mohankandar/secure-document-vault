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
$IssuerPort = 18081
$Issuer = "http://127.0.0.1:$IssuerPort"
$IssuerSource = Join-Path $Root 'tools\Unit21JwtTestIssuer.java'
$Tenant = '11111111-1111-1111-1111-111111111111'
$OtherTenant = '22222222-2222-2222-2222-222222222222'
$Suffix = ([guid]::NewGuid().ToString('N')).Substring(0, 10)
$SourceId = $null
$IssuerProcess = $null
$Passed = $false
$EnterpriseStarted = $false
$LocalStarted = $false

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition, [string]$Message) { if (-not $Condition) { throw $Message }; Ok $Message }

function HttpStatus {
    param([string]$Method, [string]$Uri, [hashtable]$Headers = @{})
    try {
        $response = Invoke-WebRequest -Method $Method -Uri $Uri -Headers $Headers -UseBasicParsing -TimeoutSec 10
        return [int]$response.StatusCode
    } catch {
        if ($_.Exception.Response -and $_.Exception.Response.StatusCode) {
            return [int]$_.Exception.Response.StatusCode
        }
        throw
    }
}

function Bearer([string]$Token) { return @{ Authorization = "Bearer $Token" } }

function Token {
    param(
        [string]$Kind = 'user',
        [string]$Roles = 'BUSINESS_USER',
        [string]$TenantId = $Tenant,
        [string]$Source = '',
        [string]$Key = '1'
    )
    $q = "kind=$([uri]::EscapeDataString($Kind))&roles=$([uri]::EscapeDataString($Roles))&tenant=$([uri]::EscapeDataString($TenantId))&key=$Key"
    if (-not [string]::IsNullOrWhiteSpace($Source)) { $q += "&source=$([uri]::EscapeDataString($Source))" }
    return [string](Invoke-WebRequest -Uri "$Issuer/token?$q" -UseBasicParsing -TimeoutSec 5).Content
}

function Start-TestIssuer {
    if (-not (Test-Path $IssuerSource)) { throw "Unit 21 JWT fixture not found: $IssuerSource" }
    $out = Join-Path $Root '.runtime\unit21-jwt-issuer.out.log'
    $err = Join-Path $Root '.runtime\unit21-jwt-issuer.err.log'
    New-Item -ItemType Directory -Force -Path (Join-Path $Root '.runtime') | Out-Null
    $script:IssuerProcess = Start-Process -FilePath 'java' -ArgumentList @($IssuerSource, "$IssuerPort") `
        -WorkingDirectory $Root -RedirectStandardOutput $out -RedirectStandardError $err -PassThru
    for ($i = 0; $i -lt 30; $i++) {
        try {
            $health = Invoke-WebRequest -Uri "$Issuer/health" -UseBasicParsing -TimeoutSec 2
            if ($health.StatusCode -eq 200) { Ok 'Local Unit 21 JWT/JWKS fixture is ready'; return }
        } catch { }
        Start-Sleep -Seconds 1
    }
    throw "Unit 21 JWT fixture did not start. See $out and $err"
}

function Stop-TestIssuer {
    if ($script:IssuerProcess -and -not $script:IssuerProcess.HasExited) {
        Stop-Process -Id $script:IssuerProcess.Id -Force -ErrorAction SilentlyContinue
    }
}

if (-not $SkipBuild) {
    Write-Host '=== BUILD ==='
    & (Join-Path $Root 'build-all.cmd')
    if ($LASTEXITCODE -ne 0) { throw 'build-all.cmd failed.' }
}

$prior = @{
    Mode = $env:SDV_AUTH_MODE
    Issuer = $env:SDV_AUTH_JWT_ISSUER
    Jwks = $env:SDV_AUTH_JWT_JWK_SET_URI
    Audience = $env:SDV_AUTH_JWT_AUDIENCE
}

try {
    Write-Host ''
    Write-Host '=== PHASE 1 - Frozen local authentication compatibility and M2M source setup ==='
    Remove-Item Env:SDV_AUTH_MODE -ErrorAction SilentlyContinue
    Remove-Item Env:SDV_AUTH_JWT_ISSUER -ErrorAction SilentlyContinue
    Remove-Item Env:SDV_AUTH_JWT_JWK_SET_URI -ErrorAction SilentlyContinue
    Remove-Item Env:SDV_AUTH_JWT_AUDIENCE -ErrorAction SilentlyContinue

    & (Join-Path $Root 'Start-SDV-Local.ps1')
    $LocalStarted = $true

    $localAdmin = @{
        'X-SDV-Tenant-Id' = $Tenant
        'X-SDV-Subject' = 'unit21-local-admin'
        'X-SDV-Roles' = 'TENANT_IT_ADMIN,BUSINESS_ADMIN'
        'X-Correlation-Id' = "unit21-$Suffix"
    }
    $session = Invoke-RestMethod -Uri "$Base/api/v1/session" -Headers $localAdmin -TimeoutSec 5
    Require ($session.tenantId -eq $Tenant) 'Frozen local-header authentication still works in local mode'

    $sourceBody = @{
        sourceKey = "unit21-$Suffix"
        displayName = "Unit 21 M2M $Suffix"
        authType = 'OAUTH2_CLIENT_CREDENTIALS'
        credentialReference = "idp://unit21/$Suffix"
        limits = @{ maxDocumentsPerSubmission = 5; incompleteAttentionHours = 24 }
    }
    $source = Invoke-RestMethod -Uri "$Base/api/v1/admin/ingestion-sources" -Method Post -Headers $localAdmin `
        -ContentType 'application/json' -Body ($sourceBody | ConvertTo-Json -Depth 8) -TimeoutSec 10
    $SourceId = [string]$source.sourceId
    Require (-not [string]::IsNullOrWhiteSpace($SourceId)) 'Created disposable source for enterprise M2M identity validation'

    & (Join-Path $Root 'Stop-SDV-Local.ps1') -KeepDatabase
    $LocalStarted = $false

    Write-Host ''
    Write-Host '=== PHASE 2 - Enterprise JWT resource-server boundary ==='
    Start-TestIssuer
    $env:SDV_AUTH_MODE = 'enterprise-jwt'
    $env:SDV_AUTH_JWT_ISSUER = $Issuer
    $env:SDV_AUTH_JWT_JWK_SET_URI = "$Issuer/jwks"
    $env:SDV_AUTH_JWT_AUDIENCE = 'sdv-api'

    & (Join-Path $Root 'Start-SDV-Local.ps1')
    $EnterpriseStarted = $true

    Require ((HttpStatus 'GET' "$Base/api/v1/session") -eq 401) 'Enterprise mode denies unauthenticated protected requests'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" $localAdmin) -eq 401) 'Enterprise mode does not accept local authentication headers'

    $userToken = Token -Roles 'BUSINESS_USER,DOCUMENT_STEWARD'
    $userSession = Invoke-RestMethod -Uri "$Base/api/v1/session" -Headers (Bearer $userToken) -TimeoutSec 5
    Require ($userSession.tenantId -eq $Tenant) 'Validated JWT tenant claim becomes SDV tenant context'
    Require (@($userSession.roles) -contains 'BUSINESS_USER' -and @($userSession.roles) -contains 'DOCUMENT_STEWARD') 'Validated JWT roles map into frozen SDV roles'
    Require (@($userSession.capabilities) -contains 'VIEW_PDF' -and @($userSession.capabilities) -contains 'DOWNLOAD_CURRENT_PDF') 'Frozen role/capability composition is preserved for enterprise JWT users'

    $spoofHeaders = Bearer $userToken
    $spoofHeaders['X-SDV-Tenant-Id'] = $OtherTenant
    $spoofHeaders['X-SDV-Roles'] = 'PLATFORM_OPERATIONS_ADMIN'
    $spoofedSession = Invoke-RestMethod -Uri "$Base/api/v1/session" -Headers $spoofHeaders -TimeoutSec 5
    Require ($spoofedSession.tenantId -eq $Tenant) 'Unsigned local tenant header cannot override signed enterprise tenant identity'
    Require (-not (@($spoofedSession.roles) -contains 'PLATFORM_OPERATIONS_ADMIN')) 'Unsigned local role header cannot elevate enterprise JWT roles'

    Write-Host ''
    Write-Host '=== PHASE 3 - JWT validation fails closed ==='
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer (Token -Kind 'bad-audience'))) -eq 401) 'Wrong JWT audience is rejected'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer (Token -Kind 'bad-issuer'))) -eq 401) 'Wrong JWT issuer is rejected'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer (Token -Kind 'expired'))) -eq 401) 'Expired JWT is rejected'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer (Token -Kind 'missing-tenant'))) -eq 401) 'JWT missing tenant identity is rejected'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer (Token -Kind 'unknown-role'))) -eq 401) 'JWT with unknown SDV role is rejected'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer (Token -Kind 'both' -Source $SourceId))) -eq 401) 'JWT cannot ambiguously identify both a user and ingestion source'

    Write-Host ''
    Write-Host '=== PHASE 4 - OAuth2 M2M source identity and signing-key rotation ==='
    $preRotationToken = Token -Roles 'BUSINESS_USER' -Key '2'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer $preRotationToken)) -eq 401) 'Token signed by unpublished key is rejected before JWKS rotation'

    $sourceToken = Token -Kind 'source' -Source $SourceId
    $missingSubmissionStatus = HttpStatus 'GET' "$Base/api/v1/ingestion/submissions?sourceSubmissionId=unit21-does-not-exist" (Bearer $sourceToken)
    Require ($missingSubmissionStatus -eq 404) 'Valid source JWT reaches frozen ingestion source boundary'
    Require ((HttpStatus 'GET' "$Base/api/v1/session" (Bearer $sourceToken)) -eq 401) 'M2M source JWT cannot impersonate an interactive SDV user'
    Require ((HttpStatus 'GET' "$Base/api/v1/ingestion/submissions?sourceSubmissionId=unit21-does-not-exist" (Bearer $userToken)) -eq 401) 'Interactive user JWT cannot impersonate an ingestion source'

    Invoke-RestMethod -Method Post -Uri "$Issuer/rotate" -TimeoutSec 5 | Out-Null
    $rotatedToken = Token -Roles 'BUSINESS_USER' -Key '2'
    $rotatedSession = Invoke-RestMethod -Uri "$Base/api/v1/session" -Headers (Bearer $rotatedToken) -TimeoutSec 10
    Require ($rotatedSession.tenantId -eq $Tenant) 'JWKS signing-key rotation is accepted without SDV restart or embedded signing key'

    Write-Host ''
    Write-Host '=== PHASE 5 - Frontend/provider-neutral integration boundary ==='
    $apiText = Get-Content -Raw (Join-Path $Root 'frontend\sdv-ui\src\api.ts')
    $authText = Get-Content -Raw (Join-Path $Root 'frontend\sdv-ui\src\auth.ts')
    Require ($apiText -match 'bearerHeaders') 'Frontend API path includes enterprise bearer adapter'
    Require ($authText -match 'if \(import\.meta\.env\.DEV\) return \{\}') 'Enterprise bearer adapter is disabled in local Vite development mode'
    Require ($authText -notmatch 'localStorage|sessionStorage') 'SDV frontend does not persist enterprise access tokens in browser storage'
    Require ($authText -match 'window\.SDV_AUTH') 'OIDC client integration remains provider-neutral through deployment-owned token adapter'

    $Passed = $true
    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV Enterprise / Operational Readiness Checkpoint B (Unit 21) acceptance passed.' -ForegroundColor Green
} finally {
    if ($SourceId -and ($Passed -or -not $KeepTestData)) {
        try {
            & docker exec sdv-postgres psql -U sdv -d sdv -v ON_ERROR_STOP=1 -c "delete from audit_events where tenant_id='$Tenant' and correlation_id='unit21-$Suffix'; delete from ingestion_sources where tenant_id='$Tenant' and source_id='$SourceId';" *> $null
            if ($LASTEXITCODE -eq 0) { Ok 'Disposable Unit 21 source/audit data removed' }
        } catch { Write-Warning "Unit 21 cleanup failed: $_" }
    }

    if (-not $KeepRunning) {
        if ($EnterpriseStarted -or $LocalStarted) {
            try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { Write-Warning $_ }
        } else {
            try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { }
        }
        Stop-TestIssuer
    }

    $env:SDV_AUTH_MODE = $prior.Mode
    $env:SDV_AUTH_JWT_ISSUER = $prior.Issuer
    $env:SDV_AUTH_JWT_JWK_SET_URI = $prior.Jwks
    $env:SDV_AUTH_JWT_AUDIENCE = $prior.Audience
}
