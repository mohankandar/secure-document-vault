[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)]
    [string]$RuntimeDirectory
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Runtime = [IO.Path]::GetFullPath((Join-Path $Root $RuntimeDirectory))
$Logs = Join-Path $Runtime 'logs'
$PackageEvidence = Join-Path $Runtime 'package'

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition,[string]$Message) { if(-not $Condition){ throw $Message }; Ok $Message }

Require (Test-Path -LiteralPath $Runtime -PathType Container) "Prior Unit 25 runtime evidence exists: $Runtime"
Require (Test-Path -LiteralPath $Logs -PathType Container) 'Prior Unit 25 regression log directory exists'
Require ($null -ne (Get-Command docker -ErrorAction SilentlyContinue)) 'Docker CLI is available for final clean-shutdown verification'
$null = & cmd.exe /d /c 'docker info 1>nul 2>&1'
Require ($LASTEXITCODE -eq 0) 'Docker engine is reachable'

Write-Host '=== RESUME PHASE A - Revalidate all 12 frozen regression PASS logs ==='
$expected = @(
    @{ script='Test-SDV-Foundation.ps1'; pass='PASS - SDV Foundation Units 1-4 acceptance passed.' },
    @{ script='Test-SDV-SecureIngestion-A.ps1'; pass='PASS - SDV Secure Ingestion Checkpoint A (Units 5, 7, 8) acceptance passed.' },
    @{ script='Test-SDV-SecureIngestion-B.ps1'; pass='PASS - SDV Secure Ingestion Checkpoint B (Units 6, 9, 10) acceptance passed.' },
    @{ script='Test-SDV-BusinessExperience-A.ps1'; pass='PASS - SDV Business Experience Checkpoint A (Units 11-13) acceptance passed.' },
    @{ script='Test-SDV-BusinessExperience-B.ps1'; pass='PASS - SDV Business Experience Checkpoint B (Units 14-15) acceptance passed.' },
    @{ script='Test-SDV-ControlledActions-A-Unit16.ps1'; pass='PASS - SDV Controlled Document Actions Checkpoint A / Unit 16 acceptance passed.' },
    @{ script='Test-SDV-ControlledActions-B-Units17-18.ps1'; pass='PASS - SDV Controlled Document Actions Checkpoint B (Units 17-18) acceptance passed.' },
    @{ script='Test-SDV-EnterpriseReadiness-A-Units19-20.ps1'; pass='PASS - SDV Enterprise / Operational Readiness Checkpoint A (Units 19-20) acceptance passed.' },
    @{ script='Test-SDV-EnterpriseReadiness-B-Unit21.ps1'; pass='PASS - SDV Enterprise / Operational Readiness Checkpoint B (Unit 21) acceptance passed.' },
    @{ script='Test-SDV-EnterpriseReadiness-C-Unit22.ps1'; pass='PASS - SDV Enterprise / Operational Readiness Checkpoint C (Unit 22) acceptance passed.' },
    @{ script='Test-SDV-EnterpriseReadiness-D-Unit23.ps1'; pass='PASS - SDV Enterprise / Operational Readiness Checkpoint D (Unit 23) acceptance passed.' },
    @{ script='Test-SDV-EnterpriseReadiness-E-Unit24.ps1'; pass='PASS - SDV Enterprise / Operational Readiness Checkpoint E (Unit 24) acceptance passed.' }
)
$acceptanceGroups = New-Object System.Collections.Generic.List[object]
foreach ($entry in $expected) {
    $logName = $entry.script.Replace('.ps1','.log')
    $logPath = Join-Path $Logs $logName
    Require (Test-Path -LiteralPath $logPath -PathType Leaf) "Regression evidence log exists: $logName"
    $logText = Get-Content -LiteralPath $logPath -Raw
    Require ($logText.Contains([string]$entry.pass)) "Regression evidence contains PASS result: $($entry.script)"
    $acceptanceGroups.Add([PSCustomObject]@{ script=$entry.script; status='PASS'; log=$logName })
}
Require ($acceptanceGroups.Count -eq 12) 'All 12 frozen acceptance PASS results are revalidated from prior-run evidence'

Write-Host ''
Write-Host '=== RESUME PHASE B - Regenerate package with FIX3 release tooling ==='
New-Item -ItemType Directory -Force -Path $PackageEvidence | Out-Null
& (Join-Path $Root 'New-SDV-V1-ReleasePackage.ps1') -OutputDirectory $PackageEvidence -Version '1.0.0-rc'
if ($LASTEXITCODE -ne 0) { throw 'Release source packaging failed during FIX3 completion.' }
$archive = Get-ChildItem -LiteralPath $PackageEvidence -Filter 'sdv-v1-*-source.zip' -File | Select-Object -First 1
Require ($null -ne $archive -and $archive.Length -gt 10000) 'Corrected FIX3 release source archive was created'
$checksumFile = "$($archive.FullName).sha256"
$manifestPath = Join-Path $PackageEvidence 'sdv-v1-release-manifest.json'
Require (Test-Path -LiteralPath $checksumFile) 'Release SHA-256 sidecar exists'
Require (Test-Path -LiteralPath $manifestPath) 'Release manifest exists'
$manifest = Get-Content -LiteralPath $manifestPath -Raw | ConvertFrom-Json
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive.FullName).Hash.ToLowerInvariant()
Require ([string]$manifest.sha256 -eq $actualHash) 'Release manifest SHA-256 matches corrected archive'
Require ((Get-Content -LiteralPath $checksumFile -Raw).Trim().StartsWith($actualHash)) 'Release checksum sidecar matches corrected archive'
Require ([string]$manifest.latestMigration -eq '010' -and [string]$manifest.frozenUnits -eq '0-24') 'Corrected release manifest records Units 0-24 / migration 010'

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($archive.FullName)
try { $entries = @($zip.Entries | ForEach-Object { $_.FullName }) } finally { $zip.Dispose() }
$forbidden = @($entries | Where-Object {
    $_ -match '(^|/)(target|node_modules|dist|\.runtime|\.git|\.idea|\.vscode|release)(/|$)' -or
    $_ -match '(^|/)\.env($|\.)' -or
    $_ -match '\.(log|class|tsbuildinfo)$' -or
    $_ -match '\.(pem|key|p12|pfx|jks|keystore)$'
})
Require ($forbidden.Count -eq 0) 'Corrected release archive still satisfies exclusion/security policy'
foreach ($required in @(
    'README.md','backend/pom.xml','frontend/sdv-ui/package.json','frontend/sdv-ui/package-lock.json',
    'docs/api/sdv-ingestion-v1.openapi.json','docs/operations/SDV_BACKUP_RESTORE_HA_RUNBOOK.md',
    'docs/operations/SDV_V1_RELEASE_READINESS_RUNBOOK.md','Test-SDV-V1-Release-Readiness-Unit25.ps1'
)) {
    Require ($entries -contains $required) "Corrected release archive contains required source: $required"
}

Write-Host ''
Write-Host '=== RESUME PHASE C - Prove this prior run left no Docker container leak ==='
$runStartedUtc = (Get-Item -LiteralPath $Runtime).CreationTimeUtc
$currentSdvContainers = @(& docker ps -a --format '{{.Names}}' | Where-Object { $_ -match '^sdv-' })
$runCreatedLeftovers = New-Object System.Collections.Generic.List[string]
foreach ($name in $currentSdvContainers) {
    $createdRaw = (& docker inspect --format '{{.Created}}' $name 2>$null | Out-String).Trim()
    if ([string]::IsNullOrWhiteSpace($createdRaw)) { continue }
    $created = [DateTimeOffset]::Parse($createdRaw).UtcDateTime
    if ($created -ge $runStartedUtc) { $runCreatedLeftovers.Add($name) }
}
if ($runCreatedLeftovers.Count -gt 0) {
    throw "Prior Unit 25 run left SDV container(s) created during that run: $($runCreatedLeftovers -join ', ')"
}
if ($currentSdvContainers.Count -gt 0) {
    Ok "No container created during the prior Unit 25 run remains; pre-existing SDV-named container(s) are preserved: $($currentSdvContainers -join ', ')"
} else {
    Ok 'No SDV Docker containers remain after the prior Unit 25 run'
}

Write-Host ''
Write-Host '=== RESUME PHASE D - Final V1 release evidence ==='
$evidence = [ordered]@{
    product='Secure Document Vault'
    release='V1'
    checkpoint='Unit 25 - V1 Acceptance / Release Baseline'
    result='PASS'
    completedAt=[DateTimeOffset]::UtcNow.ToString('o')
    completionMode='FIX3 validated resume from prior full regression'
    priorRunStartedAt=$runStartedUtc.ToString('o')
    frozenUnits='0-24'
    latestMigration='010'
    acceptanceGroups=$acceptanceGroups
    releaseArchive=$archive.Name
    releaseArchiveSha256=$actualHash
    packageManifest='sdv-v1-release-manifest.json'
    remainingPreExistingSdvContainers=$currentSdvContainers
    runCreatedContainerLeaks=$runCreatedLeftovers
}
$evidencePath = Join-Path $Runtime 'SDV-V1-RELEASE-EVIDENCE.json'
$evidence | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
Require (Test-Path -LiteralPath $evidencePath) 'Final V1 release evidence JSON was written into the original Unit 25 runtime'

Write-Host ''
Write-Host '=== RESULT ==='
Write-Host 'PASS - SDV V1 Acceptance / Release Baseline (Unit 25) passed.' -ForegroundColor Green
Write-Host "Evidence: $Runtime"
Write-Host "Corrected release candidate package evidence: $PackageEvidence"
