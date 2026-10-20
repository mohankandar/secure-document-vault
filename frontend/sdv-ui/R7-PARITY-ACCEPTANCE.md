# Vero R7 functional and visual parity gate

**Scope:** `ui/vero-r7-experience-review` only. Never alter or merge `main` without explicit user approval.

## Source of truth

- Visual and interaction reference: approved R7 prototype at `src/r7/`.
- Behavior/security reference: existing authenticated SDV implementation and API contracts (`src/App.tsx`, `src/BusinessWorkspace.tsx`, `src/PdfViewer.tsx`, `src/api.ts`, `src/auth.ts`).
- `src/r7/entry.tsx` uses simulated prototype data and **must not** become the authenticated production entry.
- `?experience=r7-live` is the partial API-backed engineering integration route until parity passes.
- Main application route `/` must remain the approved existing app until a fully working R7 default is ready.

## Acceptance matrix (not yet certified)

| Area | Mandatory acceptance evidence | Current state |
| --- | --- | --- |
| R7 single shell | R7 topbar, rail, mobile/iPad navigation, real tenant/session/capabilities; no simulated role switching in OIDC | PARTIAL |
| Business Search | Source-driven search fields, date and updated-date filters, pagination, errors and empty state, tenant isolation | PARTIAL |
| Results | Record and submission counts, record selection, secure metadata, incomplete submissions | PARTIAL |
| Viewer | Authenticated PDF pages, find, zoom, document switch, download, print, tags, comments, redaction and all capability denials | PARTIAL: existing secure viewer reused |
| Activity | Record-scoped backend activity, actor/action/date filters, views/read events OFF by default, no synthetic events | PARTIAL |
| Business Administration | Users/access, role permission mutations, retention settings, metadata source/field configuration, RBAC denial | PARTIAL: older content reused |
| Tenant IT | Storage, enterprise identity stage/test/activate/rollback, integrations, environment, IDOR/permission guards | PARTIAL: older content reused |
| Platform Operations | Live overview, intake, health, storage, incidents, audit, configuration, refresh/loading and errors | PARTIAL: older content reused |
| Visual parity | Approved R7 typography/colors/spacing, desktop, iPad portrait/landscape, dark/light, keyboard/accessibility | NOT VERIFIED |
| Release routing | `/` loads only authenticated R7 screens; mock entry inaccessible from normal route; old R5 absent in active experience | NOT DONE |
| Build and security | `npm ci`, `npm run build`, `npm run test:e2e`, local/OIDC smoke, RBAC/IDOR/permanent redaction regression | NOT VERIFIED |

## Hard no-go conditions

1. Do **not** mark R7 complete by wrapping R5 screens in new styling.
2. Do **not** connect production actions to prototype `store.tsx`, `data.ts`, mock `Viewer.tsx`, or simulated audit writes.
3. Do **not** weaken capability gates or bypass `contentApi`, `roleApi`, and authenticated viewer endpoints.
4. Do **not** claim build/runtime security PASS without recorded results.
5. Do **not** flip `/` to R7 until every essential functional/visual item is independently accepted.
6. Do **not** merge changes to `main` without explicit instruction.

## Suggested local build and acceptance

From `frontend/sdv-ui`:

```powershell
npm ci
npm run build
npm run test:e2e
```

Test the integrated UI using `http://localhost:5183/?experience=r7-live`, with backend services running. Perform separate local and OIDC acceptance using real roles and accessible/denied capabilities. R7's default route must be changed only after the parity gate is met.

## Evidence ownership

ChatGPT: architecture, code integration, static audit, test plans, and explicit gaps.
Local user: routine builds and targeted runtime tests.
Kiro: optional credit-conscious independent local review only for high-value checkpoint validation.
