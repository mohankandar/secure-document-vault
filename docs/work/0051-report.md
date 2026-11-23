# Report 0051: Quarantine without a limit, wording, schema step, old scripts

Date: 2026-11-23
Branch: `fix/quarantine-follow-ups`, from `release/vero-r7` at `7607394` (after 0050 was merged, PR #24)
Outcome: **DONE**. Built, tested, both schema checks passed, and the screen tour passed. Merge through the PR when `ci-passed` is green.

## What was done

### 1. No limit on quarantine (changeset 038)

- **The 1000 limit is gone.** The count endpoint now returns the count only. The screen no longer refuses large counts.
- **Everything matching is blocked at once.** When a Restricted Steward confirms, the incident stores its filter and a cut-off: the latest received time among the documents counted, taken in the same transaction as the count check. From then on, every document that matches the filter, was received up to the cut-off, and belongs to an open incident is quarantined, whether or not its own row exists yet.
- **Every path uses one rule.** It is one SQL condition (`JdbcQuarantineRepository.filterBlocked`) used in three places:
  - the one-document check, which the access guard uses on every way of reaching a document (open, pages, previews, text search, tags, comments, activity, print, download, correct, level history; the SDV-QUA-001 catalog entry);
  - the many-documents check, used by record views and search rows;
  - the retention cleanup.
- **Rows are written in the background.** A new scheduler (`QuarantinePreparationScheduler`, every 2 s, at most 20 batches of 1,000 per run) writes the per-document rows, oldest received first.
  - Each batch is its own transaction and moves a cursor kept on the incident, so after a restart the job continues where it stopped.
  - A batch run again writes nothing twice: the unique index on rows in force, plus the release rule below.
  - The first batch is written while the request is still open, so a small incident is ready at once.
- **Progress is shown.** The incident shows "Being prepared" and, for example, "4,000 of 20,000 done". The incident page refreshes every 3 s while preparing. "In quarantine" counts every document the filter caught.
  - Review and single release work on the rows already written.
  - A whole-incident release can be asked for only once the incident is prepared (SDV-QUA-010; the button is hidden until then).
- **The count is shown before confirming**, as before. A count that changed is still refused (SDV-QUA-009).
- **Releases.**
  - A whole release releases every row in force in one statement, audits each document as before, and closes the incident, which ends the filter's blocking.
  - A single release frees that one document even though the filter still matches it. This also works for a document whose row isn't written yet: its row is written and released together.
- **Large incidents in the views.** The review list and the exposure view list at most 500 documents and say "The first 500 of N are listed". Their counts cover the whole incident. Export CSV has every document. The Approvals counts are read a page at a time.

### 2. Wording for people who can't review

- Under "Temporarily unavailable" the record panel now shows nothing else; the line "It comes back once it has been reviewed" is removed.
- The SDV-QUA-001 sentence, used by refusals, the viewer and toasts, is now just "This document is temporarily unavailable." It no longer says "It comes back when the review is finished; if you need it urgently, ask a Document steward."
- The catalog's "What the user should do" line in `docs/api/error-codes.md` is changed to match.

### 3. Local schema step (standing approval)

`docs/work/README.md` has a new section, "Local schema step (standing approval)". It allows `.\Update-SDV-Schema.ps1 update`, `status` and `sql` against the local `sdv` database only, before starting the app for the tour. The password is read from the local-only config and never printed. Used in this task: it applied 038 to the local `sdv` database.

### 4. Old scripts retired

| Deleted file | Why |
|---|---|
| `tools/Unit21JwtTestIssuer.java` | Issued test tokens with the claims removed in 0041 (tenant ID claim, old role claim) |
| `Test-SDV-EnterpriseReadiness-A-Units19-20.ps1` | V1-era enterprise-readiness check named in report 0041; checks the old identity set-up |
| `Test-SDV-EnterpriseReadiness-B-Unit21.ps1` | Built tokens with the removed claims through `Unit21JwtTestIssuer` |
| `Test-SDV-EnterpriseReadiness-C-Unit22.ps1` | V1-era enterprise-readiness check named in report 0041 |
| `Test-SDV-EnterpriseReadiness-D-Unit23.ps1` | V1-era enterprise-readiness check named in report 0041; checks the old identity headers |
| `Test-SDV-EnterpriseReadiness-E-Unit24.ps1` | V1-era enterprise-readiness check named in report 0041 |

- **Product-name guard exceptions:** none to remove. `exceptions.txt` was already empty (0041 removed every entry).
- **References:** nothing in the build, CI or the install guides referred to these files. Two V1 release-readiness runners listed the five scripts, and those five lines are removed:
  - `Complete-SDV-V1-Release-Readiness-Unit25-FIX3.ps1`
  - `Test-SDV-V1-Release-Readiness-Unit25.ps1`
  A comment in each says why. Both still parse.

## No other product's name or behaviour in code: yes

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken (the safer, smaller choice)

1. **The cut-off** is the latest received time among the documents counted.
   - A document received after it is not caught, even inside the filter's dates.
   - A document whose intake was still finishing at the confirmation, with an earlier received time, is blocked (fail closed). If the job had already passed its place it gets no row, so it stays unavailable until a single release or the whole release. This is rare: only documents arriving during the confirmation.
2. **Only someone who can review every level quarantines by filter.** A filter can catch any level, now or later, so the check is the person's clearance rather than each document's level. In practice that is the Restricted Steward, who already had this capability alone.
3. **Documents already quarantined in another incident** are not counted, as before, and get no row in the filter incident. If they are released there after the confirmation, they are free; they are not quarantined again by the filter.
4. **A release after the confirmation frees the document from the filter**, whichever incident the release was in.
5. **Rows the job writes** carry the confirming Steward as "quarantined by". Each one is audited as `DOCUMENT_QUARANTINED` by that Steward, with `byFilter: true` and the correlation `quarantine-prepare-<incident>`.
6. **An incident being prepared stays open** even when every row written so far has been released. When the job finishes with nothing in force, the incident closes (audited `QUARANTINE_INCIDENT_CLOSED` by the system).
7. **The whole release stays one transaction**, as before, now as one statement plus one audit event per document.
8. **Lock order:** freeing a document with no row yet locks the incident before the document, the same order the job uses, so the two cannot deadlock.
9. **Queue files:** item 3 says "and to `QUEUE-*.md` templates if any". There are no templates; `QUEUE.md` and `QUEUE-OVERNIGHT.md` are past queues and are unchanged.
10. **The two V1 runners were edited, not deleted.** Both are referenced by the V1 packaging scripts (`New-SDV-V1-GAPackage.ps1`, `New-SDV-V1-ReleasePackage.ps1`) and a runbook. Deleting them would go past the files the task names.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final, after every change) | PASS: backend 653 tests, 0 failures, 0 errors, 16 skipped (the PostgreSQL-only tests; 0050: 647/15); frontend build OK |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 54 tests (50 before, 4 new) |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 038`) | PASS in 108 s. New step 4p passed. Step 4f passed every PostgreSQL test, `QuarantineFilterBlockingPostgresTest` (20,000 documents) included |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 164 s |
| Schema step on the local `sdv` database (standing approval, item 3): `status`, then `update` | 038 applied ("Done: 43 changesets applied"). An earlier inline attempt stopped before applying anything (Windows PowerShell treats Java's log lines as errors); run as a child `powershell.exe` it worked |
| `.\Start-SDV-Local.ps1` | Started (containers untouched) |
| `cd frontend/sdv-ui; npm run ui:capture` (first run) | FAIL: 26 passed, 1 failed, 13 skipped. The 0049 quarantine step couldn't find "Released incidents · N": I had damaged the encoding of `Quarantine.tsx` (see below) |
| `cd frontend/sdv-ui; npm run ui:capture` (after the repair) | PASS: 27 passed, 0 failed, 13 skipped (0050: 27/0/13) |
| `.\Stop-SDV-Local.ps1` | Stopped (containers untouched) |

**Two problems found and fixed before the commit:**
- **A real bug the tests caught.** A Java text block drops trailing spaces, so the retention query read `select 1 whereexists`. `RetentionLegalHoldTest` caught it in the first build.
- **An encoding mistake of mine.** I changed one word in `Quarantine.tsx` with Windows PowerShell `Get-Content`/`Set-Content`. That read the UTF-8 file as Windows-1252: every `·`, `—`, `…` and `”` was garbled and a byte-order mark was added. The tour caught it. I reversed the double encoding and put back the three `”` characters that had been lost. The committed diff holds only the intended changes, with no byte-order mark, and no other file was touched that way.

## Tests: counts, and any failures with their output

- **New in `QuarantineServiceTest` (5):**
  - everything matching is quarantined at once and the rows follow in batches (1,503 documents);
  - a document received after the cut-off is not caught;
  - the job continues after a restart, and a batch run twice writes nothing twice;
  - a single release frees one document (with or without its row) and a whole release ends the filter;
  - releasing every written row doesn't end an incident still being prepared.
- **New `QuarantineFilterBlockingPostgresTest` (PostgreSQL, 20,000 documents; in CI and in schema check step 4f).** It checks:
  - blocking is immediate before the rows exist, through the many-documents lookup, the one-document check and the guard for open, print and download;
  - the retention cleanup skips 100 blocked documents past their date;
  - a document after the cut-off is not caught;
  - three batches, then a restart, then to the end: 20,000 rows, no duplicates, 20,000 audit events, and the first batch run again writes nothing;
  - the exposure report is complete (20,000 counted, 500 listed, the CSV has 20,000 rows, no file names);
  - single release, then the whole release: nothing left blocked.
- **New in `quarantineWords.test.ts` (4):** the SDV-QUA-001 words say nothing else; the progress words; the count while preparing; the "first 500 of N" note.
- **Tests changed (said here, as the rules ask):**
  - `QuarantineServiceTest`: the assertion that a count above 1,000 is refused is now an assertion that a count that doesn't match is refused (no limit).
  - `QuarantineControllerTest`: the count has no `limit` field.
  - `RetentionLegalHoldTest`: the retention query's expected SQL text now includes the filter-blocking clause.
- **Tour changed:** the 0049 step also checks that the record panel says nothing after "Temporarily unavailable". There is no tour step for "being prepared": that needs more than 1,000 matching documents, and the demo data has far fewer. The words tests and the PostgreSQL test cover it.
- **Final runs:** no failures.

## Files changed

- **Backend:**
  - New: `038-quarantine-filter-blocking.sql` and `.yaml`, `QuarantinePreparationScheduler`.
  - Changed: `db.changelog-master.yaml`, `QuarantineRepository` (port), `QuarantineService`, `JdbcQuarantineRepository`, `JdbcRetentionRepository`.
- **Backend tests:**
  - New: `QuarantineFilterBlockingPostgresTest`.
  - Changed: `InMemoryQuarantineRepository`, `QuarantineServiceTest`, `QuarantineControllerTest`, `RetentionLegalHoldTest`.
- **Frontend:** `Quarantine.tsx`, `Workspace.tsx`, `liveApi.ts`, `problemWords.ts`, `quarantineWords.ts` and its test, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and CI:**
  - `Test-SDV-SchemaUpgrade.ps1`: default `-From 038`, step 4p, the new PostgreSQL test.
  - `.github/workflows/ci.yml`: the new PostgreSQL test must run.
  - The two V1 runners.
- **Docs:** `docs/api/error-codes.md` (SDV-QUA-001), `docs/work/README.md` (item 3), this report and the task.
- **Deleted:** the six files in item 4.

## Commits (hash and message), or "not committed"

See the PR: one commit, "feat(quarantine): no limit on a filter quarantine, wording, local schema step, old scripts retired (task 0051)".

## Not done, and why

- **Very large incidents (a million documents) are correct but not fast everywhere.** Each of these reads the whole incident a page at a time:
  - the incident view's count of hidden levels;
  - the Approvals counts;
  - the exposure export, which is built in memory;
  - the whole release, which is one transaction with one audit event per document.
  Measured: the 20,000-document test, with the job, the export and the whole release, ran inside step 4f's 87 s together with every other PostgreSQL test. Not measured at a million.
- `Test-SDV-V1-Release-Readiness-Unit25.ps1` still holds one frozen check on the old local-header sign-in set-up. It is not part of the build or CI.

## Questions for the owner

None blocking.
- Do you want the V1 release-readiness runners and packaging scripts retired in a later task? They are V1-era.
- The older test scripts that delete audit rows (listed in report 0050) were not part of this task.

## Suggested next step

Merge after `ci-passed`. If a million-document incident is expected soon, a follow-up task could:
- keep the per-level counts on the incident as rows are written, so views don't read the whole incident;
- write the whole release in batches too.
