# Report 0038: Sign-in codes and answers (failure-catalog fix 3)

Date: 2026-11-21
Branch: `fix/sign-in` (from `release/vero-r7` at `7d90f9f`, after `fix/startup` was merged)
Outcome: DONE

## What was done

Backend only. No schema change, and no dependency added or upgraded.

1. **AUT-001 to AUT-009 and ROL-006 are sent.** Each has its fixed sentence, the one error body with the reference, and one log line with the exact cause:
   - INFO for expected refusals: AUT-001, 002, 003, 007, 008 and ROL-006; also ROL-004 and ROL-005.
   - WARN where a person must act: AUT-005, 006, 009, SYS-006 and IDP-007 at save.
   - ERROR for the 5xx answers: AUT-004 and IDP-007 at sign-in.

   How it works:
   - The security filters put a `SignInAnswer` on the request. Vero's `/error` page writes the body and the log line.
   - `SignInAnswers` covers every sign-in mode. Refusals inside Vero's code use `CodedRefusal` and a new log detail, which is never sent.
   - ROL-006 names the action in plain words, for example "Your role doesn't include managing legal holds."
   - ROL-004 and ROL-005 now have a body too. Before, the code was only in the 401's description.
2. **Wrong kind of sign-in: 403 AUT-002** (it was 401). A person on `/api/v1/ingestion/…` gets "This address is for sending systems only." A sending system on a person's address gets "This address is for people, not sending systems."
3. **Unknown and suspended tenant: one answer.** It is 403 AUT-009, "Your organisation's Vero account isn't available. Contact your IT team." It used to be 404 or 409. One check, `ActiveTenant.require`, replaces the 11 tenant checks the catalog lists. The WARN line keeps the tenant ID and "missing" or "suspended (…)". Redaction and the retention policy used to check only that the tenant exists; they now also refuse a suspended tenant.
4. **Sign-in switched off: 401 SDV-SYS-006**, "Sign-in is switched off for Vero, so nobody can sign in yet. Your IT team has been told."
5. **Sign-in hub keys:**
   - **Confirmed by test:** Spring rethrows the failure to fetch the keys (`AuthenticationServiceException`). Vero answered it 500 SDV-SYS-013.
   - **Now:** a sign-in while the keys can't be fetched gets 503 AUT-004, with an ERROR line that names the key set's host. The parts that do this are `SignInKeysAwareJwtDecoder` and the bearer filter's failure handler.
   - **Readiness:** the new `signInKeys` check (enterprise-jwt only) makes readiness 503 while the keys can't be fetched. It writes one WARN when they go and one INFO when they come back.
6. **Two active connections for one issuer (IDP-007), confirmed by test:**
   - Before: the repository's `IllegalStateException` left Spring's chain, so every sign-in for that issuer got 500 SYS-013.
   - The database's unique index `uq_sdv_eic_active_binding` already refuses a second ACTIVE row (PostgreSQL test). An activation it stopped would have been 503 SYS-014.
   - Now, when Tenant IT saves, activates or rolls back to a connection that another tenant already has ACTIVE for the same provider, issuer and audience, Vero refuses with 409 IDP-007. Nothing is written. The answer names no other tenant; the WARN line does.
   - If two ACTIVE rows are found at sign-in anyway, the answer is 500 IDP-007 with its sentence.
7. **No account or token content in answers.** The `WWW-Authenticate` description is the code only; Spring's text can hold the token's expiry. Log lines name a claim, never its value. AUT-007 logs no subject or groups.
8. **Catalog:** the 11 codes (AUT-001 to 009, ROL-006, IDP-007) have released `###` sections, and their proposals are marked "released in task 0038". ROL-004, ROL-005 and SYS-006 are updated, and `AUT` and `IDP` are added to the list of areas.

**Test tools changed (please check):**
- `Test-SDV-CleanInstall.ps1`: its sign-in keys address was an `.invalid` host, so readiness could never pass now. It now runs a stand-in key set, `test-tools/KeySetServer.java`, on loopback, with a test key and token made for the run and deleted at the end. New steps 6a and 6b take the keys away and bring them back.
- `Test-SDV-SchemaUpgrade.ps1`: also runs the new `SignInConnectionPostgresTest`.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test -fn` (in `backend`), first run | 5 failures, all caused by the change; see the next section |
| `mvn -o test -Dtest=SignInChainTest,SignInKeysDownTest` | 1 failure: the group mapper still threw its own uncoded refusal, so no role came back as AUT-003. Fixed (AUT-007); then 11 of 11 passed. |
| `mvn -o test -fn`, final | 483 tests, 0 failures, 6 skipped |
| `.\Stop-SDV-Local.ps1` | Backend and UI stopped; sdv-postgres and sdv-vault left running |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS.** Backend 483 tests (24 + 50 + 2 + 4 + 403), 0 failures, 6 skipped (PostgreSQL-only). Frontend build passed. |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 101 s, first run. 6a: readiness 503, liveness 200, signed-in request 503 AUT-004 with reference, no token 401 AUT-001, WARN and ERROR lines with the host. 6b: ready again in the same process, the token is checked and gets 401 AUT-006 (no connection set up), no token content in answers or log. No connection outside this computer. |
| `.\Test-SDV-SchemaUpgrade.ps1` | **PASS**, 46 s. All six PostgreSQL tests passed, including `SignInConnectionPostgresTest`. |
| `.\Start-SDV-Local.ps1` | Readiness 200 after about 20 s |
| `npm run ui:capture` | **Passed**: 25 passed, 11 skipped (as in reports 0035 to 0037) |
| `curl` against the local app | No sign-in: 401 AUT-001. Bad tenant header: 401 AUT-008 naming the header. Business User on Operations: 403 ROL-006 "…seeing Operations and technical problems…". Person on intake: 403 AUT-002. Unknown tenant: 403 AUT-009; the known tenant: 200. |

## Tests: counts, and any failures with their output

**Tests changed to match the new answers (said here as the rules require):**
- `StandardLimitsTest.anInactiveOrUnknownTenantIsRefused` expected `IllegalStateException` and `NoSuchElementException`. It now expects AUT-009 for both, with the same code and message.
- `DocumentLevelEnforcementTest` recognised an allowed redaction by "Tenant not found." It now recognises AUT-009.
- `ErrorPlumbingTest`: a bare 401 or 403 on the error page now expects AUT-001 and ROL-006 (they were `UNAUTHORIZED` and `FORBIDDEN`). The bearer-header test uses Vero's entry point instead of the removed `withErrorBody`.
- `ErrorCodesDocumentedTest` failed until the catalog sections were written. It was not changed.

**New tests (27 in the normal build, plus 1 PostgreSQL test):**
- `SignInChainTest` (8): Vero's real chain with real signed tokens and a loopback hub.
  - A good token: 200.
  - No token: AUT-001 with `WWW-Authenticate: Bearer`.
  - Expired, wrong audience, wrong issuer, forged and garbage tokens: AUT-003, with the reason class only.
  - AUT-005 names the claim. AUT-006 gives the issuer host. AUT-007 names no group.
  - AUT-002 both ways.
  - IDP-007: before (500 SYS-013) and now (500 IDP-007).
  - After every test, no body, header or log line holds the subject, a group, the name, the token or "expired at".
- `SignInKeysDownTest` (3):
  - Spring on its own rethrows the key-fetch failure (confirmed).
  - Vero answers 503 AUT-004, with an ERROR line naming the host and no token.
  - A request without a token still gets AUT-001.
- `SignInCodesTest` (9):
  - Sign-in off: SYS-006.
  - AUT-008 names the header, never the value. ROL-004 has its sentence.
  - The filters' 403 is ROL-006. ROL-006 in plain words, with the capability in the log. Every capability has plain words.
  - AUT-001 at a controller.
  - **Unknown and suspended tenant give the identical code and message**, apart from the reference and the time. The WARN lines say "missing" and "suspended".
- `SignInKeysHealthIndicatorTest` (5):
  - Against a real loopback key set: up, then down (HTTP 503: code, host, reason), then up, with one WARN and one INFO.
  - Unreachable, not a key set, or no address set: down.
  - A hub that doesn't answer: down within the wait.
  - Other sign-in modes: up.
- `SecondActiveConnectionRefusedTest` (2): saving a draft is refused with IDP-007 and nothing is written; two rows at sign-in give IDP-007 and the log names both tenants.
- `SignInConnectionPostgresTest` (1, run by the schema-upgrade check):
  - The unique index refuses a direct second ACTIVE row.
  - Activation is refused and nothing changes.
  - Saving is refused and nothing is written.
  - Another audience, or a tenant's own new version, is allowed.

## Files changed

- Domain: `SdvErrorCode` (11 codes, plus a flag for INFO logging), `CodedRefusal` (log detail; `Unauthorized`), `InternalCheckFailed` (IDP-007).
- Application: new `access/ActiveTenant`. The tenant checks in `BusinessExperienceService`, `DocumentIntakeService`, `MetadataFieldAdminService`, `OperationsService`, `CorrectiveRedactionService`, `RetentionPolicyService`, `IngestionSourceAdminService`, `StorageVolumeAdminService` and `SubmissionService` now use it.
- Security:
  - New: `SignInAnswer`, `CapabilityDenied`, `config/SignInAnswers`, `enterprise/SignInRefusedException`, `SignInKeysAwareJwtDecoder`, `SignInKeysUnavailableException`.
  - Changed: `SecurityConfiguration`, `SecurityContextFacade`, `AuthorizationService`, `LocalHeaderAuthenticationFilter`, `RoleSeparationGuard`, `EnterpriseJwtAuthenticationConverter`, `EnterpriseJwtConfiguration`, `SdvGroupRoleMapper`.
- Persistence: `JdbcOidcConnectionBindingRepository`.
- Web: `ErrorReplies`, `FoundationExceptionHandler`, `SdvErrorController`, new `security/SignInKeysHealthIndicator`; `application.yml` (readiness includes `signInKeys`).
- Tests: the 6 new test classes above, the 3 changed ones, and the shared `SignInTestKit`.
- Scripts and tools: `Test-SDV-CleanInstall.ps1`, `Test-SDV-SchemaUpgrade.ps1`, new `test-tools/KeySetServer.java`.
- Docs: `docs/api/error-codes.md`, this report and the task file.
- Not touched: `Start-SDV-Local.ps1` (its local changes stay uncommitted).

## Commits (hash and message), or "not committed"

See the commit on `fix/sign-in` with the message "fix: sign-in codes and answers…". Its hash is in the follow-up commit. Nothing was pushed or merged.

## Not done, and why

- **No central tenant check per request.** AUT-009 is sent where Vero already looked up the tenant (the 11 sites the catalog lists). Addresses that never look up the tenant don't refuse a suspended tenant; that was true before too. A check for every request would add one database read to each request. See question 3.
- **The sign-in recovery and the connection bootstrap at start** don't check for another tenant's ACTIVE connection. If one exists, the unique index still stops the write, and the start reports SYS-011 or SYS-012 as before.
- **Two tenants activating the same issuer at the same moment:** the second is stopped by the unique index and also gets IDP-007, but no test covers this race (it would need two threads on PostgreSQL).

## Questions for the owner

1. **Log levels.** The task said INFO or WARN. The catalog proposed DEBUG for AUT-001 and AUT-008, and ERROR for AUT-004. I followed the task (AUT-001 and AUT-008 at INFO) and kept ERROR for the two 5xx answers (AUT-004, IDP-007 at sign-in), as every 5xx in Vero does. In OIDC mode, AUT-001 at INFO is one line for every request that arrives without a sign-in. Keep it, or move it to DEBUG?
2. **AUT-007 logs no subject.** The catalog proposed logging the subject, but the task says no token content in any log line. The INFO line has only the reference. Should IT be able to see the subject?
3. **Should every request check that the tenant is active** (one read per request), so that a suspended tenant is refused everywhere?
4. **Overall `/actuator/health`** now also includes the sign-in keys check, as it does the database ping, so it is DOWN while the hub is away. OK?

## Suggested next step

Review and merge `fix/sign-in`. Then add the new 401, 403 and 503 codes to the shared delivery contract: sending systems will now see 403 AUT-002 and AUT-009 instead of 401, 404 or 409, and 503 AUT-004. Then do the next fix task from the failure catalog.
