# Report 0090: named seats survive a restart

Date: 2026-11-24
Branch: `fix/named-seats` (from `release/vero-r7` after 0076)
Outcome: **DONE.**

## What was done

- Changeset 046: `usage_named_seats` (entity, month, keyed hash of the person). The month's count is its number of rows, so a restart
  changes nothing and several Vero processes count a person once.
- The hash is the key service's HMAC with the customer's fingerprint key, over entity, month and the sign-in's subject. Without
  that key nobody can make or turn back a hash, or follow a person from month to month. Two key service calls per run, however
  many people; with the key service away the people wait in memory and the next run writes them; a planned stop writes them too.
- Rows older than the previous month are deleted once a day. Numbers in the ledger stay.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 43 + 72 + 2 + 692 + 10 = 819 tests, 0 failures, 28 skipped |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 158 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS (passed in 529 s); an earlier run failed and found a real defect: every person had the same hash text (platform-core's fingerprint hides itself in `toString`); fixed |

`NamedSeatsPostgresTest`: restart, two processes, a rotated key, another entity and month, no name and no plain hash in a row, the key
service away, the clean-up. No screen changed, so no tour.

## Tests whose assertions changed

`LicensingTest`: the named count moved from memory to the table; the hand-over to it is tested instead.
`ChangesetChecksumBaselineTest`: one new line.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Decisions taken

- The oldest usable version of the fingerprint key is used, so a rotation in the middle of a month counts nobody twice.
- Without a key service (a local start with it switched off) named seats are not counted.
- People seen in the last five minutes before a crash are counted again when they next use Vero that month.
- The clean-up is in the usage job (once a day), not in the document clean-up, which has its own time and report.
