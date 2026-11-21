# Report 0024: Sensitivity level on documents and on intake (S1 + S4)

Date: 2026-11-20
Branch: `feature/sensitivity-1` (from `release/vero-r7`)
Outcome: PARTLY DONE. The document level (parts 1, 2, 3 and its tests) is done. The record level (parts 1a and 4a) is not built: SDV has no table for a record, so where it would live is the owner's decision (question 1). Until then a record level is refused, never dropped.

## What was done

**1. Schema: changeset `023-document-sensitivity-level`**
- `documents.sensitivity_level text not null default 'STANDARD'`, with check `ck_documents_sensitivity_level`: `STANDARD`, `CONFIDENTIAL` or `HIGHLY_CONFIDENTIAL`.
- Existing rows become `STANDARD` through the default. No other data change. The server still inserts nothing at start.
- **No index.** No query reads the column yet, and three values alone would not make a useful index. Task 0026 adds one if its search queries need it.
- **Schema version.** The server's start check compares the changesets in the jar with the database (task 0013), so it now expects 023. Production installs go from 22 to **23 changesets**; the last one is `023-document-sensitivity-level (sdv)`.
- **Reviewable SQL.** SDV has no committed SQL file. The database team's SQL is what the schema step's `sql` command writes from the changelog. The upgrade check confirms it holds exactly the 023 statements.

**2. Domain and intake**
- `SensitivityLevel` enum in `sdv-domain`, lowest to highest. `Document` carries it and refuses null.
- `SensitivityLevel.fromRequest`: missing gives `STANDARD`; anything but an exact name is refused (400). That includes blank, lower case, mixed case and padded values.
- `SensitivityLevel.effective(document, record)` is the higher of the two, null meaning no record level. It is the one place for the rule, ready for parts 1a and 0026. Nothing calls it yet, because no record level is stored.
- **Intake paths found** (searched for every `insert into documents` and every caller of the intake services):

  | Path | Creates documents | Handles the level |
  |---|---|---|
  | `POST /api/v1/ingestion/submissions/{id}/documents` (PDF upload) | yes, the only one | header `X-SDV-Sensitivity-Level`, optional |
  | `POST /api/v1/ingestion/submissions` (create submission) | no | refuses `sensitivityLevel` with 400 ("set on each document upload…") |
  | `POST …/documents/{id}/retry-finalization` | no (existing document) | keeps the stored level |
  | Corrective redaction | no (updates the same row) | keeps the level |
  | Local seed (`002`) and seed scripts | no SQL inserts; the scripts call the upload API | default STANDARD |

  The upload checks the level first, before anything is read or stored.
- **Retries:** an idempotent retry must send the same level as the first upload, or it gets 409. The stored level never changes.
- **Audit:** `DOCUMENT_RECEIVED` details now include `"sensitivityLevel":"…"`.
- **Record level (4a): not accepted yet.** `recordSensitivityLevel` in the submission JSON, or the header `X-SDV-Record-Sensitivity-Level` on the upload, is refused with 400: "Record sensitivity levels are not accepted yet; leave out recordSensitivityLevel and X-SDV-Record-Sensitivity-Level." (`RecordSensitivityGuard`). Without that refusal, Spring would silently ignore the JSON field.
- **API docs:** `docs/api/sdv-ingestion-v1.openapi.json` has the header, a `SensitivityLevel` schema, `sensitivityLevel` on `DocumentReceipt` and `DocumentSummary`, and the 400 case with its message. `docs/api/README.md` has a new section, "Sensitivity level". The submission JSON schema already says `additionalProperties: false`, so it needed no change.

**3. Read side (no enforcement)**
- Record detail (`GET /api/v1/business/records/{ref}`) returns `sensitivityLevel` for each document.
- The producer's upload and document-list responses return it too.
- Nothing is filtered: search counts and lists are unchanged.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test -pl sdv-application,sdv-web -Dtest=SensitivityLevelTest,DocumentSensitivityIntakeTest,DocumentSensitivityReadTest` | PASS after one fix (below) |
| `.\Build-SDV-All.ps1` | **BUILD SUCCESS**. Backend 18 + 19 + 2 + 4 + 174 = 217 tests (206 before), 0 failures, 0 errors. Frontend build passed. |
| `.\Test-SDV-SchemaUpgrade.ps1` (new) | **PASS** in 14 s (run twice) |
| `.\Test-SDV-CleanInstall.ps1` | **PASS** in 48 s: 23 changesets, 018 indexes valid, 0 tenants, no outside connection |
| `.\start-local.cmd` (through `Start-SDV-Local.ps1`) | **Did not start.** Vault is sealed (see "Not done") |
| `npm run ui:capture` | **Not run**: the app couldn't start |

- **Failures on the way:**
  - My first build attempt captured output with `*>&1` in PowerShell 5.1. That turns Maven's stderr into an error. The build itself was fine and passed when run in its own process.
  - The new read test first assumed the service keeps documents in input order. It doesn't (it sorts them), so the test now compares by file name. No product code changed for this.
- **Upgrade check steps** (scratch database `sdv_schema_test` and role `sdv_schema_owner` in `sdv-postgres`, both dropped at the end):
  1. **Previous version.** The schema step's own SQL for an empty database, cut just before 023, applied with psql. Changesets 000–022 are byte-identical to `release/vero-r7`. Result: 22 changesets, no `sensitivity_level`.
  2. **Data at the previous version.** 4 documents: available, received, failed, deleted.
  3. **Schema step.** `status` listed only 023. `sql` held only the column and its check, and changed nothing. `update` applied it.
  4. **After the update:**
     - all 4 documents are `STANDARD`;
     - the column is `text`, not null, default `STANDARD`;
     - the check refuses `confidential`, `Confidential`, `SECRET`, blank and null;
     - every other column of every document is unchanged (fingerprint);
     - every other table is unchanged (fingerprint);
     - `status` says up to date, with 23 changesets.

## Tests: counts, and any failures with their output

New, 11 tests, all passing:
- `SensitivityLevelTest` (4): missing gives STANDARD and each exact name is accepted; lower case, mixed case, unknown, blank and padded values are refused; the order; the effective level for every pair (the higher of the two, and null record means the document's own level).
- `DocumentSensitivityIntakeTest` (6). These run through the real upload and submission endpoints, the real intake service and the error handler, with storage, crypto and the database mocked:
  - a missing level is stored as STANDARD and audited;
  - each valid level is stored, returned and audited;
  - `confidential`, `Confidential`, `SECRET`, `HIGHLY CONFIDENTIAL`, ` CONFIDENTIAL`, blank and `1` all get 400 with the message, and nothing is stored, encrypted or audited;
  - a retry with the same level gets 200; with no level or a different one, 409;
  - a record level on the upload gets 400;
  - `sensitivityLevel` or `recordSensitivityLevel` on the submission gets 400.
- `DocumentSensitivityReadTest` (1): record detail sends each document's level, and search still counts all three documents.

Changed tests: six existing tests build `Document` or `AvailableDocumentSnapshot`. Each got the new last argument, `SensitivityLevel.STANDARD`; no assertion was changed.

## Files changed

- **Schema:** `023-document-sensitivity-level.yaml`, `023-document-sensitivity-level.sql` (new), `db.changelog-master.yaml`.
- **Domain:** `SensitivityLevel.java` (new), `Document.java`.
- **Application:** `DocumentIntakeService.java`, `BusinessRecordRepository.java`, `BusinessExperienceService.java`, `BusinessExperienceView.java`.
- **Persistence:** `JdbcSecureIngestionRepository.java`, `JdbcDocumentRedactionRepository.java`, `JdbcBusinessRecordRepository.java`.
- **Web:** `DocumentIntakeController.java`, `SubmissionController.java`, `RecordSensitivityGuard.java` (new).
- **Tests:**
  - new: `SensitivityLevelTest`, `DocumentSensitivityIntakeTest`, `DocumentSensitivityReadTest`;
  - constructor argument added: `BusinessExperienceServiceHomeTest`, `QuickSearchTest`, `RecordOpenAuditTest`, `RecordTitleTest`, `DocumentViewerServicePageViewTest`, `DocumentViewerServicePreviewsTest`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1` (new), `docs/api/sdv-ingestion-v1.openapi.json`, `docs/api/README.md`, `docs/work/0024-task.md`, this report.
- **Not committed:** `Start-SDV-Local.ps1` (left as it was), your uncommitted edit to `docs/work/README.md`, tasks 0025–0030 and `docs/design/sensitivity/`.
- **Frontend:** no change.

## Commits (hash and message), or "not committed"

One commit on `feature/sensitivity-1`: "feat: document sensitivity level on intake and record detail (task 0024)". The hash is in `git log`. Not pushed or merged.

## Not done, and why

- **Parts 1a and 4a, and the record-level tests.** There is no record table. A record is the group of submissions in one sending system that share their business-identifier values (`findSubmissionIdsByRecordIdentity`). A submission without identifiers is a record on its own. See question 1.
- **`start-local.cmd` and the UI tour.**
  - The containers `sdv-postgres` and `sdv-vault` had stopped (exited 5 hours before). The start script restarted them with `docker start`.
  - Vault is **sealed** (`initialized: true, sealed: true`). Unsealing needs the key under `C:\dev\sdv\secrets`, which I must not open, so the app wasn't started and `ui:capture` wasn't run.
  - The script also showed its own error at `Start-SDV-Local.ps1:166`: `docker logs … 2>&1` turns Vault's warning line into a PowerShell error, which hides the real message, "sdv-vault is not healthy/unsealed". I left the script as it is.
  - Both containers are now running, and Vault is still sealed.
- **The local `sdv` database is still at 022.** Local mode applies 023 the first time Vero starts after you unseal Vault. The task approves this change; existing documents become STANDARD.

## Questions for the owner

1. **Where does a record level live?** A record is not a row anywhere. It is "the submissions that share these identifier values", and which fields are identifiers is an admin setting. The options I see:
   - **a. A column on `submissions`.** The record's level is the highest level over its submissions. A later delivery can't lower it, because the earlier submission still holds the higher value. A lower value is ignored and audited, as the task says.
     - Risk: if an admin changes which fields are identifiers, submissions can regroup, and a record could lose a restriction. That would be a lowering path.
   - **b. A new table keyed by sending system and identifier values.** This is a schema change beyond what the task approved. It has the same regrouping risk, but the level is in one row.
   - **c. A record means one submission (one delivery).** This is the simplest. A level would not carry over to later deliveries for the same contract.

   I'd suggest **a**, plus a rule that changing identifier fields can't drop a level, but this is your call. Until then a record level is refused with 400.
2. **Level on the submission:** should a submission be able to give a default level for its documents? For now `sensitivityLevel` on the submission is refused with 400 and points to the upload header. The submission JSON schema already forbids extra fields.
3. **Retry with a different level:** a retry with a different level (or none, after a first upload with one) now gets 409. Is that right, or should a retry be allowed to raise the level?

## Suggested next step

1. Unseal Vault, then run `.\start-local.cmd` and `npm run ui:capture`. This also applies 023 to the local database.
2. Answer question 1. Parts 1a and 4a then go in as a follow-up on this branch, before task 0026 starts enforcing levels.

---

# Part 2: record (contract) level

Date: 2026-11-20
Branch: `feature/sensitivity-1`
Outcome: DONE. Sections 1 to 5 and 4a (error codes) are done. Build, upgrade checks, clean install and UI tour pass.

## What was done

**1. Schema: changeset `024-record-sensitivity-levels`.** New table `record_sensitivity_levels`. No other schema change.
- **Columns:** `record_level_id`, `tenant_id`, `source_id` (the sending system), `record_key`, `record_key_sha256`, `sensitivity_level`, `reason` (null), `set_by` (`SOURCE` or the person), `set_at`, `updated_at`.
- **Checks:**
  - the level is one of the three names;
  - the key is not blank;
  - the SHA-256 matches the key;
  - `set_by` is not blank;
  - `updated_at >= set_at`.
  - The sending system is a foreign key to `ingestion_sources`.
- **Unique:** on tenant + sending system + the key's SHA-256. This is the same as unique on the key. The hash is used because a record with several long identifier values could exceed Postgres's index row limit (about 2.7 KB). That unique index also serves the count used by the guard, so no other index is needed.
- **History:** goes to the audit trail. The table starts empty and no existing row changes.
- **Schema version:** 24 changesets; the last is `024-record-sensitivity-levels (sdv)`. Local mode applied 024 to the local `sdv` database when Vero started (checked: 0 rows).
- **App user rights:** the install's default privileges already cover new tables. No grant change was needed.

**Record key.** The key is one canonical text value, built by `RecordKey` in `sdv-application`.
- It uses the record's identifier values exactly as record grouping compares them (`findSubmissionIdsByRecordIdentity`): normalized and stored, sorted by field, as JSON. Example: `identifiers:[["contractNumber","300000000076"]]`.
- Intake builds it from the submission's values. Record detail builds it from the record reference. A test checks that both give the same key.

**2. Intake**
- The submission JSON accepts `recordSensitivityLevel`. Exact names only. Anything else is refused with 400 before anything is stored, including lower case, a blank, and non-strings such as `1`, `null`, `{}` and `[...]`.
- The upload header `X-SDV-Record-Sensitivity-Level` is still refused, with a message that points to the submission.
- **First level, raise, keep:**
  - The first level for a record is inserted (`on conflict do nothing`).
  - Otherwise the row is locked (`for update`). A higher value raises it. A lower or equal value is ignored, not an error.
- **Audit events:** `RECORD_SENSITIVITY_SET`, `RECORD_SENSITIVITY_RAISED` (from, to) and `RECORD_SENSITIVITY_KEPT` (note "record level kept at …"). The target is the row id. The details hold the sending system and submission ids, never identifier values.
- **A submission without identifier values** is its own record, with the key `submission:<submission id>`. That is the same identity record search already gives it. Its level applies only to that submission's documents. A second submission without identifiers is a different record.
- **Retries (answer 3, 409 kept):** the record level is part of the create fingerprint, but only when it is sent. Every submission created before this change keeps its fingerprint. A retry with a different record level, or with a level added or dropped, gets 409.
- **API docs:** the OpenAPI file, `docs/api/README.md` (new "Record level" section) and the submission JSON schema are updated. The schema forbids extra fields, so without this change it would have rejected the new field.

**3. Effective level**
- Record detail now returns, for each document, `sensitivityLevel` (its own) and `effectiveSensitivityLevel` (`SensitivityLevel.effective`). It also returns `recordSensitivityLevel` for the record (null when it has none).
- The level is read when the record is opened, so documents that arrive later get it. Nothing is copied onto document rows, and nothing is filtered yet.
- The producer's upload and document-list responses still return only the level the producer sent. They have no record context. Say if you want the effective level there too.

**4. Guard against losing a level.** While any record of a sending system has a level, these changes are refused with 409:
- making a field an identifier;
- making an identifier field a normal field;
- adding a new identifier field;
- changing an identifier's normalization.

The message gives the number of records with a level. Changes that leave the grouping alone are still allowed: labels, search settings, required, type, validation rules, and the normalization of non-identifier fields.

Every place identifier fields can change:

| Place | Covered |
|---|---|
| `PUT /api/v1/admin/ingestion-sources/{id}/metadata-fields/{field}` (`createOrRevise`) | guarded |
| `PUT /api/v1/admin/metadata-sources/{id}/fields/{field}` (Business admin, `reviseBusinessBehavior`) | guarded |
| `PUT /api/v1/admin/ingestion-sources/{id}/source-fields/{field}` (Tenant IT, `createOrReviseSourceField`; can change normalization) | guarded |
| Admin screens (`Integrations.tsx`, `SearchFields.tsx` via `liveApi.ts`) | use the three endpoints above |
| Setup and seed scripts (`Seed-SDV-*.ps1`, `Test-SDV-*.ps1`) | use the same API, so guarded |
| Schema scripts | only `001` creates and `003` constrains `metadata_field_definitions`; no changeset changes field rows; the local seed `002` adds no fields |
| Setup command | none exists. The only command-line entry points are the schema step and the OIDC bootstrap, and neither touches fields |

Not covered: direct SQL by the database team on `metadata_field_definitions`.

**4a. Error codes (added during this part).** Every refusal in this task now sends a stable code with its plain message.
- The codes are a domain enum, `SdvErrorCode`.
- The error handler sends a refusal's code when it has one. Otherwise it sends the generic code as before. HTTP statuses are unchanged.

| Code | Status | Refusal |
|---|---|---|
| `SDV-SEN-001` | 400 | unknown `X-SDV-Sensitivity-Level` (Part 1) |
| `SDV-SEN-002` | 400 | unknown `recordSensitivityLevel` |
| `SDV-SEN-003` | 409 | document retry with a different level (Part 1) |
| `SDV-SEN-004` | 409 | submission retry that differs only in `recordSensitivityLevel` |
| `SDV-SEN-005` | 400 | record level header on an upload (Part 1) |
| `SDV-SEN-006` | 400 | `sensitivityLevel` on the submission (Part 1) |
| `SDV-SEN-007` | 409 | identifier change refused while records have a level |

- `SDV-SEN-004` is found by recomputing the fingerprint with each other record level. If one matches, the record level was the only difference, and the message names the first value. A retry that differs in anything else keeps the generic `CONFLICT`.
- `docs/api/error-codes.md` lists each code with its meaning, what the user should do, and what support should tell the customer. A test fails if a code is missing from that file, lacks one of those three parts, or the file lists a code the product doesn't have.
- **Changed for producers:** the Part 1 refusals used to send `BAD_REQUEST` or `CONFLICT` and now send `SDV-SEN-00x`. The API README says so.

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o test -pl sdv-application,sdv-web -Dtest=…` (new and changed tests) | PASS, first run |
| `.\stop-local.cmd` | stopped the app's own processes only; `sdv-postgres` and `sdv-vault` left running (needed because the running app locks the jar) |
| `.\Build-SDV-All.ps1` | first try failed: "Failed to delete … sdv-web-0.1.0-SNAPSHOT.jar" (the app was running). After the stop: **BUILD SUCCESS** |
| `.\Build-SDV-All.ps1` again, after 4a | **BUILD SUCCESS**: backend 24 + 19 + 2 + 4 + 195 = **244 tests** (217 before Part 2), 0 failures; frontend build passed |
| `.\Test-SDV-SchemaUpgrade.ps1` (new default `-From 024`: 023 → 024) | **PASS**, 16 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -From 023` (022 → 024) | **PASS**, 16 s |
| `.\Test-SDV-CleanInstall.ps1` | **PASS**, 48 s: 24 changesets, 018 indexes valid, 0 tenants, no outside connection |
| `.\start-local.cmd` | started; local `sdv` database now at 024 |
| `npm run ui:capture` | **20 passed, 4 skipped** (the usual 4: desktop-only and the opt-in redaction test), 5.0 min |

**The 023 → 024 upgrade check:**
- builds the database up to 023, with documents at different levels;
- `status` lists only 024, and `sql` holds the table, its checks, the unique key and the foreign key, and changes nothing;
- after `update`:
  - the table is empty, and every document column (levels included) and every other table is unchanged (fingerprints);
  - the checks refuse a lower-case level, a wrong SHA, a second row for the same record, an unknown sending system and a blank key;
  - `on conflict do nothing` keeps the first row;
  - Postgres and the application compute the same SHA-256, including for a non-ASCII value;
  - everything the check created is dropped at the end.

`-From 023` runs both changesets and the 023 checks too. The scratch database `sdv_schema_test` was dropped after every run.

## Tests: counts, and any failures with their output

New (27 tests), all passing:
- `RecordKeyTest` (6):
  - the key's order and JSON form, and no value can imitate another key;
  - a submission alone has its own key;
  - the record reference gives the same key as intake;
  - the SHA over UTF-8;
  - optional record-level parsing.
- `RecordSensitivityIntakeTest` (11):
  - the first level is set and audited, with no identifier values in the audit trail;
  - a later delivery raises the level (the same contract written differently, with dashes);
  - lower and equal values are ignored and audited;
  - a delivery with no level leaves the record alone;
  - a submission without identifiers is its own record;
  - all identifier values together make the record;
  - unknown values are refused before anything is stored;
  - retries: the same value is a replay; a different value or none gets `SDV-SEN-004`; adding a level gets `SDV-SEN-004`; another difference gets the generic conflict;
  - old fingerprints are unchanged;
  - over HTTP, eight bad values each get 400 `SDV-SEN-002`, and a good one gets 201.
- `RecordSensitivityReadTest` (3):
  - each document's own and effective level, and the record level;
  - a later document inherits;
  - with no record level, the effective level is the document's own;
  - a submission alone reads its own level.
- `RecordIdentityGuardTest` (3): every identity change on all three paths is refused with `SDV-SEN-007` and the count (singular for 1), and nothing is changed; other changes are allowed; identity changes are allowed when no record has a level.
- `ErrorCodesDocumentedTest` (3): code format and uniqueness; every code is in `error-codes.md` with its three parts; the error body carries the code with an unchanged status.

Changed tests (behavior changed on purpose by this task):
- **`DocumentSensitivityIntakeTest`:**
  - `theSubmissionRefusesBothLevelsInsteadOfIgnoringThem` is split. `sensitivityLevel` is still refused (now with `SDV-SEN-006`). The new `theSubmissionPassesTheRecordLevelOnAsWritten` covers `recordSensitivityLevel`, which is now passed on instead of refused.
  - The upload-header test is renamed and asserts `SDV-SEN-005`.
  - The assertion `$.code == "BAD_REQUEST"` became `"SDV-SEN-001"` (4a).
  - The two 409 retries now also assert `SDV-SEN-003`.
- **`RecordTitleSettingTest`:** the constructor gets the new record-level argument (a mock). No assertion changed.
- `BusinessRecordRefCodecTest` was left alone: the in-memory `canonicalKey()` is unchanged. The stored key is a separate `recordKey()`.

## Files changed

- **Schema:** `024-record-sensitivity-levels.yaml` and `.sql` (new), `db.changelog-master.yaml`.
- **Domain:** `SensitivityLevel.java`; `error/SdvErrorCode.java` and `error/CodedRefusal.java` (new).
- **Application:**
  - new: `RecordKey.java`, `port/RecordSensitivityRepository.java`;
  - changed: `SubmissionService.java`, `CreateSubmissionCommand.java`, `MetadataFieldAdminService.java`, `BusinessExperienceService.java`, `BusinessExperienceView.java`, `BusinessRecordRefCodec.java`, `BusinessRecordRepository.java`, `DocumentIntakeService.java`.
- **Persistence:** `JdbcRecordSensitivityRepository.java` (new), `JdbcBusinessRecordRepository.java`.
- **Web:** `SubmissionController.java`, `RecordSensitivityGuard.java`, `FoundationExceptionHandler.java`.
- **Tests:** the five new classes above; `DocumentSensitivityIntakeTest`, `RecordTitleSettingTest`.
- **Scripts and docs:** `Test-SDV-SchemaUpgrade.ps1` (new `-From 023|024`), `docs/api/error-codes.md` (new), `docs/api/README.md`, `docs/api/sdv-ingestion-v1.openapi.json`, `docs/api/schemas/sdv-ingestion-submission-v1.schema.json`, `docs/work/0024-task.md`, this report.
- **Not committed:** `Start-SDV-Local.ps1`, your edit to `docs/work/README.md`, tasks 0025–0030, `docs/design/sensitivity/`.
- **Frontend:** no change.

## Commits (hash and message), or "not committed"

One commit on `feature/sensitivity-1`: "feat: record sensitivity level, effective level and error codes (task 0024 part 2)". The hash is in `git log`. Not pushed or merged.

## Not done, and why

- No end-to-end delivery was sent to the running app. That would have added a test record to your local development data. The SQL was checked in the scratch database instead, and the service logic with unit tests.
- Nothing sets a record level by a person yet. `set_by` and `reason` are ready for it.

## Questions for the owner

1. Should the producer's upload and document-list responses also return the effective level? Today they return only the level the producer sent.
2. `SDV-SEN-007` has no override. Changing identifiers for a system with record levels would need a planned migration of those levels. Is that acceptable for now?

## Suggested next step

Review and merge `feature/sensitivity-1`. Then task 0025.
