# R7 unified application conversion — engineering status

Branch: `ui/vero-r7-experience-review`

## Routes

- `/` — approved R5 API-backed frontend (unchanged by default)
- `/?prototype=r7` — original R7 mock prototype (NOT connected to SDV)
- `/?experience=r7-live` — R7 visual-token bridge on the **actual** API-backed SDV UI

## Scope and acceptance

The live visual bridge is the first safe conversion commit. It **does not** constitute
a finished R7 screen conversion or functional parity. The original App,
BusinessWorkspace, PdfViewer and auth/API clients remain the source of truth.

Do not point a production route to the mock `r7/store.tsx` or simulate a successful
security action. The fully converted R7 UI must use `api.ts`, `auth.ts`,
server-issued capabilities, tenant-scoped endpoints, and real activity/audit.

## Remaining conversion

1. Move R7 shell/navigation to live state without overwriting auth/session.
2. Convert live Search/Results/Record/Viewer UI and preserve the real PDF and
   document actions (print/download/tags/comments/activity/redaction).
3. Convert Governance and Tenant IT screens, preserving versioned retention,
   metadata and exact-version OIDC activation/rollback.
4. Convert Operations/Intake/Audit only where real endpoints support the UI.
5. Verify build, integration, capability denials, OIDC/local modes and iPad.
6. Promote R7 to default only after review and explicit user acceptance.

The mock R7 design is a *reference*, not production logic. `main` remains unchanged.
