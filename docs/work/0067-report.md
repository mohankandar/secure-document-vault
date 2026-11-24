# Report 0067: Identity bump to platform-core v0.7.0

Date: 2026-11-24
Branch: `chore/platform-core-0.7.0` (from `release/vero-r7` at `42e2de7`)
Outcome: **PARTLY DONE, not merged.** Built, tested, both schema checks passed. **Stopped for the owner:** the local
schema step on `sdv` was refused by this session's permission check, so the live check and the screen tour can't run.

## What was done

`platform.core.version` 0.6.1 → **0.7.0**. Release notes read at the tag. What touches Vero's identity use:

| Change | Vero |
|---|---|
| Licence keys only in the build (SEC-12, SEC-43) | `backend/licence-build`: the Maven default is a release build (no keys yet); `-Dlicence.build=development` adds the demo key. `Build-SDV-All.ps1` makes a development build unless `-Release`, and says so; Vero warns at start. `SDV_TRUSTED_KEYS_FILE` is gone and stops the start. Setup wrappers lost `-Keys` / `--keys` and `-Development`; the demo step lost `--keys`. CI checks that the release jars hold no development key |
| `LicenceStore.highestVersion`, `ClockGuard` with the build time | Read-only store and clock adapted |
| Identity schema V5, licensing V2 | Schema step as released; schema checks and tests expect them |
| SEC-7: a person's token is never a push caller | Vero has its own sending-system check (0062). It now also refuses claims that name a person (`SDV_AUTH_JWT_PERSON_CLAIMS`); a test reads platform-core's default list and proves Vero covers it. `sub` was never a client-ID claim in Vero |
| Group names exact, a text claim is one group (SEC-45) | Comes with platform-core's own reading; tested through Vero's sign-in |
| Grants of a replaced login system (SEC-17) | Setup lists them; Vero's own query already joins on the connection's issuer |
| Integration secrets named per entity | Test only (`SenderProfileServerContractTest`) |
| Callbacks, erase log, Vault policies, model governance, fields, bundle, reference filter | Vero doesn't use these modules |

`docs/install/SETUP.md`: the rights to take away after the upgrade, exact group names, no keys file.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 26 + 69 + 2 + 4 + 633 + 10 = **744**, 0 failures, 25 skipped; both jars hold the development keys |
| `npm test` | 66 passed |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 112 s |
| `.\Test-SDV-SchemaUpgrade.ps1` | First run FAIL: two tests still expected 0.6.1 (a version number, a secret name); adapted. Second run **PASS** in 610 s |
| `Update-SDV-Schema.ps1 status` on local `sdv` | two to run: identity V5, licensing V2 |
| `Update-SDV-Schema.ps1 update` on local `sdv` | **refused** by the session's permission check; not worked around |
| Seed, live Keycloak check, `npm run ui:capture` | **not run**: Vero refuses to start on `sdv` until the step is done |

## Tests: counts, and any failures with their output

744 backend, 0 failures. Two tests new; version numbers and set-up adapted as above.

## Files changed

See the pull request. `dev/setup/development-trusted-keys.json` moved to `backend/licence-build/development/`.

## Commits

Task file; the change; this report. Pushed; pull request open, **not merged** (queue rule 5: the task's checks must pass).

## Decisions taken

- `Build-SDV-All.ps1` defaults to a development build (the daily tool; the demo and the tour need it); the Maven default
  and CI are release. Safer the other way round, but then nothing local works; a development jar warns at every start.
- A build without keys starts and refuses activation, as before without the setting.
- Vero has no release licence keys yet, so a release build activates nothing. That was so before, too.

## Not done, and why

The live checks, as above.

## Questions for the owner

1. Please run, with this branch built (`.\Build-SDV-All.ps1` on `chore/platform-core-0.7.0`):
   `.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv -Username sdv`
   **Only when you are ready to merge this branch:** identity V5 on `sdv` makes Vero builds without 0067 see a newer
   database and refuse to start.
2. To let a session do it: add a permission rule for `Update-SDV-Schema.ps1`.

## Suggested next step

Owner runs the step; then seed, live check, `ui:capture`, merge.

## 2026-11-24: brought up to date with `release/vero-r7` (`2f0c94a`)

Merged, two conflicts resolved (CI, the schema check). Built: 43 + 69 + 2 + 4 + 643 + 10 = 771 tests, 0 failures;
`Test-SDV-CleanInstall.ps1` and `Test-SDV-SchemaUpgrade.ps1` PASS. The owner's schema step from this branch now applies
identity V5, licensing V2 **and** changeset 043 (task 0066): check the branch out, `.\Build-SDV-All.ps1`, then
`.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv -Username sdv`.

## 2026-11-24: brought up to date again (task 0085, `release/vero-r7` at `bd5c9b0`)

Merged (no rebase), three conflicts resolved keeping both sides: the schema check (0071's 044 check with this branch's platform
versions), `SignInChainTest` (0065's refusal of an ID token's marks with this branch's person claims), `SETUP.md` (0065's
row kept; the keys-file row stays gone). Nothing else changed by hand.

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 72 + 2 + 4 + 659 + 10 = **790**, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 77 passed; pass |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 043` (a database at 042, as `sdv` is) | PASS in 499 s; applies 043, 044 and the platform versions |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 90 s |

The local schema step was not run and nothing was merged into `release/vero-r7` (task 0085). **The owner's step**, from this
branch, now applies identity V5, licensing V2, 043 and 044 together:

```
git checkout chore/platform-core-0.7.0
.\Build-SDV-All.ps1
.\Update-SDV-Schema.ps1 update -Url jdbc:postgresql://localhost:5433/sdv -Username sdv
```
