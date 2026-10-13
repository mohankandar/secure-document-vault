[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot 'release'),
    [string]$Version = '1.0.0'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = (Resolve-Path $PSScriptRoot).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition,[string]$Message) { if(-not $Condition){ throw $Message }; Ok $Message }

if ($Version -ne '1.0.0') {
    throw "This frozen GA cut script is pinned to version 1.0.0. Requested: $Version"
}

$Required = @(
    'README.md',
    'build-all.cmd',
    'Build-SDV-All.ps1',
    'Start-SDV-Local.ps1',
    'Stop-SDV-Local.ps1',
    'backend\pom.xml',
    'frontend\sdv-ui\package.json',
    'frontend\sdv-ui\package-lock.json',
    'docs\api\sdv-ingestion-v1.openapi.json',
    'docs\api\schemas\sdv-ingestion-submission-v1.schema.json',
    'docs\operations\SDV_BACKUP_RESTORE_HA_RUNBOOK.md',
    'docs\operations\SDV_V1_RELEASE_READINESS_RUNBOOK.md',
    'docs\operations\SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md',
    'docs\development\V1_ACCEPTANCE_RELEASE_BASELINE_UNIT25.md',
    'docs\release\SDV_V1_GA_BASELINE.json',
    'samples\third-party-producer\Invoke-SDV-Producer.ps1',
    'Test-SDV-V1-Release-Readiness-Unit25.ps1',
    'Test-SDV-V1-GA-CleanInstall.ps1',
    'SDV-V1-FROZEN-RELEASE-BASELINE.md'
)
foreach ($relative in $Required) {
    Require (Test-Path -LiteralPath (Join-Path $Root $relative)) "Required GA release source is present: $relative"
}

$baseline = Get-Content -LiteralPath (Join-Path $Root 'docs\release\SDV_V1_GA_BASELINE.json') -Raw | ConvertFrom-Json
Require ([string]$baseline.version -eq '1.0.0') 'GA baseline marker declares version 1.0.0'
Require ([string]$baseline.frozenUnits -eq '0-25') 'GA baseline marker declares Units 0-25 frozen'
Require ([string]$baseline.latestMigration -eq '010') 'GA baseline marker declares migration 010 ceiling'
Require ([string]$baseline.status -eq 'RELEASE BASELINE / FROZEN') 'GA baseline marker declares frozen release status'

$lock = Join-Path $Root 'frontend\sdv-ui\package-lock.json'
$lockSha = (Get-FileHash -Algorithm SHA256 -LiteralPath $lock).Hash.ToLowerInvariant()
Require ($lockSha.Length -eq 64) 'Frontend dependency lock has a stable SHA-256 fingerprint'

$master = Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-web\src\main\resources\db\changelog\db.changelog-master.yaml') -Raw
$matches010 = [regex]::Matches($master, '010-performance-resilience-hardening').Count
Require ($matches010 -eq 1) 'Migration 010 appears exactly once in the Liquibase master'
$after010 = Get-ChildItem -LiteralPath (Join-Path $Root 'backend\sdv-web\src\main\resources\db\changelog') -File | Where-Object { $_.Name -match '^(?:011|01[2-9]|0[2-9][0-9]|[1-9][0-9]{2,})-' }
Require (@($after010).Count -eq 0) 'No migration exists beyond frozen migration 010'

$localFilter = Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-security\src\main\java\com\sdv\platform\security\auth\LocalHeaderAuthenticationFilter.java') -Raw
Require ($localFilter -match '@ConditionalOnProperty') 'Frozen Unit 21 auth-mode isolation annotation is present'
Require ($localFilter -match 'havingValue\s*=\s*"local-header"') 'Local-header filter remains conditional on local-header mode'

New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

function Get-RelativePath([string]$FullName) {
    $relative = $FullName.Substring($Root.Length)
    while ($relative.StartsWith('\') -or $relative.StartsWith('/')) { $relative = $relative.Substring(1) }
    return $relative.Replace('\','/')
}

function Is-Excluded([string]$RelativePath) {
    $normalized = $RelativePath.Replace('\','/')
    $parts = $normalized.Split('/')
    $blockedDirectories = @('.git','.idea','.vscode','.runtime','target','node_modules','dist','release')
    foreach ($part in $parts) { if ($blockedDirectories -contains $part) { return $true } }
    $name = [IO.Path]::GetFileName($normalized)
    if ($name -match '^\.env($|\.)') { return $true }
    if ($name -match '\.log$') { return $true }
    if ($name -match '\.tsbuildinfo$') { return $true }
    if ($name -match '\.class$') { return $true }
    if ($name -match '\.zip$') { return $true }
    return $false
}

$SensitiveExtensions = @('.pem','.key','.p12','.pfx','.jks','.keystore')
$files = New-Object System.Collections.Generic.List[object]
Get-ChildItem -LiteralPath $Root -Recurse -File -Force | ForEach-Object {
    $relative = Get-RelativePath $_.FullName
    if (-not (Is-Excluded $relative)) {
        if ($SensitiveExtensions -contains $_.Extension.ToLowerInvariant()) {
            throw "Potential secret/private-key material is present in GA release source: $relative"
        }
        $files.Add([PSCustomObject]@{ File=$_; Relative=$relative })
    }
}
Require ($files.Count -ge 20) 'GA source enumeration is non-empty and plausible'

$archiveName = "sdv-v1-$Version-source.zip"
$archivePath = Join-Path $OutputDirectory $archiveName
$checksumPath = "$archivePath.sha256"
$manifestPath = Join-Path $OutputDirectory 'sdv-v1-ga-manifest.json'
foreach ($existing in @($archivePath,$checksumPath,$manifestPath)) {
    Remove-Item -LiteralPath $existing -Force -ErrorAction SilentlyContinue
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($archivePath,[IO.FileMode]::CreateNew,[IO.FileAccess]::ReadWrite,[IO.FileShare]::None)
try {
    $zip = New-Object -TypeName System.IO.Compression.ZipArchive -ArgumentList @($stream,[IO.Compression.ZipArchiveMode]::Create,$false)
    try {
        foreach ($item in ($files | Sort-Object Relative)) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip,$item.File.FullName,$item.Relative,[System.IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally { $zip.Dispose() }
} finally { $stream.Dispose() }

$sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumPath -Value "$sha  $archiveName" -Encoding ASCII

$manifest = [ordered]@{
    product = 'Secure Document Vault'
    release = 'V1'
    version = $Version
    status = 'GENERAL AVAILABILITY / FROZEN'
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
    frozenUnits = '0-25'
    runtimeUnits = '0-24'
    latestMigration = '010'
    sourceArchive = $archiveName
    sha256 = $sha
    frontendPackageLockSha256 = $lockSha
    fileCount = $files.Count
    cleanInstallValidation = 'REQUIRED ON FRESH WINDOWS MACHINE/VM BEFORE PILOT DEPLOYMENT'
    cleanInstallScript = 'Test-SDV-V1-GA-CleanInstall.ps1'
    exclusions = @('.git','.idea','.vscode','.runtime','target','node_modules','dist','release','.env*','*.log','*.class','*.tsbuildinfo','*.zip','private-key/certificate-keystore extensions')
}
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

# Re-open and verify the completed artifact rather than trusting creation alone.
$verifySha = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
Require ($verifySha -eq $sha) 'Recomputed GA archive SHA-256 matches the recorded hash'
$sidecar = (Get-Content -LiteralPath $checksumPath -Raw).Trim()
Require ($sidecar -eq "$sha  $archiveName") 'GA checksum sidecar matches the archive'
$verifyManifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
Require ([string]$verifyManifest.sha256 -eq $sha) 'GA manifest SHA-256 matches the archive'
Require ([string]$verifyManifest.status -eq 'GENERAL AVAILABILITY / FROZEN') 'GA manifest carries the final frozen release label'

$extract = Join-Path $OutputDirectory ("verify-" + [guid]::NewGuid().ToString('N'))
try {
    [System.IO.Compression.ZipFile]::ExtractToDirectory($archivePath,$extract)
    Require (Test-Path -LiteralPath (Join-Path $extract 'backend\pom.xml')) 'GA archive extracts successfully and retains backend source'
    Require (Test-Path -LiteralPath (Join-Path $extract 'frontend\sdv-ui\package-lock.json')) 'GA archive retains the validated frontend dependency lock'
    Require (Test-Path -LiteralPath (Join-Path $extract 'Test-SDV-V1-GA-CleanInstall.ps1')) 'GA archive contains the clean-install acceptance script'
} finally {
    Remove-Item -LiteralPath $extract -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
Write-Host '=== SDV V1.0.0 GA SOURCE CUT ===' -ForegroundColor Cyan
Write-Host "Archive : $archivePath"
Write-Host "SHA-256: $sha"
Write-Host "Manifest: $manifestPath"
Write-Host ''
Write-Host 'NEXT: copy the archive + .sha256 + manifest to a fresh Windows machine/VM and run the clean-install acceptance.' -ForegroundColor Yellow
