# Queue 2 report, 2026-11-23

Run by the coding session, with the owner away, by `QUEUE-2.md`'s rules: in order; each task on its own branch from the newest `release/vero-r7`; merged through a PR only when `ci-passed` was green.

## The tasks

| # | Task | Outcome | PR | Merge commit | Tests (final local runs) |
|---|---|---|---|---|---|
| 1 | 0052 Quarantine at a million documents | Done, except the screen tour | #26 | `c764f23` | Build 657 / 0 failed; npm 55; schema check, clean install; 250,000 in CI and 1,000,000 locally |
| 2 | 0053 Report: storage and retention | Done, except the screen tour | #27 | `c686d27` | Build 662 / 0 failed; npm 58; schema check, clean install |
| 3 | 0054 Report: activity | Done, except the screen tour | #28 | `fd15fcc` | Build 672 / 0 failed; npm 61; schema check, clean install. CI failed once on time; fixed in a second commit |
| 4 | 0055 Restrictions page | Done, except the screen tour | #29 | `c9b55e0` | Build 679 / 0 failed; npm 63; schema check, clean install |

Each task's report (`docs/work/0052-report.md` to `0055-report.md`) has the commands, counts, times and its own decisions.

## What to look at first

1. **No screen tour was run in this queue.** This session's automatic permission check refused the local schema step (`.\Update-SDV-Schema.ps1 update` on the local `sdv` database), as it did in tasks 0048 and 0050. I didn't work around it. Without the new changesets, Vero answers SDV-SYS-001 on the local database, so the tour can't start. Every task's new tour step is written and compiles.
   Please:
   - run `.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv -Username sdv` (it applies 039, 040, 041 and 042);
   - start Vero;
   - run `cd frontend/sdv-ui; npm run ui:capture`.
   New screenshots: `reports-storage`, `reports-activity`, `governance-restrictions`.
2. **The agreed times were set by me** (1,000 ms for a screen's API; 3,000 ms for the quarantine count before confirming; 100 ms for a one-document check). Nobody could confirm them. Please confirm or change them.
3. **0054 failed CI once on time** (1,494 ms for a busy month on CI's runner). I fixed the cause, not the limit; it now takes 324 ms here and passed CI. The margin on CI is not measured directly; watch the next runs.

## Decisions taken while the owner was away

All are in the tasks' reports, with the reason for each.

**Across the queue**
- I merged without the screen tour. The queue allows a merge once `ci-passed` is green, and CI runs the same backend, PostgreSQL and frontend tests. The tour is left to you (above).
- New exports (storage, activity) send a reference and a SHA-256 fingerprint as headers; the screen shows both.

**0052**
- The incident's counts are by each document's level when quarantined; a later level change doesn't move them. Every list and action still checks the level now.
- Rows written before changeset 039 count as hidden until a background job fills their level.
- The Approvals row shows the whole incident's first and last received dates.
- The exposure export streams. Its fingerprint and rows are in the audit, and the screen works out the same fingerprint, instead of headers. Two audit events: started and finished.
- The approval's audit says `documentCount` instead of `releasedCount`.
- I corrected SDV-QUA-004's stale text about the old 1,000 limit.

**0053**
- The counts are worked out in the background every 15 minutes and kept. The first load for an entity counts once. The screen says when they were counted.
- A retention rule is the retention length a document was given, named by the most recent policy of that length.
- Growth counts what arrived each month, deleted since or not.
- No new `RPT` code.

**0054**
- Opens, prints and downloads now record roles; older events say "Role not recorded".
- A legal hold never refuses an open, so refusals have two columns (level, quarantine).
- "Highly Confidential approvals" are the print and download requests and their one-time grants.
- Each closed day is kept 10 minutes after it ends.
- At most 92 days per report.
- The busiest list ranks opens only.

**0055**
- A new capability `VIEW_RESTRICTIONS` and new codes SDV-RST-001 and 002, instead of borrowing another page's.
- The levels come from the kept storage counts (up to 15 minutes old).
- A record's primary identifier is its first business identifier in display order.
- Quarantine has no link, because it is the Stewards' page.

## Left as they were

- `Start-SDV-Local.ps1` keeps its uncommitted local changes (CLAUDE.md).
- No container, volume or Vault was touched. Every PostgreSQL test ran in the scratch database `sdv_schema_test` (created and dropped by the schema check); the clean install used `sdv_install_test`.
- `QUEUE-2.md` and this report are left uncommitted in `docs/work/` for you to read: every task is merged, and committing them would need one more branch and PR that the queue doesn't ask for.
