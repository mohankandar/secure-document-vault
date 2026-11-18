# Report 0001: three quick UI fixes (prototype wording, real times, identifier box)

Date: 2026-11-17
Branch: `fix/ui-quick-fixes` (from `release/vero-r7`)
Outcome: PARTLY DONE. All code changes are made, the type check and build pass, and they are committed. The checks against the running app (search times, record panel and viewer with seeded records, `npm run ui:capture`) could not run. See "Not done, and why".

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

1. Please unseal the local Vault. Then I can run the live checks (search times, identifier box with real records) and add them to this report.
2. Should I add `@playwright/test` as a dev dependency so `npm run ui:capture` can run? Task 0002 also depends on it.
3. Should the "In production the server enforces this…" text in the "not permitted" toast (`Viewer.tsx:115`) be reworded in a later task? It is prototype wording and it is reachable in a connected session.
4. `sdv-postgres` and `sdv-vault` were started by the start script and are still running. Should I leave them running, or stop them with `.\stop-local.cmd`?

## Suggested next step

Owner reviews this report, unseals the Vault and decides on Playwright. Then I run the live checks, and task 0002 can start from `fix/ui-quick-fixes`.
