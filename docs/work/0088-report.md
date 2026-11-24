# Report 0088: Keycloak set-up and the real browser sign-in check

Date: 2026-11-24
Branch: `chore/keycloak-live` (from `release/vero-r7`; the first branch, `chore/keycloak-check`, is merged)
Outcome: **Part A DONE. Part B waits for the owner's step** (below).

The first try stopped because the session may not read the folder the `dev-automation` secret was in (PR #54). The task was
rewritten; that client is not used.

## The owner's step

```
cd C:\dev\sdv\secure-document-vault
git checkout release/vero-r7
git pull
.\Set-SDV-DevKeycloak.ps1
```

It asks for the Keycloak admin user and password. Then set 0088 to `ready`, or run Part B yourself: `.\Build-SDV-All.ps1`, then
`.\Test-SDV-KeycloakLive.ps1` (Vero stopped; port 5183 free).

Also: `docker rm -f vero-keycloak-scratch-0088` (removing the scratch Keycloak was refused to the session; it holds made-up
accounts only).

## Part A

- `Set-SDV-DevKeycloak.ps1`: client `sdv-web`, five test people (one per Vero role) and one in no group. Only
  `http://localhost:8181` and realm `document-platform-local`. Signs its admin session out at the end.
- `Test-SDV-KeycloakLive.ps1` with `frontend/sdv-ui/e2e/keycloak-live.mjs`: Vero on the scratch database
  `sdv_keycloak_test`, the built screens, a real browser.
- `dev/setup/role-mapping-SDV.csv`: the group `RestrictedStewards` (demo data; there was none for that role).

## Proof, on a scratch Keycloak 26.8.2 (port 8189; never the development one)

A copy of the script with only the address changed; the prompts were answered by a stand-in.

| Case | Result |
|---|---|
| another address, the master realm, a file inside the repository | refused |
| a wrong admin password | refused, no file made |
| `sdv-web` there but loose (direct grants, `*` origin, another redirect) | set right; the other client unchanged |
| no `sdv-web` and no people | added |
| second run | "nothing changed" |
| the password file lost | warned, exit 1; with `-NewPasswords` new ones, then "nothing changed" |
| rights on the file | this Windows user only |

`Test-SDV-KeycloakLive.ps1` against it (the scratch database pointed at it): **20 of 20 pass**, on the code with platform-core
1.0.0. Each role signs in with its role; PKCE S256; no token in storage; a document opens; the sign-in is renewed; a reload
signs in again; signing out stays and Keycloak asks again; no group: refused (`SDV-AUT-005`); a token of another client:
refused (`SDV-AUT-003`); no CSP violation. Without the file of test people it stops and says the owner's step is open (exit 2).

The first two runs failed on two checks, both mistakes of the new check itself: the Business Admin has two entities and
chooses one first, and a refused person does see the empty frame with the refusal in it.

| Other check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 4 + 674 + 10 = 805 tests, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 77 passed; pass |

## Part B

Open: `C:\dev\sdv\dev-test-users.properties` isn't there.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- The other client is Keycloak's own account page, so nothing has to be added for it. The renewal is brought forward with the
  browser's clock instead of waiting four minutes.
- The script adds a missing group. A test person that is in other groups, or disabled, is reported and left as it is.
- The realm's groups are shared with whatever else uses that realm: a test person gets what those groups give there too.
