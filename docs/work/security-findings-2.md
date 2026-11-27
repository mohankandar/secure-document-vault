# Security findings: second independent review of Vero (task 0096)

Reviewed 2026-11-27 from the code, with tests and read-only checks. It covers everything since the first review (task
0061): the move to Spring Boot 4.2 and platform-core 1.3.0, customer keys, licensing, case fields and record search, the
erase log, notifications, approvals from messages, monitoring, the owner's scripts, libraries and CI. This is the
plain-terms list. The full review is kept outside the repository.

| Critical | High | Medium | Low | Info |
|---|---|---|---|---|
| 0 | 0 | 2 | 5 | 3 |

No way was found to sign in without a valid token, to read another entity's data, to open a document above one's
level, to decide an approval without the approver's permission, or to take in documents without a valid licence. No
library in use has a published advisory today. None of the fixes from the first review has come undone.

## Medium

| ID | In plain terms | Fix |
|---|---|---|
| VSEC-201 | The page an Approve or Decline link opens shows the waiting request (who asked, why, which record) to anyone on the entity's approver mailing list, even when that person may not approve. Only the final click checks the permission. | Check the approver's permission before anything is shown, and let "may approve" come from the person's role, not from the mailing list. |
| VSEC-202 | Searching a sensitive field for an exact value finds the matching record even for a person who may not open that record. They can't see the value, but they learn which record holds it. | Leave out of the answer every record the person may not open whenever the search names a sensitive field, so that "not found" and "not yours" look the same. |

## Low

| ID | In plain terms | Fix |
|---|---|---|
| VSEC-203 | Who gets e-mail is a list typed by an admin: any user ID and any address are accepted, and a person keeps getting messages and approval links after their role is gone. | Accept on a role's list only people who hold that role, and drop them when the role goes. |
| VSEC-204 | The address that every message links to is set on a screen and may be any site, even without https. A rogue IT Admin could point genuine messages at a look-alike sign-in page. | Take the address from the install configuration, or accept only the address people really sign in on, with https. |
| VSEC-205 | Vero's login to the key service never expires and works from any computer, and it may read more stored secrets than Vero needs. | Give the login an expiry and bind it to Vero's servers; narrow what it may read to Vero's own secrets; document the renewal. |
| VSEC-206 | Alerts for libraries with known weaknesses are switched off for the repository; only version updates run. | Switch the alerts on, or add an advisory check to CI. |
| VSEC-207 | The support bundle can include half of Vero's key-service login when the login file has an ordinary name. | Keep the key-service settings out of the bundle (and ask platform-core to choose the bundle's settings by origin, not by name). |

## Info

| ID | Topic |
|---|---|
| VSEC-208 | The first address of an approval link holds its one-time code, so it can reach the web server's access log. The code alone decides nothing; the IT guide should say to keep that path out of the logs. |
| VSEC-209 | The erase log's folder rights on Windows are left to the installer; the runbook should give the exact steps. |
| VSEC-210 | The licence counts seats but never limits them, by design; it stops only the intake of new documents. |

Two items belong to platform-core (how its approval check finds approvers, and how its support bundle chooses settings)
and go to its issue list.

## The first review's fixes

Every finding of task 0061 marked fixed still holds. VSEC-10 (library advisories) has none open today; VSEC-27
(platform-core version) is done with 1.3.0; VSEC-14 stays open as before.

## Status

| ID | Status | Where |
|---|---|---|
| VSEC-201 | Fixed (task 0100) | The link page checks the approver's permission before it looks anything up; platform-core's approver check is the signed-in person's role now, not the mailing list |
| VSEC-202 | Open | Task 0101 |
| VSEC-203 | Fixed as far as Vero can see roles (task 0100) | A list takes only people who held the role at their last sign-in; messages go only to them; a sign-in without the role takes them off (audited). A role taken away is seen at the person's next sign-in |
| VSEC-204 | Fixed (task 0100) | The app address comes from the install configuration (`SDV_PUBLIC_URL`, https); the screen shows it read-only |
| VSEC-205 | Open | Task 0102 |
| VSEC-206 | Open | Task 0102 |
| VSEC-207 | Open | Task 0102 |
| VSEC-208 | Done (task 0100) | Runbook and IT guide: the exact nginx and IIS lines that keep the link path out of access logs |
| VSEC-209 | Open | Task 0102 |
| VSEC-210 | By design | Recorded as a business decision |
