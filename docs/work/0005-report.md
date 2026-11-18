# Report 0005: Home screen, and date indexes for search

Date: 2026-11-17
Branch: `feature/ui-home` (from `feature/ui-search-2`)
Outcome: PARTLY DONE. Parts A and B are done. **Part C is stopped**: the task's condition for the migration cannot be met in SDV today (see "Not done, and why").

## What was done

**Part A: Home page** (`src/r7/Home.tsx` new; `Shell.tsx`, `store.tsx`, `data.ts`, `Workspace.tsx`, `liveApi.ts`, `r7.css`)
- A **Home** area tab before **Records** for Business user and Document steward. Opening the app without an address lands on the person's first area: Home for these two roles, their own area for admins (they have no Home tab). The local role switch also lands search roles on Home.
- Greeting by time of day with the first word of the session's `displayName` ("Good evening, Maya"). With no name it shows just the greeting. The local test sign-in has no name, so it reads "Good evening".
- **Search bar:** the same routing as Records (tasks 0003 and 0004), with Filters and Search buttons. Searching moves to Records with the results and the chip. Filters opens Records with the Filters panel open. A typed value Records cannot route shows the same message on Home.
- **Recently viewed:**
  - up to 8 records, each with the identifier, the first two preview fields, and when the person last opened it ("3 min ago");
  - clicking one opens Records, searched by that record's identifiers, with its record panel open;
  - empty state: "Records you open will show here."
- **Needs attention:**
  - up to 5 records with missing documents received in the last 30 days, each as "300000000090 · Omar Patelli · 1 received · Missing documents" (see the question about counts);
  - **Show all** opens Records filtered the same way (chips "Missing documents" and "Received 19 Oct – 17 Nov");
  - empty state: "Nothing needs attention."
- **Ask Vero:** never shown in a product build. A fixed-height slot keeps the right column's shape. In development builds a dashed "Later release" marker shows there.
- Phones: the cards stack, and Home and Records appear in the bottom bar.
- Records gained a "Missing documents" chip (`incompleteOnly`), and it carries out searches started on Home (`store.requestSearch`).

**Part B: backend** (`BusinessExperienceService`, `BusinessExperienceView`, `BusinessRecordRepository`, `JdbcBusinessRecordRepository`, `BusinessExperienceController`)
1. **`GET /api/v1/business/me/recent-records?limit=8`** (requires `SEARCH_RECORDS`; limit 1–20, default 8):
   - Reads only the signed-in person's own successful `DOCUMENT_VIEW_OPENED` events (`actor_type='USER'`, `actor_id` = their subject) in their tenant, from the last 90 days, at most their newest 500 events.
   - Keeps only documents still `AVAILABLE`; documents past retention or deleted are no longer `AVAILABLE`.
   - One entry per record, newest open first.
   - Each record goes through the same identity and aggregate code as search and record detail, and is returned only while that still finds it with at least one available document.
   - Response per record: `sourceId`, the same `RecordSummary` as search, and `lastOpenedAt`.
   - Reading the list is audited as `BUSINESS_RECENT_RECORDS_LISTED` (count only), like `BUSINESS_SEARCH`.
2. **`incompleteOnly`** on `POST /api/v1/business/search`: only submissions that are not `COMPLETE`, the same meaning as the `incomplete` flag in results. It counts as a criterion, so it works alone or with dates and fields. Authorization, paging and audit are the same as search.
3. **Tests (10 new; all in `sdv-web`'s test tree, because `sdv-application` and `sdv-persistence` have no Mockito):**
   - `BusinessExperienceServiceHomeTest` (6):
     - only the signed-in person's own activity is read;
     - a record with no AVAILABLE document left (past retention) is dropped;
     - one entry per record, with the newest time;
     - the list read is audited;
     - an out-of-range limit is refused;
     - `incompleteOnly` alone, and with a Received range, is passed to the repository;
     - a search with no criterion is still refused.
   - `JdbcBusinessRecordRepositorySearchSqlTest` (4 new):
     - `incompleteOnly` alone adds `s.status<>'COMPLETE'` and no metadata condition;
     - with a Received range both apply;
     - without the flag there is no status filter;
     - the recent query filters on tenant, action, `actor_id`, `outcome='SUCCESS'` and `d.status='AVAILABLE'`, with the actor bound as a parameter.
   - Live, against the development database:
     - the steward gets their own list (100000000001);
     - a subject who never opened anything (`local-new-colleague`) gets `{"records":[]}`;
     - Tenant IT gets 403;
     - `incompleteOnly` alone returns only 300000000090.

**Query plan for Recently viewed** (development database, EXPLAIN ANALYZE, 0.9 ms): Bitmap Index Scan on `idx_audit_events_tenant_action_time (tenant_id, action, occurred_at)` for the 90-day window, then a filter on `actor_id`, then a lookup by index into `documents`.
- This is fine for today's data. In a busy tenant, though, it reads every person's viewer opens from the last 90 days to keep one person's.
- An index on `audit_events (tenant_id, actor_id, action, occurred_at DESC)` would make it a direct lookup. It belongs in Part C (see below).

## Not done, and why

**Part C (date indexes).** The task requires the migration to "run through the normal schema step, the one customers run themselves before starting the app (startup inserts or changes nothing)". **SDV has no such step.**
- Liquibase runs automatically when the app starts: Spring Boot's default, with only `spring.liquibase.change-log` set in `application.yml`.
- In LOCAL mode, startup even inserts seed data (`002-local-foundation-seed.yaml`, context `local`).
- The "schema step" appears only in LexaVault's design (`LOGIN_CUSTOMER_ENTITY_DESIGN.md`, Phases 5–6, not built).
- So a migration added now would run at startup, which the task rules out. Building a separate schema step would be another backend change, which the task also rules out. I stopped instead of guessing.

What I prepared for the decision: today's plan for a date-only search reads `submissions` through `idx_submissions_operations_status_activity (tenant_id, …)` and filters `source_id` and `created_at` row by row; no index contains the date. The planned indexes are:
- `submissions (tenant_id, source_id, created_at DESC, submission_id)` (Received);
- `submissions (tenant_id, source_id, last_activity_at DESC, submission_id)` (Last updated);
- `audit_events (tenant_id, actor_id, action, occurred_at DESC)` (Recently viewed).

On a large table, plain `CREATE INDEX` blocks writes to that table while it builds. `CREATE INDEX CONCURRENTLY` avoids that, but cannot run inside a transaction, so the Liquibase changeset would need `runInTransaction: false`.

## Commands run and results

| Command | Result |
|---|---|
| `mvn test` (new tests) | 8/8 SQL tests (4 new), 6/6 service tests |
| `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 59 tests, 0 failures, 0 errors; frontend built |
| `npm run typecheck`, `npm run build` (after the last fixes) | PASS |
| Live API checks (rebuilt backend) | As listed under Part B |
| `npm run ui:capture` | **PASS 14/14** (desktop + iPad), twice in a row. Business user and Document steward: land on Home; Needs attention shows 300000000090; Show all gives the "Missing documents" and "Received" chips; a search from Home moves to Records; after the record journey, 100000000001 is first under Recently viewed and opens in Records with its panel. New "Home empty states" test (with empty responses served by the test). Admins and the local admin land on their own area, with no Home or Records tab. |
| Product build check (production build with LOCAL sign-in, preview) | Home loads; **no Ask Vero card**; Recently viewed 1 row, Needs attention 1 row |
| `.\stop-local.cmd`, `.\start-local.cmd` | Both work; containers stayed up |

Screenshots: `.runtime/ui-checks/0005/`:
- `<business|steward>-<1440|390>-<light|dark>-home.png` (development build; the Ask Vero marker is visible);
- `product-business-<1440|390>-<light|dark>-home.png` (product build; no Ask Vero).

The tour's captures (`home`, `records-needs-attention`, `records-from-home-search`, `home-recently-viewed`, `records-from-recently-viewed`, `home-empty`) are in `frontend/sdv-ui/test-results/`.

## Files changed

- Backend: `BusinessRecordRepository.java`, `JdbcBusinessRecordRepository.java`, `BusinessExperienceService.java`, `BusinessExperienceView.java`, `BusinessExperienceController.java`; tests `JdbcBusinessRecordRepositorySearchSqlTest.java`, `BusinessExperienceServiceHomeTest.java` (new)
- Frontend: `src/r7/Home.tsx` (new), `Shell.tsx`, `store.tsx`, `data.ts`, `Workspace.tsx`, `liveApi.ts`, `r7.css`
- e2e: `e2e/sdv-screen-tour.spec.ts`
- Docs: `docs/work/0005-task.md`, `docs/work/0005-report.md`

## Commits

One commit on `feature/ui-home`: "feat(home): Home page with Recently viewed and Needs attention; recent-records endpoint and incompleteOnly search (task 0005, parts A and B)". Not pushed or merged.

## Questions for the owner

1. **Part C and the schema step.** Choose one:
   - (a) add the three indexes now as a normal Liquibase changeset, which runs at app startup as every migration does today (`CREATE INDEX CONCURRENTLY`, `runInTransaction: false`);
   - (b) first build a separate schema step (Liquibase off at startup, a command to run it), as a task of its own;
   - (c) wait for the shared design's schema step.
2. **"1 of 2 documents received" on Home.** The search summary only says a record is incomplete. Getting the counts would mean opening each record's detail, which writes a `BUSINESS_RECORD_OPENED` audit event for records the person never opened, on every Home visit. So Home shows "1 received · Missing documents". Showing "1 of 2" needs a small backend change: add the expected and received counts to the search summary. Should I?
3. **Recently viewed window:** the last 90 days and the newest 500 view events. Are these acceptable?
4. Reading Recently viewed is audited as `BUSINESS_RECENT_RECORDS_LISTED` (count only), like a search. Keep it, or not audit reading one's own list?
