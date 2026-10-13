param(
  [Parameter(Mandatory=$true)][string]$DatabaseBackup,
  [Parameter(Mandatory=$true)][string]$StorageBackupRoot,
  [Parameter(Mandatory=$true)][string]$KeyManagementBackup,
  [Parameter(Mandatory=$true)][string]$ApplicationArtifact,
  [Parameter(Mandatory=$true)][string]$OutputDirectory,
  [string]$RecoveryPointId = ([DateTime]::UtcNow.ToString('yyyyMMddTHHmmssZ'))
)
$ErrorActionPreference = 'Stop'
function Require-File([string]$Path,[string]$Label) { if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) { throw "$Label file not found: $Path" } }
function Require-Directory([string]$Path,[string]$Label) { if (-not (Test-Path -LiteralPath $Path -PathType Container)) { throw "$Label directory not found: $Path" } }
function File-Evidence([string]$Path) {
  $item = Get-Item -LiteralPath $Path
  [ordered]@{ name=$item.Name; fullPath=$item.FullName; sizeBytes=$item.Length; sha256=(Get-FileHash -LiteralPath $item.FullName -Algorithm SHA256).Hash.ToLowerInvariant() }
}
function Relative-Path([string]$Root,[string]$Path) {
  $rootFull = [IO.Path]::GetFullPath($Root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
  $pathFull = [IO.Path]::GetFullPath($Path)
  $rootUri = New-Object System.Uri($rootFull)
  $pathUri = New-Object System.Uri($pathFull)
  [Uri]::UnescapeDataString($rootUri.MakeRelativeUri($pathUri).ToString()).Replace('/', [IO.Path]::DirectorySeparatorChar)
}
Require-File $DatabaseBackup 'Database backup'
Require-Directory $StorageBackupRoot 'Encrypted storage backup'
Require-File $KeyManagementBackup 'Key-management backup'
Require-File $ApplicationArtifact 'Application artifact'
New-Item -ItemType Directory -Force -Path $OutputDirectory | Out-Null
$storageRoot = (Resolve-Path -LiteralPath $StorageBackupRoot).Path
$storageFiles = @(Get-ChildItem -LiteralPath $storageRoot -File -Recurse | Sort-Object FullName)
$storageObjects = @($storageFiles | ForEach-Object {
  [ordered]@{
    relativePath = (Relative-Path $storageRoot $_.FullName)
    sizeBytes = $_.Length
    sha256 = (Get-FileHash -LiteralPath $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
  }
})
$storageBytes = ($storageFiles | Measure-Object -Property Length -Sum).Sum
if ($null -eq $storageBytes) { $storageBytes = 0 }
$manifest = [ordered]@{
  schemaVersion = 2
  recoveryPointId = $RecoveryPointId
  createdAt = [DateTime]::UtcNow.ToString('o')
  safety = [ordered]@{ containsPlaintextDocuments=$false; containsCredentials=$false; note='Manifest records references and checksums only. Backup payloads remain separately protected.' }
  database = (File-Evidence $DatabaseBackup)
  encryptedStorage = [ordered]@{ root=$storageRoot; objectCount=$storageFiles.Count; totalBytes=[int64]$storageBytes; objects=$storageObjects }
  keyManagement = (File-Evidence $KeyManagementBackup)
  application = (File-Evidence $ApplicationArtifact)
}
$manifestPath = Join-Path $OutputDirectory "sdv-recovery-manifest-$RecoveryPointId.json"
$manifest | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
Write-Host "Recovery manifest created: $manifestPath"
Write-Host "No database/Vault credentials or document plaintext were added by this tool."
