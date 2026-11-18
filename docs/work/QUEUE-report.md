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
| 7 | 0013 Separate schema step | `feature/install-1` | Done; three points to confirm (below) | `2ec76f9` |
| 8–10 | 0014–0016 | — | Replaced by the shared install work; not started | — |

**Last checks, on `feature/install-1`:**
- `Build-SDV-All.ps1`: BUILD SUCCESS, 0 failures; sdv-web has 101 tests.
- `Test-SDV-CleanInstall.ps1`: PASS in 39 s.
- `start-local.cmd`: works as before.
- `ui:capture`: 16 passed, 2 skipped (the opt-in tests).
  - One run in between had one failure: the Document Steward's search, which 0013 doesn't touch. The rerun passed. See report 0013.

## Open questions

All from report 0013.

1. **A mismatched database:** Vero keeps running but closed (health and system information only) instead of exiting, so that Health can show the message. Keep it that way?
2. **When Vero checks:** only at start-up. After the schema step, Vero has to be started again. Fine, or should it re-check by itself?
3. **Liquibase analytics in local mode:** Liquibase 4.31 has anonymous usage analytics, and the schema step turns them off. Should local-mode startup turn them off too? It is a one-line setting.

**Backlog from earlier reports:**
- **Next cleanup time** (0012).
- **For the shared install work:**
  - deployment mode ("Installed") and the role groups in Sign-in (0012);
  - creating the first customer on a clean database (0013).

## What to test first

1. **Clean install** (`feature/install-1`): run `.\Test-SDV-CleanInstall.ps1`, then read "Update the database" in `docs/operations/SDV_V1_GA_CLEAN_INSTALL_RUNBOOK.md`. This is the newest change and changes how Vero starts outside local mode.
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
