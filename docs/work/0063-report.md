# Report 0063: Security fixes 2

Date: 2026-11-24
Branch: `fix/security-2` (from `release/vero-r7` at `42e2de7`)
Outcome: DONE. No schema change, no new dependency.

## What was done

1. **Exposure.** Pages, previews and text search need the person's own opening of that document from the last 8 hours
   (else `409 SDV-DOC-011`). Previews are audited once per viewing. The exposure report counts page views, previews and
   searches, once per person, document and kind; the Activity report counts page views and searches without an opening.
2. **Viewer limits.** A global and a per-person limit on PDF work; with no place free, `503 SDV-SYS-018` with
   `Retry-After`. A time limit (`SDV-DOC-012`), the page limit at intake and viewing (`SDV-INT-022`, `SDV-DOC-013`), a
   picture-size cap, working space that spills to disk as ciphertext only, and out-of-memory ends one request only. All
   are settings (`SDV_PDF_*`, in the runbook). The viewer waits and asks again when busy; printing asks two pages at a time.
3. **PDF intake.** Refused before anything is stored: unreadable (`SDV-INT-019`), encrypted (`SDV-INT-020`), active
   content (`SDV-INT-021`). Per sending system, default refuse (`SDV_PDF_ACCEPT`).
4. **Patterns.** A metadata value is at most 4,000 characters; a pattern check has 250 ms.

Ingestion contract 1.3.0 (changelog, OpenAPI, sender profile) and `error-codes.md`.

## Load test (live, local Vero, a generated 172 MB PDF of 20 full-page pictures)

| Step | Result |
|---|---|
| Upload | 201, 3.8 s |
| A page without an opening | `409 SDV-DOC-011` |
| One page alone | 200, 1.3 s |
| One person, 100 pages at once | 19 answered 200, 81 `503 SDV-SYS-018` with `Retry-After: 5`, 10.3 s |
| 12 people, 240 pages at once | 68 answered 200, 172 `503 SDV-SYS-018` with `Retry-After: 5`, 16.0 s |
| One page afterwards | 200, 0.9 s |
| Readiness during the test | 80 checks, all OK; the slowest took 5.1 s |

Vero stayed up and answered busy; there was no other status. The slowest readiness answer is worth knowing: a probe
with a time-out shorter than that could miss one check under such a load.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final code) | BUILD SUCCESS: 43 + 69 + 2 + 4 + 631 + 10 = **759**, 0 failures, 25 skipped; frontend builds |
| `npm test`, `npm run typecheck` | 66 passed; pass |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 108 s |
| `.\Test-SDV-SchemaUpgrade.ps1` | First run **failed** (below); second run PASS in 651 s |
| `npm run ui:capture` | First: 28 passed, **2 failed** (below). After the fix: **30 passed, 0 failed, 16 skipped** |

Failures seen on the way:
- **Schema check, `QuarantinePostgresTest`:** `expected: <[Opened, Printed]> but was: <[Opened, Printed, Saw page
  previews (first time), ...]>`. The test's new rows showed in an older assertion of the same test. That assertion now
  leaves out the new test person.
- **Schema check, `SenderProfileServerContractTest`:** `health: OUTAGE INT_OUTAGE`. The log shows one fetch of the test
  sign-in keys had no answer within 2 s; the next call was healthy. Nothing was changed for it and the rerun passed, so
  the cause is **not proven**; a busy computer is the likely one.
- **Tour, Business User and Document Steward screens:** page previews never appeared. A real fault of this change: the
  thumbnails could ask before the opening was answered, and did not ask again. Fixed in `Viewer.tsx`.

## Tests: counts, and any failures with their output

New: `PdfWorkTest`, `PdfInspectorTest`, `MetadataValueLimitsTest`. Changed because the approved rules changed what they
said: viewer, intake, contract and exposure tests (without an own opening nothing is read).

## Files changed

See the pull request.

## Commits

Task file; the fixes with this report. Merged through a PR on green `ci-passed`.

## Decisions taken

- **No changeset:** what a sending system may send is an installer setting by source key, not a column.
- **Contract 1.3.0 refuses files `/v1` took before.** A security exception, named in the changelog.
- Documents over the page limit are no longer shown; they can still be downloaded by those allowed.
- The Activity report does not count reading inside an opening again; kept days stay as they are.
- Work that timed out keeps its place until it really ends, so the limit stays true.
- A malware-scan hook waits for platform-core, as the task says.
- **Release tag** `sdv-ingestion-v1.3.0` is named in the changelog but not pushed: the queue approves no tags.

## Not done, and why

- `Test-SDV-V1-GA-CleanInstall.ps1` was adapted (it now names the opening) and parses, but was **not run**.
- The refused kinds of PDF were proven by tests, not tried on the live local Vero.
- The load test left its document (`load.pdf`, contract 990063000001) and audit events in the local `sdv` database;
  nothing was deleted.

## Questions for the owner

1. Push the tags `sdv-ingestion-v1.2.0` and `sdv-ingestion-v1.3.0`?
2. Before an upgrade, sending systems that send protected PDFs or PDFs with active content need a `SDV_PDF_ACCEPT`
   entry. Acceptable as a release note?

## Suggested next step

The local schema step for 0067, then 0064.
