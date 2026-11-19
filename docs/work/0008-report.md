# Report 0008: Admin shared pieces, People & roles, Roles & permissions

Branch `feature/admin-1`, taken from `feature/page-view-audit`. Frontend only. No backend or schema change, and no dependency change.

## What changed

**Part A: shared pieces**
- **One main colour:** `src/r7/integrations.css`. The Tenant IT area's own blue `--action` colours now point at the main colour tokens (`--primary…`), and the blue dark-mode override is gone. No other blue main buttons were found in `src/r7/*.css`.
- **Load-error card:** `src/r7/CardError.tsx` (new). `LoadErrorCard` shows the title, "Vero couldn't reach this setting just now. The rest of the page still works.", a **Try again** button that reloads only that card, and "Ref …".
  - `ActionError` does the same for a change that failed. `FailureNotice` picks between the two. `failureText` is the same message as one line, for screens that show a line of text instead of a card.
  - The reference is the server's `X-Correlation-Id` when the server sent one. `ApiRequestError` now carries it (`src/api.ts`). Otherwise it is a random 8-character code. Either way the raw error goes to the browser console next to the same reference.
- **Where the new error pieces are used:**
  - Business administration: all four tabs (`Governance.tsx`).
  - Tenant IT: Integrations, Storage, Sign-in, Environment (`TenantAdmin.tsx`).
  - Operations: the overview and the live sections, including "Retention status didn't load" and "Older events didn't load" (`Operations.tsx`).
  - Records: the search error (`Workspace.tsx`).
  - Home, Viewer and the session start: these screens also had raw error text, so it was replaced with the plain line (`Home.tsx`, `Viewer.tsx`, `store.tsx`, `Shell.tsx`).
  - The made-up "sim-err-4f10" code was removed from the simulated error state (`ui.tsx`).
- **No-access page:** `Shell.tsx` `AccessBoundary`. It reads "This page isn't part of your role", "Your role is {role}…", and has the buttons "Go to {first area}" and **Sign out**.
  - Sign out ends the sign-in session. In local mode, it clears the test persona.
  - The "enforced by the server" sentence is gone.
- **A hidden problem fixed along the way:** the Records page was mounted, hidden, for every role. For an admin without search rights it called the search API on every load. That got a 403 and wrote an AUTHORIZATION_DENIED audit event each time. Records is now mounted only for roles with `SEARCH_RECORDS`.

**Part B: Business administration** (`Governance.tsx`, tab names in `store.tsx`)
- **Tabs:** People & roles, Roles & permissions, Keeping documents, Search fields. The old addresses (`#/governance/access`, `/roles`, `/retention`, `/metadata`) are unchanged.
- **People & roles:** the four roles, one sentence each, the name in the sign-in system, and "Yes, in Roles & permissions" or "Fixed". Below is the "To give someone a role…" note. Platform operations is not listed; one line says it is the provider's role.
- **Roles & permissions:**
  - The actions are in words, "Permanently redact" is marked **Cannot be undone**, and "Search records and open documents" shows "Always on". No capability codes appear.
  - The footer has the agreed sentence, **Undo changes** and **Save**. It uses the same API as before.
  - Keeping documents also got a "The rule changed while you were editing" notice. The rest of that tab, and of Search fields, is left for 0009.
- **Styles:** `.card-error` and `.roles-foot` in `r7.css`.

**Tests:** `e2e/sdv-screen-tour.spec.ts`
- **No-access page:** the existing checks now expect the new wording and Sign out.
- **Business admin:**
  - the four tab names;
  - People & roles has 4 rows, no Platform operations and no "Claim value";
  - Roles & permissions: switching Print for Business user, saving, then switching it back and saving again restores the original;
  - "Always on" shows, and no codes.
- **Load-error card:** the test makes the server return an error (500) for the role settings. It checks the card, the reference and that no raw error text shows. It then checks that Try again brings the settings back.
- **New test:** a Business user opening `#/governance/roles` gets the no-access page, with "Go to Home" and Sign out.

## Checks

| Command | Result |
|---|---|
| `.\stop-local.cmd`, then `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, all backend tests 0 failures (sdv-web 44), frontend `tsc -b && vite build` built |
| `npm run typecheck` | PASS, 0 errors |
| `.\start-local.cmd`, `npm run ui:capture` | **PASS**: 16 passed, 2 skipped (the opt-in comment and redaction tests) |
| Bundle search (`dist/assets/*.js`) | "Claim value", "sim-err-4f10", "enforced by the server": none. "ApiRequestError": only the class name inside the API code, never shown on screen. "Baseline": only in the old R5 code (`?app=r5`). Codes on screen: only the role names in "Name in your sign-in system", as the task asks |
| Screenshots | 20 files in `.runtime/ui-checks/0008/`: the four Business admin tabs and the no-access page, at 1440 and 390 px, in light and dark |

## Left for later

- The wording of the Operations health messages is left as it is; task 0012 covers it.

## Commits

One commit on `feature/admin-1`. Not pushed or merged. `Start-SDV-Local.ps1` is untouched.

## Questions for the owner

None.
