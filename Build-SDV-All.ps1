[CmdletBinding()]
param(
    # Task 0086: test only the backend modules this branch changes (against -Base), the modules they need and the
    # modules that use them, and build the frontend only if it changed. Maven runs in parallel (-T 1C), tests only: no
    # jar. Not a replacement for the full build; anything it can't place in a module runs the full build instead.
    [switch]$Quick,
    [string]$Base = 'origin/release/vero-r7'
)

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

# What -Quick covers, or $null when only the full build will do.
function Get-QuickPlan {
    $mergeBase = & git -C $Root merge-base $Base HEAD
    if ($LASTEXITCODE -ne 0) { return $null }
    $paths = @(& git -C $Root -c core.quotePath=false diff --no-renames --name-only $mergeBase)
    if ($LASTEXITCODE -ne 0) { return $null }
    $paths += @(& git -C $Root -c core.quotePath=false ls-files --others --exclude-standard)
    if ($LASTEXITCODE -ne 0) { return $null }

    [xml]$pom = Get-Content -LiteralPath (Join-Path $Backend 'pom.xml') -Raw
    $known = @($pom.project.modules.module)
    $modules = @{}
    $frontendChanged = $false
    foreach ($path in $paths) {
        if ($path -match '^backend/([^/]+)/' -and $known -contains $Matches[1]) { $modules[$Matches[1]] = $true }
        elseif ($path -like 'frontend/*') { $frontendChanged = $true }
        elseif ($path -match '^docs/work/[^/]+\.md$') { }
        else {
            Write-Host "Not in one module: $path"
            return $null
        }
    }
    # Every module that uses a changed one. Not Maven's -amd: -am does not cover the modules -amd adds, so those would
    # be tested against old jars or none.
    $uses = @{}
    foreach ($module in $known) {
        [xml]$modulePom = Get-Content -LiteralPath (Join-Path $Backend "$modulepom.xml") -Raw
        $uses[$module] = @($modulePom.SelectNodes("//*[local-name()='dependency']/*[local-name()='artifactId']") | ForEach-Object { $_.InnerText })
    }
    do {
        $added = $false
        foreach ($module in $known) {
            if ($modules.ContainsKey($module)) { continue }
            if (@($uses[$module] | Where-Object { $modules.ContainsKey($_) }).Count -gt 0) {
                $modules[$module] = $true
                $added = $true
            }
        }
    } while ($added)
    return [pscustomobject]@{ Modules = @($modules.Keys | Sort-Object); Frontend = $frontendChanged }
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

$plan = $null
if ($Quick) {
    $plan = Get-QuickPlan
    if ($null -eq $plan) { Write-Host 'Quick build: not possible for these changes, running the full build.' }
}

if ($null -eq $plan) {
    Push-Location $Backend
    try {
        Invoke-Step 'Backend Maven reactor build' { & mvn clean verify }
    } finally {
        Pop-Location
    }
} elseif ($plan.Modules.Count -gt 0) {
    Push-Location $Backend
    try {
        $list = $plan.Modules -join ','
        Invoke-Step "Backend tests: $list and the modules they need" { & mvn -T 1C -pl $list -am test }
    } finally {
        Pop-Location
    }
} else {
    Write-Host 'Quick build: no backend module changed.'
}

if ($null -ne $plan -and -not $plan.Frontend) {
    Write-Host 'Quick build: the frontend did not change.'
    Write-Host ""
    Write-Host "QUICK BUILD SUCCESS (changed modules only, no jar; run the full build before a release)"
    return
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
if ($null -ne $plan) {
    Write-Host "QUICK BUILD SUCCESS (changed modules only, no jar; run the full build before a release)"
    return
}
Write-Host "BUILD SUCCESS"
Write-Host "Backend jar: backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar"
Write-Host "Frontend dist: frontend\sdv-ui\dist"
