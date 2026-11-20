# Report 0023: Three small items (default sending system, demo files, next cleanup)

Date: 2026-11-19
Branch: `feature/small-items-1` (from `release/vero-r7`)
Outcome: DONE

## What was done

**1. Records picks the right sending system by default**
- **Server** (read-only, no schema change). `GET /api/v1/business/search-schema` now sends three more values for each sending system, with the same permission as before:
  - `status`: `ACTIVE` or `PAUSED`;
  - `hasRecords`: yes or no (not a count);
  - `lastDeliveredAt`: the newest `last_activity_at` of its submissions, or null.

  These come from one query for all systems (`JdbcBusinessRecordRepository.findSourceActivity`): a `max()` per system, read through the existing `(tenant_id, source_id, last_activity_at desc)` index, for the caller's tenant only (`BusinessExperienceService.searchSchema`).
- **The rule, in one place** (`sourceChoice.ts`, `pickDefault`), used by Home and Records:
  1. only a system with a searchable field and at least one record can be the default;
  2. the last choice in this browser, if it still qualifies;
  3. else the active system that delivered most recently;
  4. a paused one only if no active one qualifies;
  5. ties keep the order by name.

  The old "first by name" default is removed. The frontend no longer hard-codes `status: 'ACTIVE'` (`liveApi.toSource`). Anything but `ACTIVE` counts as paused, and a missing flag counts as no records.
- **Filters' Source picker** (`Workspace.tsx`) lists every searchable system. Before, it filtered on `ACTIVE`, which did nothing while the status was always `ACTIVE`. With the real status, a paused default would have been missing from its own picker.

**2. Demo files in the local seed**
- **The one command is `Seed-SDV-Canvas-Demo.ps1`** (the current seed). It now runs `Seed-SDV-Demo-Records.ps1` itself (plain, `-FreshTestData -Count 3`, `-ProblemDemo`). It is documented in `README.md`, "Demo data …".
- **What existed only in this machine's database** and the seed now creates:
  - the LexaVault sending system and its six fields (Last Name set to "contains");
  - the first storage volume, `primary-a`;
  - record 300000000090 (Omar Patelli, 1 of 2 documents, for Needs attention);
  - record 300000000092 (Lena Patel) with the 14-page `loan-file.pdf`;
  - the `e2e-demo` tag and one demo comment on 100000000001's Terms and Conditions.pdf (the document the tour opens read-only);
  - the paused "Tour test system 20261118181639", whose two fields can't be searched.

  It already made Acme Lending, the loan system, Title Records and `primary-b`. The loan system is delivered last, so it is the default on a fresh machine as well as here.
- **Running it twice changes nothing.** Every field, order or title save adds a field version and an audit event (the reason the loan fields here are at version 7). So the seed now writes only what is missing, in both scripts. The three `-ProblemDemo` state updates are guarded too.
- **Two seed bugs found by the empty-database run, and fixed:**
  - `-ProblemDemo` sent a single expected document as an object instead of a list, and the API refused it (400);
  - a wrong property name in the tag check.
- **Demo PDF:** `scripts/demo-files/loan-file-14-pages.pdf` (14 pages, 7.6 KB, made-up text). It is made by `scripts/demo-files/New-SDV-DemoPdf.ps1` and committed.
- **Packaging:** the product builds are the backend jar (`backend/**/src/main`) and `frontend/sdv-ui/dist`, and neither reads `scripts/` or `test-tools/`. The two source-archive scripts (`New-SDV-V1-ReleasePackage.ps1`, `New-SDV-V1-GAPackage.ps1`) zip the whole tree minus a blocklist, so `demo-files` was added to both blocklists. The GA script is frozen at 1.0.0 and already refuses this tree (migrations past 010).
- **Check script:** `Test-SDV-DemoSeed.ps1` (new) creates `sdv_seed_test` inside `sdv-postgres`, starts Vero on it in local mode on port 8291, seeds twice and compares every table row by row, audit included. With `-WithTour` it also runs the tour and `ui:compare` through a second Vite on port 5193, then drops the database. Vero reads the Vault token file itself, as with start-local; the script only passes the path. For the second Vite, `vite.config.ts` now reads `SDV_UI_PORT` and `SDV_API_TARGET`; the defaults are unchanged.

**3. Next cleanup time on Health**
- **Server:** `GET /api/v1/operations/retention-status` adds `nextRunAt`. It is worked out from the scheduler's own settings (`RetentionScheduler.INITIAL_DELAY_MS` and `SWEEP_DELAY_MS`, the same placeholders the `@Scheduled` annotation uses), in `RetentionSweepMonitor`:
  - after a run: when it ended plus the sweep delay;
  - before the first run: when the scheduler started plus the initial delay;
  - null while a run is going on, before the scheduler starts, when the setting can't be read, or when the time has already passed.
- **Health** ends the cleanup sentence with "Next run: tomorrow at 07:00." The **Overview Cleanup tile** reads "Documents past their keep date · next run tomorrow 07:00". With no `nextRunAt`, nothing is said about the next run, and the tile keeps "last ran …" (`opsWords.ts`, `Operations.tsx`).

## Commands run and results

| Command | Result |
|---|---|
| `.\stop-local.cmd`, `.\Build-SDV-All.ps1`, `.\start-local.cmd` | BUILD SUCCESS. Backend: 205 tests, 0 failures, 0 errors (sdv-web 166). Frontend build passed. |
| `npm run typecheck`, `npm run build` | passed |
| `npm run ui:capture` (this machine) | 20 passed, 4 skipped (the usual 4) |
| `npm run ui:compare` (this machine) | 21 passed |
| `.\Test-SDV-DemoSeed.ps1 -WithTour` | all checks PASS: seed on an empty database; second run changed nothing (20 tables); tour 20 passed, 4 skipped; `ui:compare` 21 passed; `sdv_seed_test` dropped |

The seed check failed three times before it passed:
1. The `-ProblemDemo` 400 described above.
2. The tag property name.
3. The tour used 127.0.0.1. The local role switch is only offered on `localhost`, so the script now uses `localhost`.

A first tour run did not start because of a duplicate variable in the new test.

## Tests: counts, and any failures with their output

- **Backend, new (17):**
  - `SearchSchemaSourceStateTest` (6): active with records; paused; no records; missing from the result; one query for all systems; tenant isolation.
  - `SourceActivitySqlTest` (2): the caller's tenant only, and no count.
  - `RetentionNextRunTest` (9): a daily sample (07:00:02 to tomorrow 07:00:02); before the first run; after a failed run; null while running, before the scheduler starts, after the time has passed and when the setting can't be read; milliseconds or ISO setting; the scheduler's own placeholders.
- **Tour, changed:** "Records starts with the loan system while a paused, half-set-up system exists".
  - It checks the schema values and that the loan system delivered last.
  - Then ten loads in a row, typed straight after reload. Even loads have the Tour test system stored as the last choice. Every load finds 300000000001 in the Loan Origination System.
  - A stored LexaVault (which qualifies) is kept.
  - Simulated: LexaVault without records while stored, which is ignored; and the loan system paused, so LexaVault comes first.
- **Tour, new checks:**
  - Overview tile "next run tomorrow 07:00";
  - Health "… Next run: tomorrow at 07:00." (screenshot `health-next-run`);
  - no "Next run" when `nextRunAt` is null;
  - the live `nextRunAt` is in the future.

No failures in the final runs.

## Files changed

- Backend: `BusinessRecordRepository.java`, `BusinessExperienceService.java`, `BusinessExperienceView.java`, `JdbcBusinessRecordRepository.java`, `RetentionScheduler.java`, `RetentionSweepMonitor.java`. New tests: `SearchSchemaSourceStateTest.java`, `SourceActivitySqlTest.java`, `RetentionNextRunTest.java`.
- Frontend: `src/api.ts`, `src/r7/sourceChoice.ts`, `model.ts`, `liveApi.ts`, `Workspace.tsx`, `opsWords.ts`, `Operations.tsx`, `vite.config.ts`, `e2e/sdv-screen-tour.spec.ts`.
- Seed and scripts: `Seed-SDV-Canvas-Demo.ps1`, `Seed-SDV-Demo-Records.ps1`, `Test-SDV-DemoSeed.ps1` (new), `scripts/demo-files/` (new), `New-SDV-V1-ReleasePackage.ps1`, `New-SDV-V1-GAPackage.ps1`, `README.md`.
- `docs/work/0023-task.md`, `docs/work/0023-report.md`.

`Start-SDV-Local.ps1` was left as it was and not committed. The seed was never run against the `sdv` database. The check's storage folders are kept under `.runtime\seed-test\` (four runs, a few KB of encrypted files).

## Commits (hash and message), or "not committed"

`0e0fb95` "feat: default sending system, demo seed, next cleanup time (task 0023)" on `feature/small-items-1`, plus a commit adding this hash to the report. Not pushed or merged.

## Not done, and why

Nothing in the task was left undone. Two readings of mine:
- **When no system has a record yet** (a new installation), the default is the first searchable system by name. Records can then still be searched, and it finds nothing. The task's rule offers no default in that case.
- **"Tomorrow at 07:00" won't appear here.** The scheduler runs on a fixed delay, by default one minute after the previous run ended. Locally, Health therefore says "Next run: today at 14:33". The 07:00 wording shows only with a daily schedule.

## Questions for the owner

1. **Schedule:** should automatic cleanup run daily at a fixed time, as the canvas shows (a cron setting), instead of every minute? That would be a separate small server change.
2. **Default for a new installation:** is "first searchable system by name when none has records" acceptable, or should Records show no default until a delivery arrives?
3. **Tags and comments on this machine:** this machine's comments are the two "R7-comment-persistence-…" test comments. A fresh machine gets one "Demo comment: checked against the signed copy." Keep it that way?

## Suggested next step

Owner review of `feature/small-items-1`.

## Follow-up

**The owner's answers, put into code**
1. **Schedule:** automatic cleanup now runs once a day at a fixed time, by default 02:00 in the server's time zone.
   - Set it with `sdv.retention.daily-at` (`SDV_RETENTION_DAILY_AT`, `HH:mm`). The new `RetentionSchedule.java` holds it, and Health and Overview show the next run from it.
   - The server refuses to start if the time is in any other format, or if one of the old `SDV_RETENTION_INITIAL_DELAY_MS` / `SDV_RETENTION_SWEEP_DELAY_MS` settings is still set.
   - `SDV_RETENTION_CRON` is only for the check scripts. `Test-SDV-CleanInstall.ps1`, `Test-SDV-ControlledActions-B-Units17-18.ps1` and `Test-SDV-EnterpriseReadiness-C-Unit22.ps1` now use it. README section "Automatic cleanup time".
2. **No default for a new installation:** while no searchable system holds a record, Records and Home pick no system.
   - Both say "There’s nothing to search yet." instead. `sourceChoice.ts`, `Workspace.tsx`, `Home.tsx`.
   - A new tour check covers it (screenshots `records-nothing-delivered`, `home-nothing-delivered`).

**Tour failures: the cause.** The screen tour failed now and then. The cause was this machine freezing for about 10 s at a time under load, not the app.

**Change.** The owner decided to reduce load instead of raising the tour's time limits.
- `playwright.config.ts` keeps one worker for every run, including `ui:capture` and `ui:compare`. A comment now gives the reason. One worker was already set, and there is no CI configuration in this repository.
- The earlier draft raised the limits: the test went from 45 s to 120 s, and some waits had no limit. That was undone. The test limit is 45 s again, and the new "app loaded" and "signed in" waits use the same 15 s as the other steps.
- The tour still waits for the app's own code to run (`r7-html` on `<html>`) after each page load and reload.

**Results** (app started with `.\start-local.cmd`)
- `npm run typecheck`: passed.
- `npm run ui:capture`, run 1: 20 passed, 4 skipped, 4.5 min.
- `npm run ui:capture`, run 2, straight after: 20 passed, 4 skipped, 4.5 min.
  - The skips are by design: three tests run once per tour (desktop only), and the redaction test runs only when it is switched on.
- `npm run ui:compare`: 21 passed, 2.2 min.
- `.\Build-SDV-All.ps1`: BUILD SUCCESS. Backend tests 14 + 19 + 2 + 4 + 167, no failures; frontend build passed.
  - The first attempt failed only because the running backend held the jar open. It passed after `.\stop-local.cmd`.

`Start-SDV-Local.ps1` was left as it was and not committed.
