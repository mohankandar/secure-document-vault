# Report 0018: Main screens look like the canvas

Date: 2026-11-18
Branch: `fix/match-canvas-main`, taken from `fix/records-source-choice`
Outcome: DONE as far as the data allows, with **questions for the owner** (below). Three gaps need data the server doesn't send, and three places where the canvas would remove earlier behaviour need a decision. So, as agreed, I stopped here and did not start 0019.

## What changed

**1. Comparison tool: `npm run ui:compare`** (`frontend/sdv-ui/e2e/ui-compare.spec.ts`)
- For each board it screenshots the canvas's app frame at 1440 px, with the blue "Seen by" notes hidden. It puts the app in the same role and state (same record, same panel), screenshots it at the same size, and writes `.runtime/ui-compare/<screen>.png` (canvas left, app right).
- It also writes the same app state in dark mode and at 390 px (`<screen>.app-dark.png`, `<screen>.app-390.png`), because the canvas shows desktop light only.
- **Fonts:** the canvas names no font source, so the tool adds IBM Plex to it. Otherwise the canvas would draw in a fallback font.
- **Window width:** the canvas page squeezes its 1440 px frame in a narrower window (the first runs did), so the tool uses a wider one.

**2. Tokens from the canvas CSS** (`src/r7/r7.css`, top of the file): every screen uses these.

| Token | Value |
|---|---|
| Font | IBM Plex Sans / IBM Plex Mono; line height 1.3 (the canvases set none) |
| Type sizes | page title 26 · panel title 18 · card title 16 · lede 15 · body 14 · small 13 · meta 12 · eyebrow 11 (600, 0.6px, upper case) |
| Ink | `#14191c` · `#4a5459` · `#737d82` |
| Surfaces and lines | page `#f3f5f4` · card `#ffffff` · chip/tint `#eceff0` · line `#dce1e2` · row line `#eceff0` · control border `#c3cacc` |
| Primary | `#166a4f` · hover/text on tint `#105a42` · tint `#e2efe9` |
| Warning | badge `#fdf0d5`/`#8a5300` · callout `#fdf6e8`, border `#f2d9a6`, text `#5a3b00` |
| Danger | `#a3261e` · border `#e8b7b2` · badge `#fbe9e7`/`#9b2c22` |
| Viewer stage | `#e6eaea` |
| Radius | card 12 · button/callout 9 · input 8 · badge/chip pill |
| Size | button 38 (small 32) · input 38 · top bar 56 · Records page padding 28/32 · Home padding 40/120 |
| Shadow | cards none (border only) · pop-ups `0 12px 32px rgba(0,0,0,.14)` · page `0 2px 10px rgba(0,0,0,.1)` |

Dark mode has its own value for each new token.

**3. Screens**
- **Top bar** (`Shell.tsx`, `topbar/*`):
  - "Go to a record or page".
  - The account button and panel take the canvas's spacing.
  - The open panel shows name, email and role (the organization line is gone); the theme switch has no inner dividers.
- **Home** (`Home.tsx`, `r7.css`):
  - Full-width page (40/120); one 52 px search box with Filters and Search inside it; lede "Find a contract number, a borrower or a document." from the system's labels.
  - Recently viewed rows show number, full title, type and time.
  - Needs attention items are warning callouts: "**number · name** Only 2 of 4 documents have arrived. The rest are still coming from Loan Origination System."
  - Ask Vero (development only) uses the canvas sentence.
- **Search results** (`Workspace.tsx`, new `recordColumns.ts`):
  - One bordered bar with chips ("**Updated** last 30 days", and "**Source** …" when there is more than one system), the box, "Filters · 2" and Clear.
  - The count line sits above the card and "Showing 6 of 6 · up to 25 per page" below it. Previous and Next show only when there is more than one page.
  - **Columns** (canvas widths): the number once, the record title (heading = the field the title is made of, e.g. "Borrower", else "Name"), one more field (the first field in the system's order that isn't in the title, e.g. "Loan type"), Documents and Updated.
  - The selected row stays green under the mouse.
- **Record panel:**
  - 400 px wide; heading "RECORD" and the title; the number box; the warning callout; "Documents · 2" with "received 17 Oct".
  - **Details:** label beside value, in the system's field order, without the identifiers, dates as "30 Sep 2026".
  - Then Open documents. Recent activity follows below it (see the questions).
- **Filters:**
  - A 560 px panel; the page behind it is dimmed; canvas spacing and match hints.
  - The Source picker lists only systems that can be searched (task 0017).
- **Viewer** (`Viewer.tsx`):
  - **Header:** canvas spacing; bordered Find, Download and Print; Redact with a light red border; title "Document · record title"; mono subtitle.
  - **Left column:** 250 px; the number box without an extra title line; document cards without icons ("2 pages" for the open one, else "received 18 Nov"); page previews 62 × 80 in a row.
  - **Page:** fits the stage height, so it is about 600 px wide at 1440 × 900.
  - **Right panel:** 380 px with 48 px tabs. The extra footer note is gone.
- **Times and dates** (`ui.tsx`): "12 minutes ago", "2 hours ago", "Yesterday", "Mon 16 Nov", "2 Nov", "17 Oct 2025". Months are fixed English "Sep" (the browser's en-GB says "Sept"). A yyyy-mm-dd value is read as a calendar day, so it doesn't shift a day in time zones west of UTC.
- **Phones (390 px) and dark mode:** checked with the new images. Fixed for Home at 390 px: the search icon shrank, the number column cut names, and the placeholder offset now applies to wider screens only. The selected-row edge now shows from 768 px up.

**4. Demo data (local only)**
- **`Seed-SDV-Canvas-Demo.ps1`** (new, localhost only, safe to run again):
  - Organization "Acme Lending".
  - Sending system "Loan Origination System" with fields Contract number, Borrower, Loan type and Closing date; Record title = Borrower; records 300000000001–300000000008 with the canvas's names and loan types, delivered through the producer API. 300000000003 Marcus Lee has 2 of 4 documents, still expecting Appraisal and Insurance.
  - **LexaVault:**
    - a Loan Type field (also added to its stored field order);
    - Record title First Name + Last Name;
    - names on 100000000001–100000000003 (Jordan Ellis, Priya Shah, Marcus Lee) and a loan type on every delivery.
    - Those three records had no names, and a delivery must carry at least one document. A new delivery would have changed their document counts, so the script adds the values to their existing delivery in the local database, the way a delivery stores them.
  - **Record 100000000001 now shows "Jordan Ellis".**
- **Local test people** (`LocalHeaderAuthenticationFilter`, local profile only): Maya Okafor (Business user), Daniel Reyes (Document steward), Priya Natarajan (Business admin), Jonas Weber (Tenant IT), Aiko Tanaka (Platform operations), Ari Lindqvist (local administrator), each with an `@acmelending.com` email for the account menu. This is backend code, but it is the local demo data the task names. `LocalHeaderTestNamesTest` covers it (+1 test).

**Tour** (`e2e/sdv-screen-tour.spec.ts`): updated for the new names, the organization, the Needs attention sentence, the first name now read from the title column, the Filters count (it includes the Source chip), the audit sentence (Daniel Reyes) and Show all (it follows the record listed first).

## Checks

| Check | Result |
|---|---|
| `npm run typecheck`, `npm run build` | PASS |
| `.\Build-SDV-All.ps1` (a backend file changed) | PASS: BUILD SUCCESS, 0 failures; sdv-web 109 tests |
| `npm run ui:capture` | PASS: 17 passed, 3 skipped (opt-in tests) |
| `npm run ui:compare` | PASS: images for home, results, filters, viewer and account-menu in `.runtime/ui-compare/` |
| Product build | **PASS**, see below |

**Product build:** `dist` has no "Seen by", no "Later release", no "R7 · business integration", no "Ask Vero", no "Development marker", no "Development only", no "Local test role", no "Key terms" and no "Ask about this document". The one remaining hit, "Simulated", is sample text on the design system page, which only development builds can open.

**On the way:**
- **First tour run: 7 failed.** Show all now opened the Loan Origination System, the audit expected the old name, and the field-order restore was refused. That last one was my seed's fault: it added Loan Type without adding it to the stored order. The refusal also stopped the tour putting LexaVault's Record title back. The seed now sets the full order, and rerunning it repaired both settings.
- **After the fixes:** the tour passed twice in a row.

## Remaining differences

Real data (names, times, counts, record order, which document opens first) is allowed to differ and isn't listed.

**Every screen (top bar)**

| Difference | Why it remains |
|---|---|
| "R7 · business integration" chip | Development marker; not in a product build |
| Account subtitle "Acme Lending", canvas "Acme Lending · Mortgage" | **Data gap:** the session has no entity or unit (question 1) |

**Home**

| Difference | Why it remains |
|---|---|
| Placeholder "Contract number or borrower", canvas "…, borrower name, or anything else" | The box searches those two fields only, so "anything else" would not be true. Built from the system's own labels |
| Lede "Find a contract number, a borrower…", canvas "a contract" | Built from the labels ("Contract number") |
| "Show all" in Needs attention | Task 0005 behaviour; not on the canvas (question 4) |
| "…still coming from Loan Origination System", canvas "from the loan system" | The sentence names the real system |
| The canvas draws a faint line under its placeholder | Leftover of reusing its `.ph` class; not copied |

**Search results and record panel**

| Difference | Why it remains |
|---|---|
| "12 pages · received 17 Oct" in the panel's document list: the app shows "received 18 Nov" without pages | **Data gap:** the page count is known only after a document is opened, and opening is audited (question 2) |
| Recent activity below Open documents | Earlier behaviour; not on the canvas (question 4) |
| The Source chip has no × | It can't be removed, only changed in Filters |

**Filters**

| Difference | Why it remains |
|---|---|
| The Received range and the quick-range buttons (Today, Last 7 days…) | Task 0004 behaviour; the canvas shows only Last updated, From and To (question 4) |
| Input hints "12 digits", "Any" | The server doesn't send a field's length or allowed values |
| "Borrower" in Filters, canvas "Borrower name" | One label per field, and the results heading on the canvas says "Borrower" |

**Viewer**

| Difference | Why it remains |
|---|---|
| "2 pages" shows for the open document only; the others show their date | **Data gap**, as for the record panel (question 2) |
| "1 page · redacted" | Same gap: the list doesn't know about redactions |
| The previous and next record arrows in the left column | Task 0006 behaviour |
| The page bar has page arrows and a page number box; the canvas shows "Page 1 of 2" | Task 0006 behaviour |
| Zoom shows 72%, the canvas 84% | The app's 100% is the PDF's real size; the page is drawn at the same size |
| The Ask tab is open on the canvas; the app opens Details | Ask and Key terms are "Later release" and are kept out of the product |

**Account menu**

| Difference | Why it remains |
|---|---|
| Entity list (Mortgage, Auto Finance) | **Data gap:** no entities (question 1) |
| "Development only" section with the test role and the design system | Development marker |
| Sign out | Hidden for the local test sign-in; shown with real sign-in |

## Files

- **New:**
  - `frontend/sdv-ui/e2e/ui-compare.spec.ts`
  - `frontend/sdv-ui/src/r7/recordColumns.ts`
  - `Seed-SDV-Canvas-Demo.ps1`
- **Changed:**
  - `frontend/sdv-ui/src/r7/r7.css`, `Home.tsx`, `Workspace.tsx`, `Viewer.tsx`, `Shell.tsx`, `ui.tsx`, `topbar/AccountMenu.tsx`, `topbar/topbar.css`
  - `frontend/sdv-ui/package.json` (`ui:compare` script)
  - `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
  - `backend/sdv-security/.../LocalHeaderAuthenticationFilter.java`
  - `backend/sdv-web/src/test/.../LocalHeaderTestNamesTest.java`
- **Deleted files:** none.
- **Unused code removed from files:** the `uniqueLabels` helper and the old document-icon and skeleton styles.
- **Untouched:** `Start-SDV-Local.ps1`. No new dependencies; no schema change.

## Commits

One commit on `fix/match-canvas-main` (hash in the commit log). Not pushed or merged.

## Questions for the owner

1. **Entities (Mortgage, Auto Finance):** SDV has no entity or unit, so the account subtitle and the entity list can't be shown. Leave them out until entities exist, or plan the server work?
2. **Page counts and "redacted" in document lists:** may the record's document list carry each document's page count and whether it has been redacted (a small read-only server change)? Today the count is known only after opening, which is audited.
3. **Record title heading:** the results heading is the label of the field the title is made of ("Borrower"), else "Name". Fine, or should the server send a title label?
4. **Earlier behaviour the canvas leaves out:** keep or remove each of these?
   - (a) Show all on Home;
   - (b) Recent activity in the record panel;
   - (c) the Received range and the quick-range buttons in Filters;
   - (d) the viewer's previous and next record arrows and page number box.

   All are kept for now.

## Suggested next step

Answer the questions, then task 0019 (admin screens) on `fix/match-canvas-admin`, using the same tool and tokens.
