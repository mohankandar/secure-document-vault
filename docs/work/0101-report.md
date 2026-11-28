# Report 0101: Security fixes 6: sensitive-field search (VSEC-202)

Date: 2026-11-27
Branch: fix/security-6-search
Outcome: DONE

## What was done

1. **The search (item 1).** A search criterion for a sensitive field now carries the levels the person searching may
   open (from their clearance). The repository adds the level condition to the same query that matches the
   fingerprints: the record's own level and every AVAILABLE document's level must be among those levels. A record the
   person may not open never becomes a candidate, so it is never counted, paged or summarised. The answer is the same
   object as for a value nobody has, and no second query runs only for the refused case. A sensitive criterion with no
   levels matches nothing (fail closed). Non-sensitive fields and the free-text search are unchanged; the free-text
   search never matches sensitive values (`not v.sensitive`).
2. **Tests (item 2).**
   - `PlatformRecordValuesPostgresTest.aSensitiveValueAboveThePersonsLevelIsNotFoundLikeAValueNobodyHas` uses two
     levels and two people (Standard only, all levels). Two records are above Standard: one because of a Highly
     Confidential document, one because of the record's own Highly Confidential level. The Standard person gets exactly
     the "value nobody has" answer for both, and for a value shared with an openable record sees only that record. The
     all-levels person finds them. A non-sensitive field still finds all three.
   - `QuickSearchTest.aSensitiveFieldIsSearchedOnlyInTheLevelsThePersonMayOpen` covers the service: the criterion
     carries exactly the person's levels.
   - `JdbcBusinessRecordRepositorySearchSqlTest.aSensitiveFieldFindsOnlyRecordsThePersonMayOpenInTheSameQuery` covers
     the SQL: the condition sits in the one query; no levels means nothing.
   - The API (`POST /api/v1/business/search`) calls this same service with `authorization.clearance(principal)`, so
     it is covered. There is no search export.
3. **Other paths that take a sensitive value (item 3).**

   | Path | Finding | Done |
   |---|---|---|
   | Field search (screen and API) | the leak above | fixed |
   | Free-text ("anywhere") and Ctrl K search | never compares sensitive values | none needed |
   | **Quarantine by filter: the count** | a Document Steward (Confidential) could count documents of every level by a sensitive value, and learn that a level they can't open holds it | fixed: counting by a filter that names a sensitive field needs a Steward who can open every level (SDV-QUA-002), as quarantining by filter already did. It is decided by the request, never by the data |
   | Quarantine filter, a sensitive field without fingerprints | the field was dropped from the filter, widening it | fixed: such a field matches nothing |
   | Quarantine exposure export | takes no value as input | none needed |
   | Intake duplicates | the sending system's own deliveries; no person's input | none needed |
   | Reports and their CSV exports, recent records, record detail | take no field value as input; record detail checks the level | none needed |
4. `security-findings-2.md`: VSEC-202 is fixed. `error-codes.md` (SDV-QUA-002) gains the counting rule.

## Decisions taken

- **Quarantined documents count** in the search's level condition. A record whose only document above the person's
  level is quarantined is left out of a sensitive-field search for that person (stricter than opening; fail closed).
  Copying the quarantine filter logic into the search query was the alternative; it is larger, and wrong in the
  unsafe direction if the two ever differ.
- The quarantine count's rule is "can review every level" (the same rule as quarantining by filter), not a level filter
  on the count. A Confidential Steward can't quarantine by filter anyway.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o -pl sdv-web -am -Dtest=<search, quarantine, SQL tests> test` | PASS (QuickSearchTest 20, QuarantineServiceTest 23, search SQL tests 17) |
| `Build-SDV-All.ps1` | PASS, 102 s: backend BUILD SUCCESS (sdv-web 778 tests, 0 failures, 55 skipped), frontend built |
| `Test-SDV-SchemaUpgrade.ps1 -Forks 1` (no new changeset; runs every Postgres test, incl. the new one: `PlatformRecordValuesPostgresTest` now 18) | PASS, 668 s (step 4f, every Postgres test, 645 s: over 5 minutes, as before) |
| `Test-SDV-CleanInstall.ps1` | PASS, 135 s |

No screen changed, so there is no tour (speed rules).

## Tests: counts, and any failures with their output

New: the four tests named in item 2 and `QuarantineServiceTest.countingByASensitiveFieldNeedsEveryLevelToBeOpenable`.
The sensitive-field SQL test now passes levels to `SearchCriterion.sensitive`; the factory without levels is gone, so
no caller can leave the level condition out.

## Files changed

`BusinessRecordRepository` (`SearchCriterion.openableLevels`), `JdbcBusinessRecordRepository` (`openableOnly`),
`BusinessExperienceService`, `QuarantineService`, their tests, `Test-SDV-SchemaUpgrade.ps1` (test count 18),
`error-codes.md`, `security-findings-2.md`.

## Commits (hash and message)

See the PR: task file; the fix with its tests; this report and the statuses.

## Not done, and why

Nothing.

## Questions for the owner

None.

## Suggested next step

0102 (key-service login, bundle, advisories, erase-log folder).
