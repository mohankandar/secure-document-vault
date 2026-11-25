# Report 0093: Release licence keys: the owner's procedure

Date: 2026-11-24
Branch: `chore/release-licence-keys` (from `release/vero-r7` at `2b3da8b`)
Outcome: DONE in the branch. No real key was made, held or seen. Vero, the database and Vault were not used.

## What was done

- `New-SDV-ReleaseLicenceKey.ps1` (owner): makes an Ed25519 key. The private key goes only to the named folder, for this Windows
  user only; the public key goes beside it and into `backend/licence-build/release`. Refuses an existing key, a folder in a
  repository, a cloud folder, a link, a network drive and a development key's name. `-PublishOnly` for a key made before.
- `New-SDV-Licence.ps1` (owner): signs from a small input file (`dev/licence/licence-input.example.json`). An unknown field, a
  price for example, refuses the file. Checks the licence with the keys the release build trusts before writing it.
- `tools/licence/VeroLicenceTool.java`: the Java part, run from source with platform-core's `platform-licensing` 1.0.0 (its
  format, its check). In no jar; no new dependency.
- `.gitignore`: `release/` hid `backend/licence-build/release`, so the public keys could not have been committed. Now they can;
  `*.private-key` is ignored.
- Runbook section "Release licence keys".

## No other product's name or behaviour in code: yes (guard test passed)

## Commands run and results

| Command | Result |
|---|---|
| `.\Test-SDV-ReleaseLicenceKey.ps1` (throwaway keys, temporary folder deleted after) | 28 passed, 0 failed |
| `mvn -o -q -pl sdv-setup -am test "-Dtest=com.sdv.platform.setup.*Test"` | passed (exit 0) |
| `mvn -o -q -pl sdv-web -am test "-Dtest=ProductNameGuardTest,PlatformCoreUseTest,ReleaseLicenceKeyTest"` | passed (exit 0) |

## Tests

New: `ReleaseLicenceKeyTest`. The setup command's own licence check accepts the licence of the build's key and refuses another
key (`LICENCE_UNKNOWN_KEY_ID`), another key under the trusted name (`LICENCE_FORGED`) and a changed licence
(`LICENCE_TAMPERED`). "Accepted" is shown by setup going on to its next check (`SETUP_WRONG_TENANT`: the demo sign-in file
belongs to another customer). One part runs only from the check script.

## The owner's commands (only the owner runs them)

```powershell
New-Item -ItemType Directory D:\vero-licence-keys
.\New-SDV-ReleaseLicenceKey.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026
.\New-SDV-Licence.ps1 -KeyFolder D:\vero-licence-keys -KeyId vero-release-2026 -InputFile D:\licences\testers-1.json -OutFile D:\licences\testers-1.licence.json
```

Then the offline copy, and commit `backend\licence-build\release` (runbook, "Release licence keys").

## Not done, and why

- A real release start: the main session proves it later (the task says so).
- Every check was made with Windows PowerShell 5.1 and Java 21 on this computer.

## Decisions, for the owner to know

- platform-core's guide signs inside Vault, where the private key never leaves. The task asks for a key file, so the file is
  protected by Windows rights and the folder's encryption only; it has no password of its own. Moving to Vault later: a new key
  ID; licences signed before keep working.
- No list of issued licences is kept: keep the input files; their `version` and IDs are needed for a renewal.

## Suggested next step

The owner's commands above.
