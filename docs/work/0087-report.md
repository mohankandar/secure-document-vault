# Report 0087: Plan for remote testers

Date: 2026-11-24
Branch: `plan/remote-testers`
Outcome: DONE (docs only). To be merged through PR #50 once `ci-passed` is green on the final commit: see "CI".

## What was done

`docs/work/0087-plan.md`: three tunnel options compared, what Vero, Keycloak and the proxy need, tester accounts, numbered steps marked owner or session, and checks. Read only: no code, no start, no local service.

Points found on the way that matter most:

1. Local mode must never be behind a tunnel: its loopback check does not protect it there.
2. The release-type jar listens on every network card unless `SERVER_ADDRESS` is set.
3. People's calls and sign-ins have no rate limit in Vero (only intake has).
4. Access can't be put in front of the login address: Vero's discovery call and the browser's token call would fail.

## No other product's name or behaviour in code: yes (no code)

## Commands run and results

Reading files and searching; web search and reading public provider pages. No build.

## Tests: none (docs only); CI runs on the pull request

## CI: first run under the new workflow (task 0086)

`release/vero-r7` (with PR #49, merge `091742c`) was merged into this branch as `714b58e`; CI run 45430896307:

- Ran and passed: `changes`, `product-name-guard`, `ci-passed`.
- Skipped, as `changes` said (only `docs/work/*.md` differs): `backend`, `backend-scale`, `frontend`, `sender-profile-contract`.

So `ci-passed` is green with no build or tests on that run other than the product-name guard. The run before, on `8399855` under the old workflow, passed every job. This report change gets its own CI run before the merge.

## Files changed

`docs/work/0087-task.md`, `0087-plan.md`, `0087-report.md`.

## Decisions taken

- Recommended: a named Cloudflare Tunnel with Access. Reason: the only compared option found with a per-person gate.
- A separate tester Keycloak and database, so the development ones are not changed.
- Release-type jar from a development build: no local sign-in in it, and the demo licence works.
- Intake is closed to the outside; testers don't send documents.

## Not done, and why

- Nothing was tried out. Some provider facts could not be confirmed on their own pages (plan section 8).

## Questions for the owner

1. Cloudflare with a domain, or Tailscale Serve?
2. Share the development Transit keys for the tester database (recommended for a short test), or separate ones?

## Suggested next step

The local Keycloak sign-in check (report 0065), then a task for plan step 3.
