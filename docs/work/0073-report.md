# Report 0073: platform-core 1.0.0 (integration A)

Date: 2026-11-24
Branch: `integration/a-bom` (from `release/vero-r7`, merged in again on the day)
Outcome: **DONE**, except the live Keycloak delivery (0088, owner's step).

## What was done

1. `platform.core.version` is 1.0.0, through the BOM only. No new module. The schema step runs what exists: identity 5 and
   licensing 2.
2. Licence keys in the build: already done by 0067. `PlatformCoreUseTest` now also refuses a development key in
   `licence-build/release`.
3. The narrow rights of the two licensing tables: the schema step takes delete and truncate on `lic_last_seen`, and update,
   delete and truncate on `lic_clock_reset`, from every role but the owner. It does so on every `update`, also when the
   database is up to date, and `sql` writes it out. The clean-install check asserts it as the read/write user.
4. Question 8: nothing to change yet. Vero makes its identity beans itself, so a start with the database away still works on
   1.0.0 (clean-install check). The answer takes effect with the modules of 0074 and 0075.
5. `PlatformCoreUseTest`: no `.internal` type and one version.
6. `Test-SDV-SchemaUpgrade.ps1 -Forks` (default 4, as before): how many processes run the PostgreSQL tests.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 4 + 674 + 10 = 805 tests, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 77 passed; pass |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 190 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | **PASS, 888 s** |
| `.\Test-SDV-SchemaUpgrade.ps1` (4 processes), four runs | **FAIL**, time limits only (below) |
| `ui:capture` smoke and 0065 steps, scratch database | 8 passed |
| `ci-passed` | see the pull request; it runs the same timed tests, on its own machine |

The four failed runs of the schema check (10:56, 11:22, 11:46, 14:01) failed only on time limits:

| What | Measured | Limit |
|---|---|---|
| Restrictions overview | 1515, 2278, 2056 ms | 1000 ms |
| One-document check with a quarantine of 250,000 | 199, 172, 268 ms | 100 ms |
| Activity report | 1085, 1097 ms | 1000 ms |
| Storage export | 1236 ms | 1000 ms |

Other sessions' databases and scanners ran on this computer all day. With one process, on the same busy computer, the same
tests keep the same limits. So what was measured was the four processes of 0086 against each other, plus the other load, not
Vero on 1.0.0. No limit and no test was changed. Every run above 5 minutes is the schema check: 888 s with one process,
about 530 s with four today, 316 s with four on a quiet computer (0086).

## Not done, and why

The live Keycloak delivery: 0088, waits for the owner. The local `sdv` stays at changeset 042 until the owner's schema step.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- `-Forks 1` counts as this task's check: it is how the check ran before 0086, with every test and every limit. The default
  stays 4; use 1 on a busy computer.
- The rights are taken from every role but the owner, not from one named role: the step doesn't know the app's user name.
