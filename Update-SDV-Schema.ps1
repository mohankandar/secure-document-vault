<#
.SYNOPSIS
  The Vero schema step: run by the customer's database team, with the database owner's credentials.

.DESCRIPTION
  status   lists the changesets that would run (changes nothing)
  sql      writes the SQL that "update" would run to -Output, for teams that review and run SQL themselves
  update   applies the changesets

  It runs the Liquibase bundled in the Vero application jar, always with the "production" context, so local test
  data can never be added. Starting Vero never changes the database; it refuses to serve until this step has run.

  The connection: -Url and -Username, or SDV_SCHEMA_DB_URL and SDV_SCHEMA_DB_USERNAME.
  The password: SDV_SCHEMA_DB_PASSWORD, or typed when asked. Vero never stores it, and it is never a parameter.

.EXAMPLE
  .\Update-SDV-Schema.ps1 status -Url jdbc:postgresql://db01:5432/vero -Username vero_owner
.EXAMPLE
  .\Update-SDV-Schema.ps1 sql -Output .\vero-schema-1.2.0.sql
.EXAMPLE
  .\Update-SDV-Schema.ps1 update
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [ValidateSet('status', 'sql', 'update')]
    [string]$Step,
    [string]$Url,
    [string]$Username,
    [string]$Output,
    # The Vero application jar. Default: the sdv-web jar next to this script, else the one built in backend/sdv-web/target.
    [string]$Jar
)

$ErrorActionPreference = 'Stop'

function Find-VeroJar {
    param([string]$Given)
    if ($Given) {
        if (-not (Test-Path -LiteralPath $Given -PathType Leaf)) { throw "The application jar '$Given' was not found." }
        return (Resolve-Path -LiteralPath $Given).Path
    }
    foreach ($dir in @($PSScriptRoot, (Join-Path $PSScriptRoot 'backend\sdv-web\target'))) {
        $found = @(Get-ChildItem -Path $dir -Filter 'sdv-web-*.jar' -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' })
        if ($found.Count -gt 1) { throw "More than one Vero application jar in $dir. Say which one with -Jar." }
        if ($found.Count -eq 1) { return $found[0].FullName }
    }
    throw 'The Vero application jar was not found. Say where it is with -Jar.'
}

$jarPath = Find-VeroJar -Given $Jar
if (-not (Get-Command java -ErrorAction SilentlyContinue)) { throw 'Java 21 is needed to run the schema step, and java was not found.' }

$arguments = @('-cp', $jarPath, '-Dloader.main=com.sdv.platform.web.schema.SchemaStep',
    'org.springframework.boot.loader.launch.PropertiesLauncher', $Step)
if ($Url) { $arguments += @('--url', $Url) }
if ($Username) { $arguments += @('--username', $Username) }
if ($Output) {
    $outPath = if ([System.IO.Path]::IsPathRooted($Output)) { $Output } else { Join-Path (Get-Location).Path $Output }
    $arguments += @('--output', [System.IO.Path]::GetFullPath($outPath))
}

# The password reaches Java only through this process's environment, and only for this run.
$askedForPassword = $false
if (-not $env:SDV_SCHEMA_DB_PASSWORD) {
    $who = if ($Username) { $Username } elseif ($env:SDV_SCHEMA_DB_USERNAME) { $env:SDV_SCHEMA_DB_USERNAME } else { 'the database owner' }
    $secure = Read-Host -AsSecureString "Password for $who"
    $bstr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
    try { $env:SDV_SCHEMA_DB_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($bstr) }
    finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($bstr) }
    $askedForPassword = $true
}
try {
    & java @arguments
    $code = $LASTEXITCODE
} finally {
    if ($askedForPassword) { Remove-Item Env:SDV_SCHEMA_DB_PASSWORD -ErrorAction SilentlyContinue }
}
exit $code
