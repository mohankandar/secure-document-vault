# Report 0036: Error plumbing (failure-catalog fix 1)

Date: 2026-11-21
Branch: `fix/error-plumbing` (from `release/vero-r7` at `0e70f26`)
Outcome: DONE, with three questions for the owner (below). Backend only, plus test and doc updates; no schema change; no dependency added.

## What was done

1. **One error body everywhere:** `{"code", "message", "reference", "timestamp"}`. Three places now write it, all through the new `ErrorReplies` class:
   - **`FoundationExceptionHandler`:**
     - every refusal keeps its code and status and gains `reference`;
     - a new last-resort handler takes every other exception.
   - **`SdvErrorController` (new):** replaces Spring Boot's `/error`. It covers sign-in refusals from the security filters (401, 403) and failures thrown by filters. Each status has a fixed sentence; the filters' own text is never sent.
   - **`SchemaGateFilter`:** the 503 while the database doesn't match.

   The body is written as JSON whatever the `Accept` header says, so a viewer or download request also gets the code and the reference. Nothing is written once an answer has started (a download that failed part-way).
2. **Reference:** the request's correlation ID.
   - It is the same as the `X-Correlation-Id` header.
   - It is on the log line.
   - It is on any audit event the request wrote, because services already store that ID.
   - `CorrelationIdFilter` also keeps it as a request attribute, so `/error` still has it after the filter has cleared the MDC.
3. **Bearer-token answers (enterprise sign-in)** used to send 401 or 403 with an empty body. `SecurityConfiguration` now keeps Spring's status and `WWW-Authenticate` header, then hands the answer to `/error` for the body. The codes stay `UNAUTHORIZED` and `FORBIDDEN` until fix task 3 (sign-in) gives them AUT codes.
4. **Status mapping, as the task lists it:**

   | Failure | Before | Now |
   |---|---|---|
   | `DataAccessException`, or a `SQLException` anywhere in the cause chain (so a transaction that can't start counts too) | 500, Spring's body | **503 SDV-SYS-014** |
   | Spring MVC can't read the request: bad UUID, bad JSON, missing header or parameter, failed validation | 400, Spring's body | **400 SDV-SYS-015**; the message names the field, never the value |
   | Wrong method, wrong content type, unacceptable `Accept`, unknown address | 405, 415, 406 or 404, Spring's body | Same status, **SDV-SYS-015** body (see question 1) |
   | Internal checks (`IllegalStateException` → new `InternalCheckFailed`) | 409 `CONFLICT` with the internal sentence | **500 SDV-SYS-016**; the sentence goes only to the log |
   | Audit couldn't be written (`InternalCheckFailed.auditNotWritten`) | 409 `CONFLICT` | **500 SDV-SYS-017** |
   | No storage space (`NoWritableStorageException`) | 409 `CONFLICT` | **503 SDV-STO-001** |
   | Anything else | 500, Spring's body (or none) | **500 SDV-SYS-013** |
   | Schema gate | 503 `DATABASE_OLDER`, `_NEWER`, `_UNREACHABLE` | 503 **SDV-SYS-001**, **-002**, **-003** (owner answer 3) |

   - `InternalCheckFailed` does not extend `IllegalStateException`. Nothing that handles a conflict catches it, and intake doesn't count it as a refusal.
   - The same goes for `NoWritableStorageException`, which no longer extends `IllegalStateException`.
5. **`InternalCheckFailed` replaces `IllegalStateException` at 53 internal checks:**
   - 40 for SYS-016: the SHA-256 checks, the row counts, and the unreadable stored values;
   - 13 for SYS-017: audit JSON that couldn't be written.

   That is exactly the `IllegalStateException` lines that catalog 0034 lists under SYS-016 and SYS-017. The line numbers still matched.
6. **Logs, one line per error:**
   - **ERROR** for every 5xx: code, reference, status, route pattern (never the IDs in the path) and the cause.
     - SYS-013, SYS-016, SYS-017 and STO-001 log the stack trace.
     - SYS-014 logs only the exception classes, the SQL state and the first Vero method. The SQL and PostgreSQL's message can hold the values sent.
   - **WARN** for a refusal whose code the catalog marks "needs a person" (`SdvErrorCode.needsPerson()`). Of the codes this task sends, only SYS-001, SYS-002 and STO-001 carry the mark, and they are 5xx answers, so they log at ERROR. The WARN path is ready for the later fix tasks.
   - **INFO** for SYS-015.
7. **Codes:**
   - Nine codes moved from `####` (proposed) to `###` (released) in `docs/api/error-codes.md`, in new "Server (SYS)" and "Storage (STO)" sections, each with status, meaning, what the user does and what support says: SYS-001, 002, 003, 013, 014, 015, 016, 017 and STO-001.
   - Their catalog blocks stay as notes, with a "Released" line.
   - The introduction now shows `reference` in the body.
   - `problemWords.ts` and `auditWords.ts` read none of the changed codes, so they are unchanged.
8. **OpenAPI (`docs/api/sdv-ingestion-v1.openapi.json`):**
   - `Error` gains `reference` and `required: [code, message, reference, timestamp]`.
   - `Unauthorized` and `Forbidden` now have the body.
   - New `ServerError` (500), `ServiceUnavailable` (503) and `UnsupportedMediaType` (415) responses.
   - Every operation lists 400, 500 and 503; the upload also lists 415.
   - The hand formatting is kept, and the file still parses.
9. **Other places that read `DATABASE_OLDER`** now expect `SDV-SYS-001`: `Test-SDV-CleanInstall.ps1` (one assertion) and two mocked answers in `e2e/sdv-screen-tour.spec.ts`.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test-compile` (in `backend`) | Passed |
| `mvn -o test -fae` (in `backend`), first run | 5 failures, all expected from the change (next section), then fixed |
| `mvn -o test -fae`, second run | Passed: `sdv-web` 333 tests, 0 failures, 5 skipped |
| `.\Build-SDV-All.ps1`, first run | Failed: couldn't delete `sdv-web-0.1.0-SNAPSHOT.jar`, because the running local Vero held it. Not a code problem. |
| `.\stop-local.cmd`, then `.\Build-SDV-All.ps1` | **BUILD SUCCESS**. Backend 413 tests (24 + 50 + 2 + 4 + 333), 0 failures, 0 errors, 5 skipped (the Postgres-only tests, as before). Frontend build passed. |
| `.\start-local.cmd` | Started. `sdv-postgres` was left running by the stop; `sdv-vault` was not touched. |
| Live checks with `curl` against http://localhost:8280 | Body, status and reference as expected (list below) |
| `npm run ui:capture`, run 1 | **1 failed**, 24 passed, 11 skipped. Tenant IT Administrator (desktop): `page.screenshot` timed out "waiting for fonts to load". |
| `npm run ui:capture`, run 2 | **2 failed**, 23 passed, 11 skipped. Platform Operations (desktop): the Overview stayed on "Loading…" for 15 s. Business User (iPad): "browser has been closed" afterwards. |
| `npm run ui:capture`, run 3 | **Passed**: 25 passed, 11 skipped, the same as report 0035 |
| `npm run typecheck` | Passed |

Live checks (local-header sign-in):
- no sign-in: 401 `UNAUTHORIZED`, with the reference equal to the header;
- the reference is the caller's own `X-Correlation-Id` when one was sent;
- unknown address: 404 `SDV-SYS-015`;
- bad JSON on `/business/search`: 400 `SDV-SYS-015`;
- `DELETE` on `/business/search`: 405 `SDV-SYS-015`, with `Allow: POST`;
- a viewer request with `Accept: application/pdf` and a document ID that isn't a UUID: 400 `SDV-SYS-015` "the documentId is not valid", as JSON.

**About the two failed tour runs.** I judged them timing problems, not this change:
- each failure was in a different test;
- both were waits running out: fonts, and a screen still loading;
- straight after run 2, the Overview's request, `GET /api/v1/operations/summary`, answered 200 three times in about 0.05 s;
- the screen showed "Loading…", not an error;
- run 3 passed with no change in between.

I have not proved that the same thing happens without this change. Report 0035's runs were each preceded by a demo reset; mine were not.

## Tests: counts, and any failures with their output

**New tests:**
- `ErrorPlumbingTest` (9). Each checks the status, the code, exactly the four body fields, that the reference equals the header, and that no forbidden text is in the body (exception classes, `java.`/`org.`/`com.sdv`, SQL, paths, the token and the messages used).
  - 500 SDV-SYS-013. **The body's reference equals the reference on the single ERROR log line**, which carries the cause.
  - 503 SDV-SYS-014 for a SQL error and for a lost connection. The log line has the SQL state but no SQL, no values and no stack trace.
  - SDV-SYS-015:
    - a bad UUID (the field named, the value not echoed, an INFO line);
    - a missing header;
    - bad JSON;
    - 415 with the accepted type;
    - 405 with `Allow`;
    - no ERROR lines for any of these.
  - 500 SDV-SYS-016, not 409. The check's sentence is only in the log, and `refusalCode` gives null, so it isn't counted as a refusal.
  - 500 SDV-SYS-017.
  - 503 SDV-STO-001, not 409, logged at ERROR, not counted as a refusal.
  - A "needs a person" refusal gives one WARN line; another refusal gives none.
  - `/error`: 401 and 403 get the body with the request's reference; a database failure thrown by a filter gets 503 SDV-SYS-014 and an ERROR line.
  - A refused bearer token keeps 401 and `WWW-Authenticate` and goes on to `/error`.
- `IntakeRefusalsTest.theBodysReferenceIsTheAuditEventsCorrelationId`: a refused upload's body reference equals the `INTAKE_REFUSED` event's correlation ID.
- `SchemaGateFilterTest.anUnreachableDatabaseAtStartIsSys003`.

**Existing tests changed, because this task changes the code or status they check:**
- `ErrorCodesDocumentedTest.theErrorBodyCarriesTheCodeAndTheHttpStatusIsUnchanged`: the coded 403 body now has 4 fields, not 3 (the reference is added).
- `ErrorCodesDocumentedTest.everyCodeIsListedWithMeaningUserActionAndSupportText`: failed until the nine codes were documented; the test itself is unchanged.
- `IntakeRefusalsTest.uncodedRefusalsAreRecordedWithTheCodeTheSenderGotAndFailuresAreNot`: the server failure used to escape unhandled (`assertThrows(Exception)`). It is now answered 500 SDV-SYS-013. The test still checks that it isn't recorded as a refusal.
- `SchemaGateFilterTest` (two tests): `DATABASE_OLDER` → `SDV-SYS-001`, now with reference and time; `DATABASE_NEWER` → `SDV-SYS-002`.
- `SensitivityReportServiceTest.whatTheRepositoryCantExplainStopsTheReport` and `StewardNoticeServiceTest.aNoticeOfAKindVeroDoesntKnowIsNotShownAsSomethingElse`: they expect `InternalCheckFailed` where they expected `IllegalStateException`.

Counts: `sdv-web` went from 322 to 333 tests (+9 `ErrorPlumbingTest`, +1 intake, +1 gate). Backend total 413, 0 failures, 5 skipped.

## Files changed

- **New:**
  - `backend/sdv-domain/.../error/InternalCheckFailed.java`
  - `backend/sdv-web/.../foundation/ErrorReplies.java`
  - `backend/sdv-web/.../foundation/SdvErrorController.java`
  - `backend/sdv-web/src/test/.../foundation/ErrorPlumbingTest.java`
- **Changed (web, security, domain):**
  - `FoundationExceptionHandler.java`
  - `CorrelationIdFilter.java`
  - `SchemaGateFilter.java`
  - `SecurityConfiguration.java`
  - `SdvErrorCode.java` (9 codes, `needsPerson()`)
  - `NoWritableStorageException.java`
- **Internal checks** (`IllegalStateException` → `InternalCheckFailed`, one token per line plus the import), 26 files:
  - `AccessRequestService`, `AuditQueryService`, `BusinessExperienceService`, `BusinessRecordRefCodec`, `RecordKey`, `DocumentIntakeService`, `ValidatedPdfInputStream`, `ControlledDownloadService`, `LegalHoldService`, `CorrectiveRedactionService`, `IntakeReportService`, `SensitivityReportService`, `StewardNoticeService`, `RetentionDeletionService`, `RetentionPolicyService`, `RoleJson`, `SensitivityLevelService`, `StorageVolumeAdminService`, `SubmissionService`;
  - `JdbcAccessRequestRepository`, `JdbcRecordSensitivityRepository`, `JdbcFoundationRepository`, `JdbcSecureIngestionRepository`, `JdbcLegalHoldRepository`, `JdbcSensitivityReportRepository`, `JdbcRetentionRepository`.
- **Tests changed:** `ErrorCodesDocumentedTest`, `IntakeRefusalsTest`, `SchemaGateFilterTest`, `SensitivityReportServiceTest`, `StewardNoticeServiceTest`.
- **Docs and scripts:**
  - `docs/api/error-codes.md`
  - `docs/api/sdv-ingestion-v1.openapi.json`
  - `Test-SDV-CleanInstall.ps1`
  - `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
- `docs/work/0036-task.md` and this report.

Not touched: `Start-SDV-Local.ps1` and `Stop-SDV-Local.ps1` (local changes left as they were, not committed), `.runtime`, the Docker containers and the Vault.

## Commits (hash and message), or "not committed"

- `b2e0c99` fix: one error body with a reference, last-resort handler, SYS/STO codes (task 0036)
- A second commit adds this hash to the report.

On `fix/error-plumbing` only; not pushed, not merged.

## Not done, and why

- **Checks the catalog lists under SYS-016 that are not `IllegalStateException`.** The task names `IllegalStateException`, so these are left as they are. They should go to the same type in a later task.
  - bare `orElseThrow()`: today 404 "No value present";
  - the internal "required argument" checks (`IllegalArgumentException`, today 400);
  - `IntakeReportService.java:162` (`IllegalArgumentException`);
  - the `UnsupportedOperationException` defaults.
- **Today's own codes that this task doesn't send** stay until their fix tasks: `PDF_TOO_LARGE`, `INVALID_PDF`, `EXPORT_TOO_LARGE`, `VIEWER_UNAVAILABLE`, `DOWNLOAD_UNAVAILABLE`, `REDACTION_FAILED`, the processing codes, and the generic `BAD_REQUEST`, `CONFLICT`, `NOT_FOUND`, `UNAUTHORIZED` and `FORBIDDEN`. They now carry the reference.
- **Refusal messages are unchanged.** Uncoded `IllegalArgumentException`, `IllegalStateException` and `NoSuchElementException` refusals still send their exception message, as before. The fix tasks that code those areas replace them with fixed sentences.
- **Intake does not yet record 5xx failures.** That is fix task 4.

## Questions for the owner

1. **405, 415, 406 and 404 keep their status.** The task says "Spring MVC request errors → 400". But owner answer 5 lists only 409→503, 409→500, 401→403 and 404/409→403, and senders may branch on 415. So I sent SDV-SYS-015 with Spring's own status for a wrong method, content type or `Accept` header, and for an unknown address. Should these become 400 as well?
2. **Two checks that a concurrent request can trip.** Two of the row-count checks the catalog lists under SYS-016 are at `JdbcAccessRequestRepository.java:101` (a decision on a request that is no longer pending) and `JdbcLegalHoldRepository.java:103` (a hold already released). Two people acting at the same moment can make them fail. They were 409 and are now 500 SDV-SYS-016, "nothing was changed", which is true but less helpful. Should a later task send APR-008 or HLD-008 there?
3. **Shared delivery contract.** These status changes are now live for senders: 409→503 for no storage, 409→500 for internal checks and audit failures, and no-handler failures now 503 (database) or 500 instead of Spring's 500 body. Error bodies now carry `reference`. Please add them to the shared delivery contract (owner answer 5).

## Suggested next step

Review and merge `fix/error-plumbing`. Then fix task 2 (start-up), which builds on SYS-001 to 003 and the ERROR logging added here.
