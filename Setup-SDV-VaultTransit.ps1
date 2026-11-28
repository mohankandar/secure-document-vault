<#
.SYNOPSIS
  The owner's Vault step for customer keys (task 0083): a second Transit mount, three policies and Vero's AppRole login.

.DESCRIPTION
  Run once by the person who looks after Vault. It only adds:
    - a Transit mount of its own for the customers' keys (-TransitMount, default vero-transit); the existing transit/
      mount and its keys stay as they are;
    - three policies on that mount (docs/design/crypto.md section 7 of platform-core): <prefix>-app (the running Vero: no
      rights to make, change or delete keys), <prefix>-setup (the setup command), <prefix>-offboard (customer exit);
    - the AppRole method, when there is none at -AppRoleMount, and a role for Vero with <prefix>-app only. Task 0102: its
      secret ID lasts -SecretIdHours (90 days) and -SecretIdUses logins, and the login and its tokens work only from
      -VeroAddresses. <prefix>-app reads only Vero's own secrets (secret/platform/vero/*);
    - one file with the role ID and the secret ID (-OutFile), readable by this Windows user only. Nothing secret is
      printed.
  It looks at everything first and changes nothing when something is already there but different: it never changes an
  existing mount, key, policy or role. It refuses a Vault that is sealed, not initialised or not reachable, and never
  initialises, unseals or seals one. Run again, it changes nothing and says so.

  The Vault token is asked for (not shown), or taken from VAULT_TOKEN for this run. It is never stored.

.EXAMPLE
  .\Setup-SDV-VaultTransit.ps1 -VeroAddresses 10.0.4.21/32,10.0.4.22/32 -OutFile D:\vero\config\vero-vault-login.properties
#>
[CmdletBinding()]
param(
    [string]$VaultAddress = 'http://127.0.0.1:8200',
    [ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$TransitMount = 'vero-transit',
    [ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$AppRoleMount = 'approle',
    [ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$PolicyPrefix = 'platform-crypto',
    [ValidatePattern('^[a-z][a-z0-9-]{2,40}$')][string]$RoleName = 'vero',
    # Where the login file goes: a full path outside every repository (task 0097: no default; give yours).
    [Parameter(Mandatory = $true)][string]$OutFile,
    # The role is there but its file is lost: make one more secret ID and write the file again.
    [switch]$NewSecretId,
    # Task 0102 (VSEC-205): the addresses Vault sees Vero's servers come from, as CIDRs (for example 10.0.4.21/32). The
    # login works from nowhere else. Required: there is no safe default (behind Docker, Vault sees the network's gateway).
    [string[]]$VeroAddresses = @(),
    # How long a secret ID lasts (hours; 2160 = 90 days), and how many logins it allows. Renew before either runs out
    # (runbook, "Renewing Vero's secret ID").
    [ValidateRange(24, 8760)][int]$SecretIdHours = 2160,
    [ValidateRange(100, 1000000)][int]$SecretIdUses = 20000
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

function Refuse([string]$Why) { Write-Host "REFUSED: $Why" -ForegroundColor Red; Write-Host 'Nothing was changed.'; exit 1 }

$address = $VaultAddress.TrimEnd('/')
$uri = $null
if (-not [Uri]::TryCreate($address, [UriKind]::Absolute, [ref]$uri)) { Refuse "'$VaultAddress' is not an address." }
if ($uri.Scheme -ne 'https' -and -not ($uri.Scheme -eq 'http' -and $uri.Host -in @('127.0.0.1', 'localhost'))) {
    Refuse 'The Vault address must be https, or http on this computer.'
}
if ($TransitMount -eq 'transit') { Refuse 'transit/ holds the old keys. Give the new mount a name of its own.' }
$cidrs = @($VeroAddresses | ForEach-Object { $_ -split ',' } | ForEach-Object { $_.Trim() } | Where-Object { $_ })
if ($cidrs.Count -eq 0) { Refuse 'Give -VeroAddresses: the addresses (CIDR) Vault sees Vero''s servers come from. Behind Docker on this computer, the network''s gateway (docker network inspect).' }
foreach ($c in $cidrs) {
    $ip, $bits = $c -split '/', 2
    $parsed = $null
    if (-not $bits -or -not [Net.IPAddress]::TryParse($ip, [ref]$parsed) -or -not ($bits -match '^\d{1,3}$') -or
        [int]$bits -gt $(if ($parsed.AddressFamily -eq 'InterNetworkV6') { 128 } else { 32 }) -or [int]$bits -lt 8) {
        Refuse "'$c' is not an address range such as 10.0.4.21/32 (at least /8)."
    }
}

# One call. The body and the token are never written anywhere; of an answer, only the status and Vault's own error words are.
$script:token = $null
function Invoke-Vault([string]$Method, [string]$Path, [object]$Body = $null) {
    $request = [Net.HttpWebRequest]::Create("$address/v1/$Path")
    $request.Method = $Method; $request.Timeout = 10000; $request.AllowAutoRedirect = $false
    if ($script:token) { $request.Headers['X-Vault-Token'] = $script:token }
    if ($null -ne $Body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes(($Body | ConvertTo-Json -Depth 6 -Compress))
        $request.ContentType = 'application/json'; $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream(); try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    }
    $response = $null
    try { $response = $request.GetResponse() } catch [Net.WebException] { $response = $_.Exception.Response; if ($null -eq $response) { return [pscustomobject]@{ Status = 0; Json = $null; Why = $_.Exception.Status.ToString() } } }
    $status = [int]$response.StatusCode
    $text = ''
    try { $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8); $text = $reader.ReadToEnd(); $reader.Dispose() } finally { $response.Dispose() }
    $json = $null; if ($text) { try { $json = $text | ConvertFrom-Json } catch { } }
    $why = ''; if ($json -and $json.PSObject.Properties['errors']) { $why = (@($json.errors) -join '; ') }
    [pscustomobject]@{ Status = $status; Json = $json; Why = $why }
}
function Has($Object, [string]$Name) { $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name] }
function Same([string]$A, [string]$B) { ($A -replace '\s+', ' ').Trim() -eq ($B -replace '\s+', ' ').Trim() }

# ---- 1. Is there a usable Vault? ----
$health = Invoke-Vault 'GET' 'sys/health'
if ($health.Status -eq 0) { Refuse "Vault can't be reached at $address ($($health.Why))." }
if ($health.Status -eq 501) { Refuse 'Vault is not initialised. This script never initialises one.' }
if ($health.Status -eq 503) { Refuse 'Vault is sealed. Unseal it yourself, as usual, and run this again.' }
if ($health.Status -ne 200) { Refuse "Vault is not ready to be changed (health $($health.Status))." }

$folder = Split-Path -Parent $OutFile
if (-not [IO.Path]::IsPathRooted($OutFile)) { Refuse 'OutFile must be a full path.' }
if (-not (Test-Path -LiteralPath $folder -PathType Container)) { Refuse "The folder $folder isn't there." }
$inRepository = [IO.Path]::GetFullPath($OutFile).StartsWith([IO.Path]::GetFullPath($PSScriptRoot).TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)
if ($inRepository) { Refuse 'OutFile must not be in the repository.' }

if ($env:VAULT_TOKEN) { $script:token = $env:VAULT_TOKEN }
else {
    $secure = Read-Host 'Vault token (not shown)' -AsSecureString
    $script:token = [Net.NetworkCredential]::new('', $secure).Password
}
if (-not $script:token) { Refuse 'A Vault token is needed.' }

# ---- 2. What is there already? (reads only) ----
$policies = [ordered]@{
    "$PolicyPrefix-app" = @"
path "$TransitMount/datakey/plaintext/customer-*" { capabilities = ["update"] }
path "$TransitMount/decrypt/customer-*" { capabilities = ["update"] }
path "$TransitMount/rewrap/customer-*" { capabilities = ["update"] }
path "$TransitMount/hmac/customer-*" { capabilities = ["update"] }
path "$TransitMount/keys/customer-*" { capabilities = ["read"] }
path "secret/data/platform/vero/*" { capabilities = ["read"] }
"@
    "$PolicyPrefix-setup" = @"
path "$TransitMount/keys/+" { capabilities = ["create", "update", "read"] }
path "$TransitMount/keys/+/rotate" { capabilities = ["update"] }
"@
    "$PolicyPrefix-offboard" = @"
path "$TransitMount/keys/+" { capabilities = ["read", "delete"] }
path "$TransitMount/keys/+/config" { capabilities = ["update"] }
"@
}
$todo = New-Object System.Collections.Generic.List[string]
$kept = New-Object System.Collections.Generic.List[string]

$mounts = Invoke-Vault 'GET' 'sys/mounts'
if ($mounts.Status -ne 200) { Refuse "The token may not list the mounts (status $($mounts.Status)). Use one that may add mounts, policies and an AppRole." }
$list = if (Has $mounts.Json 'data') { $mounts.Json.data } else { $mounts.Json }
$mountThere = Has $list "$TransitMount/"
if ($mountThere) {
    if ($list."$TransitMount/".type -ne 'transit') { Refuse "$TransitMount/ is there, but it is not a Transit mount." }
    $kept.Add("Transit mount $TransitMount/")
} else { $todo.Add("Transit mount $TransitMount/") }

$missingPolicies = @()
foreach ($name in $policies.Keys) {
    $p = Invoke-Vault 'GET' "sys/policies/acl/$name"
    if ($p.Status -eq 200) {
        if (-not (Same $p.Json.data.policy $policies[$name])) { Refuse "The policy $name is there with another text. This script changes no policy; give -PolicyPrefix another name, or compare it by hand." }
        $kept.Add("Policy $name")
    } elseif ($p.Status -eq 404) { $missingPolicies += $name; $todo.Add("Policy $name") }
    else { Refuse "The policy $name could not be read (status $($p.Status))." }
}

$auths = Invoke-Vault 'GET' 'sys/auth'
if ($auths.Status -ne 200) { Refuse "The sign-in methods could not be read (status $($auths.Status))." }
$authList = if (Has $auths.Json 'data') { $auths.Json.data } else { $auths.Json }
$authThere = Has $authList "$AppRoleMount/"
if ($authThere) {
    if ($authList."$AppRoleMount/".type -ne 'approle') { Refuse "$AppRoleMount/ is there, but it is not an AppRole method." }
    $kept.Add("AppRole method $AppRoleMount/")
} else { $todo.Add("AppRole method $AppRoleMount/") }

$roleThere = $false
if ($authThere) {
    $role = Invoke-Vault 'GET' "auth/$AppRoleMount/role/$RoleName"
    if ($role.Status -eq 200) {
        $has = @($role.Json.data.token_policies)
        if ($has.Count -ne 1 -or $has[0] -ne "$PolicyPrefix-app") { Refuse "The role $RoleName is there with other policies. This script changes no role; give -RoleName another name." }
        $roleThere = $true; $kept.Add("Role $RoleName")
        # Task 0102: a role from before has no limits. This script changes no role; the runbook says how.
        $d = $role.Json.data
        $limits = (Has $d 'secret_id_ttl') -and [int]$d.secret_id_ttl -gt 0 -and (Has $d 'secret_id_num_uses') -and [int]$d.secret_id_num_uses -gt 0 -and
            (Has $d 'secret_id_bound_cidrs') -and @($d.secret_id_bound_cidrs).Count -gt 0 -and (Has $d 'token_bound_cidrs') -and @($d.token_bound_cidrs).Count -gt 0
        if (-not $limits) { Write-Host "WARNING: the role $RoleName has no expiry, use limit or address binding for its secret ID. Add them (runbook, ""Keys for a customer"")." -ForegroundColor Yellow }
    } elseif ($role.Status -ne 404) { Refuse "The role $RoleName could not be read (status $($role.Status))." }
}
if (-not $roleThere) { $todo.Add("Role $RoleName") }

$fileThere = Test-Path -LiteralPath $OutFile
if ($fileThere -and -not $roleThere) { Refuse "$OutFile is there, but the role isn't. The file is never overwritten; move it away yourself if it is old." }
if ($fileThere -and $NewSecretId) { Refuse "$OutFile is there and is never overwritten. Move it away yourself first." }
if ($roleThere -and -not $fileThere -and -not $NewSecretId) { Refuse "The role $RoleName is there, but $OutFile isn't. To make one more secret ID and write the file, run again with -NewSecretId." }
if ($fileThere) { $kept.Add("File $OutFile (not opened)") } else { $todo.Add("File $OutFile") }

foreach ($k in $kept) { Write-Host "[THERE] $k" }
if ($todo.Count -eq 0) { Write-Host 'Everything is there already. Nothing was changed.' -ForegroundColor Green; exit 0 }

# ---- 3. Add what is missing ----
function Add-Vault([string]$What, [string]$Method, [string]$Path, [object]$Body) {
    $r = Invoke-Vault $Method $Path $Body
    if ($r.Status -notin 200, 204) { Write-Host "FAILED: $What (status $($r.Status); $($r.Why)). What was added before is marked [ADDED]; run again after the cause is fixed." -ForegroundColor Red; exit 2 }
    Write-Host "[ADDED] $What"
}
if (-not $mountThere) { Add-Vault "Transit mount $TransitMount/" 'POST' "sys/mounts/$TransitMount" @{ type = 'transit'; description = 'Vero: keys of the customers (platform-crypto)' } }
foreach ($name in $missingPolicies) { Add-Vault "Policy $name" 'PUT' "sys/policies/acl/$name" @{ policy = $policies[$name] } }
if (-not $authThere) { Add-Vault "AppRole method $AppRoleMount/" 'POST' "sys/auth/$AppRoleMount" @{ type = 'approle'; description = 'Sign-in of applications' } }
if (-not $roleThere) {
    Add-Vault "Role $RoleName" 'POST' "auth/$AppRoleMount/role/$RoleName" @{ token_policies = @("$PolicyPrefix-app"); token_no_default_policy = $true; bind_secret_id = $true; token_ttl = '1h'; token_max_ttl = '4h'
        secret_id_ttl = "$($SecretIdHours)h"; secret_id_num_uses = $SecretIdUses; secret_id_bound_cidrs = $cidrs; token_bound_cidrs = $cidrs }
}
if (-not $fileThere) {
    $id = Invoke-Vault 'GET' "auth/$AppRoleMount/role/$RoleName/role-id"
    if ($id.Status -ne 200) { Write-Host "FAILED: the role ID could not be read (status $($id.Status)). Run again with -NewSecretId." -ForegroundColor Red; exit 2 }
    # The file first, for this user only; then the secret goes into it. CreateNew never replaces one.
    $stream = [IO.File]::Open($OutFile, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $written = $false
    try {
        $acl = New-Object Security.AccessControl.FileSecurity
        $acl.SetAccessRuleProtection($true, $false)
        $me = [Security.Principal.WindowsIdentity]::GetCurrent().User
        $acl.AddAccessRule((New-Object Security.AccessControl.FileSystemAccessRule($me, 'FullControl', 'Allow')))
        [IO.File]::SetAccessControl($OutFile, $acl)
        $secretId = Invoke-Vault 'POST' "auth/$AppRoleMount/role/$RoleName/secret-id" @{ metadata = '{"made-by":"Setup-SDV-VaultTransit"}' }
        if ($secretId.Status -eq 200) {
            $lines = @('# Vero''s login to Vault (task 0083). Keep it out of every repository, log and chat.',
                "platform.crypto.vault.address=$address", 'platform.crypto.vault.login=APPROLE',
                "platform.crypto.vault.approle-mount=$AppRoleMount", "platform.crypto.vault.transit-mount=$TransitMount",
                "platform.crypto.vault.role-id=$($id.Json.data.role_id)", "platform.crypto.vault.secret-id=$($secretId.Json.data.secret_id)", '')
            $bytes = (New-Object Text.UTF8Encoding($false)).GetBytes(($lines -join "`r`n"))
            $stream.Write($bytes, 0, $bytes.Length); $written = $true
        }
    } finally {
        $stream.Dispose()
        if (-not $written) { Remove-Item -LiteralPath $OutFile -Force } # only the empty file made just above
    }
    if (-not $written) { Write-Host 'FAILED: no secret ID. Run again with -NewSecretId.' -ForegroundColor Red; exit 2 }
    Write-Host "[ADDED] File $OutFile (role ID and secret ID; this Windows user only)"
}
$script:token = $null
Write-Host 'Done. The old transit/ mount and its keys were not touched.' -ForegroundColor Green
Write-Host "Next: the setup command makes the customer's keys (runbook, ""Keys for a customer"")."
exit 0
