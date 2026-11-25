# SDV Current Development State

Updated: 2026-11-23 (task 0057: identity on platform-core 0.6.1)

## Verified baseline

SDV V1 Units 0–25 were previously accepted and frozen with migration 010.

That frozen baseline remains the regression reference, but the current workspace now contains approved post-V1 Business Workspace R3 changes and therefore must not be described as the old frozen V1 release artifact.

## Active candidate — Business Workspace R3

Current latest migration: **035** (task 0041; this section describes R3 as it was).

R3 candidate scope includes:

- Search / Results / Document Viewer / Activity horizontal workflow tabs;
- page-level Source selection;
- source-driven searchable metadata;
- common Date Range and Last Updated search filters;
- record-centric results with AVAILABLE document selection;
- capability-controlled Download / Print / Add Tag / Add Comment / Permanent Redaction;
- Business Administrator role-capability overrides for business roles;
- document tags and comments persisted/audited;
- record-centric activity, with document-view/read events hidden by default;
- unified horizontal tab styling for Business Administration, IT, and Platform Operations;
- desktop and iPad/tablet responsive behavior.


## Identity on platform-core (task 0041, 2026-11-22)

Vero's identity runs on platform-core (v0.2.0 in task 0041, **v0.6.1** since task 0057; `platform-identity-*`), identity only. The earlier own login-connection
store and its admin screens (Enterprise Identity R1, migrations 014-016) are gone; changeset **035** drops their tables
and the Restricted Steward group-watch tables (025). The latest Liquibase changeset is **035**; platform-core's
identity tables are at version 4 (task 0057; 3 before) and its licensing tables at version 1, each with its own Flyway history table, made by
the schema step (never at startup, the local profile included).

- Login connection, entity slots, group grants and client bindings come from platform-core's setup command
  (`setup.ps1`, `docs/install/SETUP.md`). Product code `SDV`; roles in `product-roles.json`; `TENANT_IT_ADMIN` is
  now `IT_ADMIN`.
- People: entity and roles from group grants; entity choice with `X-SDV-Entity-Id` (`SDV-AUT-010`), switcher in the
  account menu, every switch audited. Sending systems: entity and source from the client ID (`SDV-AUT-011`..`013`).
- Entities are activated by a Business Admin (`POST /api/v1/admin/entities/{slot}/activate`) or, for the demo, by the
  demo step; demo entity UUIDs are derived from the demo slot IDs.
- Licensing is on (task 0075): Vero starts only with a licence; intake stops after the grace time; usage is recorded, never
  enforced. `docs/verification` keeps the earlier identity checkpoints as history.
- **Task 0094 (2026-11-25): platform-core 1.1.0.** A results page is opened with `FieldValues.readSensitive(list)`, one key-service call; Vero no longer
  calls the key service itself for opening.
- **Task 0077 (2026-11-24): field definitions in platform-fields** (`fld_` tables). What a sending system adds is an extension
  (`source.<id>`). The old rows are a copy written in the same transaction until 0078.
- **Task 0076 (2026-11-24): documents through platform-crypto**, one key per customer, bound to entity, document and sealed original.
  No reader for the old format; local work is in database `sdv_vero`, `sdv` is kept.
- **Task 0073 (2026-11-24): platform-core 1.0.0** (identity only; no module turned on yet).
- **Task 0070 (2026-11-24): Spring Boot 4.2.0, platform-core 0.8.1, Jackson 3**, Liquibase pinned at 4.33.0. Stored JSON goes
  through `VeroJson` (unchanged bytes); no Jackson 2 in the code (a guard test).
- **Task 0067 (2026-11-24): platform-core 0.7.0**, still identity only. Identity tables at version **5**, licensing **2**.
  Licence keys come only from the build (`backend/licence-build`); `Build-SDV-All.ps1` makes a development build unless
  `-Release`. A sending system's token may not name a person.
- **Task 0057 (2026-11-23): platform-core 0.6.1**, still identity only. Identity tables at version **4**
  (`idn_platform_grant`). Platform-level grants (the role mapping's `*` rows) give `PLATFORM_OPERATIONS_ADMIN` in every
  ACTIVE entity; the separation rules run on all roles together. The IT Admin's Sign-in tab has a read-only
  **Test sign-in** (platform-core's `OidcTestSignIn` over `platform.outbound.*`). A development-signed licence
  (`LicenceSigningKey`) alone allows a local `http` login server. Test licences are signed with the JDK
  (platform-core's signer moved to `platform-licensing-issue`, which Vero does not take).

## Validation state

R3 is an **implementation candidate / local validation in progress**.

Do not declare a new frozen or GA baseline until all of the following are complete:

1. local backend + frontend build/test PASS;
2. R3 preflight PASS;
3. Search → Results → Document Viewer → Activity runtime acceptance;
4. role-capability configuration/runtime authorization acceptance;
5. desktop + iPad responsive acceptance;
6. admin regression;
7. updated full release-readiness regression against the R3 migration/capability baseline;
8. independent freeze/security review.

## Important release note

The retained Unit 25 / V1 release scripts encode the previous V1 release assumptions. They are retained for regression and release work, but must be reviewed/updated where necessary before being used to declare the R3 codebase frozen.

## R3 administration polish iteration

Current candidate also includes the administration/UI polish validated from the September 28 screenshots:
- Business Administration tabs: Users & Access, Roles & Permissions, Retention Policies, Metadata Configuration. The empty Settings tab is removed.
- Roles & Permissions uses a compact permission matrix: Search/View are fixed baseline access; configurable document actions use switches and one Save permissions action.
- Metadata Configuration clarifies the source/SDV ownership boundary: a source defines available metadata; SDV configures label/search/required/business-identifier behavior.
- IT tabs: Storage, Authentication, Integrations, Environment. The redundant Diagnostics tab is removed; Environment links to Operational Readiness.
- Local demo authentication renders an explicit local-mode explanation instead of implying an enterprise-provider failure when status is unavailable.
- Document comments keep the previously approved backlog behavior in this iteration: latest three by default, View all N / Show latest 3, and bounded internal scrolling for larger threads so the PDF is not pushed far down the page.

This remains a candidate until local build/UI regression and final Unit 25 revalidation complete.
