# Report 0080: erase log and retention (integration F)

Date: 2026-11-25
Branch: `integration/f-erase-log` (from `release/vero-r7` at fd3079e; 0930df6 merged in)
Outcome: **DONE**

## What was done

1. **BOM 1.1.1**, `platform-erase-log-spring`, the `erl_` run (version 3). The schema step takes DELETE on `erl_position` from the app user.
2. **One way to erase:** `ErasePort`. Handlers `document`, `sealed-original`, `replaced-version`, and platform-core's `case-fields` (with the audit
   event). Each checks (legal hold, quarantine, claimed state), erases, verifies. The cleanup, the sealed-original job and redaction use it.
3. **A record's values** go with its last document, never while a delivery of it is still open. A delivery that waits for that erasure starts a new record.
4. **Work gate:** requests other than health get `503 SDV-ERS-001`; all jobs wait; readiness 503 with the log's code. Vero goes on by
   itself within seconds of the cause going away. A pending entry shows on Health (number, kind, IDs, code).
5. **The log:** `SDV_ERASE_LOG_FOLDER` (required), keyed; the start refuses a document volume (`SDV-ERS-002`), and a new volume there is refused.
6. Runbooks ("The erase log", "Restore"), guides, error codes, release notes; the recovery manifest tools know the log (manifest version 3).

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 42 + 72 + 2 + 723 + 14 = 853 tests, 0 failures, 57 skipped (they need a database or key service; run below) |
| `npm test`, `npm run typecheck` | 84 passed; clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 151 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS, 654 s; all PostgreSQL tests, 4 of them new |
| `.\Test-SDV-DemoSeed.ps1 -WithTour -WithRestore` | full tour 33 passed, 19 skipped (desktop-only steps on the tablet), ui:compare 28 passed; the run then failed on the new proof's own count (the tour had erased a sealed original through the log, so there was one entry more). The count starts at the dump now; run again with the step of task 0066: PASS |
| Recovery manifest tools | same and grown log accepted; changed, shorter and another log refused |

**The restore proof** (`-WithRestore`, scratch databases, the jar itself): dump; a redaction; the cleanup erases a document and its record's values
(three keyed entries); the dump and the old files restored; Vero paused, nothing answers but health; `--erase-replay` ends with 0; Vero
starts; document, key, values and both files are gone. Then the dump restored once more **under the running Vero**: noticed, replay, start.

**Failed on the way:** the schema check failed three times: its list of platform runs lacked the new one; platform-core's handler codes allow no
hyphens, so Vero's code became `ERASE_HANDLER_ERROR` (found by `EraseLogPostgresTest`; Vero gives `SDV_ERS_003` there and shows `SDV-ERS-003`); two
mistakes in the new test. Clean install failed once: the filter asked for the log before the licence check; it asks at the first request now.

## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `SchemaVersionTest`, both schema scripts | The erase log's run |
| `HealthGroupsTest`, `VeroMonitoringTest` | The new readiness check and codes |
| `RetentionLegalHoldTest`, hold and quarantine tests, `HeldRedactionPostgresTest`, `DocumentLevelEnforcementTest` | New constructors; assertions unchanged |
| `SenderProfileServerContractTest` | Settings only: the log of its database |

## Decisions taken

- **A seal is no erasure:** the old file of a redaction on hold becomes the sealed original and is not logged; a replay would destroy what a hold
  keeps. Files that never became a document's (a failed upload or staging) are not logged either.
- **A restored original of a document redacted later is erased whole:** its redacted copy isn't in the backup. Audit event
  `DOCUMENT_ERASED_AFTER_RESTORE`. A sealed original the database doesn't know yet is treated the same way.
- The log names a rule, never a person; its audit event says who. A replay writes an event the restored database lacks, under the same ID.
- The replay doesn't look at holds. A hold on a document already erased keeps no values.
- A hold placed after a replaced version was logged but couldn't be finished doesn't stop that entry: it was decided before.
- Sealed originals wait for quarantine too (they waited for holds only).
- A volume whose folder isn't there is a failure, no longer "file gone".
- Not in the overall health address (task 0039); readiness has it. A position that can't be read is answered as the database away (`SDV-SYS-014`).
- `/api/v1/system/info` is paused too: the task says only health.
- The local log is `%USERPROFILE%\.vero\erase-log`; the check scripts, the testers' start and the owner-test script have their own.

## Not done, and why

- No test for a delivery that waits while its record's values are erased (it needs two connections at the right moment); the normal and the
  concurrent deliveries are tested.
- The `sdv` database is untouched. A local start on `sdv_vero` needs the schema step, which `Start-SDV-OwnerTest.ps1` runs.
- `RecordKey.java` and `FieldAdoption.java` still wait for the owner's OK to be deleted (report 0078).

## Questions for the owner

None.

## Suggested next step

0081.
