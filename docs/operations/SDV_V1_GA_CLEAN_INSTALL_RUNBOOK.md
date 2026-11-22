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

## Update the database

From task 0013 on, **starting Vero never changes the database**. The customer's database team applies Vero's
database changes themselves with the **schema step**, using their own database owner account. Vero's team never
needs that password. Only local mode on a developer computer (`start-local.cmd`) still updates the database at
start-up.

When the database doesn't match the Vero version, Vero starts but serves only its health and system information.
The startup log and Platform operations › Health say one of:

- "The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database")."
- "The database is newer than this Vero version. …" (an older Vero was started against a database that a newer one updated).

In that state Vero is up but **not ready**. Point load balancers and orchestrators at these addresses (no sign-in needed):

| Address | Database matches | Database doesn't match | Database can't be reached (at start or later) |
|---|---|---|---|
| `/actuator/health/readiness` | 200 `UP` | 503 `OUT_OF_SERVICE`: send no traffic | 503 `NOT_READY`: send no traffic |
| `/actuator/health/liveness` | 200 `UP` | 200 `UP`: don't restart it; it keeps showing why | 200 `UP`: don't restart it; it keeps trying |
| `/actuator/health` | 200 `UP` | 503 `OUT_OF_SERVICE` | 200 `UP` |

Vero checks the database version when it starts. After the schema step, start Vero again. If the database can't be
reached, Vero keeps trying and serves as soon as it answers, without a restart; requests get 503 `SDV-SYS-014`
meanwhile.

### Behind a proxy or load balancer: `SDV_TRUSTED_PROXIES`

Tenant IT's Problems page lists intake requests Vero couldn't link to a sending system, for example a failed
sign-in, with the caller's network address. By default that is the address Vero's server sees: behind a proxy or load
balancer, the proxy's.

| Setting | Default | Value |
|---|---|---|
| `SDV_TRUSTED_PROXIES` | empty: trust no proxy | The IP addresses of your proxies or load balancers as Vero sees them, separated by commas, for example `10.0.4.11,10.0.4.12` |

- Only a request that reaches Vero from one of these addresses has its `X-Forwarded-For` header read. The caller is
  the first address from the right that isn't one of your proxies. From any other address the header is ignored, so
  a caller can't give a false address.
- List IP addresses only. A host name, a range (`10.0.0.0/8`) or a port stops the start with `SDV-SYS-005`.
- Your proxy must set or append `X-Forwarded-For`; most do.
- The address is used for the Problems list only. It doesn't affect sign-in or access.

Vero sends no usage data anywhere. Liquibase's anonymous usage analytics are switched off in every mode (the
running app, local mode and the schema step).

### Two database users

| User | Used by | Rights |
|---|---|---|
| Owner, for example `vero_owner` | The schema step only | Owns the database (or at least schema `public`): creates and changes tables and indexes |
| App, for example `vero_app` | The running Vero (`SDV_DB_USERNAME`, `SDV_DB_PASSWORD`) | Connect, and `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the tables. No create, no alter |

Once, when the database is created (as a PostgreSQL superuser or the owner):

```sql
create role vero_owner login password '...';
create role vero_app login password '...';
create database vero owner vero_owner;
revoke all on database vero from public;
grant connect on database vero to vero_app;
\c vero
revoke create on schema public from public;
grant usage on schema public to vero_app;
-- Tables the owner creates later (first install and every upgrade) are readable and writable by the app user:
alter default privileges for role vero_owner in schema public
  grant select, insert, update, delete on tables to vero_app;
```

Vero has no sequences, functions or triggers of its own, so these table rights are all the app user needs.
`Test-SDV-CleanInstall.ps1` checks that Vero runs with such a user.

### The schema step

It is in the Vero application package: `Update-SDV-Schema.ps1` (Windows) or `update-sdv-schema.sh` (Linux), next
to the application jar (`sdv-web-<version>.jar`; use `-Jar` / `--jar` if it is elsewhere). It needs Java 21 and
runs the Liquibase bundled in the jar. It always uses the `production` context, so local test data can never be
added, and it can't be changed.

The connection comes from parameters or environment variables:

| | Windows | Linux | Environment variable |
|---|---|---|---|
| Address | `-Url jdbc:postgresql://db01:5432/vero` | `--url …` | `SDV_SCHEMA_DB_URL` |
| Owner user | `-Username vero_owner` | `--username …` | `SDV_SCHEMA_DB_USERNAME` |
| Password | typed when asked | typed when asked | `SDV_SCHEMA_DB_PASSWORD` |

The password is never a parameter, and Vero never stores any of these.

| Step | What it does |
|---|---|
| `status` | Lists the changesets that would run. Changes nothing. |
| `sql` | Writes the SQL that `update` would run to a file (`-Output` / `--output`), for teams that review and run SQL themselves. Changes nothing. It won't overwrite an existing file. |
| `update` | Applies the changesets, then checks that the database matches. |

Exit codes: 0 done, 2 wrong use (nothing was tried), 3 the database is newer than this version, 1 anything else.

### First install and every upgrade

1. **Stop Vero** (upgrades only).
2. **Status:** `.\Update-SDV-Schema.ps1 status -Url jdbc:postgresql://db01:5432/vero -Username vero_owner`
   (Linux: `./update-sdv-schema.sh status --url … --username vero_owner`). A first install lists every changeset.
3. **Review** (if your team reviews SQL first): `.\Update-SDV-Schema.ps1 sql -Output .\vero-<version>.sql`.
   Read it, then either run it yourselves as the owner, or go on to step 4. If you run it yourselves:
   - don't wrap the whole file in one transaction (for example, no `psql -1`): changeset 018 builds indexes with
     `CREATE INDEX CONCURRENTLY`, which can't run inside a transaction;
   - the file also records each changeset in `databasechangelog`; that table is how Vero checks the version.
4. **Update:** `.\Update-SDV-Schema.ps1 update`. It ends with "Done: N changesets applied. You can start Vero now."
5. **Start Vero** with the app user. The log says "Database schema check: the database matches this Vero version".
   Health shows no database notice.

Running `status` or `update` again is safe: when nothing is left it says "Up to date".

### Checking it on a test computer

`Test-SDV-CleanInstall.ps1` repeats the whole path against a scratch database `sdv_install_test` inside the local
`sdv-postgres` server (it never touches the `sdv` database and drops everything it creates): an empty database is
refused with the "older" message, `status`, `sql` and `update` run, Vero then starts as the read/write user with an
empty `tenants` table, and a further start applies nothing.
