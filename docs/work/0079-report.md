# Report 0079: case field screens, contract and scale (integration E3)

Date: 2026-11-25
Branch: `integration/e3-screens` (from `release/vero-r7` at 5ac2201)
Outcome: **DONE**

## What was done

1. **Contract 1.5.0**, with the profile and the JSON schema: 512 characters, types with the offset from UTC and letter case, what a status
   query answers, `503 SDV-KEY-002` (creating only, rule `yes`). A longer value: `400 SDV-INT-009`, "… is longer than 512 characters."
2. **Screens.** "Sensitive" is marked in Filters, the record panel, the viewer's identifiers and Quarantine. Search fields offers no partial
   match for a sensitive field. The server marks the values it sends; a value a person may not see isn't sent.
   A search or quarantine value can be 512 characters (it was 500 and 300), so every stored value can be found.
3. **Words:** tour steps, Business Admin and IT guides, error codes, and new `docs/guides/release-notes.md`. The seeds needed no change.
4. **Scale.** 1,000,000 records of one document in one entity; all within the limits after one fix (below). No index, no schema change.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 42 + 72 + 2 + 718 + 14 = 848 tests, 0 failures, 52 skipped (they need a database or key service; run below) |
| `npm test`, `npm run typecheck` | 84 passed; clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 183 s, on the final code (147 s before the search change) |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1 -SearchScale 1000000` | PASS, 1,198 s; all PostgreSQL tests |
| `.\Test-SDV-DemoSeed.ps1 -WithTour` (scratch database and key service) | PASS; full tour 33 passed, 19 skipped (desktop-only steps on the tablet); ui:compare 28 passed |
| `.\Test-SDV-KeycloakLive.ps1` (people signing in, scratch database) | PASS, every line |

**The first schema check failed**, with both scales at 1,000,000: `everywhere, one record: 8007 ms` (limit 3,000). Every other test in it
passed. The limit was not raised; the search was changed and the check run again.

**Scale** (ms; medians of 3 after one warm-up; limits: search 3,000, count 3,000, a screen 1,000, a one-document check 100):

| 1,000,000 documents | ms |
|---|---:|
| Search by identifier / sensitive name (1 in 1,000) / name nobody has | 92 / 247 / 23 |
| Contains: rare / nearly every record, names opened / none | 231 / 201 / 15 |
| Everywhere: one record (8,007 before) / nearly every record / part of nearly every value | 35 / 211 / 205 |
| Everywhere: "70" (starts every identifier) / none has it | 435 / 10 |
| Intake: a new record / a record there is | 10 / 9 |
| Quarantine filter count: identifier / received dates / sensitive name | 133 / 528 / 2,003 |
| Quarantine, one incident of 1,000,000: count / confirm | 578 / 934 |
| Incident list / page / 90 percent in / exposure page / Approvals row | 45 / 119 / 114 / 91 / 40 |
| One-document check / 100 documents | 19 / 21 |
| Preparation / release, to the end (306,125 / 215,581 in 0052) | 333,281 / 231,910 |

The quarantine incident's lines are from the first (failed) check; its code did not change after. The count by a sensitive name is the one
closest to its limit.

## Found and fixed on the way

- **Searching everywhere ranked every record of the system before showing a page.** It now goes rank by rank (identifier, exact, starts
  with, contains), newest first within one, and stops when the page is full. Same results in the same order; tested page by page
  (`searchingEverywhereIsBestMatchFirstThenNewestOnEveryPage`).

## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `AnywhereSearchSqlTest` | Asserts SQL text; the ranks are separate statements |
| `IngestionContractTest` | Two cases more (513 characters, the key service away); the stand-in for values can be told to fail |
| `PlatformRecordValuesPostgresTest` | Two tests more; the opened record's marks are asserted |
| `RecordSearchScalePostgresTest` | Documents, intake and quarantine counts added; same limits |
| Tour: Search fields, Quarantine | One assertion more each |

## Decisions taken

- Going from 4,000 to 512 characters refuses something `/v1` accepted; the task approves it as a minor version and the changelog says so.
- `SDV-KEY-002` has rule `yes`, not `after-fix`: a sealed or unreachable key service comes back by itself or within minutes.
- Intake has no limit in 0052/0055; the screen limit (1,000 ms) is used.
- A rank with more than 5,000 records is read newest first. If all its matches are old, that reads many rows first; not seen in a measure.
- The sender profile has no place for a value's length in its format, so a note says it.
- The local `sdv` is untouched; everything ran on scratch databases. I also made a throwaway database `sdv_scale_0079` in `sdv-postgres`
  to try queries and dropped it.

## Not done, and why

- A sending system's delivery through the live Keycloak: it needs a client there, which only the owner can add (as in 0088).
- The release tag `sdv-ingestion-v1.5.0`: no tag was made.
- `RecordKey.java` and `FieldAdoption.java` still wait for the owner's OK to be deleted (report 0078).

## Questions for the owner

None new.

## Suggested next step

0080.
