<#
.SYNOPSIS
  The owner's step: makes a release licence signing key (task 0093). Runbook, "Release licence keys".

.DESCRIPTION
  Run by the owner, on the owner's computer, never by a coding session. It makes an Ed25519 key pair and writes
    - <KeyFolder>\<KeyId>.private-key       the private key; only this Windows user may read it
    - <KeyFolder>\<KeyId>.public.json       the public key
    - backend\licence-build\release         the public key again, beside those that are there: what a release build trusts
  KeyFolder is a folder of the owner's outside every repository and every folder that is copied to the cloud.
  It never overwrites a key and prints nothing secret. Windows PowerShell 5.1.

.EXAMPLE
  .\New-SDV-ReleaseLicenceKey.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026
.EXAMPLE
  .\New-SDV-ReleaseLicenceKey.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026 -PublishOnly
  Writes the public key of a key that is there already into this checkout's release build.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$KeyFolder,
    [Parameter(Mandatory = $true)][string]$KeyId,
    [switch]$PublishOnly,
    # For the check script only: another place than this checkout's release build.
    [string]$BuildFolder = (Join-Path $PSScriptRoot 'backend\licence-build\release\META-INF\platform')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'tools\licence\LicenceScripts.ps1')

if ($PSVersionTable.PSEdition -ne 'Desktop') { Refuse 'Run this in Windows PowerShell (powershell.exe).' }
if ($KeyId -cnotmatch '^[a-z0-9][a-z0-9._-]{1,60}[a-z0-9]$') { Refuse 'KeyId: small letters, digits, dot, dash, underscore; for example vero-release-2026.' }
if ($KeyId -like 'vero-dev*' -or $KeyId -like '*development*') { Refuse 'That is the name of a development key.' }
$folder = Assert-SafeFolder $KeyFolder 'KeyFolder'
$private = Join-Path $folder "$KeyId.private-key"
$public = Join-Path $folder "$KeyId.public.json"
$buildKeys = Join-Path $BuildFolder 'licence-keys.json'
$buildProperties = Join-Path $BuildFolder 'licence-build.properties'

if (Test-Path -LiteralPath $buildKeys) {
    $listed = @((Get-Content -LiteralPath $buildKeys -Raw | ConvertFrom-Json).keys | ForEach-Object { $_.keyId })
    if ($listed -contains $KeyId) { Refuse "The release build lists the key $KeyId already. A key is never replaced; give a new one a new KeyId." }
}

if ($PublishOnly) {
    if (-not (Test-Path -LiteralPath $public)) { Refuse "$public isn't there." }
} else {
    foreach ($file in $private, $public) { if (Test-Path -LiteralPath $file) { Refuse "$file is there already. A key is never overwritten; give a new one a new KeyId." } }
    # The empty file first, for this user only; then the key goes into it. CreateNew never replaces one.
    $stream = [IO.File]::Open($private, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $code = 2
    try {
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true, $false)
        $me = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($me, 'FullControl', 'Allow')))
        [IO.File]::SetAccessControl($private, $acl)
        $stream.Dispose()
        $rights = [IO.File]::GetAccessControl($private)
        $rules = @($rights.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
        if (-not $rights.AreAccessRulesProtected -or $rules.Count -ne 1 -or $rules[0].IdentityReference -ne $me) {
            Write-Host 'REFUSED: the folder''s drive can''t keep a file for one user (NTFS is needed).' -ForegroundColor Red
        } else {
            $code = Invoke-LicenceTool $PSScriptRoot @('new-key', $private, $public, $KeyId)
        }
    } finally {
        $stream.Dispose()
        # Only the two files this run made, and only when the key isn't complete.
        if ($code -ne 0) { foreach ($file in $private, $public) { if (Test-Path -LiteralPath $file) { Remove-Item -LiteralPath $file -Force } } }
    }
    if ($code -ne 0) { Write-Host 'No key was made.' -ForegroundColor Red; exit 2 }
    Write-Host "Private key: $private (this Windows user only)"
}

if ((Invoke-LicenceTool $PSScriptRoot @('publish', $public, $buildKeys, $buildProperties)) -ne 0) {
    Write-Host 'The public key isn''t in the release build. After the cause is fixed, run again with -PublishOnly.' -ForegroundColor Red
    exit 2
}
Write-Host 'Next: make the offline copy of the private key, and commit backend\licence-build\release (public keys only).' -ForegroundColor Green
exit 0
