# SDV Current Development State

Updated: 2026-09-28

## Verified baseline

SDV V1 Units 0–25 were previously accepted and frozen with migration 010.

That frozen baseline remains the regression reference, but the current workspace now contains approved post-V1 Business Workspace R3 changes and therefore must not be described as the old frozen V1 release artifact.

## Active candidate — Business Workspace R3

Current latest migration: **016**.

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
- unified horizontal tab styling for Business Administration, Tenant IT, and Platform Operations;
- desktop and iPad/tablet responsive behavior.


## Enterprise Identity R1

Enterprise Identity Administration Checkpoint C is implemented and treated as a verified/frozen subsystem within this workspace. The code includes exact-version VALIDATED activation, 15-minute freshness enforcement, one-ACTIVE-per-tenant database protection, known-good rollback, and disabled-by-default one-shot operational recovery. Migration **016** is the current database head.

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
- Tenant IT tabs: Storage, Authentication, Integrations, Environment. The redundant Diagnostics tab is removed; Environment links to Operational Readiness.
- Local demo authentication renders an explicit local-mode explanation instead of implying an enterprise-provider failure when status is unavailable.
- Document comments keep the previously approved backlog behavior in this iteration: latest three by default, View all N / Show latest 3, and bounded internal scrolling for larger threads so the PDF is not pushed far down the page.

This remains a candidate until local build/UI regression and final Unit 25 revalidation complete.
