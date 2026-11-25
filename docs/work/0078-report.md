# Report 0078: records, values and search on case fields (integration E2)

Date: 2026-11-24
Branch: `integration/e2-values` (from `release/vero-r7` at 2b3da8b)
Outcome: **DONE on the branch; not pushed** (the session's permission check refused the push and the PR)

## What was done

1. **Records.** Table `records`; a submission points to its record, and levels, requests and level changes name it by ID (changeset 048).
   Intake finds the record by its identifiers or makes it; parallel deliveries of a new record make one (tested). A reference in a URL is
   the sending system and the record's ID; one of before is "not found".
2. **Values** are in `platform-fields`: the latest delivery's replace earlier ones. Sensitive ones are sealed under the record's own key.
   `legal_holds` is given to them. The audit entry names the fields delivered, never a value. Values up to 512 characters.
3. **Search.** Exact on sensitive fields by fingerprint; contains and starts with on the rest. The Filters panel and the search list say
   which. Without `pg_trgm`, contains is starts with and the panel says so.
4. **Names stay in result lists:** one batch call per page, only for records whose documents the person may open; others fall back to the
   identifier. Opening a record is one call. Without the key service lists and records open without names.
5. **Admin reads** take the primary identifier through one class; a sensitive identifier is not shown there.
6. Vero's own value, definition, order and title tables are dropped. A database that still holds values of before is refused and unchanged.
7. New code `SDV-KEY-002` (503): the key service can't be used now. Guides, runbook, error codes updated; seeds deliver names.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Checks

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 42 + 72 + 2 + 716 + 10 = 842 tests, 0 failures, 49 skipped (they need a database or key service; run below) |
| `npm test`, `npm run typecheck` | 82 passed; clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 160 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` (`-From 048`) | PASS, 657 s; all PostgreSQL tests, 13 new ones on records and values |
| `VaultBatchUnwrapTest` against the scratch Vault | 5 passed: 50 keys in one real call |
| `.\Test-SDV-DemoSeed.ps1 -WithTour -TourSteps …` (scratch database and key service) | seed, second run, reset PASS; 9 tour steps passed, 1 skipped (smoke, names in search, How it matches, Quick search, redaction, quarantine) |
| Full tour, once, before the tour's words were changed | 23 passed, 10 failed: 6 searched people by a part of the name, 4 asked the fixed port 8280. The steps were corrected and pass above; the full tour was not run again (0079) |

Failures on the way, fixed: the schema check failed four times (a duplicated column in one insert, test set-ups, the deadlock, the search
measure, which at first opened a connection for each statement: 4.5 s, with the app's pool 0.2 s; no limit was raised). The seed check failed
once: the usage job's tables (0075, 0090) changed between two snapshots; the check now leaves them out, as it does `lic_last_seen`.

**Scale** (ms, medians; 250,000 documents; before: the 0077 run of the same day; limits unchanged):

| Measure | Before | After |
|---|---:|---:|
| Quarantine: count before confirming (3,000) / confirm | 691 / 932 | 386 / 1,249 |
| One-document check (100) / 100 documents (200) | 22 / 26 | 18 / 20 |
| Preparation / release, to the end | 108,862 / 70,509 | 71,944 / 53,394 |
| Incident page / exposure page / Approvals row | 152 / 104 / 49 | 130 / 94 / 44 |
| Restrictions: overview / records page 301 / changes page 202 | 256 / 59 / 55 | 219 / 39 / 39 |
| Search, 100,000 records with an encrypted name: exact name / identifier | new | 159 / 33 |
| Contains, rare / nearly every record with the page's names opened | new | 151 / 233 |
| Everywhere, one record / nearly every record | new | 307 / 525 |

## Found and fixed on the way

- A deadlock between parallel deliveries to one record (a foreign key's lock against a row lock), found by the parallel test.
- The lookup by identifier (holds, audit) could not use the value index; it goes through the identifier fields now.

## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `AnywhereSearchSqlTest`, `JdbcBusinessRecordRepositorySearchSqlTest`, `RecordOpenAuditTest`, `RetentionLegalHoldTest` | They assert SQL text; the tables are other ones |
| `BusinessRecordRefCodecTest`, `RecordKeyTest`, `RecordKeyCheckTest`, baseline `record-reference-reading` | The reference is the record's ID; those of before are "not found" |
| `SubmissionServiceTest`, `MetadataValueLimitsTest` | Values belong to the record; 512 characters |
| `RestrictionsServiceTest` | The database picks the primary identifier; the test of the order is `PrimaryIdentifierPostgresTest`. One test removed |
| `RestrictedNamesInAdminViewsTest` | The test of the old key is replaced: a record that can't be read is Highly Confidential |
| `DocumentDuplicateIntakeTest` | The audit detail `recordKeySha256` is `recordId` |
| `PlatformFieldsPostgresTest` | Nothing is taken over from the old tables any more: one test removed |
| `AccessRequestPostgresTest` | Bad rows name a record ID; one more (a record that isn't there) |
| Tour: names in search, How it matches, Quick search | Names are found by the whole value (step 3 of the task) |

Every level, quarantine and hold test is otherwise unchanged except for its set-up.

## Decisions taken

- **The batch.** platform-crypto 1.0.0 opens one key per call; there is no batch. Vero makes the one call itself (Vault Transit decrypt with
  `batch_input`, platform-crypto's own settings, login, key and policy) and lets platform-fields read the page from it. No second copy of a
  name is stored. Proven against the scratch Vault. A wish is in `PLATFORM-ISSUES.md`; with another key service lists show no names.
- **Who sees sensitive values of a record:** who may open all its documents that aren't quarantined, at their effective level.
- A field that is an identifier for one sending system counts in the identity for every system of the entity (the platform's primary flag).
- Identifiers and exact search ignore capitals now (platform-fields' form).
- A trigger makes every new submission its own record until intake joins it to another, so a submission never lacks one.
- A document whose record can't be read is treated as Highly Confidential.
- A saved quarantine filter holds fingerprints for a sensitive field. A legal hold or the audit filter can't find a sensitive identifier.
- `GET` of a submission answers the record's fields that aren't sensitive; creating and repeating answer what was sent. 0079 publishes it,
  with 512 characters and `SDV-KEY-002` (503), in the contract.
- The schema upgrade check offers only `-From 048`: earlier ones check what 048 drops.
- A name that becomes searchable after values exist has no fingerprint for the older values (platform-fields makes it with the value).

## Not done, and why

- **Files no longer used, left for the owner to allow deleting** (`CLAUDE.md`): `RecordKey.java` (used by tests only), `FieldAdoption.java`.
  I deleted `RecordKey.java` at first and put it back.
- The old unit scripts (`Test-SDV-BusinessExperience-*`, `-ControlledActions-*`, `-SecureIngestion-*`, `-Foundation`, `-V1-GA-CleanInstall`,
  `Cleanup-SDV-Legacy-Demo-Records`) still name the dropped tables; they are not part of the checks.
- The full tour runs in 0079. The local `sdv` is untouched; it needs the new database of the owner's step.

## Questions for the owner

1. May the two unused files be deleted?

## Suggested next step

Push `integration/e2-values`, PR to `release/vero-r7`, merge on green `ci-passed`. Then 0079: screens, contract, scale.
