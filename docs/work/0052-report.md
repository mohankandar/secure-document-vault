# Report 0052: Quarantine at a million documents

Date: 2026-11-23
Branch: `perf/quarantine-scale`, from `release/vero-r7` at `b4fd5a4` (after 0051 was merged, PR #25)
Outcome: **DONE, except the screen tour**. Built, tested, both schema checks and the clean install passed, and the measured test passed at 250,000 and at 1,000,000 documents. **The screen tour was not run**: the local schema step was refused (see below). Merged through the PR once `ci-passed` was green.

## What was done

### 1. Counts kept on the incident (changeset 039)

- `quarantine_incidents` keeps its counts: total, released, by level, by level in force, first and last received. It also keeps the approved release being carried out (`release_request_id`).
- Two statement-level triggers on `quarantined_documents` (insert, update) keep the counts in the same statement that writes or releases rows. A batch of 1,000 rows is one update of the incident, whatever code writes the rows.
- Each row now keeps its document's level when quarantined (`level`) and its received time (`received_at`).
- Rows already there get their received time and are counted in the changeset. Their level depends on the record's restriction, which the application works out, so a background job fills it in (`QuarantineService.fillLevels`). Until then such a row counts as hidden to everyone below the highest clearance (fail closed).
- The incident list, the incident page, the exposure view and the Approvals row now read these counts. None of them reads the whole incident.

### 2. Lists page through the incident

- The review list and the exposure view come in pages of 100: in force first, then released, each oldest first (quarantined, received, document). Two partial indexes serve the order.
- `GET /api/v1/quarantine/incidents/{id}?after=` and `.../exposure?after=` take the page position the previous page gave (`next`; null on the last page). A position Vero didn't give is refused with SDV-QUA-004.
- The screen shows "Documents 101 to 200 of 20,000.", with **Next 100** and **First page** buttons. The totals come from the counts.
- Levels: a page holds only the levels the person reviews (as kept on the rows). Each document's level now is checked again, so a level raised since is left out (fail closed).

### 3. The whole release runs in batches in the background

- Approving a release starts it, and the first batch (1,000) is released at once, so a small incident is released when the approval returns.
- The rest is released by the existing background job (`QuarantinePreparationScheduler`, every 2 s, at most 20 batches a run). Each batch is its own transaction: rows released and each document's release audited together.
- It is resumable after a restart: the incident keeps the approved request. A batch takes rows still in force only, so nothing is released twice.
- Progress is shown: "Being released" in the list, and on the incident "300,000 of 1,000,000 released". The incident page refreshes every 3 s while releasing.
- The filter block ends only when every document is released: the incident closes then. Until then, documents not yet released stay blocked.
- While an incident is being released, nothing can be added to it (SDV-QUA-008) and no new request can be made (SDV-QUA-010).

### 4. The exposure CSV streams

- `GET .../exposure/export` writes the CSV row by row, a page of 1,000 documents at a time, to the response. Nothing is built in memory.
- The SHA-256 is worked out while writing. The reference is sent first (`X-SDV-Export-Reference`).
- Audit: `QUARANTINE_EXPOSURE_EXPORT_STARTED` is written before the first byte leaves. `QUARANTINE_EXPOSURE_EXPORTED` is written at the end, with the fingerprint, rows and documents. Both have a sentence on the Audit page.
- The screen works out the same SHA-256 from the file it received and shows it with the reference. The tour checks it equals the SHA-256 of the saved file.

### 5. Measured test

`QuarantineScalePostgresTest`: one filter incident of 250,000 documents (5,000 records of 50). It runs in CI and in schema check step 4f. `-QuarantineScale 1000000` runs it locally with 1,000,000. Agreed times, set before the first measured run:
- a screen's API: 1,000 ms;
- the count before confirming: 3,000 ms (it must count every matching document);
- a one-document check: 100 ms, and 200 ms for 100 documents.

Screen APIs are timed as the median of three calls after one warm-up. No limit was raised.

| Measure (ms) | 250,000 | 1,000,000 |
|---|---:|---:|
| Seed the documents (test set-up) | 34,907 | 98,989 |
| Count before confirming (API; agreed 3,000) | 343 | 400 |
| Confirm: count, store the filter, first batch | 772 | 744 |
| One-document check, every document path (agreed 100) | 22 | 18 |
| Many-documents check, 100 documents (agreed 200) | 32 | 18 |
| Preparation, to the end (20 batches a run) | 78,532 | 306,125 |
| Incident list (API) | 39 | 38 |
| Incident page, first (API) | 82 | 78 |
| Incident page, Steward with hidden levels (API) | 77 | 76 |
| Incident page, 90 percent in (API) | 83 | 77 |
| Exposure page (API) | 73 | 75 |
| Exposure export, streamed (whole file) | 32,065 | 124,683 |
| Exposure export size (KB) | 40,698 | 162,799 |
| Approvals row (API) | 44 | 44 |
| Approve (first batch of 1,000) | 282 | 271 |
| Release, to the end | 53,538 | 215,581 |

The screen times don't grow with the incident's size. Preparation, release and export grow in line with it: about 0.3 ms per document for preparation, 0.2 ms for the release and 0.12 ms for the export (each document's row and its audit event are written in batches of 1,000). These runs are on this machine, against the `sdv_schema_test` scratch database of `sdv-postgres`.

Both runs checked:
- blocking is immediate on every path (one-document check, many-documents check, the guard for open, print and download) as soon as the confirmation returns;
- preparation and release finish; nothing is written or released twice;
- the counts on the incident match its rows;
- the export holds every document.

### Batch audit append

`AuditEventPort.appendAll` writes a batch of events in one JDBC batch (`JdbcFoundationRepository`; the session-name port passes it through). Preparation and release use it for each batch of 1,000.

## No other product's name or behaviour in code: yes

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken while the owner was away

1. **Levels in the counts are the level when quarantined.** A level changed later doesn't move the counts. Every per-document action and every list re-checks the level now, so nothing is shown that shouldn't be. The hidden count, the request check and the Approvals levels use the kept level.
2. **Rows written before 039 count as hidden** to people below the highest clearance until the job fills their level. A Steward can't request the release of such an incident until then. On the Approvals row they count as Highly Confidential.
3. **The Approvals row's received dates** are the whole incident's first and last received, also while the request waits (before: those still in force). Keeping first and last for the rows in force only can't be maintained as rows are released.
4. **The export's order** is the lists' order: in force first, then released, oldest first. Before, it was oldest quarantined first across both.
5. **Fingerprint and row count are no longer sent as headers** for this export: a streamed file can't know them before its first byte. The fingerprint is in the audit and worked out by the screen. Other exports are unchanged.
6. **Two audit events per exposure export** (started, finished), so an export that stops part-way is still on record.
7. **The approval's audit details** say `documentCount` (documents to release) instead of `releasedCount`, since the release finishes later. Each document's release is still audited by the approver, as before.
8. **The catalog's SDV-QUA-004 text** still mentioned the 1,000 limit removed in 0051. It is corrected with this change.
9. **No new screen tour step:** an incident of more than one page needs more than 100 matching documents, and the demo data has far fewer. The unit tests, the words tests and the PostgreSQL test cover paging and the background release.
10. **Merged without the screen tour.** The queue allows a merge once `ci-passed` is green; CI runs the same backend, PostgreSQL and frontend tests. The tour is left for the owner (see "Not done").

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final, after every change) | PASS: backend 657 tests, 0 failures, 0 errors, 17 skipped (the PostgreSQL-only tests; 0051: 653/16); frontend build OK |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 55 tests (54 before: 2 new, 1 removed) |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 039`, 250,000 documents) | PASS in 297 s. Step 4q passed; step 4f passed every PostgreSQL test, `QuarantineScalePostgresTest` included (204 s) |
| `.\Test-SDV-SchemaUpgrade.ps1 -QuarantineScale 1000000` (once, local only) | PASS in 833 s |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 91 s |
| First schema check run | FAIL: the new test's seed used a temporary table, which each new connection lost. Fixed (IDs worked out from the submission number); the run above passed |
| Schema step on the local `sdv` database: `status` | 1 changeset would run: `039-quarantine-scale` |
| Schema step on the local `sdv` database: `update` | **Not run: refused** by this session's automatic permission check (as in tasks 0048 and 0050). Not worked around |
| `npm run ui:capture` | **Not run**: Vero doesn't start on the local `sdv` database until 039 is applied (SDV-SYS-001) |


## Tests: counts, and any failures with their output

- **New in `QuarantineServiceTest` (3):**
  - the lists page through the incident with the total from the counts; a Steward's hidden count; the exposure view pages; an unknown page refused;
  - the export streams every document, and audits its start and its fingerprint;
  - rows written before the counts count as hidden until their level is filled in.
- **Changed in `QuarantineServiceTest`:**
  - "a single release frees one document and a whole release ends the filter": after the approval it now checks the release is in progress (first batch, still blocked, nothing can be added). Then the job finishes it.
  - The 0051 assertion on the 500-document list is now on a page of 100 with a next page.
- **Changed elsewhere:**
  - `QuarantineControllerTest`: the export test now checks a streamed body with its reference and no length (it checked the fingerprint and rows headers before).
  - `QuarantineFilterBlockingPostgresTest`: the job finishes the whole release; the exposure view is a page of 100.
  - `QuarantinePostgresTest`, `InMemoryQuarantineRepository`: the new insert arguments.
- **New PostgreSQL test:** `QuarantineScalePostgresTest`, added to CI's "must run" list and to schema check step 4f.
- **New schema check step 4q** (039), described below.
- **Frontend:** `quarantineWords.test.ts`: page note and release progress (2 new). The 0051 "first 500 of N" test is removed with the function it tested. The tour checks the exposure fingerprint against the saved file.

## Files changed

- **Backend:**
  - New: `039-quarantine-scale.sql`, `039-quarantine-counts-trigger.sql`, `039-quarantine-scale.yaml`.
  - Changed: `db.changelog-master.yaml`, `QuarantineRepository`, `QuarantineService`, `JdbcQuarantineRepository`, `QuarantineController`, `QuarantinePreparationScheduler`, `AuditEventPort`, `JdbcFoundationRepository`, `SessionNamedAuditEventPort`, `AuditSentences`.
- **Backend tests:**
  - New: `QuarantineScalePostgresTest`.
  - Changed: `InMemoryQuarantineRepository`, `QuarantineServiceTest`, `QuarantineControllerTest`, `QuarantineFilterBlockingPostgresTest`, `QuarantinePostgresTest`.
- **Frontend:** `api.ts`, `liveApi.ts`, `Quarantine.tsx`, `quarantineWords.ts` and its test, `auditWords.ts`, `problemWords.ts`, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and CI:**
  - `Test-SDV-SchemaUpgrade.ps1`: default `-From 039`, step 4q, the new test, `-QuarantineScale`.
  - `.github/workflows/ci.yml`: the new test must run.
- **Docs:** `docs/api/error-codes.md` (SDV-QUA-004, 008, 010), this report and the task.

## Commits (hash and message), or "not committed"

See the PR: one commit, "perf(quarantine): counts on the incident, paged lists, background release, streamed export (task 0052)".

## Not done, and why

- **The screen tour (`ui:capture`) was not run.** The local schema step (`Update-SDV-Schema.ps1 update` on the local `sdv` database, standing approval) was refused by this session's automatic permission check, as in tasks 0048 and 0050. Without 039 the app answers SDV-SYS-001 and the tour can't start. I didn't work around the refusal. The owner runs the step, then the tour.
- The count before confirming still counts every matching document (measured above). It is what the person sees before confirming, so it can't come from the counts.
- A level change after the quarantine doesn't move the incident's counts (decision 1).

## Questions for the owner

None blocking. Please run `.PDATE-SDV-SCHEMA.PS1 UPDATE -URL JDBC:POSTGRESQL://LOCALHOST:5433/SDV -USERNAME SDV` (IT APPLIES 039), START VERO AND RUN `NPM RUN UI:CAPTURE`. Are the agreed times above right? I set them myself because nobody could confirm them.

## Suggested next step

Merge after `ci-passed`.
