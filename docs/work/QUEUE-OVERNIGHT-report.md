# Overnight queue report, 2026-11-22/23

No other product's name or behaviour in code (all tasks; `ProductNameGuardTest` and CI's `product-name-guard` pass).

## Tasks

| # | Task | Result | PR | Merge commit | Tests |
|---|---|---|---|---|---|
| 1 | 0041 Part B | **Done**: merged, tagged `sdv-ingestion-v1.1.0` (annotated, pushed) | #21 | `6fd09b5` | 607 backend, 20 frontend, clean install, schema upgrade, tour 25/0, CI green |
| 2 | 0048 quarantine, server side | **Done** in the morning: the owner ran the local schema step, then the tour passed (25/0) and the PR was merged | #22 | see git log | 634 backend (0 failures), 20 frontend, typecheck, schema upgrade from 036 and from 035 (9 PostgreSQL tests), clean install, tour 25/0; CI: all checks green, `ci-passed` green |
| 3 | 0049 quarantine, screens | **Not started**: needs 0048 merged | none | none | none |
| 4 | 0050 sealed audit report | **Not started**: needs 0049 merged | none | none | none |

**Why 0048 stopped overnight:** since 0041 the local start does not update the schema. Applying changeset 036 to the local `sdv` database (`.\Update-SDV-Schema.ps1 update`) was refused by the permission check, so Vero could not start and `ui:capture` could not run. That step is the owner's to do, like the seed reset. On 2026-11-23 the owner ran it; the tour then passed with 25 passed, 0 failed, 11 skipped.

## Decisions taken overnight (0048)

1. Whole-incident release requests have their own table and endpoints (`/api/v1/admin/quarantine-releases`, the Approvals permission); `access_requests` is untouched; no bulk endpoint.
2. A reviewer may read and correct only. Tags, comments, level changes, print and download are refused while quarantined, reviewers included.
3. Quarantine counts as "held" for retention (the sweep's kept-on-hold number includes it).
4. The filter matches field values exactly, with the field's current normalization. Field filters need a sending system. Both received dates are required (whole UTC days). At most 1000 documents per filter quarantine.
5. Asking to release a whole incident needs every level in it to be openable by the requester. The Business Admin sees name, count, counts by level, dates and requester, never a reason.
6. On approval, the request's reason is stored as each document's release reason.
7. A waiting request ends as EXPIRED when its incident empties by single releases.
8. The exposure report hides levels the person can't open (counted); only the CSV export is audited.
9. The exposure CSV's reference is a random UUID and its fingerprint the SHA-256 of the file, in headers and the audit event (not registered; 0050 adds a register).
10. `Clearance` equality stays on levels only.

Tests changed to match approved behaviour: `FinalRoleMatrixTest` (new capabilities) and `RetentionLegalHoldTest` (the new retention SQL text).

## What the owner should look at first

1. **PR #22 (0048):** run `.\Update-SDV-Schema.ps1 update` on the local `sdv` database, then `.\start-local.cmd` and `cd frontend/sdv-ui; npm run ui:capture` (counts should be unchanged). If the tour passes, merge (CI is green).
2. Decisions 1, 2 and 4 above: they shape the 0049 screens.
3. Then 0049 and 0050 can run. Consider approving the local schema step in the next queue, so the tour can run unattended.
