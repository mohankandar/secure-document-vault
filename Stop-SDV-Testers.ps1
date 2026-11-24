<#
.SYNOPSIS
  Stops the tester instance (task 0089): the tunnel first, then the proxy and Vero. Containers and data stay.
#>
[CmdletBinding()]
param(
    [string]$Settings = 'C:\dev\sdv\testers\testers.settings.psd1',
    [switch]$TunnelOnly
)

$ErrorActionPreference = 'Stop'
$Run = Join-Path (Split-Path -Parent ([IO.Path]::GetFullPath($Settings))) 'run'
$failed = $false
# What each process must look like, so that a reused process number never stops something else.
$parts = @(
    @{ Name = 'cloudflared'; Program = 'cloudflared.exe'; Text = 'cloudflared.yml' },
    @{ Name = 'caddy'; Program = 'caddy.exe'; Text = 'dev\testers\Caddyfile' },
    @{ Name = 'vero'; Program = 'java.exe'; Text = 'sdv-web-' }
)
foreach ($part in $parts) {
    if ($TunnelOnly -and $part.Name -ne 'cloudflared') { continue }
    $file = Join-Path $Run "$($part.Name).pid"
    if (-not (Test-Path -LiteralPath $file)) { Write-Host "$($part.Name): not started here."; continue }
    $id = 0
    if ([int]::TryParse([string](Get-Content -LiteralPath $file -TotalCount 1), [ref]$id)) {
        $p = Get-CimInstance Win32_Process -Filter "ProcessId = $id" -ErrorAction SilentlyContinue
        if ($p -and $p.Name -eq $part.Program -and ([string]$p.CommandLine).Contains($part.Text)) {
            Stop-Process -Id $id -Force
            Write-Host "$($part.Name): stopped."
        } else { Write-Host "$($part.Name): not running." }
    }
    Remove-Item -LiteralPath $file -Force
}
if (Get-Process -Name cloudflared -ErrorAction SilentlyContinue) {
    Write-Warning 'A cloudflared is still running on this computer. This script did not start it; stop it by hand.'
    $failed = $true
}
if ($failed) { exit 1 }
