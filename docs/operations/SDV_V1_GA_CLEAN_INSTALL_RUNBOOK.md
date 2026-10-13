# SDV V1.0.0 GA Clean-Install Runbook

## Purpose

This runbook validates that the exact frozen SDV V1.0.0 source distribution can be installed from scratch on a fresh Windows machine or VM.

This is **not another feature regression**. Units 0–25 are already frozen. The goal is distribution/installability proof:

1. verify the GA archive checksum;
2. extract into a clean directory;
3. build from source using the included frontend dependency lock;
4. start SDV against a fresh PostgreSQL volume and local-development Vault;
5. confirm migrations through 010 apply cleanly;
6. run one minimal encrypted producer → business search → secure-viewer lifecycle;
7. restart the application and prove the same encrypted document is still viewable;
8. capture evidence and clean up all run-owned Docker/runtime state.

## Frozen baseline

- SDV version: **1.0.0**
- Units: **0–25 VERIFIED / FROZEN**
- Runtime feature baseline: **Units 0–24**
- Latest migration: **010**
- Unit 25: release gate only; no runtime feature/migration

## Fresh-machine prerequisites

Install before extracting SDV:

- Windows PowerShell 5.1 or newer PowerShell
- Java 21
- Maven
- Node.js + npm
- Docker Desktop / Docker Engine with `docker compose`

The official clean-install run should begin with:

- no existing SDV checkout in the target directory;
- no pre-existing `sdv-*` Docker containers;
- no SDV database volume from a prior run;
- no `target`, `node_modules`, `dist`, or `.runtime` copied into the extracted distribution.

## Files to copy to the clean machine

From the GA cut directory, copy all three:

- `sdv-v1-1.0.0-source.zip`
- `sdv-v1-1.0.0-source.zip.sha256`
- `sdv-v1-ga-manifest.json`

Do not copy a developer workspace instead of the release archive. The clean-install proof is against the packaged distribution.

## 1. Verify archive SHA-256 before extraction

In PowerShell, from the directory containing the three GA files:

```powershell
$archive = '.\sdv-v1-1.0.0-source.zip'
$expected = ((Get-Content "$archive.sha256" -Raw).Trim() -split '\s+')[0].ToLowerInvariant()
$actual = (Get-FileHash -Algorithm SHA256 $archive).Hash.ToLowerInvariant()
if ($actual -ne $expected) { throw "GA archive SHA mismatch. expected=$expected actual=$actual" }
$manifest = Get-Content '.\sdv-v1-ga-manifest.json' -Raw | ConvertFrom-Json
if ($manifest.sha256 -ne $actual) { throw 'GA manifest SHA does not match the archive.' }
if ($manifest.version -ne '1.0.0' -or $manifest.status -ne 'GENERAL AVAILABILITY / FROZEN') { throw 'Unexpected GA manifest identity.' }
Write-Host "PASS - GA archive verified: $actual" -ForegroundColor Green
```

Keep the resulting `$actual` value. Pass it into the clean-install script so the evidence JSON is tied to the source archive that was verified before extraction.

## 2. Extract into an empty directory

Example:

```powershell
New-Item -ItemType Directory -Force C:\sdv-v1.0.0 | Out-Null
Expand-Archive .\sdv-v1-1.0.0-source.zip C:\sdv-v1.0.0
Set-Location C:\sdv-v1.0.0
```

Do not copy `target`, `node_modules`, `dist`, or `.runtime` into this directory.

## 3. Run the official clean-install acceptance

```powershell
.\Test-SDV-V1-GA-CleanInstall.ps1 -ExpectedSourceArchiveSha256 '<paste verified SHA-256>'
```

Do **not** use `-SkipBuild` for official evidence.

Do **not** use `-AllowExistingSdvContainers` for official evidence. That switch is provided only for a rehearsal on a developer machine and changes the result to `REHEARSAL PASS`.

Expected final result:

```text
PASS - SDV V1.0.0 GA clean-install acceptance passed.
```

## What the acceptance proves

### Clean distribution

- required GA files are present;
- baseline marker says version 1.0.0 / Units 0–25 / migration 010;
- no pre-existing generated build output exists before the official build;
- the frontend package-lock fingerprint is captured and remains unchanged by `npm ci`.

### Build

- Java 21 / Maven backend verify succeeds;
- frontend dependency installation uses the frozen lock;
- frontend production build succeeds.

### First start

- fresh PostgreSQL volume is created for the run;
- local-only Vault starts for document crypto;
- backend and frontend become healthy/reachable;
- migration 010 is applied exactly once;
- no migration beyond 010 is present.

### Minimal encrypted business lifecycle

- Tenant IT creates a storage volume and producer source;
- business metadata fields are configured;
- the standalone HTTP producer submits a PDF;
- create/document retry remains idempotent;
- business search finds the record;
- secure viewer decrypts/renders the document.

### Restart persistence smoke

The script stops/restarts the SDV application while preserving its PostgreSQL/Vault containers for the local smoke. It then proves:

- the same document remains viewable;
- the page still decrypts/renders;
- the committed ciphertext integrity fingerprint is unchanged.

This is an application-restart install smoke, not a replacement for the already-frozen Unit 22 Vault/DB/storage disaster-recovery validation.

## Local Vault warning

`Start-SDV-Local.ps1 -WithVault` uses a **development-only Vault dev server**. It is intentionally not a production key-durability configuration.

Production key durability/recovery remains governed by the frozen Unit 22 backup/restore/HA runbook. Do not use the clean-install local Vault behavior as production key policy.

## Evidence

On success the script writes:

`.runtime\ga-clean-install-evidence\<run>\SDV-V1-GA-CLEAN-INSTALL-EVIDENCE.json`

Preserve this JSON together with:

- the GA archive;
- SHA-256 sidecar;
- GA manifest;
- V1 frozen release baseline document.

## After clean-install PASS

Once the clean-machine acceptance passes:

- mark the specific `sdv-v1-1.0.0-source.zip` SHA as **GA CLEAN-INSTALL VERIFIED**;
- retain the evidence JSON with release evidence;
- use that exact archive/hash for pilot deployment;
- do not rebuild a different archive without repeating checksum + clean-install validation.
