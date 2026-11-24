# Report 0065: sign-in in the browser

Date: 2026-11-24
Part A: plan, merged as PR #40. Part B: branch `feature/browser-sign-in` (from `release/vero-r7` at `2f0c94a`).
Outcome: **built and checked; stopped: owner** for the live check with the local Keycloak (below). Not merged. No schema
change, no new dependency.

## What was done (Part B)

1. **Token checks (VSEC-11).** `exp` and `iat` required, not issued in the future, at most 2 hours (a setting, 0 refused),
   type `JWT` or `at+jwt`, no `nonce`, `at_hash` or `c_hash`. A connection whose API audience is its browser client is not used.
2. **`GET /api/v1/session/sign-in`**, open: the login system's addresses (from its discovery document, checked, kept 5
   minutes) and the public client. Codes `SDV-AUT-014` to `017`.
3. **The browser:** authorization code with PKCE and state; tokens in memory only (VSEC-22); refresh before the end; its own
   pages for signed out, refused and not reachable; a notice over the page when a sign-in ends; sign-out without an ID token,
   in every tab; switching entities without a reload. No ID token is used. Local test sign-in exists in development builds only.
4. **Content-Security-Policy** in the built page, and the stricter one in the runbook with what the customer's IT sets up.

## Owner step: the live check with the local Keycloak

The session's permission check refused signing in to the Keycloak container's administration, so the web origin could not
be looked at or added, and no person could be made for the check. Nothing in Keycloak was touched. In a quiet moment:

1. Keycloak administration, realm `document-platform-local`, client `sdv-web`: redirect address and web origin
   `http://localhost:5183` (with `/` for the redirect), standard flow on, client authentication off, PKCE S256.
2. Build the branch, schema step on `sdv`, `.\Start-SDV-OIDC.ps1`, open `http://localhost:5183`, sign in as a person of
   the realm with a mapped group: land on an entity, open a document, sign out.
3. Say so, or allow the session to do it; then the pull request can be merged.

## Live check without Keycloak (scratch database, a small login system made for it, the built screens with the runbook's headers)

| Step | Result |
|---|---|
| API: no token; `nonce`; `at_hash`; no `iat`; no `exp`; 3 hours; another audience | 401 each |
| API: a good `at+jwt` token | 200 |
| Start, sign in, land | Signed in by name and role, on the page asked for; no code in the address; no token in any storage |
| Open a document, print | Page drawn; print frame with its pictures |
| After 54 s of a 60 s token | Refreshed by itself, no new sign-in |
| Reload | Signed in again through the login system |
| Sign out, reload | Signed out, stays signed out |
| Policy violations | 0 |

It found three faults, all fixed: a token **without an issue time was accepted** (Spring makes one up); the screens'
stylesheet imported its fonts from outside, and under the policy that **left the page blank** (now a plain link that can fail).

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 72 + 2 + 4 + 646 + 10 = **777**, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 77 passed; pass |
| `.\Test-SDV-SchemaUpgrade.ps1` | PASS in 561 s |
| `.\Test-SDV-CleanInstall.ps1` | First **failed** at step 6 (`SDV-AUT-003` instead of `006`): its own test token lived 2 hours and 5 seconds. Its set-up now makes an hour's token (`test-tools/KeySetServer.java`); then PASS in 119 s |
| `npm run ui:capture` | **32 passed, 18 skipped**, with a sign-in step, on a scratch database (as in 0066). The step once failed in its own set-up (the page was not loaded again) |

## Tests: counts, and any failures with their output

New: `AccessTokenShapeValidatorTest` (3), `BrowserSignInTest` (4), one in `SignInBaselineTest`, `auth.test.ts` (11), a tour step.
Assertions changed because the task changes the behaviour: `SignInBaselineTest` (`at+jwt` and `application/at+jwt` now sign
in), `SignInChainTest` (`nonce`, `at_hash`, `c_hash` in a sending system's token: `SDV-AUT-003` instead of `005`). Set-up
changed: the decoder's lifetime argument in two test kits (0 is refused now).

## Decisions taken

- **Not merged:** the task asks for the Keycloak check.
- The audience check is made when the connection is read, not at start: Vero starts while the database is away.
- Sending systems' tokens are checked the same way. A client with tokens over 2 hours, or without `iat`, is refused after an
  upgrade: an upgrade note in the IT guide and runbook, not a new contract version (the contract names no lifetime).
- Two customers with different browser clients on one installation: sign-in is not offered (`AUT-014`), not guessed.
- The fonts stay where they come from today (two outside hosts, allowed by the policy, may be left out).
- `SDV-AUT-018` of the plan is not needed: `SDV-AUT-003` says it.

## Not done, and why

- The Keycloak check (above).
- `Start-SDV-OIDC.ps1` was adapted (no login system settings for the screens any more) and not run.

## Questions for the owner

1. Bundle the fonts (IBM Plex, open licence) instead of loading them from outside? Recommended: yes, for customers
   without internet access.

## Suggested next step

The owner step; then merge.
