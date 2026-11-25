param([Parameter(Mandatory=$true)][string]$Manifest)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Manifest -PathType Leaf)) { throw "Manifest not found: $Manifest" }
$m = Get-Content -LiteralPath $Manifest -Raw | ConvertFrom-Json
if ($m.schemaVersion -ne 3) { throw 'Unsupported recovery manifest schemaVersion. Re-export the recovery set with the current exporter.' }
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
# Task 0080: the erase log is the one of then, and nothing that was in it is missing or changed. It may have grown.
function Prefix-Sha([string]$Path, [int64]$Bytes) {
  $sha = [Security.Cryptography.SHA256]::Create(); $stream = [IO.File]::Open($Path, 'Open', 'Read', 'ReadWrite')
  try {
    $buffer = New-Object byte[] 65536; $left = $Bytes
    while ($left -gt 0) { $n = $stream.Read($buffer, 0, [int][Math]::Min($buffer.Length, $left)); if ($n -le 0) { throw 'Erase log is shorter than in the manifest.' }; $null = $sha.TransformBlock($buffer, 0, $n, $null, 0); $left -= $n }
    $null = $sha.TransformFinalBlock($buffer, 0, 0)
    -join ($sha.Hash | ForEach-Object { $_.ToString('x2') })
  } finally { $stream.Dispose(); $sha.Dispose() }
}
$logThen = @{}; foreach ($e in @($m.eraseLog.files)) { $logThen[[string]$e.name] = $e }
$idPath = Join-Path $m.eraseLog.folder 'erase-log.id'
if (-not (Test-Path -LiteralPath $idPath -PathType Leaf)) { throw 'Erase log is missing: without it a restored database must not be started.' }
if (-not $logThen.ContainsKey('erase-log.id') -or (Get-FileHash -LiteralPath $idPath -Algorithm SHA256).Hash.ToLowerInvariant() -ne $logThen['erase-log.id'].sha256) { throw 'Erase log is another log than in the manifest.' }
$checkpoint = Join-Path $m.eraseLog.folder 'erase-log.checkpoint'
$checkpointNow = if (Test-Path -LiteralPath $checkpoint -PathType Leaf) { (Get-FileHash -LiteralPath $checkpoint -Algorithm SHA256).Hash.ToLowerInvariant() } else { '' }
$checkpointThen = if ($logThen.ContainsKey('erase-log.checkpoint')) { [string]$logThen['erase-log.checkpoint'].sha256 } else { '' }
if ($checkpointNow -ne $checkpointThen) { Write-Host 'Erase log: old entries were removed since (its own retention); Vero checks the rest at its start.' }
elseif ($logThen.ContainsKey('erase-log.jsonl')) {
  $jsonl = Join-Path $m.eraseLog.folder 'erase-log.jsonl'
  if (-not (Test-Path -LiteralPath $jsonl -PathType Leaf)) { throw 'Erase log entries are missing.' }
  if ((Prefix-Sha $jsonl ([int64]$logThen['erase-log.jsonl'].sizeBytes)) -ne $logThen['erase-log.jsonl'].sha256) { throw 'Erase log entries were changed since the manifest.' }
}
Write-Host "Recovery manifest validation PASS: $($m.recoveryPointId)"
