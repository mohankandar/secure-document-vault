# Report 0031: Exact duplicates: keep one copy, acknowledge, count

Date: 2026-11-20
Branch: `feature/intake-duplicates` (from `feature/sensitivity-7`)
Outcome: DONE

One schema change, approved by the task: changeset **029**, one new table `document_duplicate_receipts`. No existing table or row changes. These pass:
- the build (362 tests);
- the schema upgrade check from 028 and from 027, with the three PostgreSQL tests;
- the clean-install check;
- the screen tour (23 passed, 9 skipped);
- `ui:compare` (26).

One decision was taken with you during the session (question below, already answered): **write, then discard**.

## What was done

**0. Carry-over from report 0030**
1. **Openings in the audit level filter.** `DOCUMENT_VIEW_OPENED` and `DOCUMENT_PAGE_VIEWED` now record `sensitivityLevel`. That is the effective level at the moment of viewing, as the access check works it out. Earlier events stay without a level, with no fallback.
   - SDV-SEN-019's support note in `docs/api/error-codes.md` is updated.
   - So is the hint on the Audit screen's Sensitivity filter.
2. **Decided recently.** New `GET /api/v1/admin/access-requests/decided`, for Business admins only (others get SDV-APR-005, audited).
   - It lists the decisions of **every** Business admin of the tenant in the last 30 days, at most 50, newest first.
   - Each entry gives who decided, kind, scope, requested level (for lowerings), primary identifier, decision and when. It never gives the reason or the requester.
   - An approval stays "Approved" after it is used or expires.
   - The Approvals screen's drawer now reads this list. The list that was kept in the browser tab is removed.
3. **Expiry note.** The approvals list now carries `pendingExpiryDays`, the tenant's own window. The footer shows it ("expire after 7 days" here).
4. Notices remembered per browser: left as they are (your answer).

**Exact duplicates (case 2)**
1. **Detection.** The check uses tenant + sending system + record + SHA-256.
   - The record is found with the same grouping as task 0024: the record key from the submission's identifier values, and that record's submissions.
   - A document that is deleted, or is being deleted by the retention cleanup, doesn't count. The new file is then stored as a new document.
   - The check runs inside the receipt transaction, after the submission lock and an advisory lock on (tenant, sending system, record, checksum). Two copies of the same file arriving at the same moment take turns, so only one is stored.
2. **No second copy.**
   - No document, storage reference or key row is written.
   - The upload is still encrypted and streamed to storage while its checksum is worked out. On a duplicate, that temporary object is deleted before the answer is sent. This is your decision during the session (see question 1).
   - The answer is **200** with the existing `documentId`, `receiptAcknowledgement: DUPLICATE_ALREADY_STORED`, `duplicate: true` (`exactDuplicateContent` has the same value) and the delivery's own `sourceDocumentId`.
3. **Submission completeness.** The duplicate fills its expected slot: the slot's state becomes `RECEIVED`, with no `received_document_id`. The existing document belongs to another submission, and the old foreign key allows only a document of the same submission. The duplicate's row names the slot (submission + `sourceDocumentId`) and the document it points at.
   - "Received" now counts documents + duplicates everywhere it is counted:
     - the sender's `progress.receivedCount`;
     - when a submission becomes COMPLETE;
     - "already has its declared number";
     - INCOMPLETE marking;
     - the operations "needs attention" queries.
   - A duplicate still needs a free place, like a document.
4. **Sensitivity.** The duplicate's document level is compared with the existing document's own level.
   - Higher: the document is raised. A row goes into the level history (changed by the sending system; reason "Sent again by the sending system with a higher level"). `DOCUMENT_SENSITIVITY_RAISED` is audited with actor type SOURCE.
   - Lower or equal: ignored, and noted in the duplicate's audit event (`levelOutcome: KEPT`).
   - Record levels are unchanged. They are set on the submission (task 0024 rules), and the duplicate's submission belongs to the same record.
5. **Record it.**
   - Audit event `DOCUMENT_DUPLICATE_RECEIVED`, with `requestId` (and whether it is a `sourceDocumentId` or an idempotency key), `sourceId`, `submissionId`, `recordKeySha256`, `contentSha256`, `existingDocumentId`, `duplicateId`, `sentLevel`, `levelOutcome` and `documentLevel`.
   - The record is named by its key's SHA-256, as in the level history. No contract number or file name goes into the audit.
   - The table row has all the Daily intake report needs: tenant, sending system, submission (which leads to the primary identifier), request ID, checksum, size, the document it points at (its pages), and the time.
   - The Audit screen and the export say "… sent a document again; Vero kept the copy it already had". It is listed under Deliveries.
6. **Same file under another record**, or from another sending system: stored normally, `duplicate: false`. `hasExactDuplicateContent` (which only set a flag and stored a second copy) is removed.
7. **Retry of a duplicate delivery** (case 1 for it): the same answer again, with `idempotentReplay: true`. Nothing is written, not even briefly, and nothing more is audited, so the report counts it once.
   - Another file under the same ID gets 409.
   - Another level gets 409 SDV-SEN-003.
   - Its file name is not compared, because a duplicate keeps none.
8. **Docs.** `docs/api/sdv-ingestion-v1.openapi.json` (the receipt's fields and the 200 answer) and a new section in `docs/api/README.md`, "Sending the same document again", with the three cases. No new error code: nothing new is refused.

**Schema (changeset 029)**
- Table `document_duplicate_receipts`. Its columns:
  - duplicate_id, tenant, sending system, submission;
  - source_document_id or producer_idempotency_key (exactly one);
  - existing_document_id, content_sha256, content_size_bytes;
  - sent_level, level_outcome (RAISED / KEPT), received_at.
- Its checks:
  - foreign keys to the submission (with its sending system) and to the document (same tenant);
  - exactly one non-blank identity;
  - the checksum is 64 lower-case hex characters;
  - the size is above 0;
  - the level and outcome names.
- Its indexes:
  - unique per delivery: (tenant, submission, source_document_id) and (tenant, submission, idempotency key);
  - (tenant, received_at, sending system), for the daily report;
  - (tenant, submission), for received counts.
- The duplicate lookup uses the existing `idx_documents_fingerprint`.
- 029 reached the dev `sdv` database through `start-local` (local profile), as 028 did. It has 0 rows; I sent no test upload to the dev database.

**Demo seed.** The seed uploaded one sample PDF for every document, so on a fresh database the second and third documents of a contract would now be duplicates. Each upload now sends its own copy: the sample plus one PDF comment line after its end that names the delivery. Documents already in the dev database are not sent again, so nothing there changes.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (backend, before the build) | BUILD SUCCESS |
| `.\Build-SDV-All.ps1` | first try: my own `*>` redirection in PowerShell turned the Mockito warning into an error (as in 0030). Rerun from a separate `powershell.exe` with output to a file: **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 282 = **362 tests** (348 before), 0 failures, 3 skipped (the PostgreSQL tests). Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 029`) | first run **FAIL**: my new `DuplicateIntakePostgresTest` broke `ck_documents_expired_deleted_tombstone` in its own setup (it set EXPIRED_DELETED without a deletion reason). After fixing the test: **PASS**, 34 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 028` | **PASS**, 34 s |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 55 s (29 changesets) |
| `.\stop-local.cmd`, `.\start-local.cmd` | stopped and started; 029 applied to the dev `sdv` database |
| `npm run typecheck` | passed |
| `npm run ui:capture` | **23 passed, 9 skipped**, including the new step (the skips are tablet copies of desktop-only steps and opt-in tests) |
| `npm run ui:compare` | **26 passed** |

## Tests: counts, and any failures with their output

New:
- `DocumentDuplicateIntakeTest` (10, through the upload endpoint):
  - **Case 1**: a retry is answered as before and is no duplicate, even when the record has another copy.
  - **Case 2**:
    - 200 with the existing ID, `DUPLICATE_ALREADY_STORED` and `duplicate: true`;
    - no document, storage or key row, no finalization;
    - the temporary object is deleted;
    - the lookup is limited to tenant, sending system and the record's submissions, under the lock;
    - the duplicate row and the audit event hold the listed fields, with no file name or contract number.
  - **Manifest**: the slot is marked received with no document of its own; the submission is updated; the place is checked with the received count.
  - **Idempotency key** without a `sourceDocumentId`: the duplicate is named by the key, and no slot is filled.
  - **No place left**: refused, nothing kept.
  - **Higher level**: raises the document, with a history row and a SOURCE audit event. **Lower or equal**: ignored, `KEPT` audited.
  - **Retry of a duplicate delivery**: same answer, nothing written or audited; another level gives SDV-SEN-003, another file 409.
  - **Case 3 / another record**: stored as new (201, no flag).
  - **Original being deleted**: when the cleanup took it before its row was locked, the file is stored again.
- `DuplicateIntakePostgresTest` (real PostgreSQL, run by the upgrade check), with the real repositories and the intake service:
  - the lookup across record, sending system, tenant and checksum, with deleted, being-deleted and expired originals left out;
  - case 2 end to end: no second document or storage row, the row, the audit event, slot RECEIVED, 1 of 4;
  - a retry not counted again, SDV-SEN-003;
  - a raise written to `documents` and `sensitivity_level_changes` (its checks pass), and a lower level kept;
  - 4 of 4 makes the submission COMPLETE;
  - INCOMPLETE marking and "needs attention" count duplicates;
  - the table's checks: duplicate delivery, no or two identities, blank identity, bad checksum, level and outcome, another sending system's submission, another tenant's document;
  - the other tenant, sending system and record left untouched.
- `ApprovalsScreenSupportTest`:
  - Decided recently: every admin's decisions, newest first; 31 days old and still waiting are left out; the subject is shown when no name is known; a used approval stays "Approved"; exactly the listed fields, no reason or requester.
  - The pending window: 7 by default, 3 with a tenant setting.
- `DocumentViewerServicePageViewTest`: an opening and both kinds of page view record the effective level.
- `AuditExportTest`: the sentence for `DOCUMENT_DUPLICATE_RECEIVED`.
- Tour step "Decided recently, the expiry note and levels on openings (task 0031)":
  - a decision made through the API shows in the drawer with "by …" and no reason;
  - the footer shows the server's window;
  - a viewer opening is found by the audit filter under its level.

Changed tests:
- `ApprovalsControllerTest`:
  - the new endpoint is in the refused list;
  - the denied-audit count follows the list's length instead of a fixed 4;
  - a pass-through check.
- `DocumentSensitivityIntakeTest`: the two new constructor arguments.
- `DocumentViewerServicePageViewTest`: the mocked access check now returns a level.

Failures on the way, all fixed: the denied-audit count (above), and the PostgreSQL test's own setup (above).

## Files changed

- **Schema:** new `029-document-duplicate-receipts.sql` / `.yaml`, `db.changelog-master.yaml`.
- **Backend:**
  - `DocumentIntakeService`, `DocumentReceipt`, `DocumentIntakeRepository`, `JdbcSecureIngestionRepository`, `JdbcOperationalRepository`, `SubmissionService` (received count, test stub);
  - `DocumentIntakeController`;
  - `DocumentViewerService`;
  - `AccessRequestService`, `AccessRequestRepository`, `JdbcAccessRequestRepository`, `ApprovalsController`;
  - `AuditSentences`.
- **Backend tests:**
  - new `DocumentDuplicateIntakeTest`, `DuplicateIntakePostgresTest`;
  - changed `DocumentSensitivityIntakeTest`, `DocumentViewerServicePageViewTest`, `ApprovalsScreenSupportTest`, `ApprovalsFixture`, `ApprovalsControllerTest`, `AuditExportTest`.
- **Frontend:** `Approvals.tsx`, `AuditLog.tsx` (hint), `auditWords.ts`, `liveApi.ts`, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1` (029, default `-From 029`), `Seed-SDV-Canvas-Demo.ps1`, `docs/api/README.md`, `docs/api/sdv-ingestion-v1.openapi.json`, `docs/api/error-codes.md`, `docs/work/0031-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, tasks 0032 to 0034.

## Commits (hash and message), or "not committed"

`643df19` feat: exact duplicates kept once and acknowledged; decided recently for all admins; levels on openings (task 0031), on `feature/intake-duplicates`. Not pushed, not merged. (This line was added in a second commit.)

## Not done, and why

- **"Nothing is encrypted or written to storage" isn't literally met.** The checksum is only known once the whole upload has been read, and the upload streams straight into encryption and storage (PDFs up to 250 MB by default). You chose "write, then discard": on a duplicate the encrypted object is deleted before the answer, and no row is kept. If that delete fails, the object is left over, as for any failed receipt today ("operational reconciliation owns rare orphan cleanup").
- **No index for "Decided recently".** It reads `access_requests` by tenant and decision time, limited to 30 days and 50 rows. I added no index because this task's schema approval covers only recording duplicates. It is quick at today's sizes.
- **No live duplicate upload against the dev database.** That would have added a duplicate row and audit event to real development data. The same path runs on PostgreSQL in `DuplicateIntakePostgresTest`.

## Questions for the owner

1. (Answered during the session.) Hash first, or write then discard? You chose **write, then discard**.
2. **A corrected document sent again.** Permanent redaction changes a document's checksum. If the sending system later sends the original, uncorrected file again, its checksum matches nothing, so Vero stores it as a new document: the redacted content is back. This is case 3 today and was the same before this task. Should a file that matches a document's checksum **before** correction also count as a duplicate of the corrected document? The old checksum is kept in the redaction history.

## Suggested next step

Answer question 2 if you want that guard, then task 0032 (the Daily intake report can count `document_duplicate_receipts` per day and sending system).
