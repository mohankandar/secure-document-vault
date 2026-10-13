# Secure Document Vault (SDV) — Development Working Agreement

**Status:** Mandatory project operating agreement  
**Applies to:** All SDV development threads, local development sessions, reviews, and Kiro verification work  
**Project code name:** Secure Document Vault (SDV)

## 1. Purpose

This file defines how SDV development is to be performed so that every ChatGPT thread, developer session, and Kiro review follows the same process.

This agreement must remain in the SDV repository and must be reviewed before implementation or verification work begins.

## 2. Product Independence

SDV is a completely independent application and product.

SDV must not share implementation with LexaVault or any other product, including:

- source code;
- common libraries or shared JARs;
- databases or schemas;
- internal IDs;
- domain models;
- deployment units;
- runtime dependencies.

LexaVault may be a highly compatible upstream producer, but compatibility exists only through published external contracts and conventions.

## 3. Primary Responsibility Split

### ChatGPT

ChatGPT is the primary owner of:

- frozen requirement continuity;
- architecture decisions;
- unit scope;
- no-regression boundaries;
- implementation planning;
- code-generation guidance and source changes;
- troubleshooting;
- reviewing Kiro findings;
- deciding whether a finding is a defect, environment issue, future enhancement, or scope change;
- updating project documentation and development continuity.

### Kiro

Kiro is primarily an independent local reviewer and tester.

Kiro should be used when access to the developer machine materially improves verification, including:

- Maven builds;
- PostgreSQL checks;
- Liquibase verification;
- Spring Boot startup;
- runtime API checks;
- frontend TypeScript/Vite builds;
- filesystem/storage verification;
- environment-specific failures;
- repository inspection;
- targeted no-regression review;
- phase-level regression verification.

Kiro must **not** implement, redesign, refactor, rename, or “improve” SDV unless the user explicitly instructs it to do so.

## 4. Kiro Credit Conservation

Kiro credits are limited and must be used carefully.

Rules:

1. Do not use Kiro for routine thinking, design, or implementation work that can be done in ChatGPT.
2. Do not run Kiro after every trivial source change.
3. Batch related verification within a unit whenever practical.
4. Prefer one strong verification prompt near the end of a meaningful unit.
5. Re-run only the minimum necessary checks after a defect fix.
6. Use phase-level regression selectively rather than repeatedly rechecking all prior units.
7. Kiro should provide evidence: commands run, results, failures, relevant logs, and concrete findings.

## 5. Unit-by-Unit Development

SDV development proceeds unit by unit.

For each unit:

1. Restate the exact unit scope.
2. Define no-regression boundaries.
3. Implement only the agreed scope.
4. Perform local/static review.
5. Use Kiro only when local-machine verification adds meaningful value.
6. Review all verification findings.
7. Fix only real defects or approved issues.
8. Re-verify the minimum necessary scope.
9. Update the master documentation.
10. Freeze the completed unit before moving to the next unit.

Units may be grouped into larger phases, but implementation should remain unit-oriented unless two units are intentionally combined by explicit decision.

## 6. Frozen Decisions

Requirements marked as frozen are not to be silently changed during implementation.

If a conflict is discovered, classify it as one of:

- defect against frozen requirement;
- implementation detail;
- technical attention item;
- future enhancement;
- explicit V1 scope change.

A frozen requirement may be changed only through an explicit decision.

## 7. No-Regression Rule

Every unit must preserve all previously completed and frozen behavior unless a newer approved decision explicitly supersedes it.

Verification should focus on:

- the current unit;
- interfaces touched by the current unit;
- critical previously frozen behavior at risk from the change.

## 8. Local Environment Verification

Kiro is particularly valuable for checks that depend on the actual machine, including:

- Java 21 / Maven;
- PostgreSQL;
- Liquibase;
- Node/npm;
- Spring Boot runtime;
- filesystem volumes and capacity behavior;
- local key-management infrastructure when introduced;
- OS-specific paths and permissions.

ChatGPT defines the expected behavior and verification plan; Kiro executes those checks locally and reports evidence.

## 9. Evidence Standard

A unit should not be considered verified merely because a reviewer says “looks good.”

Where applicable, verification should include concrete evidence such as:

- `mvn clean verify` result;
- frontend build result;
- Liquibase migration/checksum result;
- API response/status;
- database query evidence;
- application startup/health result;
- filesystem/storage observation;
- role/authorization result;
- relevant logs.

## 10. Repository Documentation Requirement

This working agreement is a required repository artifact.

Recommended path:

`docs/development/SDV_WORKING_AGREEMENT.md`

It must be included in Unit 0 and remain present in every source package used for future SDV development or review.

Future master handoff/continuity documentation should reference this file explicitly.

## 11. Kiro Prompt Rule

Every Kiro verification prompt should include or reference these instructions:

> Verify only. Do not implement, refactor, rename, or redesign unless explicitly instructed. Run only the checks needed for the requested unit. Report commands, evidence, failures, and deviations from the frozen SDV requirements. Conserve credits by avoiding unrelated analysis or repeated full-project verification.

## 12. Guiding Principle

**ChatGPT designs and drives. Kiro independently verifies where local-machine access adds value. SDV evolves one frozen unit at a time.**
