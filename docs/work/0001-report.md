# Report 0001: three quick UI fixes (prototype wording, real times, identifier box)

Date: 2026-11-17
Branch: `fix/ui-quick-fixes` (from `release/vero-r7`)
Outcome: PARTLY DONE. All code changes are made, the type check and build pass, and they are committed. The owner approved this task on 2026-11-17. The live checks with records still cannot run, because the local database has no demo records (see "Live checks, second run").

## What was done

**Part A: prototype wording**
1. The "R7 · business integration" / "Simulated" chip now shows only when `import.meta.env.DEV` is true (`src/r7/Shell.tsx`). Its text is not in the product bundle.
2. The tab title is now the product name only (`src/r7/entry.tsx`).
3. New single constant `PRODUCT_NAME = 'Vero'` in `src/r7/brand.ts`. It is used for the tab title, the brand mark, the brand's accessible name ("Vero home"), the tenant-name fallback in the top bar, and the Design system page title (development only). CSS `text-transform: lowercase` on `.v-brand` keeps the lowercase "vero" look. The R5 app and `index.html` (which R5 shares) are unchanged.
4. The redaction warning text in `src/r7/Viewer.tsx` is replaced with the agreed wording.
5. The list of remaining "simulated" and "prototype" text is below.

**Part B: real times.** `fmtRel` (`src/r7/ui.tsx`) now measures against `Date.now()`. A time up to 5 minutes in the future (clock skew) reads "just now". A time further in the future shows its date. An invalid date shows "—".

**Part C: identifier box** (`src/r7/r7.css`, `Viewer.tsx`, `Workspace.tsx`)
- `.ws-ids` makes one column per identifier, so one identifier fills the box and two sit side by side in the record panel.
- The viewer adds the `stacked` class, so its identifiers sit one above the other.
- Values stay on one line (`nowrap`) and end in "…" if they do not fit. The full value stays in the text for screen readers and in `title` for hover.

### Part A step 5: remaining "simulated" or "prototype" text

R7 has no backend-free prototype mode. Without a session it shows "Connecting…" or "Session unavailable" and no screens. So every item below is either in code that is never mounted, behind `!liveSession`, or development only.

| File:line | Text | Reachable in a connected session? |
|---|---|---|
| `TenantAdmin.tsx:61, 147, 153, 161, 217` | "Volume added/updated (simulated)", "Draft saved (simulated)", "vN active (simulated)", "Rolled back … (simulated)", "Integration saved (simulated)" | No. They are in `StorageTab`, `AuthTab` and `IntegrationsTab`, which nothing renders. Tenant IT shows `LiveStorage`, `LiveIdentity` and `ConnectedIntegrations` instead. These handlers only change local state and send nothing to the server. |
| `TenantAdmin.tsx:131` | "Simulated checks" callout | No (in the unmounted `AuthTab`). |
| `Operations.tsx:224` | "simulated" badge in the audit list | No. The `Audit`/`Overview` components that read the sample audit are not rendered. Operations uses `LiveOperationsSection`/`ConnectedOverview`. |
| `Viewer.tsx:485` | "N simulated redactions" | No. Connected documents always have `redactions: []` (`liveApi.ts:30`). Real redactions go to the server. |
| `Viewer.tsx:504`, `:544`; `Workspace.tsx:296` | "Comment added (simulated)", "· simulated" in activity | No. Only in the `!liveSession` branches. Connected comments, tags and activity use the API. |
| `Shell.tsx:22`, `:62`; `DesignSystem.tsx:19, 92` | "Prototype" rail group, Design system page | Development only. |
| `Shell.tsx:190` | Session drawer: "Role switching and simulated scenarios are unavailable in connected mode." | **Yes**, in non-LOCAL mode. It is a description only and does not claim anything was saved. Left as is; task 0002 replaces this drawer. |

Also found (not on the word list, left unchanged): `Viewer.tsx:115`. The "Not permitted for your role" toast says "In production the server enforces this and records a DENIED audit event." It is reachable in a connected session, when a role presses a locked viewer button. The click is blocked in the browser and nothing is sent to the server, so no DENIED audit event is recorded for that click.

### Part B step 2: remaining `NOW` uses (all sample data only, left as they are)

- `data.ts:4-6`: `NOW`, `ago()`, `ahead()` build the sample fixtures.
- `store.tsx:112` (`recordAudit`) and `store.tsx:121` (`addDocActivity`): timestamps for simulated audit and activity entries. They are called only from the unmounted Tenant IT tabs and the `!liveSession` viewer paths.

Not touched: `ConnectedAuthenticationView.tsx:14` has its own local `fmtRel` that already uses `Date.now()`.

## Commands run and results

| Command | Result |
|---|---|
| `npm run typecheck` (frontend/sdv-ui) | PASS |
| `npm run build` (frontend/sdv-ui) | PASS (built in 2.3 s) |
| `grep "business integration" dist/assets/*.js` | No match: the chip is not in the product build |
| `npx vite preview --port 5184` + headless Chrome, 1440 and 390 px | Top bar has no chip. Tab title is `Vero`. Brand accessible name is "Vero home". The backend was down, so the page shows "Session unavailable". |
| Static page using `r7.css`, headless Chrome | One identifier fills the box. Two sit side by side (record panel width) or stack (viewer column). A 12-digit and a 20-character value stay on one line in the 248 px viewer column. A 34-character value ends in "…". |
| `fmtRel` run in Node (esbuild bundle of `ui.tsx`) | 30 s → "just now", 10 min → "10 min ago", 3 h → "3 h ago", 5 d → "5 d ago", 100 d → date, +2 min → "just now", +2 h → date, invalid → "—", 15 Oct 2026 → "34 d ago" |
| `.\start-local.cmd` | **FAILED**: "sdv-vault is not healthy/unsealed". The script started the existing `sdv-postgres` and `sdv-vault` containers, which were stopped. The Vault is sealed. |
| `npm run ui:capture` | Not run: the app is not running, and Playwright is not installed (see Questions) |

### Live checks, second run (2026-11-17, after the owner unsealed the Vault)

| Step | Result |
|---|---|
| `.\start-local.cmd` (twice) | **FAILED** both times: "SDV frontend did not become reachable". Vite was in fact ready in 0.3 s on `127.0.0.1:5183`. The script checks `http://localhost:5183` with Windows PowerShell `Invoke-WebRequest`, which tries IPv6 `::1` first and times out, because Vite is started with `--host 127.0.0.1`. The script then stops both services. The probe line is the same in the committed version, so this problem existed before; it is not caused by the uncommitted changes. I did not edit the script. |
| Workaround | I started the backend and frontend in their own windows, with the same commands and environment as `Start-SDV-Local.ps1`, and wrote `.runtime/backend.pid` and `.runtime/frontend.pid` so `stop-local.cmd` can stop them. The Vault token file is passed to the backend by path only, as the script does; I did not open it. Backend health UP, frontend 200. |
| Search results show real update times | **Could not check: there are no records.** `GET /api/v1/business/search-schema` returns `{"sources":[]}`. A read-only check of `sdv-postgres` shows 2 tenants, 0 ingestion sources, 0 submissions, 0 documents, and 8 audit events in total. The database volume was created on 2026-11-07 and has never held documents, so nothing was lost. `Seed-SDV-Demo-Records.ps1` needs an existing source ("laxavault" / `lexaVault`), so it would fail. I did not create a source or write any data. |
| Record panel and viewer identifier box with real records | Could not check (no records). The static check above, which uses the same CSS at the same widths, still stands. |
| Product build has no chip | PASS (see above). |
| `npm run ui:capture` (Playwright 1.56.1, now installed) | **5 of 5 FAILED** (desktop project) at the first step. The spec looks for `getByLabel('Demo role')` and `.sidebar-identity`, which are R5 elements, but `/` now serves R7. The spec was already broken before this task; it is not caused by these changes. Task 0002 updates the specs. |

Playwright: `@playwright/test` 1.56.1 was added as an exact-pinned dev dependency (owner approved 2026-11-17). 1.49.1 was tried first and replaced, because it has a high-severity advisory: browsers were downloaded without SSL certificate checks (fixed in 1.55.1). `npm audit` still lists the existing `vite`, `esbuild` and `source-map-js` advisories, which were there before.

Screenshots: `.runtime/ui-checks/0001/` (ignored by git): `ids-check.png`, `topbar-product-1440.png`, `topbar-product-390.png`.

## Tests: counts, and any failures with their output

The frontend has no unit test runner (the task says not to add one). There are no automated tests for these changes. The checks above are the evidence. The e2e specs do not refer to anything changed here.

## Files changed

- `frontend/sdv-ui/src/r7/brand.ts` (new)
- `frontend/sdv-ui/src/r7/entry.tsx`, `Shell.tsx`, `DesignSystem.tsx`, `Viewer.tsx`, `Workspace.tsx`, `ui.tsx`, `r7.css`
- `CLAUDE.md`, `docs/work/README.md`, `docs/work/0001-task.md`, `docs/work/0001-report.md`

Not included: `Start-SDV-Local.ps1`, `Stop-SDV-Local.ps1` (local changes left as they are), `docs/design/` and `docs/work/0002-task.md` (they belong to task 0002).

## Commits (hash and message), or "not committed"

See `git log fix/ui-quick-fixes`: one commit, "fix(ui): remove prototype wording, real relative times, identifier box layout (task 0001)". Not pushed or merged.

## Not done, and why

- **Checks against the live app (search times, record panel and viewer with seeded records).** The local Vault is sealed. Unsealing needs the Vault keys, and `CLAUDE.md` does not allow me to touch them. The owner needs to unseal it.
- **`npm run ui:capture`.** `@playwright/test` is not in `package.json` and not installed, so the script cannot run even with the app up. Adding it is a new dependency and needs approval.

## Questions for the owner

Answered on 2026-11-17: the Vault is unsealed, Playwright is approved, the containers stay running, and task 0001 is approved.

Still open:
1. **Demo data.** The local database has no ingestion source and no documents. Should I create the "laxavault" source and run `Seed-SDV-Demo-Records.ps1 -FreshTestData`? That writes to the development database. Or will you seed it, or point me at the data you meant?
2. **Start script.** `Start-SDV-Local.ps1` checks `localhost:5183` while Vite listens only on `127.0.0.1`, so it fails on this machine. The fix is to probe `http://127.0.0.1:5183`. The file has your uncommitted changes, so I have left it alone. Should a task fix it?
3. Should the "In production the server enforces this…" text in the "not permitted" toast (`Viewer.tsx:115`) be reworded in a later task?

## Suggested next step

Seed demo data (question 1), then rerun the search-time and identifier checks.
