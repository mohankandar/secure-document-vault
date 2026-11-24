# Report 0059: Spring Boot 4.2, Part A (plan)

Date: 2026-11-23
Branch: `plan/spring-boot-4` (from `release/vero-r7` at `1af3f46`)
Outcome: Part A DONE. Part B waits for platform-core's 4.2 release and the owner's answers.

## What was done

`0059-plan.md`: every change Vero needs, what blocks 4.2, the order of work with its proof, and what Vero expects from
platform-core. From Spring's official documents (cited there) and a measurement on a copy outside the repository.

- 4.2.0 compiles after import changes in 5 main and 5 test files. 705 tests: 8 failures, 19 errors, 25 skipped.
- Main work: Jackson 3. Without it the published intake API answers 500 instead of its codes.
- Blockers, all in platform-core 0.6.1: auto-configuration order (B1), Nimbus pin (B2), built on Boot 3.3 (B3).

## No other product's name or behaviour in code: yes

No code changed.

## Commands run and results

| Command | Result |
|---|---|
| `git archive` of the backend to the session's scratch folder; version set to 4.2.0, override removed | outside the repository |
| `mvn -fae test-compile` there | 5 main files fail; after the renames, `HealthGroupsTest`'s last 2 imports; then all compile |
| `mvn -Dmaven.test.failure.ignore=true test` there | 705 tests, 8 failures, 19 errors, 25 skipped (causes in the plan) |
| `mvn dependency:tree` there | versions in the plan, section 1.9 |

## Tests: counts, and any failures with their output

The repository's own tests were not run: nothing in it changed but two documents. The failures above are the 4.2
measurement, not a state of Vero.

## Files changed

`docs/work/0059-task.md`, `0059-plan.md`, `0059-report.md`.

## Commits

Task file, then plan and report. Pushed on `plan/spring-boot-4`; **no PR** (queue note).

## Decisions taken

- Compiled and tested a throwaway copy on 4.2.0. The task says read only; the repository was not changed, and a
  measured list is safer than one read from notes. Maven downloaded the 4.2 libraries into the local cache.
- The plan recommends keeping `HttpComponentsVersionTest` after the override goes, as a floor.

## Not done, and why

PostgreSQL-only tests, a live start, the schema step and the UI on 4.2: they belong to Part B. The cause of the
`HealthGroupsTest` wiring failure was not traced.

## Questions for the owner

In the plan: the Liquibase 5 licence; steps 0 and 1 now on 3.5; the date of platform-core's 4.2 release.

## Suggested next step

A task for steps 0 and 1 of the plan (tests on 3.5 only).
