# Report 0020: Polish after the canvas review

Date: 2026-11-19
Branch: `fix/ui-polish-1` (from `release/vero-r7`)
Outcome: PARTLY DONE (items 2 to 6 done; item 1 stopped, the cause is on the server)

## What was done

**Item 1, Record "—" for "opened a record": STOPPED, cause is on the server.**
- `BusinessExperienceService.record(...)` writes `BUSINESS_RECORD_OPENED` with target type `BUSINESS_RECORD` and target ID `sha256(recordRef)`, a one-way hash. The safe details hold only `sourceId` and counts.
- The audit list's record lookup (`JdbcOperationalRepository.findRows`, task 0011 C.3) joins only `target_type='DOCUMENT'` targets to `documents`. A hash can't be joined to anything, so the record number is always empty.
- The frontend can't fix this. Two possible server fixes are under Questions. No backend change was made.

**Item 2, Overview › Latest activity.** It now shows only these events:
- documents opened, downloaded, printed or redacted (failed downloads and failed redactions count too);
- anything refused;
- settings changes;
- deliveries.

Searches, page loads, Recently viewed, audit views and record opens are left out, and so is "checked a folder for a new volume", because a check changes nothing. All of these stay in Audit. The list still has 5 rows. It now reads up to 5 pages back instead of 3, because searches can fill the newest events. (`auditWords.ts` `isMeaningfulActivity`, `Operations.tsx`)

**Item 3, Add or edit a sending system.** The wide panel now lies over the page with a shadow. The page behind it no longer moves aside, so the "What it sends" table keeps its width. The narrow panels still move the page aside, as approved in 0019. (`r7.css`, `ui.tsx` `Drawer`)

**Item 4, Tenant IT › Storage.** A volume set offline on purpose now reads "Offline (set by Tenant IT)" in the How full column, in grey with no warning tone. Read only and Being emptied get the same treatment. Platform operations and Tenant IT now share one function for this wording. (`Storage.tsx` `onPurposeWord`, `opsWords.ts`)

**Item 5, Search fields picker.** The sending-system picker is now as wide as the longest name, at least 190px and at most the panel width. (`r7.css` `.sf-source`)

**Item 6, Filters hints.** Each empty box shows a hint taken from the field's settings, never its name (`searchRouting.ts` `filterHint`):
- digits-only fields: "Digits only";
- other exact fields: "Any";
- Starts with: "The first letters";
- Contains: "Any part";
- dates keep the browser's date pattern.

**Deviation:** the canvas shows "12 digits", but the field settings hold no length. The fields read "Digits only" instead of guessing a count (see Questions).

## Commands run and results

From `frontend/sdv-ui`, with the app running (UI 5183, backend 8280):

| Command | Result |
|---|---|
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `npm run ui:capture` | 17 passed, 3 skipped; run twice, the second time after the last change |
| `npm run ui:compare` | 19 passed |

The 3 skips are the existing ones: redaction is opt-in, and two tests run on desktop only.

## Tests: counts, and any failures with their output

New tour checks in `e2e/sdv-screen-tour.spec.ts`:
- **Item 2:** Latest activity has no searches, page loads, Recently viewed or audit views, record opens or folder checks.
- **Item 3:** on a wide screen, the "What it sends" table keeps its width and position when the Add panel opens.
- **Item 4:** with a simulated offline volume (browser only), How full reads exactly "Offline (set by Tenant IT)". The page shows no "can't be read" text, the dot is grey and no banner appears. New capture: `storage-offline-on-purpose`.
- **Item 5:** the picker is wide enough for the widest option text.
- **Item 6:** Contract Number shows "Digits only", First Name shows "Any", and every text box has one of the four hints.

No failures. Item 1 has no check, because it was not fixed.

## Files changed

- `frontend/sdv-ui/src/r7/auditWords.ts`, `Operations.tsx`, `Storage.tsx`, `opsWords.ts`, `r7.css`, `ui.tsx`, `searchRouting.ts`, `Workspace.tsx`
- `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
- `docs/work/0020-task.md`, `docs/work/0020-report.md`

`Start-SDV-Local.ps1` was left as it was and not committed.

## Commits (hash and message), or "not committed"

`958eef9` "fix(ui): polish after the canvas review (task 0020, items 2-6)". Not pushed or merged.

## Not done, and why

- **Item 1:** the cause is on the server, and the task says to stop and report first.
- **"12 digits" in item 6:** the field settings hold no length, so a count can't be derived without guessing.

## Questions for the owner

1. **Item 1, which server fix?** Both are backend changes, and neither needs a schema change.
   - **(a)** When a record is opened, also put a safe, resolvable reference in the event, for example the record's first submission ID or its identifier value in the safe details. `findRows` then looks it up within the tenant. New events would show the number. Events written before the fix keep showing "—", because only the hash was stored.
   - **(b)** Same as (a), but also match old events by hashing each candidate record reference on read. This is slower and more complex.

   Recommendation: (a), as a small follow-up task.
2. **Item 6:** is "Digits only" acceptable? If you want "12 digits", it needs an "expected length" setting on the field, which is a backend and schema change.
3. **Item 2:** record opens ("opened a record") are left out, because the task lists only *documents* opened. Say if they should be shown.

## Suggested next step

Owner review of `fix/ui-polish-1`, then a decision on Question 1 for a follow-up task for item 1.

## Follow-up (2026-11-19): item 1, and the owner's answers

The owner's answers: **Question 1: option (a).** **Question 2:** "Digits only" is fine, no change. **Question 3:** keep record opens out of Overview, no change.

**Item 1, Record "—" for "opened a record": DONE for new events** (backend change, approved for this follow-up; no schema change).
- **What is stored.** `BusinessExperienceService.record(...)` now also stores `submissionId` in the safe details of `BUSINESS_RECORD_OPENED`. It is the ID of the record's first submission, an internal UUID, not the record number or any other readable value. The target ID is still the one-way hash.
- **What the Audit list does.** `JdbcOperationalRepository.findRows` reads `submissionId` from the safe details, only for `BUSINESS_RECORD` targets and only when it looks like a UUID. It then looks up the record number with the same identifier lookup that document events use. The lookup is limited to the event's own tenant (`v.tenant_id = a.tenant_id`). The Audit export uses the same query, so its Record column shows the number too.
- **Old events** hold only the hash, so they keep showing "—", as agreed.
- **Not changed:** the Audit "Record number" filter still matches document events only. A record open shows its number in the list but is not found by that filter. If you want it found, that is a separate small follow-up.

### Commands run and results (follow-up)

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS. Backend tests: 160 run, 0 failures, 0 errors (sdv-web 121). Frontend build passed. |
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `npm run ui:capture` (app restarted on the new build) | 17 passed, 3 skipped (the same skips as before) |
| `npm run ui:compare` | 19 passed |

My first run of the full build stopped partway through the `sdv-web` tests. I had redirected its output with PowerShell `*>`, which turned Mockito's warning on stderr into an error, and the script stops on errors. No test failed. The build was rerun with plain output redirection and passed as shown above.

### Tests added (follow-up)

- **Backend:** `backend/sdv-web/src/test/java/com/sdv/platform/application/business/RecordOpenAuditTest.java` (2 tests).
  - A record open stores the record's `submissionId`, and the target is still the hash. The safe details hold no record number, no readable field value and no record reference.
  - The Audit query reads `submissionId` only for record opens, casts it only when it is UUID-shaped, and looks it up within the event's tenant.
- **Tour:** in `auditChecks`, the record open that the check makes as the steward appears under "Opening and reading" as "Daniel Reyes opened a record". Its Record cell is exactly the record's number.

### Files changed (follow-up)

- `backend/sdv-application/.../business/BusinessExperienceService.java`
- `backend/sdv-persistence/.../operations/JdbcOperationalRepository.java`
- `backend/sdv-web/src/test/.../business/RecordOpenAuditTest.java` (new)
- `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
- `docs/work/0020-report.md`

`Start-SDV-Local.ps1` was left as it was and not committed.

### Commit (follow-up)

See `git log` on `fix/ui-polish-1`: "fix(audit): record opens show their record number (task 0020, item 1)". Not pushed or merged.
