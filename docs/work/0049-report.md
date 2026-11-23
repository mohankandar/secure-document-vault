# 0049 report: Quarantine, screens

Branch `feature/quarantine-screens` from `release/vero-r7` at `33938f1` (after 0048 was merged). Run in the morning of 2026-11-23, after the owner's local schema step, under `QUEUE-OVERNIGHT.md`.

## Outcome

**Done**: built, tested and the screen tour passed. Merge through the PR when `ci-passed` is green. Frontend only: **no backend change**.

## What was done

There is no canvas board for these screens yet, so they reuse the Legal holds and Approvals parts: panels, tables, dialogs, drawers, badges, the `hold-*` and `apr-*` styles, and collapsible groups.

1. **Quarantine page**: a new top-bar area for Document and Restricted stewards, after Records (`#/quarantine/incidents`; needs `QUARANTINE_DOCUMENTS`).
   - Open incidents are listed in a table. Released incidents sit in a collapsed group below them.
   - **New incident** is for the Restricted steward only. The form takes the received dates (both required, whole UTC days), the sending system and that system's fields (matched exactly). The server's count updates as the filter changes. The person ticks "Select all N matching", gives a name and a reason, then presses "Quarantine N documents".
   - More than the server's limit (1000) is refused before sending. A changed count (SDV-QUA-009) shows the catalog's words, and the form counts again.
   - **The incident view** shows the name, the reason, how it started, who started it and when, and how many documents are in quarantine. The review list comes **oldest first**, with each document's level. Each row has **Open**, which opens the viewer, where corrective redaction works as before, and **Release**, which asks for a reason.
   - **"Request release of whole incident"** asks for a reason. A waiting request shows as a notice on the incident.
   - Highly Confidential rows reach only a Restricted steward: the server leaves them out for others. A Document steward sees "1 document not shown: it is at a level you can't open".
2. **Quarantine one document**:
   - From the record panel ("Quarantine a document", choosing among the documents the person can open) and from the viewer ("Quarantine").
   - It goes into a new incident (name and reason) or an open one.
   - The button is hidden for roles without `QUARANTINE_DOCUMENTS`. The server still checks the level (SDV-QUA-002).
3. **"Temporarily unavailable"**, for everyone except a reviewer:
   - Search rows show a clock mark next to the number, like the lock: "A document is temporarily unavailable".
   - The record panel lists a "Temporarily unavailable" document: "It comes back once it has been reviewed".
   - The viewer says "This document is temporarily unavailable." (SDV-QUA-001).
   - None of them gives the level or the reason.
   - A reviewer sees the document marked **Quarantined**. In the viewer, a notice says they can read and correct it.
   - Print, download, the print and download requests, Level, adding tags and adding comments are not offered while a document is quarantined. This matches 0048 decision 2.
4. **Approvals**: a "Release incident" section above the other requests.
   - It shows the incident name, document count, levels (e.g. "1 Highly Confidential"), received dates, the requester and the waiting time. It never shows a reason.
   - Each request has Decline, and Review, which opens a dialog with the dates, the expiry and Approve release. They are decided one at a time: no checkbox and no bulk action.
   - The tab's waiting count includes them.
5. **Exposure report**: a drawer opened from the incident.
   - It shows counts by level, and per document who opened, printed or downloaded it before the quarantine, with a note of hidden levels.
   - **Export CSV** saves the server's file. The note gives the server's **reference** and **SHA-256 fingerprint** (new `contentExport` reads `X-SDV-Export-*`).
   - A failure is worded through the 0044 helper (`exportFailureText`).
6. **Errors**:
   - Every refusal goes through the 0044 helpers (`refusalText`, `loadFailed`, `FailureNotice`): the catalog's sentence with its code, never the server's message.
   - The QUA-001 to QUA-011 sentences were already in `problemWords.ts` (0048).
7. **Viewer**: it now shows the file name the server gives when the document opens. Before, it used the name from the list that opened it. The quarantine review list has no file name, so this matters there; for other documents the two names are the same.

## No other product's name or behaviour in code

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken (queue rule: the safer, smaller choice)

1. **Quarantine is its own top-bar area** for the two Steward roles (Home, Records, Quarantine), because Records has no tabs. Business users and admins don't see it, and its address shows the "not part of your role" page to them.
2. **The review viewer opens only the incident's document.** It doesn't move to other records, because the review list doesn't know the record. Its back button reads "Incident".
3. **"Request release of whole incident" is offered only when the person sees every document in it** (no hidden levels), as the server requires (0048 decision 5).
4. **The filter form allows dates alone** ("Any sending system"). Fields appear once a sending system is chosen. "Select all" must be ticked before quarantining, and a new count unticks it.
5. **The record panel's record-level "Level" button is unchanged**: the server decides any change that touches a quarantined document.
6. **Released incidents stay listed, collapsed**, as history.
7. **The tour uses the tour's own record** (399000000001, its one Highly Confidential document), so no demo document's history grows. It releases everything it quarantines, also at its start if an earlier run stopped half-way. Each run still leaves two released incidents and their audit events.

## Tests

- **New `quarantineWords.test.ts` (19 tests)**: level counts; review order (oldest first, then by quarantine time, without changing the list); the filter checks (both dates, order, day format, a field needs a sending system); name and reason lengths; the export note; "temporarily unavailable" and the SDV-QUA-001 words never naming a level or reason.
- **New tour step**, "Quarantine: an incident by filter, review, temporarily unavailable, release approval and exposure (task 0049)", desktop only:
  - Dana makes an incident by filter (count 1, select all).
  - Review list; the viewer with no print, download or level; the real file name.
  - Exposure drawer and CSV (file name, reference and fingerprint).
  - Maya sees "temporarily unavailable" in the row and the panel, with no level and no reason.
  - Daniel sees "1 document not shown" and no whole-incident request.
  - Dana requests release; Priya sees the row (no checkbox, no reason), reviews and approves; the incident is RELEASED.
  - Dana quarantines from the record panel (the viewer's dialog is shown and cancelled), sees the "Quarantined" badge, and releases it from the review list.
- No existing test was changed.

## Screenshots (the morning review)

From the new tour step (desktop): `quarantine-list`, `quarantine-new-incident`, `quarantine-incident`, `quarantine-review-viewer`, `quarantine-exposure`, `quarantine-unavailable-business-user`, `quarantine-steward-hidden-level`, `approvals-release-row`, `approvals-release-incident`, `quarantine-from-viewer`, `quarantine-from-record-panel`, `quarantine-released`.

## Commands run and results

| Command | Result |
|---|---|
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 39 tests (20 before, 19 new) |
| `npx playwright test e2e/sdv-screen-tour.spec.ts --project=desktop-chromium -g "task 0049"` (app running) | PASS (run twice, while building) |
| `cd frontend/sdv-ui; npm run ui:capture` (app running) | PASS: 26 passed, 0 failed, 12 skipped (0048: 25/0/11; the new step runs on desktop and is skipped on iPad) |
| `.\stop-local.cmd` | Stopped (containers untouched) |
| `.\Build-SDV-All.ps1` | PASS: backend 634 tests, 0 failures, 0 errors, 14 skipped (PostgreSQL-only); frontend build OK |

`npm test` in the Git Bash shell failed to load all three test files ("reading 'config'"), because that shell starts in a lowercase `c:` folder. Run from PowerShell, as in 0048, all pass. Nothing in the tests was changed for this.

## Commits

See the PR. Merge commit: see `QUEUE-OVERNIGHT-report.md`.
