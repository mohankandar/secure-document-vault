# Report 0012: Platform operations Overview, Storage, Health, and About this installation

Date: 2026-11-18
Branch: `feature/admin-5`, taken from `feature/admin-4` (which includes the 0011 follow-up, `3693884`)
Outcome: DONE, with one approval asked for (deleting a file that is no longer used) and four gaps noted where the server doesn't report something.

## What was done

**Tabs** (`store.tsx`)
- **Platform operations:** Overview, Problems, Storage, Health, Audit, About.
- **Tenant IT:** Storage, Sign-in, Integrations, Problems, About.
- **Old addresses:** `#/tenant/environment` and `#/operations/configuration` open About.

**Part A: Overview** (`Operations.tsx`, sentences in `opsWords.ts`)
- **Opening sentence:** "1 thing needs attention. 3 documents did not arrive properly." or "Everything is working."
  - The things counted are: problems, each volume in an unexpected state, each service that isn't working, and a failed cleanup.
- **Four tiles,** each opening its tab:
  - **Problems:** count and kinds ("1 could not be stored · 1 stuck · 1 missing documents").
  - **Storage:** the fullest volume that takes documents, "warns at 80%, stops at 95%".
  - **Services:** "All working", or which one isn't.
  - **Cleanup:** "N deleted today" with the last run.
- **Latest activity:** the last 5 audit sentences (0011 patterns), with "All activity →".
  - Routine views are skipped, reading up to 3 pages back.
- **The offline volume (owner, 2026-11-18):** a volume Tenant IT set offline, read only or to be emptied is never counted as needing attention. Only unexpected states count: nearly full, full, can't reach the folder, can't write.
  - The Tenant IT Storage tab's attention mark now uses the same rule on live data. Before, it was computed from prototype fixture volumes.

**Part B: Storage, read-only** (`OpsStorage` in `Operations.tsx`)
- **Columns:** Volume, Role, How full, Can store new documents.
- **Role:** In use, Standby, "Offline (set by Tenant IT)", Read only, Being emptied, or Not usable right now. In use and Standby follow the same rules as Tenant IT's Storage screen.
- **Can store new documents:** "Yes", "Yes, but nearly full", "No: full", "No: can't reach the folder", "No: can't write to the folder", or "No: offline on purpose".
- **What to do:** one sentence per volume in trouble, for example "Ask the customer's Tenant IT to add space to X or add a volume. At 95% Vero switches new documents to Y by itself." The switch sentence is left out when there is no standby volume.
- **Technical details** (folded): folder, warns at and stops at, order, last check, write test.
- No edit buttons.

**Part C: Health**
- "Checked 2 minutes ago" (it updates as time passes) and **Check now**. Refresh is gone.
- A Services table with what each one does. A service that isn't working gets a red notice with the sentence below.
- **Automatic cleanup** in a sentence. A failed run shows in red with "What to do".
- **Technical details** (folded): component codes and messages, the check time, and how long the whole check took. The raw cleanup fields are there too, with formatted times.
- No ISO time stamps anywhere on these pages; the tour checks this.

**Part D: About this installation** (`About.tsx`, shared by both areas)
- **Facts:**
  - Product: "Vero" and the version.
  - Status.
  - Organization: the tenant's display name.
  - Sign-in: Tenant IT sees the connection name and address; Platform operations sees the address; locally it says "Local test sign-in (this computer only)".
  - Checked.
- **Copy details for support:** the facts and the technical details (active profiles, build, correlation ID of the check) as plain text. It has no documents, no person's name and no login ID; the tour checks this.
- **"Installed" is left out:** the server doesn't report a deployment mode.

**Part E: `PLATFORM_CONFIGURATION` removed**
- **Checked first:**
  - **Stored data:** a data-only dump of the development database (22 tables) has no mention of it.
  - **Token mapping:** no code turns a claim into a capability by name.
  - **Frontend:** no code uses it.
  - **Shared library:** `enterprise-identity-parent` has no mention of it.
- **Removed** from `Capability` and `RoleCapabilities`. Updated `FinalRoleMatrixTest` and the exact-capability check in `Test-SDV-BusinessExperience-B.ps1`, a test script that expected it.

**Part F: Sign-in is read-only** (`SignIn.tsx`)
- **Shows:** the provider, address, client ID and when it was last changed. "By whom" comes from the newest successful sign-in switch in the audit, because the connection itself keeps no name.
- **Role groups:** the line says they are set in the installer's sign-in file, because no endpoint returns the mappings.
- **Removed from the screen:** edit, draft, test, activate and rollback. Added: "Sign-in is set during installation by your installer. To change it, contact your Vero support team."
- **Endpoints the screen no longer uses** (left in the backend as asked): `PUT /api/v1/admin/enterprise-identity/draft`, `POST …/draft/test`, `POST …/draft/activate`, `POST …/rollback`. It still reads `GET /api/v1/admin/enterprise-identity`.

**Removed from files (no file deleted):**
- The old prototype code in `Operations.tsx` and `TenantAdmin.tsx`: the old Overview, Intake, Incidents, Audit, Configuration, the editable sign-in and the fixture storage.
- The four identity-change calls in `liveApi.ts`.

## Service sentences (`opsWords.ts`)

| Service | What it does | When it isn't working |
|---|---|---|
| Vero | The application itself | Vero isn't answering. Nobody can search, open documents or change settings, and new deliveries are refused. Check that the Vero service is running and look at its log. |
| Database | Keeps records, settings and the audit | The database isn't answering. Nobody can search or open records, new deliveries are refused, and nothing can be written to the audit. Check that PostgreSQL is running and that Vero can reach it. |
| Key service | Locks and unlocks stored documents (Vault) | Key service isn't answering. Nobody can open documents and new deliveries wait. Check that Vault is running and unsealed. |
| Key service, not set up | | No key service is set up, so documents are stored without Vault's locks. This is expected only on a test computer. |
| Any service, unknown | | Vero couldn't tell whether the … is working. Check now again; if it stays like this, look at Vero's log. |

| Cleanup | Sentence |
|---|---|
| Succeeded | Last ran today at 15:24 and deleted 3 documents that had passed their keep date (or "found nothing that had passed its keep date"). |
| Some couldn't be deleted | …, but N documents couldn't be deleted. What to do: they show under Problems as "still being deleted"; Vero tries them again on the next run. |
| Failed | The last cleanup run failed today at 15:18. Documents past their keep date are not being deleted until a run succeeds. What to do: Vero tries again by itself. If it keeps failing, check that the database is answering and look for RETENTION_SWEEP_FAILED in Vero's log. |
| Not run yet | Automatic cleanup hasn't run since Vero last started. It starts by itself shortly after start-up. |

## Commands run and results

| Command | Result |
|---|---|
| `.\stop-local.cmd`, `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, 0 failures (sdv-web 81, the role matrix test included), frontend built |
| `npm run typecheck`, `npm run build` (rerun after the last edits) | PASS |
| `.\start-local.cmd` | Started |
| `npm run ui:capture` | **PASS**: 16 passed, 2 skipped (the opt-in comment and redaction tests). Rerun after the last edits: same result |
| Bundle search (check 3) | See below |
| Screenshots | 24 files in `.runtime/ui-checks/0012/`: Overview, Storage, Health, About for each role, and Sign-in, at 1440 and 390 px, light and dark |

**Tour additions:**
- **Overview:**
  - With the demo problems: the sentence, four tiles, at most 5 activity sentences, and the offline tour-volume not mentioned.
  - With nothing wrong (simulated in the browser): "Everything is working."
  - The Problems tile opens Problems.
- **Storage:** the columns, "Offline (set by Tenant IT)" and "No: offline on purpose", no edit buttons, and Technical details folded.
- **Health:**
  - The checked time, Check now, no Refresh, and the three services with what they do.
  - The key service stopped (simulated): the exact sentence, "Not answering", and the Overview then says "the key service isn't answering".
  - A failed cleanup shows in red with What to do.
- **About, both roles:**
  - It opens from the old addresses, the old tabs are gone, and the facts are shown.
  - The support note holds no person's name and no login ID.
  - Copy matches the note (checked on desktop).
- **Sign-in:** read-only, with no edit, test, activate, rollback or save buttons and no text boxes, plus the installer sentence.
- **Every page above:** no raw status words or ISO time stamps visible.

**Bundle search** (`dist/assets`, after the build):
- **Counts:**
  - `lastSweepAt`: 0.
  - `DEGRADED`, `"UP"` and `"ACTIVE"`: 4, 7 and 18 times in the R7 bundle, plus 1, 3 and 11 times in the old R5 bundle.
- **On the 0012 pages,** every remaining use is a comparison, never text on screen: `Operations.tsx` lines 95 and 100, `About.tsx` line 45, `opsWords.ts` lines 31, 33, 89 and 119.
- **Other R7 uses:**
  - the shared `toneFor` colour helper;
  - Problems' "partial data" check;
  - the development-only design-system page, which shows a "Degraded" sample badge.

**What failed along the way:**
- Copy matched except for line endings: Windows clipboards use CRLF. The check now normalises them.
- At 390 px the Storage screenshot waited for a table header that is hidden on phones; it now waits for the panel title.
- **Latest activity:**
  - It showed "Nothing yet" when the newest 30 events were all routine audit views. Each look at the audit writes one, and the screenshot runs wrote many. It now reads up to 3 pages of 100.
  - It also showed the "didn't load" message while still loading; loading now has its own state.

## Files changed

- **New:**
  - `frontend/sdv-ui/src/r7/opsWords.ts`
  - `frontend/sdv-ui/src/r7/About.tsx`
  - `frontend/sdv-ui/src/r7/SignIn.tsx`
- **Changed:**
  - `frontend/sdv-ui/src/r7/Operations.tsx` (rewritten)
  - `frontend/sdv-ui/src/r7/TenantAdmin.tsx` (rewritten, small)
  - `frontend/sdv-ui/src/r7/store.tsx`
  - `frontend/sdv-ui/src/r7/liveApi.ts`
  - `frontend/sdv-ui/src/r7/r7.css`
  - `frontend/sdv-ui/src/auth.ts` (`signInSummary`, no secrets)
  - `frontend/sdv-ui/e2e/sdv-screen-tour.spec.ts`
- **Part E:**
  - `backend/sdv-security/.../Capability.java`
  - `backend/sdv-security/.../RoleCapabilities.java`
  - `backend/sdv-security/src/test/.../FinalRoleMatrixTest.java`
  - `Test-SDV-BusinessExperience-B.ps1`
- **Untouched:** `Start-SDV-Local.ps1`.

## Commits

One commit on `feature/admin-5` (hash in the commit log). Not pushed or merged.

## Not done, and why

The server doesn't report these, and the task allows no other backend change, so the screens leave them out:
- **Installed** ("On your own servers" / "Hosted"): there is no deployment-mode field, so the fact is left out.
- **Next cleanup run:**
  - The sweep runs on a fixed delay (default every minute, `sdv.retention.sweep-delay-ms`).
  - The status endpoint gives only the last run, kept in memory since start-up.
  - The tile shows "deleted today", counted from the audit, and the last run.
- **Role groups in Sign-in:** the group-to-role mappings are in the server configuration (`sdv.auth.jwt.role-mappings`), and no endpoint returns them.
- **Response time per service:** the summary has none. Technical details shows how long the whole check took.

## Questions for the owner

1. **May I delete `frontend/sdv-ui/src/r7/ConnectedAuthenticationView.tsx` and its `authentication.css`?**
   - Nothing imports them any more; the editable Sign-in screen is gone.
   - CLAUDE.md asks before deleting files, so they are still there.
2. **Should the four gaps above be added on the server later** (deployment mode, cleanup schedule, role-group mappings, response times)? Each needs a small backend change. Deployment mode and the role groups may belong to the shared install work.

## Owner's answers (2026-11-18)

1. **Deleting the two files: approved.** `ConnectedAuthenticationView.tsx` and `authentication.css` are deleted in a follow-up commit on `feature/admin-5`. Nothing imported them; typecheck and build pass without them.
2. **The four missing facts stay out:**
   - Deployment mode ("Installed") and the role groups in Sign-in come with the shared install work.
   - The next cleanup time goes to the backlog.
   - Response time per service is dropped.

## Suggested next step

Task 0013 (separate schema step) on `feature/install-1`.
