param([Parameter(Mandatory=$true)][string]$Manifest)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { throw "Manifest not found: $Manifest" }
$m = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
if ($m.schemaVersion -ne 2) { throw 'Unsupported recovery manifest schemaVersion. Re-export the recovery set with the current exporter.' }
if ($m.safety.containsPlaintextDocuments -ne $false -or $m.safety.containsCredentials -ne $false) { throw 'Unsafe recovery manifest safety declaration.' }
foreach ($part in @('database','keyManagement','application')) {
  $e = $m.$part
  if (-not (Test-Path -LiteralPath $e.fullPath -PathType Leaf)) { throw "$part artifact is missing: $($e.fullPath)" }
  $actual = (Get-FileHash -LiteralPath $e.fullPath -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $e.sha256) { throw "$part checksum mismatch." }
  $actualSize = (Get-Item -LiteralPath $e.fullPath).Length
  if ([int64]$actualSize -ne [int64]$e.sizeBytes) { throw "$part size mismatch." }
}
if (-not (Test-Path -LiteralPath $m.encryptedStorage.root -PathType Container)) { throw 'Encrypted storage root is missing.' }
$root = (Resolve-Path -LiteralPath $m.encryptedStorage.root).Path
$files = @(Get-ChildItem -LiteralPath $root -File -Recurse)
if ($files.Count -ne [int]$m.encryptedStorage.objectCount) { throw 'Encrypted storage object-count mismatch.' }
$bytes = ($files | Measure-Object -Property Length -Sum).Sum; if ($null -eq $bytes) { $bytes = 0 }
if ([int64]$bytes -ne [int64]$m.encryptedStorage.totalBytes) { throw 'Encrypted storage byte-count mismatch.' }
$expectedObjects = @($m.encryptedStorage.objects)
if ($expectedObjects.Count -ne [int]$m.encryptedStorage.objectCount) { throw 'Encrypted storage manifest inventory is incomplete.' }
foreach ($e in $expectedObjects) {
  if ([string]::IsNullOrWhiteSpace([string]$e.relativePath)) { throw 'Encrypted storage manifest contains an empty relative path.' }
  $candidate = [IO.Path]::GetFullPath((Join-Path $root ([string]$e.relativePath)))
  $rootPrefix = [IO.Path]::GetFullPath($root).TrimEnd([IO.Path]::DirectorySeparatorChar, [IO.Path]::AltDirectorySeparatorChar) + [IO.Path]::DirectorySeparatorChar
  if (-not $candidate.StartsWith($rootPrefix, [StringComparison]::OrdinalIgnoreCase)) { throw 'Encrypted storage manifest contains a path outside the backup root.' }
  if (-not (Test-Path -LiteralPath $candidate -PathType Leaf)) { throw "Encrypted storage object is missing: $($e.relativePath)" }
  $item = Get-Item -LiteralPath $candidate
  if ([int64]$item.Length -ne [int64]$e.sizeBytes) { throw "Encrypted storage object size mismatch: $($e.relativePath)" }
  $actual = (Get-FileHash -LiteralPath $candidate -Algorithm SHA256).Hash.ToLowerInvariant()
  if ($actual -ne $e.sha256) { throw "Encrypted storage object checksum mismatch: $($e.relativePath)" }
}
Write-Host "Recovery manifest validation PASS: $($m.recoveryPointId)"
