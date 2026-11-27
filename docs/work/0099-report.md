# Report 0099: platform-core 1.3.0 (from 1.1.1), before 0082

Date: 2026-11-27
Branch: `chore/platform-core-1.3.0` (from `release/vero-r7` at 2a21384)
Outcome: **DONE**

## What was done

1. **BOM 1.3.0.** Vero compiled and its tests passed unchanged on it. The schema step now runs identity V6 (`idn_login_connection.test_sign_in_version`)
   and fields V3 (`fld_field_definition.option_names`); the runbook, the IT guide and the release notes say to **stop every instance first**.
2. **Login connections (LV-23).** Vero's sign-in screen changes nothing (owner answer 6 in 0041: setup changes the connection), so there is no
   replacement on it to add a token field to (Q-2, not blocking, option A). `IDN_TEST_SIGN_IN_REQUIRED` gets plain words on the Sign-in tab
   (`codeHint`), and the IT guide names `setup identity rollback-login-connection`.
3. **Workarounds removed:** the development build's check (any development key among the build's keys) is now `LicenceBuild.isDevelopment()`
   (`PlatformIdentityConfiguration`); the demo step's check of the licence's key ID against the development keys is now
   `LicenceBuild.isDevelopmentLicence(licence)` (`DemoStep`). Not found in Vero, so nothing to remove: a page read one record at a time (Vero
   already reads a page with `readSensitive(List)`, which is one query since 1.2.0), handling of conflicting keys, its own licence warning days.
   **Kept:** `JoinedDataSource`, which puts platform-fields' work in Vero's transaction. 1.3.0's `FieldValues.in(Connection)` replaces it, but
   with savepoints and per-transaction objects: another behaviour, which the tests here don't show is the same. Its own task.
4. **Teams and Slack through outbound:** `platform.notifications.webhook.via-outbound=true` in `application.yml`; they follow
   `platform.outbound.*` (address guard, proxy, private CA, https only); a refused address shows `WEBHOOK_OUTBOUND_REFUSED` on IT ›
   Notifications' health. Mail as before.
5. **0082:** the design chat's note at the end of `docs/work/0082-task.md` (use `Approvals.decidedElsewhere`, LV-24.5) is committed here; it says
   what item 5 asks.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 42 + 72 + 2 + 739 + 14 = 869 tests, 0 failures, 57 skipped (database or key service; run below); 118 s |
| `npm test`, `npm run typecheck` | 101 passed (1 new); clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 158 s; identity V6 and fields V3 applied; the app starts with Teams and Slack through outbound |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS, 841 s (over 5 minutes: the scale tests, as before); all PostgreSQL tests. Failed once on the way: `PlatformIdentityPostgresTest` still expected identity 5 |

## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `SchemaVersionTest`, `PlatformIdentityPostgresTest`, both schema scripts | identity 6, fields 3 |

## Decisions taken

- **Q-2, option A:** the screen stays read-only with its test; setup changes the sign-in. A draft and replace flow on the screen would be a
  feature of its own.
- `JoinedDataSource` kept (above).
- The four new thresholds of "licence expiring" (60, 30, 7, 1 days) are platform-core's defaults; Vero's notification screens don't show
  thresholds, so they apply as they are.

## Files changed

`backend/pom.xml` (BOM), `application.yml`, `PlatformIdentityConfiguration`, `DemoStep`, the Sign-in tab and its words and test,
`SchemaVersionTest`, `PlatformIdentityPostgresTest`, both schema scripts, IT guide, runbook, release notes, `docs/work/0082-task.md` (the design chat's note), this report.

## Commits

See the PR.

## Not done, and why

- `ui:capture`: the Vault is still sealed (owner's step, Q-1); the only screen change is one line of words on the Sign-in tab, under a code
  nobody can reach from the screen. The tour runs at the start of 0082 once the owner has unsealed.

## Questions for the owner

None.

## Suggested next step

0082 (approvals from messages), with the tour left open by 0081.
