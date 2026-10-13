\
param(
    [switch]$SkipBackend
)

$ErrorActionPreference = "Stop"

function Invoke-Step {
    param(
        [string]$Name,
        [scriptblock]$Command
    )
    Write-Host ""
    Write-Host "=== $Name ===" -ForegroundColor Cyan
    & $Command
    if ($LASTEXITCODE -ne 0) {
        throw "$Name failed with exit code $LASTEXITCODE."
    }
}

$repo = Split-Path -Parent $MyInvocation.MyCommand.Path
$frontend = Join-Path $repo "frontend\sdv-ui"
$backend = Join-Path $repo "backend"

if (!(Test-Path $frontend)) {
    throw "Frontend not found at $frontend"
}

Push-Location $frontend
try {
    Invoke-Step "FRONTEND - npm install state" { npm ls --depth=0 }
    Invoke-Step "FRONTEND - production build" { npm run build }

    # Run repository-provided UI/auth checks when present. Do not invent test names.
    $package = Get-Content ".\package.json" -Raw | ConvertFrom-Json
    $scripts = $package.scripts.PSObject.Properties.Name

    foreach ($candidate in @("test:ui-framework", "test:auth", "test")) {
        if ($scripts -contains $candidate) {
            Invoke-Step "FRONTEND - npm run $candidate" { npm run $candidate }
        }
    }
}
finally {
    Pop-Location
}

if (!$SkipBackend -and (Test-Path (Join-Path $backend "pom.xml"))) {
    Push-Location $backend
    try {
        Invoke-Step "BACKEND - Maven tests" { mvn test }
    }
    finally {
        Pop-Location
    }
}

Write-Host ""
Write-Host "R1D automated validation completed successfully." -ForegroundColor Green
Write-Host "Manual responsive/accessibility/state checks are still required; see SDV_UI_R1D_ACCEPTANCE.md."
