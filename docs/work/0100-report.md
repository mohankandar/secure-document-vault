# Report 0100: Security fixes 5: approval links and recipient lists (VSEC-201, 203, 204, 208)

Date: 2026-11-27
Branch: fix/security-5-approvals
Outcome: DONE

## What was done

1. **VSEC-201.** The link page (`ApprovalLinkController`) checks `APPROVE_SENSITIVE_ACCESS` first, before it opens the
   link or looks anything up. Someone who may not decide gets only "This request isn't yours to decide" (code
   `NOT_AN_APPROVER`: no who, why, which record, action or decider), and the attempt is audited as a refused link without
   the token. Vero now has its own `ApproverCheck` bean (`SignedInApprover`): platform-core asks it on open and decide, and
   it says yes only for the signed-in person themselves, in that entity of that customer, whose roles there give the
   approver's permission now. It no longer falls back to the mailing list.
2. **VSEC-203.** New table `role_holders` (changeset 051): which list role a person held in which entity, and when they
   were last seen with it at a sign-in. It holds user IDs and roles only, no names and no addresses. A filter after
   sign-in writes it when the roles change, and at most every 10 minutes otherwise.
   - A role's list accepts only someone who holds that role. The IT Admins' list accepts an IT Admin of any of the
     customer's entities. Anyone else is refused with SDV-NTF-002: "Ask them to sign in once, then add them."
   - When a sign-in shows a role gone, the person is taken off every list of that role in the same transaction. Each
     removal is audited as `NOTIFICATION_RECIPIENT_REMOVED` with `reason: ROLE_GONE`. A person leaves the IT list only
     when no entity shows them as IT Admin any more.
   - Messages and approval links go only to list entries whose person is confirmed. Confirmed means seen with the role
     at a sign-in in the last 30 days (`SDV_NOTIFICATIONS_ROLE_SEEN_DAYS`, 7 to 90; answer Q-5).
   - Both list screens mark the other entries **Not confirmed**.
3. **VSEC-204.** The app address of every message comes from the install configuration only: `sdv.public-url`
   (`SDV_PUBLIC_URL`). It must be https with no user, query or `#`; plain http is allowed only in a local start, which
   defaults to `http://localhost:5183`. A wrong value stops the start (SDV-SYS-005). If it is not set, Vero starts but
   sends nothing, and the screen says so. IT âº Notifications shows the address read-only and has a **Connect** button
   only; the edit is removed, and `PUT /it` no longer reads an address. At each start, a customer connected under
   another address is moved to the install's. `Start-SDV-Testers.ps1` sets `SDV_PUBLIC_URL=https://<its host>`.
4. **VSEC-208.** The runbook ("Notifications", 4c) and the IT guide give the exact lines that keep `/approvals/link/`
   out of access logs: an nginx `location` block with `access_log off`, and the IIS `appcmd ... httpLogging
   /dontLog:"True"` line. They also say how to check it, and that a proxy in front of Vero keeps its own log.
5. `security-findings-2.md`: a Status table was added (201, 203 and 204 fixed; 208 done; the rest open for 0101 and 0102;
   210 by design). Guides: the business admin guide covers the lists rule; the IT guide covers the address, the links,
   the lists and the limit; the runbook lists the two new settings; `error-codes.md` updates SDV-NTF-005.

## Decisions taken

- **VSEC-203: how Vero knows roles** (Q-5, answered A plus the 30-day rule). Vero has no user directory: roles come
  from each sign-in's groups. A role taken away is seen at the person's next sign-in, or after 30 days without one.
- **"One-time clean-up in the changeset"**: no list row is deleted by the changeset. Nobody's roles are known at
  upgrade time, so no row could be checked, and Liquibase can't write the audit (a deletion would go unaudited and would
  empty every list). The existing rows instead get nothing until their person signs in with the role, and the screens
  mark them Not confirmed. Q-5's answer agreed: "existing rows get nothing until confirmed".
- **"Dev profile"** in item 3 is Vero's `local` profile, the only development profile it has.
- SDV-NTF-005 stays 400 for "the install has no app address" (Connect), as documented.
- The `AddressRequest` body record is gone, so its line came out of the request-shapes baseline. Changeset 051's
  checksum was added to the checksum baseline.

## No other product's name or behaviour in code: yes

## Commands run and results

| Command | Result |
|---|---|
| `mvn -o -pl sdv-web -am -Dtest=<notifications, baselines> test` (several times while working) | PASS, last run: 74 tests, 0 failures, 1 skipped (the Postgres test, which runs only in the schema check) |
| `npm run typecheck`, `npm test` (frontend) | PASS, 104 tests |
| `Build-SDV-All.ps1` (first run) | FAIL: `JsonShapesBaselineTest`, the removed `AddressRequest` was still in the baseline. Fixed (above) |
| `Build-SDV-All.ps1` (after the fix and the 30-day rule) | PASS: backend BUILD SUCCESS (sdv-web 774 tests, 0 failures, 54 skipped), frontend built |
| `Test-SDV-SchemaUpgrade.ps1 -Forks 1` (`-From 051`, the new default) | First run FAIL: "a table other than documents and record_sensitivity_levels changed" (the new `role_holders` was not left out of the unchanged tables; script fixed). Second run PASS, 768 s, incl. "4zc. After 051" and every Postgres test with `RoleHoldersPostgresTest` (step 4f, 743 s: over 5 minutes, as before) |
| `Test-SDV-CleanInstall.ps1` | PASS, 160 s |
| `Test-SDV-DemoSeed.ps1 -WithTour -TourSteps "Business Administrator screens|IT Administrator screens|Approvals, legal holds|no-access page|Without a session"` | PASS, 381 s; 5 tour steps passed. IT › Notifications shows the address read-only with Connect (`docs/work/0100-screens/notifications-it.png`) |
| PowerShell parse of `Start-SDV-Testers.ps1` | 0 errors |

## Tests: counts, and any failures with their output

New: `SignedInApproverTest` (4), `ApprovalLinkControllerTest.someoneWhoMayNotDecideSeesNothingOfTheRequest`,
`ApprovalLinksTest.withVerosApproverCheckAListedPersonWithoutTheRoleNowIsRefused`, `PublicUrlTest` (4),
`RoleHolderServiceTest` (4), `RecipientListServiceTest` (+2), `ListRecipientsTest` (4, including 31 days unseen gets
nothing), `RoleHolderFilterTest` (2), `RoleHoldersPostgresTest` (1, in the schema check), and frontend
`notificationWords.test.ts` (+2). Existing tests were changed only for the new constructors and fixtures. No assertion
was weakened; the one removed assertion tested the screen's address edit, which no longer exists.

## Files changed

Backend: `ApprovalLinkController`, `SignedInApprover` (new), `VeroNotificationsConfiguration`, `PublicUrl` (new),
`NotificationSettingsService`, `NotificationsController`, `ListRecipients`, `RoleHolderFilter` (new),
`RecipientListService`, `RoleHolderService` (new), `RoleSeenWindow` (new), `RoleHolderRepository` (new port),
`NotificationRecipientRepository` (`deleteRole`), `JdbcRoleHolderRepository` (new), changeset `051-role-holders`,
`application.yml`, and their tests and baselines. Frontend: `NotificationsIt.tsx`, `NotificationParts.tsx`,
`notificationWords.ts` (+ test), `liveApi.ts`. Scripts: `Test-SDV-SchemaUpgrade.ps1` (051 and its checks),
`Start-SDV-Testers.ps1`. Docs: runbook, IT guide, business admin guide, `error-codes.md`, `security-findings-2.md`.

## Commits (hash and message)

See the PR. The branch's commits: task file; VSEC-201; VSEC-204 and 208; VSEC-203; baseline and statuses; the
30-day rule; this report.

## Not done, and why

- The local `sdv` database is not moved to 051 by this task. The tour runs on the seed check's scratch database.

## Questions for the owner

None.

## Suggested next step

0101 (VSEC-202, sensitive-field search).
