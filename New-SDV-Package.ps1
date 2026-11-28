<#
.SYNOPSIS
  Task 0097 (0092 open gap 2): builds the customer's Vero package: one versioned folder and its zip.
.DESCRIPTION
  The package holds what a customer's IT installs, and nothing else:
    sdv-web-<version>.jar            the application (release build: it trusts only the release licence keys)
    sdv-setup-<version>.jar, setup.ps1, setup.cmd, setup.sh, product-roles.json    the setup command
    Update-SDV-Schema.ps1, update-sdv-schema.sh                                     the schema step
    Setup-SDV-VaultTransit.ps1                                                       the key service's Vero part
    screens\                         the built screens (served by the customer's web server)
    service\vero.service, service\vero.env.example                                  the Linux service
    docs\                            the install runbook, setup guide, IT and Business Admin guides, error codes,
                                     backup runbook, platform packages
    VERSION.txt, SHA256SUMS.txt      what it is, and the checksum of every file
  A check stops the build when the package holds anything it must not: a development build (it trusts the demo licence),
  the local test sign-in, a test user, a demo seed, a key, a token, a licence, a login file, or a name of this computer.
  -Development makes a package of a development build for checks only: its folder and zip say DEVELOPMENT, and it must
  never reach a customer. The release build needs the release licence keys (owner runbook (docs/operations/SDV_OWNER_RUNBOOK.md), "Release licence keys").
.EXAMPLE
  .\New-SDV-Package.ps1 -Version 1.0.0-rc1
.EXAMPLE
  .\New-SDV-Package.ps1 -Version 1.0.0-rc1 -Development -SkipBuild
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][ValidatePattern('^\d+\.\d+\.\d+(-[A-Za-z0-9.]+)?$')][string]$Version,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '.runtime\package'),
    # A package of a development build, for checks only (never for a customer).
    [switch]$Development,
    # Use the jars and screens already built (Build-SDV-All.ps1) instead of building again.
    [switch]$SkipBuild
)
$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$root = $PSScriptRoot
Add-Type -AssemblyName System.IO.Compression.FileSystem

function Say([string]$Text) { Write-Host "  [OK] $Text" -ForegroundColor Green }
$problems = New-Object System.Collections.Generic.List[string]

# ---- 1. Build ----
if (-not $SkipBuild) {
    $build = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $root 'Build-SDV-All.ps1'))
    if (-not $Development) { $build += '-Release' }
    & powershell.exe @build
    if ($LASTEXITCODE -ne 0) { throw 'The build failed; no package was made.' }
}
function One([string]$Folder, [string]$Filter) {
    $found = @(Get-ChildItem -Path (Join-Path $root $Folder) -Filter $Filter -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' })
    if ($found.Count -ne 1) { throw "Expected exactly one $Filter in $Folder, found $($found.Count). Build first (Build-SDV-All.ps1)." }
    $found[0]
}
$webJar = One 'backend\sdv-web\target' 'sdv-web-*.jar'
$setupJar = One 'backend\sdv-setup\target' 'sdv-setup-*.jar'
$screens = Join-Path $root 'frontend\sdv-ui\dist'
if (-not (Test-Path -LiteralPath (Join-Path $screens 'index.html'))) { throw 'The screens are not built (frontend\sdv-ui\dist). Build first.' }

# ---- 2. Lay out the folder ----
$name = "vero-$Version" + $(if ($Development) { '-DEVELOPMENT' } else { '' })
$OutputDirectory = [IO.Path]::GetFullPath($OutputDirectory)
$folder = Join-Path $OutputDirectory $name
$zip = "$folder.zip"
if (Test-Path -LiteralPath $folder) { throw "$folder is there already. Move it away, or give another -Version." }
if (Test-Path -LiteralPath $zip) { throw "$zip is there already. Move it away, or give another -Version." }
New-Item -ItemType Directory -Force -Path $folder, (Join-Path $folder 'service'), (Join-Path $folder 'docs') | Out-Null

Copy-Item -LiteralPath $webJar.FullName -Destination (Join-Path $folder "sdv-web-$Version.jar")
Copy-Item -LiteralPath $setupJar.FullName -Destination (Join-Path $folder "sdv-setup-$Version.jar")
foreach ($f in 'setup.ps1', 'setup.cmd', 'setup.sh', 'Update-SDV-Schema.ps1', 'update-sdv-schema.sh', 'Setup-SDV-VaultTransit.ps1') {
    Copy-Item -LiteralPath (Join-Path $root $f) -Destination $folder
}
Copy-Item -LiteralPath (Join-Path $root 'backend\sdv-security\src\main\resources\setup\product-roles.json') -Destination $folder
Copy-Item -LiteralPath $screens -Destination (Join-Path $folder 'screens') -Recurse
$docs = @{
    'docs\operations\SDV_BACKUP_RESTORE_HA_RUNBOOK.md'   = 'BACKUP-RESTORE-RUNBOOK.md'
    'docs\install\SETUP.md'                              = 'SETUP.md'
    'docs\guides\it-admin-guide.md'                      = 'it-admin-guide.md'
    'docs\guides\business-admin-guide.md'                = 'business-admin-guide.md'
    'docs\guides\release-notes.md'                       = 'release-notes.md'
    'docs\api\error-codes.md'                            = 'error-codes.md'
}
foreach ($d in $docs.GetEnumerator()) { Copy-Item -LiteralPath (Join-Path $root $d.Key) -Destination (Join-Path $folder "docs\$($d.Value)") }
# The install runbook from "Before you install" on: what comes before it is the frozen 1.0.0 record, for the owner.
$runbook = [IO.File]::ReadAllText((Join-Path $root 'docs\operations\SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md'))
$start = $runbook.IndexOf('## Before you install')
if ($start -lt 0) { throw 'The install runbook has no "Before you install" section.' }
[IO.File]::WriteAllText((Join-Path $folder 'docs\INSTALL-RUNBOOK.md'), "# Vero install runbook`r`n`r`n" + $runbook.Substring($start), (New-Object Text.UTF8Encoding($false)))

$utf8 = New-Object Text.UTF8Encoding($false)
[IO.File]::WriteAllText((Join-Path $folder 'service\vero.service'), (@(
    '[Unit]', 'Description=Vero', 'After=network-online.target', 'Wants=network-online.target', '[Service]', 'User=vero',
    'WorkingDirectory=/opt/vero', 'EnvironmentFile=/etc/vero/vero.env', "ExecStart=/usr/bin/java -jar /opt/vero/sdv-web-$Version.jar",
    'SuccessExitStatus=143', 'Restart=always', 'RestartSec=10', '[Install]', 'WantedBy=multi-user.target', '') -join "`n"), $utf8)
[IO.File]::WriteAllText((Join-Path $folder 'service\vero.env.example'), (@(
    '# /etc/vero/vero.env (owner root, mode 600). Every value is yours; see INSTALL-RUNBOOK.md, "All settings".',
    'SDV_DB_URL=jdbc:postgresql://<database host>:5432/<database>', 'SDV_DB_USERNAME=<app user>', 'SDV_DB_PASSWORD=<app user password>',
    'SDV_ERASE_LOG_FOLDER=/var/lib/vero/erase-log', 'SDV_STORAGE_ALLOWED_ROOTS=/var/lib/vero/documents',
    'SDV_KEY_SERVICE_ADDRESS=https://<key service>:8200', 'SDV_VAULT_LOGIN_FILE=/etc/vero/vero-vault-login.properties',
    'SDV_AUTH_MODE=enterprise-jwt', 'SDV_AUTH_JWT_ISSUER=https://<login system>/<issuer path>', 'SDV_AUTH_JWT_JWK_SET_URI=https://<login system>/<keys path>',
    'SDV_AUTH_JWT_AUDIENCE=<Vero API audience>', 'SDV_AUTH_JWT_PROVIDER_ID=customer-oidc', 'SDV_PUBLIC_URL=https://<the address people open Vero on>', '') -join "`n"), $utf8)

# ---- 3. What must never be in it ----
function Jar-Entries([string]$Path) {
    $z = [IO.Compression.ZipFile]::OpenRead($Path)
    try { @($z.Entries | ForEach-Object { $_.FullName }) } finally { $z.Dispose() }
}
function Jar-Text([string]$Path, [string]$Pattern) {
    $z = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        $out = @()
        foreach ($e in @($z.Entries | Where-Object { $_.FullName -like $Pattern })) {
            $r = New-Object IO.StreamReader($e.Open()); try { $out += $r.ReadToEnd() } finally { $r.Dispose() }
        }
        $out -join "`n"
    } finally { $z.Dispose() }
}
foreach ($jar in @(Get-ChildItem -LiteralPath $folder -Filter '*.jar')) {
    $entries = Jar-Entries $jar.FullName
    $buildKind = Jar-Text $jar.FullName '*META-INF/platform/licence-build.properties'
    $keys = Jar-Text $jar.FullName '*META-INF/platform/licence-keys.json'
    if (-not $Development -and ($buildKind -match 'development-keys=true' -or $keys -match '"development"\s*:\s*true')) {
        $problems.Add("$($jar.Name) is a development build: it trusts the demo licence")
    }
    if (@($entries | Where-Object { $_ -match 'sdv-local[^/]*\.jar$|LocalHeaderAuthenticationFilter|LocalTunnelCheck' }).Count) {
        $problems.Add("$($jar.Name) holds the local test sign-in")
    }
    if (@($entries | Where-Object { $_ -match '(^|/)(dev|demo|seed)[^/]*/.*\.(json|csv|properties)$|application-local[^/]*\.ya?ml$' }).Count) {
        $problems.Add("$($jar.Name) holds a demo, seed or local settings file")
    }
}
$forbiddenNames = '\.(pem|key|p12|pfx|jks|token)$|licence[^/\\]*\.json$|license[^/\\]*\.json$|login[^/\\]*\.properties$|approle[^/\\]*\.properties$|realm[^/\\]*\.json$|Seed-|role-mapping'
foreach ($f in @(Get-ChildItem -LiteralPath $folder -Recurse -File)) {
    if ($f.Name -match $forbiddenNames) { $problems.Add("$($f.FullName.Substring($folder.Length + 1)) must not be in a package") }
}
$forbiddenText = [ordered]@{
    'a private key'              = '-----BEGIN [A-Z ]*PRIVATE KEY-----'
    'a token'                    = 'eyJ[A-Za-z0-9_-]{10,}\.eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}|hvs\.[A-Za-z0-9]{20,}'
    'a test user'                = 'Maya Okafor|Daniel Reyes|Dana Ruiz|Priya Natarajan|Jonas Weber|Aiko Tanaka|Ari Lindqvist|local-(business|document|restricted|tenant|platform)-[a-z-]+'
    'a name of this computer'    = '(?i)c:\\dev\\sdv|mohankandar|lexa-keycloak|securedoc-postgres|document-platform-local|vero-testers'
}
foreach ($f in @(Get-ChildItem -LiteralPath $folder -Recurse -File | Where-Object { $_.Extension -in '.md', '.ps1', '.sh', '.cmd', '.json', '.service', '.example', '.html', '.js', '.css', '.txt' })) {
    $text = [IO.File]::ReadAllText($f.FullName)
    foreach ($kind in $forbiddenText.Keys) {
        $m = [regex]::Match($text, $forbiddenText[$kind])
        if ($m.Success) {
            $line = ($text.Substring(0, $m.Index) -split "`n").Count
            $problems.Add("$($f.FullName.Substring($folder.Length + 1)) line ${line}: $kind")
        }
    }
}
if ($problems.Count -gt 0) {
    foreach ($p in $problems) { Write-Host "  [REFUSED] $p" -ForegroundColor Red }
    throw "The package holds what it must not ($($problems.Count)); no zip was made. Folder kept for a look: $folder"
}
Say 'no development keys, local sign-in, test users, demo data, keys, tokens, licences, login files or names of this computer'

# ---- 4. What it is, checksums, zip ----
$commit = (& git -C $root rev-parse --short HEAD).Trim()
[IO.File]::WriteAllText((Join-Path $folder 'VERSION.txt'), (@("Vero $Version", "build: $(if ($Development) { 'DEVELOPMENT (for checks only; never for a customer)' } else { 'release' })",
    "commit: $commit", "made: $((Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ'))", '') -join "`n"), $utf8)
$sums = foreach ($f in @(Get-ChildItem -LiteralPath $folder -Recurse -File | Sort-Object FullName)) {
    $rel = $f.FullName.Substring($folder.Length + 1).Replace('\', '/')
    "{0}  {1}" -f (Get-FileHash -Algorithm SHA256 -LiteralPath $f.FullName).Hash.ToLowerInvariant(), $rel
}
[IO.File]::WriteAllText((Join-Path $folder 'SHA256SUMS.txt'), (($sums -join "`n") + "`n"), $utf8)
[IO.Compression.ZipFile]::CreateFromDirectory($folder, $zip, [IO.Compression.CompressionLevel]::Optimal, $true)
$zipSum = (Get-FileHash -Algorithm SHA256 -LiteralPath $zip).Hash.ToLowerInvariant()
[IO.File]::WriteAllText("$zip.sha256", "$zipSum  $([IO.Path]::GetFileName($zip))`n", $utf8)
Say "package: $folder"
Say "zip: $zip (SHA-256 $zipSum)"
if ($Development) { Write-Host 'DEVELOPMENT package: for checks only. Never give it to a customer.' -ForegroundColor Yellow }
