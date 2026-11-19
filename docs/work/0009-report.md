# Report 0009: Keeping documents, Search fields and the Record title setting

Branch `feature/admin-2`, taken from `feature/admin-1`. Part C's backend setting and migration 019 were approved with the task. There is no other backend or schema change, and no new dependency.

## What changed

**Part A: Keeping documents** (`src/r7/KeepingDocuments.tsx`, new)
- **The rule in use, as a sentence:**
  - "Rule in use since 18 Nov 2026", "Keep for 7 years" or "Keep forever", and "Counted from the day each document arrives."
  - Who set it and why: "Set by {subject}: "{reason}"". A rule written by the installation (`SYSTEM_MIGRATION`) reads "Set at installation".
- **When time is up:** "Vero deletes the document for good and records it in the audit. A daily cleanup does this."
  - **The "last ran today at 07:00…" sentence is left out.** The last run is only in `/api/v1/operations/retention-status`, which needs `VIEW_TECHNICAL_FAILURES`, and a Business admin does not have that. Permissions were not changed.
- **Changing the rule:** the agreed sentence.
- **New rule panel:**
  - A number plus years, months or days, or **Forever**. The panel shows the days in small text ("2,555 days. Or choose "Forever".").
  - "Why this changed" is required, as is the tick "This change has been approved". The button is **Start using this rule**.
  - It uses the same API as before. The rule's name is its sentence ("Keep for 7 years"), and the reason is stored as its description.
- **How units become days:** a year is 365 days and a month is 30 days, in both directions. So 7 years = 2,555 days, and 2,555 days reads back as "7 years". A number of days that is not whole years or months is shown as days. The largest rule is 36,500 days, as before.
- **Earlier rules:** columns Rule, In use ("Since 18 Nov 2026", or "6 Nov 2026 – 18 Nov 2026"), Set by and Why. The rule in use has an "In use" badge. No v1/v2 or "Superseded".

**Part B: Search fields** (`src/r7/SearchFields.tsx`, new)
- **Sending-system picker:** shown only when there is more than one sending system. The development database has one, so it doesn't show there.
- **One row per field:** drag handle, label, a **Searchable** switch, and **How it matches** in words: "Exact", "Starts with, any case" or "Contains, any case". Then Edit.
  - Identifiers show "Identifier · always searchable" and "Always".
- **Reordering:** drag the handle (mouse, touch or pen), or use the arrow keys on the handle. Each move saves through the existing display-order endpoint and reads the order back. The separate Reorder dialog is gone.
- **Edit panel:**
  - "Label people see" and "People can search by it".
  - "How it matches" as three choices with the examples. Field key, type, normalization and version are folded under **Technical details**.
- **Limits:** "Records per page: 25 (most 50)", "Comment length: 2,000 characters", "Tag length: 80 characters".
- `Governance.tsx` now shows the two new pages, with the design's introductions. The old two tabs were removed from it.

**Part C: Record title**
- **Migration 019** (`019-record-title-fields.sql` and `.yaml`, added to the master changelog):
  - It creates table `record_title_fields`. Columns: tenant, source, field key, position (0 or 1).
  - Keys:
    - one field per position;
    - each field at most once;
    - a foreign key to `ingestion_sources (tenant_id, source_id)`.
  - It runs at startup like 018. It ran on the development database, and the table is empty.
- **Storage and setting:**
  - Stored through `MetadataFieldDefinitionRepository.findRecordTitleFields` and `replaceRecordTitleFields`, in `JdbcFoundationRepository`.
  - Set through `MetadataFieldAdminService.updateRecordTitleFields`. It is refused when there are more than 2 fields, a field chosen twice, an unknown field, an identifier, or no list. An empty list goes back to the default.
  - The change is audited as `RECORD_TITLE_FIELDS_UPDATED` with `{"fieldCount":n}`, like the field-order change.
- **API:** `GET` and `PUT /api/v1/admin/metadata-sources/{sourceId}/record-title`, both requiring `CONFIGURE_METADATA_SEARCH` (`FoundationAdminController`).
- **The title itself:** `BusinessExperienceService.recordTitle` gives the title for search results, Recently viewed and record detail (`title` on `RecordSummary` and `RecordView`).
  - With a setting, it is the chosen values joined by a space.
  - If any chosen field has no value on that record, or no setting exists, it is the first preview field, as today.
  - It is never an identifier, and it is null when the record has no other field.
- **Frontend:**
  - Home (Recently viewed and Needs attention), the record panel heading and the viewer now use the server's title (`Home.tsx`, `Workspace.tsx`, `liveApi.ts`). The frontend's own guessing is removed.
  - A record without a title is named by its identifier.
  - The Records rows keep their identifier and field columns.

**Tests**
- **Backend:**
  - `RecordTitleTest` (6): the default title; chosen fields in order; a missing or blank chosen field falls back to the default; never an identifier; search and record detail carry the chosen title; no setting gives the default.
  - `RecordTitleSettingTest` (4): a Business admin can change it, and the change is audited; Business user, Steward, Tenant IT and Platform operations are refused for both read and change, and nothing is stored; unclear settings are refused; an empty list goes back to the default.
- **Tour** (`e2e/sdv-screen-tour.spec.ts`, desktop run only):
  - **Keeping documents:** a 7-year rule saves and is first under Earlier rules, with the earlier rule second. The earlier rule is then started again.
  - **Search fields:**
    - Last Name's match is changed to Starts with, saved, and changed back.
    - The last field is dragged to the top, and the order saves.
    - The Record title is set to Last Name + First Name.
  - **As a Business user:**
    - Filters lists the fields in the new order.
    - The viewer, the record panel and Home show "Patel Ava" for 300000000076.
    - Order and title are put back through the API in a `finally` step, and the default title "Ava" is checked.
  - `playwright.config.ts` now has `actionTimeout: 20_000`, so a stuck click fails quickly and the clean-up still runs. An early run had shown this was needed.

## Checks

| Command | Result |
|---|---|
| `.\stop-local.cmd`, then `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, all backend tests 0 failures (sdv-web 54, including the 10 new), frontend built |
| `npm run typecheck`, `npm run build` (rerun after the last CSS and test edits) | PASS |
| `.\start-local.cmd`, migration 019 | Applied (`databasechangelog`: `019-record-title-fields` EXECUTED). `GET …/record-title` returns 200 for a Business admin and 403 for a Business user |
| `npm run ui:capture` | **PASS**: 16 passed, 2 skipped (the opt-in comment and redaction tests) |
| After the tour | Field order, Record title (none), Last Name match (Contains) and rule (Keep forever) are as before |
| Screenshots | 16 files in `.runtime/ui-checks/0009/`: Keeping documents, its New rule panel, Search fields and the field edit panel, at 1440 and 390 px, in light and dark |

**Two failed tour runs during the work, both fixed:**
- **What failed:** both left the field order and Record title changed. The cause was a click on a "Close filters" button that does not exist on desktop. The click waited until the test timeout, so the clean-up step could not run.
- **The fix:** I put the settings back through the API both times and checked them. The test now closes Filters with Escape and has a click time limit.

## Notes for the owner

- **Each desktop tour run adds two rules to Earlier rules** (7 years, then back to the earlier rule). Rules are never edited or deleted, so the development database now lists 9. It is what the check asks for. If the list grows too long, this step could become opt-in, like the redaction test.
- **Case wording.** The design's "Upper and lower case never matter for names" is only true for Starts with and Contains. Exact compares the value as it was sent for TRIM fields, such as First Name. So the panel says "Upper and lower case never matter for Starts with and Contains", and explains Exact. The row words follow the same rule.
- **Turning Searchable off** stores Exact as the match, as the server already requires. Turning it back on starts from Exact.
- **Record title choices** are fields other than identifiers. The example name in the design ("e.g. "Mia Patel"") is shown as the chosen labels ("Shown as Last Name First Name"), because a Business admin cannot read records.

## Commits

One commit on `feature/admin-2`. Not pushed or merged. `Start-SDV-Local.ps1` is untouched.

## Questions for the owner

None.
