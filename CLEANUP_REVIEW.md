# SDV Pre-GitHub Cleanup Review

Prepared from the uploaded source package before GitHub publication.

## What was removed

- `.runtime/` — generated runtime PIDs, logs, and test evidence.
- `.idea/` — local IntelliJ workspace/database metadata.
- `.vscode/` — local editor settings; not required to build or run SDV.
- `Query.sql` — ad-hoc local diagnostic SQL, including a direct status update that should not ship as source.
- Historical root-level checkpoint/FIX/handoff markdown and changed-file manifests already superseded by the maintained `docs/` tree.

## What was intentionally kept

- All backend and frontend source.
- Liquibase migrations 000 through 016.
- Local build/start/stop scripts.
- Unit 0–25 regression/release-readiness scripts.
- Demo seed/cleanup scripts.
- Product, API, operations, development, and verification documentation under `docs/`.
- Enterprise Identity Checkpoint C verification document under `docs/verification/`.
- Example OIDC environment template (`frontend/sdv-ui/.env.oidc-demo.example`).

## Consistency corrections

- Root `README.md` and `docs/development/CURRENT_STATE.md` incorrectly said the latest Liquibase migration was 012 even though migrations 013–016 are present and included by `db.changelog-master.yaml`. Both are corrected to 016.
- `CURRENT_STATE.md` now records Enterprise Identity R1 Checkpoint C as the verified/frozen subsystem represented by the current code while keeping the broader Business Workspace R3 release status as a candidate pending its full release gate.

## Git hygiene

`.gitignore` was expanded to cover Maven/Gradle output, Node/Vite output, local runtime folders, performance/release-readiness evidence, IDE state, package archives, local storage roots, logs, and environment-secret overrides while explicitly allowing committed environment templates.

## Source completeness check

The uploaded SDV source contains the expected Business Workspace workflow, redesigned secure PDF viewer, Business Administration, Tenant IT Administration, Platform Operations, audit views, migrations through 016, and Enterprise Identity activation/rollback/recovery implementation. No product source files were removed during cleanup.

This package is SDV source (`com.sdv.platform`, backend 0.1.0-SNAPSHOT). It should not be presented as the separate LexaVault repository. If LexaVault is also going to GitHub, review and clean that repository independently.
