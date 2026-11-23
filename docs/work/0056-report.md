# Report 0056: Primary identifier on the Restrictions page

Date: 2026-11-23
Branch: `fix/restrictions-identifier` (from `release/vero-r7` at `c9b55e0`)
Outcome: STOPPED

## What was done

Nothing in code. The task rests on a setting that Vero does not have, so I stopped and wrote the question here instead of guessing (README step 3, CLAUDE.md "fail closed").

What the task assumes: the Business Admin configures one field as "the record's identifier", and Approvals and Legal holds already use that field.

What the code does:

1. **No "primary identifier" setting exists.** Per sending system, the Business Admin can mark any number of fields as *business identifier* (`metadata_field_definitions.business_identifier`). They can also set a display order (`metadata_field_display_order`) and up to two record title fields (`record_title_fields`; an identifier can't be one). Nothing names one identifier as the primary one. No column, table or admin screen does that.
2. **Approvals and Legal holds use the same rule as 0055.** Each one takes the first non-blank business-identifier value in display order, then by field key. That is the rule the task calls a guess. The same rule, as its own copy of the SQL, is in seven repositories:
   - `JdbcAccessRequestRepository` (Approvals)
   - `JdbcLegalHoldRepository` (Legal holds)
   - `JdbcQuarantineRepository` (Quarantine)
   - `JdbcOperationalRepository` (Problems, Audit record number)
   - `JdbcIntakeReportRepository` and `JdbcSensitivityReportRepository` (Reports)
   - `JdbcRestrictionsRepository` (Restrictions, 0055)
3. So the Restrictions page already matches the other admin pages. Following "use what Approvals and Legal holds use" would change nothing. A real configured field would need a schema change, and that needs your approval first (CLAUDE.md "Ask before").

## No other product's name or behaviour in code: yes

No code was changed.

## Commands run and results

- `git fetch origin`, `git checkout release/vero-r7`, `git pull --ff-only`: already up to date (`c9b55e0`, 0055 merged).
- `git checkout -b fix/restrictions-identifier`, then the task file committed.
- Read-only searches of `backend/` and `frontend/sdv-ui/src/` for how the primary identifier is chosen, and of the changelog for an identifier setting.
- Not run, because nothing changed: `npm test`, full build, schema checks, `ui:capture`. The app was not stopped or started.

## Tests: counts, and any failures with their output

None run (no code change).

## Files changed

- `docs/work/0056-task.md` (committed as given)
- `docs/work/0056-report.md` (this report)
- `docs/work/QUEUE-2.md`, `docs/work/QUEUE-2-report.md` (committed as the task asks, unchanged)

## Commits (hash and message), or "not committed"

- `146b866` docs(work): task 0056
- The next commit: docs(work): report 0056 (stopped) and queue 2 files

Committed locally only. Not pushed, no pull request, not merged: merging a STOPPED report into `release/vero-r7` would mark 0056 as done in the work routine.

## Not done, and why

The whole code part. Which field counts as the record's primary identifier is your decision, and the setting the task describes doesn't exist.

## Questions for the owner

1. Which do you want?
   - **A. Keep the rule and share it (no schema change).** "Primary identifier = the first business identifier in display order" becomes the agreed rule. The seven copies move into one shared helper that every admin page uses. Tests show that reordering the fields changes the identifier shown on every page in the same way.
   - **B. Add a real setting (schema change).** The Business Admin picks one primary identifier field per sending system, saved in a new table or column, set on the metadata settings screen. Every admin page uses it. This needs your approval of the schema change. It also needs a decision for systems where nothing is picked: fall back to rule A, or show "Record not named".
2. If B: should changing the primary field be refused while records of that system have a level, as changing which fields are identifiers is (SDV-SEN-007)? It changes only the label shown, not the record grouping, so I would allow it unless you say otherwise.

## Suggested next step

Pick A or B (and answer question 2 if B). The design chat then rewrites 0056, and I redo it on this branch.
