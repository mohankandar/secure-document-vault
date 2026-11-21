# Report 0030: Approvals and Legal holds for the Business admin (S9a, part 2)

Date: 2026-11-20
Branch: `feature/sensitivity-7` (from `feature/sensitivity-6`)
Outcome: PARTLY DONE. Everything is built and checked. Two pieces need your answer before they are complete (questions 1 and 2):
- the audit's Sensitivity filter can't find document openings yet;
- "Decided recently" lists only this browser tab's decisions.

No schema change. The two server changes are the ones this task approves (0.3 and item 6). These pass:
- the build (348 tests);
- the schema check, with both PostgreSQL tests;
- `ui:compare` (26, including the two new boards);
- the screen tour (22 passed, 8 skipped);
- the opt-in bulk approval of 50.

## What was done

**0. Carry-over from report 0029**
1. Leftover row for 300000000007: left as it is.
2. Viewer title line: no code change (the 0018 format was already in use). Your updated `Sens-Viewer.dc.html` is committed with this task.
3. **Whole-contract requests in the viewer.**
   - Server: each request in `GET /business/access-requests` now names its record: `sourceId`, `primaryIdentifier` and `primaryIdentifierLabel`. A whole-contract request has them too. They are null when the record can't be found.
   - Viewer: the Requests tab (and "Your requests" in Details) lists whole-contract requests with the documents of their record. Each is marked "Whole contract".

**Screens** (boards `Sens-Approvals`, `Sens-LegalHold`; new files `Approvals.tsx`, `LegalHolds.tsx`, `Notices.tsx`)
1. **Tabs.** Business administration gains Legal holds (after Keeping documents) and Approvals (last). Approvals shows an amber count of waiting requests on every tab of the area.
2. **Approvals:**
   - One line per request: scope icon, kind and level, received date and pages (hover for the full date and page count), reason, requester and how long it has waited.
   - Grouped by contract, oldest first. "Approve all" and "Decline all" per group.
   - Groups open and close from the heading (chevron or number). They start collapsed when more than 10 requests wait, open otherwise. A group holding a whole-contract request always starts open.
   - A collapsed heading shows the count, the kinds, the requesters and "oldest N days". Its checkbox ticks the whole group.
   - The dark bar lists exactly what is ticked, e.g. "12 selected · 11 on 300000000003 (5 prints, 5 downloads, 1 lower to Confidential) and 1 on 300000000005 (1 lower to Standard) · all one document".
   - Filter chips by kind, with counts. "Requested by" filter. "Group by contract" switch. Decisions go to the server 100 at a time.
   - Whole-contract requests can't be ticked. They show "Review", which opens a dialog to approve or decline them on their own.
   - Footer: the expiry note and "Decided recently ›".
   - The server's refusals are shown in its words, with the code. Self-approval reads "You can't approve or decline your own request. (SDV-APR-006)".
3. **Nothing extra is shown.** Only the primary identifier, received date, page count, level, requester, reason and time. The "document 2 of 6" position the server sends is not shown either, because the task's list leaves it out.
4. **Legal holds:**
   - Type a contract number and a reason. The screen says how many documents will be held ("3 documents on contract 300000000001 will be held"), never which ones. Documents already on hold are counted apart.
   - "Place hold" holds each document. The server holds one document per call (task 0027), so a contract is held document by document.
   - "On hold now · N" lists the holds. Release asks for confirmation first, because the daily cleanup may delete the document afterwards.
5. **Keeping documents** gains "N documents on legal hold". It links to the Legal holds tab.
6. **Audit filter by level.** The Audit screen has a Sensitivity filter: Any, Confidential, Highly Confidential.
   - Server: `sensitivityLevel` on `GET /operations/audit-events` and on the export. It keeps events whose recorded level (`sensitivityLevel` or `level` in the safe details) is that level. It never uses today's level.
   - An unknown value is refused with the new code **SDV-SEN-019**, listed in `docs/api/error-codes.md`.
   - An export records the filter it used.
   - See question 1: opening a document isn't covered yet.
7. **Notices for requesters (Stewards).**
   - When a request is approved, declined or expires, the requester sees a short notice the next time they open Vero, e.g. "Print on 300000000008 declined". Each decision is told once.
   - The viewer's Requests tab shows "N new" until the tab is opened.
   - Seen decisions are remembered per person in this browser. The first time Vero opens in a browser, nothing old is announced.
8. **Any Business admin can decide.** Nothing in the screens or server assumes a single admin:
   - every admin sees the same list;
   - a request another admin already decided is refused (SDV-APR-008) and shown as such;
   - each decision is audited with its own approver.

   A new test shows two admins working one list.

**Demo seed** (`Seed-SDV-Canvas-Demo.ps1`)
- Two Highly Confidential documents more on each of 300000000007 and 300000000008 (second deliveries).
- Five demo Restricted stewards who never sign in: Sam Kim, Ali Haddad, Leo Grant, Mia Novak, Tom Reed. Each asks to print and download each of the five Highly Confidential documents: **50 waiting requests**. Their names are written in the local database only, like the other demo names.
- Dana's whole-contract request on 300000000002.
- Three legal holds.
- Result: 53 waiting in all. A second run changed nothing: requests, holds, documents, submissions and non-read audit events were all unchanged.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (affected tests) | passed |
| `.\Build-SDV-All.ps1` | first try: my own `2>&1` turned a Mockito warning into an error (not a test failure); rerun with the output to a file: **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 268 = **348 tests** (341 before), 0 failures, 2 skipped (the PostgreSQL tests). Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` | **PASS**, 27 s (step 4f runs both PostgreSQL tests, with the new checks) |
| `.\Test-SDV-CleanInstall.ps1` | not run: no schema change |
| `.\start-local.cmd` | started |
| `.\Seed-SDV-Canvas-Demo.ps1` | first run stopped at my own bug (search page 0; pages start at 1); fixed. Then passed three times; the last run changed nothing |
| `npm run typecheck`, `npm run build` | passed |
| `npm run ui:compare` | **26 passed**: `.runtime/ui-compare/sens-approvals.png`, `sens-legalhold.png` (plus dark and 390 px) |
| `npm run ui:capture` | **22 passed, 8 skipped** (tablet copies of desktop-only steps and the opt-in tests) |
| `SDV_BULK_APPROVAL_REGRESSION=1`, test "Bulk approval of the 50…" | **passed**: 50 selected, the server answered 50 decided, 0 refused. Then I re-ran the seed: 50 waiting again |

## Tests: counts, and any failures with their output

New:
- `ApprovalsScreenSupportTest`:
  - 50 waiting requests approved in one bulk decision, each audited, each with its own use-by time;
  - two Business admins on one list: the second gets SDV-APR-008 for the 30 already decided; each decision names its own approver;
  - the requester's list names the record of document and whole-contract requests, with exactly the listed fields (no file name).
- `AuditLevelFilterTest`:
  - the level reaches the query;
  - an empty level means any;
  - bad values (`confidential`, `" CONFIDENTIAL"`, `SECRET`, a list, a blank) get SDV-SEN-019 (400), and nothing is read;
  - an export records the filter.
- `JdbcAuditActionFilterSqlTest`: the level is a bound parameter inside the tenant, and there's no clause without a level.
- `AccessRequestPostgresTest` (real PostgreSQL):
  - the requester's list names the record;
  - the level filter finds requests, prints and refusals of Highly Confidential documents, and only those;
  - events without a level are left out;
  - tenants are kept apart.
- Tour, new step "Approvals, legal holds, requester notices and the audit level filter (task 0030)":
  - groups collapsed (over 10 waiting), the whole-contract group open, its request not selectable, Review;
  - chip counts, a collapsed group ticked and the bar's wording, no file names or IDs;
  - the self-approval message;
  - legal hold count for 300000000001, Release dialog (cancelled), the Keeping documents line;
  - the notice after a decline, told once;
  - the "1 new decision" count clearing when the tab opens;
  - the whole-contract request in 300000000002's Requests tab;
  - the audit filter, and SDV-SEN-019 for `highly`.
- Tour, opt-in: the 50-request bulk approval through the screen.

Not real here (said per CLAUDE.md): the tour's **self-approval** check stubs the server's 403 SDV-APR-006 answer. No local sign-in both asks and approves. The rule itself is tested on the server (`AccessRequestApprovalsTest`, `AccessRequestPostgresTest`).

Changed tests:
- the tour's tab helper accepts a count after a tab name ("Approvals 53 waiting");
- the Business admin tab list gained Legal holds and Approvals.

Failures on the way, all in my new tour step and all fixed:
- a locator pointed at "the first collapsed group", which moved once that group was opened;
- Records searched the wrong sending system;
- a regular expression missed the space PostgreSQL writes after `:` in JSON.

## Files changed

- **Backend:** `AccessRequestService`, `AuditQueryService`, `AuditQueryPort`, `JdbcOperationalRepository`, `OperationsController`, `SdvErrorCode` (SEN-019).
- **Backend tests:** new `ApprovalsScreenSupportTest`, `AuditLevelFilterTest`; extended `JdbcAuditActionFilterSqlTest`, `AccessRequestPostgresTest`.
- **Frontend:**
  - new `Approvals.tsx`, `LegalHolds.tsx`, `Notices.tsx`;
  - `Governance.tsx`, `KeepingDocuments.tsx`, `AuditLog.tsx`, `Viewer.tsx`, `Sensitivity.tsx`, `Shell.tsx`, `liveApi.ts`, `model.ts`, `store.tsx`, `ui.tsx` (tab count, title side line), `r7.css`;
  - `e2e/ui-compare.spec.ts`, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and docs:** `Seed-SDV-Canvas-Demo.ps1`, `docs/api/error-codes.md`, `docs/design/sensitivity/Sens-Viewer.dc.html` (your update), `docs/work/0030-task.md`, this report.
- **Not committed (left as found):** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, tasks 0031 to 0034.

## Remaining differences from the boards

| Board | Difference | Why |
|---|---|---|
| Both | Account without the department; the Go to box | App shell (0018) |
| Approvals | 53 waiting over 5 contracts, mostly 300000000003, 007 and 008; ages in hours, not days | Data (the seed) |
| Approvals | First group 300000000003 with 11 ticked (the board: 3 on 300000000001) | Data; compare ticks the first group and the next collapsed one |
| Legal holds | 3 holds placed today on 300000000005 and 300000000007 | Data |
| Audit (admin board) | A fourth filter, "Any sensitivity", in the filter line | This task (item 6) |

## Commits (hash and message), or "not committed"

`cb17351` feat: approvals and legal holds for the Business admin; audit filter by level; requester notices (task 0030), on `feature/sensitivity-7`. Not pushed, not merged. (This line was added in a second commit.)

## Not done, and why

- **Opening a document isn't covered by the level filter.** The task says the events from tasks 0024 to 0028 already record the level. That's true for downloads, prints, refused openings and requests. `DOCUMENT_VIEW_OPENED` and `DOCUMENT_PAGE_VIEWED` don't record it (only page counts).

  So the filter can't yet answer "who opened Highly Confidential documents". Adding the level to those events changes what they record, which the task doesn't name, so I didn't. See question 1. The screen's hint and SDV-SEN-019's support note say which events are covered.
- **"Decided recently"** lists only the decisions made on this screen in this browser tab. The server has no list of recently decided requests, and a Business admin can't read the audit. A full list (all admins) would need a small read endpoint. See question 2.
- **The expiry note** says 7 days. That is the default; a tenant may set another window, which the screen can't read.
- **Notices** are remembered per browser. On a new computer, earlier decisions aren't announced (the Requests tab still shows every state).

## Questions for the owner

1. **Audit filter and openings.** May I add the effective level to `DOCUMENT_VIEW_OPENED` (and `DOCUMENT_PAGE_VIEWED`) from now on? That is a small server change; earlier openings would stay unfiltered. Or should the filter fall back to the document's level today for events that recorded none? That is less exact after a level change.
2. **Decided recently.** Add a read endpoint listing the last decisions of all Business admins (who, what, when; no reasons), or keep it as this tab's decisions?

## Suggested next step

Answer questions 1 and 2, then task 0031.
