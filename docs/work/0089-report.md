# Report 0089: Tester set-up through Cloudflare Tunnel

## Part A

Date: 2026-11-24
Branch: `chore/tester-setup` (from `release/vero-r7` at `f8d0101`)
Outcome: DONE. Nothing of the tester instance was started.

## What was done

- `dev/testers/`: `cloudflared` template, Caddyfile, compose and realm of the tester Keycloak, sign-in and role files, settings
  example. No secrets.
- `Start-SDV-Testers.ps1`, `Stop-SDV-Testers.ps1`, `Test-SDV-TestersProxy.ps1`.
- Backend (module `sdv-local`, not in the release jar): `LocalTunnelCheck` stops a local start behind a tunnel or proxy;
  `LocalHeaderAuthenticationFilter` refuses a forwarded local sign-in.
- Runbook section "Remote testers", with the owner's steps.

## No other product's name or behaviour in code: yes (guard test passed)

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o -q -pl sdv-web -am test` with `-Dtest=LocalTunnelCheckTest,TesterTunnelConfigTest,LocalSignIn*Test,LocalHeader*Test,SwitchedOffCheckTest,ProductNameGuardTest,SignInCodesTest` | passed |
| `.\Test-SDV-TestersProxy.ps1` (throwaway Caddy 2.11.4 container, no port published, removed) | 57 of 57 |
| `.\Start-SDV-Testers.ps1 -CheckOnly` with one good and 10 bad settings files | good accepted, 10 refused |

First proxy run: 7 of 57 failed. Both causes were in the new check, not in the Caddyfile: it closed its side of the
connection, which cancels a proxied request, and it expected `/index.html` to redirect. Corrected; the forbidden addresses
were refused in both runs.

The session's permission check denied two combined commands (a rerun of the proxy check with edits in the same line, and a
settings check). Neither was sent again as it was: the edits were made in the editor, and the same two checks were then run as
plain single steps, which were allowed.

## Tests: 11 new (`LocalTunnelCheckTest` 6, `TesterTunnelConfigTest` 5); no assertion of an older test changed. Full build: CI

## Files changed

`dev/testers/*` (8), three scripts, the two `sdv-local` classes, two tests, the runbook, `docs/work/0087-answers.md`,
`0089-task.md`, this report.

## Decisions taken

1. **One web address, not the plan's two.** The task says only Vero's web address is behind the tunnel and nothing else
   answers. The sign-in paths are on it, behind Access, limited to what a sign-in needs. Price: Vero reads the discovery page
   at the public address, so that one page needs an Access bypass (owner step 5), and the login pages share Vero's origin.
2. Local mode is refused while `cloudflared` or `ngrok` is visible on the computer, whatever it is for. While testers are on,
   a local start (also the main queue's) is refused: stop the tunnel first.
3. Testers' own customer (`TEN-VERO-TESTERS-0001`), so that the key is its own, as the answers say. No sending client is bound.
4. The start scripts of the development instance are unchanged; the refusal is in Vero, so it holds for every way to start.

## Not done, and why

- Not tried, for Part B: the tunnel, Access, the Keycloak realm import, sign-in, `kcadm`, starting Vero with these settings,
  and `Stop-SDV-Testers.ps1`.
- Not confirmed on Cloudflare's pages: that an application on a path takes precedence over the one on its hostname (the page
  describes paths of one root), and the free plan's user limit. Part B proves the first from outside.
- `KeySettings` can't be filled in before 0076 names its settings. Its names must be among those `Start-SDV-Testers.ps1`
  accepts (`SDV_KEY_`, `SDV_VAULT_`, `PLATFORM_CRYPTO_`, `SPRING_CONFIG_IMPORT`).
- The demo seed uses the demo licence and slots; Part B needs it for the testers' customer (0076 changes the seed, so not here).

## Owner only

Runbook, "Remote testers", steps 1 to 10. Step 10 (the testers' licence, signed with the development key) is new: the key is
not in the repository.

## Suggested next step

The owner's steps; Part B after 0082.
