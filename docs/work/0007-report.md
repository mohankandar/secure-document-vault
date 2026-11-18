# Report 0007: Page-view audit that says what happened

Date: 2026-11-18
Branch: `feature/page-view-audit` (from `feature/ui-viewer`)
Outcome: DONE, with **one question** (Audit wording, see the end). The queue stops here.

## What was done

**Backend** (no schema change)
- `DocumentViewerService.metadata` returns the `DOCUMENT_VIEW_OPENED` event's ID as **`viewingId`** (`ViewerMetadata.viewingId`).
- `GET …/viewer/pages/{n}?scale=…&viewingId=…`: the controller treats a missing or malformed `viewingId` as none. `DocumentViewerService.renderPage(…, viewingId, …)` then decides:
  - **Accepted viewing:** `PageViewAuditPort.isViewing` must find a primary-key match: `DOCUMENT_VIEW_OPENED`, same person (`actor_type='USER'`, `actor_id`), same document (`target_id`), same tenant, `SUCCESS`, at most **8 hours** old (`VIEWING_LIFETIME`). Then `appendPageEventOnce` writes the page event **only if this viewing has none for this page yet**, whatever the zoom.
  - **Otherwise** (missing, malformed, another person's, another document's, too old) the page is recorded as before. Leaving the ID out can never avoid the record.
- `JdbcPageViewAuditRepository` (new, `sdv-persistence/.../business`): `appendPageEventOnce` runs in one transaction.
  - It first takes `pg_advisory_xact_lock(hashtextextended('sdv-page-view:<viewing>:<page>', 0))`, so a page fetched ahead and the same page shown a moment later cannot both write.
  - It then runs `insert … select … where not exists (an earlier DOCUMENT_PAGE_VIEWED by this actor, document, viewingId and page, since the viewing began)`.
- The page event's safe details now carry `viewingId` and `page` (plus `scale`). The action code stays `DOCUMENT_PAGE_VIEWED`, so earlier records still match.
- Previews (task 0006) stay unaudited.

**Frontend**
- The viewer keeps `viewingId` from opening and sends it with every page request and with Print's page requests (`Viewer.tsx`, `liveApi.viewerPage`). After a redaction the viewer opens again, so a new viewing starts and the new content is recorded.
- **Wording**, through a new shared `auditEventLabel` in `src/r7/ui.tsx`:
  - document Activity panel and the record panel's Recent activity: "**Loaded page 3 of loan-file.pdf**";
  - Audit: "**Loaded page 3**";
  - Audit action filter: "Page loaded".

## Check 2: product build, the 14-page demo document

Open, scroll through 5 pages with Next page, then Zoom in 3 times. The production build's local sign-in is the business user, because choosing the steward test role exists only in development builds; the audit logic doesn't depend on the role. So I ran it twice: production as the business user, development as the steward.

| Build | Events written |
|---|---|
| **Product** (business user) | `DOCUMENT_VIEW_OPENED` ×1, then `DOCUMENT_PAGE_VIEWED` pages **1, 2, 3, 4, 5, 6**, each once, same viewing. Page 6 was fetched ahead. **None from the 3 zoom steps**, although they re-requested every loaded page at 1.25, 1.50 and 1.75. |
| Development (steward) | `DOCUMENT_VIEW_OPENED` ×2 (React development mode), then pages 1–6 once each, on one viewing |

Before this task, the same three zoom steps wrote 6 page events each on this document.

**Live checks of the other cases** (backend at 127.0.0.1:8280, real database):

| Case | Page events |
|---|---|
| Same viewing, page 9 at scale 1.0 and 1.5 | **1** (with `viewingId`) |
| No `viewingId`, page 10 twice | 2 (recorded normally) |
| Malformed `viewingId`, page 11 | 1 (recorded normally, HTTP 200) |
| Steward sending the business user's `viewingId`, page 12 twice | 2 (recorded normally, no `viewingId` in details) |
| The viewing as checked 8 h 1 s after it opened (the repository's condition, run in psql) | not accepted; accepted at the time it opened |

## Tests (13 new)

- `DocumentViewerServicePageViewTest` (7, with an in-memory implementation of the `PageViewAuditPort` contract):
  - opening returns the open event's ID;
  - same viewing and same page twice gives 1 event;
  - three zooms of the same page give 1 event, and another page gives a second;
  - another person's viewing is recorded normally;
  - another document's viewing is recorded normally;
  - no `viewingId` is recorded normally;
  - a viewing older than 8 hours is recorded normally.
- `JdbcPageViewAuditRepositorySqlTest` (3): every condition of the viewing check; the lock key and the `not exists` conditions (actor, document, viewing, page, time); "already recorded" returns not-written.
- The real SQL is shown working by the live checks above (no test database exists yet; that comes with the installation tasks).

## Query plans (development database)

- **Is this a viewing:** Index Scan on `audit_events_pkey` (by event ID), then the other conditions on that single row.
- **Earlier page event:** Index Scan on **`idx_audit_events_target (tenant_id, target_type, target_id, occurred_at)`**, filtering actor, action, viewing and page. The planner chose this existing index over migration 018's `(tenant_id, actor_id, action, occurred_at)`, because one document's recent events are the narrower set here. Both are bounded index scans and PostgreSQL picks per data, so no change was needed.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, **83 tests**, 0 failures |
| `npm run typecheck`, `npm run build` | PASS |
| `npm run ui:capture` | **PASS**: 14 passed, 2 skipped (the opt-in redaction test) |
| Wording on screen | Activity: "Loaded page 1 of loan-file.pdf"; Audit: "Loaded page 1" (`.runtime/ui-checks/0007/`) |

## Files changed

- Backend: `PageViewAuditPort.java` (new), `JdbcPageViewAuditRepository.java` (new), `DocumentViewerService.java`, `SecurePdfViewerController.java`; tests `DocumentViewerServicePageViewTest.java`, `JdbcPageViewAuditRepositorySqlTest.java` (new), `DocumentViewerServicePreviewsTest.java` (constructor)
- Frontend: `src/r7/ui.tsx`, `Viewer.tsx`, `Workspace.tsx`, `Operations.tsx`, `liveApi.ts`
- Docs: `docs/work/0007-task.md`, `docs/work/0007-report.md`

## Commits

One commit on `feature/page-view-audit`: "feat(audit): one page event per page per viewing; 'loaded page' wording (task 0007)". Not pushed or merged.

## Question for the owner

**Audit wording without the file name.** The task asks for "loaded page 3 of loan-agreement.pdf" in Audit too. Audit events do not carry the file name, and the Audit API does not look it up, so Audit shows "Loaded page 3", with the document ID in the Target column. Adding the name needs a backend change this task does not name, and it would show file names (which can contain personal data) to Platform operations. Choose one:
- (a) keep "Loaded page 3" plus the document ID in Audit;
- (b) have the Audit API look up the document's file name when it lists events;
- (c) store the file name in the page event's safe details.
