# Report 0062: Security fixes 1

Date: 2026-11-24
Branch: `fix/security-1` (from `release/vero-r7` at `6ad37b6`)
Outcome: DONE. No schema change, no new dependency.

## What was done

1. **Machine tokens.** A bound client's token counts as a sending system's only when it is the client's own: no claim of
   a person's sign-in, an `idtyp` (when there) of `app`, and, when the installer sets it, the login system's own mark
   (`SDV_AUTH_JWT_MACHINE_CLAIM` and `..._VALUE`; Keycloak and Entra examples in SETUP.md). Else `SDV-AUT-005`.
2. **Body limits.** Every request body but a document upload is limited (1 MB; a submission 8 MB; both settings), by
   Content-Length and while it is read, before sign-in: `413 SDV-SYS-019` (new). The JSON reader has the same limit.
3. **Redaction reason.** New events hold the operation's ID, not the reason or the regions. Older events keep their
   rows; both fields are removed on the way out (audit page and export, document activity, sealed report's source).
4. **Storage folders.** `SDV_STORAGE_ALLOWED_ROOTS` (empty: only `data` under Vero's working folder); shares, device
   paths and links refused; one answer, `SDV-STO-008` (new), also for a folder another volume uses. In the runbook.
5. **Error answers.** Only coded messages reach the caller. Other exceptions keep their status and generic code with a
   fixed sentence; their text goes to one INFO line with the reference.
6. **References.** Always Vero's own. A caller's `X-Correlation-Id` is a separate field in log lines (`caller=`) and
   audit details (`callerReference`).
7. **Separation.** Business Admin with Document Steward, and IT Admin with any document role, are never held together
   (withheld and recorded as before).

Ingestion contract 1.2.0 (changelog, OpenAPI, sender profile): the reference, the 413 answer, the wider `SDV-AUT-005`.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` (final) | BUILD SUCCESS: 26 + 69 + 2 + 4 + 631 + 10 = **742**, 0 failures, 25 skipped; frontend builds. A first run failed to compile one test that implements the changed storage port; fixed |
| `npm test` | 66 passed |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 107 s |
| `.\Test-SDV-SchemaUpgrade.ps1` | PASS in 529 s (the new redaction check ran on PostgreSQL) |
| Live Keycloak check | A temporary client and a temporary person, both deleted afterwards (none left). The client's own token: 201. A person's token through the same client: `401 SDV-AUT-005`. The caller's reference is in the audit event, the answer's reference is Vero's. Two earlier tries failed in the check script itself; each cleaned up |
| `npm run ui:capture` | First: 29 passed, **1 failed**. Not this change: the demo seed's 24-hour print approval had expired at 03:53 UTC. After `Seed-SDV-Canvas-Demo.ps1 -Reset`: **30 passed, 0 failed, 16 skipped** |

## Tests: counts, and any failures with their output

13 new. Changed because an approved decision changed what they said: `SdvAccessRulesTest`,
`RoleSeparationAtSignInTest`, `LocalSignInRoleSeparationTest` (item 7), `ErrorPlumbingTest` (item 6),
`StorageFolderCheckTest` (one answer, item 4).

## Files changed

See the pull request.

## Commits

Task file; the fixes; this report. Merged through a PR on green `ci-passed`.

## Decisions taken

- **`acr` is not refused** (the task lists it): Keycloak puts `acr` in client-credentials tokens too (seen live), so
  refusing it would refuse every Keycloak sending system. `sid` and the others tell the two apart. Added `c_hash`,
  `s_hash`, `amr` and the groups overage marks.
- The setting is per install, which is per connection: an install has one login connection.
- `SDV-STO-008` and `SDV-SYS-019`: the next codes nobody has proposed for something else.
- Network shares are refused even when the installer lists one: the task says so. Volumes that exist keep working.
- The submission limit is 8 MB, far above a full document list, so nothing `/v1` accepts today is refused in practice.
- The body limit also covers requests without a sign-in. Uploads are found by their address, not their Content-Type.
- Generic codes (`BAD_REQUEST`, `CONFLICT`, `NOT_FOUND`) are kept: changing them would change the API.
- **Release tag** `sdv-ingestion-v1.2.0` is named in the changelog (a test asks for it) but **not pushed**: the queue
  approves branches and merges, not tags.

## Not done, and why

- The tag: `git tag -a sdv-ingestion-v1.2.0 <merge commit> -m "Ingestion contract 1.2.0"` and
  `git push origin sdv-ingestion-v1.2.0`, when the design chat or the owner agrees.
- Live checks left three empty test submissions at most in the local `sdv` database.

## Questions for the owner

1. Push the tag `sdv-ingestion-v1.2.0`?
2. Before an upgrade, installs that add volumes outside `data` must set `SDV_STORAGE_ALLOWED_ROOTS`. Acceptable as a
   release note?

## Suggested next step

0063.
