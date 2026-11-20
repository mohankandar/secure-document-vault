# Report 0022: Quick search across all fields

Date: 2026-11-19
Branch: `feature/quick-search` (from `chore/remove-prototype-code`, task 0021, not yet merged)
Outcome: DONE

## What was done

**Part A, server** (no schema change, no new dependency):
- `POST /api/v1/business/search` takes `anywhere`. After trimming it must be 2 to 100 characters, otherwise the server answers 400 "Search text must be 2 to 100 characters." It counts as a criterion: it works alone, or ANDed with fields, dates and `incompleteOnly`. (`BusinessExperienceController`, `BusinessExperienceService.search`)
- **Fields tried** (`anywhereCriterion`), in the source's display order:
  - every current searchable field, with its own match (exact, starts with, contains) and normalization;
  - the earlier normalizations of the same field too, as the field search already does;
  - digits-only fields only when the text has a digit;
  - date fields never;
  - unsearchable fields never.
  If no field can match the text, nothing is searched and the result is empty.
- **SQL** (`JdbcBusinessRecordRepository.appendAnywhereRank`): a `cross join lateral` reads only the submission's own value rows, in its own tenant and source. It gives each submission its best rank:
  - 0: exact on an identifier;
  - 1: exact on another field;
  - 2: starts with;
  - 3: contains.
  Exact keeps the configured normalization. Starts with and contains ignore case, like the field search. Results are sorted by rank, then newest first. Other searches are unchanged.
- **`matchedFields`** on each result (`RecordSummary.matchedFields`): the label and the record's own value of every field that matched, best match first. These are worked out in Java with the same rules as the SQL (`matchRank`). They come only from fields the search tried.
- **Audit:** the `BUSINESS_SEARCH` entry adds `"anywhere":true` to the count. The text itself is never stored. The Audit list reads "… searched all fields and found N" (`auditWords.ts`).

**Part B, screens** (match the two boards; side-by-side images in `.runtime/ui-compare/quick-search-*.png`):
- **Typing** (Home and Records, `QuickSearch.tsx`, `quickSearchRules.ts`). After 2 characters a list opens under the box with:
  - "Search everywhere for "…"", highlighted;
  - "Or only in one field": one row per field the server would try, worded with its match ("Borrower contains", "Loan type is", "Closing agent starts with"). Picking one runs today's field search and chip;
  - "Records you opened recently" that match, from Recently viewed. Clicking one opens the record as Home does.
  Arrow keys, Enter and Escape work, using the combobox pattern so the focus stays in the box. On phones the list is full width with taller rows.
- The old routing (digits to the identifier, text to the name) and the "Search it as … instead" suggestion are removed (`searchRouting.ts`). The placeholder wording stays.
- **Results** (`Workspace.tsx`):
  - the chip reads "Anywhere patel";
  - the heading reads "N records · "patel" in any searchable field · <system>", and the sort label reads "Best match first";
  - the footer adds "Narrow it with Filters, or pick one field from the search list.";
  - the matching text is marked in the row (number, title, extra column), in the record panel's Details and in the "Matched …" line;
  - "Matched Co-borrower: Priya Patel" shows under the title when the best match isn't already a visible column or part of the title.
- **Filters:** Filters and date ranges work alongside it. Applying Filters on the same sending system keeps the "anywhere" text.
- **Local seed only** (`Seed-SDV-Canvas-Demo.ps1`): two searchable fields, Co-borrower (contains) and Closing agent (starts with), are added to the Loan Origination System, last in its field order. Values are set on two existing records: 300000000004 gets co-borrower "Priya Patel" and 300000000006 gets closing agent "Patel & Sons Title". No new record or document.

**Choice I made:** Records never fetches Recently viewed itself. It reuses the list Home loaded (kept in the store), so no new server call and no new audit entry. If someone opens Records without visiting Home first, the "Records you opened recently" part is left out. The typing board's compare run shows exactly that.

## Query plan (development data)

Full plan in this session's scratchpad. The development data is tiny: 20 submissions and 102 field values in total, and 9 submissions in the loan system.
- **As run** ("patel" on the loan system): Postgres scanned both small tables. Execution took 0.27 ms.
- **With sequential scans turned off** (the shape a larger table gets):
  - an index scan on `submissions (tenant_id, source_id, …)`;
  - for each submission, a lookup of its own value rows through the unique `(tenant_id, submission_id, field_key)` index, aggregated to the rank;
  - then one sort by rank. Execution took 0.42 ms.

**What this means at scale:** the work grows with the number of submissions in the sending system. Every submission's values are read, and all matches are sorted before the first page can be returned. "Contains" can't use a btree index. The service also re-runs the query for each further batch of 200. This is fine for thousands to tens of thousands of submissions. For hundreds of thousands I expect a search to take seconds. That is the `pg_trgm` backlog item; it was not added here.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (app stopped first) | BUILD SUCCESS. Backend tests: 181 run, 0 failures, 0 errors (sdv-web 142). Frontend build passed. |
| `.\start-local.cmd`, then `.\Seed-SDV-Canvas-Demo.ps1` | started; the seed added the two fields and values |
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `npm run ui:capture` | 20 passed, 4 skipped. The skips are the usual 3, plus the new quick search test on the iPad project (it runs once, on desktop, and takes the 390 px shots itself). |
| `npm run ui:compare` | 21 passed (19 before, plus the 2 quick search boards) |

The first full tour run failed in the new test: it chose the loan system without reloading, so the page still searched LexaVault. A second run failed at the 390 px step, where the record drawer covered Clear. Both were test-order problems in the new test, not app bugs. Both were fixed and the full tour was rerun as shown above.

## Tests: counts, and any failures with their output

- **Backend, new (20):**
  - `QuickSearchTest` (13): which fields are tried (digits-only, date and unsearchable fields); each match kind and its rank; `matchedFields`; unsearchable and date fields never matched; best-match order kept; too short or too long refused; alone and with a date range and a field; the audit entry; tenant isolation.
  - `AnywhereSearchSqlTest` (7): the SQL for exact, starts with and contains; the ranking and order; reading only the tried fields in the caller's tenant and source; combined with a field and dates; unchanged without `anywhere`.
- **Tour, new test** "Quick search across all fields (task 0022)": the list after 2 characters; field rows; no digits-only or date rows for letters; Escape; keyboard-only pick of one field; search everywhere with the heading, "Best match first", order, "Matched …" lines and marks in rows and Details; with a date range (the request carries both); by name; a contract number lands first; a recently opened record from the list on Home with no recent-records call; a full-width list at 390 px. Screenshots at 1440 and 390, light and dark, are in `.runtime/ui-checks/0022/` (typing and results).
- **Tour, changed:** the Home search and journey steps 1 to 3 now expect the "Anywhere" chip with the contract first. The member-number suggestion step became "pick Member Number from the list". The box is found by role combobox, also in `ui-compare` and `sdv-comment-persistence`.

No failures in the final runs.

## Files changed

- Backend: `sdv-application/.../port/BusinessRecordRepository.java`, `.../business/BusinessExperienceService.java`, `.../business/BusinessExperienceView.java`, `sdv-persistence/.../business/JdbcBusinessRecordRepository.java`, `sdv-web/.../business/BusinessExperienceController.java`; tests `sdv-web/src/test/.../business/QuickSearchTest.java`, `.../persistence/business/AnywhereSearchSqlTest.java` (new)
- Frontend: `src/r7/QuickSearch.tsx`, `src/r7/quickSearchRules.ts` (new), `Home.tsx`, `Workspace.tsx`, `store.tsx`, `liveApi.ts`, `searchRouting.ts`, `auditWords.ts`, `r7.css`, `src/api.ts`
- e2e: `sdv-screen-tour.spec.ts`, `ui-compare.spec.ts`, `sdv-comment-persistence.spec.ts`
- `Seed-SDV-Canvas-Demo.ps1` (local seed only)
- `docs/work/0022-task.md`, `docs/work/0022-report.md`, `docs/design/vero-quick-search-typing.html`, `docs/design/vero-quick-search-results.html` (the approved boards, used by `ui:compare`)

`Start-SDV-Local.ps1` was left as it was and not committed.

## Commits (hash and message), or "not committed"

See `git log` on `feature/quick-search`: "feat(search): quick search across all fields (task 0022)". Not pushed or merged.

## Not done, and why

Nothing in the task was left undone. Differences from the boards:
- **Order of the board's examples:** the task's rule puts "starts with" before "contains". So "Patel & Sons Title" (closing agent, starts with) comes before "Priya Patel" (co-borrower, contains). The board shows Grace Lin last.
- **Records keeps its Filters and Clear buttons** while typing. The typing board shows a Search button there.
- **The Source chip** still shows when there are several sending systems, as before. The results board doesn't show it.
- **Detail order:** the record panel keeps Documents above Details, as approved in earlier tasks. Co-borrower is last in Details because the seed puts the new fields last.

## Questions for the owner

1. **Exact fields keep their capitals.** "Loan type is "mortgage"" finds nothing, because Loan type is Exact with Trim and stores "Mortgage". This follows the task ("each field with its own match"), but people may expect it to ignore case. Should exact text fields ignore case in quick search? That would be a small server change, and no schema change.
2. **Records opened before Home:** is it acceptable that "Records you opened recently" appears only once Home has loaded the list? The alternative is for Records to load it itself, which adds one audited "looked at Recently viewed" event.

## Suggested next step

Owner review of `chore/remove-prototype-code` (0021), then of `feature/quick-search` (0022). Then the `pg_trgm` backlog item, if large sending systems are expected soon.

## Follow-up (owner review, 2026-11-19)

The owner approved 0022 and asked for two changes. These also answer the two questions above.

**1. Exact text fields ignore capitals**, in quick search and in single-field search. "mortgage" now finds "Mortgage". There is no schema change and no new dependency.
- **Which fields** (`BusinessExperienceService.exactIgnoresCase`): Exact, type Text or Email, not an identifier, not digits-only. Identifiers, digits-only fields and other types (numbers and so on) still compare exactly as before. Starts with and Contains already ignored case.
- **How:** `SearchCriterion` and `AnywhereField` carry `ignoreCase`. The SQL then compares `lower(v.normalized_value)=lower(:value)`; otherwise it is unchanged. `matchRank` (for `matchedFields`) uses the same rule. The row is still found through the `(tenant_id, submission_id, field_key)` index, so index use doesn't change.
- **Tests:**
  - `QuickSearchTest`, 5 new: which fields ignore case; quick search sends Loan type with `ignoreCase` and "mortgage" matches "Mortgage"; the contract number stays exact; single-field search on Loan type ignores case and the contract number doesn't; `matchRank` with and without it.
  - `AnywhereSearchSqlTest`, 2 new: the SQL for quick search and for single-field search.
  - Tour: "mortgage" everywhere and "Loan type is "mortgage"" each find the 4 Mortgage loans.

**2. Records loads Recently viewed itself**, once per visit. It uses the same call as Home (`/me/recent-records?limit=8`) and writes the same routine `BUSINESS_RECENT_RECORDS_LISTED` audit entry (`Workspace.tsx`).
- Records stays mounted, hidden, on other pages, so the load follows the route (each time the person comes to Records), not the mount.
- A ref keeps it to one call per visit, even when React StrictMode runs the effect twice in development.
- If the load fails, the list leaves out "Records you opened recently", as before.
- `RECENT_LIMIT` is now shared from `liveApi.ts`.
- **Tour checks:**
  - picking a recent record on Home makes no call while typing, and arriving on Records makes exactly one;
  - **opening Records directly** (a fresh page load on `#/workspace/search`, Home never shown) makes exactly one call, and typing "gomez" shows 300000000004 under "Records you opened recently". Screenshot `quick-search-recent-records-direct`.
- Noted, not changed: Home on its own also runs its load twice in the development server because of StrictMode. The production build runs it once.

**Commands run and results**

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (app stopped first) | BUILD SUCCESS. Backend tests: 188 run, 0 failures, 0 errors (sdv-web 149; 7 new). Frontend build passed. |
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `.\Start-SDV-Local.ps1` | started (the 0022 seed data was already in place) |
| `npm run ui:capture` | 20 passed, 4 skipped (the same skips as before) |
| `npm run ui:compare` | 21 passed |

**Failures along the way** (all fixed before the final runs above):
- The new direct-open check first counted 0 calls. The page was already on `#/workspace`, so `page.goto` only changed the hash. The test now goes to `about:blank` first.
- It then counted 2 calls, because of StrictMode's double effect in development. The ref guard in `Workspace.tsx` fixes that in the app; the test was not loosened.

**Files changed:**
- Backend: `BusinessRecordRepository.java`, `BusinessExperienceService.java`, `JdbcBusinessRecordRepository.java`; tests `QuickSearchTest.java`, `AnywhereSearchSqlTest.java`
- Frontend: `Workspace.tsx`, `Home.tsx`, `store.tsx`, `liveApi.ts`
- Tour: `e2e/sdv-screen-tour.spec.ts`
- This report

`Start-SDV-Local.ps1` was left as it was and not committed.
