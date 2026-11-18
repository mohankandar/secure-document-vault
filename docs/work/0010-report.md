# Report 0010: Tenant IT: Integrations and Storage

Branch `feature/admin-3`, taken from `feature/admin-2`. Part B.3's check endpoint was approved with the task. There is no other backend change, no schema change, no new dependency and no permission change.

## What changed

**Tabs** (`store.tsx`): "Authentication" is now **Sign-in**; its content and address (`#/tenant/authentication`) are unchanged. The order stays Storage, Sign-in, Integrations, Environment until 0011 and 0012 add Problems and About.

**Part A: Integrations** (`src/r7/Integrations.tsx`, new)
- **Status lines:** each sending system shows one, from the source summary endpoint. They read "Working · last delivery 2 h ago", "Paused since 18 Nov 2026" or "Waiting for the first delivery". The pause date is the system's last update time, which is when its status was last changed.
- **Detail:** "Sends documents to Vero through the API. 8 deliveries, 13 documents. No problems so far." It says "Last problem: {date}." when there is one. Buttons **Edit** and **Pause receiving** / **Start receiving**.
  - Pause asks first, with the agreed sentence. The status change goes through the existing update, which is audited as before.
- **What it sends:** Field, Kind of value in words (for example "Text, digits only"), Required or Optional, and Used in search (for example "Yes · exact match"). Used in search comes from the business settings the Tenant IT field list already returns. The "Not exposed by API" columns are gone.
- **Limits per delivery:** Largest PDF, Most pages in one PDF, Most documents, Wait for missing documents.
  - **All four show "Vero's standard limit" for LexaVault,** because the server reports no number for any of them.
  - The total size of one delivery is under Technical details.
- **How it proves who it is:** one sentence for each sign-in type, plus "Vero stores only where the secret is kept, never the secret." The sign-in system's name, such as "(Okta)", is added when one is active; none is active locally.
- **Technical details** (folded): source key, credential reference, sign-in type code, field keys with versions and their type codes.
- **Monitoring:** the sub-tab is gone.

**Part A2: Add or edit a sending system** (three steps, `SourceEditor` in `Integrations.tsx`)
1. **About it:** name, and the source key when adding: "It can't change later." A key already used by another system is refused.
2. **What it sends:**
   - Add, edit and remove fields, each with a label, the key it sends, a kind of value and Required. It shows the agreed "Changing a field later…" sentence.
   - New fields can be removed. **A field that already exists can't be removed:** the server has no way to remove one, so the panel says so.
   - **No identifier column:** Tenant IT's field API can't mark which field identifies a record. Only the Business admin's can, so a note points there. Permissions were not changed.
3. **How it signs in:** three cards, then "Where the secret is kept", never the secret itself.
   - "Advanced (usually leave as is)" is folded and holds the five limits. An empty box means Vero's standard limit.
- **Buttons:** Back, Next, and on the last step **Save**, plus **Save as paused** when adding.
- **Saving a new system:**
  - It is created, then paused (if chosen) and given its limits, before any field is added.
  - Create has no status field, so for a moment the new system is active. It has no fields yet at that point.
  - It then shows "Waiting for the first delivery", or "Paused since…".

**Part B: Storage** (`src/r7/Storage.tsx`, new)
- **Roles in words:**
  - "In use, new documents go here" is the volume Vero would choose now: the usable ACTIVE volume with the lowest priority number.
  - "Standby, used when {in-use} fills up" is any other usable ACTIVE volume.
  - Other states read "Read only…", "Offline", "Being emptied…" and "Full or unreachable…".
  - Priority and state codes are under Technical details.
- **How full:** a meter, "10% full · 1.6 TB free", and "Warns at 80% · stops at 95%".
- **Banner:** the agreed wording, with and without a standby ("At 95% Vero stops accepting new documents."). There is also wording for a full volume and for one that can't be read.
- **Add a volume in three steps:**
  1. Name and folder: "The folder cannot change later."
  2. Vero checks it can write there: the result and free space, plus Check again. Next stays off unless the folder is writable.
  3. In use or Standby.
  - The "I have verified…" checkbox is gone.
  - In use takes a priority one below the lowest ACTIVE volume; Standby takes one above the highest. If a volume already has priority 0, In use is not offered, and the panel explains why.
  - New volumes get the tenant's standard warning and stop levels.
- **Edit:** name, "Takes new documents" (yes, read only or offline) and the warning and stop levels. The folder is read-only, and priority is under Technical details. The edit panel's checkbox was also replaced by a warning note.

**Check endpoint (added): `POST /api/v1/admin/storage-volumes/check`** with `{ "rootPath": "…" }`
- **Permission:** the same as adding a volume (`CONFIGURE_STORAGE_KEY_PROVIDER`), in `StorageAdminController`.
- **Overlap:** it first applies the same rule as create. A folder inside another volume's folder, or containing one, is reported and nothing is written (`StorageVolumeAdminService.checkFolder`).
- **Write test:** `LocalFilesystemVolumeProbe.check` writes and removes a small test file.
  - It creates any missing folders and removes them again.
  - An unusable path is reported plainly, for example "Part of this path is a file, not a folder." or "Vero cannot create or write files in this folder.".
  - It returns writable, free and total space.
- **Port:** `StorageVolumeProbe.check` was added to the port with a default that refuses, so existing fakes are unchanged.
- **Audit:** `STORAGE_FOLDER_CHECKED`, SUCCESS or FAILED, with `{"writable":…}`. The target is a SHA-256 of the folder, never the path itself.

**Other files**
- `TenantAdmin.tsx` now uses the two new pages. The old Integrations and Storage code was removed from it, and the introductions were updated.
- `liveApi.ts`: `checkStorageFolder`, optional thresholds on create, and the business fields on `ItField`.
- Styles are in `r7.css`.
- **`ConnectedIntegrationsView.tsx` is no longer used.** I left it in place, because deleting files needs your approval.

**Tests**
- **Backend: `StorageFolderCheckTest` (5):**
  - refused for Business user, Steward, Business admin and Platform operations, with no probe call, no audit and no folder;
  - a new nested folder is writable, with free space, and nothing is left behind;
  - an existing folder keeps its contents;
  - a path through a file is reported plainly, with nothing created and a FAILED audit;
  - an overlapping folder is refused without writing.
- **Tour** (Tenant IT test):
  - **Integrations:** status lines, detail words, no codes on screen, four "Vero's standard limit". Pause asks, shows "Paused since…", and receiving starts again; a `finally` step makes sure LexaVault is receiving. Then the three steps with two fields.
  - **Storage:** "In use…" and the threshold wording.
  - **Banner:** both wordings. The server's answer is changed in the browser only.
  - **Add steps:** a path through a file under `.runtime` is refused. A new folder under `.runtime/ui-checks/0010` shows free space, and the test checks the folder does not exist afterwards. Then both role choices.
- **Saving the new system and volume only runs with `SDV_ADMIN_CREATE_REGRESSION=1`.** They add rows that can't be deleted, so I followed the redaction test's opt-in pattern from 0006.
  - Run with the flag, the test saves the system as paused and checks its two fields.
  - It then adds the volume as Standby and sets it to Offline, so it never receives documents.
- The 0009 Search fields test now picks LexaVault by name, so a second sending system can't confuse it.

## Checks

| Command | Result |
|---|---|
| `.\stop-local.cmd`, then `.\Build-SDV-All.ps1` | **PASS**: BUILD SUCCESS, all backend tests 0 failures, frontend built |
| `npm run typecheck`, `npm run build` | PASS |
| `npm run ui:capture` (after `.\start-local.cmd`) | **PASS**: 16 passed, 2 skipped (the opt-in comment and redaction tests) |
| Tenant IT test with `SDV_ADMIN_CREATE_REGRESSION=1`, desktop, once | **PASS** |
| After the runs | LexaVault receiving. 0009 settings as before (order, no title, Keep forever) |
| Screenshots | 32 files in `.runtime/ui-checks/0010/`: Integrations, each add step, Storage and each add step, at 1440 and 390 px, in light and dark |

The tour test failed twice while I wrote it, and both failures were in the test, not the app. One field locator matched two fields; the other missed because the Folder field's name includes its hint text. Both are fixed.

## Left in the development database by the one opt-in run

- **Sending system** "Tour test system 20261118181639" (key `tour-20261118181639`): paused, signed-token sign-in, two fields. It also shows in Business admin's Search fields picker.
- **Volume** "tour-volume": **Offline**, folder `.runtime\ui-checks\0010\volume-20261118181642`.

Neither can be removed through the app.

## Notes for the owner

- **Storage tab dot:** the "needs attention" dot on the Storage tab still comes from the older simulated volume data, not the live volumes. I left it for 0012, which covers health wording.

## Commits

One commit on `feature/admin-3`. Not pushed or merged. `Start-SDV-Local.ps1` is untouched.

## Questions for the owner

None.
