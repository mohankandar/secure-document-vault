# Report 0096: second independent security review (findings only)

Date: 2026-11-27
Branch: `review/security-findings-2` (from `release/vero-r7` at 30a1906). The full review is on the local branch `review/security-2`
(6fbad1a), never pushed, as in 0061.
Outcome: **DONE**

## What was done

The review ran in a fresh subagent given only this task file and `CLAUDE.md`, read only: it changed no code, touched no container, the Vault
or a database, and didn't start the app. It read the code since 0061 (b6a05fe), ran tests and read-only checks, and re-checked the fixes of
the first review. The plain-terms list is `docs/work/security-findings-2.md`.

| Critical | High | Medium | Low | Info |
|---|---|---|---|---|
| 0 | 0 | 2 | 5 | 3 |

No way was found to sign in without a valid token, read another entity's data, open a document above one's level, decide an approval without
the permission, or take in documents without a valid licence. Every fixed finding of 0061 still holds; VSEC-14 stays open.

**VSEC-201 is in code of task 0082 (mine, merged today):** the link page shows the waiting request before it checks the permission to approve,
and the approver check is platform-core's default (the mailing list). Its fix is the first task below.

Two items belong to platform-core and are in `C:\dev\sdv\queue\PLATFORM-ISSUES.md` (P-1 the default approver check, P-2 how the support bundle
picks settings); neither is worked around inside platform-core.

## No other product's name or behaviour in code: yes

Nothing in the product changed; `ProductNameGuardTest` passes in CI.

## Commands run and results (by the reviewer)

| Check | Result |
|---|---|
| 51 named test classes of sdv-web (sign-in, approval links, licensing, health, erase log, limits, tunnel, role separation, crypto binding, reports, PDF), three runs | 335 tests, 0 failures |
| `npm audit --omit=dev --json`, `npm audit --json` | 0 advisories |
| OSV query for the 63 public Maven libraries in the built jar (public names and versions only; a known-bad control returned its advisories) | 0 advisories |
| `gh api …/dependabot/alerts` | 403: alerts are switched off (VSEC-206) |

## Fixes, grouped into tasks of about a day (the design chat writes them)

1. **Approval links and recipient lists** (VSEC-201, VSEC-203, VSEC-204, VSEC-208): Vero's own `ApproverCheck` from the person's roles in
   identity; the link page checks the permission before it shows anything; a role's list accepts only people who hold the role and drops them
   when it goes; the app address from the install config (or only the sign-in address, https); the runbook says to keep the link path out of
   access logs.
2. **Sensitive-field search** (VSEC-202): when a search names a sensitive field, leave out every record the person may not open, so "not
   found" and "not yours" look the same; a PostgreSQL test with two levels.
3. **Key-service login, support bundle, advisories, erase-log folder** (VSEC-205, VSEC-207, VSEC-206, VSEC-209): the setup script gives the
   AppRole secret an expiry, a use limit and an address binding, and the app policy reads only Vero's own secrets (owner runs the script on
   the real Vault); Vero's bundle leaves key-service settings out; an advisory check in CI (switching Dependabot alerts on is a GitHub setting:
   owner); the runbook's Windows steps for the erase log folder's rights.

VSEC-210 (seats are counted, not limited) is by design; nothing to do.

## Not done, and why

Nothing; fixes are for the tasks above.

## Questions for the owner

- VSEC-206: switching Dependabot alerts on is a repository setting (yours). Until then task 3 adds a check in CI.

## Suggested next step

0097 (install proof), then the fix tasks above.
