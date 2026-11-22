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
- This report: the commit after `ab817b7`.

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
