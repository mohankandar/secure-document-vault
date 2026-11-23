# Report 0056: Primary identifier on the Restrictions page

Date: 2026-11-23
Branch: `fix/restrictions-identifier` (from `release/vero-r7` at `c9b55e0`)
Outcome: DONE (option A), with one wording change and one item not done (see below)

## What was done

The first pass stopped: the "configured identifier field" the task assumed does not exist (no setting, and Approvals and Legal holds use the same rule as 0055). The owner chose **option A** in the session, and it is written into the task file: keep the rule, share it.

**The rule:** a record's primary identifier is its first non-blank business-identifier value, in the sending system's display order. Fields with no saved position come after, and ties go by field key.

1. **One shared helper, two forms.** The rule now exists in two places, which must stay the same:
   - `PrimaryIdentifierRule` (sdv-application): the Java comparator.
   - `PrimaryIdentifierSql` (sdv-persistence): the SQL join that reads a submission's primary identifier, and the field order.

   They replace the seven copies of the rule. Every admin read uses them:
   - Approvals (`JdbcAccessRequestRepository`)
   - Legal holds (`JdbcLegalHoldRepository`)
   - Quarantine (`JdbcQuarantineRepository`)
   - Audit list record number (`JdbcOperationalRepository`)
   - Daily intake report and steward notices (`JdbcIntakeReportRepository`, also used by `JdbcSecureIngestionRepository`)
   - Sensitivity report label (`JdbcSensitivityReportRepository`)
   - Restrictions (`JdbcRestrictionsRepository`, `RestrictionsService`)
   - Problems (`ProblemRecordResolver`)

   The frontend never picks the identifier; it shows what the backend sends. No schema change.
2. **Two places now follow the rule.** Both are visible changes:
   - **Audit list:** a blank first identifier was shown as a blank record number. It now gives way to the next identifier, as on every other page.
   - **Restrictions:** a record key without the system's first identifier fell back to the first field by **field key**. It now takes the first of the key's fields in **display order**. The old `findPrimaryFields` lookup is replaced by `findDisplayOrders`.
3. **Help text on the Search fields page,** under the field list: "The first identifier field in the order shown is the record's identifier on admin pages. Put your contract (or member, loan, patient) number first." I added the word "identifier". The owner's wording says "the first field", which is wrong when a non-identifier field is first (the test below has that case).
4. **The later switch-over is noted** in both helpers' comments: at Vero's case-fields switch-over, platform-fields' `primary` flag becomes the source of the primary identifier, and only these two helpers change.
5. **Tests:**
   - **`PrimaryIdentifierPostgresTest`:** one record on every admin read listed in item 1. Its sending system has a non-identifier field first, and two identifiers whose display order is the reverse of their field-key order.
     - Every read names the record the same way (value and label).
     - Legal holds find it by that value only.
     - A blank identifier gives way to the next.
     - After the display order is changed, every read follows.
   - **`PrimaryIdentifierRuleTest`:** the comparator alone.
   - **`RestrictionsServiceTest`:** display order beats field key, and with no saved order the field key decides.
   - The new Postgres test is added to the PostgreSQL test lists in `Test-SDV-SchemaUpgrade.ps1` and in CI's "the PostgreSQL-only tests ran" check.
6. `QUEUE-2.md` and `QUEUE-2-report.md` are committed, as the task asks.

## No other product's name or behaviour in code: yes

The helper comments name `platform-fields` as a library, which the product-name guard allows (as it allows `platform-core`). `ProductNameGuardTest` passed in the full build.

## Commands run and results

| Command | Result |
|---|---|
| `Stop-SDV-Local.ps1` (before building) | App stopped |
| `.\Build-SDV-All.ps1` (first run) | FAIL: compile error in `PrimaryIdentifierRule` (a constructor name I missed when renaming the class). Fixed |
| `.\Build-SDV-All.ps1` (final code) | PASS: backend 683 tests, 0 failures, 0 errors, 21 skipped (the PostgreSQL-only tests; 0055: 679/20); frontend build OK |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` | PASS: 63 tests |
| `.\Test-SDV-SchemaUpgrade.ps1` (final code) | PASS in 503 s. Step 4f passed every listed PostgreSQL test, `PrimaryIdentifierPostgresTest` included. An earlier pass (also PASS) ran before the new test was on the list |
| `.\Test-SDV-CleanInstall.ps1` (final code) | PASS in 127 s |
| `Start-SDV-Local.ps1`, then `cd frontend/sdv-ui; npm run ui:capture` | PASS: 30 passed, 0 failed, 16 skipped. The new help text shows on the Search fields screen. The app started on the local `sdv` database, so 039 to 042 are applied there now. I did not run the schema step myself |

## Tests: counts, and any failures with their output

- Backend: 683 run, 0 failures, 0 errors, 21 skipped (the PostgreSQL-only tests, which ran in the schema check).
- New: `PrimaryIdentifierRuleTest` (2), `PrimaryIdentifierPostgresTest` (1), `RestrictionsServiceTest` (one test added; 5 in all).
- Changed: `RecordOpenAuditTest` checks the audit query's SQL text. Its expected text now matches the shared join (`v.submission_id=coalesce(...)` instead of `v.submission_id = coalesce(...)`). Same condition, different spacing.
- Frontend: 63 passed. Screen tour: 30 passed, 16 skipped (the iPad runs of desktop-only steps).

## Files changed

- New: `backend/sdv-application/.../business/PrimaryIdentifierRule.java`, `backend/sdv-persistence/.../business/PrimaryIdentifierSql.java`
- Backend: `ProblemRecordResolver.java`, `RestrictionsRepository.java`, `RestrictionsService.java`, `JdbcAccessRequestRepository.java`, `JdbcLegalHoldRepository.java`, `JdbcOperationalRepository.java`, `JdbcQuarantineRepository.java`, `JdbcIntakeReportRepository.java`, `JdbcSensitivityReportRepository.java`, `JdbcRestrictionsRepository.java`
- Tests: `PrimaryIdentifierRuleTest.java` (new), `PrimaryIdentifierPostgresTest.java` (new), `RestrictionsServiceTest.java`, `RecordOpenAuditTest.java`
- Frontend: `frontend/sdv-ui/src/r7/SearchFields.tsx` (help text)
- Scripts and CI: `Test-SDV-SchemaUpgrade.ps1`, `.github/workflows/ci.yml` (the new test on the PostgreSQL lists)
- Docs: `docs/work/0056-task.md` (with the owner's decision), `docs/work/0056-report.md`, `docs/work/QUEUE-2.md`, `docs/work/QUEUE-2-report.md`
- `Start-SDV-Local.ps1` keeps its local changes and is not committed.

## Commits (hash and message), or "not committed"

- `146b866` docs(work): task 0056
- `d1a1a76` docs(work): report 0056 (stopped) and queue 2 files
- The next commit: fix(admin): one primary-identifier rule on every admin page (task 0056)

## Not done, and why

- **Admin guide:** Vero has no admin guide (no such file under `docs/`, only the `.docx` product documents). The rule is on the Search fields page only.

## Questions for the owner

1. Where should the admin guide live? I can write the rule into it once it exists, or start one at a path you name.
2. The help text says "the first **identifier** field" (one word added to your wording). Keep it?

## Suggested next step

Merge on green `ci-passed`, then the next task in `docs/work/`.
