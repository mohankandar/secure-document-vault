# Report 0075: licensing on (integration C)

Date: 2026-11-24
Branch: `integration/c-licensing` (from `release/vero-r7` after 0074)
Outcome: **DONE.** Interrupted once by a restart of the computer.

## What was done

1. **State:** Business administration, Licence and IT, About: state, dates, entities, this entity's use against its allowance.
   Never a price.
2. **Intake** (create submission, upload, retry of a finalization) is refused when the licence doesn't allow it: 503
   `SDV-LIC-001`, or `SDV-LIC-002` when the licence can't be checked; no `Retry-After`; after sign-in, before anything of the
   request is read. Status queries and everything else go on. Tested for every state, each side of every date.
3. **Usage**, recorded, never enforced: storage (whole GB, rounded up, daily), people at the same time (15 minutes, the day's
   highest), stewards and administrators of the month. Numbers only.
4. **Usage export** for IT (`usage_export_anchor`, changeset 045), audited. The audit holds the anchor of every export, so a
   missing, older or replaced one is refused (`SDV-LIC-003`); only an explicit new series goes on, and the audit says so.
5. The demo licence is version 2 with the three allowances, signed with a new throw-away development key; the old key stays
   trusted in development builds, so an existing local database still starts. Contract 1.4.0, sender profile, error codes, guides.
   The runbook has "The licence" with renewal and clock reset.

**Vero now starts only with a licence.** On a new local database: `.\Seed-SDV-Canvas-Demo.ps1 -SetupOnly`, start, seed.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 4 + 687 + 10 = 818 tests, 0 failures, 27 skipped |
| `npm test`, `npm run typecheck` | 81 passed; pass |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 181 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS, 585 s (45 changesets; the new UsageExportPostgresTest and the profile test against a running Vero included) |
| `npm run ui:smoke`, scratch database | 7 passed, in each of the last two runs; three earlier runs had failures (below) |
| `.\Test-SDV-KeycloakLive.ps1` (live Keycloak) | 20 of 20 |
| `.\Test-SDV-DemoSeed.ps1` | PASS |

Failed on the way, and fixed: the licensing module put the licence into the overall health address, which then waited for the
database (clean install 5c; taken out, the licence is in the check list); the tour found "no prices" in Vero's own words; the
schema script had to learn the new table.

## Tests whose assertions changed

- `VeroSetupFilesTest`: the demo licence is version 2. `ChangesetChecksumBaselineTest`: one new line, none changed.
- `IngestionContractTest`: `after-fix` also for the two licence codes; new scenarios.
- `SenderProfileServerContractTest`: gives its Vero a licence.
- Clean install: 0a and 0b moved to 2e and 2f (without tables and licence the module stops the start first); step 1 and 5a report
  the licensing module's codes; new step 2d.

## Not done, and why

The local `sdv` stays at changeset 042 until the owner's schema step.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- **Named seats are counted in memory**, so a restart in the middle of a month starts that count again; the ledger keeps the
  month's highest. Keeping people across restarts needs a table, and only one changeset (the anchor) was approved. Question below.
- Named seats count stewards, Business Admin, IT Admin and Platform operations. Seats are counted per Vero process.
- A release build without release licence keys doesn't start at all now (the module's rule). There are none yet.
- An old `sdv` keeps its licence version 1 until setup runs again; it starts with it.

## Questions

1. Should named seats survive a restart? It needs a small table (entity, month, hash of the person), kept for the month only.
