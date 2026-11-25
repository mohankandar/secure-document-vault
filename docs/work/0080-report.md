# Report 0080: erase log and retention (integration F)

Date: 2026-11-25
Branch: `integration/f-erase-log` (from `release/vero-r7` at fd3079e), not pushed
Outcome: **PARTLY DONE**, paused by the design chat (0095 first). Not ready to merge: the schema check fails (below).

## Where it stands

Built: BOM 1.1.1; `platform-erase-log-spring`; the `erl_` run and the narrowed rights; `ErasePort` and its four handlers; cleanup, sealed
originals and redaction through it; a record's values with its last document; `EraseGateFilter` and `WorkGate`; readiness and Health;
the folder check; recovery manifest tools (schema 3); the restore proof (`Test-SDV-DemoSeed.ps1 -WithRestore`); codes, guides, runbooks.

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 42 + 72 + 2 + 723 + 14 tests, 0 failures, 57 skipped; before the last change to `EraseGateFilter` (its tests pass) |
| `npm test`, `npm run typecheck` | 84 passed; clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 151 s |
| `.\Test-SDV-DemoSeed.ps1 -WithRestore` | PASS: dump, erasures, restored dump refused, replay, start, nothing readable; a restore under a running Vero noticed |
| Recovery manifest tools | same and grown log accepted; changed, shorter and another log refused |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | **FAIL**: `EraseLogPostgresTest` 4 of 4, `SenderProfileServerContractTest` 1; the script stopped there |
| Tour (`ui:capture`) | not run |

## To do when it goes on

1. **Cause of the failures:** platform-core's `EraseHandlerException(String, String)` refuses a code with hyphens (`SDV-ERS-003`):
   `IllegalArgumentException`, so a failing file shows `ERASE_HANDLER_ERROR`. Use the constructor with a `PlatformCode` in
   `DocumentContent.failed()` and `refused()`, and see what `handlerCode()` then says (`ErasePort.EraseFailed.refused()` compares it).
2. `SenderProfileServerContractTest` got `SDV-ERS-001`. Probably the same database has an entry of the failed test pending; see after 1.
   If not: its Vero starts with a clock or log of its own state; look at the log's code.
3. Schema check, full build, tour; then this report in full, with the decisions below.

## Decisions taken so far

- A seal is no erasure: the old file of a redaction on legal hold is moved under its sealed ID and is not logged. A replay of it would
  destroy what a hold keeps.
- A restored database that has the original of a document redacted later: the replay erases the whole document; its redacted copy isn't
  in the backup. A sealed original the database doesn't know is treated the same way.
- The log names a rule, never a person (an actor ID may look personal); the audit event it names says who.
- The replay doesn't look at holds: what was erased is erased again. `PlatformRecordValues.held` ignores holds on erased documents.
- Not in the overall health address: the database or a share away must not make it DOWN (task 0039); readiness has it.
- A position that can't be read is answered as the database being away (`SDV-SYS-014`), as before.
- The local log is `%USERPROFILE%\.vero\erase-log`; the check scripts have their own.
