<#
.SYNOPSIS
  Checks New-SDV-ReleaseLicenceKey.ps1 and New-SDV-Licence.ps1 with throwaway keys (task 0093).

.DESCRIPTION
  Everything is in a temporary folder that is deleted at the end; the checkout's own release build isn't touched, and Vero,
  the database and Vault aren't used. The last step runs the module test that gives both licences to the setup command's own
  licence check.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$work = Join-Path ([IO.Path]::GetTempPath()) ('vero-licence-check-' + [Guid]::NewGuid().ToString('N'))
$script:failed = 0; $script:passed = 0

function Check([string]$What, [bool]$Ok) {
    if ($Ok) { $script:passed++; Write-Host "[PASS] $What" } else { $script:failed++; Write-Host "[FAIL] $What" -ForegroundColor Red }
}
function Run([string]$Script, [string[]]$Arguments) {
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { $text = (& powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot $Script) @Arguments 2>&1 | Out-String) }
    finally { $ErrorActionPreference = $before }
    [pscustomobject]@{ Code = $LASTEXITCODE; Text = $text }
}

$releaseBuild = Join-Path $PSScriptRoot 'backend\licence-build\release'
function State { if (Test-Path -LiteralPath $releaseBuild) { (Get-ChildItem -LiteralPath $releaseBuild -Recurse -File | Get-FileHash | ForEach-Object { $_.Hash }) -join ',' } else { 'none' } }
$stateBefore = State

try {
    # The real path: the temporary folder's short name (8.3) would not be a link, but keep it plain.
    $null = New-Item -ItemType Directory -Path $work
    $work = (Get-Item -LiteralPath $work).FullName
    $keys = Join-Path $work 'keys'; $build = Join-Path $work 'build'; $other = Join-Path $work 'other-build'
    $null = New-Item -ItemType Directory -Path $keys
    $a = 'throwaway-a'; $b = 'throwaway-b'
    $private = Join-Path $keys "$a.private-key"

    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $keys, '-KeyId', $a, '-BuildFolder', $build)
    Check 'A key is made' ($r.Code -eq 0)
    if ($r.Code -ne 0) { Write-Host $r.Text; throw 'No key, nothing more to check.' }
    $secret = [IO.File]::ReadAllText($private)
    Check 'Nothing secret is printed' ($secret.Length -gt 40 -and -not $r.Text.Contains($secret) -and -not $r.Text.Contains($secret.Substring(24)))
    $rights = [IO.File]::GetAccessControl($private)
    $rules = @($rights.GetAccessRules($true, $true, [Security.Principal.SecurityIdentifier]))
    Check 'Only this Windows user may read the private key' ($rights.AreAccessRulesProtected -and $rules.Count -eq 1 -and $rules[0].IdentityReference -eq [Security.Principal.WindowsIdentity]::GetCurrent().User)
    $published = [IO.File]::ReadAllText((Join-Path $build 'licence-keys.json'))
    Check 'The release build holds the public key only, and no development mark' ($published.Contains($a) -and -not $published.Contains($secret) -and -not $published.Contains('development'))
    Check 'licence-build.properties has the build time' ([IO.File]::ReadAllText((Join-Path $build 'licence-build.properties')).Trim() -eq 'built-at=${maven.build.timestamp}')

    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $keys, '-KeyId', $a, '-BuildFolder', $build)
    Check 'The same key again is refused' ($r.Code -eq 1 -and $r.Text.Contains('REFUSED'))
    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $keys, '-KeyId', $a, '-BuildFolder', $other)
    Check 'A private key that is there is refused' ($r.Code -eq 1)
    Check 'The private key is unchanged' ([IO.File]::ReadAllText($private) -eq $secret)
    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $PSScriptRoot, '-KeyId', 'throwaway-x', '-BuildFolder', $other)
    Check 'A folder inside a repository is refused' ($r.Code -eq 1 -and $r.Text.Contains('repository'))
    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', 'keys', '-KeyId', 'throwaway-x', '-BuildFolder', $other)
    Check 'A path that isn''t full is refused' ($r.Code -eq 1)
    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $keys, '-KeyId', 'vero-dev-x', '-BuildFolder', $other)
    Check 'A development key''s name is refused' ($r.Code -eq 1)
    $link = Join-Path $work 'link'; $null = New-Item -ItemType Junction -Path $link -Target $keys
    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $link, '-KeyId', 'throwaway-x', '-BuildFolder', $other)
    Check 'A link is refused' ($r.Code -eq 1 -and $r.Text.Contains('link'))
    [IO.Directory]::Delete($link)

    $r = Run 'New-SDV-ReleaseLicenceKey.ps1' @('-KeyFolder', $keys, '-KeyId', $b, '-BuildFolder', $other)
    Check 'A second key is made in another build' ($r.Code -eq 0)

    $example = Join-Path $PSScriptRoot 'dev\licence\licence-input.example.json'
    $licence = Join-Path $work 'a.licence.json'; $foreign = Join-Path $work 'b.licence.json'
    $r = Run 'New-SDV-Licence.ps1' @('-KeyFolder', $keys, '-KeyId', $a, '-InputFile', $example, '-OutFile', $licence, '-BuildFolder', $build)
    Check 'A licence is signed' ($r.Code -eq 0 -and (Test-Path -LiteralPath $licence))
    if ($r.Code -ne 0) { Write-Host $r.Text }
    Check 'Nothing secret is printed by signing' (-not $r.Text.Contains($secret))
    $r = Run 'New-SDV-Licence.ps1' @('-KeyFolder', $keys, '-KeyId', $a, '-InputFile', $example, '-OutFile', $licence, '-BuildFolder', $build)
    Check 'A licence is never overwritten' ($r.Code -eq 1)
    $r = Run 'New-SDV-Licence.ps1' @('-KeyFolder', $keys, '-KeyId', $b, '-InputFile', $example, '-OutFile', $foreign, '-BuildFolder', $build)
    Check 'A key the build doesn''t trust can''t sign' ($r.Code -eq 2 -and -not (Test-Path -LiteralPath $foreign))
    $r = Run 'New-SDV-Licence.ps1' @('-KeyFolder', $keys, '-KeyId', $b, '-InputFile', $example, '-OutFile', $foreign, '-BuildFolder', $other)
    Check 'The second key signs for its own build' ($r.Code -eq 0)

    # The second key under the first one's name: the file says throwaway-a, but the key isn't the one the build trusts.
    $swapped = Join-Path $work 'swapped'; $null = New-Item -ItemType Directory -Path $swapped
    Copy-Item -LiteralPath (Join-Path $keys "$b.private-key") -Destination (Join-Path $swapped "$a.private-key")
    $r = Run 'New-SDV-Licence.ps1' @('-KeyFolder', $swapped, '-KeyId', $a, '-InputFile', $example, '-OutFile', (Join-Path $work 'swapped.json'), '-BuildFolder', $build)
    Check 'Another key under a trusted name can''t sign' ($r.Code -eq 2 -and -not (Test-Path -LiteralPath (Join-Path $work 'swapped.json')))

    $text = [IO.File]::ReadAllText($example)
    $bad = [ordered]@{
        'a price' = $text.Replace('"version": 1', '"price": 100, "version": 1')
        'a date that is none' = $text.Replace('2099-11-30', 'next year')
        'an end in the past' = $text.Replace('2099-11-30', '2020-01-01').Replace('2026-12-01', '2019-01-01')
        'an unknown kind' = $text.Replace('NON_PRODUCTION', 'FREE')
        'no seats' = $text.Replace('"namedSeats": 50', '"namedSeats": 0')
        'a new customer ID in version 2' = $text.Replace('"version": 1', '"version": 2')
    }
    foreach ($name in $bad.Keys) {
        $file = Join-Path $work 'bad.json'; [IO.File]::WriteAllText($file, $bad[$name])
        $r = Run 'New-SDV-Licence.ps1' @('-KeyFolder', $keys, '-KeyId', $a, '-InputFile', $file, '-OutFile', (Join-Path $work 'bad.licence.json'), '-BuildFolder', $build)
        Check "Refused: $name" ($r.Code -eq 2 -and $r.Text.Contains('REFUSED') -and -not (Test-Path -LiteralPath (Join-Path $work 'bad.licence.json')))
    }

    # The setup command's own licence check, with what the scripts wrote.
    Push-Location (Join-Path $PSScriptRoot 'backend')
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try {
        & mvn -o -q -pl sdv-setup test '-Dtest=ReleaseLicenceKeyTest' "-Dvero.licence.check=$work" 2>&1 | ForEach-Object { Write-Host "$_" }
        $code = $LASTEXITCODE
    } finally { $ErrorActionPreference = $before; Pop-Location }
    Check 'Setup''s licence check accepts the first licence and refuses the other' ($code -eq 0)
    Check 'The checkout''s release build is unchanged' ((State) -eq $stateBefore)
} finally {
    if (Test-Path -LiteralPath $work) { Remove-Item -LiteralPath $work -Recurse -Force }
}
Check 'The temporary folder is gone' (-not (Test-Path -LiteralPath $work))
Write-Host "$script:passed passed, $script:failed failed"
if ($script:failed -gt 0) { exit 1 }
exit 0
