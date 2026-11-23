# Report 0054: Report: activity

Date: 2026-11-23
Branch: `feature/report-activity`, from `release/vero-r7` at `c686d27` (after 0053 was merged, PR #27)
Outcome: **DONE, except the screen tour**. Built and tested; the schema check and the clean install passed; the PostgreSQL test measured a busy month at 10,000 agents. **The screen tour was not run**: the local schema step is refused in this session (as in 0052 and 0053). Merged through the PR once `ci-passed` was green.

## What was done

A fourth sub-tab on the Reports tab, **Activity**, for the Business Admin only. It has the same look and the same error helper (task 0044), and it is built from the audit trail.

1. **The period**: the last 7 days by default, in the entity's days (time zone) as the other reports. It offers the last 30 days, this month, or From/To up to 92 days (SDV-RPT-002 otherwise).
2. **Opened, printed and downloaded**, by role and by level, with a total per kind.
3. **Opens refused**, by role: for the level (SDV-SEN-008) and for a quarantine (SDV-QUA-001). A refused print or download isn't an open.
4. **Busiest people**: the 20 with the most opens. They are named by sign-in name as on the Audit page (the name recorded, else the sign-in ID), with the roles they opened with and their opens by level.
5. **Highly Confidential approvals**: print and download requests made, approved, declined and expired undecided; one-time grants used and given back. A lowering request isn't one of these.
6. **Counts only**: no content, file names, titles, record identifiers or reasons. People appear only in the busiest list, by sign-in name.
7. **CSV**: every count, with `X-SDV-Export-Reference` and `X-SDV-Export-Fingerprint` (SHA-256); the screen shows both. It is audited as `ACTIVITY_REPORT_EXPORTED`, with a sentence on the Audit page.
8. **Roles are now recorded on opens, prints and downloads.** `DOCUMENT_VIEW_OPENED`, `DOCUMENT_PRINTED` and `DOCUMENT_DOWNLOADED` carry the person's role names (`AuditRoles`, sorted, fixed names only), as refusals have since task 0033. Events from before show as "Role not recorded".
9. **Fast for a busy month** (changeset 041):
   - Each closed day (10 minutes after it ends, in the entity's time zone) is counted once from the audit trail and kept in `report_activity_counts` (by kind, roles and level) and `report_activity_people` (opens per person a day), with `report_activity_days` marking which days are kept and in which time zone.
   - `ActivityReportScheduler` keeps them in the background (every 10 minutes, up to 14 days a run, the last 92 days).
   - A report reads the kept days and only the rest of the period (today) from the audit trail. A day not kept yet is kept when a report first needs it.

## No other product's name or behaviour in code: yes

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken while the owner was away

1. **Roles are recorded from now on**, not guessed for older events. People's roles live in the sign-in token, not in Vero, so older opens, prints and downloads count as "Role not recorded".
2. **Legal hold refusals**: a legal hold never refuses an open (it only stops deletion), so the refusals have two columns, level and quarantine. The screen says so.
3. **"Highly Confidential approvals"** are the print and download requests (only Highly Confidential documents need them) and their one-time grants, from the audit trail.
4. **Days are kept 10 minutes after they end.** An event written later than that for an earlier day (a transaction open for over 10 minutes) isn't counted. If the entity's time zone changes, its days are counted again in the new zone.
5. **At most 92 days** per report, so a report never needs more than a quarter's kept days.
6. **The busiest list counts opens only**, as the task says. Prints and downloads are in the first section.
7. **No new `RPT` code**: SDV-RPT-001 (role) and SDV-RPT-002 (period) cover the refusals.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final) | PASS: backend 672 tests, 0 failures, 0 errors, 19 skipped (the PostgreSQL-only tests; 0053: 662/18); frontend build OK |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 61 tests (58 before, 3 new) |
| `npx playwright test --list` | The new tour step is listed (the file compiles) |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 041`, final) | PASS in 460 s; step 4s passed; step 4f passed every PostgreSQL test, `ActivityReportPostgresTest` included |
| Earlier runs of the schema check | FAIL six times, fixed one by one before the passing run. Mistakes in the new test: a wrong outcome name; a word check that matched "Level"; an entity left on a time zone, which broke `IntakeReportPostgresTest`; a seed that subtracted days in New York time and moved 702 opens across the 1 November clock change. One real problem: the month took 1,198 ms against the agreed 1,000 ms. I fixed its cause, not the limit: the kept counts are now a few rows a day, and the busiest people one row a person a day (`report_activity_people`) |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 111 s |
| CI on the PR, first push | FAIL: `ActivityReportPostgresTest`, "the month took 1494 ms (agreed: 1000 ms)". CI's runner is slower than this machine (760 ms here then). I fixed the cause, not the limit, in a second commit. Today's part of the audit trail is now read once, not twice (the totals and the busiest list share it), and its roles are taken as recorded (the JSON text) and tidied in Java, not unpacked event by event in SQL. Here: 760 → 324 ms for 31 days, 528 → 209 ms for 7 days |
| Local schema step and `npm run ui:capture` | **Not run**: the local schema step is refused in this session (see 0052) |


## Tests: counts, and any failures with their output

- **New `ActivityReportServiceTest` (6):**
  - the last 7 days by default, in New York days: the closed days kept once, today from the audit trail;
  - a day is kept only after it has settled;
  - each section;
  - the period checks (SDV-RPT-002);
  - today's part joins the kept days by role name (however the roles were recorded), and today's people go to the busiest list;
  - the CSV with its reference, fingerprint and audit.
- **New `AuditRolesTest` (2):** role names only, sorted, once; nothing when there is no role.
- **New `ActivityReportPostgresTest` (PostgreSQL; CI and schema check step 4f):**
  - exact counts on a small entity in New York: opens by role and level, an old open without roles, prints, downloads;
  - refusals for the level and for a quarantine; a refused print and a failed open don't count;
  - the busiest people; requests, approvals, declines and grants, a lowering request excluded; another entity never counted;
  - yesterday from the kept day, today from the audit trail; the CSV and its audit;
  - a busy month: 10,000 agents, 5 opens each a day for 30 days, plus today (1,510,000 audit events). The days are kept in the background, then the report for 31 days and for the last 7 days, each within the agreed 1,000 ms (median of 3 after a warm-up).
- **`ReportsControllerTest`:** every role but the Business Admin refused for the report and the CSV; the Business Admin gets both with the reference and fingerprint headers.
- **`AuditExportTest`:** the new audit sentence.
- **Frontend `activityWords.test.ts` (3):** period words, role words, the export note.
- **Tour:** a new step, "Reports: activity" (role check, the four sections, the CSV's fingerprint against the saved file), screenshot `reports-activity`. **Not run.**

**Measured** (`ActivityReportPostgresTest`, this machine, scratch database). 10,000 agents, 5 opens each a day for 30 days, plus 10,000 opens today: 1,510,000 audit events. Agreed: 1,000 ms for a report's API, set before the first run.

| Measure | ms |
|---|---:|
| Seed (test set-up) | 50,873 |
| Keeping the 30 closed days (background, once) | 73,007 |
| Report, 31 days (API; median of 3 after a warm-up) | 324 |
| Report, the last 7 days (API) | 209 |

Most of each report's time is today's part, read from the audit trail (10,000 events here). A busier today costs more until the day is kept, 10 minutes after midnight.


## Files changed

- **Backend:**
  - New: `041-report-activity.sql` and `.yaml` (three tables: kept counts, kept people, kept days), `ActivityReportRepository` (port), `ActivityReportService`, `JdbcActivityReportRepository`, `ActivityReportScheduler`, `AuditRoles`.
  - Changed: `db.changelog-master.yaml`, `ReportsController`, `AuditSentences`, `DocumentViewerService`, `DocumentActionService`, `ControlledDownloadService` (the roles).
- **Backend tests:**
  - New: `ActivityReportServiceTest`, `ActivityReportPostgresTest`, `AuditRolesTest`.
  - Changed: `ReportsControllerTest`, `AuditExportTest`.
- **Frontend:** `Reports.tsx`, `liveApi.ts`, `auditWords.ts`, new `activityWords.test.ts`, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and CI:**
  - `Test-SDV-SchemaUpgrade.ps1`: default `-From 041`, step 4s, the new test, `-ActivityPerDay`.
  - `.github/workflows/ci.yml`: the new test must run.
- **Docs:** this report and the task.

## Commits (hash and message), or "not committed"

See the PR: two commits, "feat(reports): activity report (task 0054)" and "perf(reports): read today's audit part once (task 0054)".

## Not done, and why

- **The screen tour was not run** (the local schema step is refused in this session; see 0052). The new step is ready.

## Questions for the owner

None blocking. Should prints and downloads also rank people, or only opens as now?

## Suggested next step

Merge after `ci-passed`; the owner runs the local schema step (039, 040, 041) and the tour.
