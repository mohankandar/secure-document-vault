# Report 0066: Redaction of a document on legal hold

Date: 2026-11-24
Branch: `fix/held-redaction` (from `release/vero-r7` at `b6a05fe`)
Outcome: DONE. Changeset 043. No new dependency.

## What was done

1. **Request.** Redacting a held document answers 202 and makes a request (`REDACT_HELD`); nothing is changed. The reason
   stays in the request, never in the audit. A Business Admin decides it in Approvals, one at a time (never in bulk, never
   ticked), never their own; it expires like the others. The hold is checked again under the document's row lock when a
   redaction is staged and when it completes (`SDV-HLD-009`).
2. **Approval.** Vero applies it in the background (every 10 s, retried, given up after 5 tries). A document that changed
   since is not approved (`SDV-APR-017`). The original is **sealed**: kept encrypted where it is, out of every other path.
   Only a Restricted Steward reads it (`SDV-HLD-011`), in the viewer, by an opening of its own that is audited with each
   page. Admins have no way to it.
3. **End of the hold.** Once no hold is in place, the sealed original is destroyed with its data key and audited
   (`SEALED_ORIGINAL_DESTROYED`).
4. Documents not on hold: unchanged.
5. `error-codes.md` and the texts on the screens say so. The admin guide itself is task 0068; it is not in the repository yet.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 69 + 2 + 4 + 641 + 10 = **769**, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 66 passed; pass |
| `.\Test-SDV-SchemaUpgrade.ps1` (from 042) | PASS in 557 s with `HeldRedactionPostgresTest`. Earlier runs failed (below) |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 110 s |
| `npm run ui:capture` | **31 passed, 17 skipped**, with a new step. **On a scratch database**, not on `sdv` (see Decisions) |

## Tests: counts, and any failures with their output

New: `HeldRedactionApprovalTest` (4), `HeldRedactionPostgresTest` (1, PostgreSQL). Adjusted: `StoredJsonBaselineTest` (one
more argument, more checked), the checksum baseline (one line added, none changed), constructor arguments.

Failures on the way: the schema check twice listed the two new tables as a change (its list of new tables was extended, as
for earlier changesets); the new PostgreSQL test once failed on its own SQL (`?` operator). `LegalHoldServiceTest` failed
on my first design, which refused a hold during a redaction; the design was changed, the test was not. A schema run with
small scale settings failed in `QuarantineScalePostgresTest`, which needs its full size; the full run passes.

## Files changed

See the pull request.

## Commits

Task file; the change with this report. Merged through a PR on green `ci-passed`.

## Decisions taken

- **A hold can always be placed**, also while a redaction runs. If it comes before the redaction completes, the original
  is sealed all the same (without a request).
- **No print or download of a sealed original at all.** The task allows it through the Highly Confidential grant; reading
  only is the smaller choice and can be widened.
- Sealed openings use the ordinary opening and page events with `sealedOriginalId`, so the exposure and Activity reports
  count them. A sealed opening never serves the document's own pages, nor the other way round.
- The Sensitivity report does not count `REDACT_HELD`: it is not a request about sensitivity.
- A request's reason is at most 500 characters (an ordinary redaction's: 1,000).
- `SDV-APR-016` is proposed for something else in `error-codes.md`, so `SDV-APR-017` is used.
- **The tour ran on a scratch database** (`sdv_seed_test`, made, seeded and dropped) because the local schema step on
  `sdv` was refused to this session in task 0067. `sdv` was not touched.
- `Seed-SDV-Canvas-Demo.ps1` now calls 127.0.0.1: since 0064 the local Vero answers there only, and Windows tries another
  address first for "localhost", which costs time.

## Not done, and why

- The local schema step on `sdv`. Until it is run, a Vero built from `release/vero-r7` does not work on `sdv`.
- `Test-SDV-DemoSeed.ps1` is out of date (it expects Vero to migrate at start); its steps were run from a temporary script.

## Questions for the owner

1. Is reading only (no print, no download) right for sealed originals?

## Suggested next step

Owner: run the schema step for 0067 from its branch after this is merged into it; it applies 043 as well.
