<#
.SYNOPSIS
  Starts the tester instance (task 0089): Vero and its proxy on this computer only; with -Tunnel, the tunnel.

.DESCRIPTION
  Reads a settings file outside the repository (dev\testers\testers.settings.example.psd1) and fills in the checked
  files of dev\testers. It refuses local mode, a development database, the old key mount, an address that isn't this
  computer's, and a tunnel configuration other than the checked one. Owner runbook (docs/operations/SDV_OWNER_RUNBOOK.md), "Remote testers".

  The database password: SDV_TESTERS_DB_PASSWORD, or typed when asked. It is never a parameter and never written.

.EXAMPLE
  .\Start-SDV-Testers.ps1 -CheckOnly     # checks the settings and writes the tunnel file; starts nothing
.EXAMPLE
  .\Start-SDV-Testers.ps1                # Vero and Caddy
.EXAMPLE
  .\Start-SDV-Testers.ps1 -Tunnel        # owner only: opens the tunnel to what is already running
#>
[CmdletBinding()]
param(
    [string]$Settings = 'C:\dev\sdv\testers\testers.settings.psd1',
    [switch]$CheckOnly,
    [switch]$Tunnel
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$Root = $PSScriptRoot
$Files = Join-Path $Root 'dev\testers'

function Assert-Outside([string]$Path, [string]$What) {
    $full = [IO.Path]::GetFullPath($Path)
    $prefix = $Root.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar
    if ($full.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) { throw "$What must be outside the repository." }
    return $full
}

function Get-Listener([int]$Port) { @(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue) }

# Every port of the tester instance answers on this computer only.
function Assert-Loopback {
    foreach ($port in 8290, 8291, 8292, 8293) {
        foreach ($l in (Get-Listener $port)) {
            if ($l.LocalAddress -notin '127.0.0.1', '::1') { throw "Port $port listens on $($l.LocalAddress), not only on this computer. Stop it." }
        }
    }
}

function Save-Pid([string]$Name, $Process) { Set-Content -LiteralPath (Join-Path $Run "$Name.pid") -Value $Process.Id -Encoding ASCII }

function Wait-Listener([int]$Port, [int]$Seconds, $Process, [string]$What) {
    for ($i = 0; $i -lt $Seconds; $i++) {
        if ((Get-Listener $Port).Count -gt 0) { return }
        if ($Process.HasExited) { break }
        Start-Sleep -Seconds 1
    }
    throw "$What did not start. See its window, or the log in $Run. Then run .\Stop-SDV-Testers.ps1."
}

# ---- Settings ----
$settingsPath = Assert-Outside $Settings 'The settings file'
if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) { throw "Settings file not found: $settingsPath (copy dev\testers\testers.settings.example.psd1)." }
$s = Import-PowerShellDataFile -LiteralPath $settingsPath
foreach ($key in 'Host', 'TunnelId', 'CredentialsFile', 'DatabaseUrl', 'DatabaseUser', 'StorageFolder', 'KeySettings') {
    if (-not $s.ContainsKey($key)) { throw "The settings file has no $key." }
}
$hostName = [string]$s.Host
if ($hostName -cnotmatch '^([a-z0-9]([a-z0-9-]*[a-z0-9])?\.)+[a-z]{2,}$' -or $hostName -like '*.example.com' -or $hostName -like '*.trycloudflare.com') {
    throw 'Host must be your own full web address, in lower case, without https://.'
}
if ([string]$s.TunnelId -cnotmatch '^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$' -or [string]$s.TunnelId -like '00000000-*') { throw 'TunnelId must be the ID that "cloudflared tunnel create" showed.' }
$credentials = Assert-Outside ([string]$s.CredentialsFile) 'The tunnel credentials file'
if ($credentials -notmatch '^[A-Za-z]:\\[A-Za-z0-9 _.\\-]+$') { throw 'CredentialsFile is not a plain path.' }
if ([string]$s.DatabaseUrl -cnotmatch '^jdbc:postgresql://(localhost|127\.0\.0\.1):\d+/sdv_testers$') { throw 'DatabaseUrl must be the testers'' own database, sdv_testers, on this computer.' }
if ([string]$s.DatabaseUser -cnotmatch '^vero_testers_[a-z]+$') { throw 'DatabaseUser must be the testers'' own database user, vero_testers_...' }
$storage = Assert-Outside ([string]$s.StorageFolder) 'The storage folder'
$keys = $s.KeySettings
if ($keys.Count -eq 0) { throw 'KeySettings is empty: the testers need their own key (owner runbook, "Remote testers").' }
foreach ($name in $keys.Keys) {
    if ($name -cnotmatch '^(SDV_KEY_|SDV_VAULT_|PLATFORM_CRYPTO_|SPRING_CONFIG_IMPORT$)') { throw "KeySettings: $name is not a key service setting." }
    if ($name -match '(TOKEN|SECRET|SECRET_?ID|PASSWORD)$') { throw "KeySettings: $name would hold a secret. Give the path of a file instead." }
}
if (@($keys.Values | Where-Object { [string]$_ -ceq 'vero-transit' }).Count -ne 1) { throw 'KeySettings must name the mount vero-transit, never the development keys (transit).' }

$Run = Join-Path (Split-Path -Parent $settingsPath) 'run'
New-Item -ItemType Directory -Force -Path $Run | Out-Null

# ---- The tunnel file: always the checked template, filled in ----
$template = [IO.File]::ReadAllText((Join-Path $Files 'cloudflared.template.yml'))
$filled = $template.Replace('__TUNNEL_ID__', [string]$s.TunnelId).Replace('__CREDENTIALS_FILE__', $credentials).Replace('__HOST__', $hostName)
$services = @([regex]::Matches($filled, '(?m)^\s*-?\s*service:\s*(\S+)\s*$') | ForEach-Object { $_.Groups[1].Value })
$names = @([regex]::Matches($filled, '(?m)^\s*-?\s*hostname:\s*(\S+)\s*$') | ForEach-Object { $_.Groups[1].Value })
if (($services -join '|') -cne 'http://127.0.0.1:8290|http_status:404' -or ($names -join '|') -cne $hostName) { throw 'The tunnel file exposes something else than the web address.' }
$tunnelFile = Join-Path $Run 'cloudflared.yml'
[IO.File]::WriteAllText($tunnelFile, $filled, (New-Object Text.UTF8Encoding($false)))
Write-Host "[OK] Settings checked. The tunnel file exposes https://$hostName only: $tunnelFile"
if ($CheckOnly) { return }

# ---- The tunnel (owner only) ----
if ($Tunnel) {
    if (-not (Get-Command cloudflared -ErrorAction SilentlyContinue)) { throw 'cloudflared is not installed.' }
    if (-not (Test-Path -LiteralPath $credentials -PathType Leaf)) { throw 'The tunnel credentials file was not found.' }
    foreach ($port in 8290, 8291, 8292) { if ((Get-Listener $port).Count -eq 0) { throw "Nothing listens on port $port. Start the tester instance first." } }
    Assert-Loopback
    # A Vero with the local sign-in on the testers' port would answer this with 200.
    $mode = Invoke-RestMethod -Uri 'http://127.0.0.1:8292/api/v1/session/sign-in' -TimeoutSec 10
    if ([string]$mode.mode -ne 'oidc') { throw "Vero's sign-in is '$($mode.mode)', not oidc. Local mode is never put behind the tunnel." }
    $p = Start-Process -FilePath (Get-Command cloudflared).Source -ArgumentList @('tunnel', '--config', "`"$tunnelFile`"", 'run') -PassThru
    Save-Pid 'cloudflared' $p
    Write-Host "Tunnel started: https://$hostName. Stop it with .\Stop-SDV-Testers.ps1 -TunnelOnly."
    return
}

# ---- Vero and Caddy ----
if (Get-Process -Name cloudflared -ErrorAction SilentlyContinue) { throw 'cloudflared is running. Stop it first; the tunnel is started last.' }
if (-not (Get-Command caddy -ErrorAction SilentlyContinue)) { throw 'caddy is not installed.' }
$jars = @(Get-ChildItem -Path (Join-Path $Root 'backend\sdv-web\target') -Filter 'sdv-web-*.jar' -File -ErrorAction SilentlyContinue | Where-Object { $_.Name -notlike '*.original' -and $_.Name -notlike '*-local.jar' })
if ($jars.Count -ne 1) { throw 'Exactly one Vero application jar is needed in backend\sdv-web\target (Build-SDV-All.ps1).' }
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [IO.Compression.ZipFile]::OpenRead($jars[0].FullName)
try { $local = @($zip.Entries | Where-Object { $_.FullName -like 'BOOT-INF/lib/sdv-local-*' }) } finally { $zip.Dispose() }
if ($local.Count -ne 0) { throw 'This jar has the local sign-in. It is never used for testers.' }
$dist = Join-Path $Root 'frontend\sdv-ui\dist'
if (-not (Test-Path -LiteralPath (Join-Path $dist 'index.html'))) { throw 'The built screens were not found (Build-SDV-All.ps1).' }
foreach ($port in 8290, 8292) { if ((Get-Listener $port).Count -gt 0) { throw "Port $port is in use. Run .\Stop-SDV-Testers.ps1." } }
if ((Get-Listener 8291).Count -eq 0) { throw 'The tester login system is not running on port 8291 (owner runbook, "Remote testers").' }
Assert-Loopback
New-Item -ItemType Directory -Force -Path $storage | Out-Null

$before = @{}
Get-ChildItem Env: | ForEach-Object { $before[$_.Name] = $_.Value }
try {
    # Nothing of a development session comes along.
    Get-ChildItem Env: | Where-Object { $_.Name -match '^(SDV_|SPRING_|PLATFORM_|SERVER_|JAVA_TOOL_OPTIONS$|JDK_JAVA_OPTIONS$)' -and $_.Name -ne 'SDV_TESTERS_DB_PASSWORD' } | ForEach-Object { Remove-Item -LiteralPath "Env:$($_.Name)" }
    if ($env:SDV_TESTERS_DB_PASSWORD) { $env:SDV_DB_PASSWORD = $env:SDV_TESTERS_DB_PASSWORD }
    else {
        $secure = Read-Host -AsSecureString "Password for $($s.DatabaseUser)"
        $env:SDV_DB_PASSWORD = (New-Object Net.NetworkCredential('', $secure)).Password
    }
    Remove-Item Env:SDV_TESTERS_DB_PASSWORD -ErrorAction SilentlyContinue
    $env:SERVER_ADDRESS = '127.0.0.1'
    $env:SDV_SERVER_PORT = '8292'
    $env:SDV_DB_URL = [string]$s.DatabaseUrl
    $env:SDV_DB_USERNAME = [string]$s.DatabaseUser
    $env:SDV_AUTH_MODE = 'enterprise-jwt'
    $env:SDV_AUTH_JWT_ISSUER = "https://$hostName/realms/vero-testers"
    $env:SDV_AUTH_JWT_JWK_SET_URI = 'http://127.0.0.1:8291/realms/vero-testers/protocol/openid-connect/certs'
    $env:SDV_AUTH_JWT_AUDIENCE = 'vero-testers-api'
    $env:SDV_AUTH_JWT_PROVIDER_ID = 'vero-testers'
    $env:SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS = '900'
    $env:SDV_TRUSTED_PROXIES = '127.0.0.1'
    # Task 0100: messages link only under the testers' own address.
    $env:SDV_PUBLIC_URL = "https://$hostName"
    $env:SDV_STORAGE_ALLOWED_ROOTS = $storage
    # Task 0080: beside the documents' folder, never in it.
    $env:SDV_ERASE_LOG_FOLDER = "$($storage.TrimEnd('\', '/'))-erase-log"
    foreach ($name in $keys.Keys) { Set-Item -LiteralPath "Env:$name" -Value ([string]$keys[$name]) }
    $vero = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', "`"$($jars[0].FullName)`"") -WorkingDirectory $Run -PassThru
    Save-Pid 'vero' $vero
    Remove-Item Env:SDV_DB_PASSWORD
    $env:VERO_TESTERS_HOST = $hostName
    $env:VERO_TESTERS_DIST = $dist.Replace('\', '/')
    $caddy = Start-Process -FilePath (Get-Command caddy).Source -ArgumentList @('run', '--adapter', 'caddyfile', '--config', "`"$(Join-Path $Files 'Caddyfile')`"") -WorkingDirectory $Run -PassThru
    Save-Pid 'caddy' $caddy
} finally {
    foreach ($e in @(Get-ChildItem Env:)) { if (-not $before.ContainsKey($e.Name)) { Remove-Item -LiteralPath "Env:$($e.Name)" } }
    foreach ($name in $before.Keys) { Set-Item -LiteralPath "Env:$name" -Value $before[$name] }
}
Wait-Listener 8292 120 $vero 'Vero'
Wait-Listener 8290 20 $caddy 'Caddy'
Assert-Loopback
Write-Host 'The tester instance runs on this computer only (Vero 8292, proxy 8290). Next: the local checks of the runbook, then, owner only: .\Start-SDV-Testers.ps1 -Tunnel'
