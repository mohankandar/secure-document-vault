# Report 0033: Sensitivity report

Date: 2026-11-21
Branch: `feature/reports-2` (from `feature/reports-1`)
Outcome: DONE. Six choices I made inside the task are listed under "Questions for the owner". One of them is a small backend write: Vero now records the person's role on a refused document opening. Please confirm it.

Schema change (approved by the task, item 0.1): changeset **032**, one new table `steward_notices`. No existing table or row changes. It passes:
- the build: **402 tests**, 384 before;
- the schema upgrade check from 031 and from 030, with the five PostgreSQL tests;
- the clean-install check (32 changesets);
- `ui:compare` (28, including the new board);
- the screen tour (25 passed, 11 skipped).

## What was done

**0. Carry-over from report 0032**

1. **Uncorrected-copy notices are stored.**
   - Changeset 032 adds `steward_notices`. A notice holds:
     - tenant, kind (`UNCORRECTED_COPY_RECEIVED`);
     - the sending system, and the record's primary identifier with its label;
     - the audit event it came from (one notice per event);
     - the time;
     - `read_by`, which holds each person's read time.
   - Read-at per person is a JSON object in the same table (`{"<subject>": "<time>"}`), so this is one table. There is no list of stewards to write rows for: roles come from the sign-in system.
   - Intake writes the notice in the same transaction as the duplicate receipt and its audit event.
   - Uncorrected copies already in the audit become notices when 032 runs, unread by everyone.
   - `GET /api/v1/business/intake-notices` now returns `{notices, unread}`: this person's unread notices, newest first, at most 50. It no longer stops after 30 days.
   - New `POST /api/v1/business/intake-notices/read` with `{"noticeIds": [...]}`. A steward marks their own notices read; marking again keeps the first time. Each marking is audited as `STEWARD_NOTICE_READ`.
   - New codes: **SDV-INT-002** (not a notice of this tenant, 404) and **SDV-INT-003** (none named, more than 100, or not an ID, 400). Other roles still get SDV-INT-001.
   - In the app, the notice box has "Mark as read" on each line and "Mark all as read". Dismissing only hides the box until Vero is opened again.
   - The 0030 requester notices still work per browser, as you allowed.
2. **"Refusal code" CSV column:** kept. No change.
3. **"(entity)":** kept. The Sensitivity report shows the zone in the Period chip's hover text and in the period picker.
4. **The line under the Daily intake table** now reads: "Deliveries refused before Vero could identify the sending system (for example a failed sign-in) are not counted here."
   - **Are those refusals visible to Tenant IT anywhere today? No. This is a gap.**
     - A failed sign-in gets Spring Security's 401 (`SecurityConfiguration`'s entry point). Vero writes no log line and no audit event for it.
     - Malformed JSON and a missing `X-SDV-Filename` get Spring's own 400. Spring does not log these at the default level.
     - Problems lists only documents that were stored or expected (could not store, stuck, missing).
     - Integrations shows the sign-in type, not attempts.
   - A failed client sign-in may appear in the identity provider's own event log (Keycloak, if events are on). That log is outside Vero and Tenant IT can't see it in Vero.

**Reports › Sensitivity** (board `Reports-Sensitivity.dc.html`)

- **The tab and filters.**
  - The Sensitivity sub-tab is enabled. It is for the Business admin only: the same capability as the Reports tab, so every other role gets **SDV-RPT-001** (audited).
  - **Period:** This month (the default, "This month (November)"), Last month, Last 90 days, or From/To up to 366 days. All in the tenant's time zone, the same setting as Daily intake.
  - **Sending system:** All, or one.
  - Codes: RPT-002 (a wrong period), RPT-003 (an unknown sending system), RPT-004 (a wrong tenant time zone).
- **1. Documents by level.**
  - Every stored document now, at its effective level: the higher of its own level and its record's level, the rule every document path uses. Stored means received, being finished, available, or being corrected; failed, deleted and being-deleted documents are left out.
  - It is worked out in one SQL statement. Each record level above Standard is matched to its submissions the way record search matches them. A submission with another identifier value is another record. A record key that can't be read stops the report rather than give a wrong count.
  - Below the bars:
    - how many contracts carry a contract level (every record-level row);
    - how many stored documents are on an active legal hold.
- **2. Requests by kind** (download, print, lower one document, lower whole contract).
  - Requests made in the period.
  - Each is counted by what became of it, judged now:
    - **Approved**: also used, or approved and its grant ran out;
    - **Declined**;
    - **Expired**: nobody decided in time, including one still marked waiting whose time has passed;
    - **Waiting**.
- **3. Level changes** in the period, from the audit:
  - raised by a steward;
  - raised by a sending system (a delivery raising a record level, or a duplicate raising a document level);
  - lowered after approval.
- **4. Attempts to open restricted documents** in the period: refusals with SDV-SEN-008, by role, with attempts and number of people. No names. The note "Who and when is in the audit trail (filter by level)." is under the table.
  - **To count by role, the refusal must name the role.** The audit had no role. `DOCUMENT_ACCESS_REFUSED` now carries `"roles":[...]`: the sorted role names from the sign-in.
  - The roles travel in `Clearance` and are only written to the audit. Two clearances that open the same levels are still equal.
  - Refusals recorded before this change are counted as "Role not recorded".
- **Export CSV.**
  - One line per count. Columns: Section, Line, Measure, Count, From, To, Time zone, Sending system.
  - Documents-by-level lines carry today's date as their period.
  - Every cell uses the audit CSV's formula guard.
  - The export is audited as `SENSITIVITY_REPORT_EXPORTED`.
- **Shared time-zone code.** The time-zone rule (tenant setting, else the install default, else RPT-004) moved into `TenantZone`, which both reports use. Daily intake behaves as before.
- **Audit words.** The audit screen and CSV have words for `SENSITIVITY_REPORT_EXPORTED` and `STEWARD_NOTICE_READ`.
- **A fix found by the tour.** Export CSV is now disabled while a report is loading, in both sub-tabs. Before, changing a filter and exporting at once exported the previous filter.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (new and changed tests, while working) | **first run:** 3 errors. **1:** my service called `List.of().contains(null)`, which throws; fixed in the service. **2:** a Mockito stubbing order in my new controller test; fixed in that test. **Then** passed. |
| `.\Build-SDV-All.ps1` | stopped the running app first (`Stop-SDV-Local.ps1`; the database and Vault left running). **BUILD SUCCESS**: 24 + 50 + 2 + 4 + 322 = **402 tests**, 0 failures, 5 skipped (the PostgreSQL tests). Frontend build passed. Run again at the end, on the final code: same result. |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 032`) | **run 1:** `SensitivityReportPostgresTest` failed on my own test data (a sending system named "Other tenant's system" broke the SQL quote). Renamed. **Run 2:** **PASS**, 33 s. All five PostgreSQL tests passed, and the earlier uncorrected copy became one unread notice. |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 030` | **PASS**, 34 s (030, 031, 032) |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 40 s (32 changesets) |
| `Start-SDV-Local.ps1` | started. 032 was applied to the dev `sdv` database (local profile, as in 0031 and 0032). `steward_notices` has 0 rows: the dev database had no uncorrected copies. |
| `npm run typecheck`, `npm run build` | passed |
| `npm run ui:compare` | **28 passed**. New `.runtime/ui-compare/reports-sensitivity.png`. After the first look, I let the level badges fill their column as on the board, then compared the report boards again: 2 passed. |
| `npm run ui:capture` | **run 1:** 1 failure in my new step. The CSV had the previous sending system: the export race above, now fixed. The two Reports steps then passed alone. **Run 2:** **25 passed, 11 skipped**. |

## Tests: counts, and any failures with their output

New:
- `SensitivityReportServiceTest` (6):
  - **"this month" is the tenant's month:** 02:00 UTC on 1 November is still October in New York;
  - every block and the board's row order;
  - the tenant's own zone wins (Tokyo boundary);
  - RPT-002 (one day only, not a date, backwards, 367 days; 366 allowed), RPT-003, RPT-004;
  - a wrong install default stops the server;
  - an unknown request kind or outcome stops the report;
  - **CSV:** the header, 30 lines, the formula guard on a sending system named `=HYPERLINK("x")`, and the audit event;
  - the words.
- `SensitivityReportPostgresTest` (real PostgreSQL, run by the upgrade check), a seeded tenant with two sending systems and a second tenant that is never counted:
  - **documents by level:** a contract level reaching both of its deliveries; a submission with an extra identifier left out; a one-submission record; the same contract number at another sending system kept apart; deleted and failed documents left out; active and released holds;
  - **requests:** every outcome, including a waiting request whose time ran out;
  - **level changes:** sets and kept levels are not counted;
  - **attempts:** by role with people; SEN-009 not counted; an old event without roles;
  - **the New York period boundary** at both ends;
  - the sending-system filter on every block;
  - the CSV and its audit.
- `StewardNoticeServiceTest` (4):
  - each steward's own read mark, with a second mark changing nothing and not audited again;
  - INT-002 and INT-003, with nothing marked;
  - more than 50 counted in full;
  - an unknown kind is never shown.
- `RestrictedAttemptRolesTest` (3):
  - the refusal names the roles from the sign-in;
  - without roles the event is as before;
  - roles never change what a clearance opens.
- `ReportsControllerTest` (+5):
  - SDV-RPT-001 for every role except Business admin on the Sensitivity report and CSV, audited;
  - the report and CSV headers;
  - codes pass through;
  - notices and mark-read for the two steward roles only;
  - INT-002 and INT-003.
- `DocumentDuplicateIntakeTest` (+1, and one more check):
  - an uncorrected copy stores a notice made from its audit event;
  - an exact duplicate makes none.
- `IntakeReportPostgresTest`:
  - the stored notice after a real uncorrected copy;
  - read per person, first time kept, audited once;
  - another tenant's notice refused;
  - the table's checks.
- `AuditExportTest`: the two new sentences.
- The upgrade check, step 4k: the old copy becomes one unread notice; the checks refuse a bad kind, a second notice for one event, an unknown event, a non-object `read_by` and a blank identifier.
- The tour step "Reports: the Sensitivity report … (task 0033)":
  - RPT-001 for five roles; RPT-002 and RPT-003;
  - INT-001, INT-002 and INT-003;
  - **a real refused opening:** a Business user opens the seed's Highly Confidential police report and gets SDV-SEN-008; the Business user count goes up by one;
  - the four cards; this month, last month and back; one sending system;
  - the CSV;
  - no file names and no subject in the page.
- `ui:compare` board `reports-sensitivity`.

Tests changed because the behaviour changed (not to make them pass):
- `IntakeReportServiceTest`: removed the test of the old 30-day notice reading, which is gone. The stored notices are covered by `StewardNoticeServiceTest` and `IntakeReportPostgresTest`.
- `IntakeReportPostgresTest`: the notice is now read from `steward_notices`.
- The tour's 0032 step: Sensitivity is now enabled (it was asserted disabled), and it checks the new 0.4 line.

## Files changed

- **Schema:** new `032-steward-notices.sql` and `.yaml`; `db.changelog-master.yaml`.
- **Backend:**
  - new `SensitivityReportRepository`, `SensitivityReportService`, `JdbcSensitivityReportRepository`, `StewardNoticeRepository`, `StewardNoticeService`, `JdbcStewardNoticeRepository`, `TenantZone`;
  - changed `Clearance`, `DocumentAccessGuard`, `AuthorizationService`, `DocumentIntakeService`, `DocumentIntakeRepository`, `JdbcSecureIngestionRepository`, `SubmissionService` (stub), `IntakeReportService`, `IntakeReportRepository`, `JdbcIntakeReportRepository`, `IntakeNoticeController`, `ReportsController`, `AuditSentences`, `SdvErrorCode`.
- **Backend tests:**
  - new `SensitivityReportServiceTest`, `SensitivityReportPostgresTest`, `StewardNoticeServiceTest`, `RestrictedAttemptRolesTest`;
  - changed `ReportsControllerTest`, `IntakeReportServiceTest`, `IntakeReportPostgresTest`, `DocumentDuplicateIntakeTest`, `AuditExportTest`.
- **Frontend:** `Reports.tsx`, `Notices.tsx`, `liveApi.ts`, `auditWords.ts`, `r7.css`, `e2e/sdv-screen-tour.spec.ts`, `e2e/ui-compare.spec.ts`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1` (032, default `-From 032`; with `-From 032` the report indexes already exist at the previous version), `docs/api/error-codes.md`, `docs/api/README.md`, `docs/work/0033-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, task 0034.

## Remaining differences from the board

| Difference | Why |
|---|---|
| "Raised by a sending system (document or contract level)"; the board says "(contract level)" | The task's wording. A duplicate can raise a document level too |
| The account shows no role line; the Go to box; a little more space above the sub-tabs | App shell and page spacing (as in 0032) |
| The numbers (38 / 2 / 8 documents, 2 contracts, "Role not recorded" 2) | Data: the dev database |

## Commits (hash and message), or "not committed"

`ff7f2a2` feat: Reports › Sensitivity; stewards' notices stored and marked read per person; roles on refused openings (task 0033), on `feature/reports-2`. Not pushed, not merged. (This line was added in a second commit.)

## Not done, and why

- **Refusals before Vero knows the sending system are still not recorded** (item 0.4). The task asked only for the line and to report where they show. Recording failed sign-ins on the intake endpoints would be a backend change for a later task.
- **Attempts before today have no role.** Old refusal events can't be given a role afterwards. They are shown as "Role not recorded".
- **The sub-tab is not in the address.** Reloading the page opens Daily intake.

## Questions for the owner

1. **Roles on refused openings.** To count attempts by role, the refusal's audit event now names the person's roles. That is a small backend write (no schema change). Is that acceptable, or do you want the role kept elsewhere?
2. **"Raised by a sending system"** counts only raises of an existing level (`RECORD_SENSITIVITY_RAISED`, and a duplicate's document raise). A contract's first level from a delivery (`RECORD_SENSITIVITY_SET`) is not counted, because nothing changed. Count first levels above Standard too?
3. **Requests** are the ones made in the period, each counted by what became of it as of now. An approved print whose grant ran out unused counts as Approved. Is that how you read the board?
4. **Attempts to open** count every SDV-SEN-008 refusal: opening, a page, previews, text search, asking for a level change, and so on. Approval-needed refusals (SDV-SEN-009) are not counted. Keep it that way, or count only "OPEN"?
5. **The level-changes label** follows the task ("document or contract level") rather than the board ("contract level"). Which one?
6. **Read-at per person** is a JSON column inside the one approved table, not a second table. Is that fine?

## Suggested next step

Answer the questions, then task 0034.

---

## Part 2: owner answers (2026-11-21)

Date: 2026-11-21
Branch: `feature/reports-2`
Outcome: DONE. No schema change.

### What was done

1. **Roles on refused openings:** accepted. No change.
2. **First contract levels from a delivery are counted.**
   - "Set or raised by a sending system" now also counts `RECORD_SENSITIVITY_SET` from a sending system when the level is Confidential or Highly Confidential (the migration case).
   - A first level at Standard changes nothing, so it is not counted. The same goes for an event that names no level.
   - The sending-system filter works as before: the event names its sending system.
   - New label, on the screen and in the CSV: "Set or raised by a sending system (document or contract level)". The word "contract" still comes from the primary identifier, as before.
3. **Requests by what became of them as of now:** accepted. No change.
4. **Attempts to open count only the open action.**
   - Only refusals with `"attempt":"OPEN"` are counted: one per try to open a document (the viewer's open).
   - Not counted: pages, previews, text search, tags, comments, activity, print, download, redact, level-change requests, access requests, level history.
   - SDV-SEN-009 stays out.
   - All refusals since task 0026 name their attempt, so older events are counted the same way.
5. **Level-changes label:** "document or contract level", as in item 2.
6. **Read-at per person as a JSON column:** accepted. No change.

### Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (the app was not running) | **BUILD SUCCESS**: 24 + 50 + 2 + 4 + 322 = **402 tests**, 0 failures, 5 skipped (the PostgreSQL tests). Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` (runs the PostgreSQL tests) | **PASS**, 33 s. `SensitivityReportPostgresTest`: 1 run, 0 failures, 0 skipped. |
| `Start-SDV-Local.ps1` | started |
| `npm run typecheck` | passed |
| `npm run ui:compare` | **28 passed**. `reports-sensitivity` shows the new label. |
| `npm run ui:capture` | **run 1:** 24 passed, 1 failed, 11 skipped. The failure was in "Business Administrator screens", the Search fields step (task 0022 filter order): `expect(searchable[0]).toBe(moved)`, expected "Loan Type", received `undefined` (`sdv-screen-tour.spec.ts:306`). This change does not touch that screen. That test alone: 2 passed. **Run 2 (full):** **25 passed, 11 skipped**. The Sensitivity step passed in both runs. |

### Tests

No new test classes. The test count is unchanged (402).
- `SensitivityReportPostgresTest`, seeded on real PostgreSQL:
  - **Item 2:**
    - first levels from deliveries: Highly Confidential at LexaVault and Confidential at Loan Origination are counted;
    - a first level at Standard and one with no level are not;
    - one at 23:59 on 31 October in New York counts in October, not November;
    - another tenant's is never counted.
    - Expected counts: all systems 2 → **4**; LexaVault 1 → **2**; Loan Origination 1 → **2**; October 0 → **1**.
  - **Item 4:**
    - refusals with SDV-SEN-008 for PAGE, PREVIEWS, TEXT_SEARCH, CHANGE_LEVEL, REQUEST_ACCESS, PRINT and DOWNLOAD, by a person who already has open attempts and by a person with only these;
    - also one by a Restricted steward on the other sending system.
    - None is counted. Attempts, people and role rows are as before, for all systems and for each one.
- `SensitivityReportServiceTest`: the CSV line has the new label. The expected text changed because the label changed.

### Files changed

- `JdbcSensitivityReportRepository.java` (the two queries)
- `SensitivityReportRepository.java`, `SensitivityReportService.java` (label and comments)
- `Reports.tsx` (label)
- `SensitivityReportPostgresTest.java`, `SensitivityReportServiceTest.java`
- `docs/work/0033-task.md` (your Part 2), this report
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, task 0034.

### Remaining differences from the board

As in Part 1. The board's "Raised by a sending system (contract level)" now reads "Set or raised by a sending system (document or contract level)".

### Commits

`2272d07` feat: Sensitivity report counts first contract levels from deliveries and only open attempts (task 0033, part 2), on `feature/reports-2`. Not pushed, not merged. (This line was added in a second commit.)

### Not done, and why

Nothing from Part 2.

### Questions for the owner

1. The Search fields tour step failed once and passed on the next two runs. It is outside this task. Do you want a task to make that step steady?

### Suggested next step

Task 0034.
