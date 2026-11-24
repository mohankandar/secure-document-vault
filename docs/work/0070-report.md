# Report 0070: Spring Boot 4.2 move

Date: 2026-11-24
Branch: `chore/spring-boot-4.2`, stacked on `chore/platform-core-0.7.0` (0067) with `release/vero-r7` merged in
Outcome: DONE. No schema change.

## What was done

Spring Boot **4.2.0** (the newest 4.2), Framework 7.1.0, Security 7.2.0, Java 21; platform-core **0.8.1** (identity only, as
before); Jackson **3.1.8**; Tomcat 11.0.27, HikariCP 7.1.0, Flyway 12.11.0; **Liquibase pinned at 4.33.0**. The 0058
override of httpclient5/httpcore5 is gone: 4.2 manages the same versions (5.6.5 / 5.4.4; `HttpComponentsVersionTest` keeps
the floor). Nimbus is 10.9.1, Spring Security's. The release jar holds no Jackson 2 except its annotations (which Jackson 3 uses)
and no start-up Liquibase or Flyway module.

- **Stored JSON** goes through one mapper, `VeroJson`, with the defaults it was written with: `StoredJsonBaselineTest`
  passes unchanged, byte for byte. Values as text and whole numbers read as before (Jackson 3 changed both).
- **The API's JSON** keeps its reading and writing where Jackson 3 changed it: null for a number, text after the JSON, and
  how letters beyond the basic plane are written.
- **Jackson's exceptions are unchecked now.** Each place was read. Two would have let one through and catch it: the key
  service's answer (stays a failed answer) and the product-roles file.
- **Token types:** Security 7 checks them among its defaults and knows only `JWT`; Vero names its three, as before.
- **Other origins:** Framework 7 no longer refuses a preflight by itself (it answered 200, without a permission); Vero
  refuses it itself (403) and keeps the `Vary` header.
- **Guard:** no Jackson 2 in requests, answers or anywhere in Vero's code.
- `spring.jackson.find-and-add-modules=false`; the process part of `/actuator/info` written out as off.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 72 + 2 + 4 + 660 + 10 = **791**, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 77 passed; pass (nothing changed in the screens) |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 043` | PASS in 529 s, PostgreSQL-only tests included |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 109 s: database away and back, sign-in keys away and back, live |
| **A database made and seeded by the 3.5 build** (scratch) | 4.2's schema step: up to date, nothing to run; Liquibase checksums and both Flyway histories unchanged; 4.2 reads a document made by 3.5 (page, search, record); a record reference made by 3.5 opens. Then **3.5 starts again** on it and reads the same |
| Full pool, live (pool of 1, a locked table) | readiness 503 with `Retry-After: 5`, liveness 200, ready again after |
| Sign-in in the browser against a made-up login system | as in 0065: signed in, refreshed, signed out; seven wrong tokens refused |
| `npm run ui:capture` (scratch database) | **33 passed, 19 skipped** |
| **Live Keycloak delivery** | **not run** (below) |

## Changed assertions, each with its reason

Plan rule: a test may change its imports and set-up. Method names of Jackson 3 (`asText` is `asString`, and so on) are in many
tests. **The task says step 0's recorded tests pass unchanged. Five recorded answers do change**, all to a stricter refusal
with a published code (`SDV-SYS-015`, 400/406/415, `error-codes.md`):

| Recorded answer | Was | Is | Why |
|---|---|---|---|
| `Content-Type: application/json;x` | 200 | 415 | Framework 7.1 refuses a malformed media type |
| `Content-Type` with two charsets | 200 | 415 | the same |
| `Accept: application/json;x` | 200 | 406 | the same |
| `Accept: application/json;v=1;v=2` | 200 | 406 | the same |
| A body nested 900 deep | 200 (then refused by Vero's own check) | 400 | Jackson 3 reads at most 500; with 1000, Vero's own text of such a value failed with a 500 |

Every other recorded file is unchanged: answers, stored JSON, checksums, token types, open addresses, 401 headers, other origins,
info. Also changed:

- `JacksonHttpGuardTest`: the list of five exceptions is empty (plan step 3); one test added.
- `RequestBodyLimitTest`: looks at the JSON factory, where 4.2 sets the limit.
- `HttpJsonBaselineTest`, `HealthGroupsTest`: set-up only (Vero's JSON settings; Boot's new registry class). Same assertions.

## Files changed

See the pull request: 30 main files for Jackson, 5 for moved Boot classes, the two security files, build files.

## Commits

One change with this report. Draft pull request with base `chore/platform-core-0.7.0`.

## Decisions taken

- **The five stricter answers are accepted**, not worked around: well-formed requests are untouched, the codes are in the
  contract, and undoing the framework's check would be Vero's own media-type parser. The design chat may say otherwise.
- `builderWithJackson2Defaults` for stored JSON for good (it must not change); for the API only the three settings that
  differ, named one by one, not the blanket switch.
- Flyway and Liquibase stay plain libraries: Boot can't migrate at start at all now.
- No `spring-boot-properties-migrator` run: every Spring property Vero sets was looked up in 4.2's own metadata instead.

## Not done, and why

- **The live Keycloak delivery:** it needs the administration of `lexa-keycloak`, which the session's permission check refused
  earlier; not worked around. The contract test delivers against the real application on PostgreSQL
  (`SenderProfileServerContractTest`, in the schema check). Owner step, with 0067's: as in report 0041, point 7.
- A worktree of the 3.5 build is left in the session's temporary folder.

## After 0067 (PR #35, `ea3a720`)

`release/vero-r7` merged in: no backend file changed against the checked commit. Built again: 791 tests, 0 failures. One build
before it failed with missing classes: the editor's own Java build writes into `target` while Maven runs (seen three times
today, each gone on a second run; not a code failure). CI doesn't run for a pull request with another base, so it was started by
hand on the branch: green.

## Open points and questions for the design chat

1. Are the five stricter answers fine, or should senders be told in the contract's notes?
