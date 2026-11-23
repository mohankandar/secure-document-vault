[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Backend = Join-Path $Root 'backend'
$Frontend = Join-Path $Root 'frontend\sdv-ui'

function Invoke-Step {
    param([string]$Name, [scriptblock]$Action)
    Write-Host ""
    Write-Host "============================================================"
    Write-Host $Name
    Write-Host "============================================================"
    & $Action
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

Write-Host "Vero build - identity on platform-core (task 0041) / migration 035"

# java -version writes its version text to STDERR. Windows PowerShell 5.1
# can promote native STDERR to NativeCommandError when ErrorActionPreference=Stop,
# so capture it through cmd.exe instead of invoking java directly.
$javaText = (& cmd.exe /d /c "java -version 2>&1" | Out-String)
$javaExit = $LASTEXITCODE
if ($javaExit -ne 0 -or $javaText -notmatch 'version "21[\.]') {
    throw "Java 21 is required. Detected:`n$javaText"
}

if (-not (Get-Command mvn -ErrorAction SilentlyContinue)) {
    throw 'Maven is required on PATH.'
}
if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    throw 'Node.js is required on PATH.'
}
if (-not (Get-Command npm -ErrorAction SilentlyContinue)) {
    throw 'npm is required on PATH.'
}

Write-Host 'Identity: platform-core released modules com.documentplatform.platform:platform-identity-* (platform-bom, see backend/pom.xml)'
Write-Host 'Maven reads them from GitHub Packages (server github-platform-core, token in GITHUB_PACKAGES_TOKEN; docs/install/PLATFORM_PACKAGES.md).'

Push-Location $Backend
try {
    Invoke-Step 'Backend Maven reactor build' { & mvn clean verify }
} finally {
    Pop-Location
}

Push-Location $Frontend
try {
    if (Test-Path (Join-Path $Frontend 'package-lock.json')) {
        Invoke-Step 'Frontend dependency install (npm ci)' { & npm ci }
    } else {
        Write-Warning 'package-lock.json is not present; running npm install once to materialize the exact frontend dependency lock for the V1 release baseline.'
        Invoke-Step 'Frontend dependency install + lock generation (npm install)' { & npm install }
        if (-not (Test-Path (Join-Path $Frontend 'package-lock.json'))) {
            throw 'npm install completed but package-lock.json was not created. V1 release packaging requires a frontend dependency lock.'
        }
    }
    Invoke-Step 'Frontend production build' { & npm run build }
} finally {
    Pop-Location
}

Write-Host ""
Write-Host "BUILD SUCCESS"
Write-Host "Backend jar: backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar"
Write-Host "Frontend dist: frontend\sdv-ui\dist"
