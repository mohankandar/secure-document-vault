# Report 0005: Home screen, and date indexes for search

Date: 2026-11-17
Branch: `feature/ui-home` (from `feature/ui-search-2`)
Outcome: DONE after the follow-up of 2026-11-18. The first pass did Parts A and B and stopped Part C; the owner then chose a normal migration, which is now done (see "Follow-up after approval").

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

## Follow-up after approval (2026-11-18)

The owner approved 0005 and decided: Part C as option (a), with the indexes built concurrently plus an index for Recently viewed; counts on Home; 90 days and 500 opens is fine; keep auditing Recently viewed, but as routine.

**1. Migration 018 (Part C)**
- `db/changelog/018-search-date-indexes.yaml` and `.sql`, included in `db.changelog-master.yaml`. Three indexes, each `CREATE INDEX CONCURRENTLY`, in a changeset with `runInTransaction: false` (concurrent builds cannot run inside a transaction):
  - `idx_submissions_tenant_source_created` on `submissions (tenant_id, source_id, created_at desc, submission_id)` (Received ranges);
  - `idx_submissions_tenant_source_activity` on `submissions (tenant_id, source_id, last_activity_at desc, submission_id)` (Last updated ranges);
  - `idx_audit_events_tenant_actor_action_time` on `audit_events (tenant_id, actor_id, action, occurred_at desc)` (Recently viewed).
- It runs like every other migration: Liquibase at app startup. On the development database: `databasechangelog` shows `018-search-date-indexes EXECUTED`, and all three indexes are `indisvalid = true`.
- **On a large existing table:** a concurrent build does not block reads or writes. It reads the table twice and waits for open transactions, so the first startup after the update takes longer, roughly one table scan per index.
- **If the build is interrupted** (crash, cancel, duplicate error), PostgreSQL leaves an INVALID index. The changeset deliberately has no `IF NOT EXISTS`, so a rerun fails loudly instead of quietly keeping a broken index. Recovery: find the index with `select indexrelid::regclass from pg_index where not indisvalid;`, run `drop index concurrently <name>;`, and start again.
- README: "Current latest Liquibase migration" corrected from 016 to 018.

**Query plans, before and after** (development database; only 8 submissions and about 650 audit events, so the plans show which index is usable, not timings):

| Query | Before | After |
|---|---|---|
| Received range only (`created_at`) | Index scan on `(tenant_id, …)`, then source and date checked row by row | With sequential scans off: **Index Only Scan on `idx_submissions_tenant_source_created`**, with the date range in the index condition and no sort step. With 8 rows the planner rightly reads the table directly. |
| Last updated range only | As above | With sequential scans off the planner chose the Received index, because results are ordered by received date and the table is tiny. On real volumes it chooses by how selective the date is; the new last-activity index serves narrow Last updated ranges. |
| Recently viewed (event scan) | Bitmap scan on `(tenant_id, action, occurred_at)`, then **filter on `actor_id`**: in a busy tenant, everyone's viewer opens from the last 90 days | **Index Scan on `idx_audit_events_tenant_actor_action_time`**: the condition includes tenant, actor, action and time, so only the person's own events are read |

**2. Is startup seed data only for local mode? No.** I could not confirm this; the opposite is true today:
- The seed changeset `002-local-foundation-seed.yaml` has `context: local`.
- Only `application-local.yml` sets `spring.liquibase.contexts: local`; `application.yml` sets no context.
- In Liquibase, **when no context is given at runtime, every changeset runs, including `context: local` ones.**
- Checked with the bundled Liquibase 4.31.1 itself (`ContextChangeSetFilter`, no database involved):

  | Runtime context | Seed runs? |
  |---|---|
  | none set | **yes** |
  | `''` | **yes** |
  | `local` | yes |
  | `production` | no |

- So an installation started without `--spring.profiles.active=local` would insert the two demo tenants (`local-demo`, `local-isolation`) on its first startup. Today every start script passes the local profile, and no other configuration sets a context.
- I have **not** changed this, because it is a startup and schema behaviour change nobody asked for. The fix is one line in `application.yml`: a default context for everything that is not local, e.g. `spring.liquibase.contexts: ${SDV_LIQUIBASE_CONTEXTS:production}`. Changesets without a context still run; `context: local` ones are then skipped. Owner decision below.

**3. Counts in search results and on Home**
- `RecordSummary` (search results, and Recently viewed) gains `incompleteExpectedCount` and `incompleteReceivedCount`: the expected and received counts added up over the record's incomplete submissions, counted exactly as record detail counts them (0 when complete). They come from data the search already loads; there are no extra queries.
- Home's Needs attention now reads "300000000090 · Omar Patelli · **1 of 2 documents received**".
- The Records table row still shows the "Missing documents" badge; it could now show "1 of 2 received", as design screen 2 does, if wanted.
- Tests: 2 new in `BusinessExperienceServiceHomeTest`: incomplete gives expected 2 and received 1; complete gives 0 and 0. The e2e Home step now checks "1 of 2 documents received".

**4. Recently viewed audit is routine**
- `BUSINESS_RECENT_RECORDS_LISTED` is still recorded, and is now in the Audit screen's routine set (`ROUTINE_AUDIT_ACTIONS` in `src/r7/Operations.tsx`). It is hidden by default and shown with **Include view events**, or when that action is chosen in the Action filter (new option "Recently viewed list read").
- Checked as Platform operations: 0 rows by default, 10 rows with the toggle on.
- **To be precise about "like view events":** in the Audit screen the routine set until now held only `AUDIT_VIEWED`. Document view events (`DOCUMENT_VIEW_OPENED`, `DOCUMENT_PAGE_VIEWED`) are hidden by default in the record and document **activity** panels (server-side), but **not** in the Audit screen. I did not change that. Should view events also be routine in Audit?

**Checks**

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 61 tests, 0 failures |
| `npm run typecheck` | PASS |
| `.\start-local.cmd` | Migration 018 applied at startup; three valid indexes |
| `npm run ui:capture` | **PASS 14/14** (desktop + iPad) |
| Audit routine check (Playwright, Platform operations) | Hidden by default; shown with the toggle |

Screenshots refreshed in `.runtime/ui-checks/0005/` (Home now shows "1 of 2 documents received"), plus `audit-routine-included.png`.

**Questions for the owner (follow-up)**
1. Set a default Liquibase context outside local mode (one line in `application.yml`) so the demo tenants are never inserted outside local mode?
2. Show "1 of 2 received" in the Records table row as well?
3. Should document view events also be routine (hidden by default) in the Audit screen?

## Second follow-up (2026-11-18)

The owner's answers: 1 yes, 2 yes, 3 no.

**1. Local seed runs only with the local profile**
- `application.yml` now sets `spring.liquibase.contexts: ${SDV_LIQUIBASE_CONTEXTS:production}`:
  - a start without the local profile uses the `production` context, so `context: local` changesets are skipped;
  - changesets without a context (every schema change, including 018) run in every context;
  - the local profile still sets `local` (`application-local.yml`);
  - an installation can override the context with `SDV_LIQUIBASE_CONTEXTS`.
- New test `LiquibaseSeedContextTest` (5 tests). It uses the pieces the app uses at startup: Spring's YAML loader resolves `spring.liquibase.contexts` from `application.yml` (plus `application-local.yml` for the local profile), and Liquibase's own parser and context filter decide which changesets of the real master changelog run. It proves:
  - without the local profile the context is `production`, and with it `local`;
  - a non-local start runs **no** local-only changeset, so `002-local-foundation-seed`, which inserts the demo tenants, does not run;
  - the local profile still runs the seed;
  - every changeset without a context runs in both;
  - with no context at all, Liquibase would run the seed (the old behaviour this guards against).
- **The test bites:** with the new line removed it fails 2 of 5 (context `null` instead of `production`, and "the local seed must not run outside the local profile").
- Not done: starting the app without the local profile against an empty database. That needs a scratch database, and the only Postgres here is the development container, which holds real development data.
- Local start after the change works: 19 changesets recorded, nothing new applied, and `002` recorded with context `local` as before.

**2. Records rows show "1 of 2 received"** (design screen 2). The row badge uses the new summary counts, for example "Omar · 1 of 2 received". Without counts it falls back to "Missing documents". The tour checks the 300000000090 row after "Show all". Screenshot: `.runtime/ui-checks/0005/records-row-1-of-2-received.png`.

**3. Document view events stay visible in Audit.** No change; only `AUDIT_VIEWED` and `BUSINESS_RECENT_RECORDS_LISTED` are routine there.

**`Start-SDV-Local.ps1`: not my work, left untouched.** Its uncommitted change (73 lines added, 17 removed, including the Vault token-file handling) was already in the working copy when this session started on 2026-11-17, as `CLAUDE.md` notes. My only edit to that file was the two health-check lines (`localhost` → `127.0.0.1`), committed on its own in `f904987`; they are not part of the remaining uncommitted diff. I did not stage, commit or change the rest.

**Checks**

| Command | Result |
|---|---|
| `mvn test -Dtest=LiquibaseSeedContextTest` | 5/5 PASS; 2/5 FAIL with the fix removed (as intended), then restored |
| `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 66 tests, 0 failures |
| `npm run typecheck` | PASS |
| `.\stop-local.cmd`, `.\start-local.cmd` | Both work; local start applies nothing new |
| `npm run ui:capture` | **PASS 14/14**, including "1 of 2 received" in the Records row |
