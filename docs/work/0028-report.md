# Report 0028: Level changes and approvals (S5 + S6)

Date: 2026-11-20
Branch: `feature/sensitivity-5` (from `feature/sensitivity-4`)
Outcome: DONE. One schema change, approved with the task: changeset 027 (table `access_requests`, two name columns on `legal_holds`). The build (338 tests), both schema checks with two PostgreSQL tests, the clean install, the live checks and the screen tour pass. No screens for the new functions (the task says they come later, S9a).

## What was done

**0. Carry-over from report 0027**
1. **No record titles in admin views.**
   - Problems (`/operations/summary`, `problemRecords`) now sends only the record number, its label and the sending system. The title fields are not even read.
   - On the Problems screen the title line under the record number is gone.
   - The audit list, its export and the legal-hold views never sent a title. Tests prove none reaches these responses.
2. **Document ID:** unchanged. It stays in the API and under Technical details, never as a list column.
3. **Who placed a hold:**
   - New columns `legal_holds.placed_by_display_name` and `released_by_display_name`, filled from the signed-in session at the time.
   - The hold views send `placedByName` and `releasedByName`.
   - Older holds keep no name; nothing is made up.
4. and 5. Agreed points, nothing to change.

**1. Schema** (`027-access-requests.sql`)
- `access_requests` columns: ID, tenant, kind, document ID, requested level, reason, requester, requested at, status, decided by, decided at, expires at, used at. Also:
  - For a whole-record request: the sending system, record key and key SHA-256 (as `record_sensitivity_levels`), instead of a document.
  - The requester's and decider's names, filled at the time, as in 0.3.
- Kinds: `PRINT`, `DOWNLOAD`, `LOWER_LEVEL`, `LOWER_RECORD_LEVEL`. Statuses: `PENDING`, `APPROVED`, `DECLINED`, `USED`, `EXPIRED`.
- `expires_at` holds two deadlines:
  - while pending: when the request expires undecided;
  - once a print or download is approved: when its grant ends.
- Checks:
  - a document request names a document and no record, and the other way round;
  - only a lowering names a level;
  - the reason is 3–500 characters;
  - a decision always has both who and when;
  - **decided_by can never equal requested_by**;
  - used once, and only a print or download grant.
- Partial unique indexes allow one pending request per person and target. More indexes for the pending list, the grant lookup and the requester's list.
- No existing row changes.

**2. Raise and lower** (`SensitivityLevelService`, capability `CHANGE_SENSITIVITY_LEVEL`)

Endpoints, each with `{level, reason}`:
- `POST /api/v1/business/documents/{id}/sensitivity/raise` and `…/lower-request`;
- `POST /api/v1/business/records/{recordRef}/sensitivity/raise` and `…/lower-request`. The record reference is the one search hands out.

Rules:
- **Raise** applies at once, only to a level higher than now.
  - For a document, "now" is its **effective** level, the one people see.
  - A Steward may raise to Highly Confidential. The answer then says `canStillOpen: false`, and opening it is refused from then on.
- **Lower** creates a request and applies only when approved.
  - A document's own level must come down.
  - A Steward may ask only from Confidential (SEN-015). A Restricted Steward may ask from higher.
- The document must be one the person can open (SEN-008, audited) and available (SEN-017).
- **Record level:** the same rules. Raising inserts or raises the record's row. Lowering is a `LOWER_RECORD_LEVEL` request, decided one at a time.
- **Audit:** `DOCUMENT_SENSITIVITY_RAISED` / `_LOWERED` and `RECORD_SENSITIVITY_RAISED` / `_LOWERED`.
  - Each carries who, when, from, to and **the reason**, as the task asks.
  - Each is written in the same transaction as the change.

**3. Highly Confidential print and download**
- A Restricted Steward asks with `POST /api/v1/business/documents/{id}/access-requests` and `{kind: PRINT|DOWNLOAD, reason}`.
  - Only for an available document that is Highly Confidential at its effective level and that they can open.
- An approval is a **grant**: one document, one person, one kind, one use, for 24 hours after approval.
- Print and download check for a grant before anything else happens:
  - **Print** uses the grant when the print is recorded.
  - **Download** only checks for it before sending headers. It uses the grant just before decrypting, after every other check.
  - A grant is used in a single statement (`for update skip locked`), together with its `SENSITIVE_ACCESS_GRANT_USED` event. Four simultaneous prints on PostgreSQL: exactly one succeeded.
- No grant, a used one, an expired one, another person's or the other kind: refused with **SDV-SEN-009**, and audited as before.
- The print and download events name the request they used.
- **Nobody decides their own request:** SDV-APR-006, audited as `ACCESS_REQUEST_DECISION_REFUSED`, with the table's check behind it. This matters for someone holding both Business Admin and Document Steward, which the separation rules allow.

**4. Approvals list** (`GET /api/v1/admin/access-requests`, capability `APPROVE_SENSITIVE_ACCESS`)

Each request carries only:
- request ID, kind;
- scope (`DOCUMENT` or `RECORD`);
- primary identifier and its label;
- received date and page count (a document), or document count (a record);
- position ("index 2 of 6"), only when another document on the record has the same received day (UTC) and page count;
- level (effective, or the record's), requested level;
- requester (name, or subject when there is none), reason, requested at;
- `documentId` (support only; the screen won't show it).

No title, file name, other case fields or content. The test checks the exact fields.

**5. Many requests**
1. Filters `?kind=` (comma-separated) and `?requester=` (name or subject). Oldest first. Grouped by primary identifier, groups ordered by their oldest request.
2. **Bulk:** `POST /api/v1/admin/access-requests/decisions` with `{decision, requestIds}` (1 to 100).
   - Each request is checked, decided and audited on its own.
   - The answer lists `APPROVED`, `DECLINED` or `REFUSED` with its code for each one.
   - Whole-record requests are refused in bulk (SDV-APR-009) and stay waiting.
   - Single decisions: `POST …/{id}/approve` and `…/decline`.
3. **Expiry:**
   - A pending request expires after 7 days. An unused grant expires after its 24 hours.
   - Each expiry is audited (`ACCESS_REQUEST_EXPIRED`, by Vero).
   - The requester's list (`GET /api/v1/business/access-requests`) shows `EXPIRED`.
   - The status changes when the lists or a decision are next read. Before that, a request or grant past its time is already refused, so nothing depends on when the status changes.
- **Per tenant:** server settings `sdv.approvals.grant-hours` (default 24) and `pending-days` (default 7), with `tenant-grant-hours` and `tenant-pending-days` written as `tenant id=number`. An unclear value stops the server from starting. See question 1.

**Error codes:** all in `SdvErrorCode` and `docs/api/error-codes.md` (meaning, user action, support text).
- New: SEN-010 to 017, and a new section APR-001 to 015.
- SEN-009 keeps its meaning ("needs an approval"). Its text now explains the grant.

**Audit sentences** for the new actions, so the IT Admin's audit list reads well.

## Commands run and results

| Command | Result |
|---|---|
| `.\stop-local.cmd` | stopped the app (it locked the jar); database and Vault left running |
| `mvn -o test` (during work) | 1 expected failure: the new codes weren't documented yet |
| `.\Build-SDV-All.ps1` (twice; the second after the fix below) | **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 258 = **338 tests** (316 before), 0 failures, 0 errors, 2 skipped (the two PostgreSQL tests, run by the schema check). Frontend build passed. |
| `npm run typecheck` | passed |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 027`) | first run **FAIL** (see "Tests"); after the fix **PASS**, 30 s, including step 4f, both PostgreSQL tests |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 026` | **PASS**, 31 s |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 52 s, 27 changesets |
| `.\start-local.cmd` | started; the local profile applied 027 to the dev `sdv` database |
| Live checks with curl (local headers) | all as expected, see below |
| `npm run ui:capture` | **20 passed, 4 skipped** (the usual 4), 6.0 min |

**Live checks** (dev data, document `dd7f…` on record 300000000007). Every level I changed was put back to Standard.
- Refusals by role:
  - Business User raise: 403 SEN-010;
  - Steward on the approvals list: 403 APR-005;
  - Steward asking to print: 403 APR-001;
  - Business Admin's own list: 403 APR-015.
- Raise to the same level: 409 SEN-013. Lower case: 400 SEN-011. Reason "ab": 400 SEN-012.
- Restricted Steward raises to Highly Confidential: 200. The Steward is then refused (SEN-008).
- Print without a grant: 403 SEN-009. Request: 201. Again: 409 APR-004.
- Approvals list: 200, position "3 of 5", no file name or title.
- Same subject approving: 403 APR-006. Approve: 200.
- With the grant:
  - print: 204, then again: 403 SEN-009;
  - download, approved in bulk: 200, a valid PDF, then again: 403 SEN-009.
- Lower request: 201, level unchanged. Approved: back to Standard.
- Record: raise to Confidential (200), and the Business User is locked out (403 SEN-008). Lower request in bulk: refused, APR-009. Approved alone: Standard, and the Business User opens the document again.

**Left in the dev `sdv` database:**
- changeset 027;
- 5 access requests: print and download, both used; two approved lowerings;
- the audit events of these checks (subjects `live-check-…`);
- **one row in `record_sensitivity_levels`** for record 300000000007, at Standard (question 2).

## Tests: counts, and any failures with their output

New, 22 test methods in the normal build:
- `SensitivityLevelChangeTest` (8):
  - raise rules and their audit;
  - Steward raising to Highly Confidential, then locked out;
  - effective level through the record;
  - refusal codes;
  - lowering only on approval, audited with the reason;
  - "from Confidential only";
  - record level both ways;
  - a stale lowering (APR-011).
- `AccessRequestApprovalsTest` (8):
  - grant used once; second use refused;
  - wrong person, wrong kind; check versus use;
  - expiry at 24 hours, per-tenant window;
  - request rules;
  - self-decision refused, also in bulk;
  - bulk with whole-record refusal and limits;
  - pending expiry at 7 days, seen as expired by the requester;
  - the approvals list's exact fields, position, groups, filters, tenants.
- `ApprovalsControllerTest` (4): through the real permission check, every role without the capability is refused with its code on every new endpoint, and audited. Codes pass through.
- `ProblemsAndAuditRecordsTest.theProblemsSummaryNeverCarriesARecordTitle` (1).
- `AccessRequestPostgresTest` (1, run by the schema check):
  - real repositories and services;
  - four racing prints, one wins;
  - expiry; tenants apart;
  - the table refuses a self-decision;
  - lowerings written; reasons kept out of request events;
  - 13 bad rows refused by name.

Changed tests (said here per CLAUDE.md):
- `ProblemsAndAuditRecordsTest`: the title assertions are replaced by "no title, title fields never read".
- `LegalHoldServiceTest`, `LegalHoldControllerTest`, `RetentionLegalHoldTest`, `LegalHoldPostgresTest`: the new name fields and arguments. One new assertion each for the names; the JSON field list includes them.
- `FinalRoleMatrixTest`: comment only. It now names the tests that prove the two conditional cells.
- `Test-SDV-SchemaUpgrade.ps1`:
  - `-From 027` is the default;
  - at the previous version it places a released hold and checks it is unchanged after 027, with no names;
  - it checks `access_requests`;
  - it runs both PostgreSQL tests.

Failures on the way:
1. `ErrorCodesDocumentedTest`, until the codes were documented.
2. One expectation in my own bulk test: a malformed reference ("nope") can't be audited against a request. The test now expects one refusal event, not two.
3. **First schema-check run:**
   - My PostgreSQL test reused a document ID of the hold test. That was a test mistake, fixed.
   - **It also found a real schema bug:** a lowering with no level, and a record request with no key hash, passed their checks, because a SQL check that comes out null lets the row in. Both checks were fixed in 027 before it was applied anywhere, and a test row was added. Then everything passed.

## Files changed

- **Schema:** `027-access-requests.sql` and `.yaml`, `db.changelog-master.yaml`.
- **Domain:** `SdvErrorCode`.
- **Application:**
  - new: `approvals/` (`AccessRequestService`, `SensitiveAccessGrantStore`, `ApprovalSettings`, kind and status), `sensitivity/SensitivityLevelService`, `access/SensitiveAccessGrants`, `business/RecordReferences`, `port/AccessRequestRepository`;
  - changed: `DocumentAccessGuard`, `DocumentActionService`, `ControlledDownloadService`, `RecordKey` (parse), `RecordSensitivityRepository` (`setLevel`), `LegalHoldService`, `LegalHoldRepository`, `ProblemRecordResolver`, `AuditSentences`.
- **Persistence:** new `approvals/JdbcAccessRequestRepository`; changed `JdbcRecordSensitivityRepository` and `JdbcLegalHoldRepository`.
- **Web:** new `admin/ApprovalsController`, `business/AccessRequestController`, `business/SensitivityLevelController`; `application.yml` (approval settings).
- **Frontend:** `Problems.tsx`, `liveApi.ts`, `r7.css` (title removed).
- **Tests:** as listed above, plus `ApprovalsFixture` and `TestRecordRefs`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1`, `docs/api/error-codes.md`, `docs/work/0028-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, tasks 0029–0034.

## Commits (hash and message), or "not committed"

One commit on `feature/sensitivity-5`: "feat: level changes and approvals; no titles in admin views; hold names (task 0028)". The hash is in `git log`. Not pushed, not merged.

## Not done, and why

- **Screens** for raising and lowering, requests and approvals: S9a, as the task says. The viewer still hides Print and Download for Highly Confidential documents, so a grant can only be used through the API for now.
- **A download that fails after its grant was used** (storage or decrypt error mid-stream) has used the grant; the person must ask again. I chose this over letting a grant survive a started decrypt.
- **No scheduled expiry run.** Statuses change when a list or decision is read; see 5.3.
- **Timing:** not measured. Each Highly Confidential print or download adds one indexed lookup and one update; each approvals list adds a few small queries per page.

## Questions for the owner

1. **Per-tenant windows** are server settings by tenant ID (no schema change). Is that enough, or should a Business Admin set them on a screen? That would mean two columns on `tenants`, a schema change.
2. **The live check left a record level row** (Standard) for record 300000000007. Because the loans system now "has record levels", SDV-SEN-007 refuses changes to which fields are identifiers for that sending system in your dev data. Shall I remove the row (one delete in the dev database), or leave it?
3. **"Higher than now" for a document** is taken as its effective level, so a Standard document in a Confidential record can't be "raised" to Confidential. Agreed?
4. **Reasons in the audit:** the level-change events carry the reason, as the task says. The request and decision events don't, following your hold decision. Agreed?
5. **`level` on the approvals list** is the document's effective level. For a lowering of a document whose record is higher, the approval changes only the document's own level. Should the list show the own level too?

## Suggested next step

Answer questions 1 to 5, then task 0029.
