# Report 0082: approvals from messages (integration I)

Date: 2026-11-27
Branch: `integration/i-approvals` (from `release/vero-r7` at eb30f0e)
Outcome: **DONE**

## What was done

0. **The tour left open by 0081** ran first (the owner unsealed the Vault): the role screens and the smoke steps, with the two Notifications
   tabs. Their screenshots are in this PR's description. The tour now also waits for each Notifications tab's panels (it had captured
   "Loading…").
1. **Request types** (platform-core's `ApprovalType`): print, download and lowering one document's level, the three decided on the link page.
   Their links live as long as a request waits (`sdv.approvals.pending-days`), at most 30 days (the module's limit). Lowering a whole record, a
   redaction on hold, a hold release and an incident release are decided in their own review (item 4): their "Approval waiting" links to it,
   without Approve and Decline.
2. **The link page** is Approvals: an Approve or Decline link (`/approvals/link/<token>`) opens Vero; the token waits in the tab's session
   storage and leaves the address at once; after sign-in, Approvals shows the request at the top, what the list shows for it, and one button.
   Opening decides nothing. The token goes to Vero in a request body, never in an address, so no log of Vero's holds it.
3. **Vero is the one truth:** the button decides through `AccessRequestService` with all its checks and audit (never one's own request, the
   approver's permission now). Every decision made in Vero, on the link page or in Approvals, then closes the link's request with
   `Approvals.decidedElsewhere` (platform-core 1.3.0); from then on every link says "already decided". Decided in Vero first: the page says
   so and changes nothing. Changeset 050: `access_requests.link_request_id` keeps the pair (Q-4).
4. See 1.
5. **Refused links** (not valid, someone else's, expired, the role gone, the request gone) are audited through `ApprovalAudit`
   (`APPROVAL_LINK_USED`, with the code), never the token or the link. A link of another entity: `SDV-NTF-008`.
6. **Nobody who may not decide gets a link:** links go to each Business Admin on their own, by e-mail only; when the requester is on the
   entity's Business Admin list, no links go (the plain message does). Hold releases (the placer) are decided in their review.

## No other product's name or behaviour in code: yes

`ProductNameGuardTest` passes.

## Commands run and results

| Check | Result |
|---|---|
| `.\Build-SDV-All.ps1` | 42 + 72 + 2 + 750 + 14 = 880 tests, 0 failures, 57 skipped (database or key service; run below) |
| `npm test`, `npm run typecheck` | 103 passed (2 new); clean |
| `.\Test-SDV-CleanInstall.ps1` | PASS, 121 s |
| `.\Test-SDV-SchemaUpgrade.ps1 -Forks 1` | PASS, 740 s (over 5 minutes: the scale tests, as before); 050 checked; all PostgreSQL tests |
| `.\Test-SDV-DemoSeed.ps1 -WithTour -TourSteps "<role screens, no-access, without a session>"` | PASS, 369 s; 7 tour steps passed, the two Notifications tabs with their panels (`docs/work/0082-screens/`) |
| Mail sink (`MailSinkTest`) | the link e-mail through platform-core's real SMTP sender to a sink on 127.0.0.1: one per Business Admin, Approve and Decline each, four different links, no record |
| `ApprovalLinksTest`, `ApprovalLinkControllerTest` | 10 tests: own links per person, never the requester, opening decides nothing, the button decides through Vero, decided in Vero first, refusals audited without the token, another entity's link refused |

**Failed on the way:** the first tour: the seed's "second run changes nothing" saw `notif_condition` rows (the running Vero's watch); the
check now leaves out `notif_` tables. The second: the tour's Vite served my tree mid-rename (two files differing only in capitals), so the
app didn't load; renamed (`approvalLinkWords.ts`), and run again. The tour then captured the Notifications tabs while loading: it now waits
for their panels. The upgrade check first expected `tenants.time_zone` at the previous version, which now includes 049.


## Tests whose assertions changed, and why

| Test | Why |
|---|---|
| `Test-SDV-DemoSeed.ps1` (seed run 2 changes nothing) | Leaves out the `notif_` tables: the running Vero's watch and licensing notices write them on their own (found by this tour) |
| The upgrade script | 050; `tenants.time_zone` at the previous version follows 030 and 049 |
| `ChangesetChecksumBaselineTest`, `JsonShapesBaselineTest` | One new changeset line; one new one-component request record (`LinkRequest`) |

## Decisions taken

- **Q-4, option A:** one nullable column on `access_requests` with a unique index; no change of rights.
- **No record identifier in a link e-mail:** platform-core puts it in every channel of the event; Approvals shows it after sign-in.
- **Links by e-mail only:** the request event is Vero's e-mail type, which never has a channel (0081), so Teams and Slack get no links.
- **A requester on the Business Admin list gets no links** (fail closed): the plain message goes instead.
- **Declining needs no reason** in Vero (as in Approvals); the link's request records "Declined in Vero." as the module needs one.
- **The approver check** is the module's default (the Business Admin list); Vero's own permission check runs again on the button.
- The page is Approvals, not a page of its own: the link opens Business administration › Approvals, where approvers work anyway.

## Files changed

(see the PR)

## Commits

(see the PR)

## Not done, and why

Nothing.

## Questions for the owner

None.

## Suggested next step

0096 (second security review).
