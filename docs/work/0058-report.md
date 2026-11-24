# Report 0058: Finish 0057's checks; HTTP client security fix

Date: 2026-11-23
Branch: `chore/httpclient-cve` (from `release/vero-r7` at `b830d1b`)
Outcome: DONE (merge: PR on green `ci-passed`)

The work was done in two sessions on the same day; the first ended when its editor window reloaded, during the schema
upgrade check. That check ran on to its end. Nothing was redone.

## 0057 checks

**1. Screen tour on the 0057 code** (the running app was the 0057 build, same code as `release/vero-r7`): **28 passed,
2 failed, 16 skipped** (the desktop-only scenarios on iPad). Both failures were "IT Administrator screens" (desktop and
iPad), at the last line of 0057's new test sign-in check. **A fault in the tour script, not in Vero:** it took the
sign-in panel's text with `innerText` (upper-case labels and line breaks from the styling) and compared it with
`toHaveText`, which uses the plain text, so the two never matched. 0057 could not see it because its tour never ran.

**The test was changed:** it now takes `textContent`, and also checks that the panel shows a version. It still compares
the panel before and after a test sign-in. The IT Administrator scenario alone then passed 2 / 2 on the same app. The
full tour with the fixed script ran on this branch's build only (below).

**2. Live Keycloak check of 0041: passed.** Vero was started with `Start-SDV-OIDC.ps1` (0057 code). Through `kcadm.sh`
in `lexa-keycloak`, with the container's own admin settings (never printed):

- Created temporary client `sdv-0040-contract-test-ingestion`, client credentials only, with the `sdv-api` audience
  mapper. No client with that ID existed before. It is bound by the binding the demo seed had already loaded.
- Token: `azp` / `client_id` are the client, `aud` includes `sdv-api`, **no `sdv_*` claim**. Secret and token not printed.
- Submission `aee9cd31-a04c-4760-86e3-2e20a131e82c` created (201); one PDF delivered (201, `RECEIVED`, then
  `AVAILABLE`): document `2e620362-35b9-431a-911f-90112a174021`. The database shows it under entity `bd960ee9-…`
  (`ENT-VERO-DEMO-0001`) and source `redaction-service`, as the binding says.
- Client deleted; a search for the ID finds none. The `kcadm` session file was removed.

The first attempt failed at the upload because the check script had a wrong file path (curl could not read the PDF);
its client was deleted too. It left one empty OPEN submission, `69193c59-c5f5-405c-a7f1-59aa27c58e90`. That one and the
delivered document stay in the local `sdv` database as test data.

## What was done

- **Override** in `backend/pom.xml` dependency management: `httpclient5` 5.5.2 → **5.6.5**, `httpcore5` and
  `httpcore5-h2` 5.3.6 → **5.4.4**, with a comment naming CVE-2026-64607, CVE-2026-54399 and CVE-2026-54428 and saying
  when to remove it. They are dependency entries, not version properties, because Spring Boot is imported as a BOM and
  its properties can't be set from here. The dependency tree shows the new versions in `sdv-security`, `sdv-web` and
  `sdv-setup`, with no other version changed; Vero's jar holds exactly these three.
- `HttpComponentsVersionTest`: fails if a version drops below these, or if a second copy is on the class path.
- `TestSignInTokenNotKeptTest` (owner answer 2): the real check through the endpoint, with a token that passes and one
  that fails on audience. Neither the token nor any of its three parts appears in a log line (all levels), in an audit
  call or in the answer. The code was not changed; the check stays as it was.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes (3 tests).

## Commands run and results

| Command | Result |
|---|---|
| `npm run ui:capture` (0057 code) | 28 passed, **2 failed**, 16 skipped (above) |
| `npx playwright test e2e/sdv-screen-tour.spec.ts -g "IT Administrator screens"` (0057 code, fixed script) | 2 passed |
| `Start-SDV-OIDC.ps1`, live Keycloak check | passed on the second attempt (above) |
| `Stop-SDV-Local.ps1` before building | stopped |
| `mvn dependency:tree` before and after | only the three artifacts changed |
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: backend 26 + 68 + 2 + 4 + 595 + 10 = **705**, 0 failures, 0 errors, 25 skipped (PostgreSQL-only tests and the manual licence generator); frontend type check and build pass |
| `npm test` (PowerShell) | 66 passed, 8 files |
| `.\Test-SDV-SchemaUpgrade.ps1` | **PASS** in 567 s, 42 changesets, PostgreSQL-only tests included |
| `.\Test-SDV-CleanInstall.ps1` | **PASS** in 152 s |
| `npm run typecheck` | PASS |
| `Start-SDV-Local.ps1` (this branch's jar), `npm run ui:capture` | **30 passed, 0 failed, 16 skipped** |

The local schema step was not run here: the owner had run it and the seed.

## Tests: counts, and any failures with their output

Backend 705 / 0 failures (702 before, 3 new); frontend 66 / 0; final tour 30 / 0. The only failures are the tour
failures above.

## Files changed

`backend/pom.xml`; new `HttpComponentsVersionTest` and `TestSignInTokenNotKeptTest`;
`frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`; `docs/work/0058-*`. `Start-SDV-Local.ps1` left as it was (not committed).

## Commits

- `2508dc4` docs(work): task 0058
- `7a6ff1b` chore(deps): httpclient5 5.6.5 and httpcore5 5.4.4; token-not-kept tests; tour fix
- this report (next commit)

## Not done, and why

Nothing. Vero was left running locally from this branch's build.

## Questions for the owner

None.

## Suggested next step

Remove the override when Spring Boot's own versions are newer.
