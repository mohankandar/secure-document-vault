# Report 0017: Records picks the right sending system

Date: 2026-11-18
Branch: `fix/records-source-choice`, taken from `feature/install-1`
Outcome: PARTLY DONE. The cause is found and fixed, in the frontend. Three parts of the default rule need data the server doesn't send to Records: one question for the owner. The leftover "Tour test system 20261118181639" source is kept.

## The cause

**It was not the leftover source.** Records was searching LexaVault every time. The server lists sending systems by name, so LexaVault always came first, and the leftover source was never chosen.

**It was a race between loading the sending systems and pressing Enter:**
- When the search box was submitted before the search schema had arrived, Records had no fields to route the text to. It refused with "This source has no field the search box can use. Use Filters."
- It never tried again once LexaVault's fields arrived. The failure screenshot shows exactly that: the message, with LexaVault's label "Contract Number or Last Name" already in the box.
- The tour typed straight after a page reload (in the viewer step), so it failed whenever the schema was slower than the typing.

**Proof:** a throwaway Playwright check against the running app (in the git-ignored `test-results/race-0017/`, not committed).
- **Before the fix:** reload, type, Enter: the message, 0 rows, with no added delay and with a 1.5 s schema delay.
- **Waiting for the schema first:** the record was found 3 times out of 3.
- **After the fix:** the record was found in both cases, with no message.

## What changed (frontend only)

**`src/r7/sourceChoice.ts` (new): one rule for Records and Home**
- A sending system can be searched, offered or be the default only if it has at least one searchable field.
- The default is the person's last choice in this browser (`localStorage`, in try/catch). Otherwise it is the first by name, in the server's own order (name, then key), so the same data gives the same default every time.
- The last choice is saved when the person applies Filters with a system chosen.

**Records (`Workspace.tsx`)**
- Enter before the systems have loaded is kept and run once they are known, as Home already did.
- The Filters "Source" picker offers only searchable systems. A system with no searchable field is never offered.
- A record opened from Home's lists is still searched in its own system, even if that system can't be the default.
- **When no system can be searched:**
  - Records shows "There's nothing to search yet. Records appear here once a sending system delivers documents.";
  - the box says "Nothing to search yet";
  - Filters is disabled.

**Home (`Home.tsx`)**
- The search box uses the same default.
- Needs attention searches only the systems the rule allows.
- With nothing to search, it shows the same sentence and disables Filters.

**Tour (`e2e/sdv-screen-tour.spec.ts`)**
- **Every test chooses LexaVault explicitly first,** the way a person does: it stores LexaVault as the last choice, in `beforeEach`.
- **New test: "Records starts with LexaVault while a half-set-up system exists"** (desktop):
  - It first checks that the test case exists: a "Tour test system …" source with no search fields.
  - Then come ten reloads in a row. Odd loads have no remembered choice; even loads have the half-set-up system remembered, which must be ignored.
  - Each load types the contract number straight after the reload. It checks that LexaVault's record is found, that the "no field" message never shows, and that Filters doesn't offer the tour source.
  - Then, simulated in the browser, only systems that can't be searched remain: Records and Home show the sentence, and Filters is disabled. Screenshot `records-nothing-to-search`.

## Not done: needs server data (question below)

Rule 2 and 3 also ask for three things the search schema (`GET /api/v1/business/search-schema`) doesn't send:
- **"At least one stored record":** there is no record count per system.
- **"The one with the most recent delivery":** there is no last-delivery time.
- **"Paused systems … never become the default if an active one qualifies":** there is no status. (The frontend's `toSource` has always filled in `ACTIVE`.)

The schema sends only id, key, name and searchable fields. Business users can't read Tenant IT's source list. Working these out in the browser would take extra searches for every system on every load, and each would be audited as a search the person never made. So I didn't guess. The task allows the frontend only, so I stopped short of the server.

## Commands run and results

| Command | Result |
|---|---|
| `npm run typecheck`, `npm run build` | PASS |
| New tour test alone (desktop) | PASS (10.3 s) |
| `npm run ui:capture`, runs 1–3 | 1: 17 passed, 3 skipped. 2: **2 failed**, 15 passed, 3 skipped. 3: 17 passed, 3 skipped |
| `npm run ui:capture`, runs 4–6 in a row | **4: 17 passed, 3 skipped. 5: 17 passed, 3 skipped. 6: 17 passed, 3 skipped.** Check 2 met |

**Run 2's failures:**
- **Tenant IT:** `page.goto: net::ERR_NETWORK_CHANGED`, a network change on this machine. LexaVault's own stack was started on this machine during the session, outside this work.
- **Business User, desktop:** the "Last updated, last 7 days" filter step found no rows within 15 s. The data has 11 matching records, checked through the API. The same test passed on iPad in that run and in every other run. Its screenshot was overwritten by run 3, so runs 4–6 kept their own results folders. That failure didn't come back, so its cause is still unknown. If it returns, its screenshot will be kept.

No backend change, so no full build was needed. No new dependency.

## Files changed

- **New:** `frontend/sdv-ui/src/r7/sourceChoice.ts`
- **Changed:** `frontend/sdv-ui/src/r7/Workspace.tsx`, `Home.tsx`, `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`

## Commits

One commit on `fix/records-source-choice` (hash in the commit log). Not pushed or merged.

## Questions for the owner

1. **May the search schema carry three more facts per sending system: status, record count and last delivery time?**
   - This is a small read-only server change (no schema change), so the full rule can be built: records and newest delivery first, and paused systems never the default while an active one qualifies.
   - Until then, the default is the last choice, else the first searchable system by name.

## Suggested next step

Answer question 1. If yes, a short follow-up task adds the three facts and finishes the rule.
