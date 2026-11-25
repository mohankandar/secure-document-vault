# Task 0093: what New-SDV-ReleaseLicenceKey.ps1 and New-SDV-Licence.ps1 share. Dot-sourced by them; does nothing by itself.

function Refuse([string]$Why) { Write-Host "REFUSED: $Why" -ForegroundColor Red; Write-Host 'Nothing was changed.'; exit 1 }

# A full path to a folder that is there, is no link, and is outside every repository and synchronised folder.
function Assert-SafeFolder([string]$Folder, [string]$What) {
    if (-not [IO.Path]::IsPathRooted($Folder) -or $Folder -notmatch '^[A-Za-z]:[\\/]') { Refuse "$What must be a full path on this computer, such as D:\keys." }
    $full = [IO.Path]::GetFullPath($Folder)
    if (-not (Test-Path -LiteralPath $full -PathType Container)) { Refuse "The folder $full isn't there. Make it first." }
    if ((New-Object IO.DriveInfo ([IO.Path]::GetPathRoot($full))).DriveType -eq 'Network') { Refuse "$What must not be on a network drive." }
    foreach ($sync in @($env:OneDrive, $env:OneDriveCommercial, $env:OneDriveConsumer)) {
        if ($sync -and ($full.TrimEnd('\') + '\').StartsWith($sync.TrimEnd('\') + '\', [StringComparison]::OrdinalIgnoreCase)) { Refuse "$What must not be in a folder that is copied to the cloud ($sync)." }
    }
    for ($d = New-Object IO.DirectoryInfo $full; $null -ne $d; $d = $d.Parent) {
        if ($null -ne $d.Parent -and ($d.Attributes -band [IO.FileAttributes]::ReparsePoint)) { Refuse "$What goes through a link ($($d.FullName)). Give the real folder." }
        if (Test-Path -LiteralPath (Join-Path $d.FullName '.git')) { Refuse "$What is inside the repository $($d.FullName)." }
    }
    $full
}

# Runs the Java tool with the platform-licensing version that backend/pom.xml names. Returns its exit code.
function Invoke-LicenceTool([string]$Root, [string[]]$ToolArguments) {
    if (-not (Get-Command java -ErrorAction SilentlyContinue)) { Refuse 'Java 21 is needed, and java was not found.' }
    [xml]$pom = Get-Content -LiteralPath (Join-Path $Root 'backend\pom.xml') -Raw
    $version = $pom.project.properties.'platform.core.version'
    $repository = Join-Path $env:USERPROFILE '.m2\repository\com\documentplatform\platform'
    $jars = foreach ($module in 'platform-licensing', 'platform-errors') {
        $jar = Join-Path $repository "$module\$version\$module-$version.jar"
        if (-not (Test-Path -LiteralPath $jar)) { Refuse "$module $version isn't on this computer yet. Build Vero once (Build-SDV-All.ps1)." }
        $jar
    }
    # cmd.exe would not be needed, but Windows PowerShell 5.1 makes an error of a program's error output otherwise.
    $before = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    try { & java -cp ($jars -join ';') (Join-Path $Root 'tools\licence\VeroLicenceTool.java') @ToolArguments 2>&1 | ForEach-Object { Write-Host "$_" } }
    finally { $ErrorActionPreference = $before }
    $LASTEXITCODE
}
