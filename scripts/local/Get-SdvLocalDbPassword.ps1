<#
Task 0064: the password of the local development database, for the local scripts only. It returns the password to the
caller and never prints it. Order:
  1. SDV_LOCAL_DB_PASSWORD, when set.
  2. .runtime\local-db-password.txt (untracked; only this Windows user may read it).
  3. An sdv-postgres container exists: the password it was made with, kept in that file from then on.
  4. Nothing exists yet: a new random one, kept in that file.
A data volume without its container and without the file is refused: the password can't be known.
#>
[CmdletBinding()]
param([Parameter(Mandatory)][string]$Root)

$ErrorActionPreference = 'Stop'
if ($env:SDV_LOCAL_DB_PASSWORD) { return $env:SDV_LOCAL_DB_PASSWORD }

$folder = Join-Path $Root '.runtime'
$file = Join-Path $folder 'local-db-password.txt'
if (Test-Path -LiteralPath $file -PathType Leaf) {
    $kept = ([IO.File]::ReadAllText($file)).Trim()
    if (-not $kept) { throw "The local database password file is empty: $file" }
    return $kept
}

$password = ''
$existing = (& docker ps -a --filter 'name=^/sdv-postgres$' --format '{{.Names}}' | Out-String).Trim()
if ($LASTEXITCODE -ne 0) { throw 'Docker did not answer, so the local database could not be looked for.' }
if ($existing -eq 'sdv-postgres') {
    $settings = ConvertFrom-Json (& docker inspect --format '{{json .Config.Env}}' sdv-postgres | Out-String)
    foreach ($setting in $settings) { if ([string]$setting -like 'POSTGRES_PASSWORD=*') { $password = $setting.Substring('POSTGRES_PASSWORD='.Length) } }
    if (-not $password) { throw 'The sdv-postgres container has no password setting. Set SDV_LOCAL_DB_PASSWORD and start again.' }
} else {
    $volumeName = if ($env:SDV_POSTGRES_VOLUME) { $env:SDV_POSTGRES_VOLUME } else { 'sdv-postgres-data' }
    $volume = (& docker volume ls --filter "name=^$volumeName$" --format '{{.Name}}' | Out-String).Trim()
    if ($volume) { throw "The data volume $volumeName exists, but not its container or $file. Set SDV_LOCAL_DB_PASSWORD to its password and start again." }
    $bytes = New-Object byte[] 24
    $random = [Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    $password = ([BitConverter]::ToString($bytes)).Replace('-', '').ToLowerInvariant()
}

New-Item -ItemType Directory -Force -Path $folder | Out-Null
New-Item -ItemType File -Path $file | Out-Null
& icacls.exe $file /inheritance:r /grant:r "$([Security.Principal.WindowsIdentity]::GetCurrent().Name):(F)" | Out-Null
if ($LASTEXITCODE -ne 0) { Remove-Item -LiteralPath $file -Force; throw "Could not limit who may read $file." }
[IO.File]::WriteAllText($file, $password)
return $password
