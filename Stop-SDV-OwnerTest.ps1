<#
.SYNOPSIS
  Stops what Start-SDV-OwnerTest.ps1 started (task 0095). The containers and the data stay as they are.
#>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$state = Join-Path $PSScriptRoot '.runtime\owner-test\running.json'
if (-not (Test-Path -LiteralPath $state)) { Write-Host 'Nothing to stop.'; exit 0 }
$was = Get-Content -Raw -LiteralPath $state | ConvertFrom-Json
# A number is used again by Windows: only a process that is still the one that was started is stopped.
foreach ($one in @(@{ Name = 'screens'; Id = $was.frontend; Like = 'npm\s+run\s+dev' }, @{ Name = 'Vero'; Id = $was.backend; Like = 'sdv-web-0\.1\.0-SNAPSHOT-local\.jar' })) {
    $p = Get-CimInstance Win32_Process -Filter "ProcessId = $($one.Id)" -ErrorAction SilentlyContinue
    if (-not $p) { Write-Host "$($one.Name): not running"; continue }
    if ([string]$p.CommandLine -notmatch $one.Like) { Write-Host "$($one.Name): not running (process $($one.Id) is something else now and is left alone)"; continue }
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & taskkill.exe /PID $one.Id /T /F *> $null
    $ErrorActionPreference = $before
    Write-Host "$($one.Name): stopped"
}
Remove-Item -LiteralPath $state
Write-Host 'The database, Vault and Keycloak keep running, and the data is kept.'
exit 0
