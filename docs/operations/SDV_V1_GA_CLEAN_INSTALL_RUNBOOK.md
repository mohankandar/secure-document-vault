# SDV V1.0.0 GA Clean-Install Runbook

> **Task 0068, 2026-11-24: which part is current.** The sections up to "After clean-install PASS" describe the frozen V1.0.0
> archive (migration 010) and are kept as its record; its package and acceptance scripts do not run on this version. For an
> install or upgrade today, use "Update the database" and everything after it, with `docs/install/SETUP.md`.

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

When the database can't be reached at the start, or the platform's tables in it (identity) are missing or of another
version, Vero **stops** and its log says what is wrong and what to do (task 0074). Let the service manager start Vero
again (`Restart=always`, or the Windows service's recovery settings). While Vero runs, a database that goes away never stops it.

When only Vero's own tables don't match the Vero version, Vero starts but serves only its health and system information.
The startup log and Platform operations › Health say one of:

- "The database is older than this Vero version. Ask your database team to run the schema step (install guide, "Update the database")."
- "The database is newer than this Vero version. …" (an older Vero was started against a database that a newer one updated).

In that state Vero is up but **not ready**. Point load balancers and orchestrators at these addresses (no sign-in needed):

| Address | Database matches | Database doesn't match | Database can't be reached (while Vero runs) |
|---|---|---|---|
| `/actuator/health/readiness` | 200 `UP` | 503 `OUT_OF_SERVICE`: send no traffic | 503 `NOT_READY`: send no traffic |
| `/actuator/health/liveness` | 200 `UP` | 200 `UP`: don't restart it; it keeps showing why | 200 `UP`: don't restart it; it keeps trying |
| `/actuator/health` | 200 `UP` | 503 `OUT_OF_SERVICE` | 200 `UP` |

Vero checks the database version when it starts. After the schema step, start Vero again. While the database can't be
reached, requests get 503 `SDV-SYS-014`; Vero serves again as soon as it answers, without a restart.

### The checks

Platform operations, Health and IT, Problems list what Vero depends on: state, code, why, what to do. The same list is on
`/actuator/health/dependencies` with the sign-in of an IT Admin or Platform operations; anybody else sees no details. Use
only liveness and readiness for probes: the list is up to a minute old and never turns Vero away. Readiness' sign-in keys
are checked every 10 seconds.

| Setting | Default | |
|---|---|---|
| `platform.monitoring.every` | `60s` | how often a check runs |
| `platform.monitoring.startup-checks` | `true` | stop at the start while the database can't be used |
| `platform.monitoring.folders.<name>.path` | none | a folder to check too (an export or log folder) |

The **diagnostic bundle** (Health, Problems) is a zip for support: versions, a list of allowed settings with every secret
removed (never `sdv.*` or the environment), the checks, the last error codes, the table versions. Every download is in the audit.

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

### Reading PDFs: the `SDV_PDF_*` settings

Opening, drawing, searching, redacting and checking PDFs is limited, so one file or one person can't take the server.

| Setting | Default | Value |
|---|---|---|
| `SDV_PDF_MAX_CONCURRENT` | `0`: one for each 320 MB of memory, at most one per processor | At once in this process, 1 to 256 |
| `SDV_PDF_MAX_PER_PERSON` | `3` | At once for one person or sending system, 1 to 64 |
| `SDV_PDF_WAIT_SECONDS` | `8` | How long to wait for a place before `503 SDV-SYS-018`, 0 to 60 |
| `SDV_PDF_LIMIT_SECONDS` | `30` | The longest one request may take, 5 to 600 (a redaction: 10 minutes) |
| `SDV_PDF_SCRATCH_FOLDER` | the system's temporary folder | Working space; only ciphertext is written, with a key that is never stored |
| `SDV_PDF_SCRATCH_MEMORY_BYTES`, `SDV_PDF_SCRATCH_TOTAL_BYTES` | 16 MB, 2 GB | Per request: kept in memory; at most in all |
| `SDV_PDF_ACCEPT` | empty: refuse | What one sending system may send all the same: `source-key=encrypted,active-content,unreadable;other-key=...` |

Give Vero enough memory for the pages people read at once (about 320 MB each), and the temporary folder enough room.
`-XX:+ExitOnOutOfMemoryError` must **not** be set: a file that needs too much memory ends its own request only.

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

### Sign-in in the browser, and serving the screens

Vero's screens are the folder `dist` of the build. Your web server or proxy serves them, sends `/api/` to Vero, and does TLS.
A person signs in at your login system (authorization code with PKCE); the browser then holds the tokens in memory only, so
a reload or a new tab goes through the login system again, which usually asks nothing.

**In the login system**, for the browser client named in the sign-in file (`browserClientId`):

1. A **public** client: authorization code with PKCE (S256). No secret, no implicit flow.
2. Redirect address and sign-out address `https://<vero>/`, exactly; web origin (CORS) `https://<vero>`.
3. Access tokens for Vero's API audience, signed RS256, with the groups and name claims; life at most 2 hours, 15 to 60
   minutes advised. The API audience must not be the client's own ID. No `nonce` in access tokens.
4. Refresh tokens with rotation, if you want fewer trips through the login system.

Okta: add a Trusted Origin with CORS, and use a custom authorization server. Entra ID: a single-page application redirect;
set `SDV_AUTH_BROWSER_SCOPE` to your API's scope. Keycloak: Web origins, and an audience mapper. A login system at an
internal address needs `SDV_OUTBOUND_ALLOW_INTERNAL_ADDRESSES=true`, because Vero reads its discovery document.

**Headers the proxy sends** with the screens (put your login system's origin in):

```
Content-Security-Policy: default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; img-src 'self' blob: data:; font-src 'self' https://fonts.gstatic.com; connect-src 'self' https://<login system>; frame-src 'self' blob:; object-src 'none'; base-uri 'none'; form-action 'self'; frame-ancestors 'none'
Referrer-Policy: no-referrer
X-Content-Type-Options: nosniff
Permissions-Policy: camera=(), microphone=(), geolocation=()
```

The two font addresses may be left out: the screens then use fonts of the computer, and nothing is loaded from outside.

**Check it:** Test sign-in (IT, Sign-in) checks what Vero can see. It can't see whether the browser may call the token address:
sign in once; `SDV-AUT-015` after the login page means the web origin is missing there.

### All settings

Checked against `application.yml` and the code on 2026-11-24 (task 0068). Sign-in settings are in `docs/install/SETUP.md`
section 5; those with a section of their own above are not repeated.

| Setting | Default | What |
|---|---|---|
| `SDV_SERVER_PORT` | `8280` | Vero's port. Keep it private: the proxy does TLS |
| `SDV_DB_URL`, `SDV_DB_USERNAME`, `SDV_DB_PASSWORD` | none | The app user's connection (below) |
| `SDV_DB_POOL_MAX`, `SDV_DB_POOL_MIN` | `10`, `1` | Database connections of one Vero |
| `SDV_KEY_PROVIDER` | `disabled` | `vault`. A packaged start refuses `disabled` (`SDV-KEY-001`) |
| `SDV_VAULT_BASE_URL` | none | The key service's address, https outside a test computer |
| `SDV_VAULT_TOKEN_FILE` **or** `SDV_VAULT_TOKEN` | none | Exactly one; the file is recommended. A token for Vero's Transit paths only |
| `SDV_VAULT_TRANSIT_MOUNT`, `SDV_VAULT_KEY_PREFIX` | `transit`, `sdv-tenant-` | Where the entities' keys are |
| `SDV_AUTH_MODE` | `disabled` | `enterprise-jwt`. A packaged start refuses `disabled` (`SDV-SYS-006`) and the local profile (`SDV-SYS-005`) |
| `SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS` | `7200` | The longest life of a token Vero accepts, people's and sending systems', 1 to 86400. **Upgrade:** `0` (off) is refused since task 0065 |
| `SDV_AUTH_BROWSER_SCOPE` | `openid profile email` | What the browser asks the login system for |
| `SDV_AUTH_JWT_USERNAME_CLAIM`, `..._EMAIL_CLAIM`, `..._DISPLAY_NAME_CLAIM` | `preferred_username`, `email`, `name` | Display only |
| `SDV_AUTH_JWT_ENTITY_CLAIM`, `..._SOURCE_ID_CLAIM`, `..._SOURCE_KEY_CLAIM` | `sdv_entity_id`, `sdv_source_id`, `sdv_source_key` | Optional claims of a sending system's token; when there, they must match its binding |
| `SDV_TENANT_STATUS_CACHE_SECONDS` | `30` | How soon a suspended entity is refused, 0 to 60 |
| `SDV_INTAKE_REQUESTS_PER_MINUTE` | `6000` | Intake requests a minute from one sending system, for each Vero process, 60 to 1,000,000. Above it: `SDV-SYS-018` with `Retry-After` |
| `SDV_RETENTION_DAILY_AT` | `02:00` | The daily cleanup, HH:mm in the server's time zone |
| `SDV_RETENTION_CRON` | empty | For check scripts only. Leave empty |
| `SDV_DEFAULT_TIME_ZONE` | `UTC` | The zone an entity's days are counted in when it has none; an unknown name stops the start |
| `SDV_APPROVALS_GRANT_HOURS` | `24` | How long an approved print or download may be used, 1 to 168 |
| `SDV_APPROVALS_PENDING_DAYS` | `7` | How long a request may wait, 1 to 90 |
| `SDV_APPROVALS_TENANT_GRANT_HOURS`, `SDV_APPROVALS_TENANT_PENDING_DAYS` | empty | The same for one entity: `entity-id=number,...` |

**Redaction on legal hold** (task 0066): approved redactions are applied, and sealed originals destroyed after the last
hold, by a job that runs every 10 seconds. It needs no setting.

**Licensing is not live.** The licence file names the entity slots and is read when one is activated. Nothing else checks
it: no seats, no expiry.

**The local development jar** (`sdv-web-<version>-local.jar`) is never installed for a customer.

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

### Keys for a customer

Task 0083, for the move to keys per customer (task 0076). Until then Vero uses the old keys, and this step changes nothing for
it. Whoever looks after Vault runs it once, with a Vault token that may add mounts, policies and an AppRole:

```powershell
.\Setup-SDV-VaultTransit.ps1
```

It asks for the token (not shown) and adds, only where missing: the Transit mount `vero-transit/`; the policies
`platform-crypto-app`, `-setup` and `-offboard`; the AppRole method; Vero's role; and one file with the role ID and secret ID
(default `C:\dev\sdv\secrets\vero-vault-approle.properties`, this Windows user only; elsewhere: `-OutFile <full path>`).
The old `transit/` mount stays: an older build still reads the documents encrypted with it. It never initialises, unseals
or seals a Vault and changes no mount, key, policy or role that is there; when something is there but different, it says so
and changes nothing. Run again: "Everything is there already."

| It says | Do |
|---|---|
| Vault is sealed | Unseal it as usual, run again |
| The role is there, but the file isn't | Run again with `-NewSecretId` |
| The policy is there with another text | Another product may use it: give `-PolicyPrefix` another name |

Who gets which policy: the running Vero `-app` only (it can't make or delete keys); the setup command `-setup`, as a token for
that run (`vault token create -policy=platform-crypto-setup -ttl=15m -no-default-policy`); `-offboard` only at a customer's exit.

**Undo, on a scratch Vault only:** `vault secrets disable vero-transit`, `vault delete auth/approle/role/vero`, `vault policy
delete platform-crypto-app` (and `-setup`, `-offboard`). Never on a Vault with customers' keys: disabling the mount destroys
them, and every document encrypted with them, for good.

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

Task 0073 (platform-core 1.0.0): the same versions, identity 5 and licensing 2; `update` also narrows the app user's rights on
two licensing tables. Task 0067 (platform-core 0.7.0): this release's identity tables are at version 5 and licensing tables at version 2;
`status` lists what is missing and `update` applies it. Before, task 0057 (platform-core 0.6.1): identity tables are at version 4 (`idn_platform_grant`). On a
database whose identity tables are at version 3, `status` lists `identity V4 (platform-core)` and `update` applies it
in the identity run; Vero refuses to start until then (`IDN_VERSION_OLDER`).

### Checking it on a test computer

`Test-SDV-CleanInstall.ps1` repeats the whole path against a scratch database `sdv_install_test` inside the local
`sdv-postgres` server (it never touches the `sdv` database and drops everything it creates): an empty database is
refused with the "older" message, `status`, `sql` and `update` run, Vero then starts as the read/write user with an
empty `tenants` table, and a further start applies nothing.

## Remote testers

Task 0089, plan `docs/work/0087-plan.md`. A tester instance on the owner's laptop, reached through a Cloudflare Tunnel by the
listed e-mail addresses only. **Demo documents only:** Cloudflare ends the TLS connection and can see the traffic.

```
Tester's browser -> Cloudflare (Access: listed e-mails) -> tunnel -> Caddy 127.0.0.1:8290
   /  /assets/                      the built screens
   /api/v1/ (never ingestion)       Vero 127.0.0.1:8292
   sign-in of realm vero-testers    tester Keycloak 127.0.0.1:8291
   everything else                  404
```

One web address for all of it, so the login pages are behind Access too. Files: `dev/testers/`. Checks:
`TesterTunnelConfigTest` (every build) and `.\Test-SDV-TestersProxy.ps1` (throwaway Caddy container; run it after any change).

**Rules.** Never local mode: a local start is refused while a tunnel program runs, when `SDV_TRUSTED_PROXIES` is set, or when
it would listen on more than this computer (`SDV-SYS-005`), and a forwarded local sign-in is refused (401). Never install
`cloudflared` as a service: it must stop with the test, and that check may not see a service. Never put Postgres, Vault,
health details or the Keycloak admin behind the tunnel: use `Start-SDV-Testers.ps1 -Tunnel`, never a tunnel file of your own.

### The owner's steps

Do them in this order: the Access policy exists before the address does. Cloudflare may have moved a menu.

1. **Owner only.** Buy a domain (at Cloudflare, or elsewhere with its name servers moved to Cloudflare). Below: `vero.<domain>`.
2. **Owner only.** Cloudflare account: turn on two-step sign-in. Open **Zero Trust**, choose a team name and the free plan
   (check its user limit).
3. **Owner only.** **Zero Trust > Integrations > Identity providers > Add new identity provider > One-time PIN.**
4. **Owner only.** The e-mail list. **Zero Trust > Access controls > Applications > Create new application > Self-hosted and
   private > Add public hostname**: subdomain `vero`, your domain, path empty. Policy: name `Vero testers`, action **Allow**,
   Include **Emails**, each tester's address one by one (no "Emails ending in", no "Everyone"). Login method: One-time PIN only.
   Session duration: 24 hours. **Create.**
5. **Owner only.** One more application the same way: name `Vero sign-in address`, hostname `vero.<domain>`, path
   `realms/vero-testers/.well-known/openid-configuration`, policy action **Bypass**, Include **Everyone**. Vero itself reads
   this one page from the laptop. It lists addresses only, no secret. Never bypass any other path.
6. **Owner only.** In the domain's settings switch off everything that adds scripts: Web Analytics, Rocket Loader, e-mail
   obfuscation. Switch on "Always Use HTTPS".
7. **Owner only.** Install `cloudflared` (Cloudflare's downloads page; name it `cloudflared.exe`, in a folder on PATH) and Caddy
   2.11 (`caddy.exe`, the same). Then, in a PowerShell window:

   ```powershell
   cloudflared tunnel login
   cloudflared tunnel create --credentials-file C:\dev\sdv\secrets\vero-testers-tunnel.json vero-testers
   cloudflared tunnel route dns vero-testers vero.<domain>
   ```

   The first opens the browser: choose the domain. Keep the tunnel ID the second shows; its file is a secret.
8. **Owner only.** `C:\dev\sdv\secrets\vero-testers.env` with three lines, passwords of your own:
   `VERO_TESTERS_HOST=vero.<domain>`, `VERO_TESTERS_KC_DB_PASSWORD=...`, `VERO_TESTERS_KC_ADMIN_PASSWORD=...`
9. **Owner only.** Database `sdv_testers` with owner `vero_testers_owner` and app user `vero_testers_app` ("Two database users"
   above, with these names), in the local server. Say whether a session may run the schema step on it.
10. **Owner only.** The testers' own licence, signed with the development key: customer `TEN-VERO-TESTERS-0001`, slots
    `ENT-VERO-TESTERS-0001` and `-0002`, product SDV. Its own customer gives it its own key; the demo licence would share the
    development one. Keep it at `C:\dev\sdv\testers\licence.json`.

### Settings

Copy `dev\testers\testers.settings.example.psd1` to `C:\dev\sdv\testers\testers.settings.psd1` and fill it in (names and paths
only). `KeySettings`: the key service settings of the build (task 0076), naming the mount `vero-transit` and the **path** of the
login file. The setup command makes the customer's key ("Keys for a customer"). Copy `dev\testers\role-mapping-SDV.csv`,
`client-bindings-SDV.csv` and `signin-vero-testers.template.json` (as `signin.json`, with `__HOST__` filled in) to the same folder.

`.\Start-SDV-Testers.ps1 -CheckOnly` checks the settings and starts nothing.

### Start, accounts, stop

1. Tester Keycloak (its own containers, never the development ones):
   `docker compose --env-file C:\dev\sdv\secrets\vero-testers.env -f dev\testers\compose.testers.yml up -d`.
   The realm is imported at the first start only; after a change to the file, change the realm too.
2. `Build-SDV-All.ps1`; schema step on `sdv_testers`; demo data with the tunnel off; then `setup.ps1 plan` and `apply` with
   the testers' files.
3. **Owner only.** Accounts, one per person. `kcadm.sh` asks for the passwords:

   ```powershell
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh config credentials --server http://localhost:8080 --realm master --user admin
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh create users -r vero-testers -s username=<name> -s email=<e-mail> -s firstName=<first> -s lastName=<last> -s enabled=true
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh set-password -r vero-testers --username <name> --temporary
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh get groups -r vero-testers --fields id,name
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh get users -r vero-testers -q username=<name> --fields id
   docker exec -it vero-testers-keycloak /opt/keycloak/bin/kcadm.sh update users/<user id>/groups/<group id> -r vero-testers -s realm=vero-testers -s userId=<user id> -s groupId=<group id> -n
   ```

   Groups: `BusinessUsers`, `DocumentStewards`, `RestrictedStewards` (never with an admin group), `BusinessAdmins`,
   `TenantITAdmins`. At the first sign-in the tester changes the password and sets up an authenticator app.
4. `.\Start-SDV-Testers.ps1`, then the local checks of plan 0087 section 7.
5. **Owner only.** `.\Start-SDV-Testers.ps1 -Tunnel`, then the outside checks from a phone on mobile data: a listed e-mail gets
   in, another doesn't; `/actuator/health`, `/admin/`, `/realms/master/`, `/api/v1/ingestion/` answer 404 after the Access
   sign-in; without it, only the sign-in address of the owner's step 5 answers.
6. **Stop:** `.\Stop-SDV-Testers.ps1` (tunnel first; `-TunnelOnly` for just that).
   **Remove a tester:** take the e-mail out of the policy and revoke the Access session, then disable the Keycloak user.
