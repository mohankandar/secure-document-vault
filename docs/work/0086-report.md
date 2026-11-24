# Report 0086: Faster CI and local checks (items 1 to 3)

Date: 2026-11-24
Branch: `chore/faster-checks` (PR #49)
Outcome: PARTLY DONE. Items 1 to 3 (side queue S4). Items 4 and 5 stay in the main queue.

## What was done

- **Times measured** (below). Almost all CI time was `backend`, and 308 of its 368 test seconds were five PostgreSQL tests.
- **Scale tests on their own runners.** `QuarantineScalePostgresTest` and `ActivityReportPostgresTest` (`.github/ci/scale-tests.txt`) run in `backend-scale`, one runner and one throwaway PostgreSQL each. `backend` leaves out exactly the classes in that file, so the two can't drift apart.
- **Jobs a pull request can't affect are skipped.** New job `changes` (`.github/ci/changed-areas.sh`). `docs/work/*.md` only: backend, scale, frontend and sender-profile are skipped. `backend/` only: frontend is skipped. Anything else runs everything. The name guard always runs. Pushes and manual runs run everything.
- **`ci-passed`** waits for every job. It accepts a skipped job only if `changes` succeeded and said that job isn't needed. Any other skip, failure or cancel fails it.
- **New check:** every test class outside the scale list must have a report with tests in it.
- **Caching and cancelling superseded runs** were already there (Maven keyed on the poms, npm on the lock file). Not changed.
- **`Build-SDV-All.ps1 -Quick`**: tests only changed modules, the modules that use them and the modules they need, with Maven `-T 1C`; builds the frontend only if it changed. No jar. Anything it can't place in one module runs the full build. It refuses `-Release`. The default build is the same as before.

## No other product's name or behaviour in code: yes (none in code)

## Times

CI, the last 10 successful runs before (median, lowest to highest) and the first run after:

| | Before | After |
|---|---|---|
| Whole run | 457 s (438 to 554) | 245 s |
| `backend` | 447 s (422 to 540) | 226 s |
| its build and test step | 389 s (369 to 396) | 166 s |
| `backend-scale`, two runners | in `backend` | 162 s and 182 s |
| `product-name-guard` | 39 s (27 to 134) | 42 s |
| `sender-profile-contract` | 39 s (24 to 114) | 41 s |
| `frontend` | 26 s (19 to 37) | 26 s |

Two longest tests before: `QuarantineScalePostgresTest` 127 s, `ActivityReportPostgresTest` 110 s. The 114 to 134 s highs are cold Maven caches after a pom change.

Local module tests, all modules: without `-T` 45 to 59 s, with `-T 1C` 40 to 56 s (790 tests; 788 before the release merge). `-T 1C` saves little, because `sdv-web` is about 90% of the work. `-Quick` after a one-file change in `sdv-crypto`: 61 s.

## Commands run and results

- `mvn -o -q -T 1C -pl <all modules> test`: three passing runs in a clone, 790 tests each; one run without `-T` the same. Earlier three on the pre-merge code, 788 each, all passed.
- `mvn ... test -Dsurefire.excludesFile=.github/ci/scale-tests.txt`: 161 classes and 786 tests (163 and 788 without it).
- `ProductNameGuardTest`: passed. `-Quick`: selection checked with stub `mvn` and `npm`, then one real run: passed.
- CI run 45424383468: green, 161 test classes checked, 790 tests (788 in `backend`, 1 and 1 in `backend-scale`), no skips.

## Tests: 790 in CI, 0 failed. Failures met on the way

1. **`-Quick`, first version:** Maven's `-am` does not cover modules that `-amd` adds, so `sdv-web` failed test discovery. The script now finds the dependents itself.
2. **I committed and pushed that first version while its local run had failed** (the commands were chained without stopping at the failure). The next commit (24fc281) fixed the Maven call but held a wrong path in the script, found by the stub checks and fixed in the merge commit. Neither version reached `release/vero-r7`. CI on that branch has no bearing on `-Quick`, so green CI alone would not have caught either.
3. **One `-T 1C` run of three in this folder failed** with missing class files. Every class in `sdv-web/target` was rewritten during the run, while the editor's Java language server had this folder open. Not proven, but three runs in a clone the editor doesn't watch all passed. A build in a folder open in the editor can hit this, with or without `-T`.

## Files changed

`.github/workflows/ci.yml`, `.github/ci/changed-areas.sh`, `.github/ci/scale-tests.txt`, `Build-SDV-All.ps1`, `docs/work/0086-task.md`, `docs/work/0086-report.md`.

## Commits

In PR #49: `0693d54` task, then the CI and `-Quick` commit, the fix, the merge of `release/vero-r7`, and this report.

## Decisions taken

- No `-T` in CI: `sdv-setup` and `sdv-web` tests would use the same scratch database at the same time, and the scale tests have time limits.
- No `-T` in the default build: it saves little and was proven for tests, not for the full build.
- Only the two longest tests moved: more runners give little, as `backend` and the scale jobs are now within about a minute of each other.
- No new GitHub action (a partial Maven cache would need one): that is a dependency and needs the owner.
- A frontend change still runs the backend, as a careful default.

## Not done, and why

- Items 4 and 5 and the local times of `Build-SDV-All.ps1`, the schema checks and `ui:capture`: the side session may not run a full build or use the local services. Main queue.
- The skip path is proven by the script's cases only. The first docs-only pull request (0087) is its first real run.

## Questions for the owner: none

## Suggested next step

Items 4 and 5 in the main queue.
