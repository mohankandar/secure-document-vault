# Report 0042: Vero CI (GitHub Actions)

Date: 2026-11-22
Branch: `ci/github-actions` (from `release/vero-r7` at `1c9442c`), PR #18
Outcome: DONE (merged once CI on the PR is green; see "Commits")

## What was done

- **Preconditions checked:** `GITHUB_PACKAGES_TOKEN` is set (length 93; value not printed). `~/.m2/settings.xml` has server `github-platform-core`, and its password reads `${env.GITHUB_PACKAGES_TOKEN}`. Repository secret `PLATFORM_PACKAGES_TOKEN` exists (name only checked). `platform-core-v0.2.0` release notes read (read only).
- **Identity coordinates (no Java change):** `backend/pom.xml` imports `com.documentplatform.platform:platform-bom:0.2.0` (property `platform.core.version`) and has the repository `github-platform-core` (`https://maven.pkg.github.com/mohankandar/platform-core`, releases only). `sdv-persistence` uses `platform-identity-core`, `sdv-security` uses `platform-identity-spring` (versions from the BOM). All 8 identity classes Vero imports are in v0.2.0 in the same packages. No identity schema run was needed: Vero does not use `platform-identity-jdbc`, so V3 is not touched (that is 0041).
- **CI** `.github/workflows/ci.yml`, on push and pull request to `release/vero-r7` and `main` (and by hand):
  - `backend`: Java 21. PostgreSQL 16 service container on port 5433 with database `sdv_schema_test` (trust authentication, so there is no password at all). Steps: build the jar, run the schema step (`update-sdv-schema.sh update`, 34 changesets), then `mvn clean verify` with `SDV_IT_JDBC_*` set, then a check that all 7 PostgreSQL-only test classes ran and none were skipped.
  - `frontend`: Node 22, `npm ci`, `npm run typecheck`, `npm run build`.
  - `product-name-guard`: runs `ProductNameGuardTest` on its own.
  - `sender-profile-contract`: an empty job for task 0041 to fill.
  - `ci-passed`: succeeds only if every job above succeeded. A failed, cancelled or skipped job fails it. This is the one check branch protection needs.
- `.github/maven-settings.xml`: server `github-platform-core`, password `${env.GITHUB_PACKAGES_TOKEN}`. The workflow puts the secret `PLATFORM_PACKAGES_TOKEN` into that variable. No token in any file.
- **Product-name guard:** `backend/sdv-web/src/test/java/com/sdv/platform/web/ProductNameGuardTest.java`.
  - Names: `src/test/resources/product-name-guard/names.txt` (today: the LexaVault name). Matched in any case, as a whole word. `platform-core` is allowed.
  - Scope: code, SQL, JSON, YAML, XML, properties and scripts anywhere (test and demo data included), plus install guides (`docs/install/`, `docs/operations/*INSTALL*.md`). Not in scope: build output, `node_modules`, `.runtime`, and the prose and mock-ups under `docs/design` and `docs/work`.
  - Exceptions: only in `product-name-guard/exceptions.txt`, each with a reason. A listed file that no longer holds the name also fails the guard, so the list can only get shorter.
  - Proved: with an empty exceptions list it failed and named all 20 files. With the list filled in, it passes.
- **Docs:** `docs/install/PLATFORM_PACKAGES.md` covers the one-time token and `settings.xml` setup, the CI secret, and renewal when the token expires. README "Build" points to it and describes CI. The `Build-SDV-All.ps1` banner now names platform-core (the old line said Enterprise Identity 0.2.0).

## Commands run and results

| Command | Result |
|---|---|
| Token length / `settings.xml` server check (PowerShell, value never printed) | set (93), `github-platform-core` present, reads the env variable |
| `mvn -o -pl sdv-web -am test -Dtest=ProductNameGuardTest` (empty exceptions) | failed as intended, 20 files listed |
| `.\stop-local.cmd` | app stopped (sdv-postgres and sdv-vault left running) |
| `.\Build-SDV-All.ps1` | BUILD SUCCESS, backend and frontend |
| `.\Start-SDV-Local.ps1` | started, Vault preflight PASS |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` | **refused by the session's permission check**: the owner runs it |
| `npm run ui:capture` | 25 passed, 11 skipped (same as reports 0044 and 0045) |
| `gh pr create` | PR #18 into `release/vero-r7` |
| CI run 45090904816 (commit `c42bf41`) | all 5 jobs green |

## Tests: counts, and any failures with their output

- Local full build: 583 tests (24 + 50 + 2 + 4 + 503), 0 failures, 8 skipped (the PostgreSQL-only tests, as before). The 3 new guard tests are included.
- CI: 583 tests, 0 failures, **0 skipped**. All 7 PostgreSQL-only classes ran: LegalHold, AccessRequest, DuplicateIntake, IntakeReport, SensitivityReport, SignInConnection (1 test each) and UnidentifiedIntakeAttempt (2 tests).
- Screen tour: 25 passed, 11 skipped, 0 failed.
- No test was changed to make it pass.

## Files changed

- New: `.github/workflows/ci.yml`, `.github/maven-settings.xml`, `ProductNameGuardTest.java`, `product-name-guard/names.txt`, `product-name-guard/exceptions.txt`, `docs/install/PLATFORM_PACKAGES.md`, this report.
- Changed: `backend/pom.xml`, `backend/sdv-persistence/pom.xml`, `backend/sdv-security/pom.xml`, `README.md`, `Build-SDV-All.ps1` (two banner lines).
- Not touched: `Start-SDV-Local.ps1` (local changes left as they are), `docs/work/0041-task.md` (not committed).

## Commits (hash and message), or "not committed"

- `6d9a12a` docs(work): task 0042, Vero CI (GitHub Actions)
- `c42bf41` ci: GitHub Actions CI, identity from platform-core 0.2.0, product-name guard (task 0042)
- This report: the next commit on the branch. PR #18 is merged with a merge commit only after CI on that commit is green.

## No other product's name or behaviour in code

Not yet. The guard allows these files, each listed with the reason "fixed in 0041":

- Demo sender name: `Seed-SDV-Canvas-Demo.ps1`, `Seed-SDV-Demo-Records.ps1`, `Test-SDV-DemoSeed.ps1`, six backend tests (`ProblemsAndAuditRecordsTest`, `IntakeReportPostgresTest`, `IntakeReportServiceTest`, `SensitivityReportPostgresTest`, `SensitivityReportServiceTest`, `StewardNoticeServiceTest`), `e2e/sdv-screen-tour.spec.ts`, `e2e/ui-compare.spec.ts`.
- Comments: `TopBar.tsx`, `AccountMenu.tsx` ("so … can reuse it"), `RoleSeparationPolicyTest.java`.
- Messages: `Start-SDV-WSL.sh`, `Stop-SDV-WSL.sh`.
- Older independence checks that spell the name (this guard replaces them): `Test-SDV-BusinessExperience-A.ps1`, `Test-SDV-EnterpriseReadiness-A-Units19-20.ps1`, `Test-SDV-EnterpriseReadiness-D-Unit23.ps1`, `Test-SDV-V1-Release-Readiness-Unit25.ps1`.

No other product's behaviour was added. Platform-core is used only as a library.

## GitHub settings for the owner

**1. Branch protection for `release/vero-r7`.** Settings → Branches → Add branch protection rule. Branch name pattern: `release/vero-r7`.
- [x] **Require a pull request before merging**
  - Required approvals: **0**. GitHub does not let you approve your own PR, so with 1 you could never merge. You still decide every merge yourself.
  - [x] Dismiss stale pull request approvals when new commits are pushed (optional)
- [x] **Require status checks to pass before merging**
  - [x] Require branches to be up to date before merging
  - Required check: **`ci-passed`** (source: GitHub Actions). Add only this one: it already covers backend, frontend, product-name-guard and sender-profile-contract.
- [x] **Do not allow bypassing the above settings.** Without this, the admin (you) can still push directly.
- [ ] Allow force pushes: **off**
- [ ] Allow deletions: **off**

Do the same for `main` when CI is to guard it. The workflow already runs on `main`.

**2. Package access in platform-core.**
- **Needed now: nothing.** CI reads the packages with your token (secret `PLATFORM_PACKAGES_TOKEN`). The token owner owns the packages, so the job's own `GITHUB_TOKEN` is not used.
- If you ever switch CI to `GITHUB_TOKEN`: in each package's settings in platform-core (Package settings → Manage Actions access → Add repository), give `mohankandar/secure-document-vault` the role **Read**. Today that is `platform-core` (the parent POM), `platform-bom`, `platform-identity-core` and `platform-identity-spring`, and each package 0041 adds later.

**3. The token expires.** When it does, CI and local builds fail with `401` from `maven.pkg.github.com`. Renew it in **both** places: the laptop variable `GITHUB_PACKAGES_TOKEN` and the repository secret (`gh secret set PLATFORM_PACKAGES_TOKEN`). See `docs/install/PLATFORM_PACKAGES.md`.

## Not done, and why

- `.\Seed-SDV-Canvas-Demo.ps1 -Reset`: refused by the session's permission check. **The owner runs it.** The screen tour ran on the existing data.
- The frontend unit tests (`npm test`, 19 tests) are not in CI: the task lists `npm ci`, typecheck and build only.
- CI warns that `actions/checkout@v4`, `setup-java@v4`, `setup-node@v4` and `upload-artifact@v4` use the deprecated Node 20. Moving to v5 is a dependency upgrade, so I'm asking first (below).

## Questions for the owner

1. May CI also run `npm test` (frontend unit tests)?
2. May I move the Actions to their current major versions (`@v5`) to clear the Node 20 warnings?

## Suggested next step

Set the branch protection above. Run `.\Seed-SDV-Canvas-Demo.ps1 -Reset`. Then task 0041, which fills the `sender-profile-contract` job and empties `exceptions.txt`.
