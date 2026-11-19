# SDV (Vero): working instructions for Claude Code

These instructions apply to every session in this repository. The owner (Kr) makes the business decisions and approves every merge. Claude writes the code.

## What this repository is

Secure Document Vault (product name currently **Vero**, may change) stores redacted documents from producer systems and lets authorized people search, view, and, for stewards, permanently correct them. Java backend under `backend/`; React, TypeScript and Vite frontend under `frontend/sdv-ui/`. The current UI is the R7 design in `frontend/sdv-ui/src/r7/`; the older R5 app is still reachable at `?app=r5`.

Related repositories on this machine:

| Repository | Location | Branch |
|---|---|---|
| SDV (this one) | `C:\dev\sdv\secure-document-vault` | `release/vero-r7` |
| LexaVault | `C:\dev\lexavault\secure-document-platform` | `feature/lexavault-r1b-tenant-identity` |
| Enterprise Identity (shared library) | `C:\dev\shared\enterprise-identity-parent` | `main` |

The agreed UI changes are in the Lexavault project document `SDV-UI-Review-Findings.md`; each one reaches this repository as a task in `docs/work/`.

## Rules

**Proof before commit**
- Never commit code that has not been built and tested. Report the exact commands run and what passed or failed.
- If something fails, show the failure and stop. Do not change tests to make them pass without saying so.
- Add or adjust tests with each change. Anything unclear must be refused, not guessed (fail closed).

**Branches**
- One branch per piece of work, taken from `release/vero-r7`.
- Do not commit to `main`. Do not push, merge or rewrite history unless the owner says so in the session.

**Secrets**
- No secrets in the repository, in logs or in the chat.
- Never open, print or copy anything under `C:\dev\sdv\secrets`.
- Never print database passwords or tokens. Refer to where they are stored.

**Local services.** These Docker containers hold real development data: `sdv-postgres`, `sdv-vault`, `securedoc-postgres`, `lexa-keycloak`, `lexa-keycloak-db`.
- Do not remove or recreate them, and do not delete their volumes. Do not run `docker compose down` against them.
- Never initialize, reset or reseal the Vault.

**Files**
- `Start-SDV-Local.ps1` and `Stop-SDV-Local.ps1` have uncommitted local changes (seen 2026-11-17). Leave them as they are unless a task says otherwise.
- Do not delete untracked runtime folders such as `.runtime`.

**Ask before**
- Any database schema change.
- Adding or upgrading a dependency.
- Deleting files.
- Any backend change in a task that is described as UI only.

**How to report.** Short, plain answers: what changed, what was run, what passed, what is next.

## Work routine

Tasks and reports live in `docs/work/`. When the owner says "do the next SDV task", follow `docs/work/README.md`.

## Environment (Windows)

| Task | Command (from the repository root) |
|---|---|
| Full build with tests | `.\Build-SDV-All.ps1` |
| Start locally | `.\start-local.cmd` (or `.\Start-SDV-Local.ps1`) |
| Stop locally | `.\stop-local.cmd` |
| Frontend type check | `cd frontend/sdv-ui; npm run typecheck` |
| Frontend build | `cd frontend/sdv-ui; npm run build` |
| UI screen tour (app must be running) | `cd frontend/sdv-ui; npm run ui:capture` |

The UI is at http://localhost:5183 and the backend at http://localhost:8280.
