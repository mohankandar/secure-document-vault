<#
.SYNOPSIS
  Proves what dev\testers\Caddyfile lets through (task 0089), on throwaway containers without any published port.

.DESCRIPTION
  Runs the Caddyfile with two stand-ins that answer "VERO" and "LOGIN" instead of Vero and the login system, and asks
  it for allowed and forbidden addresses. Uses the image caddy only; touches no Vero, database, Vault or Keycloak.
#>
[CmdletBinding()]
param([string]$Image = 'caddy:2.11.4')

$ErrorActionPreference = 'Stop'
$name = "vero-testers-proxy-check-$PID"
$work = Join-Path ([IO.Path]::GetTempPath()) $name
New-Item -ItemType Directory -Path (Join-Path $work 'dist\assets') | Out-Null
$failed = 0
try {
    $plain = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllText((Join-Path $work 'dist\index.html'), 'SCREENS', $plain)
    [IO.File]::WriteAllText((Join-Path $work 'dist\assets\a.js'), 'ASSET', $plain)
    [IO.File]::WriteAllText((Join-Path $work 'dist\secret.txt'), 'SECRET', $plain)
    # The stand-ins: whatever reaches them is answered, so a forbidden address that gets through is seen.
    [IO.File]::WriteAllText((Join-Path $work 'stand-in.caddy'), "{`n admin off`n auto_https off`n}`nhttp://:8292 {`n respond ""VERO xff={header.X-Forwarded-For}""`n}`nhttp://:8291 {`n respond ""LOGIN""`n}`n", $plain)

    & docker run -d --rm --name $name -v "$($work):/w:ro" -v "$(Join-Path $PSScriptRoot 'dev\testers\Caddyfile'):/w2/Caddyfile:ro" `
        -e VERO_TESTERS_HOST=vero.testers.test -e VERO_TESTERS_DIST=/w/dist $Image `
        sh -c 'caddy run --adapter caddyfile --config /w/stand-in.caddy & exec caddy run --adapter caddyfile --config /w2/Caddyfile' | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'The check container did not start.' }

    function Ask([string]$Path, [string]$HostName, [string]$Forwarded) {
        # Raw, so the path stays as given. The pause keeps the caller's side open: closing it cancels a proxied request.
        $out = & docker exec $name sh -c "(printf 'GET %s HTTP/1.1\r\nHost: %s\r\nCf-Connecting-Ip: 203.0.113.9\r\nX-Forwarded-For: $Forwarded\r\nConnection: close\r\n\r\n' '$Path' '$HostName'; sleep 1) | nc -w 5 127.0.0.1 8290"
        return ($out -join "`n")
    }
    for ($i = 0; $i -lt 20; $i++) { if ((Ask '/' 'vero.testers.test' '') -match 'SCREENS') { break }; Start-Sleep -Milliseconds 500 }

    $cases = @(
        @('/', 'SCREENS'), @('/index.html', 'SCREENS'), @('/assets/a.js', 'ASSET'),
        @('/api/v1/session/sign-in', 'VERO xff=203\.0\.113\.9(\s|$)'), @('/api/v1/admin/entities', 'VERO'),
        @('/realms/vero-testers/protocol/openid-connect/auth', 'LOGIN'), @('/realms/vero-testers/login-actions/authenticate', 'LOGIN'),
        @('/realms/vero-testers/.well-known/openid-configuration', 'LOGIN'), @('/resources/abc12/login/keycloak.v2/css/styles.css', 'LOGIN')
    )
    foreach ($p in '/actuator', '/actuator/health', '/actuator/info', '/actuator/health/readiness', '/ACTUATOR/health', '/%61ctuator/health',
        '/api/v1/../../actuator/health', '/assets/../actuator/health', '//actuator/health', '/api', '/api/v2/x', '/error',
        '/api/v1/ingestion', '/api/v1/ingestion/submissions', '/api/v1/%69ngestion/submissions', '/api/v1//ingestion/submissions',
        '/API/v1/ingestion/submissions', '/api/v1/x/../ingestion/submissions', '/api/v1/%2e%2e/v1/ingestion/submissions',
        '/admin', '/admin/', '/admin/master/console/', '/realms/master', '/realms/master/protocol/openid-connect/token',
        '/realms/vero-testers/account/', '/realms/vero-testers/clients-registrations/openid-connect', '/realms/vero-testers/broker/x/endpoint',
        '/realms/vero-testers/protocol/openid-connect/../../../master/protocol/openid-connect/token', '/realms/vero-testers/login-actions/%2e%2e/%2e%2e/master/',
        '/realms/vero-testers/protocol/openid-connect/..%2f..%2f..%2fmaster/', '/resources/abc12/admin/x.js', '/resources/abc12/login/../admin/x.js',
        '/health', '/metrics', '/v1/sys/health', '/secret.txt', '/assets/../secret.txt', '/assets/%2e%2e/secret.txt', '/index.html/../secret.txt', '/robots.txt') {
        $cases += , @($p, '^HTTP/1\.1 (404|400)')
    }
    foreach ($case in $cases) {
        $answer = Ask $case[0] 'vero.testers.test' '198.51.100.7'
        $ok = $answer -match $case[1]
        if (-not $ok) { $failed++ }
        Write-Host ("{0} {1}  ->  {2}" -f $(if ($ok) { '[OK]  ' } else { '[FAIL]' }), $case[0], (($answer -split "`n")[0]).Trim())
        if (-not $ok) { Write-Host "        $((($answer -split "`n")[-1]).Trim())" }
    }
    foreach ($other in 'localhost', '127.0.0.1', 'other.testers.test', 'vero.testers.test.evil.test') {
        $answer = Ask '/api/v1/session/sign-in' $other ''
        $ok = $answer -match '^HTTP/1\.1 404'
        if (-not $ok) { $failed++ }
        Write-Host ("{0} Host {1}  ->  {2}" -f $(if ($ok) { '[OK]  ' } else { '[FAIL]' }), $other, (($answer -split "`n")[0]).Trim())
    }
    $cors = Ask '/' 'vero.testers.test' ''
    foreach ($header in 'Content-Security-Policy: default-src ''self''', 'Strict-Transport-Security: max-age=86400', 'X-Content-Type-Options: nosniff', 'Referrer-Policy: no-referrer') {
        $ok = $cors.Contains($header)
        if (-not $ok) { $failed++ }
        Write-Host ("{0} header {1}" -f $(if ($ok) { '[OK]  ' } else { '[FAIL]' }), $header)
    }
} finally {
    $ErrorActionPreference = 'Continue'
    & docker stop $name *> $null
    Remove-Item -LiteralPath $work -Recurse -Force -ErrorAction SilentlyContinue
}
if ($failed -gt 0) { Write-Host "$failed checks failed."; exit 1 }
Write-Host 'All checks passed.'
