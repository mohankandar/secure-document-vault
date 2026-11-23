# Report 0046: CI follow-ups from report 0042

Date: 2026-11-22
Branch: `ci/follow-ups` (from `release/vero-r7` at `42fea86`), PR #19
Outcome: DONE

## What was done

1. The `frontend` job runs `npm test` after `npm run typecheck` (before `npm run build`).
2. Every Action is on its current major, pinned to the full commit SHA, with the version in a comment. All four now run on node24.

| Action | Before | Now |
|---|---|---|
| `actions/checkout` | `@v4` | `@3d3c42e5aac5ba805825da76410c181273ba90b1 # v7.0.1` |
| `actions/setup-java` | `@v4` | `@d1374f8ac74474200d16ef8ecee22c7ae7a51880 # v6.3.0` |
| `actions/setup-node` | `@v4` | `@949feb2413d6458794dcd2491c4babbbce0c15c1 # v7.1.0` |
| `actions/upload-artifact` | `@v4` | `@cf430e030ddbb5b0abf93d22962f4752f3646cd9 # v7.0.2` |

The versions come from each repository's latest release (`gh api repos/actions/<name>/releases/latest`). The SHA is the commit behind that tag. The node24 runtime was read from each release's `action.yml`.

## Commands run and results

| Command | Result |
|---|---|
| `cd frontend/sdv-ui; npm run typecheck` | passed |
| `cd frontend/sdv-ui; npm test` | passed, 1 file, 19 tests |
| CI on PR #19, run 45092698905 | all jobs passed: backend, frontend, product-name-guard, sender-profile-contract, ci-passed |
| `gh run view 45092698905 --log`, searched for "Node.js 20" / "node20" | 0 matches |
| Check-run annotations for all five jobs | none (no warnings) |

## Tests: counts, and any failures with their output

- Frontend (CI log): `Test Files 1 passed (1)`, `Tests 19 passed (19)`.
- ProductNameGuardTest (CI): 3 run, 0 failures, 0 skipped.
- PostgreSQL-only tests (CI): all 7 classes ran, none skipped.
- No failures.

## Files changed

- `.github/workflows/ci.yml`: Action pins and the `npm test` step.
- `docs/work/0046-task.md`, `docs/work/0046-report.md`.

## Commits (hash and message), or "not committed"

- `3f7b712` docs(work): task 0046, CI follow-ups
- `03eec0e` ci: run frontend unit tests; Actions on current majors pinned to commit SHAs (task 0046)
- This report, in its own commit on the same branch.

## No other product's name or behaviour in code

No other product's name or behaviour in code, apart from the guard's listed exceptions. Those are unchanged: `product-name-guard/` has no changes since `42fea86`. This task changed only the CI workflow.

## Not done, and why

Nothing left out. Dependabot (or similar) to keep the SHA pins current was not added because the task did not ask for it.

## Questions for the owner

- Should Dependabot for `github-actions` be set up so the SHA pins get update PRs? Without it, the pins stay where they are until someone moves them by hand.

## Suggested next step

Task 0041 (sender-profile contract job, empty `exceptions.txt`).
