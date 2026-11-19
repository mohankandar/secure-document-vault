# Report 0019: Admin screens look like the canvas

Branch `fix/match-canvas-admin` (from `fix/match-canvas-main`). Frontend and local demo data only; no backend, schema or dependency change.

## What changed

**Shared pieces** ([ui.tsx](../../frontend/sdv-ui/src/r7/ui.tsx), [r7.css](../../frontend/sdv-ui/src/r7/r7.css))
- `TechDetails`: the folded "Technical details" row from the canvas (card, or flat strip inside a panel). Used on About, Storage (both), Health, Problems, Audit, Integrations, Search fields, Storage edit panel and the viewer.
- `Banner`: the canvas notice strip with a bold lead-in (info, warning, problem).
- Side panels sit beside the page on wide screens, as on the canvas (440 px; 640 px for the sending-system editor).
- Small capital section labels (`v-sec`), status dots, thin fill bars, canvas page head and admin tabs.

**Business admin**
- People & roles: the "to give someone a role" note is the blue banner ([Governance.tsx](../../frontend/sdv-ui/src/r7/Governance.tsx)).
- Roles & permissions: action names in regular weight.
- Keeping documents: large rule card, two fact cards, plain "New rule" button, warning banner in the New rule panel ([KeepingDocuments.tsx](../../frontend/sdv-ui/src/r7/KeepingDocuments.tsx)).
- Search fields: source picker inline, fields table with the row being edited highlighted, limits card, panel's raw keys folded ([SearchFields.tsx](../../frontend/sdv-ui/src/r7/SearchFields.tsx)).

**Tenant IT**
- Integrations: flat list with status dots and "Add"; one detail card with Edit / Pause receiving, "What it sends" table, limits and sign-in side by side, raw keys folded ([Integrations.tsx](../../frontend/sdv-ui/src/r7/Integrations.tsx)).
- Add / edit sending system: wide side panel, "Step N of 3 · …" title, step chips, one-line field rows ([Integrations.tsx](../../frontend/sdv-ui/src/r7/Integrations.tsx)).
- Storage: one table of volumes (name and folder, role with dot, fill bar, Edit), "Add volume", warning banners, note, folded details ([Storage.tsx](../../frontend/sdv-ui/src/r7/Storage.tsx)).
- Problems (read only for Tenant IT): count chips "All · 4", table with kind badge and chevron, detail panel with sections, "Copy a note for the … team" ([Problems.tsx](../../frontend/sdv-ui/src/r7/Problems.tsx)).
- Sign-in (no board): About's fact card and the blue banner ([SignIn.tsx](../../frontend/sdv-ui/src/r7/SignIn.tsx)).
- Canvas ledes for every tab ([TenantAdmin.tsx](../../frontend/sdv-ui/src/r7/TenantAdmin.tsx)).

**Platform operations** ([Operations.tsx](../../frontend/sdv-ui/src/r7/Operations.tsx))
- Overview: attention banner, four tiles with coloured values and "Open →", latest activity with names in bold, "All activity →".
- Storage: table with fill bars and dots, "What to do:" banner, folded details.
- Health: "Checked …" left, "Check now" right, services with dots, cleanup card, folded details.
- Audit: one filter line (Activity, Person, Period, Record number, More filters, Export), names in bold, result dots, the canvas note ([AuditLog.tsx](../../frontend/sdv-ui/src/r7/AuditLog.tsx)).

**Everyone**
- About this installation: three-column fact card, "Copy details for support" with its hint, folded details ([About.tsx](../../frontend/sdv-ui/src/r7/About.tsx)).
- No access page: canvas card ([Shell.tsx](../../frontend/sdv-ui/src/r7/Shell.tsx)). Card-load error: canvas wording and "Ref …" ([CardError.tsx](../../frontend/sdv-ui/src/r7/CardError.tsx)).

**Tools and demo data**
- `npm run ui:compare` now also writes `.runtime/ui-compare/admin-<screen>.png` for all 14 admin boards (canvas | app side by side, plus app-only desktop, 390 px and dark images) ([ui-compare.spec.ts](../../frontend/sdv-ui/e2e/ui-compare.spec.ts)). The admin canvas is committed at `docs/design/vero-admin-after.html` because the tool reads it.
- [Seed-SDV-Canvas-Demo.ps1](../../Seed-SDV-Canvas-Demo.ps1): the volume in use is renamed `primary-a`, a standby `primary-b` is added, and "Title Records" is added (paused). The admin people and the Loan Origination System already came from 0018. Existing records are untouched.
- Tour updated to the new markup only (rows instead of cards, "Add volume", "Export", chip text, "Copy a note for the …", Enter on Record number, `details.v-tech`, "What to do:") ([sdv-screen-tour.spec.ts](../../frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts)). One check is looser: the storage row is checked for a percentage instead of "N% full · … free", because the canvas table shows the bar and percentage only (free space is under Technical details).
- Unused imports from this rework and the 0018 viewer rework removed. No files deleted.

## Checks

Run from `frontend/sdv-ui` with the local app running and the canvas seed applied.

| Command | Result |
|---|---|
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `npm run ui:capture` | 17 passed, 3 skipped (same skips as before), desktop and iPad |
| `npm run ui:compare` | 19 passed; images for all 14 admin boards |
| Product build marker search (same list as 0018) | no hits |

## Remaining differences

"Data" means the screen matches and only the local data differs from the canvas example.

| Screen | Difference | Why |
|---|---|---|
| All | Account menu shows "Acme Lending" without the role | Owner decision in 0018 (organization only) |
| All | "R7 · business integration" chip in the top bar | Development marker; not in a product build |
| People & roles | none | |
| Roles & permissions | Business user toggles differ | Data (real settings) |
| Keeping documents | Rule "Set by a Business admin" instead of a name | Server keeps only the sign-in ID (question 1) |
| Keeping documents | Many earlier rules; "last ran … removed 3" sentence absent | Data (tour leaves rule versions; cleanup has deleted nothing) |
| Keeping documents panel | Panel overlays the page head on narrow screens | Kept: below 1024 px the panel covers the page |
| Search fields | Record title chosen with two selects and Save; canvas shows one select with an example | Kept behaviour (no behaviour changes) |
| Integrations | Limits read "Vero's standard limit" instead of "50 MB" etc. | Server sends no per-source value when the standard applies (question 2) |
| Integrations | "Tour test system" in the list | Kept on purpose (owner, 0017) |
| Add sending system | "Key it sends" column is an input; Save as paused only on last step | Kept behaviour |
| Add sending system | "Identifies the record" is not set here | Set by Business admin in Search fields (question 3) |
| Tenant storage | 10% full, local folder paths; extra `tour-volume` (offline) | Data |
| Problems | "Check again" button kept | Kept behaviour |
| Ops overview | 3 problems not 4, 10% not 82%, "Nothing deleted today", different activity | Data |
| Ops storage | Blue "Nothing right now" instead of amber advice | Data (no volume is nearly full) |
| Ops health | Red "When a service is down (example)" box absent | Canvas example note; the tour shows the real red message |
| Audit | Default period "Last 30 days" (canvas "Last 7 days"); "Include view events" under More filters | Kept behaviour (question 4) |
| Audit | Activity rows are searches and page loads | Data |
| About | No "Installed" fact; product "Vero development" | Deployment mode comes with the shared install work (owner, 0012); local build |
| No access | Button "Go to Home" (canvas "Go to Records") | Home exists in the app |
| Card-load error | none | |

## Questions for the owner

1. **Keeping documents, "Set by".** The rule stores only the sign-in ID, so the screen says "A Business admin". Should the server keep the person's display name with each rule (backend change, small)?
2. **Integration limits.** Show "Vero's standard limit" as now, or should the server send the standard values (for example 50 MB) so the card can show numbers?
3. **"Identifies the record" in the add-system editor.** The canvas shows it in Tenant IT's editor; today it belongs to Business admin in Search fields. Keep it there?
4. **Audit default period.** Change the default from 30 days to 7 days as on the canvas (a behaviour change)?
5. **`frontend/sdv-ui/src/r7/integrations.css`.** Nothing imports it (it was already unused before this task). Delete it?

## Commits

See `git log fix/match-canvas-admin`. Not pushed, not merged.

## Follow-up (2026-11-18)

The owner answered the five questions. Done on `fix/match-canvas-admin`.

1. **Keeping documents, "Set by": the person's name is kept with each new rule.**
   - **Schema change, approved by the owner's answer:** migration 022 (`022-retention-policy-creator-name.sql/.yaml`) adds `retention_policy_versions.created_by_display_name`. It is nullable with no default, and existing rows are not changed.
   - The name comes from the signed-in session, the same way audit names do (migration 020). A blank name or one over 300 characters is not stored.
   - **API:** `GET/PUT /api/v1/admin/retention-policy` carry `createdByDisplayName` for the rule in use and for each earlier rule.
   - **Screen:** "Set by Priya Natarajan" and the name in the Earlier rules table. Older rules (all of today's rules) still say "a Business admin", and installation rules say "Set at installation". The sign-in ID is never shown.
2. **Integration limits are numbers.**
   - **New endpoint, Tenant IT only:** `GET /api/v1/admin/ingestion-sources/standard-limits` returns the tenant's standard limits (its guardrails), for example 250 MB, 2,000 pages, 100 documents, 24 hours. A Business admin is refused (403, checked live).
   - **The card shows the limit in use:** the system's own limit, never above the standard, because intake always uses the smaller of the two. If the standard values don't load, the card falls back to "Vero's standard limit".
   - **The editor's empty limit boxes show the standard,** for example "Standard: 250".
3. **"Identifies the record"** stays in Search fields for the Business admin. No change.
4. **Audit** opens on "Last 7 days" ([AuditLog.tsx](../../frontend/sdv-ui/src/r7/AuditLog.tsx)).
5. **`frontend/sdv-ui/src/r7/integrations.css` deleted.** Nothing imported it (checked again before deleting).

**Viewer fixes asked for after 0018:** all four were already in from the 0018 follow-up, and the `ui:compare` viewer image confirms them. No change was needed.
- Redact is red as on the canvas.
- "Borrower ID", not "Borrower id".
- Document ID and Submission are under a folded "Technical details".
- "Keeping: Kept until …", or "Kept forever", in Keeping documents' wording.

**Tests added:**
- `RetentionPolicyCreatorNameTest` (4): the name is stored trimmed; a blank or over-long name is not stored; the overview carries it, with null for older rules.
- `JdbcRetentionRepositoryCreatorNameSqlTest` (2): the insert and both reads use the new column.
- `StandardLimitsTest` (2): the standard is the tenant's guardrails; an inactive or unknown tenant is refused.

**Tour changes:**
- Integrations: no "Vero's standard limit" text, and the card's numbers match the server's standard limits.
- The opt-in keeping-rule step (`SDV_KEEPING_RULE_REGRESSION=1`) now expects "Priya Natarajan". It was not run because it adds permanent rules (owner, 2026-11-18). The name was proven by the unit tests and by the live API returning the new field: null for all 16 existing rules.

**Checks:**

| Command | Result |
|---|---|
| `.\stop-local.cmd`, `.\Build-SDV-All.ps1`, `.\start-local.cmd` | BUILD SUCCESS, 0 failures (sdv-web 119 tests, 8 new); frontend build passed; migration 022 applied in local mode at start |
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `npm run ui:capture` | 17 passed, 3 skipped (same skips as before), desktop and iPad |
| `npm run ui:compare` | 19 passed |

**Files:**
- **New:**
  - `022-retention-policy-creator-name.sql/.yaml` (added to the master changelog)
  - the three test files above
- **Changed:**
  - backend: `RetentionRepository`, `JdbcRetentionRepository`, `RetentionPolicyService`, `RetentionPolicyController`, `IngestionSourceAdminService`, `FoundationAdminController`
  - frontend: `liveApi.ts`, `KeepingDocuments.tsx`, `Integrations.tsx`, `AuditLog.tsx`
  - the tour
- **Deleted:** `src/r7/integrations.css`.

**Remaining differences now closed:**
- "Set by" shows a name for new rules.
- Limits show numbers.
- Audit opens on the last 7 days.

Not pushed, not merged.
