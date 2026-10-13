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

function Get-RelativePath([string]$FullName) {
    $relative = $FullName.Substring($Root.Length)
    while ($relative.StartsWith('\') -or $relative.StartsWith('/')) {
        $relative = $relative.Substring(1)
    }
    return $relative.Replace('\','/')
}

function Is-Excluded([string]$RelativePath) {
    $normalized = $RelativePath.Replace('\','/')
    $parts = $normalized.Split('/')
    $blockedDirectories = @('.git','.idea','.vscode','.runtime','target','node_modules','dist','release')
    foreach ($part in $parts) {
        if ($blockedDirectories -contains $part) { return $true }
    }
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
            throw "Potential secret/private-key material is present in release source: $relative"
        }
        $files.Add([PSCustomObject]@{ File=$_; Relative=$relative })
    }
}

if ($files.Count -lt 20) { throw 'Release source enumeration is unexpectedly small.' }

$archiveName = "sdv-v1-$Version-source.zip"
$archivePath = Join-Path $OutputDirectory $archiveName
$checksumPath = "$archivePath.sha256"
$manifestPath = Join-Path $OutputDirectory 'sdv-v1-release-manifest.json'
foreach ($existing in @($archivePath,$checksumPath,$manifestPath)) {
    Remove-Item -LiteralPath $existing -Force -ErrorAction SilentlyContinue
}

Add-Type -AssemblyName System.IO.Compression
Add-Type -AssemblyName System.IO.Compression.FileSystem
$stream = [IO.File]::Open($archivePath, [IO.FileMode]::CreateNew, [IO.FileAccess]::ReadWrite, [IO.FileShare]::None)
try {
    $zip = New-Object -TypeName System.IO.Compression.ZipArchive -ArgumentList @($stream, [IO.Compression.ZipArchiveMode]::Create, $false)
    try {
        foreach ($item in ($files | Sort-Object Relative)) {
            [System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
                $zip,
                $item.File.FullName,
                $item.Relative,
                [System.IO.Compression.CompressionLevel]::Optimal
            ) | Out-Null
        }
    } finally {
        $zip.Dispose()
    }
} finally {
    $stream.Dispose()
}

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
    fileCount = $files.Count
    exclusions = @('.git','.idea','.vscode','.runtime','target','node_modules','dist','release','.env*','*.log','*.class','*.tsbuildinfo','*.zip','private-key/certificate-keystore extensions')
}
$manifest | ConvertTo-Json -Depth 10 | Set-Content -LiteralPath $manifestPath -Encoding UTF8

Write-Host "Release source package created: $archivePath"
Write-Host "SHA-256: $sha"
Write-Host "Manifest: $manifestPath"
