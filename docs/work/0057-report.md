# Report 0057: Identity bump to the newest platform-core release

Date: 2026-11-23
Branch: `chore/platform-core-bump` (from `release/vero-r7`, pulled first)
Outcome: **PARTLY DONE**. Built, tested, both schema checks and the clean install passed. **Not run:** the screen tour
and the live Keycloak check, because the local schema step on `sdv` was refused by this session's permission check
(as in tasks 0048, 0050 and 0052) and Vero does not start on `sdv` until identity V4 is applied. One question for the
owner on dependency versions (below).

## What was done

**Version.** `platform.core.version` 0.2.0 → **0.6.1** (newest released, `platform-core-v0.6.1` in GitHub Packages; no
snapshot). Identity only: nothing else from platform-core is turned on. No other new dependency.

**1. Release notes read** (`docs/releases/` at tag `platform-core-v0.6.1`, through the GitHub API; `C:\dev\platform-core`
not touched). Every change that touches Vero's identity use, and what Vero does about it:

| Release | Change | Vero |
|---|---|---|
| 0.3.0 | Identity schema **V4** (`idn_platform_grant`, `idn_grant_history.scope`); app refuses to start with `IDN_VERSION_OLDER` until run | Schema step runs it as released (Flyway, own history). Schema checks, clean install and docs expect V4 |
| 0.3.0 | Platform-wide grants: `PlatformGroupGrants`, `PlatformGroupAuthorization`, role mapping `*` rows; setup codes `SETUP_ENTITY_ROLE_PLATFORM_WIDE`, `SETUP_PLATFORM_ROLE_ON_ENTITY` | Item 4 below |
| 0.3.0 | Test sign-in fetch moved into identity (`identity.spring.OutboundIssuerFetch` on `platform-outbound-spring`); settings `platform.outbound.*` | Item 3 below; `platform.outbound.installation` and `allow-internal-addresses` in `application.yml` |
| 0.3.0 | `integration.safety.Installation` → `outbound.Installation` | `SenderProfileServerContractTest` import |
| 0.3.0 | Role mapping: platform role on an entity row is `PLATFORM_ROLE_ON_ENTITY` | Tested on PostgreSQL |
| 0.4.0 | Every code is a `PlatformCode`; no code renamed | Test sign-in shows platform-core's codes as they are |
| 0.5.1 | Development-key marks kept for Spring apps (`DevelopmentLicence`) | Item 2 below |
| 0.6.0 | Licence signing moved to `platform-licensing-issue` (`LicenceSigner`, `Ed25519.generate`) | Vero does not take that module. Two tests and `DemoLicenceGenerator` sign with the JDK over `LicenceFormat.signedMessage` (`DevelopmentLicences`, one per test tree); a test proves platform-core's verifier accepts the result |
| 0.6.0 | `setup.ProductRoles`, `mapping.MappingTargets` package-private | `VeroSetupFilesTest` reads `product-roles.json` itself; setup checks the file |
| 0.6.0 | `licensing.spring.LicensingConfig` → `internal` | Vero does not use licensing-spring (nothing to change) |
| 0.6.0 | Messages of start-up refusals start with their code | `DatabaseSchemaCheck` no longer writes `IDN_…: IDN_…:` (`withCode`, tested) |
| 0.6.0 | `platform.model-governance.forbidden-roles` required | Model governance is not on Vero's classpath; not applicable |
| 0.6.0 | `internal` packages unsupported | Vero imports none |
| 0.6.1 | httpclient5 5.6.5, httpcore5 5.4.4, Nimbus 9.37.4; apps whose Spring Boot pins older httpcomponents "should override" | Nimbus resolves to 9.37.4. httpclient5 resolves to **5.5.2** and httpcore5 to **5.3.6** (Spring Boot 3.5.16's pins). **Not overridden**: that is a dependency upgrade outside the task (question 1) |
| 0.6.1 | SEC-1/9/52 fixes (folder export, watched folder, erase log) | Not used by Vero |

No release note asks for something Vero can't do under the identity-only rule (item 6).

**2. Development-key licences (0.5.1).** For Spring apps without platform-licensing-spring, identity gives
`identity.jdbc.LicenceSigningKey.of(LicenceService, TrustedKeys)`. Vero's `DevelopmentLicence` bean uses it over the
read-only stored licence and `sdv.identity.trusted-keys-file` (local profile: `dev/setup/development-trusted-keys.json`);
without the file it is `DevelopmentLicence.NONE`. Only a development-signed licence allows a local `http` login
server for the test sign-in; the demo licence still verifies (setup plan test).

**3. Test sign-in, read-only (LV-16 item 2).** `POST /api/v1/admin/login-connection/test` (IT Admin,
`CONFIGURE_TENANT_AUTH`), `ActiveConnectionCheck`: platform-core's `OidcTestSignIn` with the `IssuerFetch` bean of
platform-identity-spring (`platform.outbound.*`). It checks discovery document, keys and issuer; under OIDC sign-in it
also checks the IT Admin's own bearer token of that request (signature, issuer, **audience**, expiry, groups), which is
handed on and forgotten (never stored, logged or returned). In the local test sign-in there is no token, so the
audience is not checked and the screen says so. `http` is refused before any fetch unless the issuer is on this machine
and the licence is development-signed (`HTTP_NOT_ALLOWED`), as platform-core's own validation does. It writes
nothing (it does not use `LoginConnections.validate`); one INFO log line with the code. Answer: `outcome`
(PASSED / FAILED / NO_CONNECTION), platform-core's `code`, message, outbound `reason`, `tokenChecked`. Vero starts only
with the issuer fetch present (fail closed, SDV-SYS-005). Sign-in tab: one **Test sign-in** button, result banner with
the code; still "Sign-in is set during installation by your installer"; no draft, activation or rollback.
*Decision taken:* using the admin's own token for the audience check (the task lists audience; platform-core checks it
only with a token). Say if you want it issuer-only.

**4. Platform-level grants (LV-16 item 3).** Sign-in asks `PlatformGroupAuthorization` (through the 30-second
`CachedIdentityLookup`, which now also caches `activePlatformGrants`) for the groups' platform roles and adds them in
**every ACTIVE entity** of the connection (`PlatformLevelGrants`), beside the entity grants. The separation guard runs
on the union, so Platform Operations Admin with Restricted Steward is withheld and reported (ROL-004 when nothing is
left). A platform grant naming an entity role refuses the sign-in (AUT-007); unreadable grants are "database away".
Demo setup: `*,,PlatformOperations,PLATFORM_OPERATIONS_ADMIN` in `dev/setup/role-mapping-SDV.csv`. Install runbook:
`docs/install/SETUP.md` (role mapping, separation, test sign-in, outbound settings) and the clean-install runbook.
*Found:* setup 0.6.1 checks role-mapping rows only after reaching the database (even an unknown role passes the no-DB
plan), so the setup checks are a PostgreSQL test (`VeroSetupPlanPostgresTest`, plan only, writes nothing).

**5.** No notifications schema run added.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes; the diff names no other product.

## Commands run and results

| Command | Result |
|---|---|
| `Stop-SDV-Local.ps1` before building | stopped (backend and frontend) |
| `.\Build-SDV-All.ps1` (final) | BUILD SUCCESS: backend 26 + 68 + 2 + 4 + 592 + 10 = **702**, 0 failures, 0 errors, 25 skipped (PostgreSQL-only tests and the manual licence generator); frontend type check and build pass |
| `npm run typecheck` | PASS |
| `npm test` (PowerShell) | PASS: 66 tests, 8 files (new `signInTestWords.test.ts`). Under Git Bash every suite fails to load (`reading 'config'`), as before; not a code problem |
| `.\Test-SDV-SchemaUpgrade.ps1` (first run) | FAIL at 4f: my new `PlatformIdentityPostgresTest` case assumed one ACTIVE entity; the demo-step test can activate a second, so the person (correctly) had to choose. Test fixed to name the entity and to assert the choice lists every ACTIVE entity |
| `.\Test-SDV-SchemaUpgrade.ps1` (again) | **PASS** in 580 s: status 1 changeset + 5 platform versions (identity V1–V4, licensing V1); 4f passed every PostgreSQL test, `PlatformIdentityPostgresTest` (6) and `VeroSetupPlanPostgresTest` (3) included |
| `.\Test-SDV-CleanInstall.ps1` | **PASS** in 155 s: 42 changesets + identity V4 + licensing V1; packaged start with OIDC sign-in and the outbound settings serves |
| Local schema step on `sdv` (`Update-SDV-Schema.ps1 status`, then `update`) | **Not run: refused** by this session's permission check. Not worked around. `sdv` is at identity V3 |
| Live Keycloak check of 0041 (temporary client, client-credentials delivery, delete client) | **Not run**: Vero refuses to start on `sdv` until V4 is applied. Only read-only checks done (container env-var names; no admin session opened, no client created) |
| `npm run ui:capture` | **Not run**, same reason. The tour now expects one "Test sign-in" button and a passed result |

## Tests: counts, and any failures with their output

Backend 702 / 0 failures; PostgreSQL-only tests all pass in the schema check; frontend 66 / 0. New or changed tests:

- `OidcJwtAuthenticationConverterTest` (+5): platform role in every ACTIVE entity; added to entity roles; never with
  Restricted Steward (withheld, reported; ROL-004 when none left); entity role in a platform grant refused; unreadable.
- `PlatformIdentityPostgresTest` (+1, 6): a real `idn_platform_grant` row, the separation rule, identity schema 4.
- `VeroSetupPlanPostgresTest` (new, 3): demo plan shows the platform grant; both refusals.
- `VeroSetupFilesTest` (7): demo mapping has the `*` row; JDK-signed licence passes platform-core's verifier.
- `ActiveConnectionCheckTest` (new, 5, against `MockIssuer`): pass without token; audience with token
  (`IDN_TEST_AUDIENCE`); `IDN_TEST_ISSUER_UNREACHABLE`, `_DISCOVERY_MISMATCH`, `_KEYS_MISSING`; `http` refused under a
  production licence before any fetch; no issuer fetch → Vero doesn't start.
- `EntityEndpointsTest` (+1): the endpoint hands on only the admin's own token, writes nothing, NO_CONNECTION, Business
  Admin refused.
- `SchemaVersionTest` (+2): identity run at 4; code written once.

## Files changed

Backend: `pom.xml`; `OidcJwtAuthenticationConverter`, `OidcJwtConfiguration`, `SdvRoleCatalog`, new
`PlatformLevelGrants`; `PlatformIdentityConfiguration`, new `ActiveConnectionCheck`, `LoginConnectionAdminController`,
`DatabaseSchemaCheck`, `application.yml`; tests listed above, new `DevelopmentLicences` (×2), `DemoLicenceGenerator`,
`SignInTestKit`, `SenderProfileServerContractTest`. Frontend: `SignIn.tsx`, `liveApi.ts`, new `signInTestWords.ts` and
test, `e2e/sdv-screen-tour.spec.ts`. Scripts and CI: `Test-SDV-SchemaUpgrade.ps1`, `Test-SDV-CleanInstall.ps1`,
`.github/workflows/ci.yml`. Setup: `dev/setup/role-mapping-SDV.csv`. Docs: `docs/install/SETUP.md`,
`docs/install/PLATFORM_PACKAGES.md`, `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`,
`docs/development/CURRENT_STATE.md`. `Start-SDV-Local.ps1` left as it was (not committed).

## Commits (hash and message), or "not committed"

- `65f8dd2` docs(work): task 0057
- `2a8d73e` chore(identity): platform-core 0.6.1, test sign-in and platform-level grants (task 0057)
- this report (next commit)

## Not done, and why

- Local schema step, screen tour and live Keycloak check: the schema step was refused by the session's permission
  check (see above).
- `setup.ps1 apply` of the new demo role mapping on `sdv`: not part of the approved steps; the next
  `Seed-SDV-Canvas-Demo.ps1` (or `setup.ps1 apply … -Development`) loads the `*` row after the schema step.
- httpcomponents override: question 1.
- No live sign-in as a Platform Operations Admin: the local login server has no `PlatformOperations` group or user, and
  the task allows only a temporary client.

## Questions for the owner

1. **httpcomponents versions.** platform-core 0.6.1 says apps whose Spring Boot pins older httpcomponents should
   override them. Vero resolves httpclient5 5.5.2 and httpcore5/-h2 5.3.6; 0.6.1 was built against 5.6.5 / 5.4.4
   (CVE-2026-64607, CVE-2026-54399, CVE-2026-54428). Approve overriding `httpclient5.version` and `httpcore5.version`
   in `backend/pom.xml`? (Recommended; separate small task.)
2. Keep the audience check with the admin's own token (item 3), or issuer-only?

## Suggested next step

Owner runs on the local `sdv` database: `.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv
-Username sdv` (applies identity V4), then `.\Seed-SDV-Canvas-Demo.ps1` or `setup.ps1 apply … -Development` for the
platform grant. Then a short follow-up session runs `npm run ui:capture` and the live Keycloak check of 0041.
