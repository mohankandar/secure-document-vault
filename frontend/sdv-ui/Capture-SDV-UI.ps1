param(
  [string]$BaseUrl = "http://localhost:5183",
  [string]$ContractNumber = $(if ($env:SDV_DEMO_CONTRACT) { $env:SDV_DEMO_CONTRACT } else { "300000000076" }),
  [switch]$Headed
)

$ErrorActionPreference = "Stop"
$env:SDV_UI_BASE_URL = $BaseUrl
$env:SDV_DEMO_CONTRACT = $ContractNumber

Write-Host "SDV UI capture" -ForegroundColor Cyan
Write-Host "Base URL: $BaseUrl"
Write-Host "Demo contract: $ContractNumber"

if (-not (Test-Path "node_modules/@playwright/test")) {
  Write-Host "Installing Playwright test tooling (temporary, no-save)..." -ForegroundColor Yellow
  npm install --no-save --package-lock=false @playwright/test@1.55.1
}

Write-Host "Ensuring Chromium and WebKit are installed..." -ForegroundColor Yellow
npx playwright install chromium webkit

if ($Headed) {
  npm run ui:capture:headed
} else {
  npm run ui:capture
}

$bundle = Join-Path (Get-Location) "sdv-ui-capture.zip"
if (Test-Path $bundle) { Remove-Item $bundle -Force }

$items = @()
if (Test-Path "test-results") { $items += "test-results" }
if (Test-Path "playwright-report") { $items += "playwright-report" }

if ($items.Count -gt 0) {
  Compress-Archive -Path $items -DestinationPath $bundle -Force
}

Write-Host ""
Write-Host "Capture complete." -ForegroundColor Green
Write-Host "Screenshots/traces: frontend/sdv-ui/test-results"
Write-Host "HTML report: frontend/sdv-ui/playwright-report/index.html"
if (Test-Path $bundle) {
  Write-Host "Share this single file with ChatGPT: $bundle" -ForegroundColor Cyan
}
Write-Host "Run 'npm run ui:report' to open the report locally."
