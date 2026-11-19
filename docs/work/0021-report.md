# Report 0021: Remove prototype and old code

Date: 2026-11-19
Branch: `chore/remove-prototype-code` (from `release/vero-r7`)
Outcome: DONE

## What was done

**Part A, R7 prototype paths.** The Records page and the viewer are only shown with a server session, so every `!liveSession` branch was dead code. Removed:
- `Viewer.tsx`: the simulated comments, tags and activity, `addDocActivity` on switching documents, the "Possible sensitive identifier" sample callout, "policy v… at ingestion", and "simulated redaction" in Details.
- `store.tsx`: the prototype role switch (`switchRole`), the data scenario (`scenario`), simulated activity and audit (`addDocActivity`, `recordAudit`, `updateDoc`), and the unused sample state (`roleCaps`, `retention`, `volumes`, `sources`, `identity`, `audit`). The role now comes only from the session.
- `Workspace.tsx`: the sample timeline in the record panel, and the tag count and "redactions" that only sample documents had.
- `Shell.tsx`: the development-only "R7 · business integration / Simulated" chip in the top bar. It said other workflows were pending, which is no longer true.
- `ui.tsx`: the unused `Scenario` wrapper, the "sim" callout tone and its CSS. `ErrorState` now takes an optional `onRetry`.
- `Home.tsx` had no prototype branch; only its import changed.
- `data.ts` is now `model.ts`. It keeps the shared types (trimmed to the fields the live code fills), the role definitions (without the sample people's names), the capability list and the business limits. Removed: `RECORDS`, `PAGE_TEXT`, `RETENTION`, `RETENTION_SWEEP`, `VOLUMES`, `IDENTITY`, `SYSTEM`, `SERVICES`, `FAILURES`, `STUCK`, `SUBMISSIONS`, `AUDIT`, `SOURCES`, the sample people, `DEFAULT_ROLE_CAPS`, `CAPABILITY_LABELS`, `BASELINE`, `CONFIGURABLE`, `NOW`, `ago`, `ahead`.
- With no session the app shows the existing "Your session didn't start" page and no data (new tour check).
- Kept, as the task says: local mode, its test sign-in and role switch, the Design system page, the "Later release" markers in development builds, `ui:capture` and `ui:compare`.

**Part B, the old R5 app.**
- `main.tsx` loads only Vero; `?app=r5` now opens Vero.
- `api.ts`: removed `api`, `businessApi`, `contentApi`, and 25 types only R5 used. `LOCAL_TENANT_ID` is no longer exported.
- `auth.ts`: `getAccessToken` and `clearOidcSession` are no longer exported (only used inside the file).
- `CLAUDE.md`: the line about `?app=r5` is gone. No other run instructions mentioned R5.

**Deleted files** (each checked first: nothing R7 uses imports it):
- `frontend/sdv-ui/src/legacy-entry.tsx`, `App.tsx`, `BusinessWorkspace.tsx`, `AdminKit.tsx`, `PdfViewer.tsx`, `Icon.tsx`
- `frontend/sdv-ui/src/styles.css`, `ui-foundation.css`, `business-workspace.css`, `admin-framework.css`, `tokens.css` (only R5 and `admin-framework.css` imported `tokens.css`)
- `frontend/sdv-ui/src/r7/data.ts`, replaced by `model.ts`

No other file under `frontend/sdv-ui/src` was left unused.

**Kept although only R5 called them:** `beginOidcLogin`, `handleOidcCallback` and `OIDC_SESSION_EXPIRED_EVENT` in `auth.ts`. They are the browser's OIDC sign-in, and a customer install needs them. See "Found while doing this".

**Small visible changes, development builds only:** the "Simulated" chip is gone from the top bar. On the Design system page, the crumb reads "Development only" instead of "Prototype", the "Simulated" callout sample is gone, and the States note no longer refers to the removed scenario switch. A product build doesn't show any of these, and every canvas comparison still passes.

## Commands run and results

From `frontend/sdv-ui`, with the app running (UI 5183, backend 8280):

| Command | Result |
|---|---|
| `npm run typecheck` | passed |
| `npm run build` | passed |
| `npm run ui:capture` | 19 passed, 3 skipped (the same 3 skips as before: redaction is opt-in, two tests run on desktop only) |
| `npm run ui:compare` | 19 passed |

An extra `tsc --noUnusedLocals` run found one unused import, left over from this change. It was fixed.

**Bundle size (production build, `dist/`):**

| | Before | After |
|---|---|---|
| Whole `dist/` | 817,525 bytes | 433,219 bytes |
| JavaScript Vero loads | 387.6 kB (116.8 kB gzip) | 368.3 kB (110.6 kB gzip) |
| CSS Vero loads | 64.8 kB (12.8 kB gzip) | 64.2 kB (12.7 kB gzip) |
| R5 chunks | 124.4 kB JS + 239.8 kB CSS | gone |

**Search of the built bundle:** "simulated", "Simulated", "R5", "Jordan Ellis", "Maya Okafor", "Daniel Reyes", "Northwind", "Alvarez", "Member Agreement", "412-55-0193", "legacy", "app=": no matches. Two near-misses are left, and neither is prototype code:
- "r5" appears once, inside a lucide icon's internal key (`5wwlr5`).
- "prototype" appears only as JavaScript's `.prototype`.

"Jordan Ellis" is still used in `e2e/ui-compare.spec.ts` and a comment in `recordColumns.ts`. That is the local demo seed (`Seed-SDV-Canvas-Demo.ps1`), not fixture data.

## Tests: counts, and any failures with their output

New tour check in `e2e/sdv-screen-tour.spec.ts`, run on desktop and tablet: "Without a session the app shows the error page, never sample data (task 0021)".
- When the server refuses the session, the page shows "Your session didn't start". The main area has no Records, Recently viewed, Needs attention or "simulated" text, and the top bar has no Simulated chip.
- `/?app=r5` shows the same Vero page, not the old sign-in card.

No failures.

## Files changed

- Deleted: the 12 files listed above.
- Changed: `frontend/sdv-ui/src/main.tsx`, `api.ts`, `auth.ts`, `r7/model.ts` (was `data.ts`), `r7/store.tsx`, `r7/Viewer.tsx`, `r7/Workspace.tsx`, `r7/Shell.tsx`, `r7/ui.tsx`, `r7/DesignSystem.tsx`, `r7/liveApi.ts`, `r7/r7.css`, and imports only in `r7/Home.tsx`, `Governance.tsx`, `SearchFields.tsx`, `recordColumns.ts`, `searchRouting.ts`, `sourceChoice.ts`
- `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
- `CLAUDE.md` (the R5 line)
- `docs/work/0021-task.md`, `docs/work/0021-report.md`

`Start-SDV-Local.ps1` was left as it was and not committed. No backend change.

## Commits (hash and message), or "not committed"

See `git log` on `chore/remove-prototype-code`: "chore(ui): remove prototype paths and the old R5 app (task 0021)". Not pushed or merged.

## Not done, and why

Nothing in the task was left undone.

## Found while doing this (for a later task)

1. **Vero has no OIDC sign-in screen.** Only R5 called `beginOidcLogin` and `handleOidcCallback`, and R5's sign-in already didn't work end to end: the sign-in system sends people back to `/`, which opened Vero, and Vero ignores the returned code. In an OIDC install, Vero shows "Your session didn't start" without a Sign in button. This was true before this task too. The sign-in code is kept in `auth.ts`. **Recommendation:** a small task for a Vero sign-in card: a Sign in button, and processing the code on return.
2. A toast with no tone (for example "Document downloaded") still shows the flask icon that the prototype used for "simulated". It was kept so nothing on screen changes. It could become a plain check mark.

## Questions for the owner

None for this task.

## Suggested next step

Owner review of `chore/remove-prototype-code`. Task 0022 is built on this branch.
