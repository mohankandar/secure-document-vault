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
- Node.js 22 (22.12 or later), 24, or 26 and later, with npm (not Node 25)
- Supported browsers (customer requirement, for everyone who uses the SDV UI): Chrome or Edge 111 or later, Firefox 114 or later, Safari 16.4 or later. Older browsers are not supported.
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

- IT creates a storage volume and producer source;
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
needs that password. Task 0041: local mode on a developer computer uses the schema step too (it no longer updates the
database at start-up).

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

IT's Problems page lists intake requests Vero couldn't link to a sending system, for example a failed
sign-in, with the caller's network address. By default that is the address Vero's server sees: behind a proxy or load
balancer, the proxy's.

| Setting | Default | Value |
|---|---|---|
| `SDV_TRUSTED_PROXIES` | empty: trust no proxy | The IP addresses or address ranges of your proxies or load balancers as Vero sees them, separated by commas, for example `10.0.4.11,10.0.4.12` or `10.0.4.0/24, 2001:db8:4::/64, 192.0.2.10` |

- Only a request that reaches Vero from one of these addresses, or from an address inside one of these ranges, has
  its `X-Forwarded-For` header read. The caller is the first address from the right that isn't one of your proxies.
  From any other address the header is ignored, so a caller can't give a false address.
- Ranges are written in CIDR form, IPv4 (`10.0.4.0/24`) or IPv6 (`2001:db8:4::/64`), and can be mixed with single
  addresses. Use a range when your load balancer's addresses change within a known block.
- These stop the start with `SDV-SYS-005`, and the log names the entry:
  - a host name, a port, or an IPv6 address in brackets;
  - a prefix larger than the address allows (`/33` for IPv4, `/129` for IPv6), or not a plain number;
  - a prefix of `0` (`0.0.0.0/0`, `::/0`): that would trust every caller;
  - a range whose address isn't the range's first address (`10.0.4.5/24`; write `10.0.4.0/24`);
  - an IPv4 address written in IPv6 form with a prefix (`::ffff:10.0.4.0/120`; write the IPv4 range).
- Your proxy must set or append `X-Forwarded-For`; most do.
- The address is used for the Problems list only. It doesn't affect sign-in or access.

### How unidentified attempts are counted

Vero keeps the Problems list of unidentified intake attempts small, however many requests arrive:

- **One row per minute.** Requests with the same tenant (when a verified sign-in named one), caller address, method,
  address called, status and code in the same minute (UTC) are one row, with a count ("1,000 in a minute") and the
  first and last time. Rows are kept 30 days.
- **Addresses with IDs blanked.** Parts of the address called that aren't Vero's own words (`submissions`,
  `documents`, `retry-finalization`) are shown as `{id}`, at most 6 parts. So made-up addresses don't make a row each.
- **Unknown methods are `OTHER`.** A request method that isn't one of HTTP's standard ones is recorded as `OTHER`.
- **"(many addresses)".** Once a minute has as many rows as the limit below, requests from new caller addresses in
  that minute are counted in one row per key with the caller address `(many addresses)`. Many distinct callers (an
  IPv6 range, for example) can't add a row each.

| Setting | Default | Value |
|---|---|---|
| `SDV_UNIDENTIFIED_ATTEMPTS_MAX_ROWS_PER_MINUTE` | `100` | Rows a minute before new caller addresses share the "(many addresses)" row. A whole number from 1 to 10000; anything else stops the start with `SDV-SYS-005` |

Each request's reference is in Vero's log, also when it is counted in a row with others.

### Where documents may be kept: `SDV_STORAGE_ALLOWED_ROOTS`

An IT Admin adds storage volumes only inside the folders named here (task 0062).

| Setting | Default | Value |
|---|---|---|
| `SDV_STORAGE_ALLOWED_ROOTS` | empty: only `data` under Vero's working folder | Folders of this computer, separated by `;`. A network share or a device path stops the start with `SDV-SYS-005` |

- Set it **before an upgrade** if new volumes will be outside the default. Volumes that exist stay usable.
- Links inside an allowed folder are refused. Vero's service account needs rights only to these folders.

### How large a request may be

| Setting | Default | Value |
|---|---|---|
| `SDV_MAX_REQUEST_BODY_BYTES` | `1048576` (1 MB) | Any request except a submission and a document upload |
| `SDV_MAX_SUBMISSION_BODY_BYTES` | `8388608` (8 MB) | A submission with its list of documents |

Both from 1024 to 104857600; anything else stops the start with `SDV-SYS-005`. Over the limit: `413 SDV-SYS-019`. A
document's own limit is set for each sending system.

### When Vero is busy: `SDV_BUSY_RETRY_AFTER_SECONDS`

When no database connection is free for a request, Vero answers `503 SDV-SYS-018` ("busy") with a `Retry-After`
header: the number of seconds the caller should wait before sending the same request again. Sending systems do this by
themselves; nothing is lost.

| Setting | Default | Value |
|---|---|---|
| `SDV_BUSY_RETRY_AFTER_SECONDS` | `5` | The seconds in `Retry-After`. A whole number from 1 to 300; anything else stops the start with `SDV-SYS-005` |

- It changes only what Vero tells the caller. How long Vero itself waits for a free connection (the pool's 30 seconds)
  is not changed by it.
- Each busy answer writes one WARN line (not ERROR) with the code and the reference: busy is expected under load.
- The Problems page shows how many busy answers Vero gave in the last hour and since it started, when there was at
  least one in the last hour. If it keeps happening, raise `SDV_DB_POOL_MAX` (10 by default) within the database's own
  connection limit, or spread the senders' load. The count is for this Vero process and starts again at a restart.

Vero sends no usage data anywhere. Liquibase's anonymous usage analytics are switched off in every mode (the
running app, local mode and the schema step).

### Two database users

| User | Used by | Rights |
|---|---|---|
| Owner, for example `vero_owner` | The schema step only | Owns the database (or at least schema `public`): creates and changes tables and indexes |
| App, for example `vero_app` | The running Vero (`SDV_DB_USERNAME`, `SDV_DB_PASSWORD`) | Connect, and `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the tables, except the audit trail (`audit_events`) and the audit report register (`audit_reports`): `SELECT` and `INSERT` only. No create, no alter |

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

Vero has no sequences of its own, so these table rights are all the app user needs. Since changeset 037 (task 0050) the
audit trail is append-only: one function (`sdv_refuse_append_only_change`) and its triggers refuse `UPDATE`, `DELETE`
and `TRUNCATE` on `audit_events` and `audit_reports` for everyone, the owner included, and the schema step takes those
rights back from every other user (the default privileges above give them to new tables). Do not grant them again;
the triggers refuse them anyway.
`Test-SDV-CleanInstall.ps1` checks that Vero runs with such a user.

### The schema step

It is in the Vero application package: `Update-SDV-Schema.ps1` (Windows) or `update-sdv-schema.sh` (Linux), next
to the application jar (`sdv-web-<version>.jar`; use `-Jar` / `--jar` if it is elsewhere). It needs Java 21 and
runs the Liquibase bundled in the jar, then, in their own runs with their own history tables, platform-core's
identity tables (`db/platform/identity`, history `idn_schema_history`) and licensing tables (`db/platform/licensing`,
history `lic_schema_history`; licensing is not turned on). It always uses the `production` context, so no test data can be
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
   - the file starts with platform-core's identity and licensing SQL. Vero checks their versions in
     `idn_schema_version` and `lic_schema_version`; when a later release has a platform run to do, `update` starts
     its history (`idn_schema_history`, `lic_schema_history`) at the version the tables are at.
4. **Update:** `.\Update-SDV-Schema.ps1 update`. It ends with "Done: N changesets applied. You can start Vero now."
5. **Start Vero** with the app user. The log says "Database schema check: the database matches this Vero version".
   Health shows no database notice.

Running `status` or `update` again is safe: when nothing is left it says "Up to date".

Then load sign-in, entities and sending systems with the setup command: `docs/install/SETUP.md`. The role mapping
file there also grants the Platform Operations Admin, once for the whole customer, on a row with `*` as its entity
(platform-level grant, task 0057); the separation rules hold for it (never with Restricted Steward).

Task 0057 (platform-core 0.6.1): this release's identity tables are at version 4 (`idn_platform_grant`). On a
database whose identity tables are at version 3, `status` lists `identity V4 (platform-core)` and `update` applies it
in the identity run; Vero refuses to start until then (`IDN_VERSION_OLDER`).

### Checking it on a test computer

`Test-SDV-CleanInstall.ps1` repeats the whole path against a scratch database `sdv_install_test` inside the local
`sdv-postgres` server (it never touches the `sdv` database and drops everything it creates): an empty database is
refused with the "older" message, `status`, `sql` and `update` run, Vero then starts as the read/write user with an
empty `tenants` table, and a further start applies nothing.
