# Report 0095: Vero ready for the owner to test

Date: 2026-11-25
Branch: `chore/owner-test-ready` (from `release/vero-r7` at fd3079e)
Outcome: **DONE** (the owner's first run on the real `sdv_vero` and Vault is open)

## The owner's commands

```
cd C:\dev\sdv\secure-document-vault
git checkout release/vero-r7
git pull
.\Set-SDV-DevKeycloak.ps1        # once: adds the second Business Admin and Platform operations
.\Start-SDV-OwnerTest.ps1        # the first time asks for a Vault token (docs\OWNER-TEST-GUIDE.md, "Start")
```

Then http://localhost:5183/ and `docs\OWNER-TEST-GUIDE.md`. Stop: `.\Stop-SDV-OwnerTest.ps1`.

## What was done

1. `Start-SDV-OwnerTest.ps1` and `Stop-SDV-OwnerTest.ps1` (no other script is changed by them; nothing is dropped, ever).
2. `docs/OWNER-TEST-GUIDE.md`: start, restart, people, one page per role, what isn't built.
3. `Set-SDV-DevKeycloak.ps1`: two test people more (there was none for Platform operations, and releasing a hold needs two Business Admins).
   `e2e/keycloak-live.mjs` reads a digit in their names.

## Proof: scratch database `sdv_owner_test`, scratch key service, the local Keycloak

| Run | Result |
|---|---|
| 1 | built, database made, schema step, setup; the demo data failed: it runs setup again, and the token was gone by then. Fixed |
| 2, after that failure | went on where it stopped; started (85 s) |
| 3, while running | "already running", nothing started |
| Stop, start again | everything "there already"; 23 s |
| A real browser, on the running screens | first: no sign-in page (the development screens need `VITE_SECURITY_MODE`). Fixed; then Business User, Document Steward (a document opens), Restricted Steward, Business Admin and IT Admin each see their own areas; no group: refused `SDV-AUT-005` |

`sdv_owner_test` was dropped at the end; no other database was touched. `.\Build-SDV-All.ps1` on this branch: 42 + 72 + 2 + 715 + 14 tests, 0
failures, 53 skipped. `ProductNameGuardTest` on the final files: pass.

## No other product's name or behaviour in code: yes

`lexa-keycloak` in the script is the name of a container of this computer, as in `CLAUDE.md`.

## Decisions taken

- The token is asked for once, kept only as a secure string, and is in the environment of setup and the demo data only.
- The demo data needs Vero without sign-in for a minute: on port 8291, this computer only, then stopped.
- The demo data is made once for a database: running it again would put back what the owner changed while testing.
- It builds by itself when the build isn't of this version.

## Not done, and why

- The token prompt with the real Vault, and the two new people in Keycloak: both need the owner's sign-in. `Set-SDV-DevKeycloak.ps1` was only
  checked for syntax and its file reading; its way of adding people is 0088's.
- The rows of the guide were written from the screens and guides; not every one was clicked through.

## Questions for the owner

None.

## Suggested next step

Back to 0080: `docs/work/0080-report.md` on local branch `integration/f-erase-log` says what is left.
