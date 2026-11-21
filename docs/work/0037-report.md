# Report 0037: Start-up and internal checks (failure-catalog fix 2)

Date: 2026-11-21
Branch: `fix/startup` (from `release/vero-r7` at `9d8b4e6`, after PR #10 was merged)
Outcome: DONE, with two questions for the owner (below). Backend, plus the tour, a check script and the codes document. No schema change; no dependency added.

## What was done

1. **Database unreachable at start (SDV-SYS-003).**
   - Vero no longer refuses until a restart. It keeps trying: after 2 s, then doubling, at most once a minute (`DatabaseSchemaCheck`).
   - Meanwhile every request except health and system information gets **503 SDV-SYS-014** with the reference, as the task says.
   - When the database answers, the version check runs again. Vero then serves, or refuses with SYS-001 or SYS-002, without a restart.
   - Log:
     - one ERROR line `SDV-SYS-003 …` with the host and port (never the user, password or URL parameters) and the SQL state;
     - a WARN line per further try;
     - an INFO line when the database can be reached again.
   - This applies to packaged starts. A local start migrates with Liquibase and still needs the database at start (see "Not done").
2. **Readiness watches the database.**
   - New `databaseConnection` indicator in the readiness group: a connection from the pool and `isValid`.
   - It runs on its own thread. A probe never waits more than 2 s, and probes that arrive while a ping runs share it.
   - Readiness is 503 while the database is gone and 200 when it is back. Liveness stays UP.
   - Readiness also turns back to ready when a database that was unreachable at start comes back.
3. **Schema guard for `RestrictedStewardMappingWatcher` (SYS-012).**
   - **The catalog's "likely" is confirmed.** I ran the jar built from `9d8b4e6` with `enterprise-jwt` and no database. It logged "Started", then stopped with a raw `CannotCreateTransactionException` from the watcher, exit code 1.
   - New port `DatabaseStartupWork`. The watcher and the sign-in connection bootstrap implement it.
   - `DatabaseStartupWorkRunner` runs that work once the database matches: at start, or when it comes back later. While the database doesn't match, it logs one WARN line `SDV-SYS-012 Start-up work waits …` and the start goes on.
   - Fail closed, as before:
     - if the work itself fails at start, the start stops, with SYS-012 and the cause (exception classes and SQL state, never the SQL);
     - after a late recovery Vero stops, so it never serves with that work undone.
   - The explicit sign-in recovery (`OidcKnownGoodRecovery`) still stops the start when the database doesn't match. That is deliberate, and now has SYS-011.
4. **Sign-in or key service switched off (SYS-006, KEY-001).**
   - New `SwitchedOffCheck`. Locally: one WARN line each, naming what will fail.
   - A **packaged start refuses** with either switched off. "Packaged" means any start without the `local` profile; there is no other install-mode setting in the code.
   - It runs while the context is built, so the port never opens.
5. **Codes in start-up failures (SYS-001 to SYS-012).**
   - New `StartupFailure` (code, the catalog sentence, the exact cause, the action) and a Spring failure analyser (`StartupFailureAnalyzer`, registered in `META-INF/spring.factories`).
   - A refused start prints one "APPLICATION FAILED TO START" block, with no stack trace, for example:
     `SDV-SYS-006 Sign-in is switched off (SDV_AUTH_MODE=disabled). Cause: sdv.auth.mode (SDV_AUTH_MODE) is "disabled": nobody can sign in, …`
   - Sites given their code:

     | Code | Where |
     |---|---|
     | SYS-001, SYS-002 | the start-up log lines |
     | SYS-003 | the start-up log line (item 1) |
     | SYS-004 | `SchemaVersion.shipped` |
     | SYS-005 | `SecurityConfiguration` (3), `EnterpriseJwtConfiguration` (7), `EnterpriseJwtAuthenticationConverter` (2), `MaximumTokenLifetimeValidator`, the bootstrap's tenant ID |
     | SYS-007 | `VaultCryptoConfiguration` (4), and the provider's own checks, wrapped |
     | SYS-008 | `ApprovalSettings` (4) |
     | SYS-009 | `RetentionSchedule` (4) |
     | SYS-010 | `IntakeReportService` (2) |
     | SYS-011 | `OidcKnownGoodRecovery`: its 2 checks, the two IDs (no more raw "Invalid UUID string"), and every refusal from `recoverKnownGood` |
     | SYS-012 | the start-up work runner |

   - `StartupFailure` does not extend `IllegalStateException`, for the same reason as `InternalCheckFailed`.
6. **The remaining SYS-016 checks** (report 0036, "Not done"). Each one, and what I chose:

   | Check | Before | Now |
   |---|---|---|
   | Bare `orElseThrow()`: `BusinessRecordRefCodec.java:108`, `RoleConflictService.java:73`, `JdbcOidcConnectionBindingRepository.java:62, 74, 97, 117, 146` | 404 "No value present" | internal, 500 SYS-016 |
   | Required arguments: `AccessRequestService` (tenant, actor), `LegalHoldService` (tenant, actor), `SensitivityLevelService:236, 274, 275, 285`, `AuditQueryService:63, 98, 99` | 400 `BAD_REQUEST` | internal |
   | `DocumentAccessGuard:115`, `PdfSanitizingRedactor:30-32`, `ValidatedPdfInputStream:23`, `SdvEnc001StreamingCrypto:63, 119`, `VaultTransitKeyProtectionProvider:110`, `Document:28`, `SensitivityLevel:50` | 400 | internal |
   | Fixed role rules: `ForbiddenGrant:12, 16`, `RoleSeparationPolicy:23, 34`, `RoleSeparationRule:18, 24, 28`; `JdbcRoleGovernanceRepository:151` (group names come from settings already split at commas); `JdbcDocumentRedactionRepository:78, 81` (the recovery sweep's own limits) | 400 | internal |
   | `IntakeReportService.java:162` (a delivery has no document line) | 400 | internal |
   | Unsupported defaults: `MetadataFieldDefinitionRepository:17, 20`, `RecordSensitivityRepository:28`, `StorageVolumeProbe:19` | 500, Spring's body | internal |
   | **`StoragePlacementService.java:24`** | 400 | **Caller's fault:** the storage placement preview takes `requiredBytes` from the request. The preview now refuses a negative value with **400 SDV-STO-005**, "requiredBytes must be 0 or more." The check inside placement (upload sizes) is internal. |

   - Line numbers are today's on this branch. Some moved by one line since catalog 0034.
   - The catalog's `RoleConflictService:73` can't actually be reached, because the list is never empty there. It is converted so it says something if it ever fails.
7. **APR-008 and HLD-008** (owner answer 2).
   - Both repositories now say whether a row changed (`decide`, `release` return `boolean`). The services refuse with 409:
     - APR-008, "Someone else already decided this request.";
     - HLD-008, "This hold was already released."
   - APR-008 is audited like every refused decision (`ACCESS_REQUEST_DECISION_REFUSED`).
   - HLD-008 is now audited as the new **`LEGAL_HOLD_RELEASE_REFUSED`** (DENIED, with the code, written outside the rolled-back transaction). This covers the earlier "already released" path too. The audit sentence is added in `AuditSentences`.
8. **The screen tour waits for readiness.**
   - New `beforeAll` in `sdv-screen-tour.spec.ts` polls `/actuator/health/readiness`, through the UI's own proxy, until it is 200, for up to 180 s. Only this wait has that limit.
   - No step's time limit changed.
9. **Codes document (`docs/api/error-codes.md`).**
   - Released in their areas: SYS-004 to SYS-012, STO-005, and KEY-001 in a new "Key service (KEY)" section.
   - The catalog blocks are marked "released in task 0037". SYS-012 has the confirmation line.
   - SYS-003 now reads "no HTTP status; start-up log line". SYS-014 now also covers "unreachable since the start" and the readiness ping.
   - APR-008 and HLD-008 describe the race and the audit.
   - The introduction lists `KEY`.
10. **`Test-SDV-CleanInstall.ps1`.** A packaged start now needs sign-in and a key service, so the starts use placeholders that nothing in the check contacts:
    - an issuer under `.invalid`;
    - Vault at `127.0.0.1:8299`, where nothing listens;
    - a token file with the text `placeholder-not-a-token`.

    New steps 0 and 5:
    - **Step 0:** the two refusals.
    - **Step 5:** the database through `test-tools/TcpRelay.java`, a small Java relay in front of sdv-postgres that the script stops and starts. **The container is never stopped.**

## Commands run and results

| Command | Result |
|---|---|
| Jar from `9d8b4e6`, `enterprise-jwt`, database URL to a closed port | Confirmed SYS-012 "likely": "Started", then `CannotCreateTransactionException`, exit 1 |
| `mvn -o test -fn` (in `backend`), runs 1 to 3 | Failures, all from the change (next section); fixed one by one |
| `mvn -o test -fn`, final | 456 tests, 0 failures, 5 skipped |
| `.\Stop-SDV-Local.ps1` | Backend stopped; sdv-postgres and sdv-vault left running |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS**. Backend 456 tests (24 + 50 + 2 + 4 + 376), 0 failures, 5 skipped (the PostgreSQL-only tests). Frontend build passed. |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 93 s, first run. Summary below. |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 032`) | **PASS**, 43 s. All five PostgreSQL tests passed, including the two new races. |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 030` | **PASS**, 45 s; all five PostgreSQL tests passed |
| `.\Start-SDV-Local.ps1` | Started; readiness 200 through the UI proxy after about 12 s |
| `npm run ui:capture`, right after that fresh start | **Passed on the first run**: 25 passed, 11 skipped (as in reports 0035 and 0036) |
| `npm run typecheck` | Passed. It doesn't cover `e2e/`; the tour run compiled the spec. |
| `curl` preview with `requiredBytes=-1` and `1000` | 400 `SDV-STO-005` with the reference; 200 |

Clean-install steps:
- **0a, 0b:** `SDV_AUTH_MODE=disabled` and then `SDV_KEY_PROVIDER=disabled` were each refused. Exit code not 0, the port never answered, no stack trace, and the failure block starts with the code.
- **1:** the empty database is refused with "older", and the log has the SYS-012 "waits" line instead of a stopped start.
- **3:** "Start-up work done: Restricted Steward sign-in group check."
- **5a:** the database is away. Readiness 503, liveness 200, API 503 SDV-SYS-014 with a reference, system info UNREACHABLE. The SYS-003 ERROR line names `127.0.0.1:55433`, and the password is not in the log.
- **5b:** the relay starts and Vero is ready about 8 s later. It is the same process, the API answers 401 (the gate is open), and the bootstrap and group check ran.
- **5c:** the relay stops and readiness is 503 within 0.2 s. It starts again and readiness is 200. Liveness stayed 200 throughout.
- No connection outside this computer.

**About the tour.** The first run after a fresh start passed, which matches the readiness wait. I have not proved the earlier one-off failure had this cause; it couldn't be made to happen on demand. In this run the app was already ready when the tour began, so the wait returned at once.

## Tests: counts, and any failures with their output

**New tests (43 in `sdv-web`: 333 before, 376 now; plus two PostgreSQL races inside existing tests):**
- `DatabaseRecoveryTest` (7):
  - with the database down at start: the SYS-003 ERROR line has the host, port and SQL state; requests get 503 SYS-014 with the reference; readiness is OUT_OF_SERVICE and refusing; tries continue;
  - the database comes up: it serves and readiness is ACCEPTING again, with no restart;
  - the back-off goes 2, 4, 8, 16, 32, 60, 60 s;
  - the scheduled retries recover on their own;
  - a database that comes back older is SYS-001 and stays not ready;
  - a damaged package (SYS-004) still stops the start;
  - the log shows only the host and port;
  - a local start checks nothing.
- `DatabaseConnectionHealthIndicatorTest` (2): up → down (the class name only) → not valid → up again; a ping that doesn't answer gives DOWN within its limit, and probes share one ping.
- `DatabaseStartupWorkRunnerTest` (7):
  - the watcher on its own throws a raw JDBC exception (the old behaviour);
  - with the database missing, the start goes on and the watcher waits; it runs once when the database is back;
  - a database that comes back mismatched runs nothing;
  - a matching database runs the work once;
  - failed work is SYS-012 with the SQL state and no SQL;
  - work keeps its own code;
  - a late failure stops Vero.
- `SwitchedOffCheckTest` (5): WARN locally, refusal when packaged, both logged, silence when both are set, and the defaults and profile.
- `StartupCodesTest` (3): every start-up code has its own sentence and action; the failure report has code, sentence, cause and action, even when wrapped by Spring; approvals settings give SYS-008.
- `VaultSettingsCodeTest`, `SignInSettingsCodeTest`, `StartupSignInCodesTest` (6): SYS-007 (the token is never in the message), SYS-005 (names the setting), SYS-011 (each check), and the bootstrap's SYS-005 and wait.
- `InternalChecksTest` (5), `RecordKeyCheckTest`, `ValidatedPdfInputCheckTest`, `StoredNameCheckTest`, `TenantKeyNameCheckTest`, `IntakeOutcomeCheckTest`: each item 6 change, and STO-005 through the error handler.
- `AccessRequestApprovalsTest.twoDecisionsAtTheSameMomentGiveTheSecondApr008Audited`: the second decision gets APR-008 with the owner's sentence and is audited; the first decision stands; in bulk it is one REFUSED line.
- `LegalHoldServiceTest`: the same release at the same moment gives HLD-008, audited; a refusal stands when its audit can't be written.
- **PostgreSQL, two real threads:**
  - `AccessRequestPostgresTest`: one decision, one APR-008 (audited);
  - `LegalHoldPostgresTest`: one release, one HLD-008 (audited).
  - Under the row lock the second one is caught by the service's own check. The repository's new "nothing changed" path is covered by the unit tests above.

**Existing tests changed, because this task changes what they check** (I'm saying so as CLAUDE.md asks):
- `SchemaGateFilterTest`: the unreachable answer is now SYS-014, not SYS-003.
- `SensitivityLevelTest`, `RoleSeparationPolicyTest`, `AuditExportTest`: they expect `InternalCheckFailed` instead of `IllegalArgumentException` (item 6).
- `RetentionNextRunTest`, `SensitivityReportServiceTest`, `IntakeReportServiceTest`: they expect `StartupFailure` instead of `IllegalStateException` (item 5), and now also check SYS-009 and SYS-010.
- `LegalHoldServiceTest.aHoldIsReleasedOnceAndAudited`: stubs `release` to return true, because the mock otherwise returns false, which now means "someone else released it".
- `ApprovalsFixture`: the in-memory `decide` returns false instead of throwing, as the SQL does, and has a hook for the race.
- `ErrorCodesDocumentedTest`: unchanged. It failed until the new codes were documented.

## Files changed

- **New (main):**
  - `StartupFailure` (domain)
  - `DatabaseStartupWork` (application port)
  - `DatabaseConnectionHealthIndicator`
  - `web/startup/`: `DatabaseStartupWorkRunner`, `SwitchedOffCheck`, `StartupFailureAnalyzer`
  - `META-INF/spring.factories`
- **Changed, start-up:**
  - `DatabaseSchemaCheck`, `DatabaseSchemaHealthIndicator`, `SchemaGateFilter`, `SchemaVersion`
  - `RestrictedStewardMappingWatcher`, `OidcConnectionBootstrap`, `OidcKnownGoodRecovery`
  - `SecurityConfiguration`, `EnterpriseJwtConfiguration`, `EnterpriseJwtAuthenticationConverter`, `MaximumTokenLifetimeValidator`
  - `VaultCryptoConfiguration`, `ApprovalSettings`, `RetentionSchedule`
  - `SystemInfoController` (comment), `ErrorReplies` (one constant made public)
  - `application.yml` (readiness group)
  - `SdvErrorCode` (SYS-004 to 012, STO-005, KEY-001)
- **Changed, APR and HLD:**
  - `AccessRequestRepository`, `LegalHoldRepository`
  - `JdbcAccessRequestRepository`, `JdbcLegalHoldRepository`
  - `AccessRequestService`, `LegalHoldService`, `AuditSentences`
- **Changed, item 6:** the 24 files in the table above, and `StorageVolumeAdminService`.
- **Tests:** the new and changed tests listed above.
- **Scripts and docs:**
  - `Test-SDV-CleanInstall.ps1`
  - new `test-tools/TcpRelay.java`
  - `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
  - `docs/api/error-codes.md`
  - `docs/work/0037-task.md` and this report

Not touched: `Start-SDV-Local.ps1` and `Stop-SDV-Local.ps1` (local changes left as they were, not committed), `.runtime` (only new run folders were added), the containers and the Vault.

## Commits (hash and message), or "not committed"

See the commit on `fix/startup` (hash given to the owner in the session; a follow-up commit adds it here if wanted). Not pushed, not merged.

## Not done, and why

- **Local starts still need the database at start.**
  - With the `local` profile, Liquibase migrates during the start, so a database that is down stops it as before.
  - The retry covers packaged starts, which only check.
  - Making Liquibase wait would mean changing how local mode migrates, which the task didn't ask for.
- **KEY-001 and SYS-006 are not sent in answers yet.**
  - Document actions with no key service still answer `VIEWER_UNAVAILABLE`, `DOWNLOAD_UNAVAILABLE` or 500.
  - Requests with sign-in off still answer 401 `UNAUTHORIZED`.
  - The task asked for the start-up WARN and refusal; the answers belong to the key-service and sign-in fix tasks. The doc says so.
- **STO-005's other sites** (volume settings) still send `BAD_REQUEST`. Only the preview size was in this task.
- **The OpenAPI file is unchanged.** It covers intake only, and none of its answers changed.

## Questions for the owner

1. **SYS-003 no longer has an HTTP status.** The task asks for SYS-014 while the database is unreachable, so SYS-003 is now only the start-up log line. Its meaning is the same ("couldn't reach the database at start"), so I kept it rather than retiring it. Senders that branched on 503 SYS-003 from task 0036 now see 503 SYS-014. Please confirm, and add it to the shared delivery contract with the other status changes.
2. **What "packaged" means.** There is no install-mode setting, so a packaged start is any start without the `local` profile, which is how the clean-install check models a customer install. Is that the rule you want, or should there be an explicit setting?

## Suggested next step

Review and merge `fix/startup`. Then fix task 3 (sign-in: AUT codes), which can send SYS-006 in the 401 answers while sign-in is off.
