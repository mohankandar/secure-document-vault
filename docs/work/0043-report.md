# Report 0043: Fix 6, viewing, print and download (DOC, SCH)

Date: 2026-11-22
Branch: `fix/viewing` (from `release/vero-r7`)
Outcome: **DONE**, except one step the session's permission check refused: `.\Seed-SDV-Canvas-Demo.ps1 -Reset`. The owner runs it by hand.

No other product's name or behaviour in code.

## What was done

1. **Codes (item 1).** Vero now sends SDV-DOC-001 to 010 and SDV-SCH-001 to 003.
   - `VIEWER_UNAVAILABLE` and `DOWNLOAD_UNAVAILABLE` are gone. Each cause has its own code instead:
     - DOC-003: the stored copy can't be read (storage details, volume, provider, storage, key service).
     - DOC-004: a check failed, or the document is too large to view.
     - DOC-005: the document can't be drawn.
     - DOC-007: a download failed before anything was sent.
     - DOC-009: a download stopped part-way.
   - DOC-001 (no such document) and DOC-002 (not available, with the state in plain words) are shared by the viewer, download, print, tags, comments and activity.
   - DOC-006 covers viewer requests that aren't valid. DOC-008 means the document changed while downloading. DOC-010 covers tags and comments that aren't valid.
   - SCH-001 covers search criteria. SCH-002 covers a record reference that is broken (400) or names no record of this tenant (404). Both get the same sentence. SCH-003 covers Recently viewed.
   - The 422 answers keep their status. A status change wasn't among the approved ones (question 1).
2. **Logs (item 2).** Every DOC and SCH failure writes one line with the reference and the exact cause: the step, the check, the page, the document ID, the bytes sent and the grant's state. The line never holds the exception's own text.
   - ERROR: DOC-003, 004, 007, 009. These are ERROR even though the answer is 422.
   - WARN: DOC-005 and 008.
   - INFO: DOC-001, 002 and SCH-002.
   - DEBUG: DOC-006, 010, SCH-001 and 003.
   - The levels are explained under question 2.
3. **DOC-009, the one-time grant (item 3).**
   - The download still takes the grant atomically just before decrypting, so two racing downloads can't both start.
   - It now counts the bytes handed to the answer. If it fails before the first byte (storage, key service, a check), the grant is given back: approved and unused again, in the same use window. This is audited as the new `SENSITIVE_ACCESS_GRANT_RETURNED`.
   - Once part of the file has left, the grant stays used.
   - Every failure is audited as `DOCUMENT_DOWNLOAD_FAILED` with the code, the exception class, the bytes sent and, for Highly Confidential, the request and whether its grant is still used.
   - New repository method `giveBackGrant`. It is one UPDATE that only changes a USED grant of that person, document and kind. No schema change: the existing columns and checks allow USED → APPROVED.
4. **Print (item 4).** The screen prints only after Vero has answered `print-event`. If the print event can't be written after the grant was used, the grant is given back (audited) and the print fails.
5. **Screens (item 5).**
   - New `DOCUMENT_PROBLEM_WORDS` and `documentProblemText` in `problemWords.ts`. These hold the catalog's sentences, so the server's text is never shown. The viewer uses them for opening, pages, find, download, print, tags and comments. The screen shows the code, and for Vero's own failures (003, 004, 005, 007, 009) the reference.
   - The download now checks that it is complete (`contentDownload` in `api.ts`). A broken connection, or a file shorter than the announced length, shows "The download didn't finish. Ref … (SDV-DOC-009)".
   - `auditWords.ts`: the download-failed sentence names why (007, 008, 009), and there are sentences for a grant used and a grant given back. Both are in the "Downloads, prints and exports" group.
6. **Catalog.** `docs/api/error-codes.md` has new "Viewing, print and download (DOC)" and "Search (SCH)" sections in the required layout. They include the grant rule. The 13 task-0034 entries are marked "released in task 0043". The KEY-001 note and the area list are updated.

## Commands run and results

| Command | Result |
|---|---|
| `.\stop-local.cmd` (before building) | Stopped backend and frontend; containers left running |
| `mvn -o test` (backend, during the work) | First run: 1 failure, the catalog test (new codes not listed yet). After the catalog: all pass |
| `cd frontend/sdv-ui; npm run typecheck` | Passed |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS**, exit 0, frontend built |
| `.\Test-SDV-SchemaUpgrade.ps1` (scratch DB `sdv_schema_test`, dropped) | **PASS** in 52 s. Step 4f ran `AccessRequestPostgresTest` with the new give-back checks |
| `.\Test-SDV-CleanInstall.ps1` (scratch DB `sdv_install_test`, dropped) | **PASS** in 160 s, 34 changesets |
| `.\start-local.cmd` | Started |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` | **Not run.** The session's permission check refused it. The owner runs it |
| `npm run ui:capture` (on the data as it was, without the reset) | **25 passed, 0 failed, 11 skipped** (5.9 min) |
| Live: `GET /api/v1/business/documents/<unknown id>/viewer` | 404 `SDV-DOC-001` with the catalog sentence; `reference` equals `X-Correlation-Id` |

## Tests: counts, and any failures with their output

Full build: sdv-application 24, sdv-security 50, sdv-storage 2, sdv-crypto 4, sdv-web 498 (8 skipped: the PostgreSQL tests, which the schema check runs). 0 failures.

New tests:
- `DownloadGrantFirstByteTest` (9 tests). It uses the real download service, guard, grant store and audit; only storage and decryption are faked. It covers:
  - success uses the grant once;
  - the key service failing before the first byte, then storage failing to open: DOC-007, the grant is given back twice, then still works exactly once;
  - a failure after 1,000 bytes: DOC-009, the grant stays used, the audit shows bytes sent and `grantUsed: true`;
  - a bad fingerprint found at the end: DOC-009, the grant is used;
  - two downloads racing on one grant: exactly one downloads, the other gets SEN-009, audited as refused;
  - DOC-008 uses no grant;
  - DOC-001 and DOC-002;
  - a print whose event fails gives the grant back, then works once;
  - DOC-010.
- `DocumentViewerCodesTest` (5 tests): DOC-003 (open, read, key service, metadata), DOC-004 (decrypted fingerprint, encrypted fingerprint, decryption check, too large), DOC-005, DOC-006 (page, zoom, past the last page, previews, find), DOC-001 and DOC-002.
- `DocumentCodesAnswerTest` (4 tests): the answers' code, sentence and reference; nothing from the cause in the body; one log line per failure at the right level, with the cause.
- `AccessRequestPostgresTest` (real PostgreSQL, in the schema check): a give-back is refused for another person, kind, document or tenant, and when the grant isn't used. When it succeeds, the row is APPROVED with no use time and passes the table checks, and the audit row is there.

Changed tests (assertions added, none removed or loosened): `BusinessRecordRefCodecTest` (SCH-002), `BusinessExperienceServiceHomeTest` (SCH-003, SCH-001), `QuickSearchTest` (SCH-001). `ApprovalsFixture` got the in-memory `giveBackGrant`.

## Files changed

- Domain: `SdvErrorCode.java` (13 codes, WARN and ERROR log levels), `CodedFault.java` (new).
- Application:
  - `ControlledDownloadService.java`, `DocumentViewerService.java`, `DocumentActionService.java`;
  - the two `…UnavailableException.java` (now coded);
  - `DocumentRefusals.java` and `RecordRefusals.java` (new);
  - `DocumentAccessGuard.java`, `SensitiveAccessGrants.java`, `SensitiveAccessGrantStore.java`, `AccessRequestRepository.java`;
  - `BusinessExperienceService.java`, `BusinessRecordRefCodec.java`, `RecordKey.java`, `SearchValueNormalizer.java`, `BusinessRecordRepository.java`.
- Persistence: `JdbcAccessRequestRepository.java` (`giveBackGrant`).
- Web: `FoundationExceptionHandler.java` (one handler for coded faults), `ErrorReplies.java` (ERROR or WARN by code for 4xx), `BusinessExperienceController.java`.
- Frontend: `api.ts`, `r7/liveApi.ts`, `r7/Viewer.tsx`, `r7/problemWords.ts`, `r7/auditWords.ts`.
- Docs: `docs/api/error-codes.md`, this report.
- No schema change, no new dependency, no file deleted.

## Commits (hash and message), or "not committed"

- `c8207b1` docs(work): task 0043, fix 6 viewing, print and download
- `89c2395` feat: viewing, print and download codes SDV-DOC-001..010 and SDV-SCH-001..003; grant used at the first byte (task 0043)
- This report is committed after these.

Not pushed, not merged. No PR opened.

## Not done, and why

- **`Seed-SDV-Canvas-Demo.ps1 -Reset`**: the session's permission check refused it (as the task foresaw). The tour passed on the data as it was. Please run the reset by hand and, if you want, the tour again.
- **The `RecordKey` checks that build a key** (`:33`, `:35`, `:43`, `:58`) keep their generic 400. The catalog lists them under SCH-002, but they also run when documents are received, so a code there would change intake answers. Only reading a key (`parse`) is SCH-002 now.
- **Search with an unknown `sourceId`** keeps its 404 `NOT_FOUND`. It isn't in the catalog. Opening a record of another tenant's sending system is SCH-002.

## Questions for the owner

1. **Status of DOC-003, 004, 005, 007:** they stay 422, as before, because 422→500 wasn't among the approved status changes (owner answer 5). Should they move to 500 (or 503 for storage) in a later task? Their log line is already ERROR (WARN for DOC-005).
2. **Log levels:** the task says WARN for refusals. I used WARN for the refusals that come from something happening (DOC-008, a document changed mid-download) and kept the catalog's INFO and DEBUG for "not there" and "request not valid" (DOC-001, 002, 006, 010, SCH-*). WARN on every bad page number would flood the log. DOC-005 is WARN as the catalog says, not ERROR. Is that right?
3. **"First byte sent"** means the first byte handed to the answer. A failure while those bytes are still in the server's buffer (not yet on the wire) keeps the grant used. This fails closed. Is that acceptable, or should a failure before the answer is committed also give the grant back?
4. **Key service failures** are reported as DOC-003 (viewer) or DOC-007 (download) until fix 5 adds KEY codes.

## Suggested next step

Owner runs `.\Seed-SDV-Canvas-Demo.ps1 -Reset` (and the tour if wanted), reviews the branch, and merges `fix/viewing` through a PR.
