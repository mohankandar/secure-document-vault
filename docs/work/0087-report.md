# Report 0087: Plan for remote testers

Date: 2026-11-24
Branch: `plan/remote-testers`
Outcome: DONE (docs only). Not merged: see "Not done".

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

## Files changed

`docs/work/0087-task.md`, `0087-plan.md`, `0087-report.md`.

## Decisions taken

- Recommended: a named Cloudflare Tunnel with Access. Reason: the only compared option found with a per-person gate.
- A separate tester Keycloak and database, so the development ones are not changed.
- Release-type jar from a development build: no local sign-in in it, and the demo licence works.
- Intake is closed to the outside; testers don't send documents.

## Not done, and why

- **Merge:** the session's permission check refused merging a pull request (PR #49), so this one was not tried. The owner merges it when `ci-passed` is green.
- Nothing was tried out. Some provider facts could not be confirmed on their own pages (plan section 8).

## Questions for the owner

1. Cloudflare with a domain, or Tailscale Serve?
2. Share the development Transit keys for the tester database (recommended for a short test), or separate ones?

## Suggested next step

The local Keycloak sign-in check (report 0065), then a task for plan step 3.
