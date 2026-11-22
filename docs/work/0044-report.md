# Report 0044: Fix 10, error wording on every screen (UI only)

Date: 2026-11-22
Branch: `fix/screen-errors` (from `release/vero-r7` at `8b3a420`)
Outcome: DONE

No other product's name or behaviour in code.

## What was done

1. **One shared helper**, `errorWords` in `frontend/sdv-ui/src/r7/problemWords.ts`. The screens reach it through `CardError.tsx` (`describeFailure`, `failureText`, `exportFailureText`, `loadFailed`, `actionFailed`, `toFailure`). It has four kinds:
   - **refusal** (a 4xx other than 401/403): the code's sentence from `CODE_WORDS`, then `(SDV-XXX-NNN)`. An unknown code gets "Vero can’t do this as asked. Check what you entered and try again." plus the code.
   - **not allowed** (401/403): "You don’t have access to this." plus what to do. That is the code's sentence if the code is known, otherwise "Sign in again." for a 401 or "If you need it for your work, ask your IT team." for a 403. The code follows.
   - **Vero's fault** (5xx, or any error that didn't come from the server): "Something went wrong in Vero.", then the code's sentence if known, then when to try again, then "Ref …" and the code. A 503 says "Try again in a few minutes", or "Try again in N seconds" when `Retry-After` is sent (SDV-SYS-018). There is always a reference: the server's, or a new one written to the browser console next to the raw error.
   - **network** (no answer at all): "Vero can’t be reached. Check your connection."
2. **Codes and references:** `CODE_WORDS` holds one sentence per code a screen can get (SEN, ROL, AUT, IDP, HLD, APR, INT-001..003, RPT, SYS, STO, SCH). Each is taken from the catalog's "What the user should do". The DOC codes keep the task 0043 words (`DOCUMENT_PROBLEM_WORDS`), so the viewer now uses the same helper with its words unchanged. The only difference: a DOC fault always has a reference, even when the server sent none. The old `documentProblemText` is removed because the helper replaces it.
3. **Exports** (audit, Daily intake, Sensitivity): "The export didn’t work." plus the helper's words, with the reference for every kind.
4. **Never the raw server text:**
   - `api.ts`: `ApiRequestError` no longer keeps the body's `message` (its message is `HTTP <status>`). A reference is accepted only if it looks like a reference (letters, digits, dashes). It reads `Retry-After` and the body's `reference`. A fetch that gets no answer becomes status 0 (network).
   - Fixed screens: `Approvals.tsx` (bulk items used the server's message; they now use `itemWords`), `AuditLog.tsx`, `Notices.tsx`, `Reports.tsx` (a refusal line now only for a coded 4xx refusal; everything else is the card), `Sensitivity.tsx` (`refusalText`, also used by Legal holds and Approvals), and `Operations.tsx`.
   - `Operations.tsx` details: the database mismatch sentence now comes from `schemaWords(state)` in the frontend (the same sentences as the start-up log). The server's health `message` is no longer shown under Technical details.
   - All other screens already used `CardError` and now get the four kinds through it.
5. **Vitest 2.1.9** (dev dependency, exact version; the newest that supports Vite 5.4). New script `npm test` (`vitest run --dir src`, so the Playwright specs are left out). The tests are in `src/r7/problemWords.test.ts`.
6. **Screen tour:** "Business Administrator screens", Roles & permissions card. After the existing 500, it now forces:
   - a 503 SDV-SYS-018 with `Retry-After: 7`, a reference and a server message, and checks for "Something went wrong in Vero. Vero is too busy… Try again in 7 seconds.", "Ref tour-ref-busy." and "(SDV-SYS-018)", with no server text;
   - an aborted request (no answer), and checks for "Vero can’t be reached. Check your connection." with no reference.

   New screenshots: `card-error-busy` and `card-error-network`.

## Commands run and results

| Command | Result |
|---|---|
| `npm install --save-dev --save-exact vitest@2.1.9` (owner approved the retry in this session) | Installed. 30 packages added |
| `npm test` | 19 passed, 0 failed |
| `npm run typecheck` | No errors |
| `Stop-SDV-Local.ps1` | App stopped. sdv-postgres and sdv-vault left running |
| `Build-SDV-All.ps1` (in a child powershell.exe, log in `.runtime/build-0044.log`) | **BUILD SUCCESS**, exit 0. Frontend production build included |
| `Start-SDV-Local.ps1`, then readiness 200 and UI 200 | App running |
| `npm run ui:capture` | **25 passed, 0 failed, 11 skipped** (6.3 min) |

The 11 skips are the tour's own rules: desktop-only steps on iPad, and the opt-in steps for bulk approval and redaction. Report 0043 had the same count.

## Tests: counts, and any failures with their output

- Backend: sdv-application 24, sdv-security 50, sdv-storage 2, sdv-crypto 4, sdv-web 500 (8 skipped: the PostgreSQL tests). 0 failures. No backend code was changed.
- Frontend unit tests (new): 19 passed. They cover:
  - refusal with a known code, an unknown code and no code;
  - an export with its reference;
  - 401 and 403, each with no code, a known code and an unknown code;
  - 500, a known 5xx code, a 503 without `Retry-After`, and SYS-018 with 5 seconds and with 1 second;
  - a missing server reference, and a non-server error;
  - network;
  - DOC words kept;
  - the server's message never appears, across every status and code;
  - bulk item words.
- Tour: 25 passed.
- **Changed tour assertion:** the self-approval step (SDV-APR-006) used to expect the server's own sentence, "You can't approve or decline your own request. (SDV-APR-006)". This task forbids showing server text, so it now expects "You don’t have access to this. Nobody decides their own request. Ask another Business admin to decide it. (SDV-APR-006)". It also checks that the server's text is not shown.

## Files changed

`frontend/sdv-ui/`:
- `package.json`, `package-lock.json`
- `src/api.ts`
- `src/r7/problemWords.ts`, `src/r7/problemWords.test.ts` (new)
- `src/r7/CardError.tsx`
- `src/r7/Approvals.tsx`, `AuditLog.tsx`, `Notices.tsx`, `Operations.tsx`, `Reports.tsx`, `Sensitivity.tsx`, `Viewer.tsx`
- `e2e/sdv-screen-tour.spec.ts`

Plus `docs/work/0044-task.md` and this report.

## Commits (hash and message), or "not committed"

- `7af5abf` docs(work): task 0044, fix 10, error wording on every screen
- `0c56eba` feat(ui): one way to show an error on every screen, never the server's text (task 0044)
- This report is committed after those.

Not pushed, not merged. `Start-SDV-Local.ps1` (your local change) and the untracked 0041 and 0042 task files are left as they were.

## Not done, and why

- **No PR opened:** the session said not to push.
- **`Build-SDV-All.ps1` doesn't run `npm test`:** the task says 0042's CI runs it, so the build script is unchanged.
- **Steward notice line not changed:** `Notices.tsx` still shows the notice's own `message` ("A corrected document was sent again" banner). It is a fixed product sentence for the one notice kind, not an error, so I left it.

## Questions for the owner

1. **New `npm audit` findings from Vitest.** Before: 3 (esbuild, source-map-js; through Vite). After: 6. The new ones are tinypool (critical), @vitest/mocker and vitest. All are dev-only test tooling and are not in the shipped `dist`. Fixing them needs Vitest 4, which needs a newer Vite, so it is a dependency upgrade: do you want a task for it?
2. **Should a refusal show its reference too?** Refusals and "not allowed" lines show only the code; exports always add the reference. Say if refusals should show it as well.

## Suggested next step

Review the branch, then open the PR for `fix/screen-errors` when you say so. Task 0042 (CI) can then run `npm test`.
