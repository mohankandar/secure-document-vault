# Queue report: tasks 0006 follow-up to 0013

Date: 2026-11-18. Written for the owner, after the last task in `QUEUE.md`.
Every branch is local only: nothing was pushed or merged. Each branch is taken from the one above it, so `feature/install-1` holds the whole series.

## Outcomes

| # | Task | Branch | Outcome | Commits |
|---|---|---|---|---|
| 0 | 0006 follow-up: end-to-end redaction test opt-in | `feature/ui-viewer` | Done | `bac0e3b` |
| 1 | 0007 Page-view audit | `feature/page-view-audit` | Done; the Audit wording question was answered with option (a) | `343934e`, `289f37d` |
| 2 | 0008 Admin shared pieces, People & roles, Roles & permissions | `feature/admin-1` | Done | `1c63022` |
| 3 | 0009 Keeping documents, Search fields, Record title (migration 019) | `feature/admin-2` | Done | `14c86ee` |
| 4 | 0010 Tenant IT: Integrations and Storage | `feature/admin-3` | Done | `25bfb4a` |
| 5 | 0011 Problems and Audit in plain words (migration 020) | `feature/admin-4` | Done; the CSV export question was answered with option (a), then built | `e73de74`, `3693884` |
| 6 | 0012 Overview, Storage, Health, About; Sign-in read-only | `feature/admin-5` | Done; the two unused files were deleted as approved, and the four missing facts stay out | `8ecdb8d`, `b8c43ed` |
| 7 | 0013 Separate schema step | `feature/install-1` | Done and approved; the owner's three answers are built (below) | `2ec76f9` and the follow-up commit after it |
| 8–10 | 0014–0016 | — | Replaced by the shared install work; not started | — |

**Last checks, on `feature/install-1`** (after the 0013 follow-up):
- `Build-SDV-All.ps1`: BUILD SUCCESS, 0 failures; sdv-web has 108 tests.
- `Test-SDV-CleanInstall.ps1`: PASS in 43 s, including "not ready" and no outside connection.
- `start-local.cmd`: works as before.
- `ui:capture`: 16 passed, 2 skipped (the opt-in tests).
  - Two runs had failures in the Business User's and the Document Steward's record search, which 0013 doesn't touch. The reruns passed. See "Known issue" below.

## 0013: the owner's answers (2026-11-18)

1. **A mismatched database: keep Vero up but closed, and report "not ready".** Built.
   - `/actuator/health/readiness` and `/actuator/health` answer 503 `OUT_OF_SERVICE`.
   - `/actuator/health/liveness` stays 200 `UP`.
2. **Restart after the schema step; no self re-check.** Kept as built.
3. **Liquibase analytics off in every mode.** Built: the running app, local mode and the schema step.
   - **Nothing else in the app sends usage data out.** Checked in the bundled libraries, the backend and shared library code, the frontend and its bundle, and by watching Vero's network connections: none to outside this computer.
   - The only outside calls are to services the customer configures: the database, the key service and the sign-in server.
   - Details are in report 0013.

## Open questions

None.

## Known issue (not fixed)

The tour's record search sometimes lands on a source with no search fields. A tour run at 13:16 today left a paused source, "Tour test system 20261118181639", with no fields. Suggested:
- remove that source (development data, so your call);
- make the tour pick LexaVault explicitly before searching.

**Backlog from earlier reports:**
- **Next cleanup time** (0012).
- **For the shared install work:**
  - deployment mode ("Installed") and the role groups in Sign-in (0012);
  - creating the first customer on a clean database (0013).

## What to test first

1. **Clean install** (`feature/install-1`): run `.\Test-SDV-CleanInstall.ps1`, then read "Update the database" in `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`. This is the newest change and changes how Vero starts outside local mode. Point a load balancer at `/actuator/health/readiness`, and a restart policy at `/actuator/health/liveness`.
2. **Local start still works:** `.\start-local.cmd`, then sign in as each role. Local mode still migrates and seeds at startup.
3. **Platform operations › Overview, Health, About, and Tenant IT › Sign-in (0012):**
   - the sentences;
   - "Check now";
   - Sign-in is read-only.
4. **Problems and Audit (0011):**
   - the audit sentences and the record lookup;
   - the CSV export: the row limit, and that the export itself shows in Audit.
5. **Admin screens (0008–0010):**
   - People & roles, Roles & permissions;
   - Keeping documents, Search fields, Record title;
   - Integrations and Storage with their three-step editors.
6. **Viewer page events (0007):** one "Loaded page" per page per viewing.
