# Task 0097: Vero installed from the package, following its runbook, on vero-install-* scratch containers only.
# Secrets are made here at random and kept in $secrets (the session's scratch folder); none is printed.
param([ValidateSet('db', 'vault', 'keycloak', 'schema', 'setup', 'start', 'web', 'all')][string[]]$Phase = @('all'))
$ErrorActionPreference = 'Stop'
$pkg = 'C:\dev\sdv\secure-document-vault\.runtime\install-0097\vero-1.0.0-rc1-DEVELOPMENT'
$work = 'C:\dev\sdv\secure-document-vault\.runtime\install-0097\server'   # Vero's working folder on "the server"
$secrets = Join-Path $PSScriptRoot 'install-0097-secrets'
New-Item -ItemType Directory -Force -Path $work, $secrets | Out-Null
$timing = Join-Path $PSScriptRoot 'install-0097-times.txt'
function Step([string]$Name, [scriptblock]$Body) {
    $w = [Diagnostics.Stopwatch]::StartNew()
    & $Body
    $line = '{0}  {1,-55} {2,7:N1} s' -f (Get-Date -Format 'HH:mm:ss'), $Name, $w.Elapsed.TotalSeconds
    Add-Content -Path $timing -Value $line; Write-Host "PASS  $line" -ForegroundColor Green
}
function Secret([string]$Name) {
    $f = Join-Path $secrets $Name
    if (-not (Test-Path $f)) {
        $b = New-Object byte[] 24; [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($b)
        [IO.File]::WriteAllText($f, ([Convert]::ToBase64String($b) -replace '[^A-Za-z0-9]', 'x'))
    }
    [IO.File]::ReadAllText($f).Trim()
}
function Docker-Run([string[]]$ArgList) { & docker @ArgList | Out-Null; if ($LASTEXITCODE -ne 0) { throw "docker $($ArgList[0..2] -join ' ') failed" } }
function Exists([string]$Name) { [bool](& docker ps -a --filter "name=^/$Name$" --format '{{.Names}}') }
function Psql([string]$Db, [string]$Sql) {
    $out = $Sql | & docker exec -i vero-install-postgres psql -X -q -v ON_ERROR_STOP=1 -U postgres -d $Db 2>&1
    if ($LASTEXITCODE -ne 0) { throw "psql failed: $out" }
    $out
}
$all = $Phase -contains 'all'

if ($all -or $Phase -contains 'db') {
    Step 'A. PostgreSQL 16 (scratch vero-install-postgres)' {
        if (-not (Exists 'vero-install-postgres')) {
            $env:POSTGRES_PASSWORD = Secret 'pg-superuser'
            try { Docker-Run @('run', '-d', '--name', 'vero-install-postgres', '-e', 'POSTGRES_PASSWORD', '-p', '127.0.0.1:55497:5432', 'postgres:16') }
            finally { Remove-Item Env:POSTGRES_PASSWORD }
        }
        for ($i = 0; $i -lt 60; $i++) { & docker exec vero-install-postgres pg_isready -U postgres *> $null; if ($LASTEXITCODE -eq 0) { break }; Start-Sleep 1 }
        Start-Sleep 2
    }
    Step 'A. Runbook "Two database users" (word for word)' {
        $owner = Secret 'pg-vero-owner'; $app = Secret 'pg-vero-app'
        Psql 'postgres' "create role vero_owner login password '$owner'; create role vero_app login password '$app'; create database vero owner vero_owner; revoke all on database vero from public; grant connect on database vero to vero_app;" | Out-Null
        Psql 'vero' "revoke create on schema public from public; grant usage on schema public to vero_app; alter default privileges for role vero_owner in schema public grant select, insert, update, delete on tables to vero_app;" | Out-Null
        $l = Psql 'postgres' '\l vero'; $d = Psql 'vero' '\ddp'
        if ("$l" -notmatch 'vero_owner') { throw 'check: \l vero does not name vero_owner' }
        if ("$d" -notmatch 'vero_app=arwd/vero_owner') { throw 'check: \ddp does not show vero_app=arwd/vero_owner' }
    }
}

if ($all -or $Phase -contains 'vault') {
    Step 'B. Vault 1.17 dev (scratch vero-install-vault)' {
        if (-not (Exists 'vero-install-vault')) {
            $env:VAULT_DEV_ROOT_TOKEN_ID = Secret 'vault-root'
            try { Docker-Run @('run', '-d', '--name', 'vero-install-vault', '-e', 'VAULT_DEV_ROOT_TOKEN_ID', '--cap-add', 'IPC_LOCK', '-p', '127.0.0.1:18297:8200', 'hashicorp/vault:1.17.6') }
            finally { Remove-Item Env:VAULT_DEV_ROOT_TOKEN_ID }
        }
        for ($i = 0; $i -lt 60; $i++) { try { if ((Invoke-WebRequest 'http://127.0.0.1:18297/v1/sys/health' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { break } } catch { }; Start-Sleep -Milliseconds 500 }
    }
    Step 'B. Runbook "Keys for a customer": Setup-SDV-VaultTransit.ps1' {
        $gateway = (& docker inspect vero-install-vault --format '{{range .NetworkSettings.Networks}}{{.Gateway}} {{end}}').Trim().Split(' ')[0]
        $env:VAULT_TOKEN = Secret 'vault-root'
        try {
            $out = & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $pkg 'Setup-SDV-VaultTransit.ps1') -VaultAddress 'http://127.0.0.1:18297' `
                -VeroAddresses "$gateway/32" -OutFile (Join-Path $secrets 'vero-vault-login.properties') 2>&1
            $code = $LASTEXITCODE
        } finally { Remove-Item Env:VAULT_TOKEN }
        Add-Content -Path (Join-Path $PSScriptRoot 'install-0097-vault.log') -Value ($out | Out-String)
        if ($code -ne 0) { throw "Setup-SDV-VaultTransit.ps1 exit $code (log: install-0097-vault.log)" }
    }
}

function Kc([string[]]$KcArgs) {
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $errFile = Join-Path $env:TEMP 'kcadm-err.txt'
    $out = & docker exec vero-install-keycloak-tls /opt/keycloak/bin/kcadm.sh @KcArgs 2> $errFile
    if ($LASTEXITCODE -ne 0) { $out = Get-Content $errFile -Raw }
    $ErrorActionPreference = $before
    if ($LASTEXITCODE -ne 0) { throw "kcadm $($KcArgs[0..1] -join ' ') failed: $out" }
    $out
}
if ($all -or $Phase -contains 'keycloak') {
    Step 'C. Keycloak 26 (scratch vero-install-keycloak-tls)' {
        if (-not (Exists 'vero-install-keycloak-tls')) {
            $env:KC_BOOTSTRAP_ADMIN_USERNAME = 'admin'; $env:KC_BOOTSTRAP_ADMIN_PASSWORD = Secret 'kc-admin'
            $certs = (Join-Path $secrets 'certs').Replace('\', '/')
            try { Docker-Run @('run', '-d', '--name', 'vero-install-keycloak-tls', '-e', 'KC_BOOTSTRAP_ADMIN_USERNAME', '-e', 'KC_BOOTSTRAP_ADMIN_PASSWORD',
                '-e', 'KC_HTTPS_CERTIFICATE_FILE=/certs/tls.crt', '-e', 'KC_HTTPS_CERTIFICATE_KEY_FILE=/certs/tls.key', '-v', "${certs}:/certs:ro",
                '-p', '127.0.0.1:18298:8443', 'quay.io/keycloak/keycloak:26.8.2', 'start-dev') }
            finally { Remove-Item Env:KC_BOOTSTRAP_ADMIN_USERNAME, Env:KC_BOOTSTRAP_ADMIN_PASSWORD }
        }
        for ($i = 0; $i -lt 120; $i++) { if ((& curl.exe -sk -o NUL -w '%{http_code}' https://127.0.0.1:18298/realms/master) -eq '200') { break }; Start-Sleep 1 }
    }
    Step 'C. The login system, from the guides (realm acme)' {
        $null = Kc @('config', 'credentials', '--server', 'http://localhost:8080', '--realm', 'master', '--user', 'admin', '--password', (Secret 'kc-admin'))
        if (-not ((Kc @('get', 'realms', '--fields', 'realm')) -match '"acme"')) { $null = Kc @('create', 'realms', '-s', 'realm=acme', '-s', 'enabled=true', '-s', 'accessTokenLifespan=900') }
        $aud = @{ name = 'vero-api audience'; protocol = 'openid-connect'; protocolMapper = 'oidc-audience-mapper'
            config = @{ 'included.custom.audience' = 'vero-api'; 'access.token.claim' = 'true'; 'id.token.claim' = 'false' } }
        $groups = @{ name = 'groups'; protocol = 'openid-connect'; protocolMapper = 'oidc-group-membership-mapper'
            config = @{ 'claim.name' = 'groups'; 'full.path' = 'false'; 'access.token.claim' = 'true'; 'id.token.claim' = 'false'; 'userinfo.token.claim' = 'false' } }
        # Runbook "Sign-in in the browser" 1 to 3: a public client, code with PKCE S256, exact redirect and origin, the API audience, groups.
        $clients = @(
            @{ clientId = 'vero-web'; publicClient = $true; standardFlowEnabled = $true; implicitFlowEnabled = $false; directAccessGrantsEnabled = $false
               redirectUris = @('http://localhost:18299/'); webOrigins = @('http://localhost:18299')
               attributes = @{ 'pkce.code.challenge.method' = 'S256'; 'post.logout.redirect.uris' = 'http://localhost:18299/' }; protocolMappers = @($aud, $groups) },
            # One client per sending system: its own token (client credentials) for Vero's API audience.
            @{ clientId = 'acme-loans'; publicClient = $false; serviceAccountsEnabled = $true; standardFlowEnabled = $false; directAccessGrantsEnabled = $false
               protocolMappers = @($aud) })
        foreach ($c in $clients) {
            if ((Kc @('get', 'clients', '-r', 'acme', '-q', "clientId=$($c.clientId)", '--fields', 'id')) -match '"id"') { continue }
            $json = $c | ConvertTo-Json -Depth 6 -Compress
            $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
            $out = $json | & docker exec -i vero-install-keycloak-tls /opt/keycloak/bin/kcadm.sh create clients -r acme -f - 2>&1
            $ErrorActionPreference = $before
            if ($LASTEXITCODE -ne 0) { throw "client $($c.clientId): $out" }
        }
        $loans = ((Kc @('get', 'clients', '-r', 'acme', '-q', 'clientId=acme-loans', '--fields', 'id')) | Out-String | ConvertFrom-Json)[0].id
        [IO.File]::WriteAllText((Join-Path $secrets 'kc-acme-loans'), ((Kc @('get', "clients/$loans/client-secret", '-r', 'acme')) | Out-String | ConvertFrom-Json).value)
        # The role mapping's groups, and one person in each.
        foreach ($g in 'Vero IT', 'Vero Admins', 'Finance Readers') {
            if (-not ((Kc @('get', 'groups', '-r', 'acme', '--fields', 'name')) -match [regex]::Escape("""$g"""))) { $null = Kc @('create', 'groups', '-r', 'acme', '-s', "name=$g") }
        }
        $allGroups = (Kc @('get', 'groups', '-r', 'acme', '--fields', 'id,name')) | Out-String | ConvertFrom-Json
        foreach ($u in @(@('it.admin', 'Ivo', 'Tech', 'Vero IT'), @('ba.admin', 'Bea', 'Admin', 'Vero Admins'), @('reader', 'Rae', 'Reader', 'Finance Readers'))) {
            if (-not ((Kc @('get', 'users', '-r', 'acme', '-q', "username=$($u[0])", '--fields', 'id')) -match '"id"')) {
                $null = Kc @('create', 'users', '-r', 'acme', '-s', "username=$($u[0])", '-s', 'enabled=true', '-s', "firstName=$($u[1])", '-s', "lastName=$($u[2])",
                    '-s', "email=$($u[0])@acme.example", '-s', 'emailVerified=true')
            }
            $null = Kc @('set-password', '-r', 'acme', '--username', $u[0], '--new-password', (Secret "kc-user-$($u[0])"))
            $uid = ((Kc @('get', 'users', '-r', 'acme', '-q', "username=$($u[0])", '--fields', 'id')) | Out-String | ConvertFrom-Json)[0].id
            $gid = ($allGroups | Where-Object { $_.name -eq $u[3] })[0].id
            $null = Kc @('update', "users/$uid/groups/$gid", '-r', 'acme', '-s', 'realm=acme', '-s', "userId=$uid", '-s', "groupId=$gid", '-n')
        }
    }
}

function Native([string]$Log, [scriptblock]$Run) {
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    $out = & $Run 2>&1 | Out-String
    $code = $LASTEXITCODE; $ErrorActionPreference = $before
    Add-Content -Path (Join-Path $PSScriptRoot $Log) -Value $out
    if ($code -ne 0 -and -not ($code -eq 3 -and $env:ALLOW_EXIT_3)) { throw "exit $code (log: $Log)" }
}
$dbUrl = 'jdbc:postgresql://localhost:55497/vero'
# Runbook "Before you install", your own certificate authority (Windows lines), for setup and Vero.
$trust = Join-Path $work 'vero-trust.jks'
$caPem = Join-Path $secrets 'certs\ca.pem'
function Ensure-Trust {
    if (Test-Path $trust) { return }
    Copy-Item "$env:JAVA_HOME\lib\security\cacerts" $trust
    $keytool = Join-Path $env:JAVA_HOME 'bin\keytool.exe'
    Native 'install-0097-trust.log' { & $keytool -importcert -noprompt -alias company-ca -file $caPem -keystore $trust -storepass changeit }
}
if ($all -or $Phase -contains 'schema') {
    Step 'D. Runbook "The schema step": update' {
        $env:SDV_SCHEMA_DB_PASSWORD = Secret 'pg-vero-owner'
        try { Native 'install-0097-schema.log' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $pkg 'Update-SDV-Schema.ps1') update -Url $dbUrl -Username vero_owner } }
        finally { Remove-Item Env:SDV_SCHEMA_DB_PASSWORD }
    }
}
if ($all -or $Phase -contains 'setup') {
    $files = Join-Path $work 'setup-files'
    New-Item -ItemType Directory -Force -Path $files | Out-Null
    Step 'E. SETUP.md section 2: the files you write' {
        Copy-Item 'C:\dev\sdv\secure-document-vault\dev\setup\licence-vero-demo.json' (Join-Path $files 'licence.json')   # stands in for "your licence"
        $signin = [ordered]@{ format = 'platform-signin/1'; tenantId = 'TEN-VERO-DEMO-0001'; mode = 'direct'
            loginSystem = [ordered]@{ type = 'OIDC'; issuer = 'https://localhost:18298/realms/acme'; providerId = 'customer-oidc' }
            products = @{ SDV = [ordered]@{ browserClientId = 'vero-web'; apiAudience = 'vero-api'; roleMappingFile = 'role-mapping-SDV.csv'
                clientBindingsFile = 'client-bindings-SDV.csv'; sources = @(@{ key = 'loan-system'; name = 'Loan servicing system' }) } }
            timeZone = 'America/New_York' }
        [IO.File]::WriteAllText((Join-Path $files 'signin.json'), ($signin | ConvertTo-Json -Depth 6))
        [IO.File]::WriteAllText((Join-Path $files 'role-mapping-SDV.csv'), "# Format: platform-role-mapping/1`n# Product: SDV`nentity_id,entity_name,group,role`nENT-VERO-DEMO-0001,,Vero IT,IT_ADMIN`nENT-VERO-DEMO-0001,,Vero Admins,BUSINESS_ADMIN`nENT-VERO-DEMO-0001,,Finance Readers,BUSINESS_USER`n")
        [IO.File]::WriteAllText((Join-Path $files 'client-bindings-SDV.csv'), "client_id,entity_slot,source,description`nacme-loans,ENT-VERO-DEMO-0001,loan-system,Loan servicing system`n")
    }
    Step 'E. Setup: plan, then apply (from the package)' {
        $setupToken = (Invoke-RestMethod -Method Post -Uri 'http://127.0.0.1:18297/v1/auth/token/create' -Headers @{ 'X-Vault-Token' = (Secret 'vault-root') } `
            -Body (@{ policies = @('platform-crypto-setup'); ttl = '15m'; no_default_policy = $true } | ConvertTo-Json) -ContentType 'application/json').auth.client_token
        $env:PLATFORM_SETUP_DB_URL = $dbUrl; $env:PLATFORM_SETUP_DB_USER = 'vero_app'; $env:PLATFORM_SETUP_DB_PASSWORD = Secret 'pg-vero-app'
        $env:PLATFORM_SETUP_VAULT_ADDR = 'http://127.0.0.1:18297'; $env:PLATFORM_SETUP_VAULT_TRANSIT_MOUNT = 'vero-transit'; $env:PLATFORM_SETUP_VAULT_TOKEN = $setupToken
        Ensure-Trust; $env:JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=$trust"
        $extra = @(); if ($env:REPLACE_CONNECTION) { $extra = @('--replace-connection') }
        Push-Location $work
        try {
            Native 'install-0097-setup.log' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $pkg 'setup.ps1') plan -Licence (Join-Path $files 'licence.json') -Signin (Join-Path $files 'signin.json') }
            Native 'install-0097-setup.log' { & powershell.exe -NoProfile -ExecutionPolicy Bypass -File (Join-Path $pkg 'setup.ps1') apply -Licence (Join-Path $files 'licence.json') -Signin (Join-Path $files 'signin.json') @extra }
        } finally {
            Pop-Location
            Remove-Item Env:JAVA_TOOL_OPTIONS -ErrorAction SilentlyContinue
            Remove-Item Env:PLATFORM_SETUP_DB_URL, Env:PLATFORM_SETUP_DB_USER, Env:PLATFORM_SETUP_DB_PASSWORD, Env:PLATFORM_SETUP_VAULT_ADDR, Env:PLATFORM_SETUP_VAULT_TRANSIT_MOUNT, Env:PLATFORM_SETUP_VAULT_TOKEN -ErrorAction SilentlyContinue
        }
    }
}
if ($all -or $Phase -contains 'start') {
    Step 'F. Runbook "The first start" (Windows: java -jar in the working folder)' {
        New-Item -ItemType Directory -Force -Path (Join-Path $work 'data'), (Join-Path $work 'erase-log') | Out-Null
        $set = [ordered]@{
            SDV_SERVER_PORT = '8297'; SDV_DB_URL = $dbUrl; SDV_DB_USERNAME = 'vero_app'; SDV_DB_PASSWORD = (Secret 'pg-vero-app')
            SDV_ERASE_LOG_FOLDER = (Join-Path $work 'erase-log'); SDV_VAULT_LOGIN_FILE = (Join-Path $secrets 'vero-vault-login.properties')
            SDV_AUTH_MODE = 'enterprise-jwt'; SDV_AUTH_JWT_ISSUER = 'https://localhost:18298/realms/acme'
            SDV_AUTH_JWT_JWK_SET_URI = 'https://localhost:18298/realms/acme/protocol/openid-connect/certs'; SDV_AUTH_JWT_AUDIENCE = 'vero-api'
            SDV_AUTH_JWT_PROVIDER_ID = 'customer-oidc'; SDV_OUTBOUND_ALLOW_INTERNAL_ADDRESSES = 'true'
            JAVA_TOOL_OPTIONS = "-Djavax.net.ssl.trustStore=$trust"; PLATFORM_OUTBOUND_PRIVATE_CA_FILE = $caPem }
        Ensure-Trust
        foreach ($k in $set.Keys) { Set-Item -Path "Env:$k" -Value $set[$k] }
        try {
            $jar = Join-Path $pkg 'sdv-web-1.0.0-rc1.jar'
            $p = Start-Process -FilePath (Get-Command java).Source -ArgumentList @('-jar', "`"$jar`"") -WorkingDirectory $work -PassThru -WindowStyle Hidden `
                -RedirectStandardOutput (Join-Path $PSScriptRoot 'install-0097-vero.out.log') -RedirectStandardError (Join-Path $PSScriptRoot 'install-0097-vero.err.log')
            $null = $p.Handle
            [IO.File]::WriteAllText((Join-Path $secrets 'vero.pid'), "$($p.Id)")
        } finally { foreach ($k in $set.Keys) { Remove-Item -Path "Env:$k" -ErrorAction SilentlyContinue } }
        $ready = $false
        for ($i = 0; $i -lt 150 -and -not $p.HasExited; $i++) {
            try { if ((Invoke-WebRequest 'http://127.0.0.1:8297/actuator/health/readiness' -UseBasicParsing -TimeoutSec 2).StatusCode -eq 200) { $ready = $true; break } } catch { }
            Start-Sleep 1
        }
        if (-not $ready) { throw "Vero isn't ready (exited: $($p.HasExited)); see install-0097-vero.out.log" }
        if (-not (Select-String -Path (Join-Path $PSScriptRoot 'install-0097-vero.out.log') -Pattern 'the database matches this Vero version' -Quiet)) { throw 'check: the schema check line is not in the log' }
    }
}
