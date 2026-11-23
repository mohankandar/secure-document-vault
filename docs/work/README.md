# Work folder: tasks and reports

This folder is how the design chat and the coding session work together on SDV (Vero). The owner (Kr) starts each step and approves each result. It works the same way as `docs/work/` in LexaVault.

## Files

| File | Written by | Holds |
|---|---|---|
| `NNNN-task.md` | The design chat (Claude in the Lexavault project) | One task: what to do, what to check, what to report |
| `NNNN-report.md` | The coding session (Claude Code on this machine) | What was done, what was run, what passed or failed, the diff summary |

`NNNN` is a four-digit number. A task and its report share the same number. SDV numbering is separate from LexaVault's.

## Routine for the coding session

When the owner says "do the next SDV task":

1. Find the lowest-numbered `NNNN-task.md` that has no matching `NNNN-report.md`. If there is none, say so and stop.
2. Read `CLAUDE.md` at the repository root and follow its rules. They always apply.
3. Do the task exactly as written. If the task is unclear, or would break a rule in `CLAUDE.md`, stop and write the question in the report instead of guessing.
4. Write `NNNN-report.md` using the layout below.
5. Tell the owner, in a few lines, that the report is written and what the outcome was.

Do not edit task files. Do not start a later task while an earlier one has no report.

## Local schema step (standing approval)

Owner decision, task 0051. When a task adds Liquibase changesets, the session may run the schema step against the **local `sdv` database**, before it starts the app for the screen tour:

```
.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv -Username sdv
```

- **Only the local `sdv` database** (`localhost:5433/sdv`, container `sdv-postgres`). Never any other database, host or user. The scratch databases of the schema checks (`sdv_schema_test`, `sdv_install_test`) are made and dropped by their own scripts.
- Only `update` (and `status` or `sql`, which change nothing). Never drop, reset, recreate or roll back the database. The `CLAUDE.md` rules on containers and volumes still hold.
- The password goes in `SDV_SCHEMA_DB_PASSWORD`, read from where the local password is kept (the local-only `application-local.yml`). Never print it, and never put it in a report or a log.
- Say in the report that the step was run, and which changesets it applied.

## Report layout

```
# Report NNNN: <task title>

Date:
Branch:
Outcome: DONE / STOPPED / PARTLY DONE

## What was done
## No other product's name or behaviour in code: yes / no (and where)
## Commands run and results
## Tests: counts, and any failures with their output
## Files changed
## Commits (hash and message), or "not committed"
## Not done, and why
## Questions for the owner
## Suggested next step
```

Keep reports short and factual. Never put passwords, tokens or other secrets in a report. Do not paste whole build logs.

## Committing

Task and report files are committed along with the work they describe.
