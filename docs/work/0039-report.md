# Report 0039: Intake codes and recording every intake failure (failure-catalog fix 4)

Date: 2026-11-22
Branch: `fix/intake` (from `release/vero-r7` at `4537003`, after `fix/sign-in` was merged)
Outcome: DONE

## What was done

Backend, the Daily intake report and Problems. **One schema change**: changeset 033 adds one new table, `unidentified_intake_attempts`. No existing table or row changes. No dependency added or upgraded.

The task approved a schema change "for item 0.3 and for item 3 if one is needed". Item 3 (the line under the table) needs none; item 2 does. I asked in the session, and the owner approved the table and chose who sees attempts with no tenant (answers below). The tenant check (0.3) needs no schema change.

### 0. Owner answers to report 0038

1. **AUT-001 logs at DEBUG.** Codes now have a refusal log level (none, DEBUG or INFO); AUT-001 is DEBUG. The other levels are unchanged.
2. **AUT-007 logs the subject.** The INFO line now reads `… reason=tenant=<id> subject=<user or client ID> …`. Control characters are dropped and it is cut at 300 characters. It never holds the token, the groups, the name or another claim, and the answer never holds the subject.
3. **Every request checks that the tenant is active.**
   - A new filter, `ActiveTenantFilter`, runs after sign-in on every signed-in request, for people and sending systems.
   - It uses `ActiveTenantCache`: `SDV_TENANT_STATUS_CACHE_SECONDS`, default 30, allowed 0 to 60. A value outside that range stops the start (SDV-SYS-005).
   - A suspended or unknown tenant gets 403 AUT-009 everywhere, with the same sentence for both. The WARN line ends "(request check)".
   - A suspension takes effect within the cache time. The places that read the tenant anyway refuse at once.
   - If the database can't be read, the check fails (503 SYS-014); the tenant is never assumed active.
   - Vero doesn't start in a sign-in mode without the check (fail closed).
4. **Health groups.** How they are set:
   - The database ping (`databaseConnection`) and the sign-in keys check (`signInKeys`) now report `NOT_READY` instead of `DOWN`.
   - Only the readiness group lists `not-ready` in its status order and maps it to 503 (`management.endpoint.health.group.readiness.status.order` and `.status.http-mapping`).
   - Spring ignores a status that isn't in a group's order. So the overall `/actuator/health` and liveness stay UP (200) while the database or the hub is away; liveness is Spring's default group (`livenessState` only).
   - Spring's own database check (`management.health.db.enabled`) is switched off. It would otherwise make the overall health DOWN while the database is away; Vero's ping replaces it, in readiness only.
   - One case is unchanged: when the database can't be reached **at start**, the overall health is still 503 OUT_OF_SERVICE. That comes from the schema check (`databaseSchema`, state UNREACHABLE), not from the ping.

### 1. INT-004 to INT-018 are sent

Each code has its fixed sentence, the one error body with the reference, and one log line with the exact cause (a field's key, a rule, a header's name, a limit, a document ID). The line is INFO for a refusal, WARN for INT-005 and ERROR for INT-010.

| Code | Status | Was |
|---|---|---|
| INT-004 no such submission | 404 | 404 `NOT_FOUND` |
| INT-005 sending system unknown or paused (one answer for both) | 403 | 404 or 409 |
| INT-006 submission request incomplete | 400 | 400 `BAD_REQUEST` |
| INT-007 document list not valid | 400 | 400 `BAD_REQUEST` |
| INT-008 metadata field unknown, missing or not a single value | 400 | 400 `BAD_REQUEST` |
| INT-009 metadata value not valid | 400 | 400 `BAD_REQUEST` |
| INT-010 a field's own stored rule can't be used | **500** | 409 `CONFLICT` |
| INT-011 submission retry differs | 409 | 409 `CONFLICT` |
| INT-012 upload has no document ID, or one not in the list | 400 | 400 `BAD_REQUEST` |
| INT-013 upload header not valid | 400 | 400 `BAD_REQUEST` |
| INT-014 PDF too large (limit in the message and the log) | 413 | 413 `PDF_TOO_LARGE` |
| INT-015 not a PDF | 400 | 400 `INVALID_PDF` |
| INT-016 submission already full (409), or over its total size (400) | 409 / 400 | same statuses, generic codes |
| INT-017 document retry differs (the log says content or file name) | 409 | 409 `CONFLICT` |
| INT-018 can't be finished again: unknown document (404), state (409) | 404 / 409 | same statuses, generic codes |

Other changes in the answers:
- **INT-009 never repeats a value.** Only the validator's own sentences are sent; a number parser's message, which can repeat the value, is replaced by "… is not a valid <type> value.".
- **INT-010 is now a 500** (see question 1).
- **INT-013 names the header.** One message changed wording: "sourceDocumentId exceeds 200 characters." is now "X-SDV-Source-Document-Id exceeds 200 characters.".

### 2. Every intake failure is recorded

Recording now happens in one place, `IntakeRefusals`. Vero's error handler and its /error page call it after every error answer on `/api/v1/ingestion/…`. That takes in:
- the answers Spring gives before Vero's code runs (SYS-015: JSON that doesn't parse, a missing `X-SDV-Filename`, a content type other than PDF);
- the sign-in refusals;
- the 5xx failures.

The two controllers no longer record anything themselves.

**When the sending system is known** (a verified sending-system sign-in whose system is one of the tenant's, active or paused):
- A delivery, upload or finishing retry (`POST`) gets one audit event: `INTAKE_REFUSED` for a 4xx, or new `INTAKE_FAILED` (outcome FAILED) for a 5xx.
- The event holds the stage, the system, the submission, the request ID or upload ID as sent, the status and the code. Never a message, a file name or content.
- The Daily intake report counts it under that system. Failures go in the **Failed** column with their codes, beside the documents that failed after receipt. The KPI reads "N after receipt · M at intake".
- The CSV has a `FAILED_AT_INTAKE` line with its code.

**When there is no known sending system** (no sign-in, a refused token, a person's sign-in, a sending system the tenant doesn't have, a suspended tenant):
- One row is written in `unidentified_intake_attempts`: time, the caller's network address, the address called (IDs blanked to `{id}`), method, status, code and reference. Never a header value, a token or anything from one.
- The tenant is set only when a verified sign-in named a tenant that exists.
- Tenant IT and Platform operations see the rows in **Problems**, under a short list "Unidentified intake attempts · N" below the problems table. It shows the last 7 days, newest 50.
- Owner answer (2026-11-22): rows without a tenant are shown to every tenant's Tenant IT; rows with a tenant only to that tenant.
- Rows older than 30 days are removed when a new one is written.

**If recording fails,** the answer is unchanged and one WARN line says the attempt was not recorded. A failure while the database is away can't be recorded; it is in the log only.

### 3. The line under the Daily intake table

It now reads: "Refused and failed deliveries from a known sending system are counted here, with the code each got; only failures while Vero's database was away can't be (they are in Vero's log). Attempts Vero couldn't link to a sending system (for example a failed sign-in) are listed for Tenant IT under Problems."

### 4. Catalog

`docs/api/error-codes.md`:
- INT-004 to INT-018 have released `###` sections under Intake, with an intro on how every intake failure is recorded.
- Their proposals are marked "released in task 0039".
- AUT-001 (DEBUG), AUT-004 (readiness only), AUT-007 (subject), AUT-009 (every request, cache), SYS-014 (readiness only) and SYS-015 (recorded on intake) are updated.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test -fn` (in `backend`), first run | Test compile errors in the two tests whose shapes changed (`IntakeRefusalsTest`, `ReportsControllerTest`); fixed |
| `mvn -o test -fn`, second run | 2 failures: my own CSV assertion (cells are quoted), and `RecordSensitivityIntakeTest` (the INT-011 change, see below). Both fixed; then they passed |
| `.\Stop-SDV-Local.ps1` | Backend and UI stopped; sdv-postgres and sdv-vault left running |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS.** Backend 515 tests (24 + 50 + 2 + 4 + 435), 0 failures, 7 skipped (PostgreSQL-only). Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` (default, now `-From 033`) | First run: FAIL. The new table was not excluded from the "every other table unchanged" comparison. Fixed in the script; then **PASS**, 35 s. All 7 PostgreSQL tests passed, including the new `UnidentifiedIntakeAttemptPostgresTest`. |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 032` | **PASS**, 35 s (032 and 033) |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 104 s. 33 changesets. New checks: overall health 200 while the database is gone (5c) and while the keys are away (6a). A failed sign-in on an intake address is one unidentified attempt (`GET /api/v1/ingestion/submissions/{id}`, no tenant, 401 AUT-006) with no token content (6b). |
| `.\Start-SDV-Local.ps1` | Readiness 200. The local start applied changeset 033 to the dev database `sdv`. |
| `npm run typecheck` | Passed |
| `npm run ui:capture`, first run | 23 passed, 2 failed, 11 skipped: (1) the tour checked the old line under the table (changed by item 3; tour updated); (2) the Restricted steward's "Print (approved, use by …)": the seeded approval had expired ("Access request expired"). Not caused by this change. |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` | Passed; demo data put back. Under `powershell.exe -File`, `$PSScriptRoot` is empty in its parameter defaults, so I passed the four path parameters with their default values. |
| `npm run ui:capture`, second run | **25 passed, 11 skipped** (as in reports 0035 to 0038) |
| `curl` against the local app | See below |

Live results with the local test sign-in headers:

| Request | Answer | Recorded |
|---|---|---|
| No sign-in on intake | 401 AUT-001 | Problems, no tenant |
| Malformed JSON from LexaVault | 400 SYS-015 | `INTAKE_REFUSED` under LexaVault |
| `expectedDocumentCount` 0 | 400 INT-006 | `INTAKE_REFUSED`, with request ID `live-0039-a` |
| Upload to an unknown submission | 404 INT-004 | `INTAKE_REFUSED`, with the upload's ID |
| A sending system the tenant doesn't have | 403 INT-005 | Problems, with the tenant |
| A person on intake | 403 AUT-002 | Problems, with the tenant |
| Health | overall 200, liveness 200, readiness 200 | — |

The Daily intake report and Problems (as Tenant IT) showed these.

## Tests: counts, and any failures with their output

Build: 515 tests, 0 failures, 7 skipped. Report 0038 had 483 tests, 6 skipped.

**Tests changed to match the new behaviour (said here as the rules require):**
- `SignInChainTest`:
  - AUT-001 now expects one DEBUG line and none at INFO (it expected INFO).
  - The AUT-007 test now expects the subject in the INFO line, under its own subject value (`pat.lee-0039`). It used the do-not-echo subject, which no line may hold. The check that nothing else from the token leaks is unchanged.
- `RecordSensitivityIntakeTest.aRetryThatAddsALevelOrChangesSomethingElseIsTold`: expected an uncoded conflict; it now expects SDV-INT-011.
- `IntakeRefusalsTest`: rewritten for the single place of recording. It used to assert that "failures (5xx) are not recorded"; they now are (`INTAKE_FAILED`). The details now hold the status. The PDF codes are INT-014 and INT-015.
- `DatabaseConnectionHealthIndicatorTest`, `SignInKeysHealthIndicatorTest`: `DOWN` became `NOT_READY` (owner answer 4).
- `ReportsControllerTest`: the report totals have two more fields.
- `SignInTestKit`: the test stack has the tenant check (a sign-in mode without it now refuses to start).
- `e2e/sdv-screen-tour.spec.ts`: the new line under the table, the Failed KPI, and the Unidentified intake attempts list in Problems (no token content).
- Scripts: `Test-SDV-SchemaUpgrade.ps1` checks 033 (default `-From 033`) and runs the new PostgreSQL test. `Test-SDV-CleanInstall.ps1` has the checks named above.

**New tests (32 in the normal build, plus 1 PostgreSQL test):**
- `IntakeCodesTest` (15): every code from INT-004 to INT-018, through the real intake services and Vero's error handler. Each checks the status, code, sentence and reference, and one log line at the right level with the reference and the exact cause. No value sent appears in an answer or a log line.
- `IntakeRefusalsTest` (12):
  - Known system: refusals with their codes, including Spring's (malformed JSON, missing `X-SDV-Filename`, 415).
  - 5xx failures (SYS-013, SYS-014) as `INTAKE_FAILED`; a status query isn't counted.
  - An unknown sending system goes to Tenant IT with the tenant.
  - A failed sign-in on an intake address goes to Tenant IT with no tenant and no token content (token, subject, file name). The address has its IDs blanked, also when reached as /error.
  - Other addresses aren't recorded. A recording failure keeps the answer and logs a WARN.
- `ActiveTenantCacheTest` (4):
  - A suspension takes effect at 60 s and not before; the database is read once per period.
  - Unknown and suspended give the same answer.
  - 0 s reads every time; a database failure is never taken as active.
  - The setting is limited to 0 to 60.
- `SignInChainTest` (+1): a suspended tenant is refused on every address (session, a business page, the intake), for a person and for a sending system. The same answer for an unknown tenant, and accepted again when reactivated.
- `HealthGroupsTest` (3): Vero's own `application.yml` health settings in a Spring Boot context.
  - The database or the hub away: readiness NOT_READY 503; liveness and overall UP 200.
  - A real DOWN is still 503 in readiness.
  - Spring's database check is off; liveness is Spring's default.
- `ProblemsAndAuditRecordsTest` (+1): Problems carries the attempts (7 days, 50). A read failure marks only that section degraded.
- `IntakeReportServiceTest` (+1): intake failures are counted under their system with their codes, apart from refusals, and in the CSV.
- `UnidentifiedIntakeAttemptPostgresTest` (1, run by the schema-upgrade check):
  - Attempts per tenant and without one; a tenant Vero doesn't have is kept as none; never another tenant's.
  - Rows older than 30 days are removed.
  - The report's SQL returns REFUSED and FAILED with their codes and request IDs.

## Files changed

- Domain: `SdvErrorCode` (INT-004 to 018, DEBUG level), `CodedRefusal` (log detail on 400 and 404), new `SetupProblem`.
- Application:
  - New: `access/ActiveTenantCache`, `source/ActiveSource`, `port/UnidentifiedIntakeAttemptRepository`.
  - Changed: `DocumentIntakeService`, `SubmissionService`, `MetadataValueValidator`, `PdfTooLargeException`, `InvalidPdfException`, `ValidatedPdfInputStream`, `IntakeReportService`, `IntakeReportRepository`, `OperationsService`.
- Persistence: new `JdbcUnidentifiedIntakeAttemptRepository`; `JdbcIntakeReportRepository`, `JdbcSecureIngestionRepository`.
- Security: new `ActiveTenantFilter`; `SecurityConfiguration`, `EnterpriseJwtAuthenticationConverter`.
- Web: `IntakeRefusals` (rewritten), `FoundationExceptionHandler`, `SdvErrorController`, `ErrorReplies`, `SubmissionController`, `DocumentIntakeController`, `DatabaseConnectionHealthIndicator`, `SignInKeysHealthIndicator`, new `schema/ReadinessOnly`, `application.yml`.
- Schema: new `033-unidentified-intake-attempts.sql` and `.yaml`; `db.changelog-master.yaml`.
- Frontend: `Reports.tsx`, `Problems.tsx`, `liveApi.ts`, `e2e/sdv-screen-tour.spec.ts`.
- Tests: the 5 new test classes and the changed ones above.
- Scripts: `Test-SDV-SchemaUpgrade.ps1`, `Test-SDV-CleanInstall.ps1`.
- Docs: `docs/api/error-codes.md`, this report and the task file.
- Not touched: `Start-SDV-Local.ps1` (its local changes stay uncommitted), `docs/work/0040-task.md` (the next task; left untracked).

## Commits (hash and message), or "not committed"

- The work commit: "fix: intake codes INT-004..018, every intake failure recorded, tenant check per request, readiness-only health checks (task 0039)".
- A follow-up commit writes its hash here.

Both are on `fix/intake` only. Nothing was pushed or merged.

## Not done, and why

- **A known system's failed status query (`GET`) isn't counted** in the Daily intake report: it delivers nothing, and counting it would add "deliveries". It is in the log. Without a known system it is recorded (Problems), like any method.
- **Failures while the database is away can't be recorded.** The answer is SYS-014 and the log line has the reference.
- **No limit on unidentified attempts.** Every unidentified request writes one row (kept 30 days). Many requests without a sign-in would write many rows. See question 2.
- **The caller's address is what Vero's server sees.** Behind a proxy it is the proxy's address; `X-Forwarded-For` is not trusted. See question 3.
- **A suspended tenant's attempts** are recorded with that tenant, so only its own (suspended) Tenant IT would see them. They are in the log with "(request check)".
- **The shared delivery contract and the OpenAPI file** are not updated here. Task 0040 does that. Senders now get INT codes instead of the generic ones, and 403 INT-005 instead of 404 or 409.
- **The seed script's path defaults** are empty under `powershell.exe -File` (Windows PowerShell 5.1). I passed the paths by hand and didn't change the script.

## Questions for the owner

1. **INT-010 status.** The catalog said ERROR and "your IT team has been told", but gave no status. I answered 500: the sender can't fix it, and Vero sends ERROR lines only for a 5xx. The report counts it as failed, not refused. Keep 500?
2. **Unidentified attempts: limit them?** For example one row per caller address, address and code per minute, with a count. Now it is one row per request, kept 30 days.
3. **Caller address behind a proxy.** Should Vero trust `X-Forwarded-For` from a configured proxy, so Problems shows the sender's real address?
4. **The overall health at start without a database** stays 503 OUT_OF_SERVICE (the schema check, unchanged since task 0036). Should that become readiness-only too?

## Suggested next step

Review and merge `fix/intake`. Then task 0040 (the ingestion API contract) can list the INT codes and the new statuses. Before the next screen tour, run `.\Seed-SDV-Canvas-Demo.ps1 -Reset`: the demo print approvals expire after 24 hours.
