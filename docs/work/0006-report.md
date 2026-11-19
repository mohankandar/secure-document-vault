# Report 0006: Viewer

Date: 2026-11-18
Branch: `feature/ui-viewer` (from `feature/ui-home`)
Outcome: DONE

## What was done

**Part A: viewer screen** (`src/r7/Viewer.tsx`, `r7.css`, `Workspace.tsx`, `liveApi.ts`, `src/api.ts`)
1. **Actions with words** in the header: Find, Download, Print, Redact. Redact uses the danger colour, and is solid red while redaction mode is on.
   - Below 640 px, Find, Redact and the back button keep only their icon; their accessible names are unchanged.
   - On narrower screens Download and Print are in the Document information drawer, as before.
2. **Actions a role may not use are hidden**: in the header, in the drawer, and in Comments and Tags. People without the add capability see only the list, with no form and no "isn't assigned to your role" message. The old greyed-out `ActionButton` (lock icon, "not permitted" tooltip) is removed.
3. **Refusal message.** The client-side "deny" path is gone; every action goes to the server. On a 403, Download, Print, Redact, comments and tags all show **"You can't do this"** with "Your role doesn't allow it. Ask your Business admin if you need it." No codes or "In production…" text remain.
   - To detect this for binary responses, `contentBinary` in `src/api.ts` now throws `ApiRequestError` with the HTTP status, as the JSON calls already did.
4. **Title:**
   - Line 1: document kind · record title, e.g. "Loan file · Lena". The kind comes from the file name ("loan-file.pdf" → "Loan file"). The record title is the same as the record panel's (Records passes it in), so with the demo data's separate First Name and Last Name it is the first name.
   - Line 2: "300000000092 · loan-file.pdf · 1 of 1 document".
   - The back button reads **Results**; its accessible name is "Back to results".
   - A "Home" label is supported (`backLabel`), but today the viewer always opens from Records: Home leads to a record panel in Records first.
5. **Left column folds away** with a button at its top ("Hide document list"). The page area takes the space, and a slim strip keeps "Show document list". The choice is stored in `localStorage` (`sdv.r7.viewer.docsFolded`, wrapped in try/catch, default open). Phones keep the drawer.
6. **Real page previews** in the Pages list: two per row, page number below.
   - Loaded in chunks of 12, and only when a thumbnail is on screen, so only while the column or the phone drawer is open.
   - While loading, a plain page-shaped box. If previews fail, the boxes show "Page 3", with no error banner.
   - Previews reload after a redaction.
7. **Information panel:** Details, Comments (with count), Tags, Activity.
   - Comments are now loaded when a document opens, so the count shows at once. Reading comments writes no audit event.
   - In development builds there is an **Ask** tab first, with a dashed "Ask and Key terms · Later release" marker. The product build has neither, and the panel keeps its 360 px width (checked).
8. **No prototype wording** in a connected session. Checked in the product build ("simulated" and "Sample content" absent). The remaining "simulated" texts in `Viewer.tsx` are only in the `!liveSession` branches listed in report 0001.

**Part B: page previews** (approved backend addition)
- `GET /api/v1/business/documents/{documentId}/viewer/previews?first=1&count=12` (`SecurePdfViewerController`, `DocumentViewerService.previews`).
  - **Same checks as the viewer:** `VIEW_PDF`; the document must be in the caller's tenant and `AVAILABLE`; the same verified plaintext (storage reference, crypto envelope, encrypted size and SHA-256, plaintext size, PDF header and SHA-256).
  - Decrypts the document **once** per request.
  - Renders pages `first` to `first+count-1`, clipped to the page count, at a fixed **120 px width** (tall pages capped at 400 px high), as PNG.
  - `count` must be 1–24 and `first` at least 1 and within the page count; otherwise 400.
  - Built from the stored, current PDF, so redactions show.
  - Same no-store and security headers as page images.
  - **No audit event.**
- **Response shape:** JSON `{ documentId, pageCount, first, previews: [ { pageNumber, width, height, dataUrl } ] }`, where `dataUrl` is `data:image/png;base64,…`. One request gives the browser 12 images without 12 more requests.
- **Tests (7 new):**
  - `DocumentViewerServicePreviewsTest` (5):
    - small PNG previews for the requested pages, decrypted once, **no audit interaction**;
    - another tenant's document refused;
    - a non-`AVAILABLE` document refused;
    - `count` 25, `first` 0 and `first` past the end refused (24 accepted);
    - a document redacted with the real `PdfSanitizingRedactor` gives a black preview over the redacted area and keeps its content elsewhere.
  - `SecurePdfViewerControllerPreviewsTest` (2):
    - a role without `VIEW_PDF` is refused before anything is decrypted;
    - the response carries the no-store headers.
- **Timing.** Every demo document was the same 2-page fixture, so I added one **14-page demo document** (contract 300000000092, "Lena Patel", `loan-file.pdf`, 6.6 KB) through the ingestion API. Warm averages over 4 requests:

  | Request | Time | Response |
  |---|---|---|
  | 12 previews (pages 1–12) | **~109 ms** | ~125 KB JSON |
  | 24 previews (all 14 pages) | ~97 ms | ~146 KB |
  | One full page render (scale 1.0) | ~69 ms | ~189 KB PNG |
  | Opening the document (metadata) | ~25 ms | |

  **Memory:** not noticeably higher than one page render. Both hold the same decrypted PDF once. Each preview image is about 120×155×3 bytes (≈56 KB), against about 816×1056×3 bytes (≈2.6 MB) for one page at scale 1. I did not measure the heap separately.

## Commands run and results

| Command | Result |
|---|---|
| `mvn test` (new tests) | 5/5 service, 2/2 controller |
| `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, **73 tests**, 0 failures; frontend built |
| `npm run typecheck`, `npm run build` (after the last change) | PASS |
| Live: `count=25` | HTTP 400 |
| `npm run ui:capture` | **PASS: 15 passed, 1 skipped** (the redaction test is desktop-only by design); then 14/14 again without the redaction test, so the demo document is not redacted a third time |
| Product build (LOCAL sign-in, preview): viewer tabs | Details, Comments, Tags, Activity; no Ask, no Key terms, no "Later release"; panel 360 px; no prototype wording |

New tour checks:
- **Business user:** no Download, Print or Redact anywhere (header and drawer); no comment or tag form and no "isn't assigned" text; existing comments and tags readable. Desktop adds one demo tag "e2e-demo" through the API as the steward if the document has none.
- **Document steward:** Find, Download, Print and Redact shown with words; comment and tag forms present. A served 403 on Download shows "You can't do this" with the new detail, and none of "In production", "DENIED" or the capability code.
- **Desktop:** page previews are decoded images (`naturalWidth > 0`). Folding the column and reloading keeps it folded; it is unfolded again afterwards.
- **"Document Steward redaction end to end"** (desktop only): marks an area on page 1 of demo record **100000000003**, checks the new warning text, enters the reason, confirms and applies; checks "Permanent corrective redaction completed" and that the page reloads. **Each run permanently redacts a small area of that demo document**; it ran twice in this session. Contract override: `SDV_REDACTION_DEMO_CONTRACT`.

**Audit check (check 3), the 14-page document opened in the viewer:**

| Build | Events written |
|---|---|
| Development (`npm run dev`, steward) | `DOCUMENT_VIEW_OPENED` **×2** (same millisecond), `DOCUMENT_PAGE_VIEWED` page 1 and page 2 (scale 0.93); **no event for the 12 previews** |
| Product build (business user) | `DOCUMENT_VIEW_OPENED` **×1**, `DOCUMENT_PAGE_VIEWED` page 1 and page 2; **no event for previews** |
| Product build, then **two Zoom in clicks** | **4 more** `DOCUMENT_PAGE_VIEWED` (pages 1 and 2 at scale 1.25, then at 1.50) |

What this shows:
- The double `DOCUMENT_VIEW_OPENED` in development comes from React StrictMode, which runs effects twice in development only; production writes one.
- Page 2 is logged although only page 1 filled the screen: the main view fetches pages within 900 px of the visible area ahead of time.
- Every zoom step re-renders, and so re-logs, every page already loaded: 2 events per click here, more on long documents.
- As the task says, I did not change how the main view is audited.

Screenshots: `.runtime/ui-checks/0006/`:
- `<business|steward>-<1440|390>-<light|dark>-<open|folded>.png` (at 390 px: `drawer-open` and `drawer-closed`), 16 files, on the 14-page document;
- `product-build-viewer-1440.png`.

The tour's captures (`viewer-read-only`, `viewer-refused`, `viewer-folded`, `redaction-review`, `redaction-applied`) are in `frontend/sdv-ui/test-results/`.

## Files changed

- Backend: `DocumentViewerService.java` (`previews`, `PagePreview`, `PagePreviews`), `SecurePdfViewerController.java` (`/previews`); tests `DocumentViewerServicePreviewsTest.java`, `SecurePdfViewerControllerPreviewsTest.java` (new, in `sdv-web`'s test tree for Mockito)
- Frontend: `src/r7/Viewer.tsx`, `src/r7/Workspace.tsx`, `src/r7/liveApi.ts`, `src/r7/r7.css`, `src/api.ts` (`contentBinary` error status)
- e2e: `e2e/sdv-screen-tour.spec.ts`
- Docs: `docs/work/0006-task.md`, `docs/work/0006-report.md`

`Start-SDV-Local.ps1` (the owner's local change) and `docs/design/vero-admin-after.html` are left untouched and uncommitted.

## Commits

One commit on `feature/ui-viewer`: "feat(viewer): worded actions hidden by role, page previews, foldable document list (task 0006)". Not pushed or merged.

## Questions for the owner

1. **Page-viewed audit volume:**
   - pages fetched ahead (within 900 px) are logged as viewed;
   - each zoom step logs every loaded page again.

   Leave as is, or give the main view a separate task (e.g. log a page once per viewer session, or only when it is actually on screen)?
2. **Redaction in the e2e tour** permanently changes demo record 100000000003 on every desktop run. Keep it in `ui:capture`, or make it opt-in like the comment test (`SDV_REDACTION_REGRESSION=1`)?
3. **Demo data added in this task:** contract 300000000092 (one 14-page document) for the preview timing, and a demo tag "e2e-demo" on 100000000001's open document. Keep them?

## Follow-up (2026-11-18, from QUEUE.md)

1. **The redaction test is opt-in**, like the comment test. It runs only with `SDV_REDACTION_REGRESSION=1` (and only on desktop). A normal `npm run ui:capture` gave 14 passed and 2 skipped (the redaction test in both projects), and the database had **no** redaction audit event from that run, so demo record 100000000003 is no longer changed.
2. **The 14-page demo document (300000000092) and the "e2e-demo" tag stay.** They load only with the local profile, because nothing loads them at all: no seed and no changeset creates them.
   - Both exist only as rows in this machine's development database.
   - Both were created through the local test sign-in (`X-SDV-*` headers): the document by my ingestion call, the tag by the e2e tour's `ensureDemoTag`.
   - That sign-in exists only with the local profile: `LocalHeaderAuthenticationFilter` is `@Profile("local")` and needs `sdv.auth.mode=local-header`, and `SecurityConfiguration` refuses `local-header` without the local profile. So no other installation can receive them, and the tour cannot add the tag elsewhere.
   - Note: the 14-page PDF is not in the repository, so a fresh development machine does not get this document from `Seed-SDV-Demo-Records.ps1`.
3. Page-view auditing is left for task 0007.

Commit: "test(e2e): make the end-to-end redaction test opt-in (task 0006 follow-up)" on `feature/ui-viewer`.
