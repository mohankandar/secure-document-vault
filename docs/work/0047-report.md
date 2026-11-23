# Report 0047: Dependabot for Actions and npm

Date: 2026-11-22
Branch: `ci/dependabot` (from `release/vero-r7` at `ad35d5c`)
Outcome: DONE (the file is merged; two owner steps are needed before Dependabot pull requests can pass CI, see below)

No other product's name or behaviour in code.

## What was done

Added `.github/dependabot.yml`, the only file the task approves:

- `github-actions`, directory `/`: weekly (Monday). The workflow actions stay pinned to commit SHAs; Dependabot updates the SHA and the `# vX.Y.Z` comment together. At most 2 open pull requests. Commit prefix `ci`.
- `npm`, directory `/frontend/sdv-ui`: weekly (Monday). One group, `npm-minor-and-patch`, puts every minor and patch update into one pull request. Majors are in no group, so each major gets its own pull request. At most 3 open pull requests. Commit prefix `build`.
- Total: at most 5 open Dependabot pull requests (2 + 3; the limit is set per ecosystem).
- `target-branch: release/vero-r7` for both, so the pull requests go to the working branch and get the normal CI run (CI runs on pull requests to `release/vero-r7` and `main`).
- No auto-merge workflow. Each Dependabot pull request waits for `ci-passed` and the owner's merge.
- Maven: not added (see "How to add Maven later").

## Commands run and results

- `git checkout release/vero-r7; git pull --ff-only`: fast-forward `1c9442c..ad35d5c`. `git checkout -b ci/dependabot`.
- YAML parse of `.github/dependabot.yml` with SnakeYAML 2.7 from the local Maven cache (no new dependency): parses, structure as intended.
- `cd backend; mvn -B -ntp -pl sdv-web -am test -Dtest=ProductNameGuardTest -Dsurefire.failIfNoSpecifiedTests=false`: BUILD SUCCESS. The guard scans YAML under `.github/`, so it covers the new file.
- GitHub CI on the pull request: see "Tests".
- Not run: `.\Build-SDV-All.ps1`. The change is one configuration file read only by GitHub; no Java or frontend code changed. CI runs the full build on the pull request.

## Tests: counts, and any failures with their output

- ProductNameGuardTest: 3 run, 0 failures, 0 errors, 0 skipped.
- CI on the pull request: recorded in the pull request; `ci-passed` must be green before merge.

## Files changed

- `.github/dependabot.yml` (new)
- `docs/work/0047-task.md` (task, committed first)
- `docs/work/0047-report.md` (this report)

## Commits (hash and message), or "not committed"

- `e2040a4` docs(work): task 0047, Dependabot for Actions and npm
- (next) ci: Dependabot for Actions and npm, report 0047 (task 0047)

## Not done, and why

- Maven ecosystem: not approved yet (task).
- No change to `ci.yml`: not approved in this task (see question 1).

## Questions for the owner

1. **Dependabot pull requests will fail CI until a Dependabot secret exists.** GitHub does not give Actions secrets to workflow runs started by Dependabot; those runs only see Dependabot secrets. The repository has the Actions secret `PLATFORM_PACKAGES_TOKEN` but no Dependabot secrets. So on every Dependabot pull request, the `backend` job stops at "Check the packages token is set" and `product-name-guard` cannot fetch platform-core, and `ci-passed` is red. This also applies to npm and Actions updates, because CI always runs the backend. Fix, owner only: Settings → Secrets and variables → Dependabot → New repository secret, name `PLATFORM_PACKAGES_TOKEN`, the same read:packages token. `ci.yml` needs no change because it already reads `secrets.PLATFORM_PACKAGES_TOKEN`.
2. **Dependabot reads its configuration from the default branch only.** The default branch is `main`, and this pull request goes to `release/vero-r7`. Dependabot starts once `.github/dependabot.yml` is on `main` (with the next release merge into `main`, or if the default branch is changed to `release/vero-r7`). With `target-branch: release/vero-r7` its pull requests then go to the working branch.
3. Side note: `release/vero-r7` has no branch protection and the repository has no rulesets (the GitHub API answers "Branch not protected"). "Nothing merges by itself" currently depends on there being no auto-merge, not on a required check. If you want `ci-passed` to be required on `release/vero-r7`, that is a repository setting for you to change.

## How to add Maven later

1. Add the token as a Dependabot secret (the same one as in question 1, so this may already exist).
2. In `.github/dependabot.yml`, add a top-level `registries:` entry, for example `platform-packages:` with `type: maven-repository`, `url: https://maven.pkg.github.com/<owner>/<platform-core repository>`, `username: x-access-token` (or the token owner's login) and `password: ${{secrets.PLATFORM_PACKAGES_TOKEN}}`.
3. Add an `updates:` entry: `package-ecosystem: maven`, `directory: /backend`, `target-branch: release/vero-r7`, `registries: [platform-packages]`, weekly.
4. Keep platform-core manual: add `ignore: [{dependency-name: "<platform-core groupId>:*"}]` so Dependabot never bumps it.
5. Keep the total at 5: lower the other limits (for example Actions 1, npm 2, Maven 2).

## Suggested next step

Owner adds the Dependabot secret (question 1). After the next merge into `main`, check Insights → Dependency graph → Dependabot that both ecosystems show "last checked" with no errors.
