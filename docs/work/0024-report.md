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
