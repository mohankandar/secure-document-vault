# Report 0053: Report: storage and retention

Date: 2026-11-23
Branch: `feature/report-storage`, from `release/vero-r7` at `c764f23` (after 0052 was merged, PR #26)
Outcome: **DONE, except the screen tour**. Built and tested; the schema check and the clean install passed; the PostgreSQL test measured the report at 250,000 documents. **The screen tour was not run**: the local schema step is refused in this session (as in 0052), so Vero can't start on the local database. Merged through the PR once `ci-passed` was green.

## What was done

A third sub-tab on the Reports tab, **Storage and retention**, for the Business Admin only. It has the same look as Daily intake and Sensitivity, and the same error helper (task 0044). Counts only: sending systems, levels, retention rules and numbers. Never content, file names, titles, record identifiers or reasons.

1. **Storage now**: stored documents, pages and bytes, by sending system and level (effective level, as the Sensitivity report: a record level raises its documents). Totals by level and overall.
2. **Growth**: the same, received per month, for the last 12 months (this month included), in the entity's time zone. Each month counts at its documents' level now. A document deleted since still counts in the month it arrived; one whose processing failed doesn't (it was never stored).
3. **Due for deletion**: per retention rule, how many stored documents reach the end of their retention within 30, 60 and 90 days. The 60 and 90 include the earlier ones. "Keep forever" shows "Never due".
4. **Held back from deletion**: past their retention now but kept, because of:
   - a legal hold,
   - a quarantine (a row in force, or an open filter incident's block),
   - or both.
   It uses the retention cleanup's own rules.
5. **CSV**: every count, one line each. It comes with `X-SDV-Export-Reference` and `X-SDV-Export-Fingerprint` (SHA-256), and the screen shows both. The export is audited as `STORAGE_REPORT_EXPORTED` (reference, fingerprint, rows), with a sentence on the Audit page.
6. **Fast at a million documents**:
   - These counts have to read every document, so they are worked out in the background (`StorageReportScheduler`, every 15 minutes, `sdv.reports.storage.count-delay-ms`) and kept in `report_storage_counts` (changeset 040, one row per entity).
   - A screen load reads only the kept counts. The screen shows when they were counted ("Counts: counted 4 minutes ago"); the tooltip gives how long the count took and how often it runs.
   - The very first load for an entity with no counts kept counts once, while it waits.
7. The effective-level SQL of the Sensitivity report is shared (`JdbcSensitivityReportRepository.effectiveLevels`), unchanged, so both reports count levels the same way.

## No other product's name or behaviour in code: yes

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken while the owner was away

1. **A summary kept up to date by a background count**, rather than counts updated on every document change: a record level change moves many documents' levels, and only a full count gets that right. The counts can be up to 15 minutes old; the screen says when they were made.
2. **The first load counts at once** when nothing is kept for the entity yet (a new install, or an entity added since the last run). This is a one-off; later loads read the kept counts only.
3. **A retention rule** is the retention length each document was given when it arrived (`retention_days_snapshot`). Its name is the most recent retention policy of that length ("One year (365 days)"), or "Kept 30 days" when no policy had that length. Documents keep the length they were given, so the length is the rule.
4. **Growth counts every document received in the month**, deleted since or not, at its level now. "Added per month" means what arrived.
5. **No new `RPT` code**: no new refusal was needed. Everyone but the Business Admin gets SDV-RPT-001, as for the other reports.
6. **Reference and fingerprint headers** are added for this report's CSV only. The Daily intake and Sensitivity exports are unchanged (they send the row count only).
7. **A "Refresh" button** reloads the kept counts. It doesn't start a new count, so a busy screen can't make Vero read the documents again and again.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final, after every change) | PASS: backend 662 tests, 0 failures, 0 errors, 18 skipped (the PostgreSQL-only tests; 0052: 657/17); frontend build OK |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 58 tests (55 before, 3 new) |
| `npx playwright test --list` | The new tour step is listed (the file compiles) |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 040`) | PASS in 336 s; step 4r passed; step 4f passed every PostgreSQL test, `StorageReportPostgresTest` included |
| Earlier runs of the schema check | FAIL three times, each a mistake in the new test or the script, fixed before the passing run: the jar was missing after a `mvn clean`; the "no other table changed" check didn't know the new table; the test's seed broke two database checks (a deleted document needs its deletion time and reason; one current retention policy per entity) |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 127 s |
| Local schema step and `npm run ui:capture` | **Not run**: the local schema step is refused in this session (see 0052) |


## Tests: counts, and any failures with their output

- **New `StorageReportServiceTest` (3):**
  - the first load counts once, later loads read the kept counts, and the 12 months start in the entity's time zone;
  - each section: storage by system and level with totals, growth months (empty months at zero), due per rule with names (keep forever shows none), held back;
  - the CSV lines, reference, fingerprint and audit.
- **New `StorageReportPostgresTest` (PostgreSQL; CI and schema check step 4f):**
  - exact counts on a small entity: a record level raises a document; failed and deleted documents are not stored; growth by month; due within 30, 60 and 90 days per named rule; keep forever; held back by a hold, a quarantine row, both, and a filter's block; the other entity never counted; no file name in the CSV; the export audited with its fingerprint;
  - at scale: 250,000 documents. The background count, then the report from the kept counts within the agreed 1,000 ms (median of 3 after a warm-up), and the CSV within 1,000 ms.
- **`ReportsControllerTest`:** every role but the Business Admin refused (SDV-RPT-001) for the report and the CSV; the Business Admin gets both, with the reference, fingerprint and row headers.
- **`AuditExportTest`:** the new audit sentence.
- **Frontend `storageWords.test.ts` (3):** bytes in words, rule names, months and "counted … ago".
- **Tour:** a new step, "Reports: storage and retention" (role check, the four sections, the CSV's fingerprint against the saved file) with screenshot `reports-storage`. **Not run** (see "Not done").

**Measured** (`StorageReportPostgresTest`, 250,000 documents in one entity, this machine, scratch database). Agreed: 1,000 ms for a screen's API, set before the run.

| Measure | ms |
|---|---:|
| Seed (test set-up) | 23,898 |
| Background count (reads every document; not on a screen) | 549 |
| Report (API, from the kept counts; median of 3 after a warm-up) | 57 |
| CSV export (API) | 72 |


## Files changed

- **Backend:**
  - New: `040-report-storage.sql` and `.yaml`, `StorageReportRepository` (port), `StorageReportService`, `JdbcStorageReportRepository`, `StorageReportScheduler`.
  - Changed: `db.changelog-master.yaml`, `JdbcSensitivityReportRepository` (the shared level SQL), `ReportsController`, `AuditSentences`.
- **Backend tests:**
  - New: `StorageReportServiceTest`, `StorageReportPostgresTest`.
  - Changed: `ReportsControllerTest`, `AuditExportTest`.
- **Frontend:** `Reports.tsx`, `liveApi.ts`, `auditWords.ts`, new `storageWords.ts` and its test, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and CI:**
  - `Test-SDV-SchemaUpgrade.ps1`: default `-From 040`, step 4r, the new test, `-ReportScale`.
  - `.github/workflows/ci.yml`: the new test must run.
- **Docs:** this report and the task.

## Commits (hash and message), or "not committed"

See the PR: one commit, "feat(reports): storage and retention report (task 0053)".

## Not done, and why

- **The screen tour was not run.** The local schema step is refused by this session's automatic permission check (0052 has the details). Without 039 and 040 applied, Vero answers SDV-SYS-001 on the local database. The owner applies them, then runs `npm run ui:capture`; the new step is ready.
- No local run at 1,000,000 documents: the task asks for a PostgreSQL measurement, and 0052 already measured this machine at that size. The report's own time doesn't depend on the number of documents (it reads one kept row).

## Questions for the owner

None blocking. Is 15 minutes the right age for these counts?

## Suggested next step

Merge after `ci-passed`; then the owner runs the local schema step and the tour.
