# Report 0084: The demo-seed check runs again

Date: 2026-11-24
Branch: `fix/demo-seed-check` (from `release/vero-r7` at `bd5c9b0`)
Outcome: DONE. One script changed.

## What was done

**Report 0071 gave the wrong cause**, and task 0084 repeats it: the check did not start the release jar (it has used the
local build since 0064). It failed because

1. it started Vero in its own run folder, where the local profile's relative file `dev/setup/development-trusted-keys.json`
   isn't found (`SDV-SYS-005`); it now starts from the repository root, as start-local does;
2. it started Vero on an empty database without the schema step, which Vero needs since 0041; it now runs the schema step
   on the scratch database first;
3. its "nothing changed" comparison held `lic_last_seen`, the licence clock's latest time seen, which every setup run moves
   forward by design. That one table is left out, with the reason in the script; 54 tables are still compared.

It also uses 127.0.0.1, where Vero listens since 0064. **0064's refusal is untouched:** no Java file changed. Report 0071 is
corrected.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 72 + 2 + 4 + 657 + 10 = 788, 0 failures, 26 skipped (0064's refusal test is among them) |
| `.\Test-SDV-DemoSeed.ps1`, 1st run | FAIL: my edit had lost backslashes in a path |
| 2nd run | FAIL: "Seed run 2 changed nothing": `lic_last_seen` (point 3) |
| 3rd run | **PASS**, with the hold release by two admins and both resets; scratch database `sdv_seed_test`, dropped after. `-WithTour` not used (the full tour ran in 0071) |

## Files changed

`Test-SDV-DemoSeed.ps1`, `docs/work/` (this report, the task, `0072-answers.md`, the correction in `0071-report.md`).

## Decisions taken

- **Not in CI.** The check is Windows PowerShell 5.1 with `docker exec` into the container `sdv-postgres` and a Vault with
  the application's token file; CI's job is Ubuntu with a service database and no Vault. Porting the seed and its scripts is
  a task of its own, and 0076 changes the key service anyway.

## Open points and questions for the design chat

None.
