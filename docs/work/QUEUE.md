**FINISHED (2026-11-18): every task in the queue is done; 0013 was the last and is approved. See `QUEUE-report.md`; no questions are open.**

# Task queue

Written: 2026-11-18, by the design chat. The owner approves every task in this queue, including the backend and schema changes each one names, by giving this queue.

## Order

| # | Task | Branch (taken from the one above) |
|---|---|---|
| 0 | Finish 0006: the follow-up below | `feature/ui-viewer` |
| 1 | 0007 Page-view audit | `feature/page-view-audit` |
| 2 | 0008 Admin shared pieces, People & roles, Roles & permissions | `feature/admin-1` |
| 3 | 0009 Keeping documents, Search fields, Record title | `feature/admin-2` |
| 4 | 0010 Tenant IT: Integrations and Storage | `feature/admin-3` |
| 5 | 0011 Problems and Audit in plain words | `feature/admin-4` |
| 6 | 0012 Overview, Storage, Health, About | `feature/admin-5` |
| 7 | 0013 Separate schema step | `feature/install-1` |
| 8 | ~~0014 "Not set up yet"~~ **Replaced, do not do** (see below) | — |
| 9 | ~~0015 Setup command~~ **Replaced, do not do** | — |
| 10 | ~~0016 Setup checklist, review banner, progress~~ **Replaced, do not do** | — |

## Change on 2026-11-18 (owner)

The owner agreed a shared install approach for LexaVault and SDV: a signed licence file, a setup command that reads the licence and a sign-in config file, and **no install screens**. It is built later, for both products together, after the UI series. So:

- **The queue ends after task 0013.** Tasks 0014, 0015 and 0016 are replaced; don't start them.
- Task 0012 gained Part F (Sign-in tab read-only), because sign-in is set by script, not screens.
- After 0013, write `QUEUE-report.md` and stop.

## 0006 follow-up (do first)

1. Make the end-to-end redaction test opt-in, like the comment test, so normal `ui:capture` runs never change demo record 100000000003.
2. Keep the 14-page demo document and the "e2e-demo" tag. Confirm they load only with the local profile.
3. Page-view auditing is task 0007; leave it for now.
4. Add this to `0006-report.md` under "Follow-up" and commit on `feature/ui-viewer`.

## How to work through it

1. Do the tasks in order. Each task has its own report, `NNNN-report.md`, and its own commits on its own branch. Never push or merge.
2. **Go straight on to the next task** when a report has no question for the owner and every check passed.
3. **Stop and wait for the owner** when:
   - a report has a question for the owner, or a choice you'd otherwise have to guess;
   - a check fails and you can't fix it within the task's rules;
   - a task needs a backend, schema, dependency or permission change it doesn't name;
   - a task no longer fits what was built before (an earlier task changed something it relies on). Say what doesn't fit; don't adapt the task yourself.
4. When you stop, write at the top of `QUEUE.md` which task you stopped at and why, in one line.
5. `CLAUDE.md` rules apply to every task (secrets, containers, Vault, no `docker compose down`, leave `Start-SDV-Local.ps1` alone).
6. After the last task, write a one-page `QUEUE-report.md`: each task's outcome and commit, the open questions, and what the owner should test first.
