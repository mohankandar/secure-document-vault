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

## Before you install

Read this first on a new server. The order: this section, "Two database users", "Keys for a customer" (Vault's part), "The
schema step", setup (`docs/install/SETUP.md`), "The first start", activate the entities.

| You need | Which |
|---|---|
| A server | Windows or Linux, its clock kept right (NTP) |
| Java | 21, on the path of whoever runs Vero, setup and the schema step. Check: `java -version` |
| PostgreSQL | 16 is what Vero is tested with. A database of Vero's own and two users. No extension is needed |
| Vault | 1.17 is what Vero is tested with. Transit and AppRole, reached over https |
| Login system | OpenID Connect, access tokens signed RS256, a public browser client, an API audience, a groups claim, one client for each sending system |
| Web server | Serves the screens, does TLS, sends `/api/` to Vero |
| Certificates | For the web address people use. If your own authority signed the certificate of Vault, the database or the login system: that authority's certificate as a PEM file |
| Files from us | The application jar, the setup jar, `setup.ps1` or `setup.sh`, `Update-SDV-Schema.ps1` or `update-sdv-schema.sh`, `product-roles.json`, the screens (`dist`), your licence |
| A service account | Runs Vero: write in Vero's working folder and the document folders, read on the login file, nothing more |
| Backups | Database, document folders, Vault and settings together (`SDV_BACKUP_RESTORE_HA_RUNBOOK.md`), arranged before the first document |

| From | To | Port |
|---|---|---|
| People's browsers | web server; login system | 443 |
| Sending systems | web server (`/api/v1/ingestion/`); login system | 443 |
| Web server | Vero | `SDV_SERVER_PORT`, 8280; closed to everyone else |
| Vero, setup, schema step | PostgreSQL | 5432, or yours |
| Vero, setup | Vault | 8200, or yours |
| Vero | login system (discovery and keys) | 443 |

**Your own certificate authority.** Vero and setup use Java's trust store for Vault, and Vero for the login system's signing
keys. Make a trust store that also holds your authority, and name it for Vero and for setup. Linux:

```sh
cp "$JAVA_HOME/lib/security/cacerts" /opt/vero/vero-trust.jks
"$JAVA_HOME/bin/keytool" -importcert -noprompt -alias company-ca -file company-ca.pem -keystore /opt/vero/vero-trust.jks -storepass changeit
export JAVA_TOOL_OPTIONS="-Djavax.net.ssl.trustStore=/opt/vero/vero-trust.jks"
```

Windows:

```powershell
Copy-Item "$env:JAVA_HOME\lib\security\cacerts" C:\vero\vero-trust.jks
& "$env:JAVA_HOME\bin\keytool.exe" -importcert -noprompt -alias company-ca -file company-ca.pem -keystore C:\vero\vero-trust.jks -storepass changeit
$env:JAVA_TOOL_OPTIONS = '-Djavax.net.ssl.trustStore=C:\vero\vero-trust.jks'
```

(`changeit` is Java's own default for this file of public certificates, not a secret.) `PLATFORM_OUTBOUND_PRIVATE_CA_FILE`
(SETUP.md section 5) is separate and needed too: the login system's discovery document is read with it. For the database, put
TLS in its address: `jdbc:postgresql://db01:5432/vero?sslmode=verify-full&sslrootcert=/opt/vero/company-ca.pem`.

## Update the database

**Task 0078 (changeset 048):** the schema step refuses a database that holds field values of an earlier Vero and changes nothing. Make a
new database beside it and deliver again. Searching for a part of a value needs PostgreSQL's `pg_trgm` (trusted since 13: the schema step
makes it when the owner may create extensions). Without it "contains" works as "starts with" and the Filters panel says so; the database
team can add it later with `db/platform/fields-optional/partial-search.sql` from Vero's jar. Back up the whole database, not one schema.

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

### The licence (task 0075)

Vero starts only with a licence: yours, unchanged, signed with a key of this Vero build. Order on a new installation: schema
step, setup (it stores the licence and writes `platform-tenant.properties`), then the first start.

| Setting | |
|---|---|
| `SDV_CUSTOMER_ID` | your customer ID (`TEN-…`), or let Vero read setup's file: |
| `SDV_TENANT_FILE` | default `./platform-tenant.properties` in Vero's working folder |

- **A date never stops Vero.** 60 days before the end Vero says so; after the end there are 30 days of grace; then new documents
  are refused (503 `SDV-LIC-001`), everything else goes on. A missing, changed or another customer's licence stops the start.
- **Renewal:** store the new file as soon as you have it (it waits for its first day). No restart:
  ```
  set PLATFORM_LICENSING_DB_PASSWORD=<the app user's password>
  java -cp sdv-web-<version>.jar -Dloader.main=com.documentplatform.licensing.command.LicenceCommand org.springframework.boot.loader.launch.PropertiesLauncher store --file licence.json --jdbc-url <url> --db-user <app user> --tenant-id <TEN-…> --product-code SDV
  ```
  Linux: `read -rs PLATFORM_LICENSING_DB_PASSWORD; export PLATFORM_LICENSING_DB_PASSWORD` in place of the first line.
  `status` in place of `store --file …` shows it.
- **Clock reset:** when the server clock was once far in the future, licence dates stay there. Ask for a clock reset file and
  apply it with `apply-reset --file reset.json` and the **schema owner's** database user. It works once, for a few days.
- **Usage** is counted, never a limit: storage (GB, daily), people at the same time (15 minutes, the day's highest), stewards and
  administrators of the month. IT, About, **Export usage** saves the file for the account manager; no names, no prices. A file
  covers the finished months since the last one, and goes on from it; a missing or replaced anchor (`SDV-LIC-003`, for example after a restore) is said, and only IT can then
  start a new series, which is in the audit.
- **Local development:** a new database needs `.\Seed-SDV-Canvas-Demo.ps1 -SetupOnly` before Vero's first start.

### Release licence keys

For the owner; a customer never sees this. A release build starts only with a licence signed with a key it trusts (tasks 0073
and 0075). The signing key is made and used by the owner alone, on the owner's computer: **no coding session runs these two
scripts with a real key, opens its folder or sees the key.** Windows PowerShell 5.1, after Vero was built once on this computer.

1. Make a folder outside every repository and outside folders that are copied to the cloud (OneDrive), on an encrypted drive
   (BitLocker): `New-Item -ItemType Directory D:\vero-licence-keys`.
2. Make the key, in the checkout of a new branch from `release/vero-r7`:

   ```powershell
   .\New-SDV-ReleaseLicenceKey.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026
   ```

   It writes `vero-release-2026.private-key` (this Windows user only) and `vero-release-2026.public.json` to the folder, and the
   public key to `backend\licence-build\release`. It never replaces a key and prints nothing secret.
3. **Offline copy, at once:** copy the private key file to two encrypted USB sticks (BitLocker To Go), kept in two places, with
   the stick's password kept apart from it. Not by e-mail, not in a chat, a ticket, a cloud folder or a repository. Compare the
   copy: `Get-FileHash` of both files shows the same value.
4. Commit `backend\licence-build\release` (two files, public only) and have it merged. From the next release build on, licences
   of this key are accepted.
5. Sign a licence. Copy `dev\licence\licence-input.example.json` to a folder outside the repository and fill it in: customer,
   entities, dates, storage, people at the same time, named people. A field the script doesn't know, a price for example, refuses the file.

   ```powershell
   .\New-SDV-Licence.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026 -InputFile D:\licences\acme-1.json -OutFile D:\licences\acme-1.licence.json
   ```

   `"tenantId": "new"` (version 1) and `"slotId": "new"` make the IDs, which it shows: write them into the input file and keep
   it. A renewal is the same file with the same IDs, the next `version` and new dates. `kind`: `PRODUCTION`, `NON_PRODUCTION`
   (testers: `TEN-VERO-TESTERS-0001` and its two slots) or `INSTALL` (at most 60 days). The licence file isn't secret.

| It says | Do |
|---|---|
| The key is listed, or the file is there, already | Give a new key a new `-KeyId`. Nothing was changed |
| The public key isn't in the release build | Run again with `-PublishOnly` |
| The release build doesn't trust the key | The checkout has no such public key: update it, or `-PublishOnly` |
| The private key isn't the one the release build trusts | Wrong folder or file: take the offline copy |

**Lost key:** make a new one with a new `-KeyId` and ship a release; licences signed before keep working. Never take an old key
out of the release build while a licence signed with it is in use. **Somebody else may have the key:** make a new one, ship a
release without the old one, and sign every customer's licence again, as its next version, before they install that release.

`.\Test-SDV-ReleaseLicenceKey.ps1` checks both scripts with throwaway keys in a temporary folder.

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

### The erase log: `SDV_ERASE_LOG_FOLDER`

Every erasure (a document at the end of its time, a version a redaction replaces, a sealed original after its hold, the search
field values of a record whose last document went) is written to the erase log first: IDs only, never a value. After a restore
the log says what must be erased again before anyone works with the database (task 0080).

| Setting | Default | Value |
|---|---|---|
| `SDV_ERASE_LOG_FOLDER` | none; Vero doesn't start without it | An absolute path |
| `SDV_BACKUP_RETENTION_DAYS` | `35` | How long database backups are kept, 30 to 90; the log keeps its entries 30 days longer |

- **Not in a document volume** (`SDV-ERS-002` stops the start), **not on the database's storage**, not in either one's backups.
- **Backed up on its own**, for at least as long as the log keeps its entries.
- **The same folder for every Vero of one installation**, and one folder for one database. A test database has its own.
- Only the service account may write there. Vero makes the folder and its files; nobody edits them: a changed or forged
  entry stops all work (`ERASE_LOG_CHAIN_BROKEN`). The entries are keyed with the customer's key in the key service.
- **Moving it:** stop every Vero, move **all** files, change the setting, start. Never start an empty log for a database that
  used one.
- The app user has no DELETE on `erl_position`; the schema step takes it away.
- A log from before it was keyed is sealed once: `java -jar sdv-web-<version>.jar --erase-log-seal`. A new log needs nothing.

When the log stops work, every request but health is `503 SDV-ERS-001`, the jobs wait, and readiness names the log's code:

| Code | Do |
|---|---|
| `ERASE_LOG_RESTORE_DETECTED` | The database was restored: [backup runbook](SDV_BACKUP_RESTORE_HA_RUNBOOK.md), "Restore" |
| `ERASE_LOG_UNREACHABLE` | The folder isn't mounted, or rights are missing |
| `ERASE_LOG_KEY_UNAVAILABLE` | The key service must answer; Vero goes on by itself |
| `ERASE_LOG_BEHIND_DATABASE` | The log lost entries: put its newest backup back |
| `ERASE_LOG_WRONG_LOG` | The setting names another database's log |
| `ERASE_LOG_CHAIN_BROKEN`, `ERASE_LOG_FORMAT`, `ERASE_LOG_LINK_REFUSED` | Somebody changed the folder: restore the log from its own backup, and call support |
| `ERASE_LOG_GAP` | The database backup is older than the log's entries: use a newer one |

An entry that can't be finished (a share that is away) shows on Platform operations, Health, with its number and ID
(`SDV-ERS-003`); later erasures wait for it.

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

Vero's screens are the folder `dist` of the build (`frontend/sdv-ui/dist`). Your web server or proxy serves them, sends `/api/` to Vero, and does TLS.
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

Don't pass `/actuator/` on: probes reach Vero directly. The proxy's own limit on a request's size must not be below the sending
systems' document limit (nginx: `client_max_body_size`). `dev/testers/Caddyfile` is an example for Caddy.

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
| `SDV_KEY_SERVICE_ADDRESS` | none | Vault's address, https outside a test computer. An address in the login file counts first |
| `SDV_VAULT_LOGIN_FILE` | `./vero-vault-login.properties` | A file with `platform.crypto.vault.role-id` and `.secret-id` (`Setup-SDV-VaultTransit.ps1` writes it). Other logins: `platform.crypto.vault.login=TOKEN_FILE` and `.token-file` |
| `SDV_KEY_SERVICE_MOUNT` | `vero-transit` | The Transit mount with the customer's keys |
| `SDV_CRYPTO_ENABLED` | `true` | `false` only in a local start (`SDV-KEY-001` otherwise) |
| `SDV_AUTH_MODE` | `disabled` | `enterprise-jwt`. A packaged start refuses `disabled` (`SDV-SYS-006`) and the local profile (`SDV-SYS-005`) |
| `SDV_AUTH_JWT_MAX_TOKEN_LIFETIME_SECONDS` | `7200` | The longest life of a token Vero accepts, people's and sending systems', 1 to 86400. **Upgrade:** `0` (off) is refused since task 0065 |
| `SDV_AUTH_BROWSER_SCOPE` | `openid profile email` | What the browser asks the login system for |
| `SDV_AUTH_JWT_USERNAME_CLAIM`, `..._EMAIL_CLAIM`, `..._DISPLAY_NAME_CLAIM` | `preferred_username`, `email`, `name` | Display only |
| `SDV_AUTH_JWT_ENTITY_CLAIM`, `..._SOURCE_ID_CLAIM`, `..._SOURCE_KEY_CLAIM` | `sdv_entity_id`, `sdv_source_id`, `sdv_source_key` | Optional claims of a sending system's token; when there, they must match its binding |
| `SDV_TENANT_STATUS_CACHE_SECONDS` | `30` | How soon a suspended entity is refused, 0 to 60 |
| `SDV_INTAKE_REQUESTS_PER_MINUTE` | `6000` | Intake requests a minute from one sending system, for each Vero process, 60 to 1,000,000. Above it: `SDV-SYS-018` with `Retry-After` |
| `SDV_ERASE_LOG_FOLDER`, `SDV_BACKUP_RETENTION_DAYS` | none, `35` | "The erase log" above. The folder is required |
| `SDV_RETENTION_DAILY_AT` | `02:00` | The daily cleanup, HH:mm in the server's time zone |
| `SDV_RETENTION_CRON` | empty | For check scripts only. Leave empty |
| `SDV_NOTIFICATIONS_ENABLED` | `true` | Notifications by e-mail, Teams and Slack ("Notifications" below). `false` switches them off; the schema step makes their tables either way |
| `SDV_APPROVALS_GRANT_HOURS` | `24` | How long an approved print or download may be used, 1 to 168 |
| `SDV_APPROVALS_PENDING_DAYS` | `7` | How long a request may wait, 1 to 90 |
| `SDV_APPROVALS_TENANT_GRANT_HOURS`, `SDV_APPROVALS_TENANT_PENDING_DAYS` | empty | The same for one entity: `entity-id=number,...` |

**Redaction on legal hold** (task 0066): approved redactions are applied, and sealed originals destroyed after the last
hold, by a job that runs every 10 seconds. It needs no setting.

Licensing: "The licence" above.


**The local development jar** (`sdv-web-<version>-local.jar`) is never installed for a customer.

### Notifications (task 0081)

Vero tells people by e-mail, Microsoft Teams and Slack (platform-core's notifications; its install guide
`docs/install/notifications.md` at the platform-core release has the details of each step below). Without a channel Vero still
starts, records notifications and sends nothing.

1. **The tables.** The schema step makes them (`notif_…`). The app user needs select, insert, update and delete on them,
   select only on `notif_schema_version`; the schema step takes the other rights on that table back. Without the tables
   Vero stops at the start with `TABLES_MISSING`, `VERSION_OLDER` or `PRIVILEGES_MISSING` and says what to run.
2. **The secrets** go in the key service's KV store, under `secret/platform/<path>` with the key `value` (Vero's login reads
   `secret/data/platform/*`, `Setup-SDV-VaultTransit.ps1` gives it that right): the mail password (or the Microsoft 365 app's
   certificate or secret), each Teams or Slack webhook address. The settings hold only the **path**, never the secret.
3. **The settings**, in the install config (environment variables shown; YAML works as well):

   | Setting | What |
   |---|---|
   | `PLATFORM_NOTIFICATIONS_MAIL_HOST`, `..._MAIL_PORT`, `..._MAIL_SECURITY` | The mail server; STARTTLS on 587 is the default. Leave them out for no e-mail |
   | `PLATFORM_NOTIFICATIONS_MAIL_USERNAME`, `PLATFORM_NOTIFICATIONS_MAIL_PASSWORDSECRET` | The login and the **path** of its password |
   | `PLATFORM_NOTIFICATIONS_MAIL_FROMADDRESS`, `..._MAIL_FROMNAME` | The sender |
   | `PLATFORM_NOTIFICATIONS_MAIL_AUTH=OAUTH2_MICROSOFT` and `..._MAIL_OAUTH_*` | Microsoft 365 without a password (platform-core's guide, section 1a) |
   | `PLATFORM_NOTIFICATIONS_TEAMS_<ID>`, `PLATFORM_NOTIFICATIONS_SLACK_<ID>` | One per channel: the id (letters and digits in a variable's name: `PLATFORM_NOTIFICATIONS_TEAMS_OPS` is the destination `ops`; ids with `-` go in the YAML file) and the **path** of its webhook address |
   | `SDV_NOTIFICATIONS_ENABLED` | `false` switches notifications off |
   | `SDV_PUBLIC_URL` | The https address people open Vero on; every message links under it (task 0100, item 4 below) |
   | `SDV_NOTIFICATIONS_ROLE_SEEN_DAYS` | 7 to 90, 30 by default: a list entry whose person hasn't signed in with that role for this many days gets nothing (task 0100) |

   A wrong setting stops the start and names the setting, never its value.
4. **The app address** (task 0100): set `SDV_PUBLIC_URL` to the https address people open Vero on, such as
   `https://vero.example.com` (no user, query or `#`). Every message links under it, and no screen can change it. A plain
   `http` address, or anything else that isn't such an address, stops the start (SDV-SYS-005). Not set: Vero starts but
   sends no messages, and IT › Notifications says so. At each start, a customer connected under another address is moved
   to this one.
4a. **In Vero**, the IT Admin opens IT › Notifications, checks the app address shown there, presses **Connect** once,
   chooses the technical notifications and keeps the list of IT Admins who get e-mail. Each Business Admin chooses their
   entity's notifications and keeps its lists (Business administration › Notifications).
4b. **Teams and Slack go through the outbound settings** (`platform.notifications.webhook.via-outbound=true`, Vero's default since
   task 0099): the address guard, the proxy and a private certificate authority of `PLATFORM_OUTBOUND_*` apply, https only. A
   refused address shows `WEBHOOK_OUTBOUND_REFUSED` on IT › Notifications and is not retried. The mail server is reached as
   before.
4c. **Approval links** (task 0082): put a random key of at least 32 characters (`openssl rand -base64 48` makes one of 64) in the
   key service at `secret/platform/notifications/approval-link-key` (key `value`) and set
   `PLATFORM_NOTIFICATIONS_APPROVALS_LINKKEYSECRET=notifications/approval-link-key`. Without it the e-mails carry no Approve and Decline
   links. Links are `https://<vero>/approvals/link/<token>`: the web server answers that path with `index.html` and **does not log
   it** (the token is the last part; Vero takes it out of the address at once and never logs it). Replacing the key ends the links
   already sent; approvers decide in Approvals then. A link lives as long as a request waits (`SDV_APPROVALS_PENDING_DAYS`), at most
   30 days.

   **Keep the link path out of the access logs** (task 0100). The exact lines:

   - **nginx**, in the `server` block that serves the screens:

     ```nginx
     location ^~ /approvals/link/ {
         access_log off;
         add_header Referrer-Policy no-referrer always;
         try_files /index.html =404;
     }
     ```

   - **IIS**, once, in a command prompt run as administrator (`<Vero site>` is the site's name in IIS Manager):

     ```bat
     %windir%\system32\inetsrv\appcmd.exe set config "<Vero site>/approvals/link" -section:system.webServer/httpLogging /dontLog:"True" /commit:apphost
     ```

   Check it: open a link, then search the web server's log (nginx: `access.log`; IIS:
   `%SystemDrive%\inetpub\logs\LogFiles\W3SVC<site id>`) for `approvals/link`; nothing is found. A proxy or load balancer in
   front of Vero keeps its own log: leave the same path out there too.
5. **Links** in messages are paths such as `https://<vero>/governance/approvals`. The web server that serves the screens
   answers every such path with `index.html`, as it does for `/`; the screens then open the right page.
6. **Time zones** are identity's: the customer's (IT › Notifications) and an entity's own (Business administration ›
   Notifications). Daily summaries, the times in messages and the reports' days follow them. `SDV_DEFAULT_TIME_ZONE` is gone:
   **before upgrading**, set the customer's zone to the value it had (an entity's own zone set before moves by itself).

Health: `/actuator/health/notifications` (and IT › Notifications) shows each destination: working, having problems, down or
not used yet, with a code and its reason; never an address or a secret. A destination that is down marks the entry
`DEGRADED`, never Vero as down.

### Two database users

| User | Used by | Rights |
|---|---|---|
| Owner, for example `vero_owner` | The schema step only | Owns the database (or at least schema `public`): creates and changes tables and indexes |
| App, for example `vero_app` | The running Vero (`SDV_DB_USERNAME`, `SDV_DB_PASSWORD`) | Connect, and `SELECT`, `INSERT`, `UPDATE`, `DELETE` on the tables, except the audit trail (`audit_events`) and the audit report register (`audit_reports`): `SELECT` and `INSERT` only. No create, no alter |

Once, when the database is created, in `psql` as a PostgreSQL superuser, **before** the schema step (the default privileges
cover only tables made afterwards). `...`: passwords of your own.

```sql
create role vero_owner login password '...';
create role vero_app login password '...';
create database vero owner vero_owner;
revoke all on database vero from public;
grant connect on database vero to vero_app;
\c vero
-- PostgreSQL 14 and earlier (not tested) also need: grant usage, create on schema public to vero_owner;
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
**Check it,** in `psql`: `\l vero` names `vero_owner` as the owner, and `\ddp` shows `vero_app=arwd/vero_owner`.
`Test-SDV-CleanInstall.ps1` checks that Vero runs with such a user.

### Keys for a customer

Documents are encrypted with the customer's key (task 0076). Whoever looks after Vault runs this once, with a Vault token that may add mounts, policies and an AppRole:

```powershell
.\Setup-SDV-VaultTransit.ps1
```

It asks for the token (not shown) and adds, only where missing: the Transit mount `vero-transit/`; the policies
`platform-crypto-app`, `-setup` and `-offboard`; the AppRole method; Vero's role; and one file with the role ID and secret ID
(default `C:\dev\sdv\secrets\vero-vault-approle.properties`, this Windows user only; elsewhere: `-OutFile <full path>`).
The old `transit/` mount stays: an older build still reads the documents encrypted with it. It never initialises, unseals
or seals a Vault and changes no mount, key, policy or role that is there; when something is there but different, it says so
and changes nothing. Run again: "Everything is there already."

**Without Windows**, the same with Vault's own command line, by the person who looks after Vault (`VAULT_ADDR` set, after
`vault login`). The lines marked "must" are checks: when one answers otherwise, stop and compare, don't overwrite.

```sh
vault secrets list                          # vero-transit/ must not be there
vault policy list                           # platform-crypto-app, -setup and -offboard must not be there
vault read auth/approle/role/vero           # must find nothing
vault secrets enable -path=vero-transit transit
vault policy write platform-crypto-app - <<'HCL'
path "vero-transit/datakey/plaintext/customer-*" { capabilities = ["update"] }
path "vero-transit/decrypt/customer-*" { capabilities = ["update"] }
path "vero-transit/rewrap/customer-*" { capabilities = ["update"] }
path "vero-transit/hmac/customer-*" { capabilities = ["update"] }
path "vero-transit/keys/customer-*" { capabilities = ["read"] }
path "secret/data/platform/*" { capabilities = ["read"] }
HCL
vault policy write platform-crypto-setup - <<'HCL'
path "vero-transit/keys/+" { capabilities = ["create", "update", "read"] }
path "vero-transit/keys/+/rotate" { capabilities = ["update"] }
HCL
vault policy write platform-crypto-offboard - <<'HCL'
path "vero-transit/keys/+" { capabilities = ["read", "delete"] }
path "vero-transit/keys/+/config" { capabilities = ["update"] }
HCL
vault auth enable approle                   # leave out when "vault auth list" shows approle/
vault write auth/approle/role/vero token_policies=platform-crypto-app token_no_default_policy=true bind_secret_id=true token_ttl=1h token_max_ttl=4h
( umask 077; set -C; { echo "platform.crypto.vault.address=$VAULT_ADDR"
  echo "platform.crypto.vault.login=APPROLE"
  echo "platform.crypto.vault.approle-mount=approle"
  echo "platform.crypto.vault.transit-mount=vero-transit"
  echo "platform.crypto.vault.role-id=$(vault read -field=role_id auth/approle/role/vero/role-id)"
  echo "platform.crypto.vault.secret-id=$(vault write -f -field=secret_id auth/approle/role/vero/secret-id)"
} > vero-vault-login.properties )
```

Move the file to Vero's working folder and make the service account its owner (`chown vero`). On Windows the script's file can
be read only by the person who ran it: give Vero's service account read rights to it. The file's address and mount count
before `SDV_KEY_SERVICE_ADDRESS` and `SDV_KEY_SERVICE_MOUNT`.

| It says | Do |
|---|---|
| Vault is sealed | Unseal it as usual, run again |
| The role is there, but the file isn't | Run again with `-NewSecretId` |
| The policy is there with another text | Another product may use it: give `-PolicyPrefix` another name |

Who gets which policy: the running Vero `-app` only (it can't make or delete keys); the setup command `-setup`, as a token for
that run (`vault token create -policy=platform-crypto-setup -ttl=15m -no-default-policy`); `-offboard` only at a customer's exit.

The customer's keys are made by the setup command, with the `-setup` token of that run only:

```powershell
$env:PLATFORM_SETUP_VAULT_ADDR = '<Vault address>'; $env:PLATFORM_SETUP_VAULT_TRANSIT_MOUNT = 'vero-transit'
$env:PLATFORM_SETUP_VAULT_TOKEN = [Net.NetworkCredential]::new('', (Read-Host 'setup token' -AsSecureString)).Password
.\setup.ps1 apply -Licence licence.json -Signin signin.json
.\setup.ps1 plan -Licence licence.json -Signin signin.json
Remove-Item Env:PLATFORM_SETUP_VAULT_TOKEN
```

Linux:

```sh
export PLATFORM_SETUP_VAULT_ADDR='<Vault address>' PLATFORM_SETUP_VAULT_TRANSIT_MOUNT=vero-transit
read -rs PLATFORM_SETUP_VAULT_TOKEN; export PLATFORM_SETUP_VAULT_TOKEN
./setup.sh apply --licence licence.json --signin signin.json
./setup.sh plan --licence licence.json --signin signin.json
unset PLATFORM_SETUP_VAULT_TOKEN
```

**Check it:** the second command, `plan`, shows the customer keys as `unchanged`. `SETUP_KEY_SERVICE_FAILED` names a `CRYPTO_…`
code: `CRYPTO_KEY_SERVICE_UNREACHABLE` or `_TLS`, the address or certificate; `_SEALED`, unseal Vault; `CRYPTO_PERMISSION_DENIED`,
the token hasn't the `-setup` policy, has ended, or the mount is another one.

Vero doesn't call the key service when it starts. While it is away, documents can't be stored or read (503), everything else goes
on. **Documents of versions before 0076 can't be read** by this one; keep the older build and its database to read them.
Check scripts use a scratch Vault of their own (`scripts\local\Use-SdvScratchVault.ps1`), never `sdv-vault`.

**Local development:** a new database `sdv_vero` beside `sdv`, which is kept:
`docker exec sdv-postgres createdb -U sdv -O sdv sdv_vero`; the schema step on it; the two setup variables above; `.\Seed-SDV-Canvas-Demo.ps1
-SetupOnly`; start; `.\Seed-SDV-Canvas-Demo.ps1`.

**Undo, on a scratch Vault only:** `vault secrets disable vero-transit`, `vault delete auth/approle/role/vero`, `vault policy
delete platform-crypto-app` (and `-setup`, `-offboard`). Never on a Vault with customers' keys: disabling the mount destroys
them, and every document encrypted with them, for good.

### The schema step

It is in the Vero application package: `Update-SDV-Schema.ps1` (Windows) or `update-sdv-schema.sh` (Linux), next
to the application jar (`sdv-web-<version>.jar`; use `-Jar` / `--jar` if it is elsewhere). It needs Java 21 and
runs the Liquibase bundled in the jar, then, in their own runs with their own history tables, platform-core's
identity tables (`db/platform/identity`, history `idn_schema_history`) and licensing tables (`db/platform/licensing`,
history `lic_schema_history`) and, since task 0077, case field tables (`fld_…`, history `fld_schema_history`) and, since task 0080, the erase log's table (`erl_position`, history `erl_schema_history`) and, since task 0081, the notifications tables (`notif_…`, history `notif_schema_history`; Vero names it, the module names none). It always uses the `production` context, so no test data can be
added, and it can't be changed.

The connection comes from parameters or environment variables:

| | Windows | Linux | Environment variable |
|---|---|---|---|
| Address | `-Url jdbc:postgresql://db01:5432/vero` | `--url …` | `SDV_SCHEMA_DB_URL` |
| Owner user | `-Username vero_owner` | `--username …` | `SDV_SCHEMA_DB_USERNAME` |
| Password | typed when asked | typed when asked | `SDV_SCHEMA_DB_PASSWORD` |

The password is never a parameter, and Vero never stores any of these. Linux, once: `chmod +x update-sdv-schema.sh setup.sh`.

| Step | What it does |
|---|---|
| `status` | Lists the changesets that would run. Changes nothing. |
| `sql` | Writes the SQL that `update` would run to a file (`-Output` / `--output`), for teams that review and run SQL themselves. Changes nothing. It won't overwrite an existing file. |
| `update` | Applies the changesets, then checks that the database matches. |

Exit codes: 0 done, 2 wrong use (nothing was tried), 3 the database is newer than this version, 1 anything else.

### First install and every upgrade

**platform-core 1.3.0 (task 0099):** the schema step adds identity V6 and fields V3. Stop **every** Vero instance before it runs:
the earlier version does not start on the new tables (`IDN_VERSION_NEWER`), and this one does not start without them.

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
5. First install: run setup now (SETUP.md); Vero starts only with your licence. **Start Vero** with the app user ("The first start"). The log says "Database schema check: the database matches this Vero version".
   Health shows no database notice.

Running `status` or `update` again is safe: when nothing is left it says "Up to date".

Setup loads sign-in, entities and sending systems: `docs/install/SETUP.md`. The role mapping
file there also grants the Platform Operations Admin, once for the whole customer, on a row with `*` as its entity
(platform-level grant, task 0057); the separation rules hold for it (never with Restricted Steward).

Task 0073 (platform-core 1.0.0): the same versions, identity 5 and licensing 2; `update` also narrows the app user's rights on
two licensing tables. Task 0067 (platform-core 0.7.0): this release's identity tables are at version 5 and licensing tables at version 2;
`status` lists what is missing and `update` applies it. Before, task 0057 (platform-core 0.6.1): identity tables are at version 4 (`idn_platform_grant`). On a
database whose identity tables are at version 3, `status` lists `identity V4 (platform-core)` and `update` applies it
in the identity run; Vero refuses to start until then (`IDN_VERSION_OLDER`).

### The first start

After the schema step and setup. Unless `SDV_TENANT_FILE` and `SDV_VAULT_LOGIN_FILE` say otherwise, Vero reads
`platform-tenant.properties` (setup wrote it in the folder it ran in) and `vero-vault-login.properties` from its **working
folder**. Without `SDV_STORAGE_ALLOWED_ROOTS`, documents may be kept only in `data` there.

Linux, as a service. `/etc/vero/vero.env` (owner root, mode 600) holds `SDV_DB_URL`, `SDV_DB_USERNAME`, `SDV_DB_PASSWORD`,
`SDV_ERASE_LOG_FOLDER`, `SDV_KEY_SERVICE_ADDRESS`, `SDV_AUTH_MODE=enterprise-jwt`, the `SDV_AUTH_JWT_*` settings, and `JAVA_TOOL_OPTIONS` if you made a
trust store:

```ini
[Unit]
Description=Vero
After=network-online.target
Wants=network-online.target
[Service]
User=vero
WorkingDirectory=/opt/vero
EnvironmentFile=/etc/vero/vero.env
ExecStart=/usr/bin/java -jar /opt/vero/sdv-web-<version>.jar
SuccessExitStatus=143
Restart=always
RestartSec=10
[Install]
WantedBy=multi-user.target
```

Save it as `/etc/systemd/system/vero.service`, then `systemctl daemon-reload` and `systemctl enable --now vero`. The log:
`journalctl -u vero`.

Windows: set the same settings in a PowerShell window and run `java -jar sdv-web-<version>.jar` in the working folder. For every
day, run the same command with your service wrapper, as the service account, with that working folder and a restart on failure.
Vero brings no Windows service of its own.

**Check it,** on the server (Windows: `curl.exe`): `curl -i http://127.0.0.1:8280/actuator/health/readiness` answers 200 and
`UP`, and the log says "Database schema check: the database matches this Vero version".

| A start that stops says | Do |
|---|---|
| `SDV-SYS-003` | The database can't be reached: address, port, password, TLS |
| `ERASE_LOG_SETTINGS_INVALID`, `SDV-ERS-002` | `SDV_ERASE_LOG_FOLDER`: "The erase log" |
| `LIC_TABLES_MISSING`, `IDN_VERSION_OLDER`, `ERL_TABLES_MISSING` | Run the schema step |
| `IDN_PRIVILEGES_MISSING`, `LIC_PRIVILEGES_MISSING` | The app user's rights ("Two database users") |
| `LICENCE_MISSING` | Run setup, or store the licence ("The licence") |
| `LICENCE_UNKNOWN_KEY_ID` | The licence isn't signed with a key of this build: ask your account manager |
| `platform.licensing.tenant-id is missing` | `platform-tenant.properties` isn't in the working folder, and `SDV_CUSTOMER_ID` isn't set |
| `CRYPTO_SETTINGS_INVALID` | The key service's settings that the log names |
| `SDV-SYS-005`, `SDV-SYS-006`, `SDV-KEY-001` | The setting the log names ([error codes](../api/error-codes.md)) |

Vero doesn't call Vault when it starts. Afterwards sign in as a Business Admin and activate the entity (SETUP.md section 4); as
IT Admin run **Test sign-in**, add a storage volume, and look at **Problems**: every check works, the key service too.

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
