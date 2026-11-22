# Report 0040: Ingestion API contract for sending systems

Date: 2026-11-22
Branch: `docs/ingestion-contract` (from `release/vero-r7` at `f4c5c6e`)
Outcome: DONE (Part 1 as written when the session began). Part 2 was added to the task file during the session and is not done; see "Questions for the owner".

## Carry-over from report 0039 Part 2

1. **Bounds on the unidentified-attempts table.** All three stay. The 100 is now a setting: `SDV_UNIDENTIFIED_ATTEMPTS_MAX_ROWS_PER_MINUTE` (`sdv.problems.unidentified-attempts.max-rows-per-minute`), a whole number from 1 to 10000, default 100. Anything else stops the start with SDV-SYS-005, the same code `SDV_TRUSTED_PROXIES` uses. The runbook has a new section, "How unidentified attempts are counted": one row per minute, IDs blanked to `{id}`, `OTHER` for unknown methods, the "(many addresses)" row, and the setting.
2. **`SDV_TRUSTED_PROXIES` takes ranges.** IPv4 and IPv6 CIDR ranges (`10.0.4.0/24`, `2001:db8:4::/64`), mixed with single addresses. A bad entry still stops the start with SDV-SYS-005. Bad entries are:
   - a prefix that is too large or not a plain number;
   - `/0` (it would trust every caller);
   - a range whose address has bits set past the prefix (`10.0.4.5/24`; refused rather than guessed);
   - an IPv4 address in IPv6 form with a prefix;
   - as before, host names, ports and brackets.

   The runbook section is updated, and so is the SDV-SYS-005 entry in the catalog.
3. **UI server on 5183.** It did not stop during this task. I ran no screen tour (nothing in the UI changed). The UI answered 200 after the final restart.

No schema change, no dependency, no file deleted.

## What was done

**The contract, `docs/api/sdv-ingestion-v1.openapi.json` (version 1.0.0):**
- **Operations:** all six ingestion operations, plus `GET /actuator/health/readiness` as the "can I send now?" check: no sign-in; 200 `UP` means send; 503 means wait, and is never a refusal.
- **Headers and fields:** every header (`X-SDV-Filename`, `X-SDV-Source-Document-Id`, `Idempotency-Key`, `X-SDV-Sensitivity-Level`, the refused `X-SDV-Record-Sensitivity-Level`, `X-Correlation-Id`) and every field, including `recordSensitivityLevel`.
- **Answers:** each answer lists its codes (`x-sdv-codes`), and each status-and-code pair has a retry rule (`x-sdv-answers`). The rules are `yes`, `new-token`, `no` and `after-fix` (`x-sdv-retry`).
- **Sign-in:** `bearerAuth` in full: client-credentials tokens, the checks Vero makes (signature, iss, aud, exp and nbf, optional lifetime), the required claims (`sub`, tenant-ID claim, source-ID claim, no groups claim), and what each missing or wrong claim returns (`x-sdv-required-claims`, `x-sdv-sign-in-answers`).
- **Error body:** `{code, message, reference, timestamp}`.

**Corrections to the old file.** In each case Vero behaves differently from what the old file said:
- `metadata` is optional (it was marked required).
- Unknown submission fields are ignored, not refused. The old file said `additionalProperties: false`.
- A malformed `Authorization` header gets 401 SDV-AUT-003, not 400. The catalog's AUT-003 entry is corrected.
- 406 is decided only when Vero writes the answer, so the work may already be done. The contract says: send again with `Accept: application/json`, and the retry gets 200.
- The upload's 409 can carry the generic `CONFLICT` (question 2).
- Every answer field is always sent, and the file now marks all of them `required`.
- `DocumentState` lists all eight states.
- Vero never sends 429 and never sends `Retry-After`. The file says that a gateway in front may send 429, and that it means retry.

The portable JSON Schema matches the OpenAPI file again (`metadata` optional; metadata values are string, number or boolean).

**`docs/api/README.md`:**
- **Versioning policy.** `/v1` stays backward compatible. Allowed within v1: new optional fields, answer fields, codes and state values. Anything that breaks a sender needs `/v2` and a notice period. `info.version` follows semantic versioning.
- **Rules that don't change.**
- **Sender's guide:** client setup, readiness, a worked example (token, one submission, two documents), how to read each answer, and the table of every code a sender can get.
- **"Planned change" note** for the platform-core switch-over, as decided.
- **Changelog** with the 1.0.0 entry and its tag, `sdv-ingestion-v1.0.0`.

**Contract test, `IngestionContractTest`** (8 tests, in the normal build):
- **Stack:** Vero's real security chain (enterprise-jwt, real signed tokens, a loopback key server), the schema gate, and the real ingestion controllers and services. Only the repositories, storage and crypto are stand-ins.
- **Structure:** the file's paths and methods equal the controllers' `/api/v1/ingestion` mappings (found by scanning). Headers, query and path parameters (with "required") and content types equal each handler's. Request and answer fields equal the Java records' fields. Enums equal the domain enums. The JSON Schema has the same fields as the OpenAPI file.
- **Answers:** 163 scenarios. Every status and code the file lists for an operation must be produced by the stack, and every answer produced must be listed. Error bodies must have exactly the four fields, and `reference` must equal `X-Correlation-Id`. Success bodies must match their schema.
- **Catalog:** every code is a product code listed in `error-codes.md` (apart from the one named `CONFLICT`), every pair has a retry rule, and no catalog entry is unused.
- **Version:** `info.version` is semantic and is the newest changelog entry, which names its tag.
- **Proof it catches drift:** I changed the file on purpose twice (a 413 code swapped for another; an extra header added). The test failed on both, then I restored the file and it passed.
- **Two codes come from settings this one stack can't switch to:** SYS-006 (sign-in switched off) runs through the real "disabled" entry point and error page, as `SignInCodesTest` does. AUT-004 runs through Vero's real decoder pointed at a closed port.

**Live check with a machine token:** see the next section.

## Live check: how the Keycloak client was set up

- **Keycloak:** `lexa-keycloak` and `lexa-keycloak-db` were stopped. I started both with `docker start` (no recreate, nothing else changed). Realm `document-platform-local`, issuer `http://localhost:8181/realms/document-platform-local`.
- **Admin access:** `kcadm.sh` inside the container, signed in with the container's own bootstrap admin environment variables (never printed). The session file in the container's `/tmp` was deleted afterwards.
- **New client, `sdv-0040-contract-test-ingestion`** (internal ID `6f12be26-de54-42f7-b97b-78f50cc10a87`), named "SDV task 0040 contract test (machine token) - safe to delete":
  - confidential, client-secret authentication;
  - service accounts on; standard flow, implicit flow and direct access grants off;
  - access token lifespan 300 s.
- **Its three mappers:**
  - hard-coded claim `sdv_tenant_id` = `11111111-1111-1111-1111-111111111111` (tenant `local-demo`; string; access token only);
  - hard-coded claim `sdv_source_id` = `a69d1359-8b48-4ba0-b772-664d8745ccd7` (Vero's sending system `lexaVault`, ACTIVE);
  - audience mapper, custom audience `sdv-api` (access token).
- **Nothing else in Keycloak was changed.** The realm's default scopes also add `lexavault-api` to `aud`; Vero accepts that, because `aud` only has to contain `sdv-api`.
- **The token held:** `iss` as above; `aud` = `lexavault-api, sdv-api, account`; `azp` and `client_id` = the client; `sub` present; both sdv claims; lifetime 300 s; no `groups` claim. The secret and the token were kept in variables and never printed.
- **To remove the client:** Keycloak admin console → realm `document-platform-local` → Clients → `sdv-0040-contract-test-ingestion` → Delete. Or `kcadm.sh delete clients/6f12be26-de54-42f7-b97b-78f50cc10a87 -r document-platform-local`. Its service-account user is removed with it.

**How Vero was started.** Not with `Start-SDV-OIDC.ps1` itself, because:
- its `-WithVault` path runs `docker compose -f compose.local.yml up -d sdv-vault` and sets a dev root token. The running `sdv-vault` is the persistent one, so compose would likely recreate it as a dev Vault, which CLAUDE.md forbids;
- without `-WithVault`, encryption is off, so the document can't be stored.

So I used a one-off script (in the session scratchpad, not committed). It sets exactly the OIDC script's sign-in variables (enterprise-jwt, issuer and JWKS of the local realm, audience `sdv-api`, `SDV_AUTH_JWT_TENANT_ID`, persistent connection on, the group mappings). It passes the persistent Vault as `Start-SDV-Local.ps1` does: the token file's path only, which the backend reads. Same jar, same window title and PID file, so `Stop-SDV-Local.ps1` stopped it.

**Results:**

| Step | Answer |
|---|---|
| `GET /actuator/health/readiness`, no token | 200 `{"status":"UP"}` |
| Create submission `task0040-live-20261122102238` (1 expected document, the 3 required fields) | 201, `submissionId 8484ce74-24f5-40d8-be52-7ca23f56b2b3`, status OPEN |
| The same create again | 200, same ID, `idempotentReplay: true` |
| Upload `doc-1` (a one-page PDF, `CONFIDENTIAL`) | 201, `documentId 81fab2ab-6636-48b2-ae4b-03a033bd1e40`, RECEIVED, AVAILABLE, CONFIDENTIAL |
| The same upload again | 200, same document, `idempotentReplay: true` |
| Read the submission | 200, COMPLETE, the document AVAILABLE |
| No token | 401 SDV-AUT-001 |
| Token with a broken signature | 401 SDV-AUT-003 |

**Left in the dev database `sdv`:**
- that submission and its document (tenant `local-demo`, sending system `lexaVault`);
- one row in `enterprise_identity_connections`, which the start's bootstrap wrote, as `Start-SDV-OIDC.ps1` would: ACTIVE, tenant `local-demo`, issuer the local realm, audience `sdv-api`, browser client `sdv-web`, created 2026-11-22 15:22 UTC (question 3).

## Commands run and results

| Command | Result |
|---|---|
| `git checkout -b docs/ingestion-contract f4c5c6e`; commit `0040-task.md` | `d881d93` |
| `.\Stop-SDV-Local.ps1` (the app was running) | Stopped |
| `mvn -o test` `CallerAddressTest`, `UnidentifiedAttemptLimitSettingTest`, `IntakeRefusalsTest` | First run: 3 errors, `NoClassDefFound ActiveTenantFilter`. A stale `sdv-security` jar in the local Maven repository (I had installed only two modules), not a code fault. After `mvn -o install -DskipTests`: 26 passed |
| `mvn -o test` `IngestionContractTest`, runs 1 to 3 | Failures that were mine: test stand-ins (a created submission wasn't found again; data for two 406 cases), Windows line endings, query names lower-cased. And real findings: the malformed header is 401, not 400; 406 comes after the work. The file and the catalog were corrected to match the API |
| `mvn -o test` `IngestionContractTest`, `ErrorCodesDocumentedTest` | 11 passed |
| The same, with the file changed on purpose twice | 3 failures, as intended; file restored (hash equal) |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS.** Backend 540 tests (24 + 50 + 2 + 4 + 460), 0 failures, 8 skipped (PostgreSQL-only). Frontend build passed |
| `docker start lexa-keycloak-db`, `docker start lexa-keycloak` | Up; realm answers |
| Live check (above) | All as expected |
| `.\Stop-SDV-Local.ps1`, `.\Start-SDV-Local.ps1` | Normal local app back; readiness 200, UI 200 |

`Test-SDV-SchemaUpgrade.ps1` was not run. Only its PostgreSQL test changed (`UnidentifiedIntakeAttemptPostgresTest`, part 3b), and that test is skipped in the normal build (question 4).

## Tests: counts, and any failures with their output

540 in the build, 0 failures, 8 skipped (526 in report 0039 Part 2).

**New tests (14):**
- `IngestionContractTest` (8);
- `CallerAddressTest` (+3): in range, out of range, bad ranges (17 bad forms);
- `UnidentifiedAttemptLimitSettingTest` (3).

Plus one new part (3b) in `UnidentifiedIntakeAttemptPostgresTest`: with the limit at 3, five callers in a minute make 3 rows and one shared row of 2.

**Tests changed (stated as the rules require):**
- `CallerAddressTest`: `10.0.0.0/8` is no longer in the list of entries that stop the start, because a range is now valid.
- `UnidentifiedIntakeAttemptPostgresTest`: `MAX_ROWS_PER_MINUTE` renamed `DEFAULT_MAX_ROWS_PER_MINUTE`.

## Files changed

- **Code:** `CallerAddress.java`, `UnidentifiedIntakeAttemptRepository.java`, `JdbcUnidentifiedIntakeAttemptRepository.java`, `application.yml` (the new setting).
- **Tests:** `IngestionContractTest.java` (new), `UnidentifiedAttemptLimitSettingTest.java` (new), `CallerAddressTest.java`, `UnidentifiedIntakeAttemptPostgresTest.java`.
- **Docs:** `docs/api/sdv-ingestion-v1.openapi.json`, `docs/api/README.md`, `docs/api/schemas/sdv-ingestion-submission-v1.schema.json`, `docs/api/error-codes.md` (SYS-005, AUT-003, intake intro), `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`, this report.
- **Not touched:** `Start-SDV-Local.ps1`, `Start-SDV-OIDC.ps1`.
- **Not committed:** `docs/work/0040-task.md` (its Part 2 was added during the session) and `docs/work/0041-task.md`. Both were written by someone else while I worked.

## Commits (hash and message), or "not committed"

- `d881d93` docs(work): task 0040 ingestion API contract
- `d212d76` fix: trusted proxies accept CIDR ranges, unidentified-attempt row limit is a setting (task 0040 carry-over from 0039 part 2)
- `ab817b7` docs(api): ingestion contract 1.0.0 with every answer and retry rule, sender's guide, versioning, contract test (task 0040)
- This report: `a6d3be2` docs(work): report 0040, plus a follow-up commit that writes this hash in.

`error-codes.md` is in the second (contract) commit, though its SYS-005 and intake-intro lines belong to the carry-over.

Nothing pushed, merged or tagged. The tag `sdv-ingestion-v1.0.0` goes on the merge commit, as decided.

## Not done, and why

- **Part 2 of the task** (the busy answer with `Retry-After`, spec changes, the planned-change wording) appeared in the task file during this session. It changes product behaviour, which Part 1 rules out, and you didn't ask for it in this session.
- **`Start-SDV-OIDC.ps1` was not run as written.** Its Vault path could recreate the persistent Vault; see the live check.
- **No product behaviour was changed** for the uncoded `CONFLICT` or for unknown request fields. Both are documented as they are.

## Questions for the owner

1. **Part 2 of 0040:** do it now, on this branch, before the merge and tag? It needs a backend change, a new SYS code, and a new contract version entry.
2. **The upload's `409 CONFLICT`:** a storage volume whose provider isn't in the install answers 409 with the generic code and a message naming the provider. That is Vero's setup, not the sender's. Give it a code, for example 503 STO-001 or 500 SYS-016, in a later task? The contract marks it "after-fix" until then.
3. **The sign-in connection row** the OIDC start wrote in `sdv` (ACTIVE, local realm, audience `sdv-api`): keep it, or remove it? Removing it is a data change, so I didn't.
4. **Test data:** keep or remove the live check's submission and document in `sdv`, and the Keycloak client? Also, should I run `Test-SDV-SchemaUpgrade.ps1` for the new PostgreSQL part 3b before the merge?
5. **`Start-SDV-OIDC.ps1`** still assumes a dev Vault. Bring it in line with the persistent-Vault handling of `Start-SDV-Local.ps1` (a script change, in a later task)?

## Suggested next step

Review `docs/ingestion-contract` and answer question 1. If Part 2 is wanted, do it on this branch. Then merge, and tag the merge commit `sdv-ingestion-v1.0.0`.

---

## Part 2 (2026-11-22): busy answer, upload's 409, planned-change note

Outcome: **DONE.** The busy answer, the upload's STO-004, the contract and the docs; then the owner's answers to this part (items 3 and 4: the `Retry-After` setting, WARN logging and a busy count in Problems) and the second round (the tour chooses the Loan Origination System itself). Full build passed, and the screen tour passed (25 passed, 0 failed) on the last run. Committed; nothing pushed, merged or tagged.

### The places Vero can be busy today

| Place | Before | Now |
|---|---|---|
| Vero's database connection pool is full: HikariCP waited its full `connectionTimeout` (30 s by default) and no new connection had failed | 503 SDV-SYS-014 ("can't reach its database") | **503 SDV-SYS-018 + `Retry-After: 5`** |
| PostgreSQL refuses a new connection because it has none left (SQL state 53300, too_many_connections) | 503 SDV-SYS-014 | **503 SDV-SYS-018 + `Retry-After: 5`** |
| Intake | No intake pool or limit of its own exists in Vero | nothing to change |
| Tomcat's worker threads are all busy | Connections wait in the TCP queue; Vero never gets to answer | nothing Vero can answer |

How the two are told apart: when the database can't be reached, Hikari's timeout carries the last connection failure as its cause, and that stays SYS-014. A full pool has no cause. `BusyAnswerTest` proves this against a real HikariCP pool.

### What was done

- **SDV-SYS-018 (busy):**
  - Message: "Vero is too busy to take this request just now. Try again in a few seconds."
  - The answer carries `Retry-After` in whole seconds. It is set where every error body is written (`ErrorReplies.write`), so it is the same whether a controller, a filter or the error page answers.
  - No other answer carries `Retry-After`. Vero never sends 429.
- **`Retry-After` is a setting (owner answer 3):** `SDV_BUSY_RETRY_AFTER_SECONDS` (`sdv.busy.retry-after-seconds`), a whole number from 1 to 300, default 5. Anything else (empty, 0, 301, `5s`, `05`, `2.5`) stops the start with SDV-SYS-005, which names the setting. The pool's 30-second wait is not changed. The setting and the count live in `BusyAnswers` (application layer); `BusyAnswersInstaller` hands it to `ErrorReplies` at start. Documented in the install runbook ("When Vero is busy"), the catalog and the sender's guide; the OpenAPI `RetryAfter` header now says 1 to 300 (`maximum: 300`). The contract stays 1.0.0.
- **Logging and the busy count (owner answer 4):**
  - Each busy answer writes one **WARN** line (not ERROR) with the same fields: code, reference, status, route and the cause (exception classes and SQL state, never the SQL). Every other 5xx stays ERROR, also for coded answers (`logCoded`).
  - Each busy answer is counted in memory, per minute for the last hour, and since Vero started. Not in the database: a busy answer means no database connection is free. The count is for this Vero process and starts again at a restart.
  - The Problems summary (`GET /api/v1/operations/summary`, used by Platform operations and Tenant IT) has a new field `busyAnswers`: `lastHour`, `sinceStart`, `lastAt`, `retryAfterSeconds`. It is Vero-wide, not per tenant.
  - The Problems page shows a warning when there was at least one busy answer in the last hour: "Vero was too busy for N requests in the last hour", with what it means, what to do, the count since start and when the last one was. Hidden otherwise (second-round answer 2: keep it).
- **The tour no longer relies on the newest sender (second-round answer 1):**
  - The two failing steps now choose the Loan Origination System in Records' Filters panel (the **Source** picker), with a new tour helper `searchInSource`. The Sensitivity step (task 0029) searches `300000000003` there; it no longer sets the remembered choice in browser storage.
  - "Records starts with the loan system…" no longer asserts that the loan system delivered last. It chooses the loan system in Filters, checks that the choice is remembered, then checks 10 reloads: the chosen system is kept, and when the half-set-up system is remembered instead (it never qualifies), Records searches the active system that the search schema says delivered most recently, whichever that is. The simulated cases (LexaVault without records, the loan system paused, nothing searchable, nothing delivered) are unchanged.
  - No data was changed or deleted.
- **SDV-STO-004 (upload's 409):**
  - The upload's missing storage provider is now **503 SDV-STO-004**, retry rule `after-fix`, no `Retry-After`. Message: "Storage for this tenant isn't available, so nothing was stored. The installer must fix it." The provider ID goes in the ERROR log line only.
  - Why STO-004: the catalog had already reserved it for exactly this cause ("storage type not installed", proposed in task 0034). STO-002 and STO-003 are reserved for other causes, so the first number with this meaning is 004 (question 1).
  - The other places with the same cause (finishing, viewer, download, redaction, retention) keep their answers.
- **Contract (`sdv-ingestion-v1.openapi.json`, still 1.0.0):**
  - Every operation's 503 lists SDV-SYS-018 and declares a new `Retry-After` header (`components/headers/RetryAfter`).
  - The upload's 503 also lists SDV-STO-004. `CONFLICT` is gone from the 409, from `x-sdv-answers` and from the `Error.code` description.
  - The new answer `503 SDV-SYS-018` is `retry: yes` with `retryAfter: true`.
  - The `yes` rule now reads: wait the header's seconds when it is there, otherwise back off. The 429 entry says Vero's busy answer is 503 SDV-SYS-018.
- **Sender's guide (`docs/api/README.md`):**
  - The line "Vero sends no `Retry-After` header" is replaced by "The busy rule": Vero never sends 429; it sends `Retry-After` only with the busy code; every other 503 means unavailable, so retry with back-off.
  - The codes table has three 503 rows: busy, unavailable, and STO-004 (after-fix).
  - The "Planned change" note now says: the entity and sending system will be found from the client ID before the first customer install (task 0041), and `sdv_tenant_id` / `sdv_source_id` keep working until then.
  - The 1.0.0 changelog entry has both changes.
  - Old generic codes in the sensitivity section were corrected: `400 BAD_REQUEST` is now SEN-001 / SEN-002, and `409 CONFLICT` is now SEN-003 / SEN-004.
- **Catalog (`error-codes.md`):**
  - New sections SDV-SYS-018 and SDV-STO-004.
  - The SYS-014 meaning no longer says "ran out of connections".
  - The proposed STO-004 block is marked as released for the upload.
- **Contract test:**
  - Every operation now has a "too busy" scenario: 503 SDV-SYS-018.
  - The upload's provider scenario now expects 503 SDV-STO-004.
  - Every answer must carry `Retry-After` exactly when the file marks it `retryAfter`, and it must be a whole number above 0.
  - A new test checks:
    - only `503 SDV-SYS-018` is marked;
    - every 503 is `yes`, except STO-004 (`after-fix`);
    - every operation's 503 lists the busy code and declares the header;
    - there is no 429 entry;
    - the `yes` rule mentions `Retry-After`.
  - The "apart from `CONFLICT`" exception is removed.
- **Proof the test catches drift:** I removed `retryAfter` from the file on purpose, and the contract test failed twice (the busy rule, and all 6 busy scenarios). I restored the file; its hash is equal.

**Found, not changed:** readiness pings the database through the same pool. While the pool stays full for more than 2 s, readiness is 503 too, so a load balancer takes Vero out while it is busy. The contract and the catalog say so.

### Commands run and results

First session (before the owner's answers to this part):

| Command | Result |
|---|---|
| `git commit` `0040-task.md` (Part 2 and the owner's answers) | `2e9880b` |
| `.\Build-SDV-All.ps1` | BUILD SUCCESS, 549 backend tests, 0 failures, 8 skipped |
| `.\Test-SDV-SchemaUpgrade.ps1` (scratch DB `sdv_schema_test`, dropped at the end) | **PASS** in 44 s |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` | Not run by Claude (refused by the session's permission check); the owner ran it by hand |
| `npm run ui:capture` | 23 passed, 2 failed, 11 skipped |

After the owner's answers (items 3 and 4) and the second round:

| Command | Result |
|---|---|
| `.\stop-local.cmd` | Stopped |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS.** Backend 556 tests (24 + 50 + 2 + 4 + 476), 0 failures, 8 skipped. Frontend build (`tsc -b` and `vite build`) passed |
| `.\Start-SDV-Local.ps1` | Started; readiness 200, UI 200; ingestion without a token 401, no `Retry-After` |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` | Not run by Claude (the owner ran it before this session) |
| `npm run ui:capture` (before the tour fix) | 23 passed, **2 failed** (the same two steps), 11 skipped |
| `npm run typecheck` (after the tour fix) | Passed |
| `npm run ui:capture` (tour fix, first try) | 23 passed, 2 failed: the new helper looked the Source picker up by its label, and the label also holds the options' names. Fixed: it is found by role (`combobox "Source"`) |
| `npm run ui:capture` (second try) | 24 passed, **1 failed**, 11 skipped: "Platform Operations Administrator screens", the audit Export (below) |
| `npm run ui:capture` (third try, nothing changed) | **25 passed, 0 failed, 11 skipped** (5.8 min) |

**The 2 tour failures and their cause.** The newest LexaVault delivery (2026-11-22 15:22 UTC) is Part 1's live check (submission `task0040-live-20261122102238`), which the seed reset doesn't touch. Records' default is the active system that delivered most recently, so it opened on LexaVault. The fix is in the tour, not the data (second-round answer 1).

**The one-off Export failure.** On the second try, the Platform operations audit Export showed "The export didn't work" and no file came. The step passed in the three other runs that day, and on the next run with nothing changed. Afterwards, the same export through the API (record `300000000076`) answered 200 with the file. The unfiltered export now answers `422 EXPORT_TOO_LARGE`: the demo audit has more than 10,000 events, and each tour run adds some. The Audit page sends the record filter, so this limit shouldn't apply to the tour's export, and I couldn't see what the server answered that time (the backend logs to its console window only). Not changed; question 2 below.

The UI server on 5183 didn't stop during any tour.

### Tests

556 in the build, 0 failures, 8 skipped (540 in Part 1, 549 before the owner's answers to this part).

- **New:** `BusyAnswerTest` (10): a real HikariCP pool, full (busy), with a database that refuses connections (SYS-014, no header), and PostgreSQL 53300 (busy); a transaction start with a full pool; every other 503 without `Retry-After`; STO-004's message and log; the error page; the cause-chain rule; the setting is used (`Retry-After: 12`) and each busy answer counted, other 503s not; coded answers: busy WARN, other 5xx ERROR.
- **New:** `BusyAnswersTest` (4): default 5; 1 to 300 taken; anything else stops the start with SDV-SYS-005 naming `SDV_BUSY_RETRY_AFTER_SECONDS`; the count's last hour (per minute, a reused minute starts again) and since start, with a test clock.
- **New:** `ProblemsAndAuditRecordsTest.problemsCarryTheBusyAnswerCount`: the summary and its JSON carry the count; without a count it is none.
- **New:** `IngestionContractTest.theBusyAnswerIsTheOnlyOneWithRetryAfterAndEvery503IsRetry`, plus 6 busy scenarios (163 became 169).
- **Changed, as the rules require:**
  - The upload scenario "storage volume with no provider" now expects 503 SDV-STO-004; it was 409 `CONFLICT`. The catalog test no longer skips `CONFLICT`.
  - `BusyAnswerTest`'s full-pool test expected one ERROR line; it now expects one WARN line and no ERROR (owner answer 4).
  - The screen tour's two steps, as described above.

### Files changed

- **Code:** `SdvErrorCode.java` (SYS_018, STO_004), `ErrorReplies.java`, `DocumentIntakeService.java`, `StorageProviderMissingException.java` (new), `BusyAnswers.java` (new), `BusyAnswersInstaller.java` (new), `OperationsService.java` (the `busyAnswers` field), `application.yml` (`sdv.busy.retry-after-seconds`).
- **UI:** `Problems.tsx` (the busy warning), `liveApi.ts` (its type).
- **Tests:** `BusyAnswerTest.java` (new), `BusyAnswersTest.java` (new), `ProblemsAndAuditRecordsTest.java`, `IngestionContractTest.java`, `e2e/sdv-screen-tour.spec.ts`.
- **Docs:** `docs/api/sdv-ingestion-v1.openapi.json`, `docs/api/README.md`, `docs/api/error-codes.md`, `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`, this report.
- **Not committed:** `Start-SDV-Local.ps1` (its earlier local changes), `docs/work/0041-task.md` (as asked), `docs/work/0040-task.md` (the owner's new sections, see question 1).
- No schema change, no new dependency, no data deleted.

### Third round (owner answers, third round): readiness busy, the tour's server answers

**Item 2: readiness when Vero is busy** (the master section's item 2), commit `4653d7f`:

- The database ping (`databaseConnection`) now reports a new readiness-only status, `BUSY`, when it finds no free connection:
  - the ping got no answer in 2 s while Vero's HikariCP pool is full (every connection it may open in use, and someone waiting); or
  - the connection failed as busy, by the same rule as the request answers (PostgreSQL 53300, or the pool's own timeout with no cause).
  - Every other failure stays `NOT_READY`, as before. A database that can't be reached doesn't look full: its connections fail and leave the pool.
- Readiness orders `busy` after `not-ready` and maps it to 503, so readiness says `BUSY` only when busy is the one reason. With the hub also away it is `NOT_READY`; with an older database it is `OUT_OF_SERVICE`. Liveness and the overall `/actuator/health` ignore it, as they do `NOT_READY`.
- A new filter, `ReadinessBusyAnswer`, only on `/actuator/health/readiness`: when Spring's answer is 503 with status `BUSY`, it answers `503 {"status":"BUSY","code":"SDV-SYS-018","message":"Vero is too busy…"}` with `Retry-After` (the same setting, `SDV_BUSY_RETRY_AFTER_SECONDS`). Every other readiness answer passes through unchanged: no code, no `Retry-After`. A body it can't read is treated as not busy (fail closed). A probe isn't a refused request, so it isn't counted in Problems' busy count.
- Contract (still 1.0.0):
  - The readiness 503 lists `SDV-SYS-018` (`x-sdv-codes`), declares the `Retry-After` header, and has two examples, busy and unavailable.
  - The `Readiness` body has `code` and `message` (only when busy). `BUSY` is among the documented statuses.
  - The `RetryAfter` header says it comes from readiness too.
- Sender's guide: the readiness section is now a table: busy (wait the header's seconds) and any other 503 (back off). The 1.0.0 changelog says readiness follows the busy rule. Catalog SDV-SYS-018: the readiness answer described.
- Tests:
  - `IngestionContractTest.readinessWhenBusyIsTheBusyAnswerAndOtherwiseHasNoCode` (new) checks the file, then runs Vero's readiness filter over Spring's answers. `BUSY` gets the code and a whole-number `Retry-After`. `NOT_READY`, `OUT_OF_SERVICE`, `DOWN`, a body that isn't JSON, and `UP` are unchanged, with no header.
  - `HealthGroupsTest.busyIsReadinessOnlyAndOnlyWhenItIsTheOneReason` (new).
  - `DatabaseConnectionHealthIndicatorTest`, 2 new tests: busy and not-ready cases with stand-ins; `poolFull` against a real HikariCP pool (full only while every connection is in use and one caller waits; a non-Hikari data source is never full).
  - **Changed test, said plainly:** `DatabaseConnectionHealthIndicatorTest.readinessTurnsNotReady…` modelled "database down" as a pool timeout with no cause. Under the busy rule that shape means a full pool, so the test would now get `BUSY`. It now throws what Hikari really throws for a database that can't be reached: the timeout with the connection failure as its cause (shown against a real pool in `BusyAnswerTest`). It still expects `NOT_READY`.
- Live: after the start, readiness through the new filter is 200 `{"status":"UP"}` with no `Retry-After`. The busy case can't be made on the shared dev database without filling its pool, so it is proven by the tests above.

**Item 3: the tour records the server's answers**, commit `2bb8138` (the tour file only):

- During each tour step, every error answer (4xx or 5xx) from `/api/` that the page gets is kept: time, method, address, status, and from the error body `code` and `reference` (or the `X-Correlation-Id` header when there is no JSON body). At most the last 20 are kept, and nothing else of the body.
- When a step fails, they are printed in the test output and attached to the report as `server-error-answers`, one line each: `… GET /api/v1/operations/audit-events/export -> 422 code=EXPORT_TOO_LARGE reference=…`. The reference is the one on Vero's log line.
- It covers the page's own requests (what a person's click sends). The tour's direct API calls (`page.request`) already fail with their own message.
- No step failed in the run below, so nothing was printed: the recording hasn't been seen on a real failure yet.

**Commands run (third round)**

| Command | Result |
|---|---|
| `mvn -o test -pl sdv-web -Dtest=IngestionContractTest,HealthGroupsTest,DatabaseConnectionHealthIndicatorTest,BusyAnswerTest,ErrorCodesDocumentedTest` | 32 passed |
| `.\stop-local.cmd` | Stopped |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS.** Backend 560 tests (24 + 50 + 2 + 4 + 480), 0 failures, 8 skipped (556 before). Frontend build passed |
| `.\Start-SDV-Local.ps1` | Started; readiness 200 `{"status":"UP"}`, no `Retry-After`; UI 200 |
| `npm run ui:capture` | **25 passed, 0 failed, 11 skipped** (5.7 min) |

Files: `ReadinessOnly.java`, `DatabaseConnectionHealthIndicator.java`, `ReadinessBusyAnswer.java` (new), `ErrorReplies.java` (`SYS_018_MESSAGE`, `isBusy` and `busyAnswers()` made public for the filter and the ping), `application.yml`, `DatabaseConnectionHealthIndicatorTest.java`, `HealthGroupsTest.java`, `IngestionContractTest.java`, `sdv-ingestion-v1.openapi.json`, `README.md`, `error-codes.md`; `e2e/sdv-screen-tour.spec.ts`. No schema change, no new dependency (HikariCP was already in the app).

### Commits

- `2e9880b` docs(work): task 0040 Part 2 and owner answers to report 0040
- `76307d6` feat: busy answer 503 SDV-SYS-018 with Retry-After setting, upload SDV-STO-004, busy count in Problems (task 0040 part 2)
- `55d525f` docs(work): commit hash in report 0040 part 2
- `4653d7f` feat: readiness answers 503 BUSY with SDV-SYS-018 and Retry-After when Vero is busy (task 0040 part 2)
- `2bb8138` test(ui): the screen tour records Vero's error answers and prints them when a step fails (task 0040)
- The next commit adds this section.

Not committed: `Start-SDV-Local.ps1` (its earlier local changes), `docs/work/0041-task.md` (as asked), `docs/work/0040-task.md` (the owner's sections from this session). Nothing pushed, merged or tagged.

### Questions for the owner

Questions 1 and 2 of the second round are answered (third round, items 2 and 3). None new.

### Suggested next step

Rerun `.\Test-SDV-SchemaUpgrade.ps1` (last run before the third round; no schema change since), then merge and tag the merge commit `sdv-ingestion-v1.0.0`.
