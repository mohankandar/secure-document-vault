# Report 0011: Problems and Audit in plain words

Branch `feature/admin-4`, taken from `feature/admin-3`. Part C's backend additions and migration 020 were approved with the task. No other schema change and no new dependency. **The CSV export (Part B.6) was first stopped for a question. The owner chose option (a), and it is now built: see Follow-up at the end.**

## What changed

**Part A: Problems**
- **Tabs** (`store.tsx`):
  - Platform operations: Overview, **Problems**, Storage, Platform health, Audit, Configuration. "Intake" and "Incidents" are merged into Problems, and their old addresses (`#/operations/intake`, `#/operations/incidents`) open Problems.
  - Tenant IT gets **Problems** between Integrations and Environment. It is read-only, and Tenant IT has no Audit tab.
  - Health/About naming is left for 0012.
- **Page** (`src/r7/Problems.tsx`, new, shared by both roles):
  - A one-line note that nothing is fixed here.
  - Filter chips with counts: All, Could not store, Stuck, Missing documents.
  - Rows: Record (number and record title), What happened, From (sending system) and Since.
  - **Detail:** What happened, What it means, What you can do, and a note for the team that sends it, with a **Copy note** button. The note holds the record number, file name, what happened and what to send again; never the title or any other personal data. Codes and IDs are under folded **Technical details**.
  - The text about producer APIs and "retry finalization" is gone, and there are no retry buttons.
- **Sentences** are in one file, `src/r7/problemWords.ts`.

**Part B: Audit** (`src/r7/AuditLog.tsx`, new; sentences in `src/r7/auditWords.ts`)
- **Rows** read as sentences, with columns When, What happened, Record and Result (Done / Refused / Failed).
- **Everyday filters:**
  - **Activity groups:** Opening and reading, Downloads and prints, Changes to documents, Settings changes, Deliveries, Refused.
  - **Person:** people seen in the loaded events, filtered on the server.
  - **Period:** Today, Last 7 days, Last 30 days (the default), or dates without time boxes.
  - **Record number.**
- **More filters:** action code, actor type, actor ID, target type, target ID, correlation ID and result.
- **How the activity groups work:**
  - Refused is filtered on the server (outcome DENIED).
  - The other groups are applied to the loaded events, the same way routine events were already hidden. The server filters by one action code only, and Part C names no change to that.
  - When a group shows nothing, the page looks up to 4 pages further back by itself, and "Load older events" goes further.
- **Routine events:** "Include view events" works as before (audit views and Recently-viewed reads hidden), and document opens stay visible.
- **Detail:** the sentence, when, result and record, then folded **Technical details** with codes, IDs and the safe details.
- **Old code:** the Audit/Incidents/Intake code in `Operations.tsx` is no longer reached but is still in the file, to keep this change small. It can be removed in 0012.

**Part C: backend**
- **C.1, problems carry the record:**
  - `ProblemRecordResolver` (new) gives, for each problem's submission:
    - the first identifier in display order, with its label;
    - the record title, as in 0009;
    - the sending system's name.
  - All lookups stay in the problem's tenant.
  - The operations summary has a new `problemRecords` map. Failure and stuck rows now carry `originalFilename`.
  - If the lookup fails, or a record can't be resolved, the problem is still listed, without a title.
  - Files: `OperationsService`, `OperationalRepository`, `JdbcOperationalRepository`.
- **C.2, names in the audit: migration 020** (`020-audit-actor-display-name.sql`/`.yaml`)
  - Adds `audit_events.actor_display_name varchar(300) null`, with no default and no backfill. Existing rows are untouched. It ran on the development database.
  - `AuditEvent` has a new optional field, and the old 11-value constructor still works, so the 21 places that write events are unchanged.
  - **`SessionNamedAuditEventPort`** (new, `@Primary`) adds the name from the signed-in session:
    - a person's name claim;
    - a sending system's display name.
  - It names only an event whose actor is the signed-in person or system in the same tenant. Background events and events about someone else stay unnamed. If the name lookup fails, the event is still written.
  - Page events (task 0007) are named too, through `SessionNamedPageViewAuditPort` and `JdbcPageViewAuditRepository`.
  - **Local mode has no name claim,** so local events show the subject (for example `local-document-steward`), like older events. With a real sign-in system, people's names show.
- **C.3, the audit list carries the record:** for document events, the record number and file name are looked up when the list is read, within the tenant (`AuditQueryPort.findRows`, `AuditQueryService.AuditEntry`). The permission is the same as before.
- **C.4, record-number filter:** `recordNumber` on `GET /api/v1/operations/audit-events`. It matches document events whose record has that identifier value, typed or stored.

**Demo data (local only):** `Seed-SDV-Demo-Records.ps1 -ProblemDemo` sends three records through the API, then sets one state on each directly in the local database. It refuses to run against anything but localhost.

| Record | Problem |
|---|---|
| 300000000094 | `appraisal.pdf` could not be stored, after 3 attempts |
| 300000000095 | `title-deed.pdf` still being stored after 2 days |
| 300000000096 | 1 of 2 documents received, last activity a week ago |

I ran it once. 300000000090 isn't listed under Problems: it is too recent to pass the "missing documents" attention time.

## Problem sentences (`problemWords.ts`)

The server writes only these three processing error codes (`DocumentFinalizationService.classify`):

| Code | In the list | Cause in the detail |
|---|---|---|
| `DOCUMENT_FINALIZATION_FAILED` | Vero couldn't finish storing the file | Vero could not finish storing and protecting the file. |
| `DURABLE_OBJECT_VALIDATION_FAILED` | The stored copy couldn't be read back | Vero wrote the file, but could not read a complete copy back from storage, so it will not show it. |
| `STORAGE_SECURITY_VALIDATION_FAILED` | The stored copy failed the security check | The stored copy did not pass Vero's security check, so Vero will not show it. |
| any other | Vero couldn't store this file (code X). | (same) |

- **Stuck:** "Still being stored after 2 days" (processing), "A permanent correction is still running after…" (redaction), "Still being deleted after…" (retention).
- **Missing:** "1 of 2 received".
- **Each kind** has its own "What happened / What it means / What you can do" sentences in the same file.
- **The design's "password-protected PDF" is not a code here.** The server refuses such PDFs when they arrive, so they never become a stored problem.

## Audit sentences (`auditWords.ts`)

"{who}" is the name, or the subject for older events ("Vero" for system work). "{file}" is the file name, or "a document".

| Action | Sentence |
|---|---|
| DOCUMENT_VIEW_OPENED | {who} opened {file} |
| DOCUMENT_PAGE_VIEWED | {who} loaded page N of {file} |
| DOCUMENT_DOWNLOADED / _FAILED | {who} downloaded {file} / tried to download {file}, but it failed |
| DOCUMENT_PRINTED | {who} printed {file} |
| DOCUMENT_TAG_ADDED / COMMENT_ADDED | {who} tagged {file} / commented on {file} |
| DOCUMENT_PERMANENTLY_REDACTED / REDACTION_FAILED | {who} permanently redacted part of {file} / tried to…, but it failed |
| DOCUMENT_RETENTION_DELETED / _DELETE_FAILED | Vero deleted {file} for good, as the keeping rule says / couldn't delete {file} when its time was up |
| BUSINESS_RECORD_OPENED / BUSINESS_SEARCH | {who} opened a record / searched records and found N |
| BUSINESS_RECENT_RECORDS_LISTED / AUDIT_VIEWED | {who} looked at Recently viewed / looked at the audit |
| AUTHORIZATION_DENIED | {who} tried to {download a document, change search fields, …} without permission |
| SUBMISSION_CREATED / DOCUMENT_RECEIVED | {who} delivered N documents / {who} sent {file} |
| DOCUMENT_AVAILABLE / DOCUMENT_PROCESSING_FAILED | {file} is stored and ready to open / Vero couldn't store {file} |
| ROLE_CAPABILITIES_UPDATED | {who} changed what business roles may do |
| RETENTION_POLICY_UPDATED | {who} changed how long to keep new documents to 7 years (or: keep them forever) |
| METADATA_BEHAVIOR_REVISED / DISPLAY_ORDER_UPDATED / RECORD_TITLE_FIELDS_UPDATED | {who} changed how a search field works / the order of the search fields / what records are called |
| METADATA_FIELD_CREATED / _REVISED, SOURCE_FIELD_DEFINED / _REVISED | {who} added / changed a field a sending system sends |
| INGESTION_SOURCE_CREATED / _UPDATED | {who} added / changed a sending system |
| STORAGE_VOLUME_CREATED / _UPDATED / STORAGE_FOLDER_CHECKED | {who} added / changed a storage volume / checked a folder for a new volume (; Vero couldn't write there) |
| ENTERPRISE_IDENTITY_* (6 codes) | {who} saved a draft of / tested / switched on / went back to earlier sign-in settings (…, but it failed) |
| anything else | {who} · {humanized code} |

## Tests

- **Backend:**
  - `SessionNamedAuditEventPortTest` (5):
    - a person's name is added from the session;
    - a sending system's display name is added;
    - no name is added for someone else, another tenant, system work, a missing name claim or no session;
    - a failed lookup still writes the event;
    - Spring uses the marked constructor.
  - `ProblemsAndAuditRecordsTest` (4):
    - the record number, title and sending system;
    - tenant isolation;
    - an unresolved record still appears, without a title, and a failing lookup doesn't drop problems;
    - the audit list carries name, record and file name, older unnamed events still list, and the record filter reaches the query.
  - The 0007 page-event SQL test still passes with the new column.
- **Tour:**
  - **Problems** for both roles:
    - each filter shows its demo record with its sentence and sending system;
    - the detail shows the three blocks;
    - Copy note puts the note on the clipboard (checked on desktop);
    - the note has no title or name;
    - there are no codes or retry text;
    - the old Incidents address opens Problems.
  - **Tenant IT** has no Audit tab.
  - **Audit:**
    - first it makes a fresh download, open, refusal and unchanged settings save through the API;
    - then it checks the open, download, refused, settings and delivery sentences by activity group;
    - the record-number filter shows only that record's rows;
    - the detail has its codes folded.
  - The area-button locator in the tour now allows the button's attention count ("Platform operations 3 need attention").

## Checks

| Command | Result |
|---|---|
| `.\stop-local.cmd`, then `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, all backend tests 0 failures (sdv-web 68), frontend built |
| `npm run typecheck`, `npm run build` (rerun after the last wording fix) | PASS |
| `.\start-local.cmd`, migration 020 | Applied (`020-audit-actor-display-name` EXECUTED); 4,541 existing rows untouched (all without a name) |
| `npm run ui:capture` | **PASS**: 16 passed, 2 skipped (the opt-in comment and redaction tests). The two changed tests passed again after the last wording fix, on desktop and iPad (4 passed) |
| Screenshots | 16 files in `.runtime/ui-checks/0011/`: Problems list and detail, Audit list and detail, at 1440 and 390 px, in light and dark |

**What failed along the way:**
- **Startup:** the first restart failed to start the backend. `SessionNamedAuditEventPort` has two constructors and neither was marked `@Autowired`, so Spring couldn't create it. I fixed it and added a test for it.
- **Test file:** a script edit damaged the tour spec. A `` $` `` in a JavaScript replacement pasted in a copy of the file's start. I removed the copies after checking each one byte for byte, and the diff now holds only this task's changes.

## Notes for the owner

- **Offline test volume:** the "tour-volume" left Offline by the 0010 opt-in run counts as "Storage attention 1" on the Overview. An offline volume currently counts as attention. Task 0012 covers health wording; it could treat a deliberately offline volume as not needing attention.
- **Download event:** the tour makes one download (DOCUMENT_DOWNLOADED) of record 300000000076 per run, so the download sentence has something to show.

## Commits

- `e73de74`: the task.
- A second commit holds the follow-up below.

Both are on `feature/admin-4`, not pushed or merged. `Start-SDV-Local.ps1` is untouched.

## Follow-up (owner's answers, 2026-11-18)

**Owner's answer:** export option (a). A server endpoint builds the CSV from the list's filters, up to 10,000 rows (a plain message if there are more), and records the export with the filters and the row count. Also:
- make the Keeping-documents save step in the tour opt-in;
- in 0012, a volume Tenant IT took offline on purpose shows "Offline (set by Tenant IT)" and doesn't count as needing attention;
- delete `ConnectedIntegrationsView.tsx` (approved);
- give the local test sign-ins display names, in local mode only;
- leave the test system and test volume in the database.

**1. Audited export (Part B.6)**
- **Endpoint:** `GET /api/v1/operations/audit-events/export` in `OperationsController`.
  - Same permission as the list (`VIEW_TECHNICAL_FAILURES`) and the same filters.
  - It also takes two lists of action codes: `actions` (the chosen activity group) and `excludeActions` (the routine events the screen hides).
  - It always covers the whole period, not only the pages loaded on screen.
- **Service:** `AuditQueryService.export` shares the list's checks.
  - Each action code must look like one; anything unclear is refused before anything is read.
  - **More than 10,000 rows:** refused with "This list has more than 10,000 events, so it can't be exported in one file. Choose a shorter period or more filters, then export again." (HTTP 422). Nothing is exported or recorded.
  - **Up to 10,000 rows:** the CSV is built, then an `AUDIT_EXPORTED` event is written with `rowCount`, `format` and the `filters` that were applied. Only then is the file handed back. If that event can't be written, nothing is exported.
- **The file** (`AuditCsv`):
  - Columns: When (UTC), What happened, Person, Record, Result, Action code.
  - UTF-8 with a byte-order mark, so spreadsheets read names correctly.
  - Every cell is quoted. A cell a spreadsheet would run as a formula gets a leading apostrophe.
- **Sentences:** `AuditSentences.java` is the server copy of `auditWords.ts`.
  - The tour compares the exported sentences with the screen's, row by row, so a difference between the two fails the tour.
  - New sentence: "{who} exported the audit (N events)".
- **Screen:** an **Export CSV** button on the Audit filters, and a note once the file is saved.
  - The activity group is now "Downloads, prints and exports".
- **List endpoint:** unchanged. The include/exclude action filter exists only on the export.

**2. Keeping-documents save step is opt-in.** The tour now opens the New rule panel, checks it and cancels it. Saving a rule (two permanent rows under Earlier rules) runs only with `SDV_KEEPING_RULE_REGRESSION=1`.

**3. Local display names** (`LocalHeaderAuthenticationFilter`)
- This filter exists only with the `local` profile and local-header sign-in.
- The fixed test subjects get a name from a fixed list:

| Subject | Name |
|---|---|
| `local-document-steward` | Dana Steward |
| `local-business-user` | Ben Business |
| `local-business-admin` | Bea Business Admin |
| `local-tenant-it-admin`, `local-tenant-it` | Tom Tenant IT |
| `local-platform-operations-admin`, `local-platform-operations` | Pat Platform Ops |
| `local-administrator` | Ari Admin |

- Any other subject stays unnamed, and a name is never taken from a request header.
- New audit events are named locally ("Dana Steward downloaded …"), and the account button shows the name.
- Older events still show the subject.

**4. Deleted:** `frontend/sdv-ui/src/r7/ConnectedIntegrationsView.tsx`. Nothing imported it.

**5. Offline volume:** done in 0012.

**Tests added:**
- `AuditExportTest` (8):
  - file content and the recorded filters and row count;
  - more than 10,000 rows is refused with nothing recorded, and exactly 10,000 exports;
  - unclear requests are refused before anything is read;
  - nothing is exported if the export can't be recorded;
  - CSV quoting and formula guarding;
  - sentence fallbacks;
  - the Spring constructor.
- `JdbcAuditActionFilterSqlTest` (2): the include and exclude codes are bound parameters inside the tenant; an empty include list matches nothing.
- `LocalHeaderTestNamesTest` (3): the names; no name for other subjects or from a header; the filter is local-profile only.
- **Tour:**
  - Export downloads a CSV with the right header, only the filtered record's rows, and the screen's sentences in order.
  - The export then appears as "Pat Platform Ops exported the audit (N events)".
  - The steward's download reads "Dana Steward downloaded …".
  - The account button checks now expect the display names.

**Checks:**

| Command | Result |
|---|---|
| `.\stop-local.cmd`, `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 0 failures (sdv-web 81), frontend built |
| `npm run typecheck` | PASS |
| `.\start-local.cmd`, then `curl` | Export 200 `text/csv` with 37 rows for 300000000076, recorded as 37. A business user gets 403 |
| `npm run ui:capture` | **PASS**: 16 passed, 2 skipped (the opt-in comment and redaction tests) |
| Screenshots | `audit-exported-1440.png` and `audit-exported-ipad.png` added to `.runtime/ui-checks/0011/` |

**What failed along the way:**
- **Startup:** the first restart failed. `AuditQueryService` gained a second constructor (with a clock, for tests) without `@Autowired`, which is the same mistake as in 0011. I fixed it and added a test for it.
- **Tour:** the account-button checks failed once because the names now show. I updated the expected labels.

## Question for the owner (answered, see Follow-up)

**The audited CSV export (Part B.6).** "Exporting is itself audited" needs the server to record the export, and nothing in the backend does that today. Part C doesn't name such a change, and the task's rules say "no other backend change", so I stopped there rather than guess. Choose one:
- **(a) Recommended:** `GET /api/v1/operations/audit-events/export` takes the same filters as the list. The server builds the CSV (sentence, time, person, record, result, action code), caps the number of rows and records an `AUDIT_EXPORTED` event. The browser only saves the file, so the audit always matches what was exported.
- **(b)** `POST /api/v1/operations/audit-events/export-event` only records that an export happened. The browser builds the CSV from what it has loaded.
- **(c)** No export for now.
