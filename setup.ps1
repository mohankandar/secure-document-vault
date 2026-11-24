<#
.SYNOPSIS
  Vero's setup command (task 0041): platform-core's setup for product SDV, with Vero's product roles.

.DESCRIPTION
  Loads your licence, customer, entities, login connection, group-to-role grants and client bindings into Vero's
  database. "plan" shows what it would do and changes nothing; "apply" does it. Running it again is safe.
  Install guide: docs/install/SETUP.md.

  This wrapper only fills in what Vero ships: --product SDV and --product-roles (Vero's product-roles.json). The licence
  keys it trusts are inside the setup jar (task 0067); no option changes them. Every other option is passed on as given. The database settings come from the
  environment only (PLATFORM_SETUP_DB_URL, PLATFORM_SETUP_DB_USER, PLATFORM_SETUP_DB_PASSWORD); the wrapper never
  reads or prints them.

  The demo licence (dev/setup) is accepted only by a development build of the setup jar (Build-SDV-All.ps1).

.EXAMPLE
  .\setup.ps1 plan -Licence licence.json -Signin signin.json
.EXAMPLE
  .\setup.ps1 apply -Licence licence.json -Signin signin.json --remove-unlisted-grants
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)][ValidateSet('plan', 'apply')][string]$Action,
    [Parameter(Mandatory = $true)][string]$Licence,
    [Parameter(Mandatory = $true)][string]$Signin,
    [string]$Jar,
    [Parameter(ValueFromRemainingArguments = $true)][string[]]$Rest
)

$ErrorActionPreference = 'Stop'
$Here = if ($PSScriptRoot) { $PSScriptRoot } else { Split-Path -Parent $MyInvocation.MyCommand.Path }

function Find-First([string[]]$Candidates) {
    foreach ($c in $Candidates) { if ($c -and (Test-Path -LiteralPath $c -PathType Leaf)) { return (Resolve-Path -LiteralPath $c).Path } }
    return $null
}

if (-not $Jar) {
    $found = @(Get-ChildItem -Path $Here, (Join-Path $Here 'backend\sdv-setup\target') -Filter 'sdv-setup-*.jar' -File -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notlike '*.original' })
    if ($found.Count -gt 1) { throw 'More than one Vero setup jar was found. Say which one with -Jar.' }
    if ($found.Count -eq 0) { throw 'The Vero setup jar (sdv-setup-*.jar) was not found. Build it (Build-SDV-All.ps1) or say where it is with -Jar.' }
    $Jar = $found[0].FullName
}
$roles = Find-First @((Join-Path $Here 'product-roles.json'), (Join-Path $Here 'backend\sdv-security\src\main\resources\setup\product-roles.json'))
if (-not $roles) { throw "Vero's product-roles.json was not found next to this script." }

foreach ($f in @($Licence, $Signin)) { if (-not (Test-Path -LiteralPath $f -PathType Leaf)) { throw "File not found: $f" } }
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 is needed to run setup, and java was not found.' }

$arguments = @('-jar', $Jar, $Action, '--product', 'SDV', '--product-roles', $roles,
    '--licence', (Resolve-Path -LiteralPath $Licence).Path, '--signin', (Resolve-Path -LiteralPath $Signin).Path)
if ($Rest) { $arguments += $Rest }
& java @arguments
exit $LASTEXITCODE
