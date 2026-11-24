[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '.runtime\release-readiness\package'),
    [string]$Version = '1.0.0-rc'
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = (Resolve-Path $PSScriptRoot).Path.TrimEnd([IO.Path]::DirectorySeparatorChar)
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null

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
    'docs\operations\SDV_BACKUP_RESTORE_HA_RUNBOOK.md',
    'docs\operations\SDV_V1_RELEASE_READINESS_RUNBOOK.md',
    'docs\development\V1_ACCEPTANCE_RELEASE_BASELINE_UNIT25.md',
    'Test-SDV-V1-Release-Readiness-Unit25.ps1'
)
foreach ($relative in $Required) {
    if (-not (Test-Path -LiteralPath (Join-Path $Root $relative))) {
        throw "Required release source is missing: $relative"
    }
}

$archiveName = "sdv-v1-$Version-source.zip"
$archivePath = Join-Path $OutputDirectory $archiveName
$checksumPath = "$archivePath.sha256"
$manifestPath = Join-Path $OutputDirectory 'sdv-v1-release-manifest.json'
foreach ($existing in @($archivePath,$checksumPath,$manifestPath)) {
    Remove-Item -LiteralPath $existing -Force -ErrorAction SilentlyContinue
}

# Task 0064: the committed files only, never the folder.
$archive = & (Join-Path $Root 'scripts\release\New-SdvSourceArchive.ps1') -Root $Root -ArchivePath $archivePath -Required $Required

$sha = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash.ToLowerInvariant()
Set-Content -LiteralPath $checksumPath -Value "$sha  $archiveName" -Encoding ASCII

$manifest = [ordered]@{
    product = 'Secure Document Vault'
    release = 'V1'
    version = $Version
    status = 'RELEASE CANDIDATE - UNIT 25 VALIDATION'
    createdAt = [DateTimeOffset]::UtcNow.ToString('o')
    frozenUnits = '0-24'
    latestMigration = '010'
    sourceArchive = $archiveName
    sha256 = $sha
    fileCount = $archive.FileCount
    commit = $archive.Commit
    contents = 'git archive of the commit: tracked files only, without demo files, .env files, logs and archives'
}
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host "Release source package created: $archivePath"
Write-Host "SHA-256: $sha"
Write-Host "Manifest: $manifestPath"
