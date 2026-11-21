# Report 0035: Make the flaky screen-tour steps steady

Date: 2026-11-21
Branch: `fix/steady-tour` (from `docs/failure-catalog`)
Outcome: DONE. Test code, demo-seed scripts and the README only; no product change. One existing seed bug was found and fixed (below, 4).

## What was done

**1. Flaky step: Search fields, filter order (`expect(searchable[0]).toBe(moved)` got `undefined`)**
- Cause: the Filters panel only appears once the search fields have loaded, and the test read the labels as soon as it clicked Filters. When the fields were still loading, the list was empty. The line before then passed on an empty list, and `searchable[0]` was `undefined`.
- Fix: the test waits for the Filters panel, then for its first label to be the moved field, then reads the labels.
- `waitForBusinessSearchReady` now also waits until the results area has no loading skeleton, which means the sending systems have loaded.

**2. Flaky step: "Quick search across all fields" timed out waiting for 300000000004 ("gomez")**
- Cause found: `searchFor` waited only for the loading skeleton to be gone. That check can pass before the search has even started, so the 15 s wait for rows was measured from the wrong moment.
- Fix: `searchFor` now waits for the search's own answer (`POST /api/v1/business/search` with that text), checks it succeeded, then waits for the list to be drawn. No time limit was raised.
- I could not reproduce the original failure, so I can't prove this was its only cause. If it fails again, the message will now say whether the server answered.

**3. The tour leaves demo data as it found it**
- **Who lowered the third document:** not a tour step.
  - The audit shows income-statement.pdf (300000000005, the third sensitivity demo document) was lowered from Confidential to Standard at 15:33:37 UTC. It was a Business admin (`local-business-admin`) approving Daniel's lowering request on the Approvals screen.
  - In the same minute, the same account declined the 11 waiting requests on the settlement agreement, Dana's waiting lowering among them, and released the income statement's hold.
  - The events from 15:23 to 15:33 come at human pace (a few a minute; also a permanent redaction of 100000000001 Terms and Conditions.pdf at 15:28). The tour ran at 15:40–15:45, at about 100 events a minute.
  - So this was a hand test pass, and the tour's 0029 step then found only Dana's declined lowering.
  - No tour step changes a level.
- **Every tour step that changes data, and what it does now:**

  | Step | Changes | Now |
  |---|---|---|
  | Business admin, Roles & permissions | Print for Business user on, then off | Unchanged steps; the rules are also put back through the API in `finally` if a step stops halfway |
  | Search fields | Last Name match, field order, Record title | Order and title were already restored in `finally`; Last Name's match is now restored there too |
  | Integrations | Pauses and restarts LexaVault | Already restored in `finally` |
  | Viewer, business user | Adds the `e2e-demo` tag if missing | The seed's own tag; unchanged |
  | Audit | Saves LexaVault's Record title unchanged; a steward download | Audit only; unchanged |
  | Approvals, requester notice (0030) | Dana's print request, declined | **Moved to the tour's own record** |
  | Decided recently (0031) | Dana's download request, declined; an opening | **Moved to the tour's own record** |
  | Sensitivity report (0033) | A refused opening (audit only) | **Moved to the tour's own record** |
  | Daily intake (0032) | One refused delivery (audit only, nothing stored) | Unchanged |
  | Keeping rule, new sending system, new volume, redaction, bulk approval | Permanent rows | Still opt-in, off by default |

- **The tour's own record:**
  - 399000000001 "Tour Check (test record)" on the Loan Origination System, with one Highly Confidential document, `tour-check.pdf`.
  - The tour delivers it through the producer API the first time it needs it, then reuses it. A new record per run would add a record to the demo lists every run.
  - Its request history grows by 3 requests per run; no demo document's history does.
- **The 0029 step reads the request list correctly:** a document's history can hold earlier Print or Lower requests (used, declined, expired). The step now looks for the approved print and the waiting lowering among them, instead of requiring exactly one of each. Before, the list held one declined and one waiting lowering, and a check that expects exactly one item would fail even with the right data. The step's message names the reset command.

**4. The seed is safe to run in any state** (`Seed-SDV-Canvas-Demo.ps1`)
- Each request step first checks the level it needs. If the data has moved on (for example the income statement is already Standard), the step is skipped with a `[SKIP]` line saying why, and the run ends with "… step(s) skipped … Run .\Seed-SDV-Canvas-Demo.ps1 -Reset". It no longer stops with SDV-SEN-014.
- Approved print: if one is left waiting by a stopped run, it is approved instead of being ignored.
- LexaVault is set receiving again if it is paused, as the seed already did for the loan system. A paused LexaVault would refuse the record seed's deliveries.
- The task 0030 deliveries are made earlier in the script, before any request, so the reset sees every demo document.
- **Existing bug, fixed:** since task 0031, Vero stores the same file for the same contract only once (an exact duplicate). `Seed-SDV-Demo-Records.ps1` sent the same sample PDF for every document of a record, so on an empty database 100000000001 got 1 of its 3 documents. Without Terms and Conditions.pdf the canvas seed stopped. It now sends each document as its own copy (the sample plus a PDF comment line), as the canvas seed already did. The `sdv` database was seeded before task 0031, so it was not affected.

**5. The reset command**

```powershell
.\Seed-SDV-Canvas-Demo.ps1 -Reset
```

- **It refuses unless:**
  - Vero at `-Base` reports the `local` profile;
  - the database is a local `sdv*` one (as before) and holds this seed's canvas-demo deliveries ("not a demo database" otherwise);
  - Vero at `-Base` uses that database (its Loan Origination System ID matches).
- **It puts back,** through the API so each change is audited:
  - Roles & permissions: Business user without Download, Print, Tags, Comments and Redact; Document steward with all five.
  - LexaVault receiving, and the seed's field settings.
  - Waiting requests: the seed's are kept (one of each); every other waiting request is declined by Priya Natarajan.
  - Levels: contract 300000000002 Highly Confidential, every other contract Standard; the seed's 7 documents at their levels, the tour's document Highly Confidential, every other document Standard.
    - A raise is made by `local-demo-reset`.
    - A lowering is asked for by `local-demo-reset` and approved by Priya Natarajan.
  - Legal holds: the seed's three; any other hold is released.
  - Then the normal seed fills in what is missing.
- **It can't put back:** a permanent redaction, and tags or comments added by hand (there is no API to remove them). Requests, decisions and audit events stay in the history.
- **Documented in:** the script's help and `README.md` ("Demo data …").

## Commands run and results

| Command | Result |
|---|---|
| `.\Test-SDV-DemoSeed.ps1` (scratch database `sdv_seed_test`, Vero on 8291; `sdv` not touched) | First two runs failed on old problems (see "Tests"). **Run 3: all checks PASS.** The reset was refused on the empty database. Seed run 1 passed its checks, including the new 3-documents check. Run 2 changed nothing (29 tables, row by row). The data was then moved on as by hand. Seed run 3 did not fail, skipped Daniel's lowering and said why. Seed run 4 changed nothing. Reset run 1 restored levels, waiting requests, the stray request (declined), holds, Last Name and Print. Reset run 2 changed nothing. Scratch database dropped. |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` on `sdv` | Passed. Put back income-statement.pdf, Standard → Confidential. Made 10 requests again (the settlement-agreement ones declined by hand), Daniel's waiting lowering, and the income statement's hold. Nothing skipped. |
| 5 × (reset on `sdv`, then `npm run ui:capture`) | All 5 passed; see the table below |
| `npm run typecheck` | Passed |
| `npx tsc --noEmit --strict … e2e/sdv-screen-tour.spec.ts` | No errors except "cannot find node types" (`@types/node` is not a dependency; not added) |

| Run | Reset | Tour | Time |
|---|---|---|---|
| 1 | changes above | 25 passed, 11 skipped | 5.7 min |
| 2 | nothing to put back | 25 passed, 11 skipped | 5.7 min |
| 3 | nothing to put back | 25 passed, 11 skipped | 6.0 min |
| 4 | nothing to put back | 25 passed, 11 skipped | 6.6 min |
| 5 | nothing to put back | 25 passed, 11 skipped | 6.9 min |

The resets before runs 2–5 found nothing to change: the tour now leaves the demo data as it found it. The 11 skips are the usual ones: the opt-in steps, plus the desktop-only tests on the iPad project.

Not run: `.\Build-SDV-All.ps1`. No backend code changed, and the running app holds the jar, which was built after the last backend commit.

## Tests: counts, and any failures with their output

- **Screen tour:** 5 runs, 25 passed, 11 skipped, 0 failed each.
- **Seed check, first two attempts:** both failures came from old problems, now fixed:
  1. `Demo document r3-demo-doc-100000000001-3 (Terms and Conditions.pdf) was not found.` This is the duplicate-file bug in 4.
  2. `FAIL: 20 demo records …`. The task 0023 check listed contract numbers with repeats, because tasks 0029 and 0030 added second deliveries for some contracts. **I changed this check:** it now counts each contract once (`distinct`) and prints what it found.
- **New seed checks:** the reset refusal; the 3 documents of 100000000001; data moved on (5 checks); a second run while moved on; the reset (8 checks); a second reset.

## Files changed

- `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
- `Seed-SDV-Canvas-Demo.ps1`
- `Seed-SDV-Demo-Records.ps1`
- `Test-SDV-DemoSeed.ps1`
- `README.md`
- `docs/work/0035-task.md` (your edits, committed as found)
- `docs/work/0035-report.md`
- `Start-SDV-Local.ps1`: left as it was, not committed

## Commits (hash and message), or "not committed"

See `git log` on `fix/steady-tour`: "test: steady screen tour, seed safe in any state, demo reset command (task 0035)". Not pushed or merged.

## Not done, and why

- `-WithTour` on the scratch database was not run; the tour was run 5 times against `sdv` instead, as the task asks.
- The reset's local-mode refusal was not tested against a non-local Vero (that would mean starting one); its other two refusals were tested or exercised.

## Questions for the owner

1. **The tour's own record is reused, not new per run.** Is 399000000001 "Tour Check (test record)" in the loan system's lists acceptable? The other way, a new record each run, would add a record every run.
2. **The reset declines every waiting request the seed did not make, and releases every other legal hold.** That is right for demo data. Should anything made by hand be kept instead?
3. **Permanent redaction of 100000000001 Terms and Conditions.pdf** (15:28 today, by hand): it can't be undone. The tour passes with it. Should the seed's read-only demo document move to another document?

## Suggested next step

Your test pass. The demo data has just been reset and the app is running at http://localhost:5183. After testing by hand, run `.\Seed-SDV-Canvas-Demo.ps1 -Reset` before the next tour.
