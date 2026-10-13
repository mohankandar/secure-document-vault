[CmdletBinding()]
param([switch]$SkipBuild)

$ErrorActionPreference = 'Stop'
Set-StrictMode -Version Latest

$Root = $PSScriptRoot
$Suffix = ([guid]::NewGuid().ToString('N')).Substring(0,10)
$Runtime = Join-Path $Root ".runtime\release-readiness\$Suffix"
$Logs = Join-Path $Runtime 'logs'
$PackageEvidence = Join-Path $Runtime 'package'
$ReleaseDbVolume = "sdv-u25-$Suffix-postgres"
$PreviousPostgresVolume = $env:SDV_POSTGRES_VOLUME
$ReleaseDbVolumeActive = $false
New-Item -ItemType Directory -Force -Path $Runtime,$Logs,$PackageEvidence | Out-Null

function Ok([string]$Message) { Write-Host "  [OK  ] $Message" -ForegroundColor Green }
function Require([bool]$Condition,[string]$Message) { if(-not $Condition){ throw $Message }; Ok $Message }
function Restore-PostgresVolumeEnvironment {
    if ($null -eq $PreviousPostgresVolume) {
        Remove-Item Env:SDV_POSTGRES_VOLUME -ErrorAction SilentlyContinue
    } else {
        $env:SDV_POSTGRES_VOLUME = $PreviousPostgresVolume
    }
}
function Remove-ReleaseDbVolume {
    if (-not $ReleaseDbVolumeActive) { return }
    try { & (Join-Path $Root 'Stop-SDV-Local.ps1') | Out-Null } catch { }
    try { & docker rm -f sdv-postgres *> $null } catch { }
    try { & docker rm -f sdv-vault *> $null } catch { }
    $existing = @(& docker volume ls --format '{{.Name}}' | Where-Object { $_ -eq $ReleaseDbVolume })
    if ($existing.Count -gt 0) {
        & docker volume rm -f $ReleaseDbVolume *> $null
        if ($LASTEXITCODE -ne 0) { throw "Could not remove disposable Unit 25 PostgreSQL volume $ReleaseDbVolume." }
    }
    $script:ReleaseDbVolumeActive = $false
    Ok "Disposable Unit 25 PostgreSQL regression volume removed ($ReleaseDbVolume)"
}

function Run-FrozenAcceptance([string]$Name,[string]$ScriptName) {
    $path = Join-Path $Root $ScriptName
    Require (Test-Path -LiteralPath $path -PathType Leaf) "$Name acceptance script is present"
    $log = Join-Path $Logs ($ScriptName.Replace('.ps1','.log'))
    Write-Host ''
    Write-Host "--- REGRESSION: $Name ---"
    $watch = [Diagnostics.Stopwatch]::StartNew()
    $exitCode = 999
    $oldPreference = $ErrorActionPreference
    try {
        $ErrorActionPreference = 'Continue'
        & powershell.exe -NoProfile -ExecutionPolicy Bypass -File $path -SkipBuild 2>&1 |
            Tee-Object -FilePath $log |
            ForEach-Object { Write-Host $_ }
        $exitCode = $LASTEXITCODE
    } finally {
        $ErrorActionPreference = $oldPreference
        $watch.Stop()
    }
    if ($exitCode -ne 0) {
        throw "$Name regression failed with exit code $exitCode. Evidence: $log"
    }
    Ok "$Name regression PASS ($([math]::Round($watch.Elapsed.TotalSeconds,1))s)"
    return [PSCustomObject]@{ name=$Name; script=$ScriptName; status='PASS'; seconds=[math]::Round($watch.Elapsed.TotalSeconds,1); log=[IO.Path]::GetFileName($log) }
}

$results = New-Object System.Collections.Generic.List[object]
$InitialSdvContainers = @()

try {
    Write-Host '=== PHASE 1 - Release preflight + frozen baseline ==='

    $required = @(
        'README.md',
        'compose.local.yml',
        'backend\pom.xml',
        'frontend\sdv-ui\package.json',
        'backend\sdv-web\src\main\resources\db\changelog\db.changelog-master.yaml',
        'backend\sdv-web\src\main\resources\db\changelog\010-performance-resilience-hardening.yaml',
        'docs\api\sdv-ingestion-v1.openapi.json',
        'docs\api\schemas\sdv-ingestion-submission-v1.schema.json',
        'docs\operations\SDV_BACKUP_RESTORE_HA_RUNBOOK.md',
        'docs\operations\SDV_V1_RELEASE_READINESS_RUNBOOK.md',
        'docs\development\SDV_WORKING_AGREEMENT.md',
        'docs\development\V1_ACCEPTANCE_RELEASE_BASELINE_UNIT25.md',
        'New-SDV-V1-ReleasePackage.ps1'
    )
    foreach ($relative in $required) {
        Require (Test-Path -LiteralPath (Join-Path $Root $relative)) "Required V1 release source exists: $relative"
    }

    Require ($null -ne (Get-Command docker -ErrorAction SilentlyContinue)) 'Docker CLI is available for frozen runtime regression'
    $null = & cmd.exe /d /c 'docker info 1>nul 2>&1'
    Require ($LASTEXITCODE -eq 0) 'Docker engine is reachable'
    $InitialSdvContainers = @(& docker ps -a --format '{{.Names}}' | Where-Object { $_ -match '^sdv-' })
    if ($InitialSdvContainers.Count -gt 0) {
        Write-Host "  [INFO] Pre-existing SDV-named Docker containers will be preserved and excluded from the final leak assertion: $($InitialSdvContainers -join ', ')"
    }
    $composeText = Get-Content -LiteralPath (Join-Path $Root 'compose.local.yml') -Raw
    Require ($composeText -match '\$\{SDV_POSTGRES_VOLUME:-sdv-postgres-data\}') 'Local compose supports a disposable PostgreSQL volume override for non-destructive release regression'

    $master = Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-web\src\main\resources\db\changelog\db.changelog-master.yaml') -Raw
    Require (($master | Select-String -Pattern '010-performance-resilience-hardening.yaml' -AllMatches).Matches.Count -eq 1) 'Liquibase master includes migration 010 exactly once'
    $futureMigration = @(Get-ChildItem -LiteralPath (Join-Path $Root 'backend\sdv-web\src\main\resources\db\changelog') -File |
        Where-Object { $_.Name -match '^(011|01[2-9]|0[2-9][0-9]|[1-9][0-9][0-9])-' })
    Require ($futureMigration.Count -eq 0) 'No unreviewed migration beyond frozen 010 is present'

    $openApi = Get-Content -LiteralPath (Join-Path $Root 'docs\api\sdv-ingestion-v1.openapi.json') -Raw | ConvertFrom-Json
    Require ([string]$openApi.openapi -eq '3.1.0') 'Frozen public OpenAPI contract parses as 3.1.0'
    $null = Get-Content -LiteralPath (Join-Path $Root 'docs\api\schemas\sdv-ingestion-submission-v1.schema.json') -Raw | ConvertFrom-Json
    Ok 'Frozen producer JSON Schema remains machine-readable'

    $runtimeFiles = @(
        Get-ChildItem -LiteralPath (Join-Path $Root 'backend') -Recurse -File | Where-Object { $_.Extension -in @('.java','.yaml','.yml','.properties','.xml') }
        Get-ChildItem -LiteralPath (Join-Path $Root 'frontend\sdv-ui\src') -Recurse -File | Where-Object { $_.Extension -in @('.ts','.tsx') }
    )
    $runtimeText = ($runtimeFiles | ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw }) -join "`n"
    Require ($runtimeText -notmatch '(?i)lexavault') 'Frozen runtime source remains independent from LexaVault'

    # Unit 21 FIX1 is a frozen authentication-mode isolation invariant. The local
    # header filter must not exist as a bean in enterprise-jwt/disabled modes;
    # otherwise servlet auto-registration can inspect spoofed X-SDV-* headers
    # before bearer authentication. Guard the release source against regression.
    $localAuthFilterText = Get-Content -LiteralPath (Join-Path $Root 'backend\sdv-security\src\main\java\com\sdv\platform\security\auth\LocalHeaderAuthenticationFilter.java') -Raw
    Require ($localAuthFilterText -match '@Profile\("local"\)') 'Frozen Unit 21 local-header adapter remains restricted to the local Spring profile'
    Require ($localAuthFilterText -match '@ConditionalOnProperty\(prefix\s*=\s*"sdv\.auth",\s*name\s*=\s*"mode",\s*havingValue\s*=\s*"local-header"\)') 'Frozen Unit 21 FIX1 keeps local-header filter bean absent from enterprise-jwt/disabled modes'

    $sourceFiles = Get-ChildItem -LiteralPath $Root -Recurse -File |
        Where-Object {
            $_.Extension -in @('.java','.ts','.tsx','.ps1','.sql','.yaml','.yml','.md','.json') -and
            $_.FullName -notmatch '\\(target|node_modules|dist|\.runtime)\\'
        }
    $conflictFiles = @($sourceFiles | Select-String -Pattern '^(<<<<<<< |>>>>>>> )' -List)
    Require ($conflictFiles.Count -eq 0) 'No unresolved merge-conflict markers are present in release source'

    if (-not $SkipBuild) {
        Write-Host ''
        Write-Host '=== BUILD - Authoritative V1 clean build ==='
        & (Join-Path $Root 'build-all.cmd')
        if ($LASTEXITCODE -ne 0) { throw 'V1 clean build failed.' }
        Ok 'Backend Maven verify + frontend production build passed'
    } else {
        Require (Test-Path -LiteralPath (Join-Path $Root 'backend\sdv-web\target\sdv-web-0.1.0-SNAPSHOT.jar')) 'SkipBuild requested and current backend JAR exists'
        Require (Test-Path -LiteralPath (Join-Path $Root 'frontend\sdv-ui\dist\index.html')) 'SkipBuild requested and current frontend production build exists'
    }

    Require (Test-Path -LiteralPath (Join-Path $Root 'frontend\sdv-ui\package-lock.json')) 'Frontend dependency lock exists for reproducible V1 packaging'

    Write-Host ''
    Write-Host '=== PHASE 2 - Full frozen V1 regression ==='
    # The frozen checkpoint scripts intentionally use stable local acceptance tenants.
    # Run the release regression on a fresh named PostgreSQL volume so historical
    # developer data cannot influence placement/count assertions. The normal
    # sdv-postgres-data volume is never removed or modified by Unit 25.
    $existingReleaseVolume = @(& docker volume ls --format '{{.Name}}' | Where-Object { $_ -eq $ReleaseDbVolume })
    if ($existingReleaseVolume.Count -gt 0) {
        & docker volume rm -f $ReleaseDbVolume *> $null
        if ($LASTEXITCODE -ne 0) { throw "Could not clear stale disposable Unit 25 PostgreSQL volume $ReleaseDbVolume." }
    }
    $env:SDV_POSTGRES_VOLUME = $ReleaseDbVolume
    $ReleaseDbVolumeActive = $true
    Ok "Full frozen regression is isolated on disposable PostgreSQL volume $ReleaseDbVolume"

    $suite = @(
        @{ name='Foundation Units 0-4'; script='Test-SDV-Foundation.ps1' },
        @{ name='Secure Ingestion Checkpoint A'; script='Test-SDV-SecureIngestion-A.ps1' },
        @{ name='Secure Ingestion Checkpoint B'; script='Test-SDV-SecureIngestion-B.ps1' },
        @{ name='Business Experience Checkpoint A'; script='Test-SDV-BusinessExperience-A.ps1' },
        @{ name='Business Experience Checkpoint B'; script='Test-SDV-BusinessExperience-B.ps1' },
        @{ name='Controlled Actions Unit 16'; script='Test-SDV-ControlledActions-A-Unit16.ps1' },
        @{ name='Controlled Actions Units 17-18'; script='Test-SDV-ControlledActions-B-Units17-18.ps1' },
        @{ name='Enterprise Readiness Units 19-20'; script='Test-SDV-EnterpriseReadiness-A-Units19-20.ps1' },
        @{ name='Enterprise Readiness Unit 21'; script='Test-SDV-EnterpriseReadiness-B-Unit21.ps1' },
        @{ name='Enterprise Readiness Unit 22'; script='Test-SDV-EnterpriseReadiness-C-Unit22.ps1' },
        @{ name='Enterprise Readiness Unit 23'; script='Test-SDV-EnterpriseReadiness-D-Unit23.ps1' },
        @{ name='Enterprise Readiness Unit 24'; script='Test-SDV-EnterpriseReadiness-E-Unit24.ps1' }
    )

    foreach ($entry in $suite) {
        $result = Run-FrozenAcceptance $entry.name $entry.script
        $results.Add($result)
    }
    Require ($results.Count -eq 12 -and @($results | Where-Object { $_.status -ne 'PASS' }).Count -eq 0) 'All 12 frozen V1 acceptance groups passed in one release-baseline run'
    Remove-ReleaseDbVolume
    Restore-PostgresVolumeEnvironment
    Require (-not (& docker volume ls --format '{{.Name}}' | Where-Object { $_ -eq $ReleaseDbVolume })) 'Disposable release-regression database volume is gone after the frozen suite'

    Write-Host ''
    Write-Host '=== PHASE 3 - Release packaging + exclusion policy ==='
    & (Join-Path $Root 'New-SDV-V1-ReleasePackage.ps1') -OutputDirectory $PackageEvidence -Version '1.0.0-rc'
    if ($LASTEXITCODE -ne 0) { throw 'Release source packaging failed.' }

    $archive = Get-ChildItem -LiteralPath $PackageEvidence -Filter 'sdv-v1-*-source.zip' -File | Select-Object -First 1
    Require ($null -ne $archive -and $archive.Length -gt 10000) 'Release source archive was created'
    $checksumFile = "$($archive.FullName).sha256"
    $releaseManifestPath = Join-Path $PackageEvidence 'sdv-v1-release-manifest.json'
    Require (Test-Path -LiteralPath $checksumFile) 'Release SHA-256 sidecar was created'
    Require (Test-Path -LiteralPath $releaseManifestPath) 'Release manifest was created'

    $manifest = Get-Content -LiteralPath $releaseManifestPath -Raw | ConvertFrom-Json
    $actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $archive.FullName).Hash.ToLowerInvariant()
    Require ([string]$manifest.sha256 -eq $actualHash) 'Release manifest SHA-256 matches the packaged archive'
    $checksumText = (Get-Content -LiteralPath $checksumFile -Raw).Trim()
    Require ($checksumText.StartsWith($actualHash)) 'Release checksum sidecar matches the packaged archive'
    Require ([string]$manifest.latestMigration -eq '010' -and [string]$manifest.frozenUnits -eq '0-24') 'Release manifest records the frozen Units 0-24 / migration 010 baseline'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [System.IO.Compression.ZipFile]::OpenRead($archive.FullName)
    try {
        $entries = @($zip.Entries | ForEach-Object { $_.FullName })
    } finally {
        $zip.Dispose()
    }
    $forbidden = @($entries | Where-Object {
        $_ -match '(^|/)(target|node_modules|dist|\.runtime|\.git|\.idea|\.vscode|release)(/|$)' -or
        $_ -match '(^|/)\.env($|\.)' -or
        $_ -match '\.(log|class|tsbuildinfo)$' -or
        $_ -match '\.(pem|key|p12|pfx|jks|keystore)$'
    })
    Require ($forbidden.Count -eq 0) 'Release archive excludes generated output, runtime state, IDE metadata, environment secrets and private-key/keystore material'

    $mustContain = @(
        'README.md',
        'backend/pom.xml',
        'frontend/sdv-ui/package.json',
        'frontend/sdv-ui/package-lock.json',
        'docs/api/sdv-ingestion-v1.openapi.json',
        'docs/operations/SDV_BACKUP_RESTORE_HA_RUNBOOK.md',
        'docs/operations/SDV_V1_RELEASE_READINESS_RUNBOOK.md',
        'Test-SDV-V1-Release-Readiness-Unit25.ps1'
    )
    foreach ($entry in $mustContain) {
        Require ($entries -contains $entry) "Release archive contains required source: $entry"
    }

    $extract = Join-Path $Runtime 'extracted-release'
    Expand-Archive -LiteralPath $archive.FullName -DestinationPath $extract -Force
    Require (Test-Path -LiteralPath (Join-Path $extract 'backend\pom.xml')) 'Release archive extracts successfully and retains backend source'
    Require (Test-Path -LiteralPath (Join-Path $extract 'frontend\sdv-ui\package-lock.json')) 'Extracted release retains reproducible frontend dependency lock'

    Write-Host ''
    Write-Host '=== PHASE 4 - Release evidence + clean shutdown ==='
    try { & (Join-Path $Root 'Stop-SDV-Local.ps1') | Out-Null } catch { }
    $currentSdvContainers = @(& docker ps -a --format '{{.Names}}' | Where-Object { $_ -match '^sdv-' })
    $newLeftovers = @($currentSdvContainers | Where-Object { $InitialSdvContainers -notcontains $_ })
    if ($newLeftovers.Count -gt 0) {
        throw "Release regression left new SDV Docker container(s) behind: $($newLeftovers -join ', ')"
    }
    if ($InitialSdvContainers.Count -gt 0) {
        Ok "Frozen regression leaves no new SDV Docker containers behind; pre-existing container(s) were preserved"
    } else {
        Ok 'Frozen regression leaves no SDV Docker containers behind'
    }

    $evidence = [ordered]@{
        product='Secure Document Vault'
        release='V1'
        checkpoint='Unit 25 - V1 Acceptance / Release Baseline'
        result='PASS'
        completedAt=[DateTimeOffset]::UtcNow.ToString('o')
        frozenUnits='0-24'
        latestMigration='010'
        acceptanceGroups=$results
        releaseArchive=$archive.Name
        releaseArchiveSha256=$actualHash
        packageManifest='sdv-v1-release-manifest.json'
        preExistingSdvContainers=$InitialSdvContainers
        remainingPreExistingSdvContainers=$currentSdvContainers
        newSdvContainerLeaks=$newLeftovers
    }
    $evidencePath = Join-Path $Runtime 'SDV-V1-RELEASE-EVIDENCE.json'
    $evidence | ConvertTo-Json -Depth 20 | Set-Content -LiteralPath $evidencePath -Encoding UTF8
    Require (Test-Path -LiteralPath $evidencePath) 'V1 release evidence JSON was written'

    Write-Host ''
    Write-Host '=== RESULT ==='
    Write-Host 'PASS - SDV V1 Acceptance / Release Baseline (Unit 25) passed.' -ForegroundColor Green
    Write-Host "Evidence: $Runtime"
    Write-Host "Release candidate package evidence: $PackageEvidence"
}
finally {
    try {
        if ($ReleaseDbVolumeActive) { Remove-ReleaseDbVolume }
        else { try { & (Join-Path $Root 'Stop-SDV-Local.ps1') } catch { } }
    } catch { Write-Warning "Unit 25 disposable database cleanup failed: $($_.Exception.Message)" }
    Restore-PostgresVolumeEnvironment
}
