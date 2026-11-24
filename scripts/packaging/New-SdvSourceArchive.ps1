<#
Task 0064: a source package holds what is committed, never what happens to be in the folder: git archive of one commit.
Refuses uncommitted changes to tracked files, and files that look like a key or hold a .env. Returns the commit and the
number of files.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$Root,
    [Parameter(Mandatory)][string]$ArchivePath,
    [Parameter(Mandatory)][string[]]$Required
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Invoke-Git([string[]]$Arguments) {
    $out = @(& git -C $Root @Arguments)
    if ($LASTEXITCODE -ne 0) { throw "git $($Arguments[0]) failed." }
    return $out
}

$commit = (Invoke-Git @('rev-parse', 'HEAD') | Select-Object -First 1).Trim()
$changed = @(Invoke-Git @('status', '--porcelain', '--untracked-files=no'))
if ($changed.Count -gt 0) { throw "Tracked files have changes that are not committed, so the package would not match the folder:`n$($changed -join "`n")" }

# demo-files: local demo PDFs for Seed-SDV-Canvas-Demo.ps1 only, never part of a package (task 0023).
$paths = @('.', ':(exclude,glob)**/demo-files/**', ':(exclude,glob)**/.env', ':(exclude,glob)**/.env.*', ':(exclude,glob)**/*.log', ':(exclude,glob)**/*.zip')
$files = @(Invoke-Git (@('-c', 'core.quotepath=off', 'ls-files', '--') + $paths))
if ($files.Count -lt 20) { throw 'The release source is unexpectedly small.' }
foreach ($relative in $Required) {
    if ($files -notcontains $relative.Replace('\', '/')) { throw "Required release source is not committed: $relative" }
}
$sensitive = @($files | Where-Object { $_ -match '\.(pem|key|p12|pfx|jks|keystore|token)$' })
if ($sensitive.Count -gt 0) { throw "Potential secret/private-key material is in the release source: $($sensitive -join ', ')" }

$null = Invoke-Git (@('archive', '--format=zip', "--output=$ArchivePath", $commit, '--') + $paths)

Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($ArchivePath)
try { $entries = @($zip.Entries | Where-Object { -not $_.FullName.EndsWith('/') }).Count } finally { $zip.Dispose() }
if ($entries -ne $files.Count) { throw "The package has $entries files, the commit $($files.Count)." }
return [PSCustomObject]@{ Commit = $commit; FileCount = $entries }
