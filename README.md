# Secure Document Vault (SDV)

Secure Document Vault is an independent secure document custody, business search, PDF viewing, controlled document-action, and activity-history product.

## Current status

The repository contains the previously verified/frozen **SDV V1 Units 0–25** baseline plus the active **Business Workspace R3** modernization candidate.

**R3 is not yet re-frozen.** It adds post-V1 runtime behavior and must complete local build/runtime regression and the final release-readiness review before a new release baseline is declared.

Current latest Liquibase migration: **016**.

R3 includes:

- Business workflow tabs: **Search | Results | Document Viewer | Activity**
- source-driven searchable metadata plus common Date Range / Last Updated filters
- record-centric results and AVAILABLE-document selection
- capability-controlled Download, Print, Add Tag, Add Comment, and Permanent Redaction viewer actions
- Business Administrator role-capability overrides for business roles
- record-centric Activity with document-view/read events hidden by default
- desktop and iPad/tablet responsive layouts

SDV remains independent from LexaVault. LexaVault may act only as an external producer through SDV's published APIs.

## Local ports

| Service | Port |
|---|---:|
| SDV backend | 8280 |
| SDV frontend | 5183 |
| SDV PostgreSQL | 5433 |
| Local Vault dev service (when requested) | 8200 |

## Technology baseline

- Java 21
- Spring Boot 3.5.x
- Maven multi-module backend
- PostgreSQL 16 + Liquibase
- React + TypeScript + Vite
- Vault Transit behind `KeyProtectionProvider`
- pluggable storage provider abstraction
- structured/correlation-aware logging

## Build

From the repository root on Windows PowerShell:

```powershell
.\build-all.cmd
```

## Start local environment

```powershell
.\Start-SDV-Local.ps1 -WithVault
```

Expected endpoints:

- Backend health: `http://localhost:8280/actuator/health`
- System info: `http://localhost:8280/api/v1/system/info`
- Frontend: `http://localhost:5183`

Stop with:

```powershell
.\Stop-SDV-Local.ps1
```

## Current UI/R3 preflight

```powershell
.\Test-SDV-UI-ADMIN-R2.ps1 -SkipBuild
```

The filename is retained for compatibility with the current development workflow; its checks now cover the R3 Business Workspace and admin UI requirements.

## Demo data for Search / Results / Viewer / Activity validation

With the local backend and Vault running:

```powershell
.\Seed-SDV-Demo-Records.ps1
```

The seed uses the normal ingestion APIs and is intended for local validation only.

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

Historical checkpoint, FIX, move-over, handoff, and continuity documents have intentionally been removed from this cleaned source package. Enterprise Identity Checkpoint C evidence is retained under `docs/verification/`.


### Local demo cleanup
`Cleanup-SDV-Legacy-Demo-Records.ps1` is an opt-in local/demo helper that removes only the three exact pre-R3 `demo-contract-*` fixture submissions. It refuses automatic cleanup if those fixtures contain permanent-redaction operations. Use it only for local demo-data cleanup; it is not a product deletion API.
