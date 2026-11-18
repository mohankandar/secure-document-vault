# Report 0003: search bar, filters, results and record panel

Date: 2026-11-17
Branch: `feature/ui-search` (from `feature/ui-top-bar`)
Outcome: DONE

Precondition note: every 0002 follow-up is done and in the 0002 report. One of them is not committed: the stop-local change in `Stop-SDV-Local.ps1` builds on the owner's own uncommitted change there, so it waits for the owner's decision (0002 report, item 3). It does not touch the search code, so I went ahead.

## What was done

All changes are frontend only. No backend change. The search request is unchanged: same endpoint, same body, same fields, same page size.

**Part A: search bar** (`src/r7/Workspace.tsx`, `src/r7/searchRouting.ts`)
- The left "Find a record" panel is gone. One bar runs across the top. It holds the applied filters as chips ("Last Name contains Pat ×"; removing one searches again), a **Filters** button with the active count, and **Clear**.
- Keyboard: Enter searches; Escape clears the box; the box is the first Tab stop after the top bar.
- A typed value is **added** to the chips already applied (it replaces a chip for the same field). Clear empties everything.

**How the box routes input** (`searchRouting.ts`; the server has no free-text search):
1. If the input looks like a number (digits, with optional spaces, dashes, dots or slashes), it searches the **first identifier field in display order whose normalization is DIGITS_ONLY**, with that field's own match. The server strips the separators, as it does for the Filters field.
2. Otherwise it searches the **first searchable text field whose match is Contains or Starts with**.
3. If neither exists, nothing is sent and a message points to Filters.

The placeholder and the empty state name these two fields from the source's own labels ("Contract Number or Last Name").

**What the box cannot handle** (use Filters for these):
- **A second number field.** Digits always go to the *first* DIGITS_ONLY identifier, so a member number typed in the box is searched as a contract number and finds nothing. The schema carries no length or pattern that would tell the two apart.
- **Text identifiers** (e.g. Service Entity "001", TRIM / Exact). They have no recognisable shape; "001" looks like a number and goes to Contract Number.
- **Exact text fields** (e.g. First Name). Only Contains or Starts with fields are reachable from the box.
- **Full names.** "Mia Patel" is sent as one value to Last Name and finds nothing; there is no splitting into first and last name.
- **Upper and lower case.** The server compares Contains and Starts with case-sensitively on the stored value. "Pat" finds "Patel"; "pat" does not. Fixing this would need a server or normalization change.

**Part B: Filters panel**
- Opens as a panel under the bar (768 px and up) and as a full-screen sheet on phones.
- Source shows only when there is more than one source.
- One field per searchable field, in the server's display order, each with its match label (Exact, Contains, Starts with).
- **Last updated** with visible **From** and **To** labels.
- Footer: "Up to 25 records per page. You only see records your role allows." The number is the new `SEARCH_PAGE_SIZE` in `liveApi.ts`; the search request uses the same constant, so the two cannot drift apart.
- **Show results** applies and closes; **Clear** empties the fields; Escape closes it.

**Part C: results**
- A full-width table: each identifier once, then the `metadataPreview` fields in the order the server sends them, then Documents (`availableDocumentCount`) and Updated (relative time).
- A record with an incomplete submission shows a **"Missing documents"** badge in its row.
- Header: "N records · source name" and "Sorted by last updated". The server orders results by newest submission first, so this is accurate.
- Pager: "Showing 1–N · up to 25 per page", with "more on the next page" when the server reports `hasMore`. The server returns no total count, so the wording cannot be "Showing X of Y".
- Clicking a row highlights it and opens the record panel. Arrow keys, Home and End move between rows; Enter or Space opens.
- Empty states: before a search, "Search by Contract Number or Last Name"; with no results, "No matching records" plus what to try next and an "Open Filters" button.
- On phones each row becomes a stacked card, using the existing table pattern.

**Part D: record panel**
- On the right from 1024 px; a drawer below that. Title: the first preview field's value. With the demo data that is "First Name", so the title is the first name only.
- The identifier box sits below the title. With more than two identifiers they go two per row, and an odd last one spans the row.
- **Incomplete submissions:** "1 of 2 documents received. Still expected: Insurance certificate." The counts are added up across all incomplete submissions, and the names come from `missingSourceDocumentIds`. `liveApi.toRecord` previously kept only the first submission and no names.
- Documents show the page count when known (not sent today) and the received date. Details and Recent activity are as before. **Open documents** is shown only with `VIEW_PDF`.
- Close with × or Escape. The row stays selected (highlighted) in the list. A record chosen from "Go to" also opens the panel.

**Fixes found on the way**
- On phones the identifier box collapsed to 2 px inside the record drawer: a flex item with `overflow: hidden` can shrink to nothing. Fixed with `flex: none`.
- Stacked phone rows showed their labels in the monospace font. Fixed.

## Demo data added for this task (development database, demo source only)

- `Seed-SDV-Demo-Records.ps1 -SourceDisplayName 'LexaVault' -FreshTestData -Count 3`: contracts **300000000076–78** with First Name and Last Name ("Ava/Noah/Mia Patel"). The default seed has no name field, so "search by borrower name" had nothing to search.
- **Last Name** set to **Contains** (TRIM) through the Business admin metadata API, so the box has a text field. The seed script defines it as Exact; running `-FreshTestData` again would reset it.
- One incomplete record, **300000000090** (Omar Patelli), sent through the ingestion API: 2 documents expected ("Appraisal report", "Insurance certificate"), 1 sent. This lets the "Missing documents" row and the counts in the record panel be checked.
- The opt-in comment spec added one more tagged test comment to 100000000001.

## e2e (`e2e/sdv-screen-tour.spec.ts`, `e2e/sdv-comment-persistence.spec.ts`)

- Business user and Document steward:
  1. search by contract number (chip "Contract Number is 100000000001");
  2. search by name (`SDV_DEMO_NAME`, default "Pat"; chip "… contains Pat");
  3. add a filter chip from Filters ("First Name is …", count 2, 1 row), then remove it with × (back to the name results);
  4. open the record panel (row selected) and the viewer.
- Business admin, Tenant IT, Platform operations and the local admin: no Records tab, and opening `#/workspace/search` shows "This area isn't assigned to your role" (captured as `records-not-assigned`).
- The record journey is skipped, with a note in the test output, only when the source has no Contract Number field, i.e. an empty database. The owner has not yet answered 0002 question 2 on this.
- The comment spec uses the search bar.

## Commands run and results

| Command | Result |
|---|---|
| `npm run typecheck`, `npm run build` | PASS |
| `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 45 backend tests, 0 failures; frontend `npm ci` and build OK. The first run failed because the backend was still running and locked its jar (my stop command ran from the wrong folder). After `.\stop-local.cmd` (containers stayed up) the second run passed. |
| `npm run ui:capture` | **PASS 12/12** (desktop + iPad), three runs, the last after the full build |
| `SDV_COMMENT_REGRESSION=1` comment spec (desktop) | PASS |
| Routing check (Node, `searchRouting.ts` against the live schema) | As described above, including the limits |
| Check script (Playwright) | **22/22 PASS**: Tab order; placeholder and empty state from labels; Escape clears; "3000-0000-0076" goes to Contract Number (1 record); "Pat" goes to Last Name contains; "4 records · LexaVault"; Missing documents badge; page size in the pager and the Filters hint; Source hidden with one source; From and To labels; Escape closes Filters; ArrowDown and Enter; the opened row is highlighted; panel title, counts and missing names; Open documents; Escape closes the panel and the selection stays; removing the last chip returns to the empty state; no-results message |
| `.\stop-local.cmd`, `.\start-local.cmd` | Both work (containers stayed up) |

Screenshots: `.runtime/ui-checks/0003/` (ignored by git):
- `<business|steward>-<1440|390>-<light|dark>-<1-empty|2-results|3-filters|4-record>.png` (32 files);
- `business-1440-light-5-no-results.png`.

The tour's captures are in `frontend/sdv-ui/test-results/`.

## Files changed

- `src/r7/Workspace.tsx` (rewritten), `src/r7/searchRouting.ts` (new), `src/r7/liveApi.ts` (`SEARCH_PAGE_SIZE`, raw normalization, all incomplete submissions and missing names), `src/r7/data.ts` (two optional fields), `src/r7/r7.css` (workspace section)
- `e2e/sdv-screen-tour.spec.ts`, `e2e/sdv-comment-persistence.spec.ts`
- `docs/work/0003-task.md`, `docs/work/0003-report.md`, `docs/design/vero-ui-after.html` (the design chat's update to the approved screens)

## Commits

One commit on `feature/ui-search`: "feat(ui): search bar, filters panel, results table and record panel (task 0003)". Not pushed or merged.

## Questions for the owner

1. **Second number field.** Should the box also try the second DIGITS_ONLY identifier (e.g. Member Number) when the first finds nothing? That means a second search request in that case. The alternative is for metadata configuration to name which field the box searches.
2. **Case-insensitive name search** needs a server change or lowercase normalization at ingestion. Should it become a backend task?
3. **Panel title** uses the first preview field. With separate First Name and Last Name fields it shows only the first name. Is that acceptable, or should the title join the first two text preview fields?
4. **Stop script** (from 0002): may I commit `Stop-SDV-Local.ps1` including your earlier uncommitted change?
5. **e2e without data** (from 0002): should the record journey stay optional, or fail when no demo data exists?
