# Report 0094: platform-core 1.1.0, batch opening

Date: 2026-11-25
Branch: `chore/platform-core-1.1.0` (from `release/vero-r7` at 12d5ab5)
Outcome: **DONE**

## What was done

1. **BOM 1.1.0**, the newest released 1.1.x on 2026-11-25 (1.1.1 exists only as rc.1). No schema change, no new dependency.
2. **A results page is opened with `FieldValues.readSensitive(list)`.** `VaultBatchUnwrap`, its settings, the interface `BatchUnwrap` and the
   one-page `KeyService` are removed. Vero calls the key service directly nowhere for opening.
3. **Behaviour kept:** one key-service call per results page (counted); names only for roles that may open the level; a record that can't be
   opened shows its identifier; a list without the key service goes on without names; `503 SDV-KEY-002` as before. platform-fields
   closes the opened keys; Vero holds none.
4. **A page is at most `KeyService.MAX_BATCH` (100).** The test opens 100 in one call, then shows that 101 is refused before any call and
   that a search with a page size over the limit (50) is refused.
5. The entry in `PLATFORM-ISSUES.md` is marked resolved.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1`, on the final code | 42 + 72 + 2 + 715 + 14 = 845 tests, 0 failures, 53 skipped (they need a database or key service; run below) |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1`, on the final code | PASS, 737 s; all PostgreSQL tests |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 162 s |
| `.\Test-SDV-DemoSeed.ps1 -WithTour -TourSteps …` (scratch database and the scratch Vault) | PASS; 24 steps passed, 2 skipped, desktop |
| A page of 50 records opened by the scratch Vault (a test for this task only, see below) | passed |

Product code is the same in every run; only test code and the script's test count changed between them. Search times are as in 0079
(a page with names opened: 156 ms, limit 3,000).

**The schema check failed three times before it passed**, none of them from product code:

| Run | Failure | Cause |
|---|---|---|
| 1 | `PlatformRecordValuesPostgresTest`: batch of 2 expected, was 1 | My expectation. Another customer's envelope is refused before the key service is asked; the test now says so |
| 1 | `SenderProfileServerContractTest`: health `OUTAGE` | My wrapper passed the scratch Vault's settings on to it; without them it passes |
| 2 | `NoClassDefFound PlatformFieldRepository` | The test's class in `target` had an unresolved type; probably the editor's Java compiler wrote over Maven's output after an edit. Gone after a Maven build |
| 3 | All tests passed, the script failed | It expects a fixed number of tests (15) |

## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `PlatformRecordValuesPostgresTest` | Two tests more; the stand-in for the batch is gone and the key service's own batch call is counted |
| `RecordSearchScalePostgresTest` | The stand-in for the batch is gone; same limits |
| `VaultBatchUnwrapTest` | Removed with the class it tested |
| `Test-SDV-SchemaUpgrade.ps1` | Expects 17 tests in `PlatformRecordValuesPostgresTest` (15) |

## Decisions taken

- 1.1.0 and not a candidate: only released versions are used.
- The real-Vault test of 0078 has no successor in the repository: the schema check accepts no skipped test, and CI has no Vault. It passed once
  here; the seed check's tour opens pages against the scratch Vault.
- A record without any values is not logged as a failure (0078 left it out quietly as well).

## Not done, and why

Nothing left out. `RecordKey.java` and `FieldAdoption.java` still wait for the owner's OK to be deleted (report 0078).

## Questions for the owner

None.

## Suggested next step

0080, when platform-core 1.1.1 is released.
