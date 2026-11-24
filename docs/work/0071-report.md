# Report 0071: Security fixes 4

Date: 2026-11-24
Branch: `fix/security-4` (from `release/vero-r7` at `16ed880`)
Outcome: DONE. Changeset 044. No new dependency. Codes `SDV-DOC-014`, `SDV-HLD-012`, `SDV-QUA-012`.

## What was done

1. **Print.** Highly Confidential pages of a print come in print quality only for an approved print of one's own, for 15
   minutes after it; each carries the person's name and the time. On screen they are drawn at screen quality at most, and
   marked when more was asked for. The guides say screenshots can't be stopped.
2. **Quarantine.** One person releases at most 25 documents of an incident one by one in 24 hours (`SDV-QUA-012`). Who
   started the incident sees each single release on it.
3. **Legal holds.** Request release, with a reason, goes to Approvals; a Business Admin who neither asked nor placed the
   hold approves it (`SDV-HLD-012`), on its own, never in bulk.
4. **Incident names** and reasons, the exposure report and its export: only for Stewards who can open at least one of the
   documents, and for who started the incident. For others the incident doesn't exist.
5. **System information** needs sign-in; profiles and the database's state only for IT Admin and Platform Operations.
6. **Intake rate** for each sending system: `SDV_INTAKE_REQUESTS_PER_MINUTE`, 6,000 unless set; above it `SDV-SYS-018`
   with `Retry-After`.
7. **IT guide:** how long a removed group, role mapping or grant keeps working.
8. `security-findings.md`, also VSEC-2, 11, 22 and 29, which earlier tasks fixed.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Command | Result |
|---|---|
| `.\Build-SDV-All.ps1` | BUILD SUCCESS: 43 + 72 + 2 + 4 + 657 + 10 = **788**, 0 failures, 26 skipped |
| `npm test`, `npm run typecheck` | 77 passed; pass |
| `.\Test-SDV-SchemaUpgrade.ps1` (from 043) | PASS in 498 s, with the hold release on PostgreSQL |
| `.\Test-SDV-CleanInstall.ps1` | PASS in 89 s |
| `npm run ui:capture` | **33 passed, 19 skipped**, with a new step. **On a scratch database** (see Decisions) |
| Demo seed, then `-Reset` with one hold too many (scratch database) | pass; the hold was released by two admins |
| `.\Test-SDV-DemoSeed.ps1` | **FAIL, not from this task**: it starts the release jar in local mode, which that jar refuses since task 0064. Its line for a hold release was adapted but has not run |

After the schema check and the full tour only the drawing of the mark changed (below); the build and the print step ran again after it.

## Tests: counts, and any failures with their output

New: `IntakeRateLimitTest`, `SystemInfoControllerTest`, and tests in the viewer, quarantine, print and held-redaction
(PostgreSQL) classes. Adjusted because the behaviour changed: `LegalHoldControllerTest` (release is a request),
`QuarantineServiceTest` (one test released more than 25 as one person; now each by another), the open-address, checksum
and request-shape baselines (one line each), the clean-install check (system information without sign-in is 401; it checks
readiness and the log for the rest), tour steps 0030, 0049 and 0066.

On the way: the tour failed once in step 0049, which expected a Steward to see an incident of which they can open nothing
(that is item 4). The first printed page showed overlapping letters in the mark; fixed, with a test.

## Files changed

See the pull request.

## Commits

Task file; the change with this report. Merged through a PR on green `ci-passed`.

## Decisions taken

- A release needs an approver who neither asked nor placed the hold. The placer may ask, and may decline. With two
  Business Admins: the placer asks, the other approves. One waiting release for each hold.
- "A day" is the last 24 hours, counted under the incident's lock.
- The single releases shown to the creator: the newest 200; a number only where they can open the document.
- A sealed original is drawn as Highly Confidential, whatever the document's level, and never for a print.
- The intake limit counts in each Vero process, not across several.
- System information needs a person even when the database is away, so Health may not show its sentence then; readiness
  and the log do.
- **The tour ran on a scratch database**: `sdv` is at changeset 042 and this release needs 044. `sdv` was not touched.
- `Start-SDV-Local.ps1` still prints the address of system information; left as it is (CLAUDE.md).

## Open points and questions for the design chat

1. The owner's schema step on `sdv` now also applies 043 and 044.
2. Repair `Test-SDV-DemoSeed.ps1` (local jar and schema step)? It is small, and outside this task.
3. An entity with one Business Admin can't release holds. Should Vero warn about it?
