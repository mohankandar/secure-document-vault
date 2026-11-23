# Queue 2, 2026-11-23

Written by the design chat. **The owner approves every task in this queue, and everything each task file approves, by giving this queue.** The owner is away and can't answer.

| # | Task |
|---|---|
| 1 | **0052** quarantine at a million documents |
| 2 | **0053** report: storage and retention |
| 3 | **0054** report: activity |
| 4 | **0055** Restrictions page |

## Rules
- In order. Each on its own branch from the newest `release/vero-r7`, merged through a PR **only when `ci-passed` is green**, before the next starts.
- **Never wait for an answer.** If something isn't covered, take the safer, smaller choice, list it under "Decisions taken while the owner was away" in that task's report, and go on. Stop a task only if continuing could lose data, break a CLAUDE.md rule, or needs something only the owner can do; then leave its PR open, skip the tasks after it, and write the queue report.
- The local schema step is approved (`docs/work/README.md`). The seed reset: run it if allowed; if refused, run the tour on the data as it is; a failure caused only by demo data is reported and doesn't block the merge.
- All CLAUDE.md rules hold (secrets, Vault, containers, time limits, no printed tokens).
- Don't end your turn to report progress between tasks; keep going until the queue is done or a real blocker hits.
- At the end, write `docs/work/QUEUE-2-report.md`: one line per task (done / stopped, PR, merge commit, tests), every decision taken while the owner was away, and what to look at first.
