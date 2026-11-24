# Report 0064: Security fixes 3

Date: 2026-11-24
Branch: `fix/security-3` (from `release/vero-r7` at `b4c718f`)
Outcome: DONE in code. Owner steps for the database container below. No schema change, no new dependency.

## What was done

1. **Local profile.** It listens on 127.0.0.1. Local sign-in is refused (`SDV-AUT-008`) from another computer and under
   another Host than localhost, 127.0.0.1 or ::1. Vite's Host check is on again. The profile and its sign-in moved to a
   module of their own (`sdv-local`): the release jar leaves it out and refuses to start with the local profile
   (`SDV-SYS-005`). The local scripts use `backend\sdv-web\target\local\sdv-web-<version>-local.jar`.
2. **Compose.** Ports on 127.0.0.1; no password and no known Vault token in the files. The local database password is in
   `.runtime\local-db-password.txt` (untracked, readable by this Windows user only), made at first start, or taken from an
   existing container. `Start-SDV-Local.ps1` uses the restricted token file, as `Start-SDV-OIDC.ps1` does.
3. **CI.** Only one step has the package token; it compiles nothing and runs no tests. Every build and test runs offline
   without it, and checkouts keep no Git token.
4. **Packaging.** `git archive` of the commit (`scripts\packaging\New-SdvSourceArchive.ps1`): changed tracked files and
   key-like files are refused.
5. **Dependabot** watches Maven weekly. The Dependabot secret is there.

## Steps for the owner (nothing here was run)

**Database container** (`sdv-vault` is already published on 127.0.0.1 only and needs nothing). From the repository root
in PowerShell, in a quiet moment; the data is in the named volume `sdv-postgres-data`, which stays:

1. `.\Stop-SDV-Local.ps1`
2. A backup first: `docker exec sdv-postgres pg_dump -U sdv -Fc -f /tmp/sdv.dump sdv`, then
   `docker cp sdv-postgres:/tmp/sdv.dump <a folder outside the repository>`
3. `$env:SDV_LOCAL_DB_PASSWORD = & .\scripts\local\Get-SdvLocalDbPassword.ps1 -Root (Get-Location).Path`
4. `docker compose -p secure-document-vault -f compose.local.yml up -d --no-deps --force-recreate sdv-postgres`
   (a notice that the volume was not made by this project is harmless). Never `down`, never `-v`.
5. A new password, because the old one is in git's history: `docker exec -it sdv-postgres psql -U sdv -d sdv -c "\password sdv"`,
   then put the same new password, alone, into `.runtime\local-db-password.txt`.
6. `Remove-Item Env:SDV_LOCAL_DB_PASSWORD`, then `.\Start-SDV-Local.ps1`.

**The old development Vault token** in git's history. Whether the persistent Vault accepts it was not checked (Vault not
touched). With your own administrator sign-in to Vault: `vault token lookup <that token>`; if it is found,
`vault token revoke <that token>`. Vero no longer uses it.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 69 + 2 + 4 + 636 + 10 = **764**, 0 failures, 25 skipped |
| `npm test`, `npm run typecheck` | 66 passed; pass |
| `.\Test-SDV-CleanInstall.ps1`, `.\Test-SDV-SchemaUpgrade.ps1` | PASS in 114 s; PASS in 614 s |
| `.\Start-SDV-Local.ps1`, twice | Works (password taken from the container, then from the file). Both ports listen on 127.0.0.1 only |
| Live, local Vero | Own Host 200, through Vite 200; another Host: backend 401, Vite 403 |
| Release jar's contents | No `sdv-local`, no `application-local.yml`; the local jar has the module |
| Package helper | 891 committed files, an untracked probe file not in it; refused with a changed tracked file |
| CI | First run **failed**: `go-offline` alone misses versions the modules choose together. Fixed with a second resolve; then green |

## Tests: counts, and any failures with their output

5 new (`LocalSignInAddressTest`, one in `SwitchedOffCheckTest`). None changed. A first local start failed inside the new
password script (reading the container's settings); fixed, nothing was printed or written.

## Files changed

See the pull request.

## Commits

Task file; the fixes; this report. Merged through a PR on green `ci-passed`.

## Decisions taken

- **`Start-SDV-Local.ps1`:** the owner's uncommitted local changes already did what item 2 asks, so they are committed as
  they were (read first, no secret in them), with the jar's new place and the password lines added.
- A module, not a setting, keeps local sign-in out of a release: a setting could be set back.
- The local jar is in a folder of its own, so scripts that look for `sdv-web-*.jar` still find only the release jar.
- An existing container's own password is kept, so nobody's local data becomes unreachable.
- Dependabot leaves platform-core and Spring Boot's major versions alone: they move by hand.

## Not done, and why

- **Starting the release jar with the local profile to see the refusal:** the session's permission check denied that
  command, so it was not run. The refusal has a unit test, and the jar was checked to lack the module.
- `New-SDV-V1-GAPackage.ps1` and `New-SDV-V1-ReleasePackage.ps1` stop before packaging, on a document that is not in the
  repository (also before this task) and the first also on newer changesets. The shared helper was run on its own instead.
- `Start-SDV-OIDC.ps1`, `Start-SDV-WSL.sh` and `Test-SDV-DemoSeed.ps1` were adapted and parse, but were **not run**; nor was the demo seed.
- `ui:capture`: the task does not ask for it.

## Questions for the owner

1. Fix or retire the two V1 package scripts?

## Suggested next step

The owner steps above; 0067's schema step.
