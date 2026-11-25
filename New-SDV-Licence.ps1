<#
.SYNOPSIS
  The owner's step: signs a Vero licence for a customer or the testers (task 0093). Runbook, "Release licence keys".

.DESCRIPTION
  Run by the owner, never by a coding session. It reads a small input file (dev\licence\licence-input.example.json:
  customer, entities, dates, seats; a field it doesn't know, such as a price, refuses the file), signs the licence with the
  private key in KeyFolder, checks it with the keys the release build trusts, and writes it. It never overwrites a licence and
  prints nothing secret. "new" as tenantId (version 1 only) or slotId makes an ID; keep it for every later version.

.EXAMPLE
  .\New-SDV-Licence.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026 -InputFile D:\licences\acme-1.json -OutFile D:\licences\acme-1.licence.json
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$KeyFolder,
    [Parameter(Mandatory = $true)][string]$KeyId,
    [Parameter(Mandatory = $true)][string]$InputFile,
    [Parameter(Mandatory = $true)][string]$OutFile,
    # For the check script only: another place than this checkout's release build.
    [string]$BuildFolder = (Join-Path $PSScriptRoot 'backend\licence-build\release\META-INF\platform')
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
. (Join-Path $PSScriptRoot 'tools\licence\LicenceScripts.ps1')

if ($KeyId -cnotmatch '^[a-z0-9][a-z0-9._-]{1,60}[a-z0-9]$') { Refuse 'KeyId: small letters, digits, dot, dash, underscore; for example vero-release-2026.' }
$private = Join-Path (Assert-SafeFolder $KeyFolder 'KeyFolder') "$KeyId.private-key"
if (-not (Test-Path -LiteralPath $private -PathType Leaf)) { Refuse "$private isn't there." }
if (-not (Test-Path -LiteralPath $InputFile -PathType Leaf)) { Refuse "The input file $InputFile isn't there." }
if (-not [IO.Path]::IsPathRooted($OutFile)) { Refuse 'OutFile must be a full path.' }
$out = Join-Path (Assert-SafeFolder (Split-Path -Parent $OutFile) 'The folder of OutFile') (Split-Path -Leaf $OutFile)
if (Test-Path -LiteralPath $out) { Refuse "$out is there already. A licence is never overwritten." }

if ((Invoke-LicenceTool $PSScriptRoot @('sign', $private, $KeyId, (Join-Path $BuildFolder 'licence-keys.json'), (Resolve-Path -LiteralPath $InputFile).Path, $out)) -ne 0) {
    Write-Host 'No licence was written.' -ForegroundColor Red
    exit 2
}
Write-Host "Licence: $out" -ForegroundColor Green
exit 0
