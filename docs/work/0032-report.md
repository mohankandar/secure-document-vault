# Report 0032: Reports tab and the Daily intake report

Date: 2026-11-20 (finished just after midnight, 21 November)
Branch: `feature/reports-1` (from `feature/intake-duplicates`)
Outcome: DONE. Three choices I made inside what the task allows are listed under "Questions for the owner" for you to confirm or change.

Schema changes (approved by the task): changeset **030** (one nullable column `tenants.time_zone` and its check) and changeset **031** (three indexes, no table or row change). These pass:
- the build: **384 tests**, 362 before;
- the schema upgrade check from 029 and from 028, with the four PostgreSQL tests;
- the clean-install check (31 changesets);
- `ui:compare` (27, including the new board);
- the screen tour (24 passed, 10 skipped).

## What was done

**0. A corrected document sent again (carry-over from report 0031)**
- **Where the old checksum is.** A finished correction keeps no row of its own: the pending redaction row is deleted. The checksum a document had before correction survives only in its audit event `DOCUMENT_PERMANENTLY_REDACTED` (`oldContentSha256`). So that is where intake looks. Every earlier correction of a document counts, and corrections made before this task are covered too.
  - This is the server change you approved, and it is one index: `idx_audit_events_redaction_old_sha` (changeset 031), on that event only.
- **What intake does.** If an incoming file matches no current document, Vero looks for a document whose checksum before correction matches it.
  - The scope is the same as task 0031: tenant, sending system, the record's submissions, not deleted or being deleted. It runs under the same lock.
  - If one is found, it is a duplicate of the corrected document:
    - nothing is stored, and the temporary encrypted object is deleted;
    - the answer is `200`, `DUPLICATE_ALREADY_STORED`, with the corrected document's ID;
    - a row goes into `document_duplicate_receipts`, holding the arrival's checksum, so a retry gets the same answer and the report counts it;
    - the manifest slot and the "2 of 4" count are filled as in 0031.
  - It is audited as `DOCUMENT_UNCORRECTED_COPY_RECEIVED` instead of `DOCUMENT_DUPLICATE_RECEIVED`. The event also holds the corrected checksum, and no file name or contract number.
  - A higher level raises the corrected document, as for any duplicate.
- **Notice for stewards.** New `GET /api/v1/business/intake-notices` gives Document and Restricted stewards the last 30 days of these notices. Each names:
  - the record's primary identifier;
  - the sending system;
  - the time;
  - and says "An uncorrected copy of a corrected document was sent again; nothing was stored".

  Other roles get SDV-INT-001 (audited). The app shows each notice once per person and browser, like the 0030 requester notices.
- **Docs.** `docs/api/README.md` describes the case.

**Reports tab** (board `Reports-Intake.dc.html`; new `Reports.tsx`)
1. **The tab.**
   - Business administration gains Reports as its last tab. The heading reads "Counts only. No document contents, titles or names."
   - It has two sub-tabs, Daily intake and Sensitivity. Sensitivity is shown disabled until task 0033.
   - New capability `VIEW_INTAKE_REPORTS` is held by the Business admin only. Every other role gets **SDV-RPT-001** (audited as `AUTHORIZATION_DENIED`). The role-matrix test has the new row.
2. **Counts only.** The server never selects a file name, title, other metadata or content. A line shows at most:
   - the request ID;
   - the time;
   - the primary identifier;
   - counts.
3. **Time zone.** There was no tenant time-zone setting.
   - **030** adds `tenants.time_zone`. It is null for every tenant now, which means the install default applies. The default is the new setting `sdv.tenant-defaults.time-zone` (`SDV_DEFAULT_TIME_ZONE`, UTC when unset; `America/New_York` in the local profile, as on the board).
   - The report shows the zone read-only: "Eastern time (entity)". Hovering it gives the zone's name and where the setting comes from.
   - Nothing is guessed:
     - a wrong install default stops the server;
     - a wrong tenant value refuses the report with **SDV-RPT-004**.
4. **Filters.**
   - **Day:** Today, Yesterday, Last 7 days, or From/To, up to 31 days. Anything else gets **SDV-RPT-002**.
   - **Sending system:** All, or one. An unknown one gets **SDV-RPT-003**.
5. **Totals:**
   - deliveries and the number of sending systems;
   - documents accepted, their pages, and the exact duplicates (0031's, plus uncorrected copies);
   - contracts: distinct primary-identifier values, split into new (no earlier submission carried the value, from any sending system) and updated;
   - refused at intake, with codes;
   - failed after receipt (`PROCESSING_FAILED`).

   The word "Contracts" comes from the identifier's display name ("Contract number" gives Contracts, "Patient ID" gives Patients).
6. **Breakdown by sending system.**
   - Each sending system is a group with its own total row; the first is open and the others are collapsed, as on the board.
   - Each delivery line has: request ID (shortened in the middle, in full on hover), time, primary identifier, documents, pages, duplicates, refused (with code), failed.
   - A group shows at most 50 lines, then "… N more deliveries · shown in full in the CSV".
7. **Overall total.** A contract sent by two sending systems counts once.
8. **CSV.** Export CSV gives one line per document. The columns are:
   - SHA-256 checksum;
   - request ID;
   - sending system;
   - primary identifier;
   - pages;
   - received in the tenant's time zone and in UTC;
   - outcome (`ACCEPTED`, `DUPLICATE_ALREADY_STORED`, `REFUSED`, `FAILED`);
   - refusal code.

   It uses the audit CSV's formula guard: a cell starting with `= + - @`, tab or carriage return is escaped. The export is audited as `INTAKE_REPORT_EXPORTED`.
9. **Refusals before a document exists.** Intake refusals were not recorded anywhere before this task. Now every refusal on the two intake endpoints (create submission, upload) is recorded as `INTAKE_REFUSED`. That means every 4xx once the sending system is known, including a refused submission, where no submission row exists.
   - The event holds: stage, sending system, submission, the request ID as sent, the upload's own ID, and the code the sender got. It never holds the message or a file name.
   - Failures (5xx) are not recorded.
   - The report lists such a delivery with its request ID, time, sending system and code.
10. **Error codes:**
    - new SDV-RPT-001 to 005 (005: more than 200,000 lines in one range);
    - new SDV-INT-001;
    - all listed in `docs/api/error-codes.md`.

    The audit screen has words for the three new events, which are listed under Deliveries and Exports.

**What a delivery is.** One submission, under the ID its sending system gave it (`sourceSubmissionId`). It is listed on each day it was created or received something. Its time is the first of those in the range, and its counts are what arrived in the range.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (new and changed tests, while working) | passed |
| `.\Build-SDV-All.ps1` | three tries. **1:** the running app held the jar open, so I stopped it with `Stop-SDV-Local.ps1`; the database container was left running. **2:** 1 failure in my new audit sentence: "refused (null)" when there is no code. Fixed. **3:** **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 304 = **384 tests**, 0 failures, 4 skipped (the PostgreSQL tests). Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 030`) | four failed runs, all in my own new test code. **1:** the script's fingerprint check still expected tenants. **2:** my test's fake ciphertext checksum. **3:** a missing storage volume, so I removed that step; the lookups prove the scope. **4 and 5:** two wrong counts that I had worked out by hand in the test (19 Nov has 2 contracts, not 1; the range has 6 accepted documents, not 7). The code was right each time. Then **PASS**, 32 s. |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 029` | **PASS**, 33 s (029, 030 and 031) |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 46 s (31 changesets) |
| `Start-SDV-Local.ps1` | started. 030 and 031 were applied to the dev `sdv` database (local profile, as in 0031). All three indexes are valid and no tenant has a zone set. |
| `npm run typecheck`, `npm run build` | passed |
| `npm run ui:compare` | **27 passed**. New `.runtime/ui-compare/reports-intake.png`. |
| `npm run ui:capture` | **run 1:** 1 failure, my new step's "Sending system" locator matched two elements; fixed. **Run 2:** 1 failure in the existing Search fields check ("Business Administrator screens": the first field in Filters wasn't the one just moved). That code is unchanged by this task; the test passed in run 1 and twice alone. **Run 3:** **24 passed, 10 skipped**. |

## Tests: counts, and any failures with their output

New:
- `IntakeReportServiceTest` (9):
  - **A seeded day with every outcome** (2 sending systems, America/New_York):
    - accepted, duplicate, failed and refused;
    - a refusal before any submission;
    - one contract from both systems, counted once;
    - new and updated.
  - **The time-zone day boundary:**
    - 04:59:59 UTC on the 20th falls on the 19th in New York;
    - 04:59 UTC on the 21st falls on the 20th;
    - the tenant's own setting beats the install default;
    - "today" at 02:00 UTC is still the 20th.
  - **One sending system, and a range.**
  - **50 lines and "3 more".**
  - **CSV:** the exact header, the lines, the formula guard (`=HYPERLINK("x")`), both times, outcome and code, the audit event.
  - **Every refusal code;** a wrong install default stops the server.
  - **Too many lines** gives SDV-RPT-005.
  - **The notice text.**
- `ReportsControllerTest` (4):
  - every role except the Business admin gets SDV-RPT-001 for the report and the CSV, audited, and the report is never read;
  - the CSV's headers;
  - codes pass through with their status;
  - notices are for the two steward roles only (SDV-INT-001).
- `IntakeRefusalsTest` (5):
  - a refused delivery is recorded with its request ID and code, without metadata;
  - a refused upload is recorded with no file name;
  - uncoded refusals are recorded with the code the sender got: PDF_TOO_LARGE, BAD_REQUEST, NOT_FOUND, CONFLICT, and a record level on an upload;
  - a 5xx is not recorded;
  - the answer is unchanged when recording fails.
- `DocumentDuplicateIntakeTest` (+3):
  - an uncorrected copy is not stored, is answered with the corrected document, its row holds the arrival's checksum, and it gets its own audit event;
  - a higher level raises the corrected document;
  - an exact duplicate is found first, and a corrected document being deleted no longer counts.
- `IntakeReportPostgresTest` (real PostgreSQL, run by the upgrade check):
  - the 030 column and its check; the 031 indexes are valid;
  - **section 0 end to end:**
    - the original after correction is not stored;
    - the corrected document is unchanged;
    - the duplicate row, the audit event and the stewards' notice are written;
    - the report and CSV count it as a duplicate;
    - another sending system, record or tenant isn't matched;
  - **the report's SQL on a seeded day:**
    - the tenant time zone;
    - a refusal naming another system's submission isn't attributed to it;
    - another tenant isn't counted;
    - new and updated;
    - the range;
    - the CSV and its audit.
- Tour, new step "Reports … (task 0032)":
  - SDV-RPT-001 for five roles;
  - RPT-002, RPT-003 and INT-001;
  - **a real refused delivery** sent through the intake API raises the refused and delivery counts by one, and appears in the CSV with SDV-SEN-002;
  - the screen: heading, disabled Sensitivity tab, zone, the KPI, group open and close, one sending system, Yesterday and back to Today;
  - Export CSV downloads and checks the header;
  - no `.pdf` anywhere.
- `ui:compare` board `reports-intake`.

Changed tests:
- `FinalRoleMatrixTest`: the new capability.
- `AuditExportTest`: the new sentences.
- The tour's Business admin tab list gains Reports.
- `Test-SDV-SchemaUpgrade.ps1`: 030 and 031, `-From 030` and `-From 031`. Tenants are compared on their old columns.

No existing test was changed to make it pass.

## Files changed

- **Schema:** new `030-tenant-time-zone.sql/.yaml`, `031-intake-report-indexes.sql/.yaml`; `db.changelog-master.yaml`.
- **Backend:**
  - new `IntakeReportRepository`, `IntakeReportService`, `JdbcIntakeReportRepository`, `ReportsController`, `IntakeNoticeController`, `IntakeRefusals`;
  - changed `DocumentIntakeService`, `DocumentIntakeRepository`, `JdbcSecureIngestionRepository`, `SubmissionService` (stub), `DocumentIntakeController`, `SubmissionController`, `FoundationExceptionHandler`, `AuditSentences`, `AuditCsv` (cell is public), `SdvErrorCode`, `Capability`, `RoleCapabilities`, `application.yml`, `application-local.yml`.
- **Backend tests:**
  - new `IntakeReportServiceTest`, `IntakeReportPostgresTest`, `ReportsControllerTest`, `IntakeRefusalsTest`;
  - changed `DocumentDuplicateIntakeTest`, `AuditExportTest`, `FinalRoleMatrixTest`.
- **Frontend:**
  - new `Reports.tsx`;
  - changed `Governance.tsx`, `Notices.tsx`, `Shell.tsx`, `liveApi.ts`, `model.ts`, `store.tsx`, `auditWords.ts`, `r7.css`, `e2e/sdv-screen-tour.spec.ts`, `e2e/ui-compare.spec.ts`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1`, `docs/api/error-codes.md`, `docs/api/README.md`, `docs/work/0032-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, tasks 0033 and 0034.

## Remaining differences from the board

| Difference | Why |
|---|---|
| The account shows no role line; the Go to box | App shell (0018) |
| 6 deliveries from 1 sending system, 0 duplicates, refusals or failures | Data: today's real intake in the dev database (the board's 486 is an example) |
| Approvals shows 53 | Data (the seed) |
| A little more space between the filter line and the cards | App page spacing (16 px against the board's 14 px) |

## Commits (hash and message), or "not committed"

See the commit on `feature/reports-1`. Not pushed, not merged.

## Not done, and why

- **Refusals before the controller runs aren't counted.** That covers malformed JSON, a missing `X-SDV-Filename` header and a failed sign-in. Spring answers these before Vero knows the sending system's request, and a failed sign-in has no known sending system.
- **The time zone can't be changed in the app.** The task asks for it read-only. Tenant IT can set `tenants.time_zone` in the database, or `SDV_DEFAULT_TIME_ZONE` for the install.
- **The tour doesn't trigger an uncorrected copy against the dev database.** That needs a corrected document and an upload. The full path runs on PostgreSQL in `IntakeReportPostgresTest`. The tour's only write is one refused delivery: one `INTAKE_REFUSED` audit event per run, with nothing stored.

## Questions for the owner

1. **The stewards' notice comes from the audit event**, with no table of its own. The approval for section 0 covered "an index or column", so I added no notices table. Each person is told once per browser, like the 0030 requester notices. Is that enough, or do you want a stored notice with "read" per person (one small table)?
2. **The refusal code has its own CSV column** ("Refusal code"), after Outcome, rather than inside it (for example "REFUSED SDV-SEN-001"). That keeps Outcome to the four values for programs that read the file. Keep it, or merge them?
3. **The zone label reads "(entity)"**, as on the board ("Eastern time (entity)"). Is "entity" the word you want there, or "(tenant)"?

## Suggested next step

Answer the three questions, then task 0033 (the Sensitivity sub-tab).
