# Report 0026: Enforce sensitivity levels everywhere; locked results (S3)

Date: 2026-11-20
Branch: `feature/sensitivity-3` (from `feature/sensitivity-2`)
Outcome: DONE. No schema change. Build (302 tests), live checks, query timing and the screen tour pass.

## What was done

**0. Carry-over from report 0025**
1. **Role matrix:** `FinalRoleMatrixTest.sections32And34OfTheSensitivityMatrix` checks every cell of `ROLE-MATRIX.md` sections 3.2 and 3.4, as the capability each function needs.
   - Two cells carry a condition that is not a capability: "from Confidential only" (task 0027) and "never own request" (task 0028). The test says so.
   - Section 3.1, with its L cells, is checked through the real controllers and services in `DocumentLevelEnforcementTest` (below).
2. **Legacy direct-role claim:** kept as it was; it never gives Restricted Steward.
3. **Role conflict ends:** at a sign-in whose roles no longer conflict, the person's open problems from that source are removed. One `ROLE_CONFLICT_ENDED` event is written, in the same transaction.
   - The event holds the roles, the first-seen time and the count.
   - Nothing is written while a conflict lasts, or for someone who never had one.
   - `delete … returning` makes sure two sign-ins at once can't both write the end.
   - If recording the end fails, the sign-in still goes ahead; the problem stays open, so the next sign-in records it.
   - No schema change was needed. The ended problem leaves the IT Admin's open list (`GET /api/v1/admin/role-conflicts`); the audit trail keeps both events (question 2).
4. **Restricted Steward stays fixed** in Roles & permissions: unchanged.

**1. Inventory: every server path that touches a document, and its check**

The clearance comes from the person's role on the server (`AuthorizationService.clearance`: `VIEW_CONFIDENTIAL`, `VIEW_HIGHLY_CONFIDENTIAL`). Nothing in the request can change it. The check itself is in the services, through one class, `DocumentAccessGuard`. It always uses the **effective level** (`SensitivityLevel.effective`, the higher of the document's level and its record's level).

| Path | What it returns | Check |
|---|---|---|
| `POST /api/v1/business/search` (search and quick search: same endpoint, `anywhere`) | records | records returned as before; each has `restrictedLevel` |
| `GET /api/v1/business/me/recent-records` (Home's Recently viewed) | records | as search |
| `GET /api/v1/business/records/{ref}` (record detail) | record, its documents | documents the person can't open move to `lockedDocuments` (ID and level only) |
| Counts on Home and Overview (`availableDocumentCount`, `hasRecords`, `/operations/summary` counts) | numbers | unchanged, as the task says |
| `GET …/documents/{id}/viewer` (open) | file name, pages | refused, SDV-SEN-008 |
| `GET …/viewer/pages/{n}` (page image) | PNG | refused, SDV-SEN-008 |
| `GET …/viewer/previews` (thumbnails) | PNGs | refused, SDV-SEN-008 |
| `GET …/viewer/search` (text search in the document) | matches | refused, SDV-SEN-008 |
| `GET …/documents/{id}/download` | the PDF | SDV-SEN-008; Highly Confidential SDV-SEN-009 for everyone |
| `POST …/documents/{id}/print-event` (print) | – | SDV-SEN-008; Highly Confidential SDV-SEN-009 for everyone |
| `POST …/documents/{id}/redactions` (corrective redaction) | – | refused, SDV-SEN-008 |
| `GET/POST …/documents/{id}/tags` | tags | refused, SDV-SEN-008 |
| `GET/POST …/documents/{id}/comments` | comments | refused, SDV-SEN-008 |
| `GET …/documents/{id}/activity` (the document's own audit) | events | refused, SDV-SEN-008 |
| `GET /api/v1/operations/audit-events` and `/export` (audit list and CSV, IT Admin and Platform Ops) | event, record number, **file name** | file name left out ("a document") unless the reader could open it |
| `GET /api/v1/operations/summary` (Problems: processing failures, stuck documents) | **file name** | as the audit list; counts unchanged |
| Business metadata values | record fields | stay visible: stored per submission (record), not per document |
| Producer's own upload and document list (`/api/v1/ingestion/…`) | its own deliveries | not a person's access; unchanged |
| Retention sweep, redaction recovery | nothing to a person | system work; unchanged |

**Metadata stored per document:** no business metadata is. `business_metadata_values` is keyed by submission. What a document has on its own (file name, its source document ID, page count, dates, redacted flag, tags, comments, activity) is treated as content and hidden.

**2. Locked results**
- Search and Recently viewed return the same records and counts as before. `restrictedLevel` is the highest effective level among the record's documents that this person can't open (`CONFIDENTIAL` or `HIGHLY_CONFIDENTIAL`), or null. The page's record levels are read in one query.
- In record detail, `documents` holds only what the person can open. `lockedDocuments` holds the rest as `{documentId, effectiveSensitivityLevel}` and nothing else. The reference is the document ID, a random UUID with no meaning (question 1). The record's own fields and identifiers are unchanged.
- **Every refusal:**
  - 403 with `{"code":"SDV-SEN-008","message":"This document is restricted.","timestamp":…}`;
  - audited as `DOCUMENT_ACCESS_REFUSED` (DENIED): who, when, the document ID, the effective level, what was tried, and the code.
  - The level check runs before the status check, so a restricted document reveals nothing, not even its status. An unknown ID still gets 404.
  - If the audit can't be written, the request is still refused.
- **UI:** a minimal version; the board-exact screens are task 0029.
  - A lock next to the record number in the results (dark for Highly Confidential, light blue for Confidential), with an accessible label.
  - Locked items in the record panel: "Restricted · You can't open this document" and the level.
  - Download and Print hidden in the viewer for Highly Confidential.

**3. Print and download**
- Highly Confidential: refused for everyone, Restricted Steward included, with SDV-SEN-009, and audited.
- Standard and Confidential: work as before for the roles that may. `DOCUMENT_DOWNLOADED` and `DOCUMENT_PRINTED` now carry `sensitivityLevel`, so Confidential ones stand out in the audit.
- Download checks the level again just before decrypting, in case it was raised in between.

**Error codes:** `SDV-SEN-008` and `SDV-SEN-009` are in `SdvErrorCode` and `docs/api/error-codes.md`, with meaning, user action and support text. Both are sent as a coded 403 (`CodedRefusal.Forbidden`).

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (during work) | 6 expected failures, all fixed (see "Tests") |
| `.\stop-local.cmd` | stopped the app (it locked the jar); database and Vault left running |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 222 = **302 tests** (287 before), 0 failures, 0 errors. Frontend build passed. |
| `npm run typecheck` | passed |
| `.\start-local.cmd` | started; Vault unsealed |
| Live checks with curl (local headers) | all as expected, see below |
| Query timing in a scratch database | see "Performance" |
| `npm run ui:capture` | **20 passed, 4 skipped**, 4.8 min |

`Test-SDV-CleanInstall.ps1` and `Test-SDV-SchemaUpgrade.ps1` were not run: nothing in the schema changed.

**Live checks** (dev data: 39 available documents, all Standard; no record levels):
- Search as Business User, Document Steward and Restricted Steward: 200, 9 records, each with `restrictedLevel: null`. As Business Admin: 403.
- Record detail: 2 documents, `lockedDocuments: []`, `restrictedLevel: null`.
- Business User opens a document and a page: 200. Business User prints: 403 (no print permission, as before).
- Steward prints: 204, and the event has `"sensitivityLevel": "STANDARD"`. Steward downloads: 200, and the event has the level.
- Unknown document: 404 `NOT_FOUND`.
- IT Admin: audit list 200 (Standard file names shown), export 200, summary 200. These prove the changed SQL runs.
- Conflict end: two sign-ins as `local-administrator` with `BUSINESS_ADMIN` only. The first removed the 2 open conflicts left by the 0025 live checks and wrote one `ROLE_CONFLICT_ENDED`. The second wrote nothing.
- I didn't deliver Confidential documents to the running app, to keep test records out of your dev data. Locking is proven by the tests instead.
- **Left in the dev `sdv` database:** a few audit events from these checks (subjects `live-check…`); one print and one download event; the `ROLE_CONFLICT_ENDED` event; `role_conflict_problems` is now empty.

**Performance (how measured)**
- **What the change adds:**
  - search and Recently viewed: one query per page (the record levels, by the unique index);
  - record detail: none (the record level was already read);
  - each document request: two small queries (the submission's identifier values and the record level);
  - audit list and Problems: two per page and sending system;
  - every sign-in without a conflict: one `delete` that matches nothing.
  - The search queries themselves are unchanged.
- **Timed in a scratch database** (`sdv_perf_test` in `sdv-postgres`, dropped afterwards), with `explain analyze` and the parameter types JDBC sends:

  | Query | Data | Index scan | Time |
  |---|---|---|---|
  | record levels of a 25-record page | 10,000 records with a level | yes | 0.05–0.12 ms |
  | one document's record level | same | yes | 0.03 ms |
  | conflict-end delete | 5,000 open conflicts | yes | 0.07 ms |

  The search and record queries are unchanged, so the documents table wasn't rebuilt there.
- **Live:** 20 searches (25 records a page) against the running app: median 70 ms, min 62 ms, max 84 ms.
- **What isn't measured:** no before-and-after comparison on a database holding a few thousand real documents. The added work is one sub-millisecond query per page. Say if you want a full end-to-end run on seeded data.

## Tests: counts, and any failures with their output

New tests, 18, all passing:
- `DocumentLevelEnforcementTest` (6), through the real controllers, permission check and services; only storage, crypto and the database are mocked:
  - **Every cell of section 3.1:** 6 roles × 3 levels × 12 functions (open, page image, thumbnails, text search, print, download, redact, tags and adding one, comments and adding one, activity). Each cell runs twice: once with the document's own level, once with a Standard document in a record with that level. That is 432 runs.
    - Each refused cell must give its exact outcome (no permission, SDV-SEN-008 or SDV-SEN-009), write exactly one refusal audit (who, document ID, level, code, DENIED), use the fixed message, and never reach storage.
    - Allowed cells write no refusal, and an allowed print names its level.
  - Search for every role and level, both ways: `restrictedLevel` is right; the count stays 2; identifiers stay; admins are refused.
  - **JSON:** a locked document has exactly the keys `documentId` and `effectiveSensitivityLevel`. The record JSON doesn't contain its file name, source document ID or page count. The identifiers are still there.
  - A record level of Highly Confidential locks a Standard document for a Steward.
  - The level check comes before the status check; an unknown document is still 404.
  - A refusal stands when the audit is down.
  - **Mutation check:** with Confidential made open to all, the test failed on the right cells. The rule was restored before the build.
- `RestrictedNamesInAdminViewsTest` (4):
  - the audit list and CSV hide restricted names, by own level and by record level, and also a name with no document behind it; full clearance shows them;
  - the problem lists hide them, keep the counts and facts, and hide every name if the levels can't be read;
  - the level is not sent to the Problems screen;
  - a document's record key equals the record's key from search.
- `FinalRoleMatrixTest.sections32And34OfTheSensitivityMatrix` (1).
- `RoleGovernanceServicesTest` (2): a conflict is audited when it starts and when it ends, never while it lasts; nothing is written for someone who never conflicted.
- `RoleSeparationGuardTest` (2): a sign-in without a conflict tells the reporter; a failure there never refuses the sign-in.
- `ErrorCodesDocumentedTest` (extended): a coded 403 sends exactly code, message and time.

Changed tests (said here per CLAUDE.md):
- `DocumentSensitivityReadTest`, `RecordSensitivityReadTest`: record detail now locks what the reader can't open. Called without a clearance it locks everything above Standard (fail closed). These tests read with full clearance, and their assertions are unchanged; one message lost "no filtering yet".
- `AuditExportTest`: the marked constructor has 3 parameters, not 2 (the guard). The test rows carry a Standard document and a clearance is passed; the assertions are unchanged.
- `ProblemsAndAuditRecordsTest`: the guard, the clearance and a Standard level were added. Assertions unchanged.
- `DocumentViewerServicePageViewTest`, `DocumentViewerServicePreviewsTest`, `SecurePdfViewerControllerPreviewsTest`: the new constructor and method arguments only.

Failures on the way:
1. The 6 failures above, from the new behaviour and the new codes, before those changes and the error-code doc.
2. My first mutation check didn't take effect: `-pl sdv-web` used the installed jar. It was rerun with the modules built together.
3. In the scratch timing, my first queries compared the key to a `text` expression and scanned the whole table. With the parameter types the app actually sends, the index is used (table above). No product change.

Screen tour (`npm run ui:capture`, app running): **20 passed, 4 skipped** (the usual 4: desktop-only and the opt-in redaction test), 4.8 min.

## Files changed

- **Domain:** `SdvErrorCode` (SEN-008, SEN-009), `CodedRefusal` (`Forbidden`), `SensitivityLevel` (comment).
- **Application:**
  - new `access/Clearance`, `access/DocumentAccessGuard`;
  - `business/BusinessExperienceService`, `BusinessExperienceView`, `RecordKey`;
  - `viewer/DocumentViewerService`, `download/ControlledDownloadService`, `document/DocumentActionService`, `redaction/CorrectiveRedactionService`;
  - `audit/AuditQueryService`, `audit/AuditSentences`, `operations/OperationsService`, `roles/RoleConflictService`;
  - ports `AuditQueryPort`, `BusinessRecordRepository`, `OperationalRepository`, `RoleGovernancePort`.
- **Persistence:** `JdbcBusinessRecordRepository` (one query for a page of record levels), `JdbcOperationalRepository` (document level with audit and problem rows), `JdbcRoleGovernanceRepository` (end conflicts).
- **Security:** `AuthorizationService.clearance`, `RoleSeparationGuard`, `RoleConflictReporter`, `RecordingRoleConflictReporter`.
- **Web:** the five business controllers, `OperationsController`, `FoundationExceptionHandler`.
- **Tests:** the new and changed tests above.
- **Frontend:** `api.ts`, `r7/model.ts`, `r7/liveApi.ts`, `r7/Workspace.tsx`, `r7/Viewer.tsx`, `r7/r7.css`.
- **Docs:** `docs/api/error-codes.md`, `docs/work/0026-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, `docs/design/sensitivity/` (question 3), tasks 0027–0030.

## Commits (hash and message), or "not committed"

`b219e72` feat: enforce sensitivity levels on every document path; locked results (task 0026), on `feature/sensitivity-3`. Not pushed, not merged. (This line was added in a second commit.)

## Not done, and why

- **Locking on live data:** not shown, because the dev data has only Standard documents and I didn't add test records to it.
- **End-to-end timing on thousands of documents:** not run; see "Performance".
- **Printing from the screen:** the server refuses the print event, and the viewer prints only after that event succeeds. A person who can open a page can still take a screenshot of it. That is outside what a server can stop.
- **Board-exact screens:** task 0029.

## Questions for the owner

1. **Locked reference:** a locked document is referred to by its document ID, a random UUID with no meaning that also identifies it in the refusal audit. Is that acceptable, or do you want a separate reference that can't be matched to the audit?
2. **Conflict end and the IT Admin's list:** when a conflict ends, its problem is removed from the open list (`/admin/role-conflicts`) and the audit keeps the start and the end. The alternative is an `ended_at` column, which keeps ended problems in the list; that is a schema change. Is removal what you want?
3. **`docs/design/sensitivity/`** (with `ROLE-MATRIX.md`) is still untracked, so the matrix the tests point to is not in git. Shall I commit it with this branch?
4. **Admin views:** IT Admins and Platform Ops can't open any document, yet they see the file names of Standard documents in the audit and in Problems, as before this task. Only Confidential and Highly Confidential names are now hidden. Should Standard names be hidden there too (separation rule 4 says admin screens never show file names)?

## Suggested next step

Answer questions 1 to 4, then task 0027.
