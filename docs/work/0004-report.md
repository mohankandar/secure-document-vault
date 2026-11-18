# Report 0004: search follow-ups (date ranges, case, suggestions, test data)

Date: 2026-11-17
Branch: `feature/ui-search-2` (from `feature/ui-search`)
Outcome: DONE

Before this task, at the owner's request: `Stop-SDV-Local.ps1` committed with the owner's earlier edit (`860c4a0` on `feature/ui-search`, so it is also on this branch).

## What was done

**Part A: list everything in a date range** (`src/r7/Workspace.tsx`, `src/r7/searchRouting.ts`, `src/r7/liveApi.ts`)
- Filters now has two ranges, each with visible **From** and **To** labels and a short hint:
  - **Received** ("When the submission arrived"): server `dateFrom`/`dateTo`, which filter **`submissions.created_at`**, the time the producer's submission arrived in SDV. A record matches when one of its submissions arrived in the range.
  - **Last updated** ("Latest activity on the submission"): `lastUpdatedFrom`/`lastUpdatedTo`, which filter `submissions.last_activity_at`.
- Quick buttons beside each range: Today, Last 7 days, Last 30 days, This month. A pressed button shows as selected; pressing it again clears the range.
- A date range alone is enough: Show results lists every record in the range, 25 per page with Previous and Next. The screen check now asks for "a field, or a Received or Last updated date range". The server already allowed this.
- Each range is one chip in the bar, e.g. "Received 19 Oct – 17 Nov ×", "Updated 10 Nov – 17 Nov", or "Received from 1 Nov" when only one end is set.
- **Changed:** dates are now the person's own calendar days, from the start of the From day to the end of the To day in their time zone. Before, Last updated used UTC days.

**Part B: Contains and Starts with ignore case** (backend: `JdbcBusinessRecordRepository.java`)
- The predicates are now `position(lower(:value) in lower(normalized_value))` (`>0` for Contains, `=1` for Starts with). Exact is unchanged and keeps the field's configured normalization.
- **Index: no migration was needed.** EXPLAIN on the development database shows the same plan as before: each candidate's metadata row is found through the unique index `(tenant_id, submission_id, field_key)`, and the value test is a filter on that single row. Contains was never index-driven (`position(...)>0` cannot use a btree), so `lower()` changes nothing there.
- Tests: new `JdbcBusinessRecordRepositorySearchSqlTest` (4 tests):
  - Contains and Starts with lower-case both sides;
  - Exact has no `lower()`;
  - a Received range alone filters `created_at` and adds no metadata condition.

  It lives in `sdv-web`'s test tree because `sdv-persistence` has no test dependencies, and adding them would be a dependency change.
- Checked through the live API:
  - "pat" and "PATEL" in Last Name (Contains) each find 4 records;
  - First Name (Exact): "omar" finds 0 and "Omar" finds 1, so case is unchanged there.

**Part C: suggestion when the box finds nothing**
- A number typed in the box that finds nothing as the first identifier shows "No Contract Number 20000000001", with one button per other identifier that takes the same input (DIGITS_ONLY): **"Search it as Member Number instead"**, plus Open Filters.
- One click replaces the chip and searches once; nothing is sent automatically.
- Text identifiers (e.g. Service Entity) are not offered, so that every number doesn't suggest them.

**Part D: e2e fails when demo data is missing**
- The record steps now fail with: "No demo records: <what is missing>. Run .\Seed-SDV-Demo-Records.ps1 -SourceDisplayName LexaVault (and -FreshTestData -Count 3 for names, with Last Name set to Contains). Set SDV_E2E_ALLOW_NO_DATA=1 to skip these steps on purpose."
- With `SDV_E2E_ALLOW_NO_DATA=1` they are skipped, with a note in the test output.
- New tour steps, run as Business user and Document steward on desktop and iPad:
  - the suggestion (member number, then one click);
  - a **lower-case** name search ("pat");
  - a date-only search with the **Last 30 days** quick button;
  - a typed date-only **Last updated** range.

## Commands run and results

| Command | Result |
|---|---|
| `mvn test` for the new SQL test | 4/4 PASS |
| `.\Build-SDV-All.ps1` (includes `npm run build`, which runs the type check) | **PASS**: BUILD SUCCESS, 49 tests, 0 failures, 0 errors; frontend built |
| `npm run typecheck` | PASS |
| Live API (rebuilt backend) | "pat" → 4, "PATEL" → 4, Exact "omar" → 0, "Omar" → 1, Received range only → 7 records |
| EXPLAIN (development database) | Unique index `(tenant_id, submission_id, field_key)`; the `lower()` test is a filter on that row; plan unchanged |
| `npm run ui:capture` | **PASS 12/12** (desktop + iPad) with the new steps |
| `SDV_DEMO_CONTRACT=999999999999`, Business User test | **FAILS** as intended, with the "No demo records … Run .\Seed-SDV-Demo-Records.ps1 …" message |
| The same with `SDV_E2E_ALLOW_NO_DATA=1` | PASS (record steps skipped on purpose) |
| `.\stop-local.cmd`, `.\start-local.cmd` | Both work; containers stayed up |

The comment spec was not rerun; it did not change in this task.

Screenshots: `.runtime/ui-checks/0004/steward-<1440|390>-<light|dark>-<1-lowercase-name|2-suggestion|3-filters-quick-range|4-received-range-results>.png` (16 files). The tour's captures, including `suggestion`, `results-name`, `results-received-last-30-days` and `results-updated-range`, are in `frontend/sdv-ui/test-results/`.

## Files changed

- Backend: `sdv-persistence/.../JdbcBusinessRecordRepository.java`; new test `sdv-web/src/test/java/com/sdv/platform/persistence/business/JdbcBusinessRecordRepositorySearchSqlTest.java`
- Frontend: `src/r7/Workspace.tsx`, `src/r7/searchRouting.ts`, `src/r7/liveApi.ts`, `src/r7/r7.css`
- e2e: `e2e/sdv-screen-tour.spec.ts`
- Docs: `docs/work/0004-task.md`, `docs/work/0004-report.md`

## Commits

One commit on `feature/ui-search-2`: "feat(search): date ranges with quick buttons, case-insensitive name search, identifier suggestion (task 0004)". Not pushed or merged.

## Questions for the owner

1. **Index for date-only searches.** The submissions table has no index that includes `created_at` or `last_activity_at` together with tenant and source. A date-only search over a large source therefore reads all of that source's submissions through `(tenant_id, source_id, …)` and filters by date. An index on `(tenant_id, source_id, created_at)`, and maybe one with `last_activity_at`, would fix this but needs a migration. Should I write one? I have not.
2. **Faster Contains on very large tables** would need the `pg_trgm` extension and a GIN index on `lower(normalized_value)`: also a migration, and a database extension. Does it go to the backlog?
3. **Local days.** Last updated now uses the person's own calendar days instead of UTC days, the same as Received. Is that what you want?
