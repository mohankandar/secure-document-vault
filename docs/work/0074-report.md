# Report 0074: monitoring through platform-core (integration B)

Date: 2026-11-24
Branch: `integration/b-monitoring` (from `release/vero-r7` after 0073)
Outcome: built and proven locally. Merges on green `ci-passed`.

## What was done

1. **Checks.** `platform-errors` and `platform-monitoring-spring` (BOM). Vero's own: database version, free connections, storage,
   unidentified intake attempts, key service, sign-in keys; none of them can stop a start. `database` is the module's own; the
   login-system and licence checks are the module's classes, made in Vero (below).
2. **Readiness** keeps its meaning and stays live: `readinessState` is Spring's own again, the module's required checks don't
   count in it. The list is on `/actuator/health/dependencies`, details for IT Admin and Platform operations only.
3. **References** are platform-core's `R-…`, made before sign-in, the same in the answer, `X-Correlation-Id`, the body, the log
   and the audit. A caller never chooses it; the caller's own value is kept beside it as before.
4. **Screens:** the list and **Download diagnostic bundle** on Platform operations, Health and IT, Problems; audited
   (`DIAGNOSTIC_BUNDLE_DOWNLOADED`) before the file leaves. A test puts secrets, names and titles where the bundle looks and
   finds none in it.
5. **Gone:** the sign-in keys' own thread and polling on every probe (now one check, every 10 s).
6. Question 8: a start with the database away, or with the platform's tables missing, stops with code and fix.
7. New codes `SDV-SYS-020`, `SDV-STO-009` (checks only, never answers). Error codes, runbook, IT guide, clean-install check.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 4 + 680 + 10 = 811 tests, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 79 passed; pass |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 176 s (database away at the start and later, sign-in keys away and back, live) |
| `.\Test-SDV-SchemaUpgrade.ps1` | not run: no changeset and no platform schema version (speed rules); CI runs the same tests |
| `npm run ui:smoke`, scratch database | 7 passed, with the new list, bundle, refusals and health details in the IT and Operations steps |
| Busy, live, pool of 1 | readiness 503 BUSY with `Retry-After` 5, liveness 200, ready again after |
| `ci-passed` | green on the branch; see "CI" |

First runs that failed: the clean install once (my login-system check read the licence while Vero started; it now reads when it
runs) and the type check once (a wrong callout tone).

## Tests whose assertions changed

- `ErrorPlumbingTest`: the reference is a platform reference, no longer a UUID.
- `SignInKeysHealthIndicatorTest`: same outcomes, through the check; the host is in the reason; "answers within the wait" became
  "a check that hangs is down, and readiness never waits".
- `Test-SDV-CleanInstall.ps1`: steps 1, 5a and 5b, for question 8.

## CI

From about 19:30 to 20:45 UTC on 2026-11-24 CI failed on every branch before any test: GitHub Packages answered 403 for
platform-core. Re-running the failed jobs passed later; nothing was changed for it. If it comes back: the token
`PLATFORM_PACKAGES_TOKEN` and the Packages quota.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- `platform.monitoring.startup-checks` is on (answer 8). So a start on an **empty** database stops too; before, Vero served its
  health and said "older". When only Vero's own tables are behind, it still does that.
- Login-system and licence checks are made in Vero: the module wants the customer's ID in a setting (Vero has it in the setup
  tables) and a licence bean (0075).
- The checks list is for the whole installation, so storage shows counts only.
- Unidentified intake attempts count from 10 an hour; Problems still lists every one.
- `sdv.` settings are not in the bundle (plan: leave out when unsure).
- Readiness notices lost or returned sign-in keys within about 15 seconds; before, at the next probe.
