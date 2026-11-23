# Overnight queue, 2026-11-22

Written by the design chat for the owner, who is asleep. **The owner approves every task in this queue, and everything each task file approves, by giving this queue.** Run the tasks in order, each on its own branch from the newest `release/vero-r7`, each merged through a PR only when `ci-passed` is green, before the next starts.

| # | Task | Notes |
|---|---|---|
| 1 | **0041 Part B** (if still running or not finished) | As already prompted. Tag `sdv-ingestion-v1.1.0` on its merge commit |
| 2 | **0048** quarantine, server side | after 0041 is merged |
| 3 | **0049** quarantine, screens | after 0048 is merged |
| 4 | **0050** sealed audit report | after 0049 is merged |

## Rules for tonight
- **Nobody can answer questions until morning.** When a task needs a decision the task file doesn't cover, take the **safer, smaller choice**, write it under "Decisions taken overnight" in the report, and go on. Stop a task only if continuing could lose data, break a rule in CLAUDE.md, or need something only the owner can do.
- **The seed reset (`Seed-SDV-Canvas-Demo.ps1 -Reset`) is not run tonight** (the permission check refuses it). Run the screen tour on the data as it is. A tour failure caused only by demo data is written in the report and does not block the merge; any other tour failure does.
- **If a task stops** (a real blocker), write its report, leave its PR open, and **don't start the tasks after it** that depend on it. Write `QUEUE-OVERNIGHT-report.md` and stop.
- All CLAUDE.md rules hold: never touch `C:\dev\sdv\secrets`, never reset or reseal the Vault, never remove or recreate containers, never `docker compose down`, never raise time limits to pass a test, never print tokens or passwords.
- Every report has the line "No other product's name or behaviour in code".
- At the end, write `docs/work/QUEUE-OVERNIGHT-report.md`: one line per task (done / stopped, PR, merge commit, tests), every "Decision taken overnight" in one list, and what the owner should look at first.
