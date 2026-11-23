# Report 0055: Restrictions page

Date: 2026-11-23
Branch: `feature/restrictions-page`, from `release/vero-r7` at `fd15fcc` (after 0054 was merged, PR #28)
Outcome: **DONE, except the screen tour**. Built and tested; the schema check and the clean install passed; the PostgreSQL test measured the page. **The screen tour was not run**: the local schema step is refused in this session (as in 0052 to 0054). Merged through the PR once `ci-passed` was green.

## What was done

A new Business administration tab, **Restrictions**, between Legal holds and Search fields. It is read-only, in the Legal holds and Approvals look, and each part links to the page that acts on it where a Business Admin may go.

1. **Restricted documents**: Confidential and Highly Confidential documents by sending system, with totals. They come from the Storage and retention report's counts (task 0053, kept in the background); the page says when they were counted.
2. **Records with a record level**: how many, and a list of 50 at a time, newest first: the record's primary identifier, level and date set. No titles, no other fields.
3. **Level changes of the last 30 days**, 50 at a time, newest first: who, when, one document or the whole record, from, to, and the record by its primary identifier. A document's change names its record, never the document. Reasons stay out.
4. **Lowering requests waiting**: how many and since when the oldest has waited, with a link to **Approvals**.
5. **Legal holds**: holds in place and the documents they hold, with a link to **Legal holds**.
6. **Open quarantine incidents**: how many, their documents still quarantined, and those still being prepared. Counts only, from the incidents' own counts (task 0052). No link: Quarantine is the Stewards' page.
7. **Separation rule 4**: records by primary identifier only; never the internal document ID, titles, file names, content or reasons. A record key that can't be read is shown as "Record not named", never guessed.
8. **Who may**: a new capability `VIEW_RESTRICTIONS`, for the Business Admin only. Everyone else gets **SDV-RST-001** (new), audited as `AUTHORIZATION_DENIED`. A page position Vero didn't give is **SDV-RST-002** (new). Both are in the error catalog and the screen's words.
9. **Fast at a million documents**:
   - nothing here reads the documents; the levels come from the kept storage counts;
   - the two lists page through their own indexes (changeset 042: restricted records by date set; level changes by time);
   - the other parts are counts on small tables or on the incidents' own counts.
10. `GET /api/v1/admin/restrictions` (overview with the first page of each list), `/records?after=` and `/level-changes?after=`.

## No other product's name or behaviour in code: yes

No other product's name or behaviour in code. `ProductNameGuardTest` passes in the full build.

## Decisions taken while the owner was away

1. **A new capability and two new codes** (`VIEW_RESTRICTIONS`, SDV-RST-001 and 002) rather than borrowing another page's. A refusal then names the right page in the audit and on the screen. It is fixed to the Business Admin, as the task says, and the role matrix test says so.
2. **The levels come from the Storage and retention counts**, so they can be up to 15 minutes old (the page says when they were counted). Counting them live would read every document on each visit.
3. **A record's primary identifier** is its sending system's first business identifier in display order. For a record of one submission, it is that submission's identifier. The value shown is the one in the record key (normalized, as search matches it).
4. **"Linking nowhere a Business Admin can't go"**: Approvals and Legal holds have links; quarantine has none.
5. **Changeset 042** adds two indexes only (approved for speed); no table or row changes.
6. **No schema change of tables, no new dependency.**

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final) | PASS: backend 679 tests, 0 failures, 0 errors, 20 skipped (the PostgreSQL-only tests; 0054: 672/19); frontend build OK |
| `cd frontend/sdv-ui; npm run typecheck` | PASS |
| `cd frontend/sdv-ui; npm test` (PowerShell) | PASS: 63 tests (61 before, 2 new) |
| `npx playwright test --list` | The new tour step is listed (the file compiles) |
| `.\Test-SDV-SchemaUpgrade.ps1` (default `-From 042`) | PASS in 493 s; step 4t passed; step 4f passed every PostgreSQL test, `RestrictionsPostgresTest` included |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 119 s |
| A first scripted edit of the words, the catalog and the tab | It used a Perl UTF-8 switch that garbled the non-ASCII characters (’, —). I put back the eight files from git before any test, and applied the edit again without the switch; the committed diff has no garbled character |
| Local schema step and `npm run ui:capture` | **Not run**: the local schema step is refused in this session (see 0052) |


## Tests: counts, and any failures with their output

- **New `RestrictionsServiceTest` (4):**
  - each section;
  - records named by their primary identifier only (the primary field, not another identifier; a one-submission record; a key that can't be read; a document's change named by its record);
  - nothing restricted leaks (no submission ID, other identifier, document ID, reason, title or file name in the answer);
  - pages, and an unknown page position refused (SDV-RST-002).
- **New `RestrictionsControllerTest` (2):** every role but the Business Admin refused on all three endpoints, with SDV-RST-001, before the service is called, and audited as `AUTHORIZATION_DENIED` on `VIEW_RESTRICTIONS`; the Business Admin gets the overview and the pages.
- **New `RestrictionsPostgresTest` (PostgreSQL; CI and schema check step 4f):**
  - 250,000 documents, 20,000 records with a record level, 31,100 level changes (1,000 older than 30 days), 500 holds, a waiting lowering and an open incident;
  - every section counts what it should;
  - no reason, file name or document ID in the answer;
  - every restricted record listed once across the pages;
  - the overview, a deep page of records and a deep page of changes each within the agreed 1,000 ms.
- **Changed `FinalRoleMatrixTest`** (as the rules ask, said here): the Business Admin's expected capabilities now include `VIEW_RESTRICTIONS`, and the matrix has its row.
- **Frontend `restrictionWords.test.ts` (2).**
- **Tour:** a new step, "Business administration: the Restrictions page" (role check, the sections, no file names, the link to Legal holds), screenshot `governance-restrictions`. **Not run.**

**Measured** (`RestrictionsPostgresTest`, this machine, scratch database; agreed: 1,000 ms for a screen's API, as tasks 0052 to 0054):

| Measure | ms |
|---|---:|
| Overview (API; median of 3 after a warm-up) | 188 |
| Records with a record level, page 301 (API) | 38 |
| Level changes, page 202 (API) | 39 |


## Files changed

- **Backend:**
  - New: `042-restrictions-indexes.sql` and `.yaml`, `RestrictionsRepository` (port), `RestrictionsService`, `JdbcRestrictionsRepository`, `RestrictionsController`.
  - Changed: `db.changelog-master.yaml`, `Capability`, `RoleCapabilities`, `CapabilityDenied`, `SdvErrorCode`, `AuditSentences`.
- **Backend tests:**
  - New: `RestrictionsServiceTest`, `RestrictionsControllerTest`, `RestrictionsPostgresTest`.
  - Changed: `FinalRoleMatrixTest`.
- **Frontend:** new `Restrictions.tsx`, `restrictionWords.ts` and its test; changed `Governance.tsx`, `store.tsx` (the tab), `liveApi.ts`, `model.ts` (the capability), `problemWords.ts`, `e2e/sdv-screen-tour.spec.ts`.
- **Scripts and CI:**
  - `Test-SDV-SchemaUpgrade.ps1`: default `-From 042`, step 4t, the new test.
  - `.github/workflows/ci.yml`: the new test must run.
- **Docs:** `docs/api/error-codes.md` (SDV-RST-001 and 002), this report and the task.

## Commits (hash and message), or "not committed"

See the PR: one commit, "feat(admin): Restrictions page (task 0055)".

## Not done, and why

- **The screen tour was not run** (the local schema step is refused in this session; see 0052). The new step is ready.

## Questions for the owner

None blocking.

## Suggested next step

Merge after `ci-passed`; then the owner runs the local schema step (039 to 042) and the tour.
