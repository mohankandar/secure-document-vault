# Report 0027: Legal hold, V1-lite (R2)

Date: 2026-11-20
Branch: `feature/sensitivity-4` (from `feature/sensitivity-3`)
Outcome: DONE. One schema change, approved with the task: table `legal_holds` (changeset 026). The build (316 tests), both schema checks, a PostgreSQL test of holds and retention, the live checks and the screen tour pass.

## What was done

**0. Carry-over from report 0026**
1. Locked reference = document ID: nothing to change.
2. Ended role conflicts: nothing to change.
3. `docs/design/sensitivity/` (all eight files, `ROLE-MATRIX.md` included) is committed on this branch.
4. **Admin views never show file names, at any level.**
   - The audit list, its CSV export and Problems (IT Admin, Platform Ops) no longer read a file name at all. The queries don't select `original_filename`.
   - A document event or problem now carries:
     - the record's primary identifier (the existing `recordNumber`, or `problemRecords` in Problems);
     - `documentId`;
     - the **effective** level;
     - the received date;
     - the page count.
   - The CSV has four new columns: `Document ID, Level, Received (UTC), Pages`. Sentences say "a document".
   - On screen, Problems show Level and Pages under Technical details, and the copied note says "Document: received 18 Nov, … Vero document ID …". The audit entry shows Level, Received and Pages.
   - The clearance argument the 0026 rule needed is gone from these two services.
   - I checked the dev database: none of its 26,187 audit events has a file name in its details.

**1. Schema** (`026-legal-holds.sql`)
- `legal_holds`: hold ID, tenant, document (foreign key to documents in the same tenant), reason, placed by and at, released by and at.
- Checks:
  - the reason is 3–500 characters, with no spaces at either end;
  - a release has both who and when;
  - a release can't come before the hold was placed.
- One active hold per document (a partial unique index). Released holds stay as history.
- No existing table or row changes.

**2. Server**
1. **Endpoints** under `/api/v1/admin/legal-holds`, capability `MANAGE_LEGAL_HOLD` (Business Admin only):
   - `GET /documents?identifier=…` finds the record's documents by its primary identifier (first business identifier in display order, as the record number elsewhere). It leaves out deleted documents.
   - `GET` lists the holds in place.
   - `POST {documentId, reason}` places a hold (201).
   - `POST /{holdId}/release` releases it.
   - The responses carry only: primary identifier and its label, effective level, received date, page count, hold facts, and `documentId` (for support; admin screens must not show it). No title, file name or content.
2. **Retention:**
   - The cleanup's due list and its claim both skip documents with an active hold.
   - The claim now runs in a transaction that locks the document row first; placing a hold takes the same lock. A hold and a deletion can't pass each other: a hold placed first stops the claim, and a claim made first makes the hold refuse (SDV-HLD-006).
   - The PostgreSQL test proves both orders. A mutation run without the lock failed it: PostgreSQL then let the claim miss a hold committed while it waited.
   - Run summary, count only: `heldSkipped` in `/operations/retention-status` and `keptOnLegalHold=` in the log.
3. **Corrective redaction** is unchanged and never reads holds. On a held document, redaction's own status change works and the hold stays.
4. **Audit:** `LEGAL_HOLD_PLACED` and `LEGAL_HOLD_RELEASED` (target: the document; details: hold ID, and placed-at on release). Each is written in the same transaction as the change, so if the audit can't be written, nothing changes. The free-text reason is not copied into the audit (it stays in `legal_holds`). A refusal for missing permission is audited as `AUTHORIZATION_DENIED`, as before.

**Error codes** `SDV-HLD-001` to `008` are in `SdvErrorCode` and in `docs/api/error-codes.md`, with meaning, user action and support text:

| Code | Status | When |
|---|---|---|
| HLD-001 | 403 | not a Business Admin |
| HLD-002 | 400 | reason not 3–500 characters |
| HLD-003 | 400 | identifier missing or over 300 characters |
| HLD-004 | 404 | no such document in this tenant |
| HLD-005 | 409 | already on hold |
| HLD-006 | 409 | deleted or being deleted by retention |
| HLD-007 | 404 | no such hold in this tenant |
| HLD-008 | 409 | already released |

A new `CodedRefusal.NotFound` makes 404s carry their code.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (during work) | the expected compile failures in tests that used the removed file-name fields (see "Tests") |
| `.\stop-local.cmd` | stopped the app (it locked the jar); database and Vault left running |
| `.\Build-SDV-All.ps1` (final) | **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 236 = **316 tests** (302 before), 0 failures, 0 errors, 1 skipped (the PostgreSQL test, which only runs from the schema check). Frontend build passed. |
| `npm run typecheck` | passed |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 026`) | **PASS**, 21 s, including step 4e (the PostgreSQL test) |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 025` | **PASS**, 25 s (025 and 026 together) |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 47 s, 26 changesets |
| `.\start-local.cmd` | started; the local profile applied 026 to the dev `sdv` database |
| Live checks with curl (local headers) | all as expected, see below |
| `npm run ui:capture` | **20 passed, 4 skipped** (the usual 4), 4.7 min |

**Live checks** (dev data, record 300000000007):
- Find: 5 documents, each with primary identifier, label "Contract number", level, received date. Page count is null, because these older dev documents have none stored. No file name or title.
- Place: 201. Again: 409 HLD-005. Reason "ab": 400 HLD-002. Unknown document: 404 HLD-004. No identifier: 400 HLD-003.
- Business User, Document Steward, Restricted Steward, IT Admin and Platform Ops: 403 HLD-001 each.
- Release: 200. Again: 409 HLD-008. Bad reference: 404 HLD-007. The list is empty afterwards.
- IT Admin audit list, CSV export and summary: 200, no `filename`/`originalFilename`/`.pdf` anywhere. The hold events show the record, document ID, level and received date.
- `retention-status` includes `heldSkipped`.
- **Left in the dev `sdv` database:**
  - table `legal_holds` (changeset 026), holding one released hold from the live check;
  - the audit events of these checks (subjects `live-check-…`).

**Schema checks** (scratch database `sdv_schema_test`, dropped at the end):
- The reviewable SQL holds only 026, and the `sql` step changes nothing.
- After the update, `legal_holds` exists and is empty. It accepts a released hold and a new active one for the same document. Its checks refuse:
  - short, space-padded and too-long reasons (the last by the column's 500 limit);
  - half a release, and a release before placing;
  - an unknown document, and a second active hold.
- Every other table is unchanged.

**Performance (not measured).**
- The cleanup adds:
  - a lookup against the active-hold index for each candidate;
  - one count per run;
  - one row lock for each claim, inside the claim's own short transaction.
- The identifier lookup filters metadata values on `normalized_value or stored_value`, as the audit's record-number filter already does. On a tenant with very many values that may not use the lookup index well.
- I didn't time it: the only other scratch database allowed is `sdv_install_test`. Say if you want a timing run.

## Tests: counts, and any failures with their output

New tests: 14 test methods in the normal build, of which 1 is the PostgreSQL test (skipped there).
- `LegalHoldServiceTest` (7):
  - trimmed reason, and the 3/500 limits;
  - audit in the same transaction, without the reason; rollback if the audit fails;
  - HLD-004 for malformed or unknown documents, and for another tenant's;
  - HLD-006 for deleted or being deleted; HLD-005 for already held;
  - holds allowed while a document is being stored or corrected;
  - release once, HLD-007 and HLD-008; lookup limits and JSON fields.
- `LegalHoldControllerTest` (3):
  - through the real permission check, every role except Business Admin is refused with HLD-001 on all four endpoints, and audited;
  - the exact JSON fields;
  - codes and statuses pass through.
- `RetentionLegalHoldTest` (3):
  - `heldSkipped` in the summary, and each claim in its own transaction;
  - the cleanup SQL skips active holds, and the claim locks first;
  - every hold statement is limited to the tenant and reads no file name or title.
- `LegalHoldPostgresTest` (1, run by the schema check), with the real repositories and services on PostgreSQL:
  - a held document past its retention date isn't deleted, and after release it is deleted on the next run;
  - both race orders; tenant isolation;
  - redaction status on a held document;
  - audit rows; no reason in the audit.

Changed tests (said here per CLAUDE.md):
- `RestrictedNamesInAdminViewsTest`: its 0026 tests asserted the old rule (names shown when the reader could open the document). They are rewritten for the new rule:
  - no field with "file" in its name in the audit list or Problems JSON;
  - the exact fields, the CSV columns and effective levels;
  - the queries never select `original_filename`.

  The record-key test is unchanged.
- `AuditExportTest`: the CSV header and rows have the four new columns, and sentences say "a document" instead of the file name. Two hold sentences were added.
- `ProblemsAndAuditRecordsTest`: page count and level instead of the file name; the clearance argument removed.
- Screen tour `e2e/sdv-screen-tour.spec.ts`:
  - Problems now expect "Vero received a document from LexaVault…" instead of "appraisal.pdf", and the note expects "Document: received …";
  - audit sentences expect "opened/downloaded/sent a document";
  - the CSV header has the four new columns;
  - new assertions check that no `.pdf` appears in these places.

  The first tour run failed exactly on these old expectations: 4, then 2 after the first fix.

Failures on the way:
1. Compile errors in the three test files above until they were updated.
2. Schema check:
   - the 501-character reason is refused by the column type, not by the check, so the script now expects that message;
   - the PostgreSQL test's seed data lacked `business_metadata_values.source_id`;
   - one count in it was wrong, because tenant 2's due document was deleted too (correct behaviour).
3. The mutation run above, as intended. The lock was restored and the build rerun.

## Files changed

- **Schema:** `026-legal-holds.sql` and `.yaml`, `db.changelog-master.yaml`.
- **Domain:** `SdvErrorCode` (HLD-001…008), `CodedRefusal` (`NotFound`).
- **Application:**
  - new `legalhold/LegalHoldService` and `port/LegalHoldRepository`;
  - `retention/RetentionDeletionService` and `port/RetentionRepository`;
  - `audit/AuditQueryService`, `AuditCsv` and `AuditSentences`;
  - `operations/OperationsService`;
  - ports `AuditQueryPort` and `OperationalRepository`.
- **Persistence:** new `legalhold/JdbcLegalHoldRepository`; `JdbcRetentionRepository`, `JdbcOperationalRepository`.
- **Web:** new `admin/LegalHoldController`; `FoundationExceptionHandler` (coded 404), `OperationsController`, `RetentionScheduler`, `RetentionSweepMonitor`.
- **Frontend:** `r7/liveApi.ts`, `Problems.tsx`, `problemWords.ts`, `AuditLog.tsx`, `auditWords.ts`, `ui.tsx` (`levelWords`), `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1` (026, default `-From 026`), `docs/api/error-codes.md`, `docs/design/sensitivity/` (8 files), `docs/work/0027-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, tasks 0028–0034.

## Commits (hash and message), or "not committed"

See the commit on `feature/sensitivity-4`: "feat: legal hold V1-lite; admin views never show file names (task 0027)". Not pushed, not merged.

## Not done, and why

- **No UI for legal holds** (the task says so). The audit and Problems screens were updated only to follow the server change (0.4).
- **No bulk hold.** The design board reads "3 documents on contract … will be held". The server holds one document per call, so a screen would place one hold per document.
- **Malformed JSON body** on place: the global handler still sends the generic `BAD_REQUEST`. It isn't specific to holds, so I left it.
- **Timing:** see "Performance".

## Questions for the owner

1. **Record titles in Problems.** Problems still send the record's title (`problemRecords.recordTitle`, shown under the record number). Separation rule 4 says admin screens never show titles, but 0.4 named only file names. Remove titles too?
2. **Document ID on admin screens.** 0.4 lists the document ID for the audit and Problems. Rule 4 and task section 2.1 say admin screens never show it. I kept it in the API and where it already appeared (Technical details, the copied note), and never in a list column. Is that right, or should it leave the screens entirely?
3. **Who placed a hold.** The table stores the sign-in subject (`placed_by`), as the task lists. The board shows a person's name. Should I add display-name columns (a schema change), or take the name from the audit event when the V1 screen is built?
4. **Documents being deleted.** A hold on a document the cleanup is deleting (`RETENTION_DELETING`, including one stuck after a storage failure) is refused with HLD-006, since its file may already be gone. Agreed?
5. **Reason in the audit.** The reason is free text. I kept it out of the audit, which IT Admins read, and only in `legal_holds`, which Business Admins read. Agreed?

## Suggested next step

Answer questions 1 to 5, then task 0028.
