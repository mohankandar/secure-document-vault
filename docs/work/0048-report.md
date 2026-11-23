# 0048 report: Quarantine, server side

Branch `feature/quarantine` from `release/vero-r7` at `6fd09b5` (after 0041 was merged). Run overnight under `QUEUE-OVERNIGHT.md`.

## Outcome

**STOPPED before merge: the code is built and tested, but the screen tour could not run.** The local start no longer updates the schema itself (task 0041). Applying changeset 036 to the local `sdv` database (`.\Update-SDV-Schema.ps1 update`) was **refused by the permission check**, so the app could not start for `ui:capture`. Only the owner can run that step, as with the seed reset. By the queue rules the PR is left open and 0049 and 0050 (which depend on 0048 being merged) were not started.

**To finish (owner):**
1. `.\Update-SDV-Schema.ps1 update` on the local `sdv` database. It adds 036: three new, empty tables; no existing table or row changes.
2. `.\start-local.cmd`, then `cd frontend/sdv-ui; npm run ui:capture`. Expect unchanged counts: no screens changed.
3. If the tour passes and CI's `ci-passed` is green, merge the PR.

## What was done

- **Tables (changeset 036, owner-approved):**
  - `quarantine_incidents`: name, reason, scope DOCUMENT or FILTER, who, when, OPEN or RELEASED.
  - `quarantined_documents`: at most one quarantine in force per document (unique partial index); released rows stay as history, with who, when, the reason and the approving request.
  - `quarantine_release_requests`: PENDING, APPROVED, DECLINED or EXPIRED; one waiting request per incident; a database check refuses deciding your own request.
- **Capabilities:**
  - `QUARANTINE_DOCUMENTS`: Document Steward and Restricted Steward.
  - `QUARANTINE_BY_FILTER`: Restricted Steward only.
  - Nobody else has either, and they are not configurable in Roles & permissions.
  - `Clearance` carries "reviews quarantine", always together with the levels the person can open.
- **Access (`DocumentAccessGuard`, every document path):**
  - A quarantined document is refused with **SDV-QUA-001** ("This document is temporarily unavailable.": no level, no reason). The refusal is audited as `DOCUMENT_ACCESS_REFUSED` with the code.
  - The exception is a reviewer who can open its level. A reviewer may open it, load pages and previews, search its text, see tags, comments, activity and level history, and correct (redact) it.
  - Print and download are refused for everyone, reviewers included, until release.
- **Search rows and record panel:**
  - Search rows have `unavailableDocumentCount`, and the levels of quarantined documents are left out of `restrictedLevel`. Counts are unchanged.
  - The record view lists `unavailableDocuments` with the reference only, never the level.
  - A reviewer sees the document among `documents`, marked `quarantined: true`.
- **Service and API (`QuarantineService`; `/api/v1/quarantine/*` for Stewards, `/api/v1/admin/quarantine-releases` for the Business Admin):**
  - Quarantine one document, into a new incident or an open one.
  - By filter: the server counts first, then quarantines exactly that count, or refuses with SDV-QUA-009.
  - The incident list and the incident view. Rows the person can't open are counted, not shown.
  - Single release by a reviewer. The incident closes when nothing is left.
  - Whole-incident release request: one at a time; the requester must be able to open every level in the incident.
  - Business Admin approve and decline: one by one, no bulk endpoint, never their own; requests expire on the Approvals window (7 days, `sdv.approvals.pending-days`).
  - Exposure report and CSV.
- **Exposure report:**
  - Counts by level.
  - Per document: the primary identifier, received date, pages, and who opened, printed or downloaded it before its quarantine (from the audit).
  - The CSV carries a reference and the SHA-256 of the file, in `X-SDV-Export-Reference` and `X-SDV-Export-Fingerprint` and in the audit event.
  - Never a file name, title, content or reason.
- **Retention:** the cleanup's "held" query also counts a quarantine in force, so deletion pauses as for a legal hold. Hold and quarantine are independent.
- **Audit:** `QUARANTINE_INCIDENT_CREATED`, `DOCUMENT_QUARANTINED`, `DOCUMENT_QUARANTINE_RELEASED`, `QUARANTINE_INCIDENT_CLOSED`, `QUARANTINE_RELEASE_REQUESTED`, `_APPROVED`, `_DECLINED`, `_EXPIRED`, `QUARANTINE_EXPOSURE_EXPORTED`. Each records who, what, when and the incident ID, never a reason. Each has a sentence in `AuditSentences` and `auditWords.ts`.
- **Error codes:** `SDV-QUA-001` to `-011` in `SdvErrorCode`, `docs/api/error-codes.md` (each with "What the user should do") and `problemWords.ts`.
- **Checks:**
  - `Test-SDV-SchemaUpgrade.ps1` now defaults to `-From 036`. Step 4n checks that the tables are there and empty and that their checks refuse bad rows; the new `QuarantinePostgresTest` runs in step 4f.
  - The CI list of PostgreSQL-only tests includes `QuarantinePostgresTest`.
  - Fixed while doing this: at `-From 036` the previous version already ran 035, so one role table is expected, not three.
- `docs/work/0041-report.md`: the Commits and Next step sections brought up to date (merged, tagged).
- No screens (0049). No notifications. New arrivals are not held into an incident (V2).

## No other product's name or behaviour in code

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken overnight

1. **Whole-incident release requests have their own table and endpoints** (`/api/v1/admin/quarantine-releases`), checked with the Approvals permission (`APPROVE_SENSITIVE_ACCESS`, refusal SDV-APR-005). `access_requests` and its endpoints are untouched; the 0049 screens show them in the Approvals list. There is no bulk endpoint for them.
2. **A reviewer may read and correct only.** Tags, comments, a level change and print or download are refused while quarantined, for reviewers too (SDV-QUA-001).
3. **Quarantine counts as "held" for retention.** The sweep's "kept on hold" number includes quarantined documents.
4. **The filter matches field values exactly**, normalized with the field's current normalization, as search stores them; older normalizations are not tried, so it can match fewer documents, never more. Field filters need a sending system. The received dates are both required (whole days, UTC). At most 1000 documents per filter quarantine.
5. **The requester of a whole-incident release must be able to open every level in it** (otherwise SDV-QUA-002). The Business Admin's view shows the incident name, document count, counts by level, first and last received dates and the requester; **no reason**: neither the incident's nor the request's.
6. **The request's reason is stored as each document's release reason** when it is approved (the table requires a release reason).
7. **When an incident empties by single releases, a waiting request for it ends as EXPIRED** (audited as `QUARANTINE_RELEASE_EXPIRED` by Vero).
8. **The exposure report hides levels the person can't open** (counted as `hiddenCount`). Only the CSV export is audited, not viewing the report (as the other reports).
9. **The reference and fingerprint of the exposure CSV** are a random UUID and the SHA-256 of the CSV bytes, in response headers and the audit event. 0050 adds a register; this report is not registered yet.
10. `Clearance` equality stays on the levels alone; review rights are checked separately.

## Tests changed to match (said, as CLAUDE.md asks)

- `FinalRoleMatrixTest`: the expected matrix now includes `QUARANTINE_DOCUMENTS` for both stewards and `QUARANTINE_BY_FILTER` for the Restricted Steward, plus two rows for them. This is the owner-approved role decision; without it the test fails by design ("a capability added without a decision here fails").
- `RetentionLegalHoldTest`: the expected text of the cleanup's "held" SQL now includes the quarantine `union all`.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (app stopped first) | PASS: backend 634 tests, 0 failures, 0 errors, 14 skipped (PostgreSQL-only tests, which need the scratch database); frontend build OK |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 20 tests |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 036`) | PASS: 036 alone; 4n quarantine tables and their checks; 4f all 9 PostgreSQL-only tests pass, `QuarantinePostgresTest` included |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 035` | PASS (the older path still works) |
| `.\Test-SDV-CleanInstall.ps1` | PASS |
| `.\Update-SDV-Schema.ps1 update` on local `sdv` | **Refused by the permission check (not run)** |
| `.\start-local.cmd` | Backend did not become healthy: the database is at 035, so it waits for the schema step. Stopped with `.\stop-local.cmd` (containers untouched) |
| `npm run ui:capture` | **Not run** (app not started) |

## Tests: new

- `QuarantineServiceTest` (12): who quarantines, by level; one quarantine at a time; joining an open incident; the filter count equals what is quarantined; a count mismatch quarantines nothing; filter validation; review hides levels; single release and closing; request plus approval by someone else; no self-approval; decline; expiry after 7 days; a waiting request ends when the incident empties; exposure contents, never content or reasons; the CSV fingerprint; audit without reasons.
- `QuarantineAccessTest` (6): SDV-QUA-001 for every attempt for others; reviewers read and redact but never print, download, tag or comment; Highly Confidential only for a Restricted Steward, and its level is never said; the refusal is audited.
- `QuarantineControllerTest` (4): through the real permission check, roles for each endpoint (SDV-QUA-002, SDV-QUA-003, SDV-APR-005); no bulk decision; export headers.
- `RecordQuarantineReadTest` (3) and a new `QuickSearchTest` case: "temporarily unavailable" in the record panel and search rows, no level given away, counts unchanged.
- `QuarantinePostgresTest` (1, PostgreSQL): real tables and their checks, the filter count within the entity, the guard refusal and its audit, retention skipping quarantined documents and deleting after release, exposure from the audit, request, own-decision refusal (service and table), approval, no reasons in the audit.

## Commits

See the PR. Not merged (see Outcome).

## Questions for the owner

1. The queue said the seed reset is refused; the schema step on the local database is refused the same way. Should a later queue approve `Update-SDV-Schema.ps1 update` on the local `sdv` database, so the tour can run overnight?
