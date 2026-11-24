# Report 0086 (items 4 to 6): Faster schema check and screen tour

Date: 2026-11-24
Branch: `chore/faster-schema-check` (from `release/vero-r7` at `a958657`). Items 1 to 3 are the side queue's.
Outcome: DONE. No check left out, no time limit changed.

## Times

| | Before | After |
|---|---|---|
| `Test-SDV-SchemaUpgrade.ps1` | 498 to 529 s (three runs today); 655 s with the machine busy | **316 s** |
| of that, step 4f (PostgreSQL tests) | 480 to 508 s; 634 s busy | 291 s |
| of that, replaying the earlier changesets | 4.0 to 4.4 s | 5.4 s |
| `ui:capture`, full | 8.1 to 10.0 min | the same |
| `ui:smoke` (new, 7 steps, desktop) | | **2.3 min** |
| CI, last ten successful runs | 214 to 620 s, most about 455 s | side queue |

## What was done

4. **Schema check.** A saved dump would save at most the 4 s of replaying: not built. The time is in step 4f, where the
   PostgreSQL tests ran one after another (Activity 227 s, Quarantine at scale 200 s, Restrictions 60 s, blocking 55 s in the busy run). They
   now run in **four processes, each on its own copy** of the scratch database, made after the schema step was checked. The
   same 18 classes run, the script still looks for each one's result line, and the scale and the limits of the screens are
   unchanged (measured up to 437 ms beside the others). The copies go with the scratch database, also after a failure.
5. **Tour.** `npm run ui:steps -- "0049|0071"` runs the steps whose name matches; `npm run ui:smoke` runs one step per role, the
   no-access page and the page without a session. Nothing in the tour itself changed.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS before the change to the test guards; CI builds the final state |
| `.\Test-SDV-SchemaUpgrade.ps1` | PASS in 655 s (before), PASS in 316 s (after); both passed the same classes |
| `npm run ui:smoke` (scratch database) | 7 passed |
| `npm run ui:steps -- "0049\|0071" --list` | the two steps (desktop and iPad) |

## Decisions taken

- Tried and taken out: `synchronous_commit = off` on the scratch database. It didn't help (the work is processor time).
- The tests' guard accepts `sdv_schema_test` and its copies `sdv_schema_test_1` to `_9`; never another database.
- Four processes: the longest class (270 s beside the others) sets the end; more would not shorten it. Splitting that test
  would, and is left.
- CI still runs them one after another (its workflow is the side queue's).

## Open points and questions for the design chat

CI's backend job could use the same profile (`-Ppostgres-copies`), with four databases made in its PostgreSQL service.
