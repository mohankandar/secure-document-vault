# Report 0029: Sensitivity screens for stewards and business users (S9a, part 1)

Date: 2026-11-20
Branch: `feature/sensitivity-6` (from `feature/sensitivity-5`)
Outcome: PARTLY DONE. Everything is built and checked except carry-over item 2: the one delete in the dev database was blocked by this session's permission check, so the row is still there (see "Not done").

There is one schema change, approved by section 0 item 4: changeset 028 (`sensitivity_level_changes`). These pass:
- the build (341 tests);
- both schema checks, with both PostgreSQL tests;
- the clean install;
- `ui:compare` (24, including the three new boards);
- the screen tour (21 passed, 5 skipped).

## What was done

**0. Carry-over from report 0028**
1. Per-tenant windows: no change (agreed).
2. Leftover record-level row for 300000000007: **not removed**, see "Not done".
3. "Higher than now" uses the effective level: no change (agreed).
4. **Reasons are out of the audit for level changes too.**
   - New table `sensitivity_level_changes` (changeset 028). Each row holds:
     - scope (document or record) and the target;
     - direction: raised, or lowered after approval;
     - from, to and the reason;
     - who changed it, with their name at the time, and when;
     - for a lowering, the request it came from.
   - The table's checks refuse bad rows. As in 027, every check is written so it can't come out null.
   - Raises write a row in the same transaction as the change, and so do approved lowerings. For a lowering, "who" is the approver: their approval makes the change apply.
   - The four level-change audit events (`DOCUMENT_/RECORD_SENSITIVITY_RAISED/_LOWERED`) now carry who, when, scope, from and to, plus `levelChangeId`, which points to the history row. They no longer carry a reason.
   - **Read endpoints:**
     - `GET /api/v1/business/documents/{id}/sensitivity/history`;
     - `GET /api/v1/business/records/{recordRef}/sensitivity/history`.
   - **Who may read them:** Document stewards, Restricted stewards and Business admins.
     - A steward must be able to open the document; if not, it is refused with SEN-008 and audited.
     - A Business admin reads it without opening the document.
     - Anyone else gets the new code **SDV-SEN-018**, and the refusal is audited. The code is in `docs/api/error-codes.md`.
5. **The approvals list shows both levels.** Each waiting request now also carries:
   - `ownLevel`;
   - `recordLevel`;
   - `levelAfter`: the effective level once a document lowering is approved;
   - `accessUnchanged`.

   `liveApi.approvalLevelNote()` builds the sentence "The contract stays at <level>, so who can open it won't change" for the Approvals screen (task 0030). The Level dialog already shows the same sentence to the steward when they pick such a lowering.

**Screens** (boards `Sens-Results`, `Sens-Viewer`, `Sens-Level`; new shared file `src/r7/Sensitivity.tsx`)
1. **Results rows:**
   - a lock next to the record number: dark for Highly Confidential, light blue for Confidential;
   - an accessible label, e.g. "Holds Highly Confidential documents you can't open";
   - colours now come from design tokens, with dark-mode values.
2. **Record panel:**
   - Restricted documents are locked items with the level badge.
   - A new "Contract level" box. The word comes from the record's own identifier ("Contract number" gives "Contract").
   - It has a **Level** button for Document and Restricted stewards only. It is hidden, not greyed, for everyone else.
3. **Viewer:**
   - The level badge sits next to the title, with a lock for Highly Confidential.
   - For a Highly Confidential document, a Restricted steward sees "Request download" and "Request print" instead of Download and Print.
   - The request popover follows the board: "Why do you need it?", the 24-hour sentence, Send request.
   - Once a request is approved, the button reads "Download (approved, use by tomorrow 18:54)" and works once. The list refreshes after use.
   - While a request waits, the button reads "Download requested" (disabled).
   - **Details** gains Sensitivity and "Set by":
     - the contract's level, when the contract raises it;
     - otherwise the last person in the history;
     - otherwise "Sending system".
   - Details also gains "Your requests".
   - A new **Requests** tab (stewards only) lists the person's requests for this document and their state: waiting, approved with its use-by time, used, declined, expired.
   - **Level ▾** opens the dialog.
4. **Level dialog:**
   - "Apply to": This document (preselected from the viewer) or "Whole contract · N documents" (preselected from the panel, where This document is disabled).
   - Each level is marked "Now" or "Needs approval".
   - A raise reads "Changes straight away"; a lowering becomes a request.
   - A warning appears when the person will lose access after raising.
   - The button follows the choice, e.g. "Raise contract to Highly Confidential" or "Ask to lower document to Standard".
   - Choices that can't work are blocked, with the reason:
     - a steward lowering from Highly Confidential;
     - a document held up by its contract.
   - Server refusals show their message and code.
5. **Locked document:**
   - Clicking a locked item shows only "You can't open this document".
   - If a document is refused when opening (SDV-SEN-008), the viewer says the same and nothing else.

**Other**
- **Error codes:** refusals now pass their `code` through to the screens (`ApiRequestError.code`).
- **Restricted steward local sign-in:** "Restricted Steward" in the local role switch, with subject `local-restricted-steward`, shown as **Dana Ruiz**. The name is in the local-profile-only `LocalHeaderAuthenticationFilter`, like the other demo names.
- **Demo seed** (`Seed-SDV-Canvas-Demo.ps1`): second deliveries on four records, so the existing documents stay as they are:
  - 300000000003 Marcus Lee: settlement agreement, Highly Confidential;
  - 300000000005 Wei Chen: income statement, Confidential;
  - 300000000009 Noah Kim: credit report, Confidential;
  - 300000000002 Priya Shah: the whole contract Highly Confidential.

  It also makes requests in every state: approved, waiting (two), declined, used, expired. Most are Dana's; one waiting request is Daniel's. Priya Natarajan decides them. The expired one is dated back eight days in the local database only.
- **Ran it twice:** the second run changed nothing. Submissions, documents, requests (same fingerprint), level history, record levels and non-read audit events were all unchanged.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test` (the affected tests, during work) | first run: 1 failure, from my own in-memory test helper (it put changes made at the same moment in the wrong order); fixed the helper, then passed |
| `.\stop-local.cmd` | stopped the app; database and Vault left running |
| `.\Build-SDV-All.ps1` | first try: my own `*>` redirection turned a Mockito warning into an error (not a test failure); rerun without it: **BUILD SUCCESS**. Backend 24 + 50 + 2 + 4 + 261 = **341 tests** (338 before), 0 failures, 2 skipped (the PostgreSQL tests). Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 028`) | first run **FAIL**: `AccessRequestPostgresTest` (see "Tests"); after the fix **PASS**, 25 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 027` | **PASS**, 26 s |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 45 s, 28 changesets |
| `.\start-local.cmd` | started; 028 applied to the dev `sdv` database |
| `.\Seed-SDV-Canvas-Demo.ps1`, twice | both passed; the second changed nothing |
| `npm run typecheck`, `npm run build` | passed |
| `npm run ui:compare` | **24 passed**: images in `.runtime/ui-compare/sens-results.png`, `sens-viewer.png`, `sens-level.png` (plus dark and 390 px) |
| `npm run ui:capture` | **21 passed, 5 skipped**: the usual 4, plus the new desktop-only step on iPad |

## Tests: counts, and any failures with their output

New or extended:
- `SensitivityLevelChangeTest`:
  - raises and lowerings write history rows and keep the reason out of the audit;
  - the event names its row;
  - histories come newest first;
  - new test: a steward can't read the history of a Highly Confidential document (SEN-008, audited); unknown and other-tenant documents get SEN-016.
- `AccessRequestApprovalsTest`:
  - the four level fields;
  - new test: a lowering under a higher contract level gives `accessUnchanged: true`.
- `ApprovalsControllerTest`, new test: only stewards and Business admins read the history (SEN-018 and audited for the others). Only the Business admin reads without opening.
- `AccessRequestPostgresTest`:
  - no level-change event carries a reason;
  - the event joins to its row;
  - the record history comes newest first;
  - tenants are kept apart;
  - 10 bad rows are refused by name.
- `LocalHeaderTestNamesTest`: Dana Ruiz.
- `Test-SDV-SchemaUpgrade.ps1`:
  - checks 028;
  - proves an access request made before 028 is unchanged;
  - runs the PostgreSQL tests for 027 or 028.
- Tour, new step: a Business user, a Document steward and a Restricted steward on record 300000000003. It covers the lock, the locked item and its message, the buttons hidden per role, both entries into the Level dialog, the warning, request buttons instead of Download and Print, the approved print, the Requests tab and the request popover. It cancels everything, so it changes no data.

**Changed tests** (said here per CLAUDE.md):
- In `SensitivityLevelChangeTest` and `AccessRequestPostgresTest`, the assertions "the audit event carries the reason" now assert the opposite. That follows the owner's decision 0.4.
- `AccessRequestApprovalsTest`'s exact field list gained the four level fields.

**Failure on the way: `AccessRequestPostgresTest`, first schema-check run.**
- Output: `the record's history, newest first ==> expected: <[Closed, Litigation]> but was: <[Litigation, Closed]>`.
- Cause: the test's clock gave the raise and the lowering the same second, so their order was undefined.
- Fix: the test now moves its clock 5 minutes before the approval. In real use the two times differ.

## Files changed

- **Schema:** `028-sensitivity-level-changes.sql` and `.yaml`, `db.changelog-master.yaml`.
- **Backend:**
  - `AccessRequestRepository` (port) and `JdbcAccessRequestRepository`;
  - `SensitivityLevelService`, `AccessRequestService`;
  - `DocumentAccessGuard` (attempt `LEVEL_HISTORY`), `AuditSentences`, `SdvErrorCode` (SEN-018);
  - `AuthorizationService.has`, `SensitivityLevelController`;
  - `LocalHeaderAuthenticationFilter` (Dana Ruiz).
- **Frontend:**
  - new `Sensitivity.tsx`;
  - `Workspace.tsx`, `Viewer.tsx`, `liveApi.ts`, `api.ts`, `model.ts`, `store.tsx`, `Shell.tsx`, `r7.css`;
  - `e2e/ui-compare.spec.ts`, `e2e/sdv-screen-tour.spec.ts`.
- **Tests:** as listed above, plus `ApprovalsFixture`.
- **Scripts and docs:** `Seed-SDV-Canvas-Demo.ps1`, `Test-SDV-SchemaUpgrade.ps1`, `docs/api/error-codes.md`, `docs/work/0029-task.md`, this report.
- **Not committed (left as found):**
  - `Start-SDV-Local.ps1`;
  - your edit to `docs/work/README.md`;
  - tasks 0030 to 0034;
  - `src/r7/ui.tsx`, which differs only in line endings from my editing.

## Remaining differences from the boards

"Data" means the screen matches and only the local data differs from the board's example.

| Board | Difference | Why |
|---|---|---|
| All | Account shows "Acme Lending" without the department; the top bar has the Go to box | Owner decision in 0018 / app shell |
| Results | 9 records over the last 30 days, not 5 for "3000000000"; 005 also has a Confidential lock; Marcus Lee has the "2 of 4 received" notice | Data (the seed's records) |
| Results | The board shows Level to a Business user (tagged STEWARDS); the app hides it | The task: hidden for other roles |
| Viewer | Title line keeps "number · file name · 1 of 3 documents" (board: "number · 9 pages") | The approved viewer board of 0018 shows the file name; see question 2 |
| Viewer | Left document list and page bar; Ask, Comments, Tags and Activity tabs; Details label above value | Kept behaviour (0018 viewer) |
| Viewer | "Print (approved, use by …)" instead of "Request print" | Data: the seed gives Dana an approved print, as the board's own Requests list does |
| Level | "Now" sits on Standard (contract "None set"), Confidential is offered as a raise | Data: the board's example contract is Confidential |
| Level | The dim covers the page area only, not the document list or panel | The board has no side columns to compare with |

## Commits (hash and message), or "not committed"

`507876d` feat: sensitivity screens for stewards and business users; level-change history (task 0029), on `feature/sensitivity-6`. Not pushed, not merged. (This line was added in a second commit.)

## Not done, and why

- **Item 2: the delete was not run.** The session's permission check refused the statement as a destructive action. The row is still in the dev `sdv` database. The statement to run (dev database only):

  `docker exec sdv-postgres psql -U sdv -d sdv -c "delete from record_sensitivity_levels where record_level_id='07ce6f60-b663-4b0c-9e05-fa9f0fcb31c3' and sensitivity_level='STANDARD';"`

  The row is record 300000000007, Standard, set by `live-check-admin`. The Loan Origination System now has a real record level (300000000002, from the seed) anyway. So SDV-SEN-007 would refuse identifier changes for that system even without this row.
- **Whole-contract requests in the viewer's lists:** the requester's list (`GET /business/access-requests`) doesn't say which record a whole-record request is for. The viewer therefore lists only this document's requests. Showing them would need one more field from the server.
- **Old audit events:** the events written by the 0028 live checks still hold their reasons. I didn't rewrite audit history.

## Questions for the owner

1. **Item 2:** run the statement above yourself, or allow it in this project's settings and I'll run it?
2. **Viewer title line:** the 0018 board shows "number · file name · 1 of 3 documents", while Sens-Viewer shows "number · 9 pages". I kept 0018's. Which should win?
3. **Whole-contract requests** in a steward's Requests tab: add the record reference to the requester's list (small server change), or leave them out?

## Suggested next step

Answer questions 1 to 3, then task 0030.
