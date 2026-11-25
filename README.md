# Secure Document Vault (SDV)

Secure Document Vault is an independent secure document custody, business search, PDF viewing, controlled document-action, and activity-history product.

## Current status

The repository contains the previously verified/frozen **SDV V1 Units 0–25** baseline plus the active **Business Workspace R3** modernization candidate.

**R3 is not yet re-frozen.** It adds post-V1 runtime behavior and must complete local build/runtime regression and the final release-readiness review before a new release baseline is declared.

Current latest Liquibase migration: **018**.

R3 includes:

- Business workflow tabs: **Search | Results | Document Viewer | Activity**
- source-driven searchable metadata plus common Date Range / Last Updated filters
- record-centric results and AVAILABLE-document selection
- capability-controlled Download, Print, Add Tag, Add Comment, and Permanent Redaction viewer actions
- Business Administrator role-capability overrides for business roles
- record-centric Activity with document-view/read events hidden by default
- desktop and iPad/tablet responsive layouts

Vero is independent of other products: they may act only as external producers through Vero's published APIs.

## Local ports

| Service | Port |
|---|---:|
| SDV backend | 8280 |
| SDV frontend | 5183 |
| SDV PostgreSQL | 5433 |
| Local Vault dev service (when requested) | 8200 |

## Technology baseline

- Java 21
- Spring Boot 4.2.x
- Maven multi-module backend
- PostgreSQL 16 + Liquibase
- React + TypeScript + Vite 8 (tests: Vitest 5)
- Node.js 22 (22.12 or later), 24, or 26 and later to build and test the frontend (Vitest 5 supports only these; not Node 25). The frontend build uses Vite's default browser target, "baseline widely available" (Chrome and Edge 111, Firefox 114, Safari 16.4 or later). Before task 0045 (Vite 5) the target was Chrome 87, Edge 88, Firefox 78, Safari 14.
- Vault Transit through platform-crypto, one key per customer
- pluggable storage provider abstraction
- structured/correlation-aware logging

## Build

From the repository root on Windows PowerShell:

```powershell
.\build-all.cmd
```

**One-time setup:** the backend reads released platform-core modules from GitHub Packages, which needs a personal
token with `read:packages` in the environment variable `GITHUB_PACKAGES_TOKEN` and a `github-platform-core` server
in `~/.m2/settings.xml` (outside every repository). See [docs/install/PLATFORM_PACKAGES.md](docs/install/PLATFORM_PACKAGES.md).

CI (GitHub Actions, `.github/workflows/ci.yml`) runs on every push and pull request to `release/vero-r7` and `main`:
the full backend build with all tests (PostgreSQL-only tests included), the frontend typecheck and build, and the
product-name guard (`ProductNameGuardTest`: no other product's name in code, SQL, JSON, YAML, scripts or install
guides; exceptions only in `backend/sdv-web/src/test/resources/product-name-guard/exceptions.txt` with a reason).

## Start local environment

```powershell
.\Start-SDV-Local.ps1 -WithVault
```

Expected endpoints:

- Backend health: `http://localhost:8280/actuator/health`
- System info (needs sign-in since task 0071): `http://localhost:8280/api/v1/system/info`
- Frontend: `http://localhost:5183`

Stop with:

```powershell
.\Stop-SDV-Local.ps1
```

## Automatic cleanup time

Documents past their keep date are deleted once a day at a fixed time, by default 02:00 in the server's time zone. To change it, set `sdv.retention.daily-at` (environment variable `SDV_RETENTION_DAILY_AT`) to a 24-hour `HH:mm` time, for example `23:30`. Health and Overview show the next run from this setting. A time in any other format stops the server at start-up. The old `SDV_RETENTION_INITIAL_DELAY_MS` and `SDV_RETENTION_SWEEP_DELAY_MS` settings are no longer used, and the server refuses to start while either is set. `SDV_RETENTION_CRON` is for the check scripts only.

## Current UI/R3 preflight

```powershell
.\Test-SDV-UI-ADMIN-R2.ps1 -SkipBuild
```

The filename is retained for compatibility with the current development workflow; its checks now cover the R3 Business Workspace and admin UI requirements.

## Demo data for Search / Results / Viewer / Activity validation

With the local backend and Vault running, one command gives a fresh machine the demo data the screen tour (`npm run ui:capture`) and `npm run ui:compare` use:

On a **new database** run `.Seed-SDV-Canvas-Demo.ps1 -SetupOnly` first, then start Vero: it starts only with a licence (task 0075).

```powershell
.\Seed-SDV-Canvas-Demo.ps1
```

It creates the Redaction Service and Loan Origination System sending systems with their records (including the 14-page demo document on 300000000092 and the record with a missing document, 300000000090), the storage volumes `primary-a` and `primary-b`, the `e2e-demo` tag and a demo comment, the paused "Title Records" and "Tour test system" sending systems, and the organization name "Acme Lending". It runs `Seed-SDV-Demo-Records.ps1` itself; that script is still there for one-off records (`-FreshTestData`, `-ProblemDemo`).

Running it again changes nothing. The seed uses the normal APIs plus a few direct writes to the local database and is for local development only: it refuses anything but `localhost`. Its PDFs (`test-tools\fixtures`, `scripts\demo-files`) are not part of the backend jar or the frontend build.

It is safe to run in any state: when the demo data has moved on (for example a demo document's level was lowered by hand), a step it can't do is skipped with a `[SKIP]` line, never failed.

To put the demo data back to the state the seed makes (levels, waiting requests, legal holds, Roles & permissions, field settings), for example before a test pass or after testing by hand:

```powershell
.\Seed-SDV-Canvas-Demo.ps1 -Reset
```

The reset refuses unless Vero runs in local mode on a local `sdv*` database that this seed has filled. It works through the API, so each change is audited. It can't undo a permanent redaction, or tags and comments added by hand.

To check the seed on an empty scratch database (`sdv_seed_test`, created and dropped by the check; the `sdv` database is not touched):

```powershell
.\Test-SDV-DemoSeed.ps1 -WithTour
```

## Release-readiness scripts

The Unit 0–25 acceptance scripts and V1 packaging scripts are intentionally retained because they are needed for regression/reference when R3 is finalized. They represent the frozen V1 release gate and must not be treated as proof that the R3 candidate is already frozen.

Before the next release baseline, update/revalidate release-readiness expectations for the approved R3 migrations and capabilities, run the full regression, then perform the independent freeze review.

## Documentation kept in this cleaned source tree

- `docs/product/` — authoritative product/architecture baseline documents
- `docs/api/` — published ingestion/API contract and schemas
- `docs/operations/` — operational and recovery runbooks
- `docs/development/SDV_WORKING_AGREEMENT.md` — development/review working agreement
- `docs/development/IMPLEMENTATION_DECISIONS.md` — durable engineering decisions
- `docs/development/CURRENT_STATE.md` — current baseline/candidate state

Historical checkpoint, FIX, move-over, handoff, and continuity documents have intentionally been removed from this cleaned source package. The earlier identity checkpoints (before platform-core, task 0041) are kept as history under `docs/verification/`.


### Local demo cleanup
`Cleanup-SDV-Legacy-Demo-Records.ps1` is an opt-in local/demo helper that removes only the three exact pre-R3 `demo-contract-*` fixture submissions. It refuses automatic cleanup if those fixtures contain permanent-redaction operations. Use it only for local demo-data cleanup; it is not a product deletion API.
