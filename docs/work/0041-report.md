# Report 0041: Identity on platform-core, client-ID lookup, independence clean-up, sender profile (Part B)

Date: 2026-11-22
Branch: `feature/platform-identity` (from `release/vero-r7`; `release/vero-r7` at `8ac0ead` merged in, a merge, no rewrite)
Outcome: DONE (merge and tag: see "Commits").

## What was done

Followed `0041-task.md` (with its Notes and Update sections), `0041-plan.md` and `0041-owner-answers.md`; where they differ, the answers.

**1. Identity on platform-core v0.2.0**
- New dependencies, as approved (answer 2): `platform-identity-jdbc` (brings `platform-licensing`, read only), `platform-identity-testkit` (test), `platform-setup` (setup jar only), `flyway-core` + `flyway-database-postgresql` (schema step only; `spring.flyway.enabled=false`). Test scope only (answer 16): `platform-integration`, `platform-integration-testkit`. `platform-licensing-testkit` was not needed (the demo licence generator uses `platform-licensing` itself).
- Sign-in (`auth/oidc/OidcJwtAuthenticationConverter`, renamed from `auth/enterprise/EnterpriseJwt…`): people get entity and roles from `idn_group_grant` through platform-core's `EntityMembershipResolver` (30 s cache, as before). Roles come from `product-roles.json` (product `SDV`, answer 3; `TENANT_IT_ADMIN` renamed `IT_ADMIN` everywhere, answer 4a), one list with `SdvRole` (test). `PLATFORM_OPERATIONS_ADMIN` is the platform role: no entity grant gives it, so nobody holds it for now (answer 4b).
- Entity choice, the minimum (answer 5): `X-SDV-Entity-Id` checked with `EntitySelection`; none chosen with more than one entity, or a choice that isn't theirs: `SDV-AUT-010` (409, so the browser doesn't sign out). A person who must choose (or may only activate) gets a principal that reaches only `GET /api/v1/session/entities` and activation (`EntityChoiceFilter`). Frontend: account-menu switcher (`EntityChoice.tsx`, `Shell.tsx`), a minimal chooser, last entity kept in the browser, every switch audited (`POST /api/v1/session/entity-switch` → `ENTITY_SWITCHED`).
- Separation rules on platform grants (answer 8): they run at every sign-in on the roles the grants give, per entity, so they hold for grants loaded by setup and any later grant change; conflicts are withheld, recorded and audited at start and end as before (tests on in-memory and real PostgreSQL grants). `RoleMappingWatchService` and its two tables are gone; the Business Admin's view of grant changes is now `GET /api/v1/admin/grant-history` (platform-core `idn_grant_history`).
- Schema step: Liquibase, then platform-core identity (V1–V3, `idn_schema_history`) and licensing tables (V1, `lic_schema_history`) as their own Flyway runs (`PlatformSchemaRuns`); `status`, `sql` and `update` cover all three. No Liquibase or Flyway at startup in any profile (answer 10). The startup check also runs platform-core's `IdentitySchema.verify` (rights).
- Changeset `035-drop-old-identity` (answer 14) drops `enterprise_identity_connection(s|_history)`, `role_group_mappings_seen`, `role_mapping_notices`. The local seed changeset 002 is removed (answer 3); `SchemaVersion.RETIRED` keeps databases that ran it from reading as "newer".
- Removed (plan section 4, answers 3, 6, 8): `OidcConnectionBootstrap`, `JdbcOidcConnectionBindingRepository`, `EnterpriseIdentityAdminController`, `OidcDiscoveryTester`, `OidcKnownGoodRecovery`, `SdvGroupRoleMapper`, `RestrictedStewardMappingWatcher`, `RoleMappingWatchService`, the role-mapping, tenant-id, tenant-claim, persistent-connection, legacy `sdv_roles` and recovery settings, and the codes `SDV-IDP-007` and `SDV-SYS-011` (marked retired in the catalog notes). Deleted tests: `SignInConnectionPostgresTest`, `EnterpriseIdentityCheckpointBContractTest`, `EnterpriseIdentityCheckpointCContractTest`, `EnterpriseIdentityPreCheckpointCVersionGuardContractTest`, `SecondActiveConnectionRefusedTest`, `StartupSignInCodesTest`, and the watch tests in `RoleGovernanceServicesTest`.
- IT Admin Sign-in tab stays read only (answer 6): `GET /api/v1/admin/login-connection` (no test sign-in, no platform-integration in the app). Audit words `ENTERPRISE_IDENTITY_*` gone (answer 9); `ENTITY_ACTIVATED`, `ENTITY_SWITCHED` added.
- Activation (answer 11): `POST /api/v1/admin/entities/{slotId}/activate` by a person whose groups give Business Admin on that slot (so the first entity can be activated too), platform-core `SlotActivation` under the stored licence (read only, answer 15: no store writes, no clock writes, nothing counted, no promotion), Vero's row, audit; `SDV-ENT-001` / `SDV-ENT-002`.
- **Found and fixed:** an entity created after changeset 013 had no retention policy, so "Keeping documents" failed (SDV-SYS-016) for every newly activated entity. Vero now writes the first policy version with the entity row (app activation and demo step, idempotent). No schema change.
- Setup: `backend/sdv-setup` (runnable jar of platform-core's `SetupCommand`), wrappers `setup.ps1` / `setup.cmd` / `setup.sh` (fill `--product SDV`, `--product-roles`, `--keys`; refuse without a release `trusted-keys.json`; `-Development` for the demo key). `--no-customer-keys` with `"customerKeys": "optional"` (answer 12).
- Demo data: `dev/setup/` (customer `TEN-VERO-DEMO-0001`, slots `ENT-VERO-DEMO-0001/0002`, new development key `vero-dev-demo-1`, private key thrown away; generator `DemoLicenceGenerator`), the demo step command (`web/install/DemoStep`, refuses a licence not signed by a development key), derived demo UUIDs `bd960ee9-9d54-5053-9958-92aaa54893a6` and `9b233c00-fe6e-582c-a9de-c1fa46f87254` in the frontend (unit test recomputes them), scripts and screen tour. `Seed-SDV-Canvas-Demo.ps1` runs setup and the demo step; `-Reset` removes the two old demo entities and their records in the local `sdv` database only (answer 7).

**2. Client-ID lookup.** Client ID from `azp` / `client_id` / `cid` (must agree); `PostgresMachineClients` and platform-core `PostgresMachineEntityBindings` over `idn_machine_binding`, loaded by setup (`ClientBindingsFile`). No Vero bindings table. `SDV-AUT-011` (no active binding, or entity/connection not active; also no binding and no groups on an intake address), `SDV-AUT-012` (optional `sdv_entity_id`, `sdv_source_id`, `sdv_source_key` not the binding's), `SDV-AUT-013` (binding's source not in Vero for the entity); all recorded as unidentified intake attempts. `sdv_tenant_id` ignored (answer 13). `SDV_REQUIRE_CLIENT_BINDING` not added (the binding is always required).

**3. Independence clean-up** (done by a helper agent on a disjoint file set, then checked by the build): demo sender "Redaction Service", source keys `redaction-service`, `loan-origination`, `title-records`; comments in `TopBar.tsx` / `AccountMenu.tsx`; rule in `CLAUDE.md` (and the Enterprise Identity row removed); report line in `docs/work/README.md`; `exceptions.txt` emptied. Two tour fixes followed from the new key order (the lists are sorted by key).

**4. Sender profile** `docs/api/sdv-ingestion-v1.profile.json` (`platform-integration-profile/1`, version 1.1.0; no address, secret, client ID or customer data). `SenderProfileContractTest`: platform-core's `ProfileValidator` and `ProfileContract.checkAgainstSpec`. `SenderProfileServerContractTest` (PostgreSQL): `checkAgainstServer` against the real app started with OIDC sign-in, `MockIssuer` and a client-credentials token endpoint, a test client bound as setup binds it. The spec now lists each answer's codes also as `x-error-codes` (the kit's extension; a test keeps it equal to `x-sdv-codes`). CI job `sender-profile-contract` runs the spec check; the server check runs in the backend job and is in its "did the PostgreSQL-only tests run" list.

**5. Docs:** OpenAPI 1.1.0 (bearer scheme, required and optional claims, new codes), sender's guide and 1.1.0 changelog (`docs/api/README.md`), `error-codes.md` (AUT-010…013, ENT-001/002; AUT-005/006/007 updated; IDP-007 and SYS-011 out), new `docs/install/SETUP.md` (with the "Licensing is not live" line), runbook "Update the database", `CURRENT_STATE.md`.

**6. `Start-SDV-OIDC.ps1`:** the persistent `sdv-vault` with the token file path only (no compose, no dev Vault, no root token); no tenant ID or group settings.

**7. Live check with the local Keycloak** (owner allowed Keycloak admin access in the session): `kcadm.sh` inside `lexa-keycloak` with the container's own admin settings (never printed; its session file deleted afterwards). Removed the two hard-coded `sdv_source_id` / `sdv_tenant_id` mappers of `sdv-0040-contract-test-ingestion`, kept the audience mapper. Vero started with the rewritten `Start-SDV-OIDC.ps1` (persistent `sdv-vault`, token file only). A client-credentials token (claims: `azp`, `client_id`, `aud` with `sdv-api`, no `sdv_*` claim; secret and token never printed) created submission `d59f3fb9-36f5-4a76-853c-d70e30bb6b34` and delivered one PDF: `RECEIVED`, then `AVAILABLE`, stored under entity `bd960ee9-…` (`ENT-VERO-DEMO-0001`) and source `redaction-service`, as the client binding says. Then the client was deleted (its service-account user with it). The live submission and document stay in the local `sdv` database as test data. Found on the way: the script's readiness probes used `localhost`, which can resolve to IPv6 while Vite listens on `127.0.0.1`; they now use `127.0.0.1` as `Start-SDV-Local.ps1` does.

## No other product's name or behaviour in code

Yes: `ProductNameGuardTest` passes with an empty exception list.

## Commands run and results

| Command | Result |
|---|---|
| `stop-local.cmd` before every build | stopped |
| `.\Build-SDV-All.ps1` (final) | BUILD SUCCESS: backend tests 24 + 63 + 2 + 4 + 509 + 5 = 607, 0 failures, 13 skipped (the PostgreSQL-only ones); frontend type check and production build pass |
| `npm test` (PowerShell) | 20 passed (2 files; new `entityIds.test.ts`) |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 18 steps: schema step makes 35 changesets + identity V3 + licensing V1 (own history tables), old tables dropped, Vero starts as the read/write user |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 035`) | PASS: 035 and the platform runs on a 034 database with data; every other table unchanged; all 8 PostgreSQL-only tests pass, incl. `PlatformIdentityPostgresTest` (5) and `SenderProfileServerContractTest` |
| `.\Update-SDV-Schema.ps1 update` on local `sdv` | 035 + identity V1–V3 + licensing V1 applied |
| `.\setup.ps1 plan … -Development` without DB settings | stops at `SETUP_DB_SETTINGS_MISSING` after every file check (also a test) |
| `.\Seed-SDV-Canvas-Demo.ps1 -Reset` | Ran it myself (not refused). First run: setup, demo step, old demo entities removed, new entity seeded; second run: the ordinary reset path. Exit 0 |
| `npm run ui:capture` | 25 passed, 0 failed, 11 skipped (desktop-only scenarios on iPad, as before) |

Earlier runs that failed and were fixed: released changesets 025 and 033 had been touched by the "Tenant IT" → "IT" wording change (checksum error on the local database); restored byte for byte. The tour first failed 4 times (key order ×2, the retention gap above ×2).

## Tests: counts, and any failures with their output

See the table: no failures in the final runs. New tests: `OidcJwtAuthenticationConverterTest` (rewritten, 19), `RoleSeparationAtSignInTest` (rewritten, 6), `SdvRoleCatalogTest`, `EntityChoiceFilterTest`, `EntityEndpointsTest` (10), `SenderProfileContractTest` (4), `SenderProfileServerContractTest`, `PlatformIdentityPostgresTest` (5), `VeroSetupFilesTest` (5), `entityIds.test.ts`; `SignInTestKit`, `SignInChainTest`, `IngestionContractTest`, `LiquibaseSeedContextTest`, `DatabaseStartupWorkRunnerTest`, `RoleSeparationAdminTest` adapted. One test's expectation changed meaning: `IntakeReportServiceTest` (the list order flips with the new name).

## Files changed

201 files (see the commit). Main new ones: `PlatformIdentityConfiguration`, `PlatformIdentityReads`, `SdvRoleCatalog`, `EntityChoiceFilter`, `EntityChoicePrincipal`, `SessionEntitiesController`, `EntityActivationController`, `LoginConnectionAdminController`, `PlatformSchemaRuns`, `DemoStep`, `035-drop-old-identity`, `product-roles.json`, `backend/sdv-setup`, `setup.*`, `dev/setup/*`, `docs/api/sdv-ingestion-v1.profile.json`, `docs/install/SETUP.md`.

## Commits (hash and message)

- `d3d3b59` docs(work): owner answers to plan 0041
- `337f4f3` merge of `origin/release/vero-r7` (Dependabot, task 0047)
- `ff1b0e3` feat(identity): Vero on platform-core identity v0.2.0, client-ID lookup, sender profile (task 0041 Part B)
- `3f23328` fix(scripts): OIDC start probes 127.0.0.1; docs(work): report 0041

Pushed; PR #21 merged into `release/vero-r7` on green `ci-passed` (merge commit `6fd09b5`); annotated tag `sdv-ingestion-v1.1.0` on `6fd09b5`, pushed. (This section was brought up to date in the 0048 branch.)

## Not done, and why

- Not changed: the frozen V1-era scripts (`Test-SDV-EnterpriseReadiness-*`, `tools/Unit21JwtTestIssuer.java`) still build tokens with the old claims; they are not part of the build or CI. The 0034 failure catalog in `error-codes.md` keeps its historical line references.

## Questions for the owner

1. The old enterprise-readiness scripts and `tools/Unit21JwtTestIssuer.java` use the removed claims: retire them in a later task?

## Suggested next step

Done: merged and tagged. Next in the queue: 0048 (quarantine, server side).
