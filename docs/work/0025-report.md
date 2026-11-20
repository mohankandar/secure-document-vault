# Report 0025: Restricted Steward role and separation rules (S2)

Date: 2026-11-20
Branch: `feature/sensitivity-2` (from `feature/sensitivity-1`)
Outcome: PARTLY DONE. Everything in the task is built, tested and committed. One item could not be checked: the design doc `SDV-Sensitivity-and-Quarantine.md` is not on this machine, so the matrix test checks the task's table and every existing cell, not sections 3.1, 3.2 and 3.4 of the doc (question 1).

## What was done

**1. Role and capabilities**
- `SdvRole.RESTRICTED_STEWARD`: everything `DOCUMENT_STEWARD` has, plus `VIEW_HIGHLY_CONFIDENTIAL` and `REQUEST_SENSITIVE_ACCESS`.
- New `Capability` values, mapped exactly as in the task's table: `VIEW_CONFIDENTIAL` and `CHANGE_SENSITIVITY_LEVEL` (both stewards); `VIEW_HIGHLY_CONFIDENTIAL` and `REQUEST_SENSITIVE_ACCESS` (Restricted Steward); `APPROVE_SENSITIVE_ACCESS` and `MANAGE_LEGAL_HOLD` (Business Admin).
- Nothing filters or acts on them yet (task 0026 onwards). The session now lists them.
- Restricted Steward's permissions are **fixed**. Roles & permissions can't change them, and stored overrides for it are ignored. "Everything Document Steward has today" means its defaults, not a Document Steward's per-tenant overrides.

**2. Generic rule (3a)**
- `com.sdv.platform.security.separation`: `RoleSeparationRule<R>` ("these roles can never be held with those"), `RoleSeparationPolicy<R>` and `ForbiddenGrant<R, C>`. They are generic, have no Vero names and no Spring, and are tested with plain text role names. They can move to Enterprise Identity later. They were not moved now.
- Vero's rules are data in `SdvAccessRules`: one separation rule (`RESTRICTED_STEWARD_NOT_ADMIN`) and two forbidden grants.

**3. Separation rule 1: roles from sign-in.** Every place roles are resolved:

| Place | What it does now |
|---|---|
| `SdvGroupRoleMapper` → `EnterpriseJwtAuthenticationConverter` (enterprise sign-in, login groups) | groups mapped to roles, then `RoleSeparationGuard` |
| Same mapper, legacy direct `sdv_roles` claim | same guard. A direct claim **never** gives `RESTRICTED_STEWARD` (decision, question 2) |
| `LocalHeaderAuthenticationFilter` (local mode, `X-SDV-Roles`) | same guard |
| Ingestion-source sign-in (JWT and local) | no roles; unchanged |

I searched for every `new SdvPrincipal` and `SdvRole.valueOf` in production code. There are no other places.

- **Conflict:** the person gets neither Restricted Steward nor the conflicting admin role. Other roles are kept. If no role is left, the sign-in is refused with 401 `SDV-ROL-004`.
- **Record:** each conflict is written to `role_conflict_problems`, which the Tenant IT Admin reads at `GET /api/v1/admin/role-conflicts` (needs `CONFIGURE_TENANT_AUTH`). It is audited as `ROLE_CONFLICT_DETECTED` (DENIED).
  - The audit event is written once, at the first sighting of the same person, source and roles.
  - Later sign-ins only update the last-seen time and the count. Otherwise every request would add an audit event.
- **Fail closed:** if the conflict can't be recorded, the sign-in is refused with 401 `SDV-ROL-005`. The server won't start in enterprise mode without the reporter.
- **New setting:** `sdv.auth.jwt.role-mappings.restricted-steward` (environment variable `SDV_AUTH_GROUP_RESTRICTED_STEWARD`). It is empty by default, so nobody is a Restricted Steward until it is set.

**4. Rule 2: role-permission overrides** (`PUT /api/v1/admin/role-capabilities`)
- Refused with 400 and a plain message:
  - `SDV-ROL-001`: a forbidden grant. Business Admin, Tenant IT Admin or Platform Operations with `VIEW_CONFIDENTIAL`, `VIEW_HIGHLY_CONFIDENTIAL` or `REQUEST_SENSITIVE_ACCESS`; or Document Steward or Restricted Steward with `APPROVE_SENSITIVE_ACCESS`.
  - `SDV-ROL-002`: a fixed role, including Restricted Steward.
  - `SDV-ROL-003`: any capability other than the five on the screen. Before this task such names were silently dropped.
- The whole request is checked before anything is stored, so a refusal changes nothing for any role. Before, roles were written one by one.
- Each refusal is audited as `ROLE_CAPABILITIES_UPDATE_REFUSED` (DENIED) with its code.
- Defence in depth: whatever is stored, `CapabilityPolicyService` never puts a forbidden grant into effect.

**5. Rule 3: changes to the Restricted Steward groups**
- The groups come from the server's settings, so a change can only take effect at a restart.
- At each start in enterprise mode, `RestrictedStewardMappingWatcher` compares the configured groups with the ones recorded last time (`role_group_mappings_seen`).
- On a change, every tenant gets the audit event `ROLE_GROUP_MAPPING_CHANGED`, with the groups before and after, and a notice in `role_mapping_notices`. The Business Admin reads the notices at `GET /api/v1/admin/role-notices` (needs `CONFIGURE_ROLE_PERMISSIONS`). This is all one transaction.
- The first start with groups set counts as a change. If the check fails, the server doesn't start.

**6. Schema: changeset `025-role-separation`** (role storage only, approved with the task). Three new tables; no existing table or row is changed.
- `role_conflict_problems`:
  - unique on tenant, subject, source and held roles;
  - checks on the source, non-blank role lists, count ≥ 1, and last seen ≥ first seen;
  - index on tenant and last seen.
- `role_group_mappings_seen`: role name (primary key), groups, recorded at.
- `role_mapping_notices`: index on tenant and time.

Production installs go from 24 to **25 changesets**. The app user's rights are covered by the install's default privileges.

**7. Error codes:** `SDV-ROL-001` to `SDV-ROL-005` are in `SdvErrorCode` and in `docs/api/error-codes.md`, with meaning, user action and support text. At sign-in there is no JSON body, so for 004 and 005 the code starts the 401's error description.

**8. UI:** People & roles lists "Restricted steward" as Fixed. Nothing else changed in the UI. New audit actions have sentences on the server (`AuditSentences`). The UI's audit words fall back to the action name.

## Commands run and results

| Command | Result |
|---|---|
| `.\stop-local.cmd` | Stopped the running local app (its jar blocked the build). Database and Vault left running. |
| `.\Build-SDV-All.ps1` (1st) | BUILD SUCCESS, 287 tests, 0 failures |
| `.\Test-SDV-CleanInstall.ps1` (1st) | **FAIL**: Vero didn't start. `RoleConflictService` had two constructors and no `@Autowired`. Fixed in both new services. |
| `.\Build-SDV-All.ps1` (2nd) | **BUILD SUCCESS**. Backend 24 + 47 + 2 + 4 + 210 = **287 tests** (244 before), 0 failures, 0 errors. Frontend build passed. |
| `.\Test-SDV-CleanInstall.ps1` (2nd) | **PASS** in 46 s. 25 changesets, the app user can't create or alter, 0 tenants, no outside connection. |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 025` (new default) | **PASS** in 16 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 023` | **PASS** in 18 s (023, 024 and 025 in one go) |
| `.\start-local.cmd` | Started. The local profile applied 025 to the dev `sdv` database. |
| Live checks with curl (local headers) | see below |
| `npm run typecheck` | passed |
| `npm run ui:capture` | see "Tests" |

- **Upgrade check for 025** (extended in `Test-SDV-SchemaUpgrade.ps1`; scratch database, dropped at the end):
  - the previous version has no role tables;
  - `sql` holds the new tables, constraints and indexes, and changes nothing;
  - after `update`, the three tables exist and are empty;
  - the repository's own statements behave: a second sighting adds no row and is counted, and the mapping upsert keeps one row;
  - the checks refuse an unknown source, blank roles, a count of 0, the wrong time order, an unknown tenant and a duplicate;
  - every other table is unchanged.
- **Live checks** against the running app:
  - `RESTRICTED_STEWARD,BUSINESS_USER` signs in with the new capabilities;
  - `RESTRICTED_STEWARD,BUSINESS_ADMIN` gets 401;
  - `RESTRICTED_STEWARD,TENANT_IT_ADMIN,BUSINESS_USER` signs in as Business User only, and the conflict appears in `GET /api/v1/admin/role-conflicts`;
  - giving `VIEW_CONFIDENTIAL` to `BUSINESS_ADMIN` returns `{"code":"SDV-ROL-001", …}`.
  - These checks left 2 role-conflict rows, their 2 audit events and 1 refusal audit event in the dev `sdv` database.

## Tests: counts, and any failures with their output

New, 43 tests, all passing:
- `FinalRoleMatrixTest`: new test `everyCellOfTheRoleMatrixIsExact`, every role against every capability.
- `SdvAccessRulesTest` (6):
  - Restricted Steward with each admin role, and with every combination of them;
  - the allowed combinations;
  - every forbidden grant;
  - the fixed matrix breaks no forbidden grant;
  - Restricted Steward ⊇ Document Steward.
- `RoleSeparationPolicyTest` (5): the generic rule, with plain text role names.
- `RoleSeparationGuardTest` (4): withheld and reported; no role left gives 004; recording fails, or no reporter, gives 005; no conflict means no report.
- `RoleSeparationAtSignInTest` (5): the login-group path through the real converter, for each admin group, including 004, 005 and the direct claim.
- `CapabilityPolicyServiceTest` (7): every forbidden override; the fixed roles; other capabilities; a stored forbidden row never takes effect; Restricted Steward ignores stored overrides.
- `RoleSeparationAdminTest` (4), through the real endpoint and error handler:
  - each of the 11 forbidden pairs gets 400 `SDV-ROL-001` with its message;
  - nothing is stored even when a valid role comes first in the request;
  - each refusal is audited;
  - 002 and 003;
  - an allowed change is still saved;
  - only the IT Admin reads conflicts and only the Business Admin reads notices.
- `RoleGovernanceServicesTest` (7):
  - the first start with groups is a change for every tenant (audit and notice);
  - the same groups in another order are no change;
  - a changed group is audited with before and after;
  - removing all groups is a change;
  - the first start without groups only records that;
  - a failed audit fails the check;
  - a conflict is audited once and counted every time.
- `LocalSignInRoleSeparationTest` (4): the local-mode path.

Changed tests (say so per CLAUDE.md):
- `FinalRoleMatrixTest`, two existing tests: Document Steward now also has `VIEW_CONFIDENTIAL` and `CHANGE_SENSITIVITY_LEVEL`, and Business Admin has `APPROVE_SENSITIVE_ACCESS` and `MANAGE_LEGAL_HOLD`, as the task's table says. The expected sets were extended. Nothing was removed.
- `e2e/sdv-screen-tour.spec.ts`: People & roles now expects 5 rows, not 4, and checks that the Restricted steward row says Fixed.

Screen tour (`npm run ui:capture`):
- First full run: 17 passed, 3 failed, 4 skipped (23.2 min).
  - "Business Administrator screens", on desktop and iPad: `Expected: 4, Received: 5` rows on People & roles. This was my new row; I changed the tour as above.
  - "Quick search across all fields (task 0022)", desktop: `getByRole('grid', { name: 'Records' }) tbody tr` not found while waiting for `300000000004`. It ran 18.9 minutes. The server answers the same search (`anywhere: gomez`, Loan Origination System) in 0.1 s with Ana Gomez, and nothing in this task touches search.
- Rerun of those steps (`npx playwright test e2e/sdv-screen-tour.spec.ts -g "Business Administrator screens|Quick search across all fields"`): **3 passed, 1 skipped** (the quick search is desktop only), 59 s. The quick-search failure did not repeat. I don't know its cause; it looks like a one-off timing problem in the browser.

## Files changed

- Backend, security:
  - `SdvRole`, `Capability`, `RoleCapabilities`;
  - new `SdvAccessRules` and `separation/*`;
  - `auth/CapabilityPolicyService`, `auth/LocalHeaderAuthenticationFilter`;
  - new `auth/RoleConflictReporter`, `auth/RoleSeparationGuard`, `auth/RecordingRoleConflictReporter`;
  - `enterprise/EnterpriseJwtAuthenticationConverter`, `enterprise/EnterpriseJwtConfiguration`, `enterprise/SdvGroupRoleMapper`;
  - new `enterprise/RestrictedStewardMappingWatcher`.
- Backend, application: new `port/RoleGovernancePort`, `roles/RoleConflictService`, `roles/RoleMappingWatchService` and `roles/RoleJson`; `audit/AuditSentences`.
- Backend, persistence: new `business/JdbcRoleGovernanceRepository`.
- Backend, domain: `error/SdvErrorCode`.
- Backend, web:
  - `admin/RoleCapabilityAdminController`;
  - new `admin/RoleGovernanceAdminController`;
  - `application.yml`;
  - changelog `025-role-separation.sql` and `.yaml`, and the master changelog.
- Tests: as listed above.
- Frontend: `src/r7/Governance.tsx` (one row), `e2e/sdv-screen-tour.spec.ts`.
- Scripts and docs: `Test-SDV-SchemaUpgrade.ps1` (`-From 025`), `docs/api/error-codes.md`, this report and `docs/work/0025-task.md`.
- Not committed (left as found): `Start-SDV-Local.ps1`, `docs/work/README.md` (the owner's uncommitted edits), `docs/design/sensitivity/` and tasks 0026–0030.

## Commits (hash and message), or "not committed"

See the commit on `feature/sensitivity-2`: `feat: restricted steward role and separation rules (task 0025)`. Not pushed, not merged.

## Not done, and why

- **Matrix against the design doc:** sections 3.1, 3.2 and 3.4 aren't in this repository or on this machine. The test pins the task's table. Question 1.
- **Screens for the conflict list and the notices:** the task allows only the role list. Both lists are API-only for now.
- **Rule 3 with a real enterprise sign-in:** the watcher is tested with mocks, and its tables with the upgrade check. It hasn't run in an OIDC start (`Start-SDV-OIDC.ps1`), because that uses `lexa-keycloak`, and the mapping would need a Restricted Steward group set up there.

## Questions for the owner

1. Please paste or commit sections 3.1, 3.2 and 3.4 of `SDV-Sensitivity-and-Quarantine.md` (for example under `docs/design/sensitivity/`), so the matrix test can be checked cell by cell against them. If any cell differs from the task's table, tell me which one wins.
2. A direct `sdv_roles` claim (the legacy mode) never gives Restricted Steward, so every way to the role goes through the audited group mapping. Is that right? Otherwise a direct claim could give the role with no audit of the mapping.
3. A role conflict is audited once per person and set of roles, then only counted. Should it instead be audited again, for example once a day while it lasts?
4. Should a Restricted Steward be configurable in Roles & permissions, like a Document Steward? It is fixed now.

## Suggested next step

Answer questions 1 and 2, then task 0026 (document filtering by level), which reads `VIEW_CONFIDENTIAL` and `VIEW_HIGHLY_CONFIDENTIAL`.
