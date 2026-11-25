<#
.SYNOPSIS
  The owner's step for the local development login server (task 0088): Vero's browser client and the test people.

.DESCRIPTION
  Works only on http://localhost:8181, realm document-platform-local. Asks for the Keycloak admin's name and password at the
  prompt; they are never stored, printed or put on a command line. It sets:
    - client sdv-web: public, standard flow only, PKCE S256, redirect http://localhost:5183/, web origin
      http://localhost:5183, the sdv-api audience and the group names in the access token;
    - one test person per Vero role (two Business Admins: some decisions need a second one), in the groups of dev\setup\role-mapping-SDV.csv, and one in no group.
  The generated passwords go only to -OutFile (this Windows user only). They are development accounts for demo data.
  No other client, person or realm setting is changed; a missing group is added. Run again, it says "nothing changed".

.EXAMPLE
  .\Set-SDV-DevKeycloak.ps1
#>
[CmdletBinding()]
param(
    [string]$Address = 'http://localhost:8181',
    [string]$Realm = 'document-platform-local',
    [string]$OutFile = 'C:\dev\sdv\dev-test-users.properties',
    # The test people are there but their passwords are not in the file: give those people new ones.
    [switch]$NewPasswords
)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest
$AllowedAddress = 'http://localhost:8181'
$AllowedRealm = 'document-platform-local'
$ClientId = 'sdv-web'
$Audience = 'sdv-api'
$RedirectUri = 'http://localhost:5183/'
$WebOrigin = 'http://localhost:5183'
$People = @(
    @{ Key = 'BUSINESS_USER'; Name = 'vero-test-business-user'; Group = 'BusinessUsers'; Last = 'Business User' }
    @{ Key = 'DOCUMENT_STEWARD'; Name = 'vero-test-document-steward'; Group = 'DocumentStewards'; Last = 'Document Steward' }
    @{ Key = 'RESTRICTED_STEWARD'; Name = 'vero-test-restricted-steward'; Group = 'RestrictedStewards'; Last = 'Restricted Steward' }
    @{ Key = 'BUSINESS_ADMIN'; Name = 'vero-test-business-admin'; Group = 'BusinessAdmins'; Last = 'Business Admin' }
    @{ Key = 'BUSINESS_ADMIN_2'; Name = 'vero-test-business-admin-2'; Group = 'BusinessAdmins'; Last = 'Business Admin Two' }
    @{ Key = 'IT_ADMIN'; Name = 'vero-test-it-admin'; Group = 'TenantITAdmins'; Last = 'IT Admin' }
    @{ Key = 'PLATFORM_OPERATIONS'; Name = 'vero-test-platform-operations'; Group = 'PlatformOperations'; Last = 'Platform Operations' }
    @{ Key = 'NO_GROUP'; Name = 'vero-test-no-group'; Group = ''; Last = 'No Group' }
)

$script:changes = 0
function Refuse([string]$Why) { throw "REFUSED: $Why" }
function Changed([string]$What) { $script:changes++; Write-Host "  $What" }
function Has($Object, [string]$Name) { $null -ne $Object -and $null -ne $Object.PSObject.Properties[$Name] }
function Value($Object, [string]$Name) { if (Has $Object $Name) { $Object.$Name } else { $null } }
function Put($Object, [string]$Name, $New) { if (Has $Object $Name) { $Object.$Name = $New } else { $Object | Add-Member -NotePropertyName $Name -NotePropertyValue $New } }

if ($Address.TrimEnd('/') -cne $AllowedAddress) { Refuse "only $AllowedAddress." }
if ($Realm -cne $AllowedRealm) { Refuse "only the realm $AllowedRealm." }
if (-not [IO.Path]::IsPathRooted($OutFile)) { Refuse 'OutFile must be a full path.' }
$repository = $PSScriptRoot.TrimEnd('\') + '\'
if ([IO.Path]::GetFullPath($OutFile).StartsWith($repository, [StringComparison]::OrdinalIgnoreCase)) { Refuse 'OutFile must be outside the repository.' }
if (-not (Test-Path -LiteralPath (Split-Path -Parent $OutFile) -PathType Container)) { Refuse 'the folder of OutFile is not there.' }

# One call. Bodies and answers are never written anywhere; of a failure, only the status is.
$script:access = $null
function Send([string]$Method, [string]$Path, [string]$Body, [string]$Type, [int[]]$Ok) {
    $request = [Net.HttpWebRequest]::Create("$AllowedAddress$Path")
    $request.Method = $Method; $request.Timeout = 20000; $request.AllowAutoRedirect = $false; $request.Proxy = $null
    if ($script:access) { $request.Headers['Authorization'] = "Bearer $script:access" }
    if ($Body) {
        $bytes = [Text.Encoding]::UTF8.GetBytes($Body)
        $request.ContentType = $Type; $request.ContentLength = $bytes.Length
        $stream = $request.GetRequestStream(); try { $stream.Write($bytes, 0, $bytes.Length) } finally { $stream.Dispose() }
    }
    try { $response = $request.GetResponse() } catch [Net.WebException] {
        $response = $_.Exception.Response
        if ($null -eq $response) { Refuse "Keycloak can't be reached at $AllowedAddress." }
    }
    $status = [int]$response.StatusCode
    try { $reader = New-Object IO.StreamReader($response.GetResponseStream(), [Text.Encoding]::UTF8); $text = $reader.ReadToEnd() } finally { $response.Dispose() }
    if ($Ok -notcontains $status) { throw "Keycloak answered $status to $Method $(($Path -split '\?')[0])." }
    # Through a variable, so that a list comes out as its items (Windows PowerShell 5.1).
    if ($text) { $answer = ConvertFrom-Json -InputObject $text; $answer }
}
function Api([string]$Method, [string]$Path, $Body = $null) {
    $json = ''
    if ($null -ne $Body) { $json = ConvertTo-Json -InputObject $Body -Depth 20 -Compress }
    Send $Method "/admin/realms/$AllowedRealm$Path" $json 'application/json' @(200, 201, 204)
}
function Find-Client { @(Api 'GET' "/clients?clientId=$ClientId") | Where-Object { $_ -and $_.clientId -ceq $ClientId } | Select-Object -First 1 }
function Find-Person([string]$Name) { @(Api 'GET' "/users?username=$Name&exact=true") | Where-Object { $_ -and $_.username -ceq $Name } | Select-Object -First 1 }
function Find-Group([string]$Name) { @(Api 'GET' "/groups?search=$Name&exact=true&max=100") | Where-Object { $_ -and $_.name -ceq $Name } | Select-Object -First 1 }

function New-TestPassword {
    $chars = 'ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789'
    $limit = 256 - (256 % $chars.Length)
    $rng = New-Object Security.Cryptography.RNGCryptoServiceProvider
    $b = New-Object byte[] 1; $out = ''
    try { while ($out.Length -lt 28) { $rng.GetBytes($b); if ($b[0] -lt $limit) { $out += $chars[$b[0] % $chars.Length] } } } finally { $rng.Dispose() }
    $out + '-aZ7'
}
$saved = [ordered]@{}
if (Test-Path -LiteralPath $OutFile) {
    foreach ($line in [IO.File]::ReadAllLines($OutFile)) { if ($line -match '^([A-Z_0-9]+\.(?:username|password))=(.+)$') { $saved[$Matches[1]] = $Matches[2] } }
}
function Save-People {
    if (-not (Test-Path -LiteralPath $OutFile)) { New-Item -ItemType File -Path $OutFile | Out-Null }
    # Every time, so that a file somebody made beforehand is closed too before a password goes in.
    & icacls $OutFile /inheritance:r /grant:r "$($env:USERDOMAIN)\$($env:USERNAME):F" | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The access to OutFile could not be limited; no password was written.' }
    $others = @((Get-Acl -LiteralPath $OutFile).Access | Where-Object { $_.IdentityReference.Value -ne "$($env:USERDOMAIN)\$($env:USERNAME)" })
    if ($others.Count -gt 0) { throw 'Somebody else has rights on OutFile; no password was written.' }
    $lines = @('# Local development accounts for demo data only (Set-SDV-DevKeycloak.ps1). Never commit or share.') + @($saved.GetEnumerator() | ForEach-Object { "$($_.Key)=$($_.Value)" })
    [IO.File]::WriteAllLines($OutFile, $lines, (New-Object Text.UTF8Encoding($false)))
}
function Set-Password([string]$Id, [string]$Password) { Api 'PUT' "/users/$Id/reset-password" @{ type = 'password'; value = $Password; temporary = $false } | Out-Null }

$adminName = [string](Read-Host 'Keycloak admin user')
$secret = Read-Host 'Keycloak admin password (not shown)' -AsSecureString
$refresh = $null
$problems = @()
try {
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
    try {
        $form = 'grant_type=password&client_id=admin-cli&username=' + [Uri]::EscapeDataString($adminName) + '&password=' + [Uri]::EscapeDataString([Runtime.InteropServices.Marshal]::PtrToStringBSTR($pointer))
        try { $answer = Send 'POST' '/realms/master/protocol/openid-connect/token' $form 'application/x-www-form-urlencoded' @(200) }
        catch { if ("$_" -match 'answered 40[01]') { Refuse 'Keycloak did not accept this admin sign-in. Nothing was changed.' }; throw }
        $script:access = $answer.access_token; $refresh = Value $answer 'refresh_token'
    } finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($pointer); $form = $null; $answer = $null; $secret.Dispose() }

    if ((Api 'GET' '').realm -cne $AllowedRealm) { Refuse 'this is not the realm.' }

    # ---- The browser client ----
    $wanted = [ordered]@{ enabled = $true; protocol = 'openid-connect'; publicClient = $true; bearerOnly = $false; standardFlowEnabled = $true; implicitFlowEnabled = $false; directAccessGrantsEnabled = $false; serviceAccountsEnabled = $false }
    $attributes = [ordered]@{ 'pkce.code.challenge.method' = 'S256'; 'post.logout.redirect.uris' = $RedirectUri; 'oauth2.device.authorization.grant.enabled' = 'false'; 'oidc.ciba.grant.enabled' = 'false' }
    $audienceMapper = @{ name = 'vero-audience'; protocol = 'openid-connect'; protocolMapper = 'oidc-audience-mapper'; config = @{ 'included.custom.audience' = $Audience; 'access.token.claim' = 'true'; 'id.token.claim' = 'false' } }
    $groupMapper = @{ name = 'vero-groups'; protocol = 'openid-connect'; protocolMapper = 'oidc-group-membership-mapper'; config = @{ 'claim.name' = 'groups'; 'full.path' = 'false'; 'access.token.claim' = 'true'; 'id.token.claim' = 'false'; 'userinfo.token.claim' = 'false' } }
    $client = Find-Client
    if (-not $client) {
        $new = @{ clientId = $ClientId; name = 'Vero'; fullScopeAllowed = $false; redirectUris = @($RedirectUri); webOrigins = @($WebOrigin); attributes = $attributes; protocolMappers = @($audienceMapper, $groupMapper) }
        foreach ($k in $wanted.Keys) { $new[$k] = $wanted[$k] }
        Api 'POST' '/clients' $new | Out-Null
        Changed "client ${ClientId}: added"
        $client = Find-Client
    }
    $different = @()
    foreach ($k in $wanted.Keys) { if ((Value $client $k) -cne $wanted[$k]) { $different += $k; Put $client $k $wanted[$k] } }
    if ((@(Value $client 'redirectUris') -join ' ') -cne $RedirectUri) { $different += 'redirectUris'; Put $client 'redirectUris' @($RedirectUri) }
    if ((@(Value $client 'webOrigins') -join ' ') -cne $WebOrigin) { $different += 'webOrigins'; Put $client 'webOrigins' @($WebOrigin) }
    if (-not (Has $client 'attributes')) { Put $client 'attributes' ([pscustomobject]@{}) }
    foreach ($k in $attributes.Keys) { if ((Value $client.attributes $k) -cne $attributes[$k]) { $different += $k; Put $client.attributes $k $attributes[$k] } }
    if ($different.Count -gt 0) { Api 'PUT' "/clients/$($client.id)" $client | Out-Null; Changed "client ${ClientId}: set $($different -join ', ')" }
    $mappers = @(Api 'GET' "/clients/$($client.id)/protocol-mappers/models" | ForEach-Object { $_ })
    $has = @($mappers | Where-Object { $_ -and $_.protocolMapper -eq 'oidc-audience-mapper' -and ((Value $_.config 'included.custom.audience') -ceq $Audience -or (Value $_.config 'included.client.audience') -ceq $Audience) -and (Value $_.config 'access.token.claim') -eq 'true' })
    if ($has.Count -eq 0) { Api 'POST' "/clients/$($client.id)/protocol-mappers/models" $audienceMapper | Out-Null; Changed "client ${ClientId}: audience $Audience in the access token" }
    $groupMappers = @($mappers | Where-Object { $_ -and $_.protocolMapper -eq 'oidc-group-membership-mapper' -and (Value $_.config 'claim.name') -ceq 'groups' })
    if ($groupMappers.Count -eq 0) { Api 'POST' "/clients/$($client.id)/protocol-mappers/models" $groupMapper | Out-Null; Changed "client ${ClientId}: group names in the access token" }
    elseif ((Value $groupMappers[0].config 'full.path') -ne 'false' -or (Value $groupMappers[0].config 'access.token.claim') -ne 'true') {
        Put $groupMappers[0].config 'full.path' 'false'; Put $groupMappers[0].config 'access.token.claim' 'true'
        Api 'PUT' "/clients/$($client.id)/protocol-mappers/models/$($groupMappers[0].id)" $groupMappers[0] | Out-Null; Changed "client ${ClientId}: group names without a path"
    }

    # ---- The test people ----
    foreach ($p in $People) {
        if ($p.Group -and -not (Find-Group $p.Group)) { Api 'POST' '/groups' @{ name = $p.Group } | Out-Null; Changed "group $($p.Group): added" }
        $passwordKey = "$($p.Key).password"
        $person = Find-Person $p.Name
        if (-not $person) {
            # Saved first, so a person never exists without its password in the file.
            $saved["$($p.Key).username"] = $p.Name; $saved[$passwordKey] = New-TestPassword
            Save-People
            $groups = @(); if ($p.Group) { $groups = @("/$($p.Group)") }
            Api 'POST' '/users' @{ username = $p.Name; enabled = $true; email = "$($p.Name)@vero.invalid"; emailVerified = $true; firstName = 'Vero Test'; lastName = $p.Last; requiredActions = @(); groups = $groups; credentials = @(@{ type = 'password'; value = $saved[$passwordKey]; temporary = $false }) } | Out-Null
            $person = Find-Person $p.Name
            Changed "$($p.Name): added"
        } elseif (-not $saved.Contains($passwordKey)) {
            if ($NewPasswords) {
                $saved["$($p.Key).username"] = $p.Name; $saved[$passwordKey] = New-TestPassword
                Save-People
                Set-Password $person.id $saved[$passwordKey]
                Changed "$($p.Name): new password"
            } else { $problems += "$($p.Name) is there but its password is not in the file. Run again with -NewPasswords." }
        }
        if (-not $person.enabled) { $problems += "$($p.Name) is disabled. Left as it is." }
        $member = @(Api 'GET' "/users/$($person.id)/groups" | ForEach-Object { $_ } | Where-Object { $_ } | ForEach-Object { $_.name })
        if (($member -join ' ') -cne $p.Group) { $problems += "$($p.Name) should be in '$($p.Group)' only but is in '$($member -join ', ')'. Left as it is." }
    }
} finally {
    if ($refresh) {
        $script:access = $null
        try { Send 'POST' '/realms/master/protocol/openid-connect/logout' "client_id=admin-cli&refresh_token=$([Uri]::EscapeDataString($refresh))" 'application/x-www-form-urlencoded' @(200, 204) | Out-Null } catch { Write-Warning 'The admin session was not signed out; it ends by itself.' }
        $refresh = $null
    }
    $script:access = $null
}
if ($problems.Count -gt 0) { foreach ($x in $problems) { Write-Warning $x }; Write-Host 'Not complete: see the warnings.'; exit 1 }
if ($script:changes -eq 0) { Write-Host 'nothing changed' } else { Write-Host "Done: $script:changes change(s). The test people are in $OutFile (passwords not shown)." }
exit 0
