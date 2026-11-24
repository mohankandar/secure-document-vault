# Report 0060: Spring Boot 4.2 groundwork on 3.5 (plan 0059 steps 0 and 1)

Date: 2026-11-23
Branch: `chore/sb4-groundwork` (from `release/vero-r7` at `1af3f46`)
Outcome: DONE. Tests only; no product code, dependency or setting changed.

## What was done

**Step 0: today's behaviour recorded.** New tests compare with text files in
`backend/sdv-web/src/test/resources/baseline`; what a run saw is written to `target/baseline`.

| Test | Records |
|---|---|
| `HttpJsonBaselineTest` | Over the HTTP set-up Spring Boot makes for the running app: the published ingestion answers and every kind of value, exactly; 35 request bodies and 3 depths on a submission and 12 on a metadata field (null, text after the JSON, unknown and repeated fields, wrong types, enums); 17 `Content-Type` and 12 `Accept` values |
| `StoredJsonBaselineTest` | Every mapper Vero makes by hand (28 classes) writes the same bytes; record keys and hash, record references, roles, redaction regions, storage counts, filters; what each accepts when read back |
| `SignInBaselineTest` | Eight `typ` values, algorithms and keys, how the `Authorization` header is read, the six open addresses and their near misses, every header of a 401, a cross-origin preflight and request |
| `InfoBaselineTest` | `/actuator/info` says nothing; no build or git file ships |
| `JsonShapesBaselineTest` | Enums are written as their names; the request records with primitives or one component; no duration, month, optional or old date in a request or answer |
| `ChangesetChecksumBaselineTest` | The checksum of all 42 changesets as Liquibase 4.31.1 computes it |
| `FullPoolBaselineTest` | On a real HikariCP pool: full is busy, database away is not |

Already covered before this task, so not repeated: published codes (`IngestionContractTest`), readiness groups
(`HealthGroupsTest`), hub away (`SignInKeysDownTest`), no migration at start (`LiquibaseSeedContextTest`).

**Step 1: the Jackson guard** (`JacksonHttpGuardTest`). It walks every request and answer type of Vero's controllers
(fields, generics, nested records). Exceptions: the five `JsonNode` fields of today. A second test proves that it finds one in
a map of lists, a nested record, an answer and a wildcard.

**3.** `0059-plan.md` and `0059-report.md` are in this branch, unchanged.

## Seen on the way (recorded, not changed)

Today's behaviour, for the decisions in Part B:

- Text after the JSON is accepted in request bodies, record references and stored rows (Jackson 3 refuses it).
- `null`, `""` and `1.5` for a whole number are read as 0, 0 and 1; an enum can be given by its number (`"fieldType": 0`).
- A metadata number `1.50` is read as `1.5`; nesting of 900 is accepted, 1100 refused.
- A cross-origin preflight is answered 403 with no permission header.
- Malformed media types: some are accepted (`application/json;x`), which Framework 7.1 will refuse.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes in the build.

## Commands run and results

| Command | Result |
|---|---|
| `mvn … -Dtest=*Baseline*Test,JacksonHttpGuardTest` | 24 passed. First runs failed only for files not yet written, one wrong class name and one too-small region in my test |
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: backend 26 + 68 + 2 + 4 + 619 + 10 = **729**, 0 failures, 25 skipped (PostgreSQL-only tests and the manual licence generator); frontend type check and build pass |
| `npm test` | 66 passed |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 161 s |
| `.\Test-SDV-SchemaUpgrade.ps1` | @@SCHEMA@@ |

## Tests: counts, and any failures with their output

729 backend (705 before, 24 new), 0 failures; frontend 66.

## Files changed

Eleven new test files (`web/baseline`, `SignInBaselineTest`, `FullPoolBaselineTest`), 14 text files in `test/resources/baseline`,
`docs/work/0059-plan.md`, `0059-report.md`, `0060-*`.

## Commits

Task file; tests with the plan; this report. Merged through a PR on green `ci-passed`.

## Decisions taken

- Baselines are text files compared exactly, with no switch that rewrites them: a change must be made by hand and seen in review.
- The stored-JSON test uses reflection on purpose, so that it runs unchanged on either library.
- The list of classes with a hand-made mapper is not pinned (only that there are more than 20), so that other tasks aren't blocked.
- A new changeset must add its line to `changeset-checksums.txt` (released lines never change).
- The plan's two whole-app cases (a jar's content; "made by the 3.5 jar, used by the 4.2 jar") need the 4.2 jar: Part B.

## Not done, and why

Nothing.

## Questions for the owner

None.

## Suggested next step

0061 (security review).
